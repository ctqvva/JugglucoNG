package tk.glucodata.ui

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
