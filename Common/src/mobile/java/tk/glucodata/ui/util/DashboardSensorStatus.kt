package tk.glucodata.ui.util

import tk.glucodata.Applic
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
    val warmupStatus = getLegacyWarmupStatus(Applic.app, sensorKind, startMsec, nativeStatus)
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
