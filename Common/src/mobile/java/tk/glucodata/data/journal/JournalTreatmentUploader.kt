package tk.glucodata.data.journal

import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Sends Kotlin Journal entries to Nightscout as treatments. Replaces the legacy C++
 * uploadtreatments() path that pulled
 * from Numdata. Invoked from the native upload loop via NightPost.
 *
 * Sync state is tracked per-row on JournalEntryEntity (nsUploadedAt, nsRemoteId);
 * deletes are queued in journal_pending_deletes so they survive process death.
 */
@Keep
object JournalTreatmentUploader {
    private const val LOG_ID = "JournalTreatmentUploader"
    private const val ID_PREFIX = "jng-j-"
    private const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000  // mirrors C++ nighttimeback (30 days)
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_RECEIVE_TREATMENTS = "nightscout_receive_treatments"
    private const val PREF_SEND_LONG_INSULIN = "nightscout_send_long_insulin"
    private const val TREATMENT_FETCH_COUNT = 240
    private const val ERROR_INVALID_URL = -2
    private const val RECEIVE_ERROR_LOG_INTERVAL_MILLIS = 5L * 60 * 1000

    /**
     * Refused deletes the tombstone survives before it is dropped. A document Nightscout
     * will never let us delete (a token without `api:treatments:delete` answers 403 forever)
     * must not stay queued for the rest of the install's life.
     */
    internal const val MAX_DELETE_ATTEMPTS = 20

    internal enum class TombstoneAction { CLEAR, RETRY, GIVE_UP }

    private data class UploadResult(
        val code: Int,
        val remoteId: String? = null
    )

    /** True only when the server actually removed the document for us. */
    internal fun isDeleteAccepted(code: Int): Boolean =
        code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_NO_CONTENT

    /**
     * What to do with a tombstone after a delete answered [code]. 404/410 count as done:
     * the document we wanted gone is gone, and the old code kept retrying those forever.
     */
    internal fun tombstoneAction(code: Int, attemptsSoFar: Int): TombstoneAction = when (code) {
        HttpURLConnection.HTTP_OK,
        HttpURLConnection.HTTP_NO_CONTENT,
        HttpURLConnection.HTTP_NOT_FOUND,
        HttpURLConnection.HTTP_GONE -> TombstoneAction.CLEAR
        else -> if (attemptsSoFar + 1 >= MAX_DELETE_ATTEMPTS) {
            TombstoneAction.GIVE_UP
        } else {
            TombstoneAction.RETRY
        }
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
     * The server's own words when it has any. A refused read says which permission is missing
     * ("Missing permission api:treatments:read", typical of an upload-only token), and that
     * sentence is the whole difference between an actionable failure and a bare status code.
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

    /** Path only: the host is already known and repeating it just crowds the line. */
    internal fun endpointPath(endpoint: String): String =
        runCatching { URL(endpoint).path }.getOrNull()?.takeIf { it.isNotBlank() } ?: endpoint

    // Mirrors writetreatment(V3) acceptance: 200/201 always; 409 only on V3 (POST conflict).
    private fun isUploadOk(code: Int, useV3: Boolean): Boolean {
        if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) return true
        if (useV3 && code == HttpURLConnection.HTTP_CONFLICT) return true
        return false
    }

    @JvmStatic
    @Keep
    fun uploadAll(useV3: Boolean): Boolean = runBlocking {
        try {
            uploadInternal(useV3)
        } catch (th: Throwable) {
            Log.e(LOG_ID, "uploadAll failed: ${Log.stackline(th)}")
            false
        }
    }

    @JvmStatic
    @Keep
    fun getReceiveTreatments(): Boolean =
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

