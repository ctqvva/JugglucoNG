package tk.glucodata.ui.util

import tk.glucodata.Applic
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy

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
    if (tk.glucodata.combined.CombinedSensorRegistry.isCombined(serial)) return ""
    val warmupStatus = getLegacyWarmupStatus(Applic.app, sensorKind, startMsec, nativeStatus)
    if (warmupStatus != null) {
        return warmupStatus
    }
    val managedStatus = managedDashboardStatus(serial)
    if (managedStatus.isNotBlank() || ManagedSensorRuntime.resolveUiSnapshot(serial, serial) != null) {
        return managedStatus
    }
    return nativeStatus
}

fun resolveDashboardSensorStatus(serial: String, nativeStatus: String): String {
    if (tk.glucodata.combined.CombinedSensorRegistry.isCombined(serial)) return ""
    val managedStatus = managedDashboardStatus(serial)
    if (managedStatus.isNotBlank() || ManagedSensorRuntime.resolveUiSnapshot(serial, serial) != null) {
        return managedStatus
    }
    return nativeStatus
}

/** A native history shell is storage geometry; a combined source has no physical wear lifetime. */
internal fun resolveDashboardSensorLifecycle(
    serial: String,
    managed: tk.glucodata.drivers.ManagedSensorUiSnapshot?,
    nativeSnapshot: LongArray?,
    fallbackDurationDays: Int,
    nowMs: Long
): tk.glucodata.drivers.ManagedSensorLifecycleSummary {
    if (tk.glucodata.combined.CombinedSensorRegistry.isCombined(serial)) {
        return tk.glucodata.drivers.ManagedSensorLifecycleSummary(0f, "", 0, 999L)
    }
    val native = nativeSnapshot?.takeIf { it.size >= 5 }
    return ManagedSensorStatusPolicy.resolveLifecycleSummary(
        startTimeMs = managed?.startTimeMs?.takeIf { it > 0L } ?: native?.get(2) ?: 0L,
        officialEndMs = managed?.officialEndMs?.takeIf { it > 0L } ?: if (managed == null) native?.get(4) ?: 0L else 0L,
        expectedEndMs = managed?.expectedEndMs?.takeIf { it > 0L } ?: if (managed == null) native?.get(3) ?: 0L else 0L,
        sensorRemainingHours = managed?.sensorRemainingHours ?: -1,
        sensorAgeHours = managed?.sensorAgeHours ?: -1,
        fallbackDurationDays = fallbackDurationDays,
        allowExpectedEndOverrun = managed?.uiFamily == tk.glucodata.drivers.ManagedSensorUiFamily.ANYTIME &&
            managed.expectedEndMs > 0L && !managed.isSensorExpired,
        nowMs = nowMs
    )
}
