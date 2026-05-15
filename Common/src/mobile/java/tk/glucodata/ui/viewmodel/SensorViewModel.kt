package tk.glucodata.ui.viewmodel

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.Natives
import tk.glucodata.UiRefreshBus
import tk.glucodata.bluediag
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorIdentityRegistry
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSignals
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.ManagedSensorViewModeStore
import tk.glucodata.drivers.mq.MQBootstrapClient
import tk.glucodata.drivers.mq.MQDriver
import tk.glucodata.drivers.mq.MQRegistry
import tk.glucodata.ui.util.getLegacyWarmupStatus
import kotlin.math.abs

/**
 * Color palette for sensors - M3 Expressive style.
 * Colors are assigned deterministically based on sensor serial hash.
 */
object SensorColors {
    // Muted, expressive palette that works in both light and dark modes
    private val palette = listOf(
        Color(0xFF6750A4), // Primary purple
        Color(0xFF00796B), // Teal
        Color(0xFF5C6BC0), // Indigo
        Color(0xFFD81B60), // Pink
        Color(0xFF1E88E5), // Blue
        Color(0xFF43A047), // Green
        Color(0xFFF4511E), // Deep orange
        Color(0xFF8E24AA), // Purple
    )
    
    fun getColor(serial: String): Color {
        val index = abs(serial.hashCode()) % palette.size
        return palette[index]
    }
    
    fun getColorIndex(serial: String): Int {
        return abs(serial.hashCode()) % palette.size
    }
}

data class SensorInfo(
    val serial: String,
    val displayName: String,
    val deviceAddress: String,
    val connectionStatus: String,
    val starttime: String,
    val streaming: Boolean,
    val rssi: Int,
    val dataptr: Long,
    val officialEnd: String,
    val expectedEnd: String,
    val viewMode: Int,
    val autoResetDays: Int,
    val isSibionics: Boolean,
    val isSibionics2: Boolean,
    val isAidex: Boolean,
    val isMq: Boolean = false,
    val startMs: Long,
    val officialEndMs: Long,
    val expectedEndMs: Long,
    val customCalEnabled: Boolean,
    val customCalIndex: Int,
    val customCalAutoReset: Boolean,
    val supportsDisplayModes: Boolean = false,
    val supportsManualCalibration: Boolean = false,
    val supportsHardwareReset: Boolean = false,
    val detailedStatus: String = "",
    val isActive: Boolean = false,  // True if this is the primary data source
    val isVendorPaired: Boolean = false,  // AiDex: has saved vendor pairing keys
    val vendorCalibrations: List<VendorCalibrationInfo> = emptyList(),  // AiDex: calibration records from sensor
    val isVendorConnected: Boolean = false,  // AiDex: vendor BLE stack actively connected
    val batteryMillivolts: Int = 0,  // AiDex: sensor battery voltage in mV (0 = not yet received)
    val batteryPercent: Int = -1,  // MQ: vendor reports battery as percent, not voltage
    val isSensorExpired: Boolean = false,  // AiDex: sensor has reported itself as expired
    // Edit 58a/58b/58c: Parsed sensor metadata from vendor protocol
    val sensorRemainingHours: Int = -1,  // AiDex: hours remaining (-1 = unknown)
    val sensorAgeHours: Int = -1,  // AiDex: sensor age in hours (-1 = unknown)
    val vendorFirmware: String = "",  // AiDex: firmware version from GET_DEVICE_INFO
    val vendorHardware: String = "",  // AiDex: hardware version from GET_DEVICE_INFO
    val vendorModel: String = "",  // AiDex: model name from GET_DEVICE_INFO (e.g. "GX-01S")
    val isIcan: Boolean = false,
    val isAnytime: Boolean = false,  // Anytime/Yuwell: vendor reports battery as percent + voltage
    // Edit 59: Reset compensation state
    val resetCompensationActive: Boolean = false,  // AiDex: whether initialization bias compensation is active
    val resetCompensationStatus: String = ""  // AiDex: human-readable compensation status (e.g. "Phase 1: ×1.176 (23h left)")
) {
    /** Get the assigned color for this sensor */
    val color: Color get() = SensorColors.getColor(serial)
}

/** UI-friendly calibration record from the AiDex sensor */
data class VendorCalibrationInfo(
    val index: Int,
    val referenceGlucoseMgDl: Int,
    val timeOffsetMinutes: Int,
    val timestampMs: Long,
    val cf: Float,
    val offset: Float,
    val isValid: Boolean
)


class SensorViewModel : ViewModel() {
    private companion object {
        private val sibionicsFreshRestartSeq = java.util.concurrent.atomic.AtomicLong(1L)
        private val sibionicsCustomToggleSeq = java.util.concurrent.atomic.AtomicLong(1L)
        private val sibionicsDisableSeq = java.util.concurrent.atomic.AtomicLong(1L)
    }

    private val _sensors = MutableStateFlow<List<SensorInfo>>(emptyList())
    val sensors = _sensors.asStateFlow()

    // Polling job - only active when screen is visible
    private var pollingJob: Job? = null
    private var lastDeviceSyncElapsedMs: Long = 0L

    private fun findGatt(serial: String): SuperGattCallback? {
        val gatts = SensorBluetooth.mygatts() ?: return null
        return gatts.firstOrNull { it.SerialNumber == serial }
            ?: gatts.firstOrNull { SensorIdentity.matches(it.SerialNumber, serial) }
            ?: gatts.firstOrNull {
                (it as? ManagedBluetoothSensorDriver)?.matchesManagedSensorId(serial) == true
            }
    }

    private fun normalizePublishedSensor(sensor: SensorInfo): SensorInfo {
        val resolved = SensorIdentity.resolveAppSensorId(sensor.serial) ?: sensor.serial
        return if (resolved == sensor.serial) sensor else sensor.copy(serial = resolved)
    }

    private fun sensorPriority(sensor: SensorInfo): Int {
        var score = 0
        if (sensor.isActive) score += 10_000
        if (sensor.streaming) score += 5_000
        if (sensor.dataptr != 0L) score += 2_000
        if (sensor.deviceAddress != "Unknown") score += 500
        if (sensor.vendorModel.isNotBlank()) score += 200
        if (sensor.vendorFirmware.isNotBlank()) score += 200
        if (sensor.startMs > 0L) score += 100
        if (sensor.detailedStatus.isNotBlank()) score += 50
        if (sensor.connectionStatus.isNotBlank()) score += 20
        return score
    }