    private suspend fun uploadInternal(useV3: Boolean): Boolean {
        val sendEnabled = Natives.getpostTreatments()
        val receiveEnabled = getReceiveTreatments()
        val sendLongInsulin = getSendLongInsulin()
        if (!sendEnabled && !receiveEnabled) return true
        val baseUrl = Natives.getnightuploadurl()?.takeIf { it.isNotBlank() } ?: return true
        val secretHashed = if (useV3) null else hashedSecret(Natives.getnightuploadsecret())
        val rawSecret = Natives.getnightuploadsecret().orEmpty()
        val dao = HistoryDatabase.getInstance(Applic.app).journalDao()
        val presetCache = HashMap<Long, JournalInsulinPresetEntity?>()
        val foodCache = HashMap<Long, JournalFoodEntity?>()
        var uploadOk = true
        var acceptedDocument = false
        var deleteFailureCode: Int? = null
        var uploadFailureCode: Int? = null

        // Deletes and creates are independent, so a refused delete must not cost us the
        // pending entries: it used to break out of the whole run and, since the tombstone
        // was never cleared, wedge every later cycle at the same document (issue #191).
        if (sendEnabled) {
            for (tomb in dao.getPendingNightscoutDeletes()) {
                val deleteRemoteId = resolveDeleteRemoteId(baseUrl, rawSecret, tomb.nsRemoteId, useV3)
                val deleteUrl = treatmentDeleteUrl(baseUrl, deleteRemoteId, useV3)
                val code = NightPost.deleteUrlCode(deleteUrl, secretHashed)
                val attempts = tomb.attempts + 1
                when (tombstoneAction(code, tomb.attempts)) {
                    TombstoneAction.CLEAR -> {
                        dao.clearPendingNightscoutDelete(tomb.entryId)
                        // A 404 also clears the tombstone, but it is not proof that the
                        // server took anything from us, so it must not move "last sent".
                        if (isDeleteAccepted(code)) acceptedDocument = true
                    }
                    TombstoneAction.RETRY -> {
                        Log.e(
                            LOG_ID,
                            "tombstone delete failed for entryId=${tomb.entryId} " +
                                "remoteId=$deleteRemoteId code=$code attempt=$attempts"
                        )
                        dao.recordFailedNightscoutDelete(tomb.entryId, attempts, System.currentTimeMillis())
                        deleteFailureCode = code
                    }
                    TombstoneAction.GIVE_UP -> {
                        Log.e(
                            LOG_ID,
                            "giving up on tombstone entryId=${tomb.entryId} remoteId=$deleteRemoteId " +
                                "after $attempts refused deletes (last code=$code); dropping it"
                        )
                        dao.clearPendingNightscoutDelete(tomb.entryId)
                        deleteFailureCode = code
                    }
                }
            }
        }

        val sinceMillis = System.currentTimeMillis() - LOOKBACK_MILLIS
        if (sendEnabled) {
            val pending = dao.getEntriesNeedingNightscoutUpload(sinceMillis)
            for (entry in pending) {
                if (!isSendableType(entry.entryType)) continue
                if (isExternalMirrorSource(entry.source)) continue

                val preset = entry.insulinPresetId?.let { id ->
                    presetCache.getOrPut(id) { dao.getInsulinPresetById(id) }
                }
                if (!shouldUploadTreatment(entry.entryType, preset, sendLongInsulin)) continue

                val localIdentifier = ID_PREFIX + entry.id.toString(16)
                val remoteId = if (useV3) {
                    entry.nsRemoteId ?: localIdentifier
                } else {
                    localIdentifier
                }
                // Re-upload: drop the old copy first (mirrors legacy delete-then-PUT/POST).
                if (entry.nsRemoteId != null) {
                    NightPost.deleteUrl(treatmentDeleteUrl(baseUrl, entry.nsRemoteId, useV3), secretHashed)
                }

                val food = entry.foodId?.let { id ->
                    foodCache.getOrPut(id) { dao.getFoodById(id) }
                }
                val json = JournalTreatmentTransfer.buildTreatmentJson(
                    entry = entry,
                    remoteId = remoteId,
                    preset = preset,
                    food = food,
                    useV3 = useV3,
                    includeRemoteId = useV3
                )
                    ?: continue
                val result = if (useV3) {
                    uploadViaNightPost(baseUrl, json, secretHashed, useV3, remoteId)
                } else {
                    json.remove("_id")
                    json.put("identifier", localIdentifier)
                    postV1Treatment(baseUrl, rawSecret, json, localIdentifier, entry.timestamp)
                }
                if (!isUploadOk(result.code, useV3)) {
                    Log.e(LOG_ID, "upload failed entry id=${entry.id} code=${result.code}")
                    uploadFailureCode = result.code
                    uploadOk = false
                    break
                }
                dao.markEntryUploadedToNightscout(
                    entry.id,
                    result.remoteId ?: remoteId,
                    System.currentTimeMillis()
                )
                acceptedDocument = true
            }
        }

        if (sendEnabled) {
            recordSendStatus(uploadFailureCode, deleteFailureCode, acceptedDocument)
        }

        val receiveOk = if (receiveEnabled) {
            receiveRemoteTreatments(baseUrl, rawSecret, useV3)
        } else {
            true
        }
        // A refused delete is deliberately not folded in here. Returning false makes the native
        // loop back off and skip the IOB device status, which is exactly the collateral damage
        // this path used to cause; the tombstone is retried on the next cycle either way.
        return uploadOk && receiveOk
    }

