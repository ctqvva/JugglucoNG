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
        val token = settings.token
        if (token.isBlank()) {
            return FlushResult.Unauthorized(0)
        }
        val all = queue.peek()
        if (all.isEmpty()) {
            return FlushResult.Success(0)
        }
        var uploadedTotal = 0
        val bySerial = all.groupBy { it.serial }
        for ((serial, items) in bySerial) {
            var index = 0
            while (index < items.size) {
                val end = minOf(index + BATCH_SIZE, items.size)
                val batch = items.subList(index, end).toList()
                var attempt = 0
                var success = false
                var unauthorized = false
                var lastCode = 0
                var lastMessage = ""
                while (attempt < MAX_RETRIES && !success && !unauthorized) {
                    attempt++
                    when (val result = client.uploadGlucose(token, serial, batch)) {
                        is UploadResult.Ok -> success = true
                        is UploadResult.Failed -> {
                            if (result.code == 401) {
                                unauthorized = true
                                lastCode = result.code
                                lastMessage = result.message
                            } else {
                                lastCode = result.code
                                lastMessage = result.message
                                if (attempt < MAX_RETRIES) {
                                    sleep(backoffMs(attempt))
                                }
                            }
                        }
                    }
                }
                if (unauthorized) {
                    settings.token = ""
                    return FlushResult.Unauthorized(uploadedTotal)
                }
                if (!success) {
                    return FlushResult.Failed(uploadedTotal, lastCode, lastMessage)
                }
                queue.remove(batch)
                uploadedTotal += batch.size
                index = end
            }
        }
        return FlushResult.Success(uploadedTotal)
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

        private val executor: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, THREAD_NAME)
            }

        private val lock = Any()
        private var state: State? = null

        private class State(
            val queue: ICanHealthCloudQueue,
            val client: ICanHealthCloudClient,
            val settings: ICanHealthCloudSettings,
        )

        @JvmStatic
        fun onHistoryStored(sensorSerial: String, rows: List<ICanHealthCloudUploader.Row>) {
            if (sensorSerial.isEmpty() || rows.isEmpty()) return
            try {
                val context = tk.glucodata.Applic.app ?: return
                if (!ICanHealthCloudSettings.isEnabled(context)) return
                val current = stateFor(context)
                val accepted = current.queue.let { q ->
                    var count = 0
                    for (row in rows.filter { it.mgdl > 0 }) {
                        val egv = EgvUpload(row.serial, row.timeMs, row.mgdl, row.trendIndex)
                        val before = q.size()
                        q.enqueue(egv)
                        if (q.size() > before) count++
                    }
                    count
                }
                if (accepted > 0) {
                    requestFlush(context)
                }
            } catch (error: Throwable) {
                tk.glucodata.Log.stack(LOG_ID, "onHistoryStored", error)
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

        private fun requestFlush(context: Context) {
            val appContext = context.applicationContext
            synchronized(lock) {
                executor.execute { runFlush(appContext) }
            }
        }

        private fun runFlush(context: Context) {
            try {
                val current = stateFor(context)
                ICanHealthCloudUploader(current.client, current.settings, current.queue).flush()
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