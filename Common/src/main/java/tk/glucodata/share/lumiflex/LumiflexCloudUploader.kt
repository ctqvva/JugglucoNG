package tk.glucodata.share.lumiflex

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SensorSourceResolver
import tk.glucodata.drivers.aidex.AiDexSerialIdentity

class LumiflexCloudUploader(
    private val client: LumiflexCloudClient,
    private val settings: LumiflexCloudSettings,
    private val queue: LumiflexCloudQueue,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val sensorIds: () -> Map<String, String>? = { emptyMap() },
) {

    data class Row(
        val serial: String,
        val timeMs: Long,
        val mgdl: Int,
        val trendIndex: Int,
    )

    sealed class FlushResult {
        data class Success(val uploaded: Int) : FlushResult()
        data class Unauthorized(val uploaded: Int) : FlushResult()
        data class Failed(val uploaded: Int, val code: Int, val message: String) : FlushResult()
    }

    @Synchronized
    fun flush(): FlushResult {
        if (!settings.enabled) return FlushResult.Success(0)
        val token = settings.token
        if (token.isBlank()) return FlushResult.Unauthorized(0)

        val pending = queue.peek()
        val unusable = pending.filter { it.mgdl <= 0 }
        if (unusable.isNotEmpty()) queue.remove(unusable)
        val usable = pending.filter { it.mgdl > 0 }
        if (usable.isEmpty()) return FlushResult.Success(0)

        var uploaded = 0
        val sensorIdBySerial = sensorIds()
            ?: return FlushResult.Failed(0, 0, "Device list unavailable")
        usable.groupBy { it.serial }.forEach { (serial, items) ->
            val sensorId = sensorIdFor(sensorIdBySerial, serial)
            if (sensorId.isNullOrBlank()) return@forEach
            var index = 0
            while (index < items.size) {
                val end = minOf(index + LumiflexCloudConstants.BATCH_SIZE, items.size)
                val batch = items.subList(index, end)
                var attempt = 0
                var success = false
                var lastCode = 0
                var lastMessage = ""
                while (attempt < LumiflexCloudConstants.RETRY_LIMIT && !success) {
                    if (!settings.enabled) return FlushResult.Success(uploaded)
                    attempt++
                    val result = client.uploadGlucose(
                        token = token,
                        userId = settings.userId,
                        records = batch.map { it.copy(sensorId = sensorId) },
                        nowMs = System.currentTimeMillis(),
                    )
                    when (result) {
                        is LumiflexCloudClient.SyncResult.Ok -> success = true
                        is LumiflexCloudClient.SyncResult.Failed -> {
                            lastCode = result.code
                            lastMessage = result.message
                            if (result.kind == LumiflexCloudError.Kind.UNAUTHORIZED) {
                                settings.token = ""
                                return FlushResult.Unauthorized(uploaded)
                            }
                            if (attempt < LumiflexCloudConstants.RETRY_LIMIT) {
                                sleep(backoffMs(attempt))
                            }
                        }
                    }
                }
                if (!success) return FlushResult.Failed(uploaded, lastCode, lastMessage)
                queue.remove(batch)
                uploaded += batch.size
                index = end
            }
        }
        if (uploaded > 0) settings.lastSyncMs = System.currentTimeMillis()
        return FlushResult.Success(uploaded)
    }

    companion object {
        private const val LOG_ID = "LumiflexCloud"
        private const val THREAD_NAME = "LumiflexCloudSend"
        private const val INITIAL_BACKOFF_MS = 100L

        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, THREAD_NAME) }

        private val lock = Any()
        private var state: State? = null

        private class State(
            val queue: LumiflexCloudQueue,
            val settings: LumiflexCloudSettings,
            val client: LumiflexCloudClient,
        )

        internal fun toRecords(rows: List<Row>, sensorStartMs: Long): List<CgmRecord> =
            rows.mapNotNull { row ->
                if (row.mgdl <= 0) return@mapNotNull null
                CgmRecord.fromRow(
                    serial = row.serial,
                    timeMs = row.timeMs,
                    mgdl = row.mgdl,
                    trend = row.trendIndex,
                    sensorStartMs = sensorStartMs,
                )
            }

        @JvmStatic
        fun onHistoryStored(sensorSerial: String, rows: List<Row>) {
            if (sensorSerial.isEmpty() || rows.isEmpty()) return
            try {
                val context: Context = Applic.app ?: return
                if (!LumiflexCloudSettings.isEnabled(context)) return
                if (!shouldUpload(sensorSerial)) return
                val current = stateFor(context)
                val start = sensorStartProvider(sensorSerial)
                val records = toRecords(rows, start)
                if (records.isEmpty()) return
                current.queue.enqueueAll(records)
                requestFlush(context)
            } catch (error: Throwable) {
                tk.glucodata.Log.stack(LOG_ID, "onHistoryStored", error)
            }
        }

        internal var sensorStartProvider: (String) -> Long = { serial ->
            runCatching {
                val pointer = Natives.str2sensorptr(serial)
                if (pointer == 0L) 0L else Natives.getSensorStartmsecFromSensorptr(pointer)
            }.getOrDefault(0L)
        }

        internal var sensorKindProvider: (String) -> Int = { serial ->
            runCatching {
                SensorSourceResolver.resolveSensorKind(serial, SensorSourceResolver.SENSOR_KIND_UNKNOWN)
            }.getOrDefault(SensorSourceResolver.SENSOR_KIND_UNKNOWN)
        }

        internal fun sensorIdFor(bySerial: Map<String, String>, serial: String): String? {
            val bare = AiDexSerialIdentity.bareSerial(serial)
            return bySerial[serial] ?: bySerial[bare] ?: bySerial["X-$bare"]
        }

        internal fun shouldUpload(serial: String): Boolean =
            sensorKindProvider(serial) == SensorSourceResolver.SENSOR_KIND_AIDEX

        private fun stateFor(context: Context): State {
            synchronized(lock) {
                state?.let { return it }
                val appContext = context.applicationContext
                val created = State(
                    LumiflexCloudQueue(appContext),
                    LumiflexCloudSettings(appContext),
                    LumiflexCloudClient(),
                )
                state = created
                return created
            }
        }

        private fun requestFlush(context: Context) {
            synchronized(lock) {
                executor.execute { runFlush(context.applicationContext) }
            }
        }

        private fun runFlush(context: Context) {
            try {
                val current = stateFor(context)
                val uploader = LumiflexCloudUploader(
                    client = current.client,
                    settings = current.settings,
                    queue = current.queue,
                    sensorIds = { current.client.devicesBySensorSerial(current.settings.token) },
                )
                uploader.flush()
            } catch (error: Throwable) {
                tk.glucodata.Log.stack(LOG_ID, "runFlush", error)
            }
        }

        private fun backoffMs(attempt: Int): Long =
            INITIAL_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 10))
    }
}