    private fun mergeDuplicateSensorInfo(existing: SensorInfo, candidate: SensorInfo): SensorInfo {
        val existingScore = sensorPriority(existing)
        val candidateScore = sensorPriority(candidate)
        return when {
            candidateScore > existingScore -> candidate
            candidateScore < existingScore -> existing
            candidate.detailedStatus.length > existing.detailedStatus.length -> candidate
            candidate.connectionStatus.length > existing.connectionStatus.length -> candidate
            candidate.displayName.length > existing.displayName.length -> candidate
            else -> existing
        }
    }

    private fun dedupePublishedSensors(sensors: List<SensorInfo>): List<SensorInfo> {
        if (sensors.size < 2) {
            return sensors.map(::normalizePublishedSensor)
        }
        val deduped = LinkedHashMap<String, SensorInfo>()
        sensors.forEach { original ->
            val sensor = normalizePublishedSensor(original)
            val existing = deduped[sensor.serial]
            if (existing == null) {
                deduped[sensor.serial] = sensor
            } else {
                android.util.Log.w(
                    "SensorViewModel",
                    "Deduping duplicate published sensor ${sensor.serial} (${existing.displayName} / ${sensor.displayName})"
                )
                deduped[sensor.serial] = mergeDuplicateSensorInfo(existing, sensor)
            }
        }
        return deduped.values.toList()
    }

    init {
        // Initial refresh with device sync — only time we need to call updateDevices() automatically
        refreshSensorsWithDeviceSync()
    }

