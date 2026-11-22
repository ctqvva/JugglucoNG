package tk.glucodata.ui

/** Native `getsensortext` literals that the card needs to localize. */
enum class LegacySensorStatusText {
    SensorError,
    ReplaceSensor,
    SensorEnded,
    WarmingUp,
    Other,
}

/** Sensor kind the native layer reports for a Dexcom G7 / ONE+. */
const val DEXCOM_SENSOR_KIND = 0x40

/** Warm-up assumed when the record carries none; every 10-day sensor has 30 minutes. */
const val DEXCOM_DEFAULT_WARMUP_MINUTES = 30

fun classifyLegacySensorStatus(status: String): LegacySensorStatusText = when (status) {
    "Sensor Error" -> LegacySensorStatusText.SensorError
    "Replace Sensor?" -> LegacySensorStatusText.ReplaceSensor
    "Sensor Ended" -> LegacySensorStatusText.SensorEnded
    "Warming Up" -> LegacySensorStatusText.WarmingUp
    else -> LegacySensorStatusText.Other
}

/** A connecting or warming sensor is enabled unless its callback was paused. */
fun isSensorLocallyEnabled(streaming: Boolean, paused: Boolean?): Boolean =
    paused?.not() ?: streaming

fun libre3WarmupMinutes(
    sensorKind: Int,
    startMs: Long,
    nowMs: Long,
    warmupMinutes: Int,
    nativeStatus: String,
): Int? {
    if (sensorKind != 3 || startMs <= 0L || warmupMinutes <= 0) return null
    // Do not cover a sensor-reported error, ended state, or history transfer.
    if (nativeStatus.isNotBlank() && nativeStatus != "Warming Up") return null
    val elapsed = nowMs - startMs
    val duration = warmupMinutes * 60_000L
    if (elapsed < 0 || elapsed >= duration) return null
    return ((duration - elapsed + 59_999L) / 60_000L).toInt()
}

/** Minutes left in a Dexcom G7 / ONE+ warm-up, or null outside it. */
fun dexcomWarmupRemainingMinutes(
    sensorKind: Int,
    startMs: Long,
    nowMs: Long,
    warmupMinutes: Int,
    nativeStatus: String,
): Int? {
    if (sensorKind != DEXCOM_SENSOR_KIND || startMs <= 0L) return null
    if (nativeStatus.isNotBlank() && nativeStatus != "Warming Up") return null
    // The sensor's own value (62 min for a 15-day G7, 30 for a 10-day one);
    // 30 is the value every older record carries.
    val minutes = if (warmupMinutes > 0) warmupMinutes else DEXCOM_DEFAULT_WARMUP_MINUTES
    val duration = minutes * 60_000L
    val elapsed = nowMs - startMs
    if (elapsed < 0 || elapsed >= duration) return null
    return ((duration - elapsed + 59_999L) / 60_000L).toInt()
}