    /**
     * Publishes the outcome of one send cycle so the settings screen can show a treatment
     * path that has been failing while the native entries uploader kept reporting HTTP 200.
     */
    private fun recordSendStatus(
        uploadFailureCode: Int?,
        deleteFailureCode: Int?,
        acceptedDocument: Boolean
    ) {
        val now = System.currentTimeMillis()
        when {
            uploadFailureCode != null ->
                JournalSyncStatus.recordFailure(now, uploadFailureCode, JournalSyncFailure.UPLOAD)
            deleteFailureCode != null ->
                JournalSyncStatus.recordFailure(now, deleteFailureCode, JournalSyncFailure.DELETE)
            else -> JournalSyncStatus.recordSuccess(now, acceptedDocument)
        }
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

    private fun isExternalMirrorSource(source: String): Boolean {
        return source == JournalEntrySource.AAPS.storageValue ||
            source == JournalEntrySource.NIGHTSCOUT.storageValue ||
            source == JournalEntrySource.API.storageValue
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

    private fun treatmentDeleteUrl(baseUrl: String, remoteId: String, useV3: Boolean): String =
        baseUrl + (if (useV3) "/api/v3/treatments/" else "/api/v1/treatments/") + remoteId

    private fun uploadViaNightPost(
        baseUrl: String,
        json: JSONObject,
        secretHashed: String?,
        useV3: Boolean,
        remoteId: String
    ): UploadResult {
        val code = NightPost.upload(
            treatmentPostUrl(baseUrl, useV3),
            json.toString().toByteArray(Charsets.UTF_8),
            secretHashed,
            false
        )
        return UploadResult(code = code, remoteId = remoteId)
    }

    private fun postV1Treatment(
        baseUrl: String,
        secret: String,
        json: JSONObject,
        localIdentifier: String,
        timestamp: Long
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
                ?: if (code in 200..299) findRemoteIdByIdentifier(baseUrl, secret, localIdentifier, timestamp) else null
            if (code !in 200..299) {
                Log.e(LOG_ID, "postV1Treatment ResponseCode=$code\n${body.take(512)}")
            } else if (responseId == null) {
                Log.w(LOG_ID, "postV1Treatment success without returned Nightscout _id; using local identifier")
            } else {
                Log.i(LOG_ID, "postV1Treatment ResponseCode=$code remoteId=$responseId")
            }
            return UploadResult(code = code, remoteId = responseId ?: localIdentifier)
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

    private fun resolveDeleteRemoteId(
        baseUrl: String,
        secret: String,
        remoteId: String,
        useV3: Boolean
    ): String {
        if (useV3 || !remoteId.startsWith(ID_PREFIX, ignoreCase = true)) return remoteId
        return findRemoteIdByIdentifier(baseUrl, secret, remoteId, timestamp = null) ?: remoteId
    }

    private fun findRemoteIdByIdentifier(
        baseUrl: String,
        secret: String,
        localIdentifier: String,
        timestamp: Long?
    ): String? =
        runCatching {
            // Reached only from the v1 branches: postV1Treatment runs in the else of
            // `if (useV3)`, and resolveDeleteRemoteId returns before this when v3 is on.
            val array = JSONArray(fetchTreatmentsJson(baseUrl, secret, useV3 = false))
            for (index in 0 until array.length()) {
                val treatment = array.optJSONObject(index) ?: continue
                if (!treatment.optString("identifier").equals(localIdentifier, ignoreCase = false)) continue
                val remoteId = treatment.optNightscoutDocumentId() ?: continue
                val date = treatment.optLong("date", 0L)
                if (timestamp == null || date == 0L || kotlin.math.abs(date - timestamp) <= 60_000L) {
                    return@runCatching remoteId
                }
            }
            null
        }.getOrNull()

    private fun JSONObject.optNightscoutDocumentId(): String? =
        optString("_id").trim().takeIf { it.isNotBlank() }
            ?: optString("id").trim().takeIf { it.isNotBlank() }

    private fun receiveRemoteTreatments(baseUrl: String, secret: String, useV3: Boolean): Boolean =
        runCatching {
            val body = fetchTreatmentsJson(baseUrl, secret, useV3)
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

    private fun fetchTreatmentsJson(baseUrl: String, secret: String, useV3: Boolean): String {
        val normalized = NightscoutFollowerRegistry.normalizeUrl(baseUrl)
        if (normalized.isBlank()) return "[]"
        val endpoint = treatmentFetchUrl(normalized, useV3)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG Nightscout journal sync")
            // v3 reads want the same bearer token the v3 upload path already obtains and
            // caches; the configured secret is an access token there, and hashing it into an
            // api-secret header is what the 401 was.
            val v3Auth = if (useV3) NightPost.getV3AuthorizationHeader() else ""
            if (v3Auth.isNotEmpty()) {
                setRequestProperty("Authorization", v3Auth)
            } else {
                NightscoutFollowerRegistry.applyAuth(this, secret)
            }
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return "[]"
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${endpointPath(endpoint)}: ${serverMessage(body)}")
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
