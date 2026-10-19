package tk.glucodata.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.SensorIdentity

/** Where a flush reports what happened; the Android side persists it. */
internal interface DexcomShareStatusSink {
    fun recordSuccess(count: Int, newestMs: Long)
    fun recordFailure(code: Int, message: String)
    fun recordAuthBlocked()
    fun persistQueue()
}

/**
 * One flush of the pending queue: log in if there is no session, then upload in batches of
 * [DexcomShareJson.MAX_EGVS_PER_REQUEST].
 *
 * Everything that decides whether a failure may be retried is here rather than in
 * [DexcomShareUploader], so it can be tested without Android or a network. The rules that matter:
 * an expired session is re-logged in at most once per flush, bad credentials and lockouts stop
 * everything until the user acts, and a transient failure keeps the queue and backs off.
 *
 * @param clock injected so the tests can move time instead of waiting.
 */
internal class DexcomShareFlush(
    private val queue: DexcomShareQueue,
    private val sink: DexcomShareStatusSink,
    private val transportFor: (DexcomShareRegion) -> DexcomShareClient.Transport,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val INITIAL_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 30L * 60_000L

        /**
         * Logins Dexcom answered with an error, in a row, before the account is treated as
         * blocked. A wrong password Dexcom reports under a code this app does not know would
         * otherwise be retried on every backoff (about 48 times a day) until the account locks.
         * Transport failures do not count: they never reached Dexcom.
         */
        const val MAX_FAILED_LOGINS = 5
    }

    var sessionId: String? = null
        private set

    var authBlocked: Boolean = false
        private set

    private var backoffMs = 0L
    private var nextAttemptAtMs = 0L
    private var failedLogins = 0

    /** Either a session, or what [flush] must return because there is none. */
    private sealed class Login {
        object Ok : Login()
        class Stop(val delayMs: Long?) : Login()
    }

    /** The persisted flag from a previous process, restored when the engine is built. */
    @Synchronized
    fun markAuthBlocked(blocked: Boolean) {
        authBlocked = blocked
    }

    /**
     * @return the delay before the next attempt when one is scheduled, or null when there is
     *         nothing left to do (or nothing that may be tried).
     */
    @Synchronized
    fun flush(config: DexcomShareSettings.Config): Long? {
        val now = clock()
        if (!config.isReady() || authBlocked) return null
        if (now < nextAttemptAtMs) return nextAttemptAtMs - now

        val pending = queue.pending(now)
        if (pending.isEmpty()) return null

        val client = DexcomShareClient(config.region, transportFor(config.region))
        if (sessionId == null) {
            val login = logIn(client, config, now)
            if (login is Login.Stop) return login.delayMs
        }

        var relogged = false
        var index = 0
        while (index < pending.size) {
            val end = minOf(index + DexcomShareJson.MAX_EGVS_PER_REQUEST, pending.size)
            val chunk = pending.subList(index, end)
            when (val result = client.upload(sessionId!!, config.serial, chunk)) {
                is DexcomShareClient.UploadResult.Ok -> {
                    queue.markDelivered(chunk)
                    sink.recordSuccess(chunk.size, chunk.last().timeMs)
                    resetBackoff()
                    index = end
                }

                is DexcomShareClient.UploadResult.Failed -> when (result.kind) {
                    DexcomShareError.Kind.SESSION_EXPIRED -> {
                        // One re-login per flush: a server that keeps answering 500 must not
                        // turn into a login loop.
                        if (relogged) return delayNextAttempt(now, result.code, result.message)
                        relogged = true
                        sessionId = null
                        val login = logIn(client, config, now)
                        if (login is Login.Stop) return login.delayMs
                    }

                    DexcomShareError.Kind.BAD_CREDENTIALS,
                    DexcomShareError.Kind.LOCKED -> return blockAccount(result.code, result.message)

                    DexcomShareError.Kind.TRANSIENT ->
                        return delayNextAttempt(now, result.code, result.message)

                    DexcomShareError.Kind.REJECTED -> {
                        // This batch would never succeed; drop it and carry on with the rest.
                        queue.drop(chunk)
                        sink.recordFailure(result.code, result.message)
                        sink.persistQueue()
                        index = end
                    }
                }
            }
        }
        sink.persistQueue()
        return null
    }

    private fun logIn(client: DexcomShareClient, config: DexcomShareSettings.Config, now: Long): Login =
        when (val login = client.login(config.account, config.password, config.serial)) {
            is DexcomShareClient.LoginResult.Ok -> {
                sessionId = login.sessionId
                failedLogins = 0
                Login.Ok
            }
            is DexcomShareClient.LoginResult.Failed -> {
                if (login.code != DexcomShareClient.TRANSPORT_FAILURE_CODE) failedLogins++
                when {
                    login.kind == DexcomShareError.Kind.BAD_CREDENTIALS ||
                        login.kind == DexcomShareError.Kind.LOCKED ||
                        failedLogins >= MAX_FAILED_LOGINS ->
                        Login.Stop(blockAccount(login.code, login.message))
                    else -> Login.Stop(delayNextAttempt(now, login.code, login.message))
                }
            }
        }

    private fun blockAccount(code: Int, message: String): Long? {
        authBlocked = true
        sink.recordFailure(code, message)
        sink.recordAuthBlocked()
        sink.persistQueue()
        return null
    }

    private fun delayNextAttempt(now: Long, code: Int, message: String): Long {
        sink.recordFailure(code, message)
        backoffMs = if (backoffMs <= 0L) {
            INITIAL_BACKOFF_MS
        } else {
            (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        nextAttemptAtMs = now + backoffMs
        sink.persistQueue()
        return backoffMs
    }

    private fun resetBackoff() {
        backoffMs = 0L
        nextAttemptAtMs = 0L
    }
}

/**
 * Sends what the app already produces to the user's Dexcom Share publisher account, so Dexcom
 * Follow and the followers built on Share (xDrip+, Nightscout's bridge, Loop) show it.
 *
 * Phone only: [tk.glucodata.SuperGattCallback] calls [onLiveReading] only when it is not the
 * watch, because the credentials and the queue live on the phone. Nothing here blocks the caller:
 * the queue is updated inline and the network work is handed to a single background thread.
 */
@Keep
object DexcomShareUploader {
    private const val LOG_ID = "DexcomShare"
    private const val THREAD_NAME = "DexcomShareSend"

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, THREAD_NAME) }

    /** A stored reading as the history writer hands it over. */
    data class Row(val timeMs: Long, val mgdl: Int, val trendIndex: Int)

    data class Result(val ok: Boolean, val message: String)

    /** How long after history was held to look again, so held rows go out even if live stops. */
    private const val HELD_RECHECK_MS = DexcomShareQueue.DEFAULT_MAX_HOLD_MS + 5_000L

    /**
     * How often to look again while there is no network and readings wait. Only a connectivity
     * check runs, so it costs nothing on the network; the queue's 24-hour window bounds it.
     */
    private const val OFFLINE_RETRY_MS = 5L * 60_000L

    /**
     * @param destination where this queue's readings go (region, account, serial); a queue is only
     *        carried into new settings that send to the same place.
     * @param enabledAtMs the switch-on moment the queue was built for; switching Share off and on
     *        again starts a fresh queue.
     */
    private class State(
        val queue: DexcomShareQueue,
        val engine: DexcomShareFlush,
        val sink: AndroidSink,
        val destination: String,
        val enabledAtMs: Long
    )

    private val lock = Any()
    private var state: State? = null

    /** The state a settings change replaced, kept until the next one is built (see [stateFor]). */
    private var retired: State? = null

    /**
     * Bumped whenever the settings change. A flush started under older settings may still be
     * running; its results (a blocked login, the status, the queue) must not overwrite what the
     * new settings start from.
     */
    @Volatile
    private var generation = 0

    /** 0 while nothing is queued; otherwise the time the pending flush is due. */
    private var scheduledAtMs = 0L
    private var scheduled: ScheduledFuture<*>? = null

    /** A live reading. Called on the BLE callback thread, so it must not block or throw. */
    @JvmStatic
    fun onLiveReading(sensorId: String?, mgdl: Int, trendIndex: Int, timeMs: Long) {
        if (sensorId.isNullOrEmpty() || mgdl <= 0 || timeMs <= 0L) return
        try {
            val context = Applic.app ?: return
            if (!DexcomShareSettings.isEnabled(context)) return
            val current = stateFor(context)
            val accepted = current.queue.offerLive(
                sensorKey(sensorId),
                ShareEgv(timeMs, mgdl, DexcomShareJson.normalizeTrend(trendIndex)),
                System.currentTimeMillis()
            )
            if (accepted) {
                // Saved now, not only when a flush runs: offline or blocked, no flush gets that
                // far, and a restart or a settings save would bring back the older queue.
                current.sink.persistQueue()
                requestFlush(context)
            }
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onLiveReading", error)
        }
    }

    /**
     * Readings the history writer just stored, which the live path may have missed. Only the
     * sensor that produced the last live reading is backfilled, and only for readings newer than
     * the moment Share was switched on — see [DexcomShareQueue.offerBackfill].
     */
    @JvmStatic
    fun onHistoryStored(sensorSerial: String, rows: List<Row>) {
        if (sensorSerial.isEmpty() || rows.isEmpty()) return
        try {
            val context = Applic.app ?: return
            if (!DexcomShareSettings.isEnabled(context)) return
            val current = stateFor(context)
            val accepted = current.queue.offerBackfill(
                sensorKey(sensorSerial),
                rows.filter { it.mgdl > 0 }.map {
                    ShareEgv(it.timeMs, it.mgdl, DexcomShareJson.normalizeTrend(it.trendIndex))
                },
                System.currentTimeMillis()
            )
            val held = current.queue.heldCount()
            if (accepted > 0 || held > 0) current.sink.persistQueue()
            when {
                accepted > 0 -> requestFlush(context)
                // Nothing to send yet, but held rows must not wait for a live reading that may
                // never come (the sensor stopped): look again once they can be released.
                held > 0 -> requestFlush(context, HELD_RECHECK_MS)
            }
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "onHistoryStored", error)
        }
    }

    /**
     * Saves the settings the screen holds and starts over from them: a new session, and a flush
     * still waiting out a backoff is cancelled, or the new settings would sit unused for up to
     * [DexcomShareFlush.MAX_BACKOFF_MS].
     *
     * The save and the [generation] bump happen under [lock], the same lock every background
     * result is checked and written under. A login or Test still running for the old settings
     * therefore cannot slip its result in between the save and the bump.
     *
     * @param flush false when the caller is about to run [testConnection]: that test logs in
     *        itself and starts the flush when it succeeds, so flushing here too would spend a
     *        second login, and with a wrong password a second failed attempt, on one tap.
     */
    fun applySettings(context: Context, config: DexcomShareSettings.Config, flush: Boolean = true) {
        try {
            val appContext = context.applicationContext
            val destination = destinationOf(config)
            synchronized(lock) {
                // Switching on starts a new era: the queue of the previous one must not survive,
                // not even through a flush that is still running across this save.
                val switchingOn = config.enabled && !DexcomShareSettings.isEnabled(appContext)
                generation++
                DexcomShareSettings.save(appContext, config)
                val live = state
                // Only a state that still sends to the same place may hand its queue over. A
                // destination change must not keep the old queue alive even in memory, or a flush
                // of the old settings could write it back after the save cleared it.
                retired = when {
                    switchingOn -> null
                    live != null -> live.takeIf { it.destination == destination }
                    else -> retired?.takeIf { it.destination == destination }
                }
                state = null
            }
            cancelScheduledFlush()
            if (flush) requestFlush(appContext)
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "applySettings", error)
        }
    }

    /**
     * The settings screen's Test button. Runs the full login sequence off the UI thread and hands
     * the outcome back; a success clears a blocked login and starts sending again.
     *
     * The outcome only touches the saved state if the settings are still the ones the Test was
     * started for: an old Test returning after the user corrected and saved the password must not
     * block the new one, and an old success must not clear a newer block.
     */
    fun testConnection(
        context: Context,
        config: DexcomShareSettings.Config,
        callback: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        val startedIn = generation
        // The verdict belongs to these credentials and this destination, not to the generation
        // alone: the screen saves again on dispose, and a save that leaves the login target
        // unchanged must not make a rejected password look stale.
        val startedFor = config.copy(serial = DexcomShareSettings.normalizeSerial(config.serial))
        executor.execute {
            val serial = startedFor.serial
            var rejected = false
            val result = try {
                val client = DexcomShareClient(startedFor.region, DexcomShareClient.HttpTransport)
                when (val login = client.login(startedFor.account, startedFor.password, serial)) {
                    is DexcomShareClient.LoginResult.Ok -> Result(true, "Connected")
                    is DexcomShareClient.LoginResult.Failed -> {
                        rejected = login.kind == DexcomShareError.Kind.BAD_CREDENTIALS ||
                            login.kind == DexcomShareError.Kind.LOCKED
                        Result(false, login.message)
                    }
                }
            } catch (error: Throwable) {
                Log.stack(LOG_ID, "testConnection", error)
                Result(false, error.message ?: "Connection failed")
            }
            if (result.ok) {
                val current = synchronized(lock) {
                    if (!outcomeApplies(appContext, startedIn, startedFor)) return@synchronized false
                    DexcomShareSettings.setAuthBlocked(appContext, false)
                    generation++
                    state?.let { retired = it }
                    state = null
                    true
                }
                if (current) {
                    cancelScheduledFlush()
                    requestFlush(appContext)
                }
            } else if (rejected) {
                val engine = synchronized(lock) {
                    if (!outcomeApplies(appContext, startedIn, startedFor)) return@synchronized null
                    DexcomShareSettings.setAuthBlocked(appContext, true)
                    state?.engine
                }
                // An engine built while the Test was running read the flag as false; it must not
                // try the password Dexcom has just rejected. Called outside the lock: the engine's
                // own monitor is always taken before [lock], never after.
                engine?.markAuthBlocked(true)
            }
            runCatching { callback(result) }.onFailure { Log.stack(LOG_ID, "testConnection callback", it) }
        }
    }

    /**
     * Whether a background outcome — a status write, a blocked login, a Test verdict — still
     * describes the saved settings.
     *
     * The [generation] alone is too strict: the screen saves again on the way out, and a flush or
     * a Test may still be in flight. Discarding its rejection for credentials that the save did
     * not change would leave `auth_blocked` false and let the next flush retry a password Dexcom
     * has just rejected. Outcomes for genuinely changed credentials are still dropped.
     */
    private fun outcomeApplies(
        context: Context,
        startedIn: Int,
        startedFor: DexcomShareSettings.Config
    ): Boolean {
        if (generation == startedIn) return true
        val saved = runCatching { DexcomShareSettings.load(context) }.getOrNull() ?: return false
        return saved.sameLoginTarget(startedFor)
    }

    /**
     * The queue and the session state, restored lazily from the prefs on first use.
     *
     * After a settings change the in-memory queue of the replaced state is carried over when the
     * readings still go to the same place. An upload that was running across the save records its
     * deliveries in that queue object; rebuilding from the prefs instead would drop the record
     * (the old flush may no longer write) and send the same batch again.
     */
    private fun stateFor(context: Context): State {
        synchronized(lock) {
            state?.let { return it }
            val config = DexcomShareSettings.load(context)
            val destination = destinationOf(config)
            val enabledAtMs = DexcomShareSettings.enabledAtMs(context)
            val carried = retired
                ?.takeIf { it.destination == destination && it.enabledAtMs == enabledAtMs }
                ?.queue
            retired = null
            val queue = carried ?: DexcomShareSettings.queueJson(context)
                .takeUnless { it.isNullOrBlank() }
                ?.let(DexcomShareQueue::decode)
                ?: DexcomShareQueue(enabledAtMs = enabledAtMs)
            val sink = AndroidSink(context, queue, generation, config)
            val engine = DexcomShareFlush(
                queue = queue,
                sink = sink,
                transportFor = { DexcomShareClient.HttpTransport },
            )
            engine.markAuthBlocked(DexcomShareSettings.isAuthBlocked(context))
            val created = State(queue, engine, sink, destination, enabledAtMs)
            state = created
            return created
        }
    }

    private fun destinationOf(config: DexcomShareSettings.Config): String =
        "${config.region.name}|${config.account}|${config.serial}"

    /**
     * Makes sure a flush runs within [delayMs]. A flush already due sooner is left alone; one due
     * later (a backoff, a held-row recheck) is moved up. Moving it up costs nothing: the engine
     * still refuses to try before its own backoff ends.
     */
    private fun requestFlush(context: Context, delayMs: Long = 0L) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val dueAtMs = System.currentTimeMillis() + delayMs
            if (scheduledAtMs != 0L && scheduledAtMs <= dueAtMs) return
            scheduled?.cancel(false)
            scheduledAtMs = dueAtMs
            scheduled = executor.schedule({ runFlush(appContext) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelScheduledFlush() {
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = null
            scheduledAtMs = 0L
        }
    }

    private fun runFlush(context: Context) {
        synchronized(lock) {
            scheduledAtMs = 0L
            scheduled = null
        }
        try {
            val config = DexcomShareSettings.load(context)
            if (!config.isReady()) return
            val current = stateFor(context)
            if (!hasUsableNetwork(context)) {
                // No attempt and no recorded failure, but keep a retry armed: the sensor's last
                // readings may have been queued offline, and nothing else may ever call in again.
                val waiting = current.queue.pending(System.currentTimeMillis()).isNotEmpty() ||
                    current.queue.heldCount() > 0
                if (waiting) requestFlush(context, OFFLINE_RETRY_MS)
                return
            }
            // The saved flag is the truth: a Test may have blocked the login after this engine
            // was built.
            if (DexcomShareSettings.isAuthBlocked(context)) current.engine.markAuthBlocked(true)
            val delay = current.engine.flush(config)
            when {
                delay != null -> requestFlush(context, delay)
                current.queue.heldCount() > 0 -> requestFlush(context, HELD_RECHECK_MS)
            }
        } catch (error: Throwable) {
            Log.stack(LOG_ID, "flush", error)
        }
    }

    /** Same rule as the outbound API: no network means no attempt, and no recorded failure. */
    private fun hasUsableNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sensorKey(sensorId: String): String =
        runCatching { SensorIdentity.resolveAppSensorId(sensorId) }.getOrNull() ?: sensorId

    /**
     * Writes a flush's results unless the settings have become another login target since it
     * started. "Another login target", not "another generation": a save that leaves the
     * credentials and the destination alone (the screen saves on the way out) must not discard a
     * rejected password — that is exactly how the next flush would retry it.
     *
     * Each check and its write happen under [lock], which [applySettings] holds while it saves and
     * bumps the generation, so a result can never land between the two.
     */
    private class AndroidSink(
        private val context: Context,
        private val queue: DexcomShareQueue,
        private val startedIn: Int,
        private val startedFor: DexcomShareSettings.Config
    ) : DexcomShareStatusSink {
        private inline fun ifCurrent(write: () -> Unit) {
            synchronized(lock) { if (outcomeApplies(context, startedIn, startedFor)) write() }
        }

        override fun recordSuccess(count: Int, newestMs: Long) =
            ifCurrent { DexcomShareSettings.recordSuccess(context, count, newestMs) }

        override fun recordFailure(code: Int, message: String) =
            ifCurrent { DexcomShareSettings.recordFailure(context, code, message) }

        override fun recordAuthBlocked() = ifCurrent { DexcomShareSettings.setAuthBlocked(context, true) }

        /**
         * Called from the live callback, the history writer and the send thread. Encoding and
         * writing under one lock keeps the saves in order: `apply()` updates the in-memory prefs
         * at once, so whichever snapshot is encoded last is also the one that stays.
         *
         * Not fenced by the settings generation: after a password-only save the queue object is
         * carried into the new state, and a flush that was running across the save records its
         * deliveries in exactly that object. Refusing that write would leave the prefs with the
         * batch still pending, and the next process start would send it again. The identity check
         * keeps a queue that a destination change replaced out of the prefs instead.
         */
        override fun persistQueue() = synchronized(lock) {
            val stillCurrent = state?.queue === queue || retired?.queue === queue
            if (!stillCurrent) return@synchronized
            DexcomShareSettings.saveQueueJson(context, queue.encode())
            DexcomShareSettings.recordPending(context, queue.pending(System.currentTimeMillis()).size)
        }
    }
}
