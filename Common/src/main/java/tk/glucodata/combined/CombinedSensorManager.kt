package tk.glucodata.combined

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tk.glucodata.*
import tk.glucodata.alerts.*
import tk.glucodata.drivers.*

/** A derived source, with its own history and identity, using the normal primary-reading pipeline. */
class CombinedSensorManager(serial: String, val inputs: List<String>,
    private val nowMillis: () -> Long = System::currentTimeMillis) :
    SuperGattCallback(serial, 0L, 0), ManagedBluetoothSensorDriver {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val primary = CombinedPrimaryState(inputs)
    private val samples = mutableMapOf<String, CurrentDisplaySource.Snapshot>()
    @Volatile private var latest: VirtualGlucoseSensorBridge.Reading? = null
    @Volatile internal var result = DualSensorConsensus.Result()
    @Volatile internal var isStopped = false
    @Volatile private var assessmentExpiresAt = 0L
    private var restored = false
    private var revision = Long.MIN_VALUE
    private var modelSettings: Pair<Int, Int>? = null
    private var monitoring = false
    private var outputWasFresh = false
    override var viewMode: Int = 0

    override fun canConnectWithoutDataptr() = true
    override fun hasNativeSensorBacking() = true
    override fun shouldUseNativeHistorySync() = true
    override fun managesLiveRoomStorage() = true
    override fun integratesUserCalibration(isRawMode: Boolean) = true
    override fun shouldShowSearchingStatusWhenIdle() = false
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate() = false
    override fun matchesManagedSensorId(sensorId: String?) = SerialNumber.equals(sensorId, true)
    override fun mygetDeviceName(): String = Applic.app.getString(R.string.combined_glucose_source)
    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (inputs.size >= 2 && !Applic.isWearable && !isStopped && !monitoring) {
            CombinedSensorRegistry.register(this)
            monitoring = true
            worker.scheduleWithFixedDelay({ refresh() }, 0, 15, TimeUnit.SECONDS)
        }
        return true
    }
    internal fun observe(snapshot: CurrentDisplaySource.Snapshot) {
        if (!isStopped) runCatching { worker.execute {
            if (isStopped) return@execute
            val id = inputs.firstOrNull { SensorIdentity.matches(it, snapshot.sensorId) } ?: return@execute
            val old = samples[id]
            if (old == null || snapshot.timeMillis >= old.timeMillis) samples[id] = snapshot.copy(sensorId = id)
            refresh()
        } }
    }

    private fun refresh() {
        if (isStopped || CombinedSensorRegistry.manager !== this) return
        try {
            CombinedSensorRegistry.migrateDisplaySelection(this)
            val now = nowMillis()
            val currentRevision = CalibrationAccess.getRevision()
            if (revision != Long.MIN_VALUE && revision != currentRevision) {
                primary.resetLearning()
                samples.clear()
            }
            revision = currentRevision
            inputs.forEach { id ->
                val loaded = CurrentDisplaySource.resolveCurrent(DualSensorConsensus.MAX_AGE_MS, id)
                if (loaded != null && SensorIdentity.matches(id, loaded.sensorId) &&
                    (loaded.timeMillis > (samples[id]?.timeMillis ?: 0L) || loaded.viewMode != samples[id]?.viewMode)) samples[id] = loaded.copy(sensorId = id)
            }
            val configs = AlertRepository.loadConfig(AlertType.SENSOR_DISAGREEMENT)
            val settings = configs.disagreementPercent to (configs.durationMinutes ?: 5)
            if (modelSettings != settings) primary.resetLearning()
            modelSettings = settings
            val physical = inputs.mapNotNull { samples[it] }
            val readings = physical.map { snapshot ->
                val raw = snapshot.viewMode == 1 || snapshot.viewMode == 3
                val anchors = CalibrationAccess.getActiveCalibrationAnchors(snapshot.sensorId, raw)
                val last = (anchors.size - 3).takeIf { it >= 0 }
                DualSensorConsensus.Reading(snapshot.sensorId!!, snapshot.timeMillis,
                    snapshot.primaryValue * if (snapshot.isMmol) 18.0182f else 1f, snapshot.viewMode,
                    calibrationTimeMs = last?.let { anchors[it + 2].toLong() } ?: 0L,
                    calibrationReferenceMgdl = last?.let { anchors[it + 1].toFloat() * if (Applic.unit == 1) 18.0182f else 1f } ?: Float.NaN)
            }
            if (!restored) {
                // Never roll a restored source backwards or republish an already mirrored minute.
                CurrentGlucoseSource.getFresh(Long.MAX_VALUE, SerialNumber)?.let { stored ->
                    val value = stored.numericValue * if (Applic.unit == 1) 18.0182f else 1f
                    if (SensorIdentity.matches(stored.sensorId, SerialNumber) && value.isFinite() && value > 0f) {
                        primary.restore(CombinedPrimaryState.Output(stored.timeMillis, value, Float.NaN, false))
                        latest = VirtualGlucoseSensorBridge.Reading(stored.timeMillis, value, calibratedMgdl = value, rate = Float.NaN)
                    }
                }
                restored = true
            }
            val output = primary.update(readings, now, configs.disagreementPercent, configs.durationMinutes ?: 5)
            assessmentExpiresAt = readings.minOfOrNull { it.timeMs + DualSensorConsensus.MAX_AGE_MS } ?: 0L
            if (result != primary.result) {
                result = primary.result
                CombinedSensorRegistry.assessmentChanged()
            }
            if (output == null) return
            val next = VirtualGlucoseSensorBridge.Reading(output.timeMs, output.mgdl,
                calibratedMgdl = output.mgdl, rate = output.rate)
            CombinedSensorRegistry.publishIfCurrent(this) {
                CombinedTrendHistory.recordOutput(SerialNumber, output.timeMs, startsSegment = !output.rate.isFinite())
                latest = next
                VirtualGlucoseSensorBridge.publishCurrent(SerialNumber, next, 0, "combined sensors",
                    mirrorToNative = true, source = GlucoseReadingSource.COMBINED)
                exportCombinedToHealthConnect()
            }
        } catch (t: Throwable) { Log.stack("CombinedSensor", "refresh", t) }
        finally {
            val fresh = getManagedCurrentSnapshot(DualSensorConsensus.MAX_AGE_MS) != null
            if (outputWasFresh != fresh) {
                outputWasFresh = fresh
                CombinedSensorRegistry.assessmentChanged()
            }
        }
    }

    internal fun assessment(nowMs: Long): DualSensorConsensus.Result =
        if (isStopped || nowMs > assessmentExpiresAt) DualSensorConsensus.Result() else result

    private fun exportCombinedToHealthConnect() {
        if (Applic.isWearable || android.os.Build.VERSION.SDK_INT < 28 || !Natives.gethealthConnect()) return
        val ptr = Natives.str2sensorptr(SerialNumber)
        if (ptr != 0L) HealthConnectAccess.get()?.writeAll(ptr, SerialNumber)
    }

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        if (isStopped) return null
        val reading = latest ?: return null
        if (nowMillis() - reading.timestampMs !in 0..maxAgeMillis) return null
        val divisor = if (Applic.unit == 1) 18.0182f else 1f
        return ManagedSensorCurrentSnapshot(reading.timestampMs, reading.glucoseMgdl / divisor,
            calibratedGlucoseValue = reading.glucoseMgdl / divisor, rate = reading.rate)
    }
    override fun getManagedUiSnapshot(activeSensorId: String?) = ManagedSensorUiSnapshot(
        SerialNumber, mygetDeviceName(), "", connectionStatus = mygetDeviceName(),
        isActive = SerialNumber == activeSensorId, supportsManualCalibration = false,
        isVendorConnected = latest != null)
    @Synchronized
    internal fun shutdown() {
        isStopped = true
        result = DualSensorConsensus.Result()
        worker.shutdownNow()
        CombinedSensorRegistry.assessmentChanged()
    }
    override fun close() { shutdown(); super.close() }
    override fun softDisconnect() { shutdown() }
    override fun terminateManagedSensor(wipeData: Boolean) { shutdown() }
}
