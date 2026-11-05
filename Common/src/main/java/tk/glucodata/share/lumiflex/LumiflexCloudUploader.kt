package tk.glucodata.share.lumiflex

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SensorSourceResolver
import tk.glucodata.drivers.aidex.AiDexSerialIdentity

class LumiflexCloudUploader(
    private val client: LumiflexCloudClient,
    private val settings: LumiflexCloudSettings,
    private val queue: LumiflexCloudQueue,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val sensorIds: (String) -> LumiflexCloudClient.DeviceList = {
        LumiflexCloudClient.DeviceList.Ok(emptyMap())
    },
) {

    data class Row(
        val serial: String,
        val timeMs: Long,
        val mgdl: Int,
        val trendIndex: Int,
    )

    sealed class FlushResult {
        data class Success(val uploaded: Int) : FlushResult()
        data class WaitingForSensor(val uploaded: Int) : FlushResult()
        data class Unauthorized(val uploaded: Int) : FlushResult()
        data class Failed(
            val uploaded: Int, val code: Int, val message: String,
            val kind: LumiflexCloudError.Kind = LumiflexCloudError.classify(code, null),
        ) : FlushResult()
    }

    @Synchronized
    fun flush(): FlushResult {
        if (!settings.enabled) return FlushResult.Success(0)
        val session = settings.session()
        settings.recordSync(session, LumiflexCloudSettings.SyncState.SYNCING, queue.size())
        val result = flushSession(session)
        val (state, uploaded, message) = when (result) {
            is FlushResult.Success -> Triple(
                if (result.uploaded > 0 || settings.lastSyncMs > 0) LumiflexCloudSettings.SyncState.SYNCED
                else LumiflexCloudSettings.SyncState.IDLE, result.uploaded, "",
            )
            is FlushResult.WaitingForSensor -> Triple(LumiflexCloudSettings.SyncState.WAITING_FOR_SENSOR, result.uploaded, "")
            is FlushResult.Failed -> Triple(LumiflexCloudSettings.SyncState.ERROR, result.uploaded, result.message)
            is FlushResult.Unauthorized -> Triple(LumiflexCloudSettings.SyncState.SIGN_IN_REQUIRED, result.uploaded, "")
        }
        settings.recordSync(session, state, queue.size(), message, uploaded,
            retrying = result is FlushResult.Failed && result.kind == LumiflexCloudError.Kind.TRANSIENT)
        return result
    }

    private fun flushSession(session: LumiflexCloudSettings.Session): FlushResult {
        if (!session.signedIn) return FlushResult.Unauthorized(0)

        var usable: List<CgmRecord> = emptyList()
        if (!settings.withCurrentSession(session) {
            queue.clearIfOwnerMismatch(session.account)
            val pending = queue.peek()
            val unusable = pending.filter { it.mgdl <= 0 }
            if (unusable.isNotEmpty()) queue.remove(unusable, session.account)
            usable = pending.filter { it.mgdl > 0 }
        }) return FlushResult.Success(0)
        if (usable.isEmpty()) return FlushResult.Success(0)

        var uploaded = 0
        var missingSensor = false
        val sensorIdBySerial = when (val devices = sensorIds(session.token)) {
            is LumiflexCloudClient.DeviceList.Ok -> devices.sensorIdBySerial
            LumiflexCloudClient.DeviceList.Unauthorized -> {
                settings.invalidateSession(session)
                return FlushResult.Unauthorized(0)
            }
            is LumiflexCloudClient.DeviceList.Failed -> {
                return FlushResult.Failed(0, devices.code, devices.message)
            }
        }
        usable.groupBy { it.serial }.forEach { (serial, items) ->
            val sensorId = sensorIdFor(sensorIdBySerial, serial)
            if (sensorId.isNullOrBlank()) {
                missingSensor = true
                return@forEach
            }
            var index = 0
            while (index < items.size) {
                val end = minOf(index + LumiflexCloudConstants.BATCH_SIZE, items.size)
                val batch = items.subList(index, end)
                var attempt = 0
                var success = false
                var lastCode = 0
                var lastMessage = ""
                var lastKind = LumiflexCloudError.Kind.OTHER
                while (attempt < LumiflexCloudConstants.RETRY_LIMIT && !success) {
                    if (!settings.enabled) return FlushResult.Success(uploaded)
                    if (!settings.isCurrent(session)) return FlushResult.Success(uploaded)
                    attempt++
                    val result = client.uploadGlucose(
                        token = session.token,
                        userId = session.userId,
                        records = batch.map { it.copy(sensorId = sensorId) },
                        nowMs = System.currentTimeMillis(),
                    )
                    when (result) {
                        is LumiflexCloudClient.SyncResult.Ok -> success = true
                        is LumiflexCloudClient.SyncResult.Failed -> {
                            lastCode = result.code
                            lastMessage = result.message
                            lastKind = result.kind
                            if (result.kind == LumiflexCloudError.Kind.UNAUTHORIZED) {
                                settings.invalidateSession(session)
                                return FlushResult.Unauthorized(uploaded)
                            }
                            if (result.kind != LumiflexCloudError.Kind.TRANSIENT) {
                                return FlushResult.Failed(uploaded, result.code, result.message, result.kind)
                            }
                            if (attempt < LumiflexCloudConstants.RETRY_LIMIT) {
                                sleep(backoffMs(attempt))
                            }
                        }
                    }
                }
                if (!success) return FlushResult.Failed(uploaded, lastCode, lastMessage, lastKind)
                // A late reply must not delete the backlog of a session that replaced this one.
                var removed = false
                if (!settings.withCurrentSession(session) { removed = queue.remove(batch, session.account) } || !removed) {
                    return FlushResult.Success(uploaded)
                }
                uploaded += batch.size
                settings.recordSync(session, LumiflexCloudSettings.SyncState.SYNCING, queue.size(), uploaded = batch.size)
                index = end
            }
        }
        return if (missingSensor) FlushResult.WaitingForSensor(uploaded) else FlushResult.Success(uploaded)
    }

    companion object {
        private const val LOG_ID = "LumiflexCloud"
        private const val INITIAL_BACKOFF_MS = 100L

        private val lock = Any()
        private val flushLock = Any()
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
                val session = current.settings.session()
                if (!current.settings.withCurrentSession(session) {
                    current.queue.enqueueAll(records, session.account)
                    current.settings.recordSync(session, LumiflexCloudSettings.SyncState.QUEUED, current.queue.size())
                }) return
                LumiflexCloudSyncWorker.enqueue(context)
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

        internal var cloneOwnerProvider: (String) -> Boolean = { serial ->
            tk.glucodata.CloneSensorRegistry.isCloneSensor(serial)
        }

        internal fun sensorIdFor(bySerial: Map<String, String>, serial: String): String? {
            val bare = AiDexSerialIdentity.bareSerial(serial)
            return bySerial[serial] ?: bySerial[bare] ?: bySerial["X-$bare"]
        }

        internal fun shouldUpload(serial: String): Boolean =
            sensorKindProvider(serial) == SensorSourceResolver.SENSOR_KIND_AIDEX &&
                !cloneOwnerProvider(serial)

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

        fun pendingCount(context: Context): Int {
            val current = stateFor(context)
            return if (current.queue.owner() == current.settings.account) current.queue.size() else 0
        }

        /** Worker and manual requests share one queue and one in-process upload lock. */
        fun flushNow(context: Context): FlushResult = synchronized(flushLock) {
            val current = stateFor(context)
            val session = current.settings.session()
            try {
                val uploader = LumiflexCloudUploader(
                    client = current.client,
                    settings = current.settings,
                    queue = current.queue,
                    sensorIds = { token -> current.client.devicesBySensorSerial(token) },
                )
                uploader.flush()
            } catch (error: Throwable) {
                tk.glucodata.Log.stack(LOG_ID, "runFlush", error)
                current.settings.recordSync(session, LumiflexCloudSettings.SyncState.ERROR,
                    current.queue.size(), error.message.orEmpty(), retrying = true)
                FlushResult.Failed(0, -1, error.message.orEmpty())
            }
        }

        private fun backoffMs(attempt: Int): Long =
            INITIAL_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 10))
    }
}