    /**
     * Start real-time polling when Sensors screen becomes visible.
     * Call this from LaunchedEffect in SensorScreen.
     */
    fun startPolling() {
        // Cancel any existing job first
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            // Re-entering the Sensors screen should resync the callback/device list once.
            // ViewModels survive tab switches, and follower/mirror sensors can appear while
            // the existing VM is alive. Without this, the screen can keep a stale callback
            // snapshot until full app restart.
            val now = SystemClock.elapsedRealtime()
            if (now - lastDeviceSyncElapsedMs > 1500L) {
                refreshSensorsWithDeviceSync()
            } else {
                refreshSensors()
            }
            while (true) {
                if (ManagedSensorUiSignals.consumeDeviceListDirty()) {
                    refreshSensorsWithDeviceSync()
                } else {
                    refreshSensors()
                }
                kotlinx.coroutines.delay(2000) // 2 second refresh for real-time feel
            }
        }
    }

    /**
     * Stop polling when leaving Sensors screen.
     * Call this from DisposableEffect onDispose in SensorScreen.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Set the main active sensor (for notifications/xDrip).
     * Multi-sensor: No longer clears the Room DB. The new sensor's data is already
     * in Room (synced via HistorySync). The dashboard/chart will automatically
     * filter by the new main sensor serial. We just trigger a sync to ensure
     * the latest data is available.
     */
    fun setMain(serial: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                SensorBluetooth.setCurrentSensorSelection(serial)
                // Ensure this sensor's data is synced into Room (non-destructive)
                tk.glucodata.data.HistorySync.mergeFullSyncForSensor(serial)
                refreshSensorsWithDeviceSync()
            } catch (e: Exception) {
                android.util.Log.e("SensorVM", "Failed to set main sensor: ${e.message}")
            }
        }
    }

    fun refreshSensors() {
        refreshSensorsInternal(syncDeviceList = false)
    }

    /**
     * Edit 60c: Full refresh that also calls SensorBluetooth.updateDevices() to sync the
     * device list with native state. This is expensive (touches SharedPrefs, native sensors,
     * generates ~16 log lines per call) so it should only be called on actual state changes
     * (init, set main, remove sensor, etc.), NOT on the 2-second polling loop.
     */
    private fun refreshSensorsWithDeviceSync() {
        lastDeviceSyncElapsedMs = SystemClock.elapsedRealtime()
        refreshSensorsInternal(syncDeviceList = true)
    }

    private fun mapManagedSensorInfo(snapshot: ManagedSensorUiSnapshot): SensorInfo {
        val vendorCalibrations = snapshot.vendorCalibrations.map { record ->
            VendorCalibrationInfo(
                index = record.index,
                referenceGlucoseMgDl = record.referenceGlucoseMgDl,
                timeOffsetMinutes = record.timeOffsetMinutes,
                timestampMs = record.timestampMs,
                cf = record.cf,
                offset = record.offset,
                isValid = record.isValid
            )
        }
        val isAiDex = snapshot.uiFamily == ManagedSensorUiFamily.AIDEX
        val isMq = snapshot.uiFamily == ManagedSensorUiFamily.MQ
        val isIcan = snapshot.uiFamily == ManagedSensorUiFamily.ICAN
        val isAnytime = snapshot.uiFamily == ManagedSensorUiFamily.ANYTIME
        val detailsConnectionStatus = if (snapshot.showConnectionStatusInDetails) {
            snapshot.connectionStatus
        } else {
            ""
        }
        return SensorInfo(
            serial = snapshot.serial,
            displayName = snapshot.displayName,
            deviceAddress = snapshot.deviceAddress,
            connectionStatus = detailsConnectionStatus,
            starttime = if (snapshot.startTimeMs > 0) bluediag.datestr(snapshot.startTimeMs) else "",
            streaming = snapshot.isUiEnabled,
            rssi = snapshot.rssi,
            dataptr = snapshot.dataptr,
            officialEnd = if (snapshot.officialEndMs > 0) bluediag.datestr(snapshot.officialEndMs) else "",
            expectedEnd = if (snapshot.expectedEndMs > 0) bluediag.datestr(snapshot.expectedEndMs) else "",
            viewMode = snapshot.viewMode,
            autoResetDays = 0,
            isSibionics = false,
            isSibionics2 = false,
            isAidex = isAiDex,
            isMq = isMq,
            isIcan = isIcan,
            isAnytime = isAnytime,
            startMs = snapshot.startTimeMs,
            officialEndMs = snapshot.officialEndMs,
            expectedEndMs = snapshot.expectedEndMs,
            customCalEnabled = false,
            customCalIndex = 0,
            customCalAutoReset = false,
            supportsDisplayModes = snapshot.supportsDisplayModes,
            supportsManualCalibration = snapshot.supportsManualCalibration,
            supportsHardwareReset = snapshot.supportsHardwareReset,
            detailedStatus = snapshot.subtitleStatus.ifBlank {
                snapshot.detailedStatus.ifBlank { snapshot.connectionStatus }
            },
            isActive = snapshot.isActive,
            isVendorPaired = snapshot.isVendorPaired,
            vendorCalibrations = vendorCalibrations,
            isVendorConnected = snapshot.isVendorConnected,
            batteryMillivolts = snapshot.batteryMillivolts,
            batteryPercent = snapshot.batteryPercent,
            isSensorExpired = snapshot.isSensorExpired,
            sensorRemainingHours = snapshot.sensorRemainingHours,
            sensorAgeHours = snapshot.sensorAgeHours,
            vendorFirmware = snapshot.vendorFirmware,
            vendorHardware = snapshot.vendorHardware,
            vendorModel = snapshot.vendorModel,
            resetCompensationActive = snapshot.resetCompensationActive,
            resetCompensationStatus = snapshot.resetCompensationStatus
        )
    }

    private fun refreshSensorsInternal(syncDeviceList: Boolean) {
        viewModelScope.launch {
            if (syncDeviceList) {
                SensorBluetooth.updateDevices()
            }
            val gatts = SensorBluetooth.mygatts() ?: ArrayList()
            
            // Preserve the explicit current selection even if that sensor is not active.
            // A non-active sensor can still be a valid dashboard/history target.
            val activeSensors = try { Natives.activeSensors() } catch (_: Exception) { null }
            val activeSensorSerial = try {
                SensorIdentity.resolveAvailableMainSensor(
                    selectedMain = SensorIdentity.resolveMainSensor(),
                    preferredSensorId = null,
                    activeSensors = activeSensors
                )
            } catch (e: Exception) {
                null
            }

            // Edit 56c: Build a set of active sensor serials for filtering.
            // Legacy sensors not in activeSensors() are finished — exclude them from the UI.
            // AiDex sensors (X- prefix) are managed via SharedPreferences, not activeSensors().
            val activeSet = activeSensors?.toHashSet() ?: HashSet()

            val sensorList = gatts.mapNotNull { gatt ->
                try {
                    // Edit 56c: Skip finished legacy sensors (not in activeSensors list).
                    // AiDex sensors bypass this check since they're tracked in SharedPreferences.
                    val serial = gatt.SerialNumber ?: ""
                    val managedOutsideNative =
                        gatt is ManagedBluetoothSensorDriver && gatt.isManagedOutsideNativeActiveSet()
                    if (!managedOutsideNative && serial.isNotEmpty() && !activeSet.contains(serial)) {
                        android.util.Log.d("SensorVM", "Edit 56c: Filtering out finished sensor $serial from UI")
                        return@mapNotNull null
                    }
                    
                    if (gatt is ManagedBluetoothSensorDriver) {
                        val snapshot = gatt.getManagedUiSnapshot(activeSensorSerial) ?: return@mapNotNull null
                        mapManagedSensorInfo(snapshot)
                    } else {
                        // LEGACY NATIVE SENSOR LOGIC
                        val officialEndMs = Natives.getSensorEndTime(gatt.dataptr, true)
                        val expectedEndMs = Natives.getSensorEndTime(gatt.dataptr, false)
                        val startMs = Natives.getSensorStartmsec(gatt.dataptr)
                        val nativeViewMode = Natives.getViewMode(gatt.dataptr)
                        var autoResetDays = Natives.getAutoResetDays(gatt.dataptr)
                        val isSi2 = Natives.isSibionics2(gatt.dataptr)
                        val isSi = Natives.isSibionics(gatt.dataptr)
                        // If 0 (Fresh), force to 21 (Default ON)
                        if (isSi2 && autoResetDays == 0) {
                            Natives.setAutoResetDays(gatt.dataptr, 21)
                            autoResetDays = 21
                        }
    
                        // Get custom calibration settings
                        val customSettings = Natives.getCustomCalibrationSettings(gatt.dataptr)
                        val customEnabled = (customSettings and 1L) != 0L
                        val customAutoReset = (customSettings and 2L) != 0L
                        val customIndex = ((customSettings ushr 8) and 0xFF).toInt()
    
                        // Get detailed status from native code
                        val nativeStatus = Natives.getsensortext(gatt.dataptr) ?: ""
                        val bleStatus = gatt.constatstatusstr ?: ""
                        val warmupStatus = getLegacyWarmupStatus(tk.glucodata.Applic.app, gatt.dataptr, nativeStatus)
                        
                        // Edit 85: Check `stop` (paused) state via SensorBluetooth helper.
                        // SuperGattCallback.stop is protected, not accessible from this package,
                        // so we use SensorBluetooth.isSensorPaused() as a same-package bridge.
                        // When paused (stop=true), the sensor is NOT actively receiving regardless
                        // of what the native streamingIsEnabled flag says (it lags).
                        val isPaused = SensorBluetooth.isSensorPaused(gatt)
                        val isActivelyReceiving = !isPaused && (nativeStatus.isNotEmpty() || gatt.streamingEnabled())
                        
                        fun mapBleStatus(status: String): String = when {
                            status == "Status=22" -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_bluetooth_off)
                            status == "Status=133" -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_connection_failed)
                            status.startsWith("Status=") -> status 
                            else -> status
                        }
                        
                        val finalStatus = when {
                            warmupStatus != null -> warmupStatus
                            nativeStatus.isNotEmpty() -> nativeStatus
                            // Pass through custom status strings from GATT callbacks (e.g., "Connected, waiting for data...", "Connected, raw values received")
                            bleStatus.isNotEmpty() && !bleStatus.startsWith("Status=") -> bleStatus
                            bleStatus.isNotEmpty() && (bleStatus.startsWith("Status=") || bleStatus.contains("Bluetooth off", ignoreCase = true) || bleStatus.contains("search", ignoreCase = true) || bleStatus.contains("Loss of signal", ignoreCase = true)) -> bleStatus
                            isActivelyReceiving && (bleStatus.isEmpty() || bleStatus == "Disconnected") -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_connected)
                            else -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_disconnected)
                        }
                        
                        val displayStatus = mapBleStatus(finalStatus)
                        val sensorSerial = SensorIdentity.resolveAppSensorId(gatt.SerialNumber)
                            ?: gatt.SerialNumber
                            ?: "Unknown"
                        val currentViewMode = nativeViewMode
                        val isActiveSensor = activeSensorSerial != null && SensorIdentity.matches(sensorSerial, activeSensorSerial)
    
                        SensorInfo(
                            serial = sensorSerial,
                            displayName = try { gatt.mygetDeviceName() } catch (_: Throwable) { sensorSerial },
                            deviceAddress = gatt.mActiveDeviceAddress ?: "Unknown",
                            connectionStatus = if (bleStatus.startsWith("Status=")) mapBleStatus(bleStatus) else "",
                            starttime = if (startMs > 0) tk.glucodata.bluediag.datestr(startMs) else "",
                            streaming = warmupStatus == null && isActivelyReceiving,
                            rssi = gatt.readrssi,
                            dataptr = gatt.dataptr,
                            officialEnd = if(officialEndMs > 0) tk.glucodata.bluediag.datestr(officialEndMs) else "",
                            expectedEnd = if(expectedEndMs > 0) tk.glucodata.bluediag.datestr(expectedEndMs) else "",
                            viewMode = currentViewMode,
                            autoResetDays = autoResetDays,
                            isSibionics = isSi,
                            isSibionics2 = isSi2,
                            isAidex = false,
                            startMs = startMs,
                            officialEndMs = officialEndMs,
                            expectedEndMs = expectedEndMs,
                            customCalEnabled = customEnabled,
                            customCalIndex = customIndex,
                            customCalAutoReset = customAutoReset,
                            supportsDisplayModes = false,
                            supportsManualCalibration = false,
                            detailedStatus = displayStatus,
                            isActive = isActiveSensor
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SensorViewModel", "Error loading sensor ${gatt.SerialNumber}", e)
                    SensorInfo(
                        serial = gatt.SerialNumber ?: "Error",
                        displayName = try { gatt.mygetDeviceName() } catch (_: Throwable) { gatt.SerialNumber ?: "Error" },
                        deviceAddress = gatt.mActiveDeviceAddress ?: "Unknown",
                        connectionStatus = "Load Error",
                        starttime = "",
                        streaming = false,
                        rssi = 0,
                        dataptr = gatt.dataptr,
                        officialEnd = "",
                        expectedEnd = "",
                        viewMode = 0,
                        autoResetDays = 0,
                        isSibionics = false,
                        isSibionics2 = false,
                        isAidex = false,
                        isIcan = false,
                        startMs = 0L,
                        officialEndMs = 0L,
                        expectedEndMs = 0L,
                        customCalEnabled = false,
                        customCalIndex = 0,
                        customCalAutoReset = false,
                        supportsDisplayModes = false,
                        supportsManualCalibration = false,
                        detailedStatus = "Error: ${e.message}",
                        isActive = false
                    )
                }
            }
            // Edit 86: Preserve natural order from mygatts() (native insertion order).
            // The main sensor is visually distinguished by its isActive styling —
            // no need to sort it to the top. Sorting on every refresh caused the
            // list to jump when the user manually switched the main sensor.
            _sensors.value = dedupePublishedSensors(sensorList)
        }
    }

    fun setAutoResetDays(serial: String, days: Int) {
        val gatt = findGatt(serial)
        if (gatt != null) {
            Natives.setAutoResetDays(gatt.dataptr, days)
            refreshSensors()
        }
    }

    private fun clearSibionicsTransmitterBinding(gatt: SuperGattCallback, reason: String) {
        val dataptr = gatt.dataptr
        if (dataptr == 0L) return
        try {
            if (!Natives.isSibionics(dataptr)) return
            Natives.siClearTransmitterBinding(dataptr)
            try {
                gatt.setDeviceAddress(null)
            } catch (_: Throwable) {}
            android.util.Log.i("SensorVM", "Cleared Sibionics transmitter binding for ${gatt.SerialNumber} ($reason)")
        } catch (t: Throwable) {
            android.util.Log.e("SensorVM", "clearSibionicsTransmitterBinding($reason) failed: ${t.message}")
        }
    }

    private fun wipeSibionicsDataIfNeeded(gatt: SuperGattCallback, reason: String) {
        val dataptr = gatt.dataptr
        if (dataptr == 0L) return
        try {
            if (!Natives.isSibionics(dataptr)) {
                android.util.Log.w(
                    "SensorVM",
                    "wipeSibionicsDataIfNeeded($reason): ${gatt.SerialNumber} is not Sibionics, skipping siWipeDataOnly"
                )
                return
            }
            Natives.siWipeDataOnly(dataptr)
            clearSibionicsTransmitterBinding(gatt, reason)
        } catch (t: Throwable) {
            android.util.Log.e("SensorVM", "wipeSibionicsDataIfNeeded($reason) failed: ${t.message}")
        }
    }

    private fun forceDeleteSensorDirectory(serial: String) {
        if (serial.isEmpty()) {
            android.util.Log.w("SensorViewModel", "forceDeleteSensorDirectory called with empty serial")
            return
        }
        try {
            val sensorsDir = java.io.File(tk.glucodata.Applic.app.filesDir, "sensors")
            val sensorDir = java.io.File(sensorsDir, serial)
            if (sensorDir.exists()) {
                val success = sensorDir.deleteRecursively()
                android.util.Log.i("SensorViewModel", "Force deleting sensor dir $serial: $success")
            }
        } catch (e: Exception) {
            android.util.Log.e("SensorViewModel", "Failed to force delete sensor dir $serial", e)
        }
    }

    // Edit 76: Remove an AiDex sensor from SharedPrefs (reverted to Edit 56a inline implementation).
    private fun removeAiDexFromPrefs(serial: String) {
        try {
            val prefs = tk.glucodata.Applic.app.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
            val sensors = prefs.getStringSet("aidex_sensors", HashSet()) ?: return
            val updated = HashSet(sensors)
            val removed = updated.removeAll { it.startsWith("$serial|") || it == serial }
            if (removed) {
                prefs.edit().putStringSet("aidex_sensors", updated).commit()
                android.util.Log.i("SensorVM", "Edit 56a: Removed $serial from aidex_sensors prefs")
            }
        } catch (t: Throwable) {
            android.util.Log.e("SensorVM", "Edit 56a: removeAiDexFromPrefs failed: ${t.message}")
        }
    }

    // Edit 56b: When terminating/forgetting the current lastsensorname, update it to
    // the next active sensor. Otherwise notification/UI state can keep targeting the
    // finished sensor on every refresh cycle.
    // Multi-sensor: No longer calls forceFullSync()/clearAllTables(). Data from the
    // departed sensor stays in Room (harmless — just won't be queried on the dashboard
    // since the dashboard filters by main sensor serial). The next sensor's data is
    // already synced.
    private fun switchAwayFromSensor(serial: String) {
        try {
            val current = SensorIdentity.resolveMainSensor()
            if (SensorIdentity.matches(current, serial)) {
                val next = SensorBluetooth.resolveReplacementSensorSerial(serial)
                if (next != null) {
                    SensorBluetooth.setCurrentSensorSelection(next)
                    android.util.Log.i("SensorVM", "Edit 56b: Switched current sensor from $serial to $next")
                    // Ensure the new main sensor's data is up-to-date in Room
                    try {
                        tk.glucodata.data.HistorySync.mergeFullSyncForSensor(next)
                    } catch (t2: Throwable) {
                        android.util.Log.e("SensorVM", "switchAwayFromSensor sync failed: ${t2.message}")
                    }
                } else {
                    // No other active sensor — set to empty to stop Notify from hitting getdataptr
                    SensorBluetooth.setCurrentSensorSelection("")
                    android.util.Log.i("SensorVM", "Edit 56b: Cleared current sensor (was $serial, no other active)")
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("SensorVM", "Edit 56b: switchAwayFromSensor failed: ${t.message}")
        }
    }

    // "Disconnect" in UI now maps to "Terminate" (finishSensor) as requested.
    // Edit 39c: Guard ALL JNI calls with dataptr != 0 check. For AiDex sensors,
    // route through forgetVendor() which handles vendor stack, BLE bond, and key cleanup
    // without touching libg.so native code (which crashes with SIGSEGV on null dataptr).
    // Edit 54a: For both AiDex and legacy, stop BLE processing BEFORE finishSensor()
    // to prevent race where incoming BLE notification resets finished=0 via processchanged().
    // Sequence: setPause(true) → disconnect() → finishSensor() → sensorEnded().
    fun terminateSensor(serial: String, wipeData: Boolean = false) {
        // Edit 56b: Switch lastsensorname away BEFORE teardown to prevent Notify.java
        // from calling getdataptr on the finished sensor during the teardown window
        switchAwayFromSensor(serial)
        val gatt = findGatt(serial)
        if (gatt != null) {
            try {
                if (gatt is ManagedBluetoothSensorDriver) {
                    gatt.terminateManagedSensor(wipeData)
                    gatt.removeManagedPersistence(tk.glucodata.Applic.app)
                    SensorBluetooth.sensorEnded(serial)
                } else if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
                    // AiDex: never call Sibionics wipe JNI here.
                    // If wipeData=true, forceDeleteSensorDirectory() below handles local AiDex files.
                    if (wipeData) {
                        android.util.Log.i("SensorVM", "terminateSensor AiDex wipeData requested: using AiDex-local cleanup only")
                    }
                    try { gatt.forgetVendor() } catch (t: Throwable) {
                        android.util.Log.e("SensorVM", "terminateSensor AiDex forgetVendor failed: ${t.message}")
                    }
                    // Edit 54a: Set finished=1 in native so bluetoothactive() skips this sensor
                    if (gatt.dataptr != 0L) {
                        try { gatt.finishSensor() } catch (t: Throwable) {
                            android.util.Log.e("SensorVM", "terminateSensor AiDex finishSensor failed: ${t.message}")
                        }
                    }
                    try { gatt.close() } catch (t: Throwable) {
                        android.util.Log.e("SensorVM", "terminateSensor AiDex close failed: ${t.message}")
                    }
                    // Edit 56a: Remove from SharedPreferences BEFORE sensorEnded to prevent
                    // updateDevicers() from re-adding it to gattcallbacks
                    removeAiDexFromPrefs(serial)
                    SensorBluetooth.sensorEnded(serial)
                } else {
                    // Legacy sensors: native finishSensor path
                    // Edit 54a: Stop BLE processing first to prevent race
                    gatt.setPause(true)
                    gatt.disconnect()
                    if (wipeData && gatt.dataptr != 0L) {
                        wipeSibionicsDataIfNeeded(gatt, "terminate/wipe")
                    }
                    gatt.finishSensor()
                    SensorBluetooth.sensorEnded(serial)
                }
            } catch (t: Throwable) {
                android.util.Log.e("SensorViewModel", "terminateSensor($serial) crashed: ${t.message}", t)
                // Still try to clean up
                try { SensorBluetooth.sensorEnded(serial) } catch (_: Throwable) {}
            }
        } else {
            // A managed record can still exist after a bad restore/update even if
            // its live callback is gone or has already promoted to another id.
            try {
                ManagedSensorIdentityRegistry.removePersistedSensor(tk.glucodata.Applic.app, serial)
            } catch (t: Throwable) {
                android.util.Log.e("SensorViewModel", "terminateSensor($serial) managed persistence cleanup failed: ${t.message}", t)
            }
            try { SensorBluetooth.sensorEnded(serial) } catch (_: Throwable) {}
        }

        // Force delete AFTER stopping everything and native wipe, to ensure no recreating happens
        if (wipeData) {
            forceDeleteSensorDirectory(serial)
        }

        refreshSensors()
    }

    // Edit 54b: Also mark sensor finished in native so bluetoothactive() won't return it
    // and cause re-creation in updateDevicers(). Stop BLE processing first to prevent race.
    fun forgetSensor(serial: String) {
        // Edit 56b: Switch lastsensorname away first
        switchAwayFromSensor(serial)
        val gatt = findGatt(serial)
        if (gatt != null) {
            if (gatt is ManagedBluetoothSensorDriver) {
                try {
                    gatt.terminateManagedSensor(wipeData = false)
                    gatt.removeManagedPersistence(tk.glucodata.Applic.app)
                } catch (t: Throwable) {
                    android.util.Log.e("SensorViewModel", "forgetSensor($serial) managed teardown crashed: ${t.message}", t)
                }
                try { SensorBluetooth.sensorEnded(serial) } catch (_: Throwable) {}
                try { SensorBluetooth.startscan() } catch (_: Throwable) {}
                refreshSensors()
                return
            }
            // Edit 38e: Wrap in try/catch to prevent crashes when GATT or vendor state
            // is already torn down. The forgetVendor→stopVendor→close chain can crash if
            // called from the UI thread while native lib is mid-operation.
            try {
                // AiDex: stop vendor stack, remove BLE bond, wipe saved AES keys
                if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
                    gatt.forgetVendor()
                }
            } catch (t: Throwable) {
                android.util.Log.e("SensorViewModel", "forgetSensor($serial) forgetVendor crashed: ${t.message}", t)
            }
            // Stop BLE processing and mark finished before removing from Java lists
            try {
                gatt.setPause(true)
                gatt.disconnect()
                if (gatt.dataptr != 0L) {
                    gatt.finishSensor()
                }
            } catch (t: Throwable) {
                android.util.Log.e("SensorViewModel", "forgetSensor($serial) finishSensor crashed: ${t.message}", t)
            }
            if (gatt !is tk.glucodata.drivers.aidex.AiDexDriver) {
                clearSibionicsTransmitterBinding(gatt, "forget")
            }
            try {
                gatt.close()
            } catch (t: Throwable) {
                android.util.Log.e("SensorViewModel", "forgetSensor($serial) close crashed: ${t.message}", t)
            }
        }
        // Edit 56a: Remove from SharedPreferences BEFORE sensorEnded
        removeAiDexFromPrefs(serial)
        // Properly notify system that sensor is ended/removed from list
        try { SensorBluetooth.sensorEnded(serial) } catch (_: Throwable) {}
        // Restart scanning so the system can find new sensors
        try { SensorBluetooth.startscan() } catch (_: Throwable) {}
        refreshSensors()
    }

    fun resetSensor(serial: String, enableBiasCompensation: Boolean = false) {
         val gatt = findGatt(serial)
         if (gatt != null) {
             if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
                 // Route AiDex to multi-strategy reset (runs on IO thread)
                 resetAiDexSensor(serial, enableBiasCompensation)
             } else if (gatt is ManagedSensorMaintenanceDriver && gatt.supportsResetAction()) {
                 viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                     val success = runCatching { gatt.resetSensor() }.getOrDefault(false)
                     android.util.Log.i("SensorVM", "Managed resetSensor result: $success serial=$serial")
                     refreshSensors()
                 }
             } else {
                 Natives.setResetSibionics2(gatt.dataptr, true)
             }
         }
    }

    fun clearCalibration(serial: String) {
        val gatt = findGatt(serial)
        if (gatt != null && gatt.dataptr != 0L) {
            try { Natives.siClearCalibration(gatt.dataptr) } catch (_: Throwable) {}
        }
    }

    fun localReplay(serial: String) {
        val gatt = findGatt(serial)
        if (gatt != null && gatt.dataptr != 0L) {
            try { Natives.siLocalReplay(gatt.dataptr) } catch (_: Throwable) {}
            // Force chart refresh — localReplay modifies polls[] in mmap,
            // Room DB needs sync to reflect the recalibrated values.
            // Multi-sensor: sync only this sensor, not destructive clearAllTables
            try { tk.glucodata.data.HistorySync.forceFullSyncForSensor(serial) } catch (_: Throwable) {}
            UiRefreshBus.requestStatusRefresh()
            refreshSensors()
        }
    }

    /**
     * Fresh-equivalent native restart for Sibionics:
     * rebind native algorithm state from persisted native files while preserving
     * current view mode (Auto/Raw/Auto+Raw/Raw+Auto).
     */
    fun restartSibionicsNativeFresh(serial: String) {
        val gatt = findGatt(serial) ?: return
        if (gatt.dataptr == 0L) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val opId = sibionicsFreshRestartSeq.getAndIncrement()
            val preservedViewMode = try { Natives.getViewMode(gatt.dataptr) } catch (_: Throwable) { 0 }

            val ok = try {
                Natives.siRebindNativeContext(gatt.dataptr, preservedViewMode)
            } catch (t: Throwable) {
                android.util.Log.e("SensorVM", "SibNativeFresh[$opId] native rebind failed: ${t.message}")
                false
            }

            android.util.Log.i(
                "SensorVM",
                "SibNativeFresh[$opId] done ok=$ok preservedViewMode=$preservedViewMode fallbackPath=rebind-only"
            )
            if (ok) {
                try { tk.glucodata.data.HistorySync.forceFullSyncForSensor(serial) } catch (_: Throwable) {}
            }
            refreshSensors()
        }
    }

    fun clearAll(serial: String) {
        val gatt = findGatt(serial)
        if (gatt != null && gatt.dataptr != 0L) {
            try { tk.glucodata.data.HistorySync.markSensorReset(serial) } catch (_: Throwable) {}
            try { Natives.siClearAll(gatt.dataptr) } catch (_: Throwable) {}
        }
    }

    fun setCalibrationMode(serial: String, mode: Int) {
        val gatt = findGatt(serial)
        if (gatt != null) {
            val normalizedMode = ManagedSensorViewModeStore.sanitize(mode)
            ManagedSensorViewModeStore.write(Applic.app, serial, normalizedMode)
            if (gatt is ManagedBluetoothSensorDriver) {
                gatt.viewMode = normalizedMode
            }
            if (gatt.dataptr != 0L) {
                Natives.setViewMode(gatt.dataptr, normalizedMode)
            }
            UiRefreshBus.requestStatusRefresh()
            refreshSensors()
        }
    }

    fun updateCustomCalibration(serial: String, enabled: Boolean, index: Int, autoReset: Boolean) {
        val gatt = findGatt(serial)
        if (gatt != null && gatt.dataptr != 0L) {
            val opId = sibionicsCustomToggleSeq.getAndIncrement()
            val currentSettings = try { Natives.getCustomCalibrationSettings(gatt.dataptr) } catch (_: Throwable) { -1L }
            var currentEnabled = false
            var currentAutoReset = false
            var currentIndex = 0
            if (currentSettings >= 0L) {
                currentEnabled = (currentSettings and 1L) != 0L
                currentAutoReset = (currentSettings and 2L) != 0L
                currentIndex = ((currentSettings shr 8) and 0xFF).toInt()
                if (currentEnabled == enabled && currentAutoReset == autoReset && currentIndex == index) {
                    android.util.Log.d("SensorVM", "updateCustomCalibration[$opId]: unchanged ($serial), skipping native write")
                    refreshSensors()
                    return
                }
            }
            val offToOn = !currentEnabled && enabled
            Natives.setCustomCalibrationSettings(gatt.dataptr, enabled, index, autoReset)
            android.util.Log.i(
                "SensorVM",
                "updateCustomCalibration[$opId]: serial=$serial from(enabled=$currentEnabled,index=$currentIndex,autoReset=$currentAutoReset) " +
                    "to(enabled=$enabled,index=$index,autoReset=$autoReset) offToOn=$offToOn"
            )
            UiRefreshBus.requestStatusRefresh()
            refreshSensors()
        }
    }

    /**
     * Disable custom calibration and restore the last native snapshot when available.
     * Falls back to native rebind only; never falls back to destructive native replay.
     */
    fun disableCustomCalAndReplay(serial: String) {
        val gatt = findGatt(serial)
        if (gatt != null && gatt.dataptr != 0L) {
            val opId = sibionicsDisableSeq.getAndIncrement()
            val wasCustomEnabled = try {
                (Natives.getCustomCalibrationSettings(gatt.dataptr) and 1L) != 0L
            } catch (_: Throwable) {
                // Keep old behavior if settings read fails.
                true
            }
            val vmBefore = try { Natives.getViewMode(gatt.dataptr) } catch (_: Throwable) { -1 }
            if (wasCustomEnabled) {
                Natives.setCustomCalibrationSettings(gatt.dataptr, false, 0, false)
                val restoreOk = try { Natives.siRestoreOriginalPolls(gatt.dataptr) } catch (_: Throwable) { false }
                val vm = if (vmBefore in 0..3) vmBefore else 0
                val rebindOk = if (!restoreOk) {
                    try { Natives.siRebindNativeContext(gatt.dataptr, vm) } catch (_: Throwable) { false }
                } else {
                    false
                }
                val vmAfter = try { Natives.getViewMode(gatt.dataptr) } catch (_: Throwable) { -1 }
                android.util.Log.i(
                    "SensorVM",
                    "disableCustomCal[$opId]: serial=$serial wasCustomEnabled=1 restoreOk=$restoreOk rebindOk=$rebindOk viewMode=$vmBefore->$vmAfter"
                )
            } else {
                android.util.Log.i("SensorVM", "disableCustomCal[$opId]: serial=$serial wasCustomEnabled=0 fallbackPath=none")
            }
            try { tk.glucodata.data.HistorySync.forceFullSyncForSensor(serial) } catch (_: Throwable) {}
            UiRefreshBus.requestStatusRefresh()
            refreshSensors()
        }
    }

    // Edit 39d: AiDex-safe reconnect. For AiDex, restart vendor stack instead of
    // calling native resetbluetooth (SIGSEGV risk). For legacy sensors, use proven sequence.
    fun reconnectSensor(serial: String, wipeData: Boolean = false) {
        val gatt = findGatt(serial)
        if (gatt != null) {
            viewModelScope.launch {
                if (gatt is ManagedBluetoothSensorDriver) {
                    if (wipeData) {
                        android.util.Log.w("SensorVM", "Managed reconnect requested with wipeData=true; using non-destructive reconnect")
                    }
                    gatt.softReconnect()
                } else if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
                    // AiDex reconnect must be non-destructive: keep pairing keys/local sensor state.
                    // Using forgetVendor() here wipes pairing + removes from prefs, which can leave
                    // the sensor dead until a full app reset/re-add.
                    if (wipeData) {
                        android.util.Log.w("SensorVM", "AiDex reconnect requested with wipeData=true; running safe reconnect without destructive wipe")
                    }
                    if (gatt.dataptr != 0L) {
                        try { Natives.unfinishSensor(gatt.dataptr) } catch (t: Throwable) {
                            android.util.Log.e("SensorVM", "reconnectSensor AiDex unfinishSensor: ${t.message}")
                        }
                    }
                    try { gatt.softDisconnect() } catch (t: Throwable) {
                        android.util.Log.e("SensorVM", "reconnectSensor AiDex softDisconnect: ${t.message}")
                    }
                    kotlinx.coroutines.delay(250)
                    gatt.manualReconnectNow()
                } else {
                    if (wipeData && gatt.dataptr != 0L) {
                        wipeSibionicsDataIfNeeded(gatt, "reconnect/wipe")
                    }
                    gatt.setPause(true)
                    gatt.disconnect()
                    kotlinx.coroutines.delay(500)
                    if (gatt.dataptr != 0L) {
                        try { Natives.resetbluetooth(gatt.dataptr) } catch (_: Throwable) {}
                    }
                    gatt.setPause(false)
                    gatt.connectDevice(200)
                }
                refreshSensors()
            }
        }
    }

    fun wipeSensorData(serial: String) {
        val gatt = findGatt(serial)
        if (gatt != null && gatt.dataptr != 0L) {
            try {
                wipeSibionicsDataIfNeeded(gatt, "wipeData")
            } catch (t: Throwable) {
                android.util.Log.e("SensorVM", "wipeSensorData: ${t.message}")
            }
            // Edit 84: Only delete the sensor directory for AiDex sensors.
            // Legacy/Sibionics sensors don't use a per-sensor directory — their data
            // lives in the mmap'd native file, which siWipeDataOnly already handles.
            if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
                forceDeleteSensorDirectory(serial)
            }
            refreshSensors()
        }
    }

    // Edit 39d: AiDex-safe disconnect. For AiDex, soft-stop the vendor stack and disconnect GATT
    // WITHOUT calling forgetVendor() (which destroys bond + keys, making reconnect impossible).
    // Preserve bond + keys so the Play button can reconnect later.
    // Edit 84: Legacy sensors restored to dev-latest behavior — non-destructive disconnect
    // (setPause + disconnect + resetbluetooth). Does NOT call finishSensor/sensorEnded, so
    // the sensor stays in the list and can be reconnected with reconnectSensor().
    // The old Edit 54c code called finishSensor+sensorEnded which permanently terminated
    // the sensor — this was the "pause button disconnected Sibionics" bug.
    fun disconnectSensor(serial: String) {
        android.util.Log.d("SensorViewModel", "disconnectSensor called for: $serial")
        val gatt = findGatt(serial)
        if (gatt != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (gatt is ManagedBluetoothSensorDriver) {
                    gatt.softDisconnect()
                } else if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
                    // AiDex disconnect must stay non-destructive so Play can reconnect.
                    android.util.Log.d("SensorViewModel", "AiDex disconnect: soft-stopping vendor + GATT (non-destructive)")
                    try { gatt.softDisconnect() } catch (t: Throwable) {
                        android.util.Log.e("SensorVM", "disconnectSensor AiDex softDisconnect: ${t.message}")
                    }
                } else {
                    // Edit 84: Legacy sensors — non-destructive disconnect (dev-latest behavior).
                    // Does NOT call finishSensor/sensorEnded — sensor stays in the list.
                    // Use reconnectSensor() to bring it back.
                    android.util.Log.d("SensorViewModel", "Legacy disconnect: setPause + disconnect + resetbluetooth (non-destructive)")
                    gatt.setPause(true)
                    gatt.disconnect()
                    kotlinx.coroutines.delay(500)
                    if (gatt.dataptr != 0L) {
                        try { Natives.resetbluetooth(gatt.dataptr) } catch (_: Throwable) {}
                    }
                    // DON'T call finishSensor/sensorEnded — just refresh to show disconnected state
                }
                refreshSensors()
            }
        } else {
            android.util.Log.d("SensorViewModel", "Gatt not found for serial: $serial")
        }
    }

    fun sendAiDexMaintenanceCommand(serial: String, opCode: Int) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val success = gatt.sendMaintenanceCommand(opCode)
                if (success) {
                    refreshSensors()
                }
            }
        }
    }

    /**
     * Multi-strategy AiDex sensor reset: vendor native lib -> FF32 direct write -> BLE bond removal.
     * Must run on IO dispatcher (uses Thread.sleep internally).
     */
    fun resetAiDexSensor(serial: String, enableBiasCompensation: Boolean = false) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // Edit 59d: Enable/disable bias compensation before reset
                if (enableBiasCompensation) {
                    gatt.enableResetCompensation()
                } else {
                    gatt.disableResetCompensation()
                }
                val success = gatt.resetSensor()
                android.util.Log.i("SensorVM", "AiDex resetSensor result: $success, biasCompensation=$enableBiasCompensation")
                refreshSensors()
            }
        }
    }

    /**
     * Disable AiDex initialization bias compensation for a sensor.
     */
    fun disableAiDexBiasCompensation(serial: String) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            gatt.disableResetCompensation()
            viewModelScope.launch { refreshSensors() }
        }
    }

    /**
     * Edit 77: Manually enable AiDex initialization bias compensation for a sensor.
     * Use case: app was reinstalled while sensor was already in its post-reset compensation
     * window, so the persisted compensation state was lost. This lets the user re-enable it.
     */
    fun enableAiDexBiasCompensation(serial: String) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            gatt.enableResetCompensation()
            viewModelScope.launch { refreshSensors() }
        }
    }

    /**
     * Multi-strategy AiDex start new sensor: vendor native lib -> FF32 direct write -> full reset fallback.
     * Must run on IO dispatcher (uses Thread.sleep internally).
     */
    fun startNewAiDexSensor(serial: String) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val success = gatt.startNewSensor()
                android.util.Log.i("SensorVM", "AiDex startNewSensor result: $success")
                refreshSensors()
            }
        }
    }

    /**
     * Send a manual calibration (finger-stick blood glucose) to the AiDex sensor.
     * @param glucoseMgDl glucose in mg/dL (integer)
     */
    fun calibrateAiDexSensor(serial: String, glucoseMgDl: Int) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val success = gatt.calibrateSensor(glucoseMgDl)
                android.util.Log.i("SensorVM", "AiDex calibrateSensor($glucoseMgDl mg/dL) result: $success")
            }
        }
    }

    fun calibrateManagedSensor(serial: String, glucoseMgDl: Int) {
        val gatt = findGatt(serial)
        if (gatt is ManagedSensorMaintenanceDriver) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val success = gatt.calibrateSensor(glucoseMgDl)
                android.util.Log.i("SensorVM", "Managed calibrateSensor($serial, $glucoseMgDl mg/dL) result: $success")
                refreshSensors()
            }
        }
    }

    fun fetchMqBootstrap(serial: String, qrCode: String?) {
        val context = Applic.app ?: return
        val gatt = findGatt(serial)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (gatt is MQDriver) {
                val success = gatt.refreshVendorBootstrap(
                    context = context,
                    qrCode = qrCode,
                )
                android.util.Log.i("SensorVM", "MQ refreshVendorBootstrap($serial) result: $success")
                refreshSensors()
                return@launch
            }
            val record = MQRegistry.findRecord(context, serial) ?: return@launch
            val normalizedQr = qrCode?.trim().orEmpty().takeIf { it.isNotEmpty() }
                ?: MQRegistry.loadQrContent(context, record.sensorId)
            if (normalizedQr != null) {
                MQRegistry.saveQrContent(context, record.sensorId, normalizedQr)
            }
            val accountState = MQRegistry.loadAccountState(context)
            val result = MQBootstrapClient.fetchBestEffort(
                context = context,
                bleId = record.address.takeIf { it.isNotBlank() },
                qrCode = normalizedQr,
                authToken = accountState.authToken,
                credentials = accountState.credentials,
            )
            result.refreshedToken?.let { MQRegistry.saveAuthToken(context, it) }
            result.config?.let { MQRegistry.applyBootstrapConfig(context, record.sensorId, it) }
            android.util.Log.i("SensorVM", "MQ refreshVendorBootstrap($serial) fallback result: ${result.config != null}")
            refreshSensors()
        }
    }

    /**
     * Unpair from the AiDex sensor: delete bond on sensor side, clear saved keys.
     */
    fun unpairAiDexSensor(serial: String) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val success = gatt.unpairSensor()
                android.util.Log.i("SensorVM", "AiDex unpairSensor result: $success")
                refreshSensors()
            }
        }
    }

    /**
     * Re-pair with the AiDex sensor: clear keys and restart vendor stack for fresh pairing.
     */
    fun rePairAiDexSensor(serial: String) {
        val gatt = findGatt(serial)
        if (gatt is tk.glucodata.drivers.aidex.AiDexDriver) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                gatt.rePairSensor()
                android.util.Log.i("SensorVM", "AiDex rePairSensor initiated")
                refreshSensors()
            }
        }
    }
}
