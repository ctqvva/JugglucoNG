package tk.glucodata.data.journal

import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.JournalTreatmentUploadBridge
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.NightscoutTokenGrant
import tk.glucodata.NightscoutUploadWake
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends Kotlin Journal entries to Nightscout as treatments. Replaces the legacy C++
 * uploadtreatments() path that pulled
 * from Numdata. Invoked from the native upload loop via NightPost.
 *
 * Sync state is tracked per-row on JournalEntryEntity (nsUploadedAt, nsRemoteId);
 * deletes are queued in journal_pending_deletes so they survive process death, and so is what
 * is on the way (journal_nightscout_uploads). [NightscoutUploadLedger] does that bookkeeping.
 */
@Keep
object JournalTreatmentUploader : JournalTreatmentUploadBridge {
    private const val LOG_ID = "JournalTreatmentUploader"
    private const val ID_PREFIX = "jng-j-"
    private const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000  // mirrors C++ nighttimeback (30 days)
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_RECEIVE_TREATMENTS = "nightscout_receive_treatments"
    private const val PREF_SEND_LONG_INSULIN = "nightscout_send_long_insulin"
    private const val TREATMENT_FETCH_COUNT = 240
    private const val FETCH_WINDOW_MILLIS = 2_000L
    private const val ERROR_INVALID_URL = -2
    private const val SEND_BACKOFF_FIRST_MILLIS = 60_000L
    private const val SEND_BACKOFF_MAX_MILLIS = 30L * 60_000L
    private const val RECEIVE_ERROR_LOG_INTERVAL_MILLIS = 5L * 60 * 1000
    private const val RECEIVE_MIN_INTERVAL_MILLIS = 5L * 60 * 1000
    private const val DELETE_RETRY_IMMEDIATE_DELAY_MILLIS = 1_000L

    /**
     * Outcome for a pending deletion tombstone. Unresolved tombstones are never abandoned after a
     * fixed attempt cap: transient outages use capped exponential backoff, while auth and config
     * refusals back off slowly for eventual server-side recovery and resume immediately when
     * credentials/settings change or the user requests a retry.
     */
    internal enum class TombstoneAction { CLEAR, RETRY_TRANSIENT, RETRY_AUTH, RETRY_CONFIG }

    private class HttpStatusException(
        val code: Int,
        val serverMessage: String,
        detail: String,
    ) : IllegalStateException(detail)

    private data class DeleteOutcome(
        val action: TombstoneAction,
        val code: Int,
        val message: String = "",
    )

    internal var clockMillis: () -> Long = { System.currentTimeMillis() }

    /** Optional hook for unit tests to observe coalesced deferred delete wakes without a Looper. */
    internal var deleteRetryScheduler: ((delayMillis: Long) -> Unit)? = null

    private val transientRecoveryVersion = AtomicLong(0L)
    private val manualResetVersion = AtomicLong(0L)
    private val pendingTransientRelease = AtomicBoolean(false)
    private val pendingFullRelease = AtomicBoolean(false)
    private val deleteRetryScheduleLock = Any()
    private var scheduledDeleteRetryAt = 0L
    private var scheduledDeleteRetryRunnable: Runnable? = null

    private data class UploadResult(
        val code: Int,
        val remoteId: String? = null,
        /** What the server said when it refused, so a 403 can name the missing permission. */
        val message: String = ""
    )

    /**
     * The server's own words when it has any. A refusal says which permission is missing
     * ("Missing permission api:treatments:update" on a role that may create but not change,
     * "...:read" on an upload-only token), and that sentence is the whole difference between
     * an actionable failure and a bare status code.
     */
    internal fun serverMessage(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed.take(160)
        val message = runCatching {
            JSONObject(trimmed).let { it.optNonBlank("message") ?: it.optNonBlank("description") }
        }.getOrNull()
        return message ?: trimmed.take(160)
    }

    private fun JSONObject.optNonBlank(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() }

    /** "code" alone, or "code: what the server said" when it said anything. */
    internal fun failureText(code: Int, message: String): String =
        if (message.isBlank()) code.toString() else "$code: $message"

    /**
     * The rule of the re-upload path: a failed write must never leave the server with less
     * data than it had. An edited entry used to go out as delete-then-POST; when the POST was
     * refused (a token without api:treatments:update answers 403) the delete had already gone
     * through, and the entry was not stale on Nightscout but gone. So the new document is
     * written first, and only once it has been accepted is the old copy surplus -- and only
     * when it is a different document. On v3 the re-upload carries the old identifier and is
     * an update of that document, so there is nothing old left to delete; on v1 the server
     * answers with the _id it stored under, which is the old one when it upserted.
     */
    internal enum class OldCopyAction { KEEP, DELETE }

    /** Whether a v3 write creates a document or changes one the server already holds. */
    internal enum class TreatmentWrite { CREATE, UPDATE }

    /**
     * What a v3 document is named. The time is part of the name because v3 will not let a
     * client move a document's date: an entry whose time was corrected is a different
     * document, and saying so in the identifier is what turns that write into a create the
     * server accepts, after which the old copy is deleted as any other stale copy is.
     */
    internal fun datedIdentifier(entryId: Long, timestampMillis: Long): String =
        ID_PREFIX + entryId.toString(16) + "-" + timestampMillis.toString(16)

    /** What a v1 document carries as its identifier; the server names it with an `_id` of its own. */
    internal fun localIdentifier(entryId: Long): String = ID_PREFIX + entryId.toString(16)

    private val localIdentifierPattern = Regex("^$ID_PREFIX([0-9a-f]{1,15})$")
    private val datedIdentifierPattern = Regex("^($ID_PREFIX([0-9a-f]{1,15})-([0-9a-f]{1,15}))(?:-[a-z]+)?$")

    internal fun entryIdOfLocalIdentifier(identifier: String): Long? =
        localIdentifierPattern.find(identifier)?.groupValues?.get(1)?.toLong(16)

    /** The time a dated identifier names, or null for any other id. */
    internal fun timestampOfDatedIdentifier(identifier: String): Long? =
        datedIdentifierPattern.find(identifier)?.groupValues?.get(3)?.toLong(16)

    /**
     * A name for the same entry and time under another event type. Like the date, v3 does not
     * let a client change a document's eventType (400), so an insulin entry corrected to carbs,
     * or a meal that crossed the line between the two carb types, is a different document.
     */
    internal fun typedIdentifier(datedIdentifier: String, eventType: String): String =
        datedIdentifier + "-" + eventType.lowercase(Locale.US).filter { it in 'a'..'z' }

    /** What a v3 write is named: the typed name only where the server already holds that one. */
    internal fun v3Identifier(nsRemoteId: String?, datedIdentifier: String, eventType: String): String =
        typedIdentifier(datedIdentifier, eventType).takeIf { it == nsRemoteId } ?: datedIdentifier

    /** v3 refused a write because the document there has another eventType. */
    internal fun isEventTypeRefusal(code: Int, message: String): Boolean =
        code == HttpURLConnection.HTTP_BAD_REQUEST && message.contains("eventType")

    /**
     * Whether a failed write or delete is known to have changed nothing. A refusal is; no
     * answer, or the server failing half way, is not, and has to be reckoned with.
     */
    internal fun changedNothing(code: Int): Boolean =
        code == ERROR_INVALID_URL ||
            code == NightPost.ERROR_CONFIG ||
            (code in 400..499 && code != HttpURLConnection.HTTP_CLIENT_TIMEOUT)

