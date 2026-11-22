package tk.glucodata.ui.util

import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.ui.LegacySensorStatusText
import tk.glucodata.ui.classifyLegacySensorStatus

private fun localizeNativeStatus(status: String): String {
    val app = Applic.app ?: return status
    return when (classifyLegacySensorStatus(status)) {
        LegacySensorStatusText.SensorError -> app.getString(R.string.sensor_error)
        LegacySensorStatusText.ReplaceSensor -> app.getString(R.string.sensor_replace)
        LegacySensorStatusText.SensorEnded -> app.getString(R.string.sensor_ended)
        LegacySensorStatusText.WarmingUp -> app.getString(R.string.sensor_warming_up)
        LegacySensorStatusText.Other -> status
    }
}

private fun managedDashboardStatus(serial: String): String {
    val snapshot = ManagedSensorRuntime.resolveUiSnapshot(serial, serial) ?: return ""
    return ManagedSensorStatusPolicy.collapseSummaryStatus(snapshot.detailedStatus)
        .ifBlank { ManagedSensorStatusPolicy.collapseSummaryStatus(snapshot.subtitleStatus) }
}

fun resolveDashboardSensorStatus(
    serial: String,
    sensorKind: Int,
    startMsec: Long,
    nativeStatus: String
): String {
    // The native record carries the sensor's own warm-up (62 min for a 15-day
    // G7). Look it up by name: str2sensorptr would also clear sensor-error
    // flags, which this status refresh must not do.
    val warmupMinutes = runCatching {
        Natives.getSensorWarmupMinutesByName(serial)
    }.getOrDefault(0)
    val warmupStatus = getLegacyWarmupStatus(
        Applic.app,
        sensorKind,
        startMsec,
        nativeStatus,
        dexcomWarmupMinutes = warmupMinutes,
    )
    if (warmupStatus != null) {
        return warmupStatus
    }
    val managedStatus = managedDashboardStatus(serial)
    if (managedStatus.isNotBlank() || ManagedSensorRuntime.resolveUiSnapshot(serial, serial) != null) {
        return managedStatus
    }
    return localizeNativeStatus(nativeStatus)
}

fun resolveDashboardSensorStatus(serial: String, nativeStatus: String): String {
    val managedStatus = managedDashboardStatus(serial)
    if (managedStatus.isNotBlank() || ManagedSensorRuntime.resolveUiSnapshot(serial, serial) != null) {
        return managedStatus
    }
    return localizeNativeStatus(nativeStatus)
}
