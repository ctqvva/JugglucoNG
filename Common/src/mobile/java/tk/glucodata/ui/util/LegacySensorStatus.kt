package tk.glucodata.ui.util

import android.content.Context
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.ui.DEXCOM_DEFAULT_WARMUP_MINUTES
import tk.glucodata.ui.dexcomWarmupRemainingMinutes
import tk.glucodata.ui.libre3WarmupMinutes

fun getLegacyWarmupStatus(
    context: Context,
    sensorKind: Int,
    startMs: Long,
    nativeStatus: String,
    warmupMinutes: Int = 60,
    dexcomWarmupMinutes: Int = DEXCOM_DEFAULT_WARMUP_MINUTES,
): String? {
    libre3WarmupMinutes(sensorKind, startMs, System.currentTimeMillis(), warmupMinutes, nativeStatus)?.let {
        return context.getString(R.string.status_ready_in_minutes, it)
    }
    val remainingMinutes = dexcomWarmupRemainingMinutes(
        sensorKind,
        startMs,
        System.currentTimeMillis(),
        dexcomWarmupMinutes,
    ) ?: return null
    if (nativeStatus.isBlank()) {
        return context.getString(R.string.status_waiting_for_connection)
    }
    return context.getString(R.string.status_ready_in_minutes, remainingMinutes)
}

fun getLegacyWarmupStatus(context: Context, dataptr: Long, nativeStatus: String): String? {
    if (dataptr == 0L) {
        return null
    }
    val sensorKind = runCatching { Natives.getLibreVersion(dataptr) }.getOrDefault(-1)
    val startMs = runCatching { Natives.getSensorStartmsec(dataptr) }.getOrDefault(0L)
    val warmupMinutes = runCatching { Natives.getSensorWarmupMinutes(dataptr) }.getOrDefault(0)
    return getLegacyWarmupStatus(context, sensorKind, startMs, nativeStatus, warmupMinutes, warmupMinutes)
}
