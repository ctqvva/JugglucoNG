package tk.glucodata.share.ican

import android.content.Context
import tk.glucodata.share.ican.ICanHealthCloudClient.UploadResult

class ICanHealthCloudUploader(
    private val client: ICanHealthCloudClient,
    private val settings: ICanHealthCloudSettings,
    private val queue: ICanHealthCloudQueue,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    data class Row(val serial: String, val timeMs: Long, val mgdl: Int, val trendIndex: Int)

    @Synchronized
    fun flush(): FlushResult {
        var session = settings.session()
        if (session.token.isBlank()) return FlushResult.Unauthorized(0)
        if (!settings.enabled) return FlushResult.Disabled(0)
        val all = synchronized(ICanHealthCloudSettings.sessionLock) {
            if (settings.session() != session) return FlushResult.Unauthorized(0)
            queue.peek()
        }
        var uploadedTotal = 0
        var refreshed = false
        for ((serial, items) in all.groupBy { it.serial }) {
            for (batch in items.chunked(BATCH_SIZE)) {
                var attempt = 0
                while (true) {
                    // Revalidate before every request, including retries after a backoff.
                    synchronized(ICanHealthCloudSettings.sessionLock) {
                        if (!settings.enabled) return FlushResult.Disabled(uploadedTotal)
                        if (settings.session() != session) return FlushResult.Unauthorized(uploadedTotal)
                    }
                    val result = client.uploadGlucose(session.token, serial, batch)
                    synchronized(ICanHealthCloudSettings.sessionLock) {
                        if (settings.session() != session) return FlushResult.Unauthorized(uploadedTotal)
                        if (result is UploadResult.Ok) {
                            queue.remove(batch)
                            uploadedTotal += batch.size
                            settings.lastSyncMs = maxOf(settings.lastSyncMs, batch.maxOf { it.timeMs })
                        }
                    }
                    if (result is UploadResult.Ok) break
                    result as UploadResult.Failed
                    if (result.kind == ICanHealthCloudError.Kind.UNAUTHORIZED) {
                        // A server that keeps rejecting a fresh token must not create an endless loop.
                        val renewed = if (!refreshed && settings.enabled) relogin(session) else null
                        if (renewed != null) {
                            refreshed = true
                            session = renewed
                            continue
                        }
                        synchronized(ICanHealthCloudSettings.sessionLock) {
                            if (settings.session() == session) settings.token = ""
                        }
                        return FlushResult.Unauthorized(uploadedTotal)
                    }
                    attempt++
                    if (result.kind != ICanHealthCloudError.Kind.TRANSIENT || attempt >= MAX_RETRIES) {
                        return FlushResult.Failed(uploadedTotal, result.code, result.message, result.kind)
                    }
                    sleep(backoffMs(attempt))
                }
            }
        }
        return FlushResult.Success(uploadedTotal)
    }

    private fun relogin(session: ICanHealthCloudSettings.Session): ICanHealthCloudSettings.Session? {
        val credentials = synchronized(ICanHealthCloudSettings.sessionLock) {
            if (!settings.enabled || settings.session() != session) return null
            settings.account to settings.password
        }
        if (credentials.first.isBlank() || credentials.second.isBlank()) return null
        val result = client.login(credentials.first, credentials.second)
        if (result !is ICanHealthCloudClient.LoginResult.Ok) return null
        return synchronized(ICanHealthCloudSettings.sessionLock) {
            if (!settings.enabled || settings.session() != session) return null
            // Automatic refresh may replace the token, never the patient owning the queued data.
            if (result.model.userId.isNotBlank() && settings.userId.isNotBlank() &&
                result.model.userId != settings.userId) return null
            if (result.model.tenantId.isNotBlank() && settings.tenantId.isNotBlank() &&
                result.model.tenantId != settings.tenantId) return null
            settings.token = result.sessionId
            if (result.model.refreshToken.isNotEmpty()) settings.refreshToken = result.model.refreshToken
            settings.session()
        }
    }

    sealed class FlushResult(
        open val uploaded: Int = 0,
        open val success: Boolean = false,
        open val unauthorized: Boolean = false,
        open val code: Int = 0,
        open val message: String = "",
    ) {
        data class Success(override val uploaded: Int = 0) : FlushResult(uploaded, true, false, 0, "")
        data class Ok(override val uploaded: Int = 0) : FlushResult(uploaded, true, false, 0, "")
        data class Completed(override val uploaded: Int = 0) : FlushResult(uploaded, true, false, 0, "")
        data class Done(override val uploaded: Int = 0) : FlushResult(uploaded, true, false, 0, "")
        data class Finished(override val uploaded: Int = 0) : FlushResult(uploaded, true, false, 0, "")
        data class Unauthorized(override val uploaded: Int = 0, override val message: String = "") :
            FlushResult(uploaded, false, true, 401, message)
        data class AuthFailed(override val uploaded: Int = 0, override val message: String = "") :
            FlushResult(uploaded, false, true, 401, message)
        data class Unauthenticated(override val uploaded: Int = 0, override val message: String = "") :
            FlushResult(uploaded, false, true, 401, message)
        data class Disabled(override val uploaded: Int = 0) :
            FlushResult(uploaded, false, false, 0, "disabled")
        data class Failed(
            override val uploaded: Int = 0,
            override val code: Int = 0,
            override val message: String = "",
            val kind: ICanHealthCloudError.Kind? = null,
        ) : FlushResult(uploaded, false, false, code, message)
        data class Error(override val uploaded: Int = 0, override val code: Int = 0, override val message: String = "") :
            FlushResult(uploaded, false, false, code, message)
        data class Transient(override val uploaded: Int = 0, override val code: Int = 0, override val message: String = "") :
            FlushResult(uploaded, false, false, code, message)
        object Empty : FlushResult(0, true, false, 0, "")
    }

    companion object {
        private const val LOG_ID = "ICanHealthCloud"
        private const val THREAD_NAME = "ICanHealthCloudSend"
        private const val BATCH_SIZE = 60
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 100L
        private const val RETRY_DELAY_MS = 5L * 60_000L

        private val executor: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, THREAD_NAME)
            }

        private val lock = Any()
        private var state: State? = null
        private var scheduledFlush: java.util.concurrent.ScheduledFuture<*>? = null
        private var scheduledAtMs = 0L

        private class State(
            val queue: ICanHealthCloudQueue,
            val client: ICanHealthCloudClient,
            val settings: ICanHealthCloudSettings,
        )

        @JvmStatic
        fun onHistoryStored(
            sensorSerial: String,
            rows: List<ICanHealthCloudUploader.Row>,
            expectedAccountKey: String? = null,
        ) {
            if (sensorSerial.isEmpty() || rows.isEmpty()) return
            try {
                val context = tk.glucodata.Applic.app ?: return
                if (!ICanHealthCloudSettings.isEnabled(context)) return
                val current = stateFor(context)
                val accepted = synchronized(ICanHealthCloudSettings.sessionLock) {
                    if (!current.settings.enabled || (expectedAccountKey != null &&
                        current.settings.accountKey != expectedAccountKey)) return
                    current.queue.enqueueAll(
                        rows.filter { it.mgdl > 0 }.map {
                            EgvUpload(it.serial, it.timeMs, it.mgdl, it.trendIndex)
                        },
                    )
                }
                if (accepted > 0) {
                    requestFlush(context)
                }
            } catch (error: Throwable) {
                tk.glucodata.Log.stack(LOG_ID, "onHistoryStored", error)
            }
        }

        @JvmStatic
        fun onLiveReading(sensorSerial: String, timeMs: Long, mgdl: Int, trendIndex: Int) {
            if (sensorSerial.isEmpty() || mgdl <= 0 || timeMs <= 0L) return
            try {
                val context = tk.glucodata.Applic.app ?: return
                if (!ICanHealthCloudSettings.isEnabled(context)) return
                val appContext = context.applicationContext
                val accountKey = ICanHealthCloudSettings(appContext).session().accountKey
                synchronized(lock) {
                    executor.execute {
                        try {
                            val current = stateFor(appContext)
                            synchronized(ICanHealthCloudSettings.sessionLock) {
                                if (!current.settings.enabled || current.settings.accountKey != accountKey) return@execute
                                current.queue.enqueue(EgvUpload(sensorSerial, timeMs, mgdl, trendIndex))
                            }
                            requestFlush(appContext)
                        } catch (error: Throwable) {
                            tk.glucodata.Log.stack(LOG_ID, "onLiveReading", error)
                        }
                    }
                }
            } catch (error: Throwable) {
                tk.glucodata.Log.stack(LOG_ID, "onLiveReading", error)
            }
        }

        private fun stateFor(context: Context): State {
            synchronized(lock) {
                state?.let { return it }
                val settings = ICanHealthCloudSettings(context.applicationContext)
                val queue = ICanHealthCloudQueue(context.applicationContext)
                val client = ICanHealthCloudClient()
                val created = State(queue, client, settings)
                state = created
                return created
            }
        }

        private fun requestFlush(context: Context, delayMs: Long = 0L) {
            val appContext = context.applicationContext
            synchronized(lock) {
                val dueAt = System.currentTimeMillis() + delayMs
                if (scheduledAtMs != 0L && scheduledAtMs <= dueAt) return
                scheduledFlush?.cancel(false)
                scheduledAtMs = dueAt
                scheduledFlush = executor.schedule({ runFlush(appContext) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }

        private fun runFlush(context: Context) {
            synchronized(lock) {
                scheduledAtMs = 0L
                scheduledFlush = null
            }
            try {
                val current = stateFor(context)
                val result = ICanHealthCloudUploader(current.client, current.settings, current.queue).flush()
                if (result is FlushResult.Failed && result.kind == ICanHealthCloudError.Kind.TRANSIENT) {
                    requestFlush(context, RETRY_DELAY_MS)
                }
            } catch (error: Throwable) {
                tk.glucodata.Log.stack(LOG_ID, "runFlush", error)
            }
        }

        private fun backoffMs(attempt: Int): Long {
            return INITIAL_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 10))
        }
    }
}

typealias FlushResult = ICanHealthCloudUploader.FlushResult