    /**
     * An update only where the server already holds this exact document; anything else is a
     * create. Entries written before the time was part of the name land here too, once each:
     * they are created afresh under the new name and their old copy is then removed.
     */
    internal fun treatmentWrite(nsRemoteId: String?, identifier: String): TreatmentWrite =
        if (nsRemoteId != null && nsRemoteId == identifier) TreatmentWrite.UPDATE else TreatmentWrite.CREATE

    internal fun oldCopyAction(oldRemoteId: String?, acceptedRemoteId: String?): OldCopyAction =
        if (oldRemoteId == null || oldRemoteId == acceptedRemoteId) OldCopyAction.KEEP else OldCopyAction.DELETE

    /**
     * Holds off a re-attempt of an entry that just failed. The uploader is woken by every
     * journal change and the trace showed the same refused entry knocking three times in
     * twelve seconds; a refusing server does not change its mind that fast. The hold doubles
     * from [firstDelayMillis] up to [maxDelayMillis] while the same entry keeps failing, and
     * ends with the next accepted write. Per entry, so a new entry behind it is not held for
     * an old one's sins -- though the loop still stops at the first failure, as it always has.
     */
    internal class SendBackoff(private val firstDelayMillis: Long, private val maxDelayMillis: Long) {
        private var entryId: Long? = null
        private var delayMillis = 0L
        private var notBeforeMillis = 0L

        /** True while the entry's last failure is still cooling off. */
        fun shouldHold(entryId: Long, nowMillis: Long): Boolean {
            if (entryId != this.entryId) return false
            // A clock that jumped back must not hold the entry for longer than one delay.
            return nowMillis < notBeforeMillis && nowMillis >= notBeforeMillis - delayMillis
        }

        fun recordFailure(entryId: Long, nowMillis: Long) {
            delayMillis = if (entryId == this.entryId && delayMillis > 0L) {
                minOf(delayMillis * 2, maxDelayMillis)
            } else {
                firstDelayMillis
            }
            this.entryId = entryId
            notBeforeMillis = nowMillis + delayMillis
        }

        /** An accepted write ends the episode. */
        fun reset() {
            entryId = null
            delayMillis = 0L
            notBeforeMillis = 0L
        }
    }

    private val sendBackoff = SendBackoff(SEND_BACKOFF_FIRST_MILLIS, SEND_BACKOFF_MAX_MILLIS)
    /** True only when the server actually removed the document for us. */
    internal fun isDeleteAccepted(code: Int): Boolean =
        code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_NO_CONTENT

    /**
     * What to do with a tombstone after a delete answered [code]. Only confirmed deletion
     * (200/204) or confirmed remote absence (404/410) clears a tombstone; all other failures
     * preserve it with capped exponential backoff.
     */
    internal fun tombstoneAction(code: Int): TombstoneAction =
        when (NightscoutDeleteRetry.failureKindForDeleteCode(code)) {
            null,
            DeleteFailureKind.NONE -> TombstoneAction.CLEAR
            DeleteFailureKind.TRANSIENT -> TombstoneAction.RETRY_TRANSIENT
            DeleteFailureKind.AUTH -> TombstoneAction.RETRY_AUTH
            DeleteFailureKind.CONFIG -> TombstoneAction.RETRY_CONFIG
        }

    private fun actionForLookupFailure(code: Int): TombstoneAction =
        when (NightscoutDeleteRetry.failureKindForLookupCode(code)) {
            DeleteFailureKind.AUTH -> TombstoneAction.RETRY_AUTH
            DeleteFailureKind.CONFIG -> TombstoneAction.RETRY_CONFIG
            DeleteFailureKind.TRANSIENT,
            DeleteFailureKind.NONE -> TombstoneAction.RETRY_TRANSIENT
        }

    /**
     * Keeps an unchanged, repeating failure to one line per interval. The sync retries far
     * faster than a stuck server recovers, and the identical line every few seconds is what
     * made the trace unreadable while a Nightscout receive failure was being diagnosed.
     */
    internal class RepeatedErrorLog(private val intervalMillis: Long) {
        private var lastMessage: String? = null
        private var lastLoggedAt = 0L
        private var suppressed = 0

        /**
         * @return how many repeats were swallowed since the last line, or -1 to stay silent.
         *         A changed message always speaks, so a new failure is never hidden behind an
         *         old one's interval.
         */
        fun suppressedSince(message: String, nowMillis: Long): Int {
            val elapsed = nowMillis - lastLoggedAt
            val due = message != lastMessage || lastLoggedAt == 0L ||
                elapsed >= intervalMillis || elapsed < 0
            if (!due) {
                suppressed++
                return -1
            }
            val repeats = if (message == lastMessage) suppressed else 0
            lastMessage = message
            lastLoggedAt = nowMillis
            suppressed = 0
            return repeats
        }

        /** A success ends the episode, so the next failure reports immediately. */
        fun reset() {
            lastMessage = null
            lastLoggedAt = 0L
            suppressed = 0
        }
    }

    private val receiveErrorLog = RepeatedErrorLog(RECEIVE_ERROR_LOG_INTERVAL_MILLIS)

    /**
     * Lower bound on how often treatments are read back. The read is the newest 240 documents
     * every time (a few hundred KB on a busy site), and it runs on every treatment pass, so
     * anything that makes passes frequent multiplies it directly: the receive/wake loop did
     * about one pass every two seconds, tens of MB in ten minutes. Nightscout gives no way to
     * ask only for what changed that covers v1-written documents (they carry no srvModified,
     * so v3 history misses them, and a created_at window misses back-dated entries), so the
     * rate is what gets bounded. A pass inside the interval is not dropped: one wake is
     * booked for when the interval ends, so a remote change still arrives without waiting
     * for the next local edit.
     *
     * Keyed by server and API version, so pointing the app at a different site reads it at once.
     */
    internal class ReceiveFloor(private val intervalMillis: Long) {
        private var lastKey: String? = null
        private var lastReadAt = 0L

        /** 0 when a read may go now, otherwise how long until it may. */
        fun waitMillis(key: String, nowMillis: Long): Long {
            if (key != lastKey || lastReadAt == 0L) return 0L
            val elapsed = nowMillis - lastReadAt
            if (elapsed < 0 || elapsed >= intervalMillis) return 0L
            return intervalMillis - elapsed
        }

        /** Only a read that succeeded starts the interval; a failure is the native backoff's. */
        fun recordRead(key: String, nowMillis: Long) {
            lastKey = key
            lastReadAt = nowMillis
        }
    }

    private val receiveFloor = ReceiveFloor(RECEIVE_MIN_INTERVAL_MILLIS)
    private val deferredReceiveBooked = AtomicBoolean(false)

    private fun bookDeferredReceive(delayMillis: Long) {
        if (!deferredReceiveBooked.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).postDelayed({
            deferredReceiveBooked.set(false)
            runCatching { Natives.waketreatments() }
                .onFailure { Log.e(LOG_ID, "deferred receive wake failed: ${Log.stackline(it)}") }
        }, delayMillis)
    }

