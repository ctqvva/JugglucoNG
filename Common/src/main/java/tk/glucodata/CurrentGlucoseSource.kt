package tk.glucodata

import tk.glucodata.drivers.ManagedSensorRuntime

object CurrentGlucoseSource {
    private const val DEFAULT_MAX_AGE_MS = 15 * 60 * 1000L
    private const val SECONDS_EPOCH_CUTOFF = 10_000_000_000L

    data class Snapshot(
        val timeMillis: Long,
        val valueText: String,
        val numericValue: Float,
        val rawNumericValue: Float,
        val calibratedNumericValue: Float = Float.NaN,
        val rate: Float,
        val sensorId: String?,
        val sensorGen: Int,
        val index: Int,
        val source: String
    )

    @JvmStatic
    fun normalizeTimeMillis(rawTime: Long): Long {
        if (rawTime <= 0L) {
            return rawTime
        }
        return if (rawTime < SECONDS_EPOCH_CUTOFF) rawTime * 1000L else rawTime
    }

    @JvmStatic
    fun getFresh(maxAgeMillis: Long = DEFAULT_MAX_AGE_MS): Snapshot? {
        return getFresh(maxAgeMillis, null)
    }

    @JvmStatic
    fun getFresh(maxAgeMillis: Long, preferredSensorId: String?): Snapshot? {
        val now = System.currentTimeMillis()
        val targetSensor = preferredSensorId ?: SensorIdentity.resolveMainSensor()

        // A sensor we handed to the other device leaves its driver holding the
        // last value it read. That value stopped being current at the handover,
        // so preferring it made the display alternate between the stale reading
        // and the fresh ones arriving from the device that now holds the sensor.
        val stoodDown = runCatching { SensorOwnershipRuntime.hasStoodDown(targetSensor) }
            .getOrDefault(false)
        val callback = if (stoodDown) null else getFromCallback(now, maxAgeMillis)
        val managed = if (stoodDown) null else getFromManaged(now, maxAgeMillis, targetSensor)
        val native = if (targetSensor == null || SensorIdentity.hasNativeSensorBacking(targetSensor)) {
            getFromNative(now, maxAgeMillis)
        } else {
            null
        }
        if (preferredSensorId.isNullOrBlank()) {
            if (managed != null) {
                return managed
            }
            if (callback != null && (targetSensor == null || SensorIdentity.matches(callback.sensorId, targetSensor))) {
                return enrichWithManagedRaw(callback)
            }
            return enrichWithManagedRaw(native)
        }

        if (managed != null && SensorIdentity.matches(managed.sensorId, preferredSensorId)) {
            return managed
        }
        if (callback != null && SensorIdentity.matches(callback.sensorId, preferredSensorId)) {
            return enrichWithManagedRaw(callback)
        }
        if (native != null && SensorIdentity.matches(native.sensorId, preferredSensorId)) {
            return enrichWithManagedRaw(native)
        }
        return null
    }

    @JvmStatic
    fun getFresh(): Snapshot? = getFresh(DEFAULT_MAX_AGE_MS)

    private fun getFromCallback(now: Long, maxAgeMillis: Long): Snapshot? {
        val latest = SuperGattCallback.previousglucose ?: return null
        val numericValue = SuperGattCallback.previousglucosevalue
        if (!numericValue.isFinite() || numericValue < 0.1f) {
            return null
        }
        val timeMillis = normalizeTimeMillis(latest.time)
        if (kotlin.math.abs(now - timeMillis) > maxAgeMillis) {
            return null
        }
        return Snapshot(
            timeMillis = timeMillis,
            valueText = latest.value ?: "",
            numericValue = numericValue,
            rawNumericValue = Float.NaN,
            calibratedNumericValue = Float.NaN,
            rate = latest.rate,
            sensorId = SuperGattCallback.previousglucosesensorid ?: Natives.lastsensorname(),
            sensorGen = latest.sensorgen2,
            index = 0,
            source = "callback"
        )
    }

    private fun getFromManaged(now: Long, maxAgeMillis: Long, preferredSensorId: String?): Snapshot? {
        val targetSensor = preferredSensorId ?: SensorIdentity.resolveMainSensor()
        val managed = ManagedSensorRuntime.resolveCurrentSnapshot(targetSensor, maxAgeMillis) ?: return null
        val timeMillis = normalizeTimeMillis(managed.timeMillis)
        if (kotlin.math.abs(now - timeMillis) > maxAgeMillis) {
            return null
        }
        return Snapshot(
            timeMillis = timeMillis,
            valueText = "",
            numericValue = managed.glucoseValue,
            rawNumericValue = managed.rawGlucoseValue,
            calibratedNumericValue = managed.calibratedGlucoseValue,
            rate = managed.rate,
            sensorId = targetSensor,
            sensorGen = managed.sensorGen,
            index = 0,
            source = "managed"
        )
    }

    private fun getFromNative(now: Long, maxAgeMillis: Long): Snapshot? {
        val latest = Natives.lastglucose() ?: return null
        val numericValue = GlucoseValueParser.parseFirst(latest.value)
            ?.takeIf { it.isFinite() && it > 0.1f }
            ?: return null
        val timeMillis = normalizeTimeMillis(latest.time)
        if (kotlin.math.abs(now - timeMillis) > maxAgeMillis) {
            return null
        }
        return Snapshot(
            timeMillis = timeMillis,
            valueText = latest.value ?: "",
            numericValue = numericValue,
            rawNumericValue = Float.NaN,
            calibratedNumericValue = Float.NaN,
            rate = latest.rate,
            sensorId = latest.sensorid,
            sensorGen = latest.sensorgen2,
            index = latest.index,
            source = "native"
        )
    }

    private fun enrichWithManagedRaw(snapshot: Snapshot?): Snapshot? {
        snapshot ?: return null
        if (snapshot.rawNumericValue.isFinite() && snapshot.rawNumericValue > 0.1f) {
            return snapshot
        }
        val managed = ManagedSensorRuntime.resolveCurrentSnapshot(snapshot.sensorId, DEFAULT_MAX_AGE_MS) ?: return snapshot
        if (!managed.rawGlucoseValue.isFinite() || managed.rawGlucoseValue <= 0.1f) {
            return snapshot
        }
        return snapshot.copy(rawNumericValue = managed.rawGlucoseValue)
    }

    @JvmStatic
    fun getFreshNotGlucose(maxAgeMillis: Long): notGlucose? {
        val snapshot = getFresh(maxAgeMillis) ?: return null
        return notGlucose(snapshot.timeMillis, snapshot.valueText, snapshot.rate, snapshot.sensorGen)
    }

    @JvmStatic
    fun getFreshNotGlucose(): notGlucose? = getFreshNotGlucose(DEFAULT_MAX_AGE_MS)
}
