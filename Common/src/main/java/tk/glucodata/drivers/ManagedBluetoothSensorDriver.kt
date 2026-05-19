package tk.glucodata.drivers

import android.content.Context

data class ManagedSensorCurrentSnapshot(
    val timeMillis: Long,
    val glucoseValue: Float,
    val rawGlucoseValue: Float = Float.NaN,
    val rate: Float = Float.NaN,
    val sensorGen: Int = 0,
)

data class ManagedSensorCalibrationRecord(
    val index: Int,
    val referenceGlucoseMgDl: Int,
    val timeOffsetMinutes: Int,
    val timestampMs: Long,
    val cf: Float,
    val offset: Float,
    val isValid: Boolean,
    val source: ManagedSensorCalibrationSource = ManagedSensorCalibrationSource.GENERIC,
    val appliedGlucoseId: Int = 0,
    val appliedAtMs: Long = 0L,
    val outputGlucoseMgDl: Int = 0,
)

enum class ManagedSensorCalibrationSource {
    GENERIC,
    AIDEX,
    ANYTIME,
}

enum class ManagedSensorUiFamily {
    GENERIC,
    MQ,
    AIDEX,
    ICAN,
    ANYTIME,
}

data class ManagedSensorUiSnapshot(
    val serial: String,
    val displayName: String,
    val deviceAddress: String,
    val uiFamily: ManagedSensorUiFamily = ManagedSensorUiFamily.GENERIC,
    val connectionStatus: String = "",
    val detailedStatus: String = "",
    val subtitleStatus: String = "",
    val showConnectionStatusInDetails: Boolean = true,
    val startTimeMs: Long = 0L,
    val officialEndMs: Long = 0L,
    val expectedEndMs: Long = 0L,
    val isUiEnabled: Boolean = true,
    val isActive: Boolean = false,
    val rssi: Int = 0,
    val dataptr: Long = 0L,
    val viewMode: Int = 0,
    val supportsDisplayModes: Boolean = false,
    val supportsManualCalibration: Boolean = false,
    val supportsHardwareReset: Boolean = false,
    val isVendorPaired: Boolean = false,
    val vendorCalibrations: List<ManagedSensorCalibrationRecord> = emptyList(),
    val isVendorConnected: Boolean = false,
    val batteryMillivolts: Int = 0,
    val batteryPercent: Int = -1,
    val isSensorExpired: Boolean = false,
    val sensorRemainingHours: Int = -1,
    val sensorAgeHours: Int = -1,
    val vendorFirmware: String = "",
    val vendorHardware: String = "",
    val vendorModel: String = "",
    val resetCompensationActive: Boolean = false,
    val resetCompensationStatus: String = "",
)

/**
 * Minimal shared contract for BLE sensors whose identity and/or live data path
 * is owned by a Kotlin driver rather than the legacy native stack alone.
 *
 * This is intentionally small. It exists to keep shared app code generic when it
 * needs to answer a few cross-driver questions:
 * - does this callback own managed sensor identity matching?
 * - does this sensor currently have native backing?
 * - should generic native history sync run for it?
 * - can the driver provide a current reading before native state catches up?
 */
interface ManagedBluetoothSensorDriver {

    fun canConnectWithoutDataptr(): Boolean = false

    fun getDetailedBleStatus(): String = ""

    fun matchesManagedSensorId(sensorId: String?): Boolean = false

    fun hasNativeSensorBacking(): Boolean = true

    fun shouldUseNativeHistorySync(): Boolean = hasNativeSensorBacking()

    fun managesLiveRoomStorage(): Boolean = false

    fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? = null

    fun getManagedUiSnapshot(activeSensorId: String? = null): ManagedSensorUiSnapshot? = null

    fun isManagedOutsideNativeActiveSet(): Boolean = true

    fun shouldShowSearchingStatusWhenIdle(): Boolean = true

    fun softDisconnect() {}

    fun softReconnect() {}

    fun terminateManagedSensor(wipeData: Boolean = false) {}

    fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = true

    fun removeManagedPersistence(context: Context) {}

    var viewMode: Int

    fun supportsDisplayModes(): Boolean = false

    fun supportsManualCalibration(): Boolean = false

}