    /** Coalesces deferred delete retries onto one main-looper callback (no AlarmManager or wakelock). */
    private fun scheduleDeferredDeleteWake(targetAtMillis: Long, nowMillis: Long) {
        if (targetAtMillis <= 0L) {
            cancelDeferredDeleteWake()
            return
        }
        val delayMillis = (targetAtMillis - nowMillis).coerceAtLeast(DELETE_RETRY_IMMEDIATE_DELAY_MILLIS)
        val customScheduler = deleteRetryScheduler
        synchronized(deleteRetryScheduleLock) {
            if (scheduledDeleteRetryAt > nowMillis && scheduledDeleteRetryAt <= targetAtMillis) return
            cancelDeferredDeleteWakeLocked()
            scheduledDeleteRetryAt = targetAtMillis
            if (customScheduler != null) {
                customScheduler(delayMillis)
                return
            }
            val looper = Looper.getMainLooper() ?: run {
                scheduledDeleteRetryAt = 0L
                return
            }
            val runnable = Runnable {
                synchronized(deleteRetryScheduleLock) {
                    scheduledDeleteRetryAt = 0L
                    scheduledDeleteRetryRunnable = null
                }
                NightscoutUploadWake.afterJournalChange()
            }
            scheduledDeleteRetryRunnable = runnable
            Handler(looper).postDelayed(runnable, delayMillis)
        }
    }

    private fun cancelDeferredDeleteWake() {
        synchronized(deleteRetryScheduleLock) { cancelDeferredDeleteWakeLocked() }
    }

    private fun cancelDeferredDeleteWakeLocked() {
        val runnable = scheduledDeleteRetryRunnable
        scheduledDeleteRetryRunnable = null
        scheduledDeleteRetryAt = 0L
        if (runnable != null) {
            Looper.getMainLooper()?.let { Handler(it).removeCallbacks(runnable) }
        }
    }

    @Keep
    override fun onConnectivityRecovered() {
        transientRecoveryVersion.incrementAndGet()
        pendingTransientRelease.set(true)
        cancelDeferredDeleteWake()
    }

    @Keep
    override fun onDeleteRetryReset(wakeIfPending: Boolean) {
        manualResetVersion.incrementAndGet()
        pendingFullRelease.set(true)
        cancelDeferredDeleteWake()
        sendBackoff.reset()
        if (wakeIfPending && JournalSyncStatus.state().hasUnresolvedDeletes) {
            NightscoutUploadWake.afterJournalChange()
        }
    }

    /** Diagnostic snapshot of unresolved Nightscout deletions for the active server from Room. */
    suspend fun unresolvedDeleteDiagnostics(): DeleteDiagnostics {
        val dao = HistoryDatabase.getInstance(Applic.app).journalDao()
        return NightscoutDeleteRetry.summarize(
            allTombstones = dao.getPendingNightscoutDeletes(),
            currentServer = NightscoutDeleteRetry.currentTargetServer(),
            nowMillis = clockMillis(),
        )
    }

    /** Simulates process restart in unit tests: drops in-memory state while keeping Room & prefs. */
    internal fun resetRuntimeStateForTesting() {
        sendBackoff.reset()
        receiveErrorLog.reset()
        deferredReceiveBooked.set(false)
        pendingTransientRelease.set(false)
        pendingFullRelease.set(false)
        cancelDeferredDeleteWake()
        JournalSyncStatus.resetCacheForTesting()
    }

    /** Path only: the host is already known and repeating it just crowds the line. */
    internal fun endpointPath(endpoint: String): String =
        runCatching { URL(endpoint).path }.getOrNull()?.takeIf { it.isNotBlank() } ?: endpoint

