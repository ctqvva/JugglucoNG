package tk.glucodata.ui.util

import android.content.Context
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.ui.libre3WarmupMinutes

private const val DEXCOM_SENSOR_KIND = 0x40
private const val DEXCOM_WARMUP_DURATION_MS = 30L * 60L * 1000L

fun getLegacyWarmupStatus(context: Context, sensorKind: Int, startMs: Long, nativeStatus: String, warmupMinutes: Int = 60): String? {
    libre3WarmupMinutes(sensorKind, startMs, System.currentTimeMillis(), warmupMinutes, nativeStatus)?.let {
        return context.getString(R.string.status_ready_in_minutes, it)
    }
    if (sensorKind != DEXCOM_SENSOR_KIND) {
        return null
    }
    if (startMs <= 0L) {
        return null
    }
    val elapsedMs = System.currentTimeMillis() - startMs
    if (elapsedMs < 0L || elapsedMs >= DEXCOM_WARMUP_DURATION_MS) {
        return null
    }
    if (nativeStatus.isBlank()) {
        return context.getString(R.string.status_waiting_for_connection)
    }
    val remainingMs = (DEXCOM_WARMUP_DURATION_MS - elapsedMs).coerceAtLeast(60_000L)
    val remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()
    return context.getString(R.string.status_ready_in_minutes, remainingMinutes)
}

fun getLegacyWarmupStatus(context: Context, dataptr: Long, nativeStatus: String): String? {
    if (dataptr == 0L) {
        return null
    }
    val sensorKind = runCatching { Natives.getLibreVersion(dataptr) }.getOrDefault(-1)
    val startMs = runCatching { Natives.getSensorStartmsec(dataptr) }.getOrDefault(0L)
    val warmupMinutes = runCatching { Natives.getSensorWarmupMinutes(dataptr) }.getOrDefault(0)
    return getLegacyWarmupStatus(context, sensorKind, startMs, nativeStatus, warmupMinutes)
}
