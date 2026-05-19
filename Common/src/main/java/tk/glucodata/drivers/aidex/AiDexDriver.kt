// JugglucoNG — AiDex Driver Interface
//
// Shared interface for all AiDex driver implementations (vendor-lib AiDexSensor
// and native-Kotlin AiDexBleManager). SensorViewModel, ComposeHost, and other UI
// code type-check `is AiDexDriver` instead of `is AiDexSensor`, so both drivers
// work identically from the UI's perspective.
//
// Lives in tk.glucodata.drivers.aidex (Java-accessible package).

package tk.glucodata.drivers.aidex

import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCalibrationRecord
import tk.glucodata.drivers.ManagedSensorCalibrationSource
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSignals
import tk.glucodata.drivers.ManagedSensorUiSnapshot

/**
 * Calibration record from the AiDex sensor's on-board storage.
 *
 * Previously an inner class of AiDexSensor; moved here so both driver
 * implementations can return the same type.
 */
data class CalibrationRecord(
    val index: Int,
    val timeOffsetMinutes: Int,
    val referenceGlucoseMgDl: Int,
    val cf: Float,
    val offset: Float,
    val isValid: Boolean,
    /** Absolute timestamp: sensorstartmsec + timeOffsetMinutes * 60_000L */
    val timestampMs: Long,
)

/**
 * Interface that all AiDex BLE driver implementations must satisfy.
 *
 * Both [AiDexSensor] (vendor-lib driver) and
 * [tk.glucodata.drivers.aidex.native.ble.AiDexBleManager] (native Kotlin driver)
 * implement this interface.
 *
 * **UI code should check `gatt is AiDexDriver`** rather than `gatt is AiDexSensor`.
 */