    // Mirrors writetreatment(V3) acceptance: 200/201 always; 409 only on V3 (POST conflict).
    private fun isUploadOk(code: Int, useV3: Boolean): Boolean {
        if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) return true
        if (useV3 && code == HttpURLConnection.HTTP_CONFLICT) return true
        return false
    }

    /**
     * One pass at a time, whichever side asked. Two at once would both read the same pending
     * rows and post each of them twice.
     */
    private val passLock = Mutex()

    @Keep
    override fun uploadAll(useV3: Boolean): Boolean = runBlocking {
        try {
            passLock.withLock {
                // Both switches are journal options, hidden while the journal is off.
                val journal = tk.glucodata.JournalEnabled.isOn(Applic.app)
                uploadInternal(useV3, journal && Natives.getpostTreatments(), journal && getReceiveTreatments())
            }
        } catch (th: Throwable) {
            Log.e(LOG_ID, "uploadAll failed: ${Log.stackline(th)}")
            false
        }
    }

    private val followerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NightscoutFollowerTreatments").apply { isDaemon = true }
    }
    private val followerPassQueued = AtomicBoolean(false)

    /**
     * A follower's send pass. Follow mode runs no native uploader, so this has its own thread;
     * a failure is retried by the next follower poll, and [sendBackoff] still keeps a refused
     * entry from being knocked on every time. Send only: the follower already reads the
     * server's treatments on every poll, and reading them a second time here would be waste.
     */
    @Keep
    override fun sendAsFollower() {
        if (!followerPassQueued.compareAndSet(false, true)) return
        followerExecutor.execute {
            followerPassQueued.set(false)
            try {
                runBlocking { passLock.withLock { sendAsFollowerLocked() } }
            } catch (th: Throwable) {
                Log.e(LOG_ID, "sendAsFollower failed: ${Log.stackline(th)}")
            }
        }
    }

    private suspend fun sendAsFollowerLocked() {
        if (!NightscoutUploadWake.followerSendsTreatments()) return
        if (!tk.glucodata.JournalEnabled.isOn(Applic.app)) return
        val config = NightscoutFollowerRegistry.loadConfig(Applic.app)
        // The v3 write path authenticates with the uploader's URL and secret. The settings
        // screen keeps them the same as the follower's; if something else changed one, do not
        // post this phone's entries to a server it is not following.
        val uploaderSecret = Natives.getnightuploadsecret().orEmpty().trim()
        if (!sameServer(Natives.getnightuploadurl(), config.url) || uploaderSecret != config.secret.trim()) {
            Log.e(LOG_ID, "follower send skipped: uploader server settings differ from the followed server")
            return
        }
        NightscoutJournalFollowerImporter.followerSendLock.withLock {
            uploadInternal(config.useV3, sendEnabled = true, receiveEnabled = false, isFollowerPass = true)
        }
    }

    @Keep
    override fun getReceiveTreatments(): Boolean =
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_RECEIVE_TREATMENTS, false)

    @JvmStatic
    @Keep
    fun setReceiveTreatments(enabled: Boolean) {
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_RECEIVE_TREATMENTS, enabled)
            .apply()
    }

    @JvmStatic
    @Keep
    fun getSendLongInsulin(): Boolean =
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_SEND_LONG_INSULIN, true)

    @JvmStatic
    @Keep
    fun setSendLongInsulin(enabled: Boolean) {
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SEND_LONG_INSULIN, enabled)
            .apply()
    }

    /** The native side drops an "/api/..." tail the follower keeps; neither cares about case. */
    internal fun sameServer(uploaderUrl: String?, followedUrl: String?): Boolean {
        fun key(url: String?) = NightscoutFollowerRegistry.normalizeUrl(url)
            .substringBefore("/api/")
            .trimEnd('/')
            .lowercase(Locale.US)
        val uploader = key(uploaderUrl)
        return uploader.isNotEmpty() && uploader == key(followedUrl)
    }

    private suspend fun uploadInternal(
        useV3: Boolean,
        sendEnabled: Boolean,
        receiveEnabled: Boolean,
        isFollowerPass: Boolean = false,
    ): Boolean {
        val sendLongInsulin = getSendLongInsulin()
        if (!sendEnabled && !receiveEnabled) return true
        val baseUrl = Natives.getnightuploadurl()?.takeIf { it.isNotBlank() } ?: return true
        val rawSecret = Natives.getnightuploadsecret().orEmpty()
        val secretHashed = if (useV3) null else hashedSecret(rawSecret)
        val currentServer = NightscoutTokenGrant.normalizeServerUrlPreservingPathCase(baseUrl)
        val configKey = NightscoutDeleteRetry.configKey(baseUrl, rawSecret, useV3)
        val initialNativeV3 = if (isFollowerPass) {
            runCatching { NightscoutFollowerRegistry.loadConfig(Applic.app).useV3 }.getOrDefault(useV3)
        } else {
            runCatching { Natives.getnightscoutV3() }.getOrDefault(useV3)
        }
        fun configurationStillMatches(): Boolean {
            val liveUrl = runCatching { Natives.getnightuploadurl() }.getOrNull().orEmpty()
            val liveSecret = runCatching { Natives.getnightuploadsecret() }.getOrNull().orEmpty()
            val liveV3 = if (isFollowerPass) {
                runCatching { NightscoutFollowerRegistry.loadConfig(Applic.app).useV3 }.getOrDefault(initialNativeV3)
            } else {
                runCatching { Natives.getnightscoutV3() }.getOrDefault(initialNativeV3)
            }
            return NightscoutTokenGrant.normalizeServerUrlPreservingPathCase(liveUrl) == currentServer &&
                liveSecret.trim() == rawSecret.trim() &&
                liveV3 == initialNativeV3
        }

        val database = HistoryDatabase.getInstance(Applic.app)
        val dao = database.journalDao()
        val ledger = NightscoutUploadLedger(database)
        val presetCache = HashMap<Long, JournalInsulinPresetEntity?>()
        val foodCache = HashMap<Long, JournalFoodEntity?>()
        var uploadOk = true
        var acceptedDocument = false
        var deleteFailureCode: Int? = null
        var uploadFailureCode: Int? = null
        var changedMeanwhile = false
        var configChangedMidPass = false
        var endpointBlocked = false
        var hadEndpointFailureBeforePass = false
        var maxEndpointAttempts = 0

        if (sendEnabled) {
            val previousConfigKey = JournalSyncStatus.state().lastDeleteConfigKey
            val configChangedSinceLastRun = previousConfigKey.isNotEmpty() && previousConfigKey != configKey
            if (pendingFullRelease.getAndSet(false) || configChangedSinceLastRun) {
                pendingTransientRelease.set(false)
                dao.releaseAllNightscoutDeleteBackoff()
            } else if (pendingTransientRelease.getAndSet(false)) {
                dao.releaseTransientNightscoutDeleteBackoff()
            }
        }

        suspend fun deletePending(tomb: JournalPendingDeleteEntity) {
            if (!NightscoutDeleteRetry.matchesServer(tomb, currentServer)) return
            if (dao.countOwnUploadsOfNightscoutDocument(tomb.nsRemoteId) > 0) {
                // Restored or sent again since the deletion was queued: it is a live entry's.
                if (tomb.lastAttemptAt == 0L) {
                    Log.i(LOG_ID, "tombstone remoteId=${tomb.nsRemoteId} belongs to an entry again; dropping it")
                    dao.clearPendingNightscoutDelete(tomb.nsRemoteId)
                    return
                }
                // A delete went out and was never answered, so whether the entry still has its
                // document is not known. Finish the deletion and send the entry afresh.
                dao.releaseNightscoutDocument(tomb.nsRemoteId)
                changedMeanwhile = true
            }
            if (!configurationStillMatches()) {
                configChangedMidPass = true
                return
            }
            if (tomb.targetServer.isEmpty()) {
                dao.bindPendingNightscoutDeleteServer(tomb.nsRemoteId, currentServer)
            }
            val transientVer = transientRecoveryVersion.get()
            val resetVer = manualResetVersion.get()
            // Nightscout v3 bakes role permissions into the cached JWT; dropping it before an
            // auth-recovery probe lets server-side permission fixes take effect without a phone edit.
            if (useV3 && tomb.failureKind == DeleteFailureKind.AUTH.storageValue) {
                NightPost.dropToken()
            }
            dao.recordFailedNightscoutDelete(tomb.nsRemoteId, tomb.attempts, clockMillis())
            val outcome = deleteRemoteDocumentVerified(dao, baseUrl, rawSecret, secretHashed, tomb.nsRemoteId, useV3)
            val attemptNow = clockMillis()
            if (outcome.action == TombstoneAction.CLEAR) {
                if (ledger.deleted(tomb.nsRemoteId)) changedMeanwhile = true
                // A 404/410 clears the tombstone, but is not proof the server took a write from
                // us, so it must not move "last sent".
                if (isDeleteAccepted(outcome.code)) {
                    acceptedDocument = true
                    if (hadEndpointFailureBeforePass) {
                        dao.clearEndpointNightscoutDeleteHold(currentServer)
                        hadEndpointFailureBeforePass = false
                        maxEndpointAttempts = 0
                    }
                }
                return
            }
            val kind = when (outcome.action) {
                TombstoneAction.RETRY_AUTH -> DeleteFailureKind.AUTH
                TombstoneAction.RETRY_CONFIG -> DeleteFailureKind.CONFIG
                else -> DeleteFailureKind.TRANSIENT
            }
            val configValid = configurationStillMatches()
            val staleRecovery = !configValid ||
                resetVer != manualResetVersion.get() ||
                (kind == DeleteFailureKind.TRANSIENT && transientVer != transientRecoveryVersion.get())
            if (staleRecovery) {
                configChangedMidPass = !configValid
                if (changedNothing(outcome.code)) {
                    dao.recordFailedNightscoutDelete(tomb.nsRemoteId, tomb.attempts, 0L)
                }
                Log.i(LOG_ID, "ignoring stale delete failure for remoteId=${tomb.nsRemoteId} after recovery")
                return
            }
            val endpointWide = NightscoutDeleteRetry.isEndpointWideFailure(kind, outcome.code)
            val nextAttempts = if (endpointWide) maxOf(tomb.attempts, maxEndpointAttempts) + 1 else tomb.attempts + 1
            val nextRetryAt = attemptNow + NightscoutDeleteRetry.backoffMillis(nextAttempts, kind)
            dao.recordNightscoutDeleteRetryState(
                remoteId = tomb.nsRemoteId,
                targetServer = currentServer,
                attempts = nextAttempts,
                attemptedAt = if (changedNothing(outcome.code)) 0L else attemptNow,
                nextRetryAt = nextRetryAt,
                failureKind = kind.storageValue,
                lastErrorCode = outcome.code,
                lastErrorMessage = outcome.message,
            )
            Log.e(
                LOG_ID,
                "tombstone delete failed ($kind) for entryId=${tomb.entryId} " +
                    "remoteId=${tomb.nsRemoteId} code=${failureText(outcome.code, outcome.message)} " +
                    "attempt=$nextAttempts nextRetryIn=${((nextRetryAt - attemptNow) / 1000L).coerceAtLeast(0L)}s"
            )
            deleteFailureCode = outcome.code
            if (endpointWide) {
                maxEndpointAttempts = nextAttempts
                endpointBlocked = true
            }
        }

        // Deletes and creates are independent, so a refused delete must not cost us the
        // pending entries: it used to break out of the whole run and, since the tombstone
        // was never cleared, wedge every later cycle at the same document (issue #191).
        if (sendEnabled) {
            ledger.settleAbandoned(clockMillis(), currentServer)
            val serverDeletes = dao.getPendingNightscoutDeletes()
                .filter { NightscoutDeleteRetry.matchesServer(it, currentServer) }
            // Drop tombstones already owned by a live entry even while an endpoint hold is active.
            val activeDeletes = ArrayList<JournalPendingDeleteEntity>(serverDeletes.size)
            for (tomb in serverDeletes) {
                if (tomb.lastAttemptAt == 0L && dao.countOwnUploadsOfNightscoutDocument(tomb.nsRemoteId) > 0) {
                    Log.i(LOG_ID, "tombstone remoteId=${tomb.nsRemoteId} belongs to an entry again; dropping it")
                    dao.clearPendingNightscoutDelete(tomb.nsRemoteId)
                } else {
                    activeDeletes += tomb
                }
            }
            val deleteNow = clockMillis()
            val holdUntil = NightscoutDeleteRetry.endpointHoldUntil(activeDeletes, deleteNow)
            maxEndpointAttempts = NightscoutDeleteRetry.maxEndpointAttempts(activeDeletes)
            hadEndpointFailureBeforePass = maxEndpointAttempts > 0
            endpointBlocked = holdUntil > deleteNow
            if (!endpointBlocked) {
                val ordered = if (hadEndpointFailureBeforePass) {
                    // Probe a previously endpoint-failed tombstone first so the exponential counter
                    // advances smoothly and a single probe gates the rest of the queue.
                    activeDeletes.sortedWith(
                        compareByDescending<JournalPendingDeleteEntity> {
                            NightscoutDeleteRetry.isEndpointWideFailure(
                                DeleteFailureKind.fromStorage(it.failureKind),
                                it.lastErrorCode,
                            ) && it.attempts > 0
                        }.thenBy { it.deletedAt }.thenBy { it.nsRemoteId }
                    )
                } else {
                    activeDeletes.sortedWith(
                        compareBy<JournalPendingDeleteEntity>({ it.attempts }, { it.deletedAt }, { it.nsRemoteId })
                    )
                }
                for (tomb in ordered) {
                    if (endpointBlocked || configChangedMidPass) break
                    val now = clockMillis()
                    if (NightscoutDeleteRetry.effectiveRetryAt(tomb, now) > now) continue
                    deletePending(tomb)
                }
            }
        }

        val sinceMillis = clockMillis() - LOOKBACK_MILLIS
        if (sendEnabled && !configChangedMidPass) {
            val pending = dao.getEntriesNeedingNightscoutUpload(sinceMillis)
            val pendingDeleteIds = dao.getPendingNightscoutDeletes().mapTo(HashSet()) { it.nsRemoteId }
            for (listed in pending) {
                if (!configurationStillMatches()) {
                    configChangedMidPass = true
                    break
                }
                // Read again: the entries before this one took as long as their requests did.
                val entry = dao.getEntryById(listed.id) ?: continue
                if (!isSendableType(entry.entryType)) continue
                if (isExternalMirrorSource(entry.source)) continue

                val preset = entry.insulinPresetId?.let { id ->
                    presetCache.getOrPut(id) { dao.getInsulinPresetById(id) }
                }
                if (!shouldUploadTreatment(entry.entryType, preset, sendLongInsulin)) continue

                if (sendBackoff.shouldHold(entry.id, clockMillis())) {
                    // Same entry, same refusal a moment ago: reported then, not again now.
                    uploadOk = false
                    break
                }

                val dated = datedIdentifier(entry.id, entry.timestamp)
                // A re-upload writes first; the old copy is dealt with after the server has
                // accepted the new document (see oldCopyAction). It used to be deleted here,
                // before the POST, and a refused POST then left the entry gone from Nightscout.

                val food = entry.foodId?.let { id ->
                    foodCache.getOrPut(id) { dao.getFoodById(id) }
                }
                fun treatmentJson(remoteId: String) = JournalTreatmentTransfer.buildTreatmentJson(
                    entry = entry,
                    remoteId = remoteId,
                    preset = preset,
                    food = food,
                    useV3 = useV3,
                    includeRemoteId = useV3
                )
                val json = treatmentJson(dated) ?: continue
                val eventType = json.optString("eventType")
                // On v1 only the record of what is on the way uses this name.
                val identity = if (useV3) v3Identifier(entry.nsRemoteId, dated, eventType) else dated
                var attempt = ledger.begin(entry, identity, clockMillis(), targetServer = currentServer)
                if (attempt == null) {
                    changedMeanwhile = true
                    continue
                }
                var result = if (useV3) {
                    val hadPendingDelete = identity in pendingDeleteIds
                    val write = treatmentWrite(entry.nsRemoteId, identity)
                    val initial = sendV3Treatment(baseUrl, entry.nsRemoteId, identity, secretHashed, treatmentJson(identity)!!)
                    if (write == TreatmentWrite.CREATE &&
                        hadPendingDelete &&
                        initial.code == HttpURLConnection.HTTP_CONFLICT
                    ) {
                        // The restored timestamp still exists on Nightscout because its earlier
                        // delete was backed off; update it in place so restored edits are kept.
                        sendV3Treatment(baseUrl, identity, identity, secretHashed, treatmentJson(identity)!!)
                    } else {
                        initial
                    }
                } else {
                    json.remove("_id")
                    json.put("identifier", localIdentifier(entry.id))
                    postV1Treatment(baseUrl, rawSecret, json, entry)
                }
                if (useV3 && isEventTypeRefusal(result.code, result.message)) {
                    ledger.refused(attempt)
                    val typed = typedIdentifier(dated, eventType)
                    attempt = ledger.begin(entry, typed, clockMillis(), targetServer = currentServer)
                    if (attempt == null) {
                        changedMeanwhile = true
                        continue
                    }
                    result = sendV3Treatment(baseUrl, entry.nsRemoteId, typed, secretHashed, treatmentJson(typed)!!)
                }
                val now = clockMillis()
                if (!isUploadOk(result.code, useV3)) {
                    if (changedNothing(result.code)) ledger.refused(attempt)
                    Log.e(LOG_ID, "upload failed entry id=${entry.id} code=${failureText(result.code, result.message)}")
                    sendBackoff.recordFailure(entry.id, now)
                    uploadFailureCode = result.code
                    uploadOk = false
                    break
                }
                sendBackoff.reset()
                acceptedDocument = true
                val ack = ledger.accept(attempt, result.remoteId, now, targetServer = currentServer)
                pendingDeleteIds.remove(result.remoteId ?: identity)
                if (!ack.revisionCurrent) {
                    changedMeanwhile = true
                    Log.i(
                        LOG_ID,
                        "entry id=${entry.id} was ${if (ack.entryKept) "edited" else "deleted"} during its upload"
                    )
                }
                // The new document is on the server; what it left over is a duplicate, not a
                // loss. Only attempt inline cleanup while the endpoint is healthy; otherwise it
                // stays durably queued in Room until the endpoint backoff expires.
                for (tomb in ack.queuedDeletes) {
                    if (endpointBlocked || configChangedMidPass) break
                    deletePending(tomb)
                }
            }
        }
        // The edit or deletion asked for a pass itself, but not every writer does, and the
        // entry it belongs to is due again.
        if (changedMeanwhile) NightscoutUploadWake.afterJournalChange()

        if (sendEnabled && !configChangedMidPass) {
            val statusNow = clockMillis()
            val diagnostics = NightscoutDeleteRetry.summarize(
                allTombstones = dao.getPendingNightscoutDeletes(),
                currentServer = currentServer,
                nowMillis = statusNow,
            )
            if (!isFollowerPass) {
                if (diagnostics.nextRetryAt > statusNow) {
                    scheduleDeferredDeleteWake(diagnostics.nextRetryAt, statusNow)
                } else {
                    cancelDeferredDeleteWake()
                }
            }
            recordSendStatus(statusNow, uploadFailureCode, deleteFailureCode, acceptedDocument, diagnostics, configKey)
        } else if (sendEnabled && configChangedMidPass && !isFollowerPass) {
            scheduleDeferredDeleteWake(clockMillis() + DELETE_RETRY_IMMEDIATE_DELAY_MILLIS, clockMillis())
        }

        val receiveOk = if (receiveEnabled && !configChangedMidPass) {
            receiveRemoteTreatments(baseUrl, rawSecret, useV3)
        } else {
            true
        }
        // A refused delete is deliberately not folded in here. Returning false makes the native
        // loop back off and skip the IOB device status, which is exactly the collateral damage
        // this path used to cause; the tombstone uses Room-backed capped exponential backoff.
        return uploadOk && receiveOk
    }

    /**
     * Publishes the outcome of one send cycle so the settings screen can show a treatment
     * path that has been failing while the native entries uploader kept reporting HTTP 200.
     */
    private fun recordSendStatus(
        now: Long,
        uploadFailureCode: Int?,
        deleteFailureCode: Int?,
        acceptedDocument: Boolean,
        diagnostics: DeleteDiagnostics,
        configKey: String,
    ) {
        JournalSyncStatus.recordCycleOutcome(
            now = now,
            acceptedDocument = acceptedDocument,
            uploadFailureCode = uploadFailureCode,
            deleteFailureCode = deleteFailureCode,
            unresolvedDeletes = diagnostics,
            configKey = configKey,
        )
    }

    private fun isSendableType(entryType: String): Boolean {
        val type = JournalEntryType.fromStorage(entryType)
        return type == JournalEntryType.INSULIN ||
            type == JournalEntryType.CARBS ||
            type == JournalEntryType.FINGERSTICK ||
            type == JournalEntryType.ACTIVITY ||
            type == JournalEntryType.NOTE
    }

    internal fun shouldUploadTreatment(
        entryType: String,
        preset: JournalInsulinPresetEntity?,
        sendLongInsulin: Boolean
    ): Boolean {
        if (sendLongInsulin || JournalEntryType.fromStorage(entryType) != JournalEntryType.INSULIN) {
            return true
        }
        // Basal presets deliberately do not count toward IOB. Unknown/deleted
        // presets remain sendable so this preference never silently drops an
        // insulin entry whose classification cannot be recovered.
        return preset?.countsTowardIob != false
    }

    internal fun isExternalMirrorSource(source: String): Boolean {
        return source == JournalEntrySource.AAPS.storageValue ||
            source == JournalEntrySource.NIGHTSCOUT.storageValue ||
            source == JournalEntrySource.API.storageValue ||
            source == JournalEntrySource.CLONE.storageValue ||
            source == JournalEntrySource.CLONE_LOCAL_ICE.storageValue ||
            source == JournalEntrySource.CLONE_TURN.storageValue
    }

    private fun treatmentPostUrl(baseUrl: String, useV3: Boolean): String =
        baseUrl + if (useV3) "/api/v3/treatments" else "/api/v1/treatments"

    /**
     * Read URL for treatments. The v1 form is a bare array under a .json suffix counted by
     * `count`; v3 has neither, and limits with `limit` while sorting newest first, so the two
     * cannot share one string. Sending the v1 form to a v3 setup answers 401, which reads as a
     * server permission problem rather than a client using the wrong API.
     */
    internal fun treatmentFetchUrl(baseUrl: String, useV3: Boolean, count: Int = TREATMENT_FETCH_COUNT): String =
        if (useV3) {
            "$baseUrl/api/v3/treatments?sort%24desc=date&limit=$count&fields=_all"
        } else {
            "$baseUrl/api/v1/treatments.json?count=$count"
        }

    /**
     * The v1 documents of one moment, however old. Servers keep created_at as text with
     * milliseconds, and compare it as text, so the window is a little wider than the moment.
     */
    internal fun treatmentFetchUrlAt(baseUrl: String, timestampMillis: Long): String {
        fun bound(millis: Long) = URLEncoder.encode(Instant.ofEpochMilli(millis).toString(), "UTF-8")
        return "$baseUrl/api/v1/treatments.json?count=$TREATMENT_FETCH_COUNT" +
            "&find%5Bcreated_at%5D%5B%24gte%5D=${bound(timestampMillis - FETCH_WINDOW_MILLIS)}" +
            "&find%5Bcreated_at%5D%5B%24lte%5D=${bound(timestampMillis + FETCH_WINDOW_MILLIS)}"
    }

    /**
     * v1 answers with the document array itself, v3 wraps it in {status, result}. Everything
     * downstream parses an array, so the envelope is peeled here rather than in the importer.
     * A body that is already an array is passed through, so a v3 host that answers the v1 shape
     * still imports.
     */
    internal fun treatmentsArrayBody(body: String, useV3: Boolean): String {
        val trimmed = body.trim()
        if (!useV3 || trimmed.startsWith("[")) return trimmed
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching { JSONObject(trimmed).optJSONArray("result")?.toString() }
            .getOrNull()
            ?: "[]"
    }

    /**
     * API v3 deletes are soft by default. That is invisible to v3 searches, but Nightscout's
     * legacy v1 treatment reads can still return the invalid document and show the stale copy
     * in the web UI. These deletes represent an explicit local deletion or a copy superseded by
     * a successful write, so remove the v3 document permanently after the safety checks above.
     */
    internal fun treatmentDeleteUrl(baseUrl: String, remoteId: String, useV3: Boolean): String =
        baseUrl + if (useV3) {
            "/api/v3/treatments/$remoteId?permanent=true"
        } else {
            "/api/v1/treatments/$remoteId"
        }

    private fun sendV3Treatment(
        baseUrl: String,
        previousRemoteId: String?,
        remoteId: String,
        secretHashed: String?,
        json: JSONObject
    ): UploadResult {
        // A partial update belongs at the concrete document endpoint. Collection
        // POST is only for a create and requires date, while PATCH deliberately
        // omits the immutable time and identity fields.
        val write = treatmentWrite(previousRemoteId, remoteId)
        if (write == TreatmentWrite.UPDATE) {
            JournalTreatmentTransfer.stripImmutableForUpdate(json)
        }
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val code = if (write == TreatmentWrite.UPDATE) {
            NightPost.uploadPatch(treatmentWriteUrl(baseUrl, remoteId, write), payload, secretHashed)
        } else {
            NightPost.upload(treatmentWriteUrl(baseUrl, remoteId, write), payload, secretHashed, false)
        }
        val message = if (isUploadOk(code, useV3 = true)) "" else serverMessage(NightPost.getLastPrimaryResponseBody())
        return UploadResult(code = code, remoteId = remoteId, message = message)
    }

    internal fun treatmentWriteUrl(baseUrl: String, remoteId: String, write: TreatmentWrite): String =
        if (write == TreatmentWrite.UPDATE) "$baseUrl/api/v3/treatments/$remoteId" else treatmentPostUrl(baseUrl, true)

    private fun postV1Treatment(
        baseUrl: String,
        secret: String,
        json: JSONObject,
        entry: JournalEntryEntity
    ): UploadResult {
        val normalized = NightscoutFollowerRegistry.normalizeUrl(baseUrl)
        if (normalized.isBlank()) return UploadResult(ERROR_INVALID_URL)
        val endpoint = "$normalized/api/v1/treatments"
        val postData = json.toString().toByteArray(Charsets.UTF_8)
        Log.i(LOG_ID, "postV1Treatment($endpoint,#${postData.size})")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Length", postData.size.toString())
            setRequestProperty("User-Agent", "JugglucoNG Nightscout journal sync")
            NightscoutFollowerRegistry.applyAuth(this, secret)
        }
        try {
            connection.outputStream.use { output ->
                output.write(postData)
                output.flush()
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            val responseId = extractCreatedRemoteId(body)
                ?: if (code in 200..299) {
                    findWrittenRemoteId(baseUrl, secret, entry, json)
                } else {
                    null
                }
            if (code !in 200..299) {
                Log.e(LOG_ID, "postV1Treatment ResponseCode=$code\n${body.take(512)}")
                return UploadResult(code = code, message = serverMessage(body))
            } else if (responseId == null) {
                Log.w(LOG_ID, "postV1Treatment success without returned Nightscout _id")
            } else {
                Log.i(LOG_ID, "postV1Treatment ResponseCode=$code remoteId=$responseId")
            }
            return UploadResult(code = code, remoteId = responseId)
        } catch (th: Throwable) {
            Log.e(LOG_ID, "postV1Treatment failure:\n${Log.stackline(th)}")
            return UploadResult(-1)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractCreatedRemoteId(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            when {
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.optNightscoutDocumentId()?.let { return@runCatching it }
                    }
                    null
                }
                trimmed.startsWith("{") -> JSONObject(trimmed).optNightscoutDocumentId()
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Deletes the document [remoteId] names, verifying ambiguous 404/410 responses before removing
     * a tombstone so a missing DELETE route or disabled Care Portal cannot drop a live deletion.
     */
    private suspend fun deleteRemoteDocumentVerified(
        dao: JournalDao,
        baseUrl: String,
        rawSecret: String,
        secretHashed: String?,
        remoteId: String,
        useV3: Boolean,
    ): DeleteOutcome {
        if (useV3 || !remoteId.startsWith(ID_PREFIX, ignoreCase = true)) {
            return executeVerifiedDelete(baseUrl, rawSecret, secretHashed, remoteId, useV3)
        }
        val timestamp = timestampOfDatedIdentifier(remoteId)
        val documentIds = try {
            val body = if (timestamp == null) {
                fetchTreatmentsJson(baseUrl, rawSecret, useV3 = false, treat404AsEmpty = false)
            } else {
                fetchTreatmentsJson(baseUrl, rawSecret, useV3 = false, atMillis = timestamp, treat404AsEmpty = false)
            }
            documentIdsToDelete(JSONArray(body), remoteId, dao.getOwnUploadedNightscoutRemoteIds().toSet())
        } catch (http: HttpStatusException) {
            Log.e(LOG_ID, "lookup of remoteId=$remoteId failed: ${http.message}")
            return DeleteOutcome(actionForLookupFailure(http.code), http.code, http.serverMessage)
        } catch (error: Throwable) {
            // Not having been able to look is not the same as there being nothing.
            Log.e(LOG_ID, "lookup of remoteId=$remoteId failed: ${error.message}")
            return DeleteOutcome(TombstoneAction.RETRY_TRANSIENT, NightPost.ERROR_NO_RESPONSE)
        }
        var lastOutcome = DeleteOutcome(TombstoneAction.CLEAR, HttpURLConnection.HTTP_NOT_FOUND)
        for (documentId in documentIds) {
            lastOutcome = executeVerifiedDelete(baseUrl, rawSecret, secretHashed, documentId, useV3 = false)
            if (lastOutcome.action != TombstoneAction.CLEAR) return lastOutcome
        }
        return lastOutcome
    }

    private fun executeVerifiedDelete(
        baseUrl: String,
        rawSecret: String,
        secretHashed: String?,
        remoteId: String,
        useV3: Boolean,
    ): DeleteOutcome {
        val code = NightPost.deleteUrlCode(treatmentDeleteUrl(baseUrl, remoteId, useV3), secretHashed)
        val rawBody = if (isDeleteAccepted(code)) "" else NightPost.getLastPrimaryResponseBody().orEmpty()
        val message = if (isDeleteAccepted(code)) "" else serverMessage(rawBody)
        val baseAction = tombstoneAction(code)
        if (baseAction != TombstoneAction.CLEAR || isDeleteAccepted(code)) {
            return DeleteOutcome(baseAction, code, message)
        }
        // code is 404 or 410 here: reject missing-route responses and verify ambiguous ones.
        if (NightscoutDeleteRetry.isRouteMissingResponse(code, rawBody)) {
            val detail = message.ifBlank { "HTTP $code: DELETE route unavailable" }
            return DeleteOutcome(TombstoneAction.RETRY_CONFIG, NightPost.ERROR_CONFIG, detail)
        }
        if (NightscoutDeleteRetry.isUnambiguousDocumentAbsence(code, rawBody, useV3)) {
            return DeleteOutcome(TombstoneAction.CLEAR, code)
        }
        return verifyRemoteDocumentAbsent(baseUrl, rawSecret, remoteId, useV3)
    }

    private fun verifyRemoteDocumentAbsent(
        baseUrl: String,
        rawSecret: String,
        remoteId: String,
        useV3: Boolean,
    ): DeleteOutcome {
        val normalized = NightscoutFollowerRegistry.normalizeUrl(baseUrl)
        if (normalized.isBlank()) {
            return DeleteOutcome(TombstoneAction.RETRY_CONFIG, NightPost.ERROR_INVALID_URL)
        }
        val encodedId = URLEncoder.encode(remoteId, Charsets.UTF_8.name())
        val endpoint = if (useV3) {
            "$normalized/api/v3/treatments/$encodedId"
        } else {
            val epoch = URLEncoder.encode("1970-01-01T00:00:00.000Z", Charsets.UTF_8.name())
            "$normalized/api/v1/treatments.json?find%5B_id%5D=$encodedId&find%5Bcreated_at%5D%5B%24gte%5D=$epoch&count=1"
        }
        var connection: HttpURLConnection? = null
        return try {
            val v3Auth = if (useV3) NightPost.getV3AuthorizationHeaderForUrl(endpoint).orEmpty() else ""
            if (useV3 && v3Auth.isEmpty()) {
                return DeleteOutcome(TombstoneAction.RETRY_TRANSIENT, NightPost.ERROR_NO_RESPONSE)
            }
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "JugglucoNG Nightscout journal sync")
                if (v3Auth.isNotEmpty()) {
                    setRequestProperty("Authorization", v3Auth)
                } else {
                    NightscoutFollowerRegistry.applyAuth(this, rawSecret)
                }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            val message = serverMessage(body)
            when {
                useV3 && (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) -> {
                    if (NightscoutDeleteRetry.isUnambiguousDocumentAbsence(code, body, useV3 = true)) {
                        DeleteOutcome(TombstoneAction.CLEAR, code)
                    } else {
                        DeleteOutcome(
                            TombstoneAction.RETRY_CONFIG,
                            NightPost.ERROR_CONFIG,
                            message.ifBlank { "HTTP $code: treatments endpoint unavailable" },
                        )
                    }
                }
                !useV3 && code in 200..299 -> {
                    val trimmed = body.trim()
                    if (!trimmed.startsWith("[")) {
                        DeleteOutcome(TombstoneAction.RETRY_CONFIG, NightPost.ERROR_CONFIG, "Unexpected v1 response")
                    } else if (JSONArray(trimmed).length() == 0) {
                        DeleteOutcome(TombstoneAction.CLEAR, HttpURLConnection.HTTP_NOT_FOUND)
                    } else {
                        DeleteOutcome(
                            TombstoneAction.RETRY_CONFIG,
                            NightPost.ERROR_CONFIG,
                            "DELETE returned 404 while treatment still exists on server",
                        )
                    }
                }
                useV3 && code in 200..299 ->
                    DeleteOutcome(
                        TombstoneAction.RETRY_CONFIG,
                        NightPost.ERROR_CONFIG,
                        "DELETE returned 404 while treatment still exists on server",
                    )
                else -> DeleteOutcome(actionForLookupFailure(code), code, message)
            }
        } catch (th: Throwable) {
            Log.e(LOG_ID, "verifyRemoteDocumentAbsent($remoteId) failed: ${th.message}")
            DeleteOutcome(TombstoneAction.RETRY_TRANSIENT, NightPost.ERROR_NO_RESPONSE)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * The `_id`s a deletion of [remoteId] means over v1, without the ones entries hold. A dated
     * name means that entry at that time only, wherever the server or another time left it; the
     * older undated name cannot say which, and means the newest.
     */
    internal fun documentIdsToDelete(treatments: JSONArray, remoteId: String, heldByEntries: Set<String>): List<String> {
        val dated = timestampOfDatedIdentifier(remoteId) != null
        val matches = (0 until treatments.length())
            .mapNotNull { treatments.optJSONObject(it) }
            .filter { treatment ->
                val identifier = treatment.optString("identifier")
                identifier == remoteId ||
                    (dated && JournalTreatmentTransfer.datedIdentifierOf(treatment) == remoteId)
            }
            .filterNot { dated && it.optString("identifier") in heldByEntries }
            .mapNotNull { it.optNightscoutDocumentId() }
            .filterNot { it in heldByEntries }
            .distinct()
        return if (dated) matches else matches.take(1)
    }

    /**
     * The document a v1 write went to when the server did not say, which is what older servers
     * do for one they replaced in place. It carries the revision that was sent, and the
     * eventType: those servers tell documents of one moment apart by it, so the copy from
     * before a type edit stands beside the new one and may even share its updated_at.
     */
    internal fun writtenDocumentId(treatments: JSONArray, entry: JournalEntryEntity, sent: JSONObject): String? {
        val updatedAt = sent.optString("updated_at")
        for (index in 0 until treatments.length()) {
            val treatment = treatments.optJSONObject(index) ?: continue
            if (treatment.optString("identifier") != localIdentifier(entry.id)) continue
            if (treatment.optString("eventType") != sent.optString("eventType")) continue
            if (treatment.optString("updated_at").ifEmpty { updatedAt } != updatedAt) continue
            val date = treatment.optLong("date", 0L)
            if (date != 0L && kotlin.math.abs(date - entry.timestamp) > 60_000L) continue
            treatment.optNightscoutDocumentId()?.let { return it }
        }
        return null
    }

    private fun findWrittenRemoteId(
        baseUrl: String,
        secret: String,
        entry: JournalEntryEntity,
        sent: JSONObject
    ): String? = runCatching {
        val body = fetchTreatmentsJson(baseUrl, secret, useV3 = false, atMillis = entry.timestamp)
        writtenDocumentId(JSONArray(body), entry, sent)
    }.getOrNull()

    private fun JSONObject.optNightscoutDocumentId(): String? =
        optString("_id").trim().takeIf { it.isNotBlank() }
            ?: optString("id").trim().takeIf { it.isNotBlank() }

    private fun receiveRemoteTreatments(baseUrl: String, secret: String, useV3: Boolean): Boolean {
        val floorKey = "${if (useV3) "v3" else "v1"} ${NightscoutFollowerRegistry.normalizeUrl(baseUrl)}"
        val waitMillis = receiveFloor.waitMillis(floorKey, System.currentTimeMillis())
        if (waitMillis > 0L) {
            bookDeferredReceive(waitMillis)
            return true
        }
        return runCatching {
            val body = fetchTreatmentsJson(baseUrl, secret, useV3)
            receiveFloor.recordRead(floorKey, System.currentTimeMillis())
            if (body.isBlank() || body == "[]") return@runCatching true
            val sensorId = NightscoutFollowerRegistry.deriveSensorId(baseUrl)
            val imported = NightscoutJournalFollowerImporter.importTreatments(sensorId, body)
            if (imported > 0) {
                UiRefreshBus.requestDataRefresh()
                Log.i(LOG_ID, "received $imported Nightscout treatment journal items")
            }
            receiveErrorLog.reset()
            true
        }.onFailure { error ->
            // The uploader retries on its own cadence, so an unreachable or refusing server
            // used to write the same line every few seconds and bury the rest of the trace.
            val message = "receive treatments failed: ${error.message}"
            val repeats = receiveErrorLog.suppressedSince(message, System.currentTimeMillis())
            if (repeats >= 0) {
                Log.e(LOG_ID, if (repeats == 0) message else "$message (repeated ${repeats + 1}x)")
            }
        }.getOrDefault(false)
    }

    private fun fetchTreatmentsJson(
        baseUrl: String,
        secret: String,
        useV3: Boolean,
        retryRejectedToken: Boolean = true,
        atMillis: Long? = null,
        treat404AsEmpty: Boolean = true,
    ): String {
        val normalized = NightscoutFollowerRegistry.normalizeUrl(baseUrl)
        if (normalized.isBlank()) return "[]"
        val endpoint = if (atMillis == null) {
            treatmentFetchUrl(normalized, useV3)
        } else {
            treatmentFetchUrlAt(normalized, atMillis)
        }
        // v3 reads want the same bearer token the v3 upload path already obtains and
        // caches; the configured secret is an access token there, and hashing it into an
        // api-secret header is what the 401 was.
        val v3Auth = if (useV3) NightPost.getV3AuthorizationHeaderForUrl(endpoint).orEmpty() else ""
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG Nightscout journal sync")
            if (v3Auth.isNotEmpty()) {
                setRequestProperty("Authorization", v3Auth)
            } else {
                NightscoutFollowerRegistry.applyAuth(this, secret)
            }
        }
        try {
            val code = connection.responseCode
            // The cached JWT outlived the key that signed it (Nightscout restarted, #716).
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED && retryRejectedToken && v3Auth.isNotEmpty()) {
                NightPost.dropRejectedV3AuthorizationHeader(v3Auth)
                connection.disconnect()
                return fetchTreatmentsJson(
                    baseUrl = baseUrl,
                    secret = secret,
                    useV3 = useV3,
                    retryRejectedToken = false,
                    atMillis = atMillis,
                    treat404AsEmpty = treat404AsEmpty,
                )
            }
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code == HttpURLConnection.HTTP_NOT_FOUND && treat404AsEmpty) return "[]"
            if (code !in 200..299) {
                val msg = serverMessage(body)
                throw HttpStatusException(code, msg, "HTTP $code ${endpointPath(endpoint)}: $msg")
            }
            return treatmentsArrayBody(body, useV3)
        } finally {
            connection.disconnect()
        }
    }

    private fun hashedSecret(raw: String?): String? {
        val s = raw?.takeIf { it.isNotEmpty() } ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return hex.toString()
    }
}
