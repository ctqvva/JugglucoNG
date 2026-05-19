// AnytimeDriver.kt — Managed-driver contract for Anytime / Yuwell CT3 sensors.
//
// Shape mirrors `MQDriver` so the rest of the managed-sensor infrastructure
// (UI, history sync, identity lookup) treats Anytime uniformly.

package tk.glucodata.drivers.anytime

import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCalibrationRecord
import tk.glucodata.drivers.ManagedSensorCalibrationSource
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot

/** A single live reading the driver can hand back to the UI without waiting for native. */
data class AnytimeCurrentSnapshot(
    val timeMillis: Long,
    val glucoseValue: Float,         // mg/dL
    val rawValue: Float = Float.NaN, // raw Iw current (nA), for diagnostic display
    val rate: Float = Float.NaN,
    val sensorGen: Int = 0,
)

data class AnytimeReferenceCalibrationRecord(
    val targetGlucoseId: Int,
    val referenceMgdlTimes10: Int,
    val acceptedAtMs: Long = 0L,
    val appliedGlucoseId: Int = 0,
    val appliedAtMs: Long = 0L,
    val outputMgdlTimes10: Int = 0,
)

interface AnytimeDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun isUiEnabled(): Boolean = true
    fun getPassiveConnectionStatus(): String = ""

    /** Set / push K/R from a freshly-scanned QR code. */
    fun setQrCalibration(rawQr: String): Boolean

    /** Push a fingerstick reference BG (mg/dL) to the transmitter. */
    fun pushReferenceBg(mgdl: Int): Boolean

    /**
     * Trigger an explicit transmitter reset. The sensor responds with the
     * current bind state. Used for recovery; not normally needed.
     */
    fun requestTransmitterReset(): Boolean

    /** Schedule an unbind-then-disconnect. */
    fun requestUnbind(): Boolean

    /**
     * Walk the transmitter's record buffer from `lastGlucoseId+1` upward until
     * it returns no more data, populating Juggluco's history with everything
     * we missed. Idempotent — extra calls are no-ops while a backfill is
     * already running.
     */
    fun requestHistoryBackfill(): Boolean

    fun getCurrentSnapshot(maxAgeMillis: Long): AnytimeCurrentSnapshot? = null

    fun getReferenceCalibrationRecords(): List<AnytimeReferenceCalibrationRecord> = emptyList()

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val snap = getCurrentSnapshot(maxAgeMillis) ?: return null
        return ManagedSensorCurrentSnapshot(
            timeMillis = snap.timeMillis,
            glucoseValue = snap.glucoseValue,
            rawGlucoseValue = snap.rawValue,
            rate = snap.rate,
            sensorGen = snap.sensorGen,
        )
    }

    override fun softDisconnect() {}
    override fun softReconnect() {}
    override fun terminateManagedSensor(wipeData: Boolean) {}

    fun supportsRawDisplayModes(): Boolean = true
    fun supportsSensorCalibration(): Boolean = true
    override fun supportsResetAction(): Boolean = true

    override fun supportsDisplayModes(): Boolean = supportsRawDisplayModes()
    override fun supportsManualCalibration(): Boolean = supportsSensorCalibration()

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val callback = this as? SuperGattCallback ?: return null
        val sensorSerial = callback.SerialNumber ?: return null
        val active = activeSensorId?.takeIf { it.isNotBlank() }
        val detailedStatus = runCatching { getDetailedBleStatus() }.getOrDefault("")
        val passiveStatus = if (detailedStatus.isBlank()) {
            runCatching { getPassiveConnectionStatus() }.getOrDefault("")
        } else ""
        return ManagedSensorUiSnapshot(
            serial = sensorSerial,
            displayName = runCatching { callback.mygetDeviceName() }.getOrDefault(sensorSerial),
            deviceAddress = callback.mActiveDeviceAddress ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.ANYTIME,
            connectionStatus = passiveStatus,
            detailedStatus = detailedStatus,
            subtitleStatus = detailedStatus.ifBlank { passiveStatus },
            showConnectionStatusInDetails = true,
            startTimeMs = runCatching { getStartTimeMs() }.getOrDefault(0L),
            officialEndMs = runCatching { getOfficialEndMs() }.getOrDefault(0L),
            expectedEndMs = runCatching { getExpectedEndMs() }.getOrDefault(0L),
            isUiEnabled = runCatching { isUiEnabled() }.getOrDefault(true),
            isActive = active != null && SensorIdentity.matches(sensorSerial, active),
            rssi = callback.readrssi,
            dataptr = callback.dataptr,
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            supportsHardwareReset = supportsResetAction(),
            vendorCalibrations = runCatching {
                getReferenceCalibrationRecords().map { record ->
                    ManagedSensorCalibrationRecord(
                        index = record.targetGlucoseId,
                        referenceGlucoseMgDl = (record.referenceMgdlTimes10 + 5) / 10,
                        timeOffsetMinutes = 0,
                        timestampMs = record.acceptedAtMs,
                        cf = Float.NaN,
                        offset = Float.NaN,
                        isValid = record.referenceMgdlTimes10 > 0,
                        source = ManagedSensorCalibrationSource.ANYTIME,
                        appliedGlucoseId = record.appliedGlucoseId,
                        appliedAtMs = record.appliedAtMs,
                        outputGlucoseMgDl = if (record.outputMgdlTimes10 > 0) {
                            (record.outputMgdlTimes10 + 5) / 10
                        } else {
                            0
                        },
                    )
                }
            }.getOrDefault(emptyList()),
            isVendorConnected = callback.mActiveBluetoothDevice != null,
            isSensorExpired = runCatching { isSensorExpired() }.getOrDefault(false),
            sensorRemainingHours = runCatching { getSensorRemainingHours() }.getOrDefault(-1),
            sensorAgeHours = runCatching { getSensorAgeHours() }.getOrDefault(-1),
            vendorFirmware = runCatching { vendorFirmwareVersion }.getOrDefault(""),
            vendorModel = runCatching { vendorModelName }.getOrDefault(""),
            batteryMillivolts = runCatching { batteryMillivolts }.getOrDefault(0),
            batteryPercent = runCatching { batteryPercent }.getOrDefault(-1),
        )
    }

    fun getStartTimeMs(): Long
    fun getOfficialEndMs(): Long
    fun getExpectedEndMs(): Long
    fun isSensorExpired(): Boolean
    fun getSensorRemainingHours(): Int
    fun getSensorAgeHours(): Int
    fun getReadingIntervalMinutes(): Int
    override fun calibrateSensor(glucoseMgDl: Int): Boolean = pushReferenceBg(glucoseMgDl)

    val vendorFirmwareVersion: String
    val vendorModelName: String
    val batteryMillivolts: Int
    val batteryPercent: Int get() = -1
}