interface AiDexDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

    override fun canConnectWithoutDataptr(): Boolean = true

    override fun managesLiveRoomStorage(): Boolean = true

    override fun shouldShowSearchingStatusWhenIdle(): Boolean = !broadcastOnlyConnection

    override fun shouldDeleteLocalSensorDirectoryOnWipe(): Boolean = true

    override fun supportsDisplayModes(): Boolean = true

    override fun supportsManualCalibration(): Boolean = true

    override fun softReconnect() {
        val callback = this as? SuperGattCallback
        if (callback != null && callback.dataptr != 0L) {
            runCatching { Natives.unfinishSensor(callback.dataptr) }
                .onFailure { android.util.Log.e("AiDexDriver", "unfinishSensor failed: ${it.message}") }
        }
        runCatching { softDisconnect() }
            .onFailure { android.util.Log.e("AiDexDriver", "softDisconnect before reconnect failed: ${it.message}") }
        try {
            Thread.sleep(250L)
        } catch (_: InterruptedException) {
        }
        manualReconnectNow()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        val callback = this as? SuperGattCallback ?: return
        if (wipeData) {
            android.util.Log.i("AiDexDriver", "terminateManagedSensor wipeData requested for ${callback.SerialNumber}")
        }
        forgetVendor()
        if (callback.dataptr != 0L) {
            runCatching { callback.finishSensor() }
                .onFailure { android.util.Log.e("AiDexDriver", "finishSensor failed: ${it.message}") }
        }
        runCatching { callback.close() }
            .onFailure { android.util.Log.e("AiDexDriver", "close failed: ${it.message}") }
    }

    override fun removeManagedPersistence(context: android.content.Context) {
        val callback = this as? SuperGattCallback ?: return
        AiDexManagedSensorIdentityAdapter.removePersistedSensor(context, callback.SerialNumber)
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val callback = this as? SuperGattCallback ?: return null
        val sensorSerial = callback.SerialNumber ?: return null
        val dataptr = callback.dataptr
        val activeSensor = activeSensorId?.takeIf { it.isNotBlank() }
        val startMs = if (dataptr != 0L) {
            runCatching { Natives.getSensorStartmsec(dataptr) }.getOrDefault(0L)
        } else {
            0L
        }
        val officialEndMs = if (dataptr != 0L) {
            runCatching { tk.glucodata.Natives.getSensorEndTime(dataptr, true) }.getOrDefault(0L)
        } else {
            0L
        }
        val calibrations = runCatching {
            getCalibrationRecords().map { record ->
                ManagedSensorCalibrationRecord(
                    index = record.index,
                    referenceGlucoseMgDl = record.referenceGlucoseMgDl,
                    timeOffsetMinutes = record.timeOffsetMinutes,
                    timestampMs = record.timestampMs,
                    cf = record.cf,
                    offset = record.offset,
                    isValid = record.isValid,
                    source = ManagedSensorCalibrationSource.AIDEX,
                )
            }
        }.getOrDefault(emptyList())
        return ManagedSensorUiSnapshot(
            serial = sensorSerial,
            displayName = runCatching { callback.mygetDeviceName() }.getOrDefault(sensorSerial),
            deviceAddress = callback.mActiveDeviceAddress ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.AIDEX,
            connectionStatus = callback.constatstatusstr?.takeIf { it.startsWith("Status=") }.orEmpty(),
            detailedStatus = getDetailedBleStatus(),
            subtitleStatus = getDetailedBleStatus().ifBlank {
                callback.constatstatusstr?.takeIf { it.startsWith("Status=") }.orEmpty()
            },
            startTimeMs = startMs,
            officialEndMs = officialEndMs,
            expectedEndMs = 0L,
            isUiEnabled = !isPaused,
            isActive = activeSensor != null && SensorIdentity.matches(sensorSerial, activeSensor),
            rssi = callback.readrssi,
            dataptr = dataptr,
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            isVendorPaired = runCatching { isVendorPaired() }.getOrDefault(false),
            vendorCalibrations = calibrations,
            isVendorConnected = runCatching { isVendorConnected() }.getOrDefault(false),
            batteryMillivolts = runCatching { getBatteryMillivolts() }.getOrDefault(0),
            isSensorExpired = runCatching { isSensorExpired() }.getOrDefault(false),
            sensorRemainingHours = runCatching { getSensorRemainingHours() }.getOrDefault(-1),
            sensorAgeHours = runCatching { getSensorAgeHours() }.getOrDefault(-1),
            vendorFirmware = runCatching { vendorFirmwareVersion }.getOrDefault(""),
            vendorHardware = runCatching { vendorHardwareVersion }.getOrDefault(""),
            vendorModel = runCatching { vendorModelName }.getOrDefault(""),
            resetCompensationActive = runCatching { resetCompensationEnabled }.getOrDefault(false),
            resetCompensationStatus = runCatching { getCompensationStatusText() }.getOrDefault(""),
        )
    }

    // ── Status ──────────────────────────────────────────────────────────

    /** Whether the driver is paused (not actively receiving data). */
    val isPaused: Boolean

    /** Whether only BLE advertisements are used (no GATT connection). */
    val broadcastOnlyConnection: Boolean

    /** Whether the sensor has saved vendor pairing keys. */
    fun isVendorPaired(): Boolean

    /** Whether the vendor BLE stack is actively connected right now. */
    fun isVendorConnected(): Boolean

    // ── Metadata ────────────────────────────────────────────────────────

    /** Calibration records stored on the sensor. Newest first. */
    fun getCalibrationRecords(): List<CalibrationRecord>

    /** Sensor battery voltage in millivolts (0 = not yet received). */
    fun getBatteryMillivolts(): Int

    /** Whether the sensor has reported itself as expired. */
    fun isSensorExpired(): Boolean

    /** Hours of sensor life remaining (-1 = unknown, 0 = expired). */
    fun getSensorRemainingHours(): Int

    /** Hours since sensor activation (-1 = unknown). */
    fun getSensorAgeHours(): Int

    /** Firmware version string from startup metadata / vendor device-info. */
    val vendorFirmwareVersion: String

    /** Hardware version string from startup metadata / vendor device-info. */
    val vendorHardwareVersion: String

    /** Model name from startup metadata / vendor device-info (e.g. "GX-01S"). */
    val vendorModelName: String

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Full teardown: stop BLE, remove bond, wipe AES keys, remove from prefs.
     * Called from terminate/forget flows.
     */
    fun forgetVendor()

    /**
     * Non-destructive disconnect: stop vendor BLE but preserve pairing keys
     * so the sensor can reconnect later.
     */
    override fun softDisconnect()

    /** Force an immediate reconnection attempt. */
    fun manualReconnectNow()

    /** Enable or disable broadcast-only (advertisement) mode. */
    fun setBroadcastOnlyConnection(enabled: Boolean)

    // ── Sensor Commands ─────────────────────────────────────────────────

    /** Send a hardware reset (0xF0) to the sensor. Returns true on success. */
    override fun resetSensor(): Boolean

    /** Activate a new sensor (SET_NEW_SENSOR 0x20). Returns true on success. */
    override fun startNewSensor(): Boolean

    /** Send a calibration value to the sensor. Returns true on success. */
    override fun calibrateSensor(glucoseMgDl: Int): Boolean

    /** Remove vendor pairing (delete bond + keys). Returns true on success. */
    override fun unpairSensor(): Boolean

    /** Initiate re-pairing from scratch. */
    override fun rePairSensor()

    /** Send an arbitrary maintenance/diagnostic command. Returns true on success. */
    override fun sendMaintenanceCommand(opCode: Int): Boolean

    // ── Bias Compensation ───────────────────────────────────────────────

    /** Whether post-reset initialization bias compensation is active. */
    val resetCompensationEnabled: Boolean

    /** Enable post-reset bias compensation. */
    override fun enableResetCompensation()

    /** Disable post-reset bias compensation. */
    override fun disableResetCompensation()

    /** Human-readable compensation status (e.g. "Phase 1: x1.176 (23h left)"). */
    fun getCompensationStatusText(): String

    // ── Data Mode ───────────────────────────────────────────────────────

    // ── Device List Dirty Flag ──────────────────────────────────────────

    companion object {
        /**
         * Set to true when the device list changes (sensor added/removed/reconnected).
         * SensorViewModel polls this to trigger a refresh.
         *
         * Both driver implementations should set this when their state changes.
         */
        @JvmStatic
        var deviceListDirty: Boolean
            get() = ManagedSensorUiSignals.isDeviceListDirty()
            set(value) {
                if (value) {
                    ManagedSensorUiSignals.markDeviceListDirty()
                } else {
                    ManagedSensorUiSignals.consumeDeviceListDirty()
                }
            }
    }
}
