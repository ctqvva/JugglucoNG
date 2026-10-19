// JugglucoNG — AiDex Native Kotlin Driver
// AiDexNativeSensorManager.kt — Multi-sensor orchestrator
//
// Manages multiple AiDexBleManager instances, coordinating:
//   - Sensor registry (add, remove, lookup)
//   - Main sensor designation (notifications/alerts gating)
//   - Cross-sensor health checks (othersworking pattern)
//   - SharedPrefs persistence (serial|address StringSet)
//   - HealthConnect write deduplication
//   - Scan dispatch (device name → correct AiDexBleManager)
//   - Full removal lifecycle (12-step teardown)
//
// This is the Phase 3 orchestrator that sits above AiDexBleManager (Phase 2)
// and below SensorBluetooth (Phase 4 integration).

package tk.glucodata.drivers.aidex.native.ble

import tk.glucodata.drivers.aidex.native.data.GlucoseReading

/**
 * Interface for BLE manager lifecycle control.
 *
 * AiDexBleManager implements this. Using an interface here allows
 * AiDexNativeSensorManager to be compiled and tested without Android
 * framework dependencies (BluetoothGatt, etc.).
 */
interface SensorBleController {
    /** Release all resources (disconnect, close GATT, stop handler thread). */
    fun destroy()
}

/**
 * Manages multiple AiDex sensors simultaneously.
 *
 * Mirrors the multi-sensor patterns from SensorBluetooth.java:
 * - [addSensor] / [removeSensor] for runtime management
 * - [mainSensorSerial] for notification/alert gating
 * - [onGlucoseReceived] triggers cross-sensor health checks
 * - SharedPrefs persistence prevents resurrection of removed sensors
 *
 * Thread safety: All public methods synchronize on [lock]. The sensor list
 * and main sensor designation are always consistent within a single lock scope.
 *
 * @param persistence Strategy for persisting sensor list and main sensor designation.
 *   Injected to allow unit testing without Android SharedPreferences.
 * @param clock Provides current time in millis. Injected for testability.
 * @param reconnectTimeoutMs How long since last glucose before a sensor is considered
 *   stale and should be force-reconnected. Default 5 minutes (matches Notify.glucosetimeout).
 * @param reconnectDebounceMs Minimum interval between reconnect attempts for a single sensor.
 *   Default 60 seconds (matches SuperGattCallback.shouldreconnect debounce).
 */
class AiDexNativeSensorManager(
    private val persistence: SensorPersistence = InMemoryPersistence(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val reconnectTimeoutMs: Long = 5 * 60_000L,
    private val reconnectDebounceMs: Long = 60_000L,
) {
    companion object {
        private const val TAG = "AiDexNativeSensorMgr"
    }

    // =========================================================================
    // Sensor Persistence Interface
    // =========================================================================

    /**
     * Strategy for persisting the sensor list and main sensor designation.
     *
     * The Android implementation wraps SharedPreferences; the in-memory
     * implementation is used for unit tests.
     */
    interface SensorPersistence {
        /** Load persisted sensor entries. Each entry is "serial|address". */
        fun loadSensorEntries(): Set<String>

        /** Save sensor entries. Each entry is "serial|address". */
        fun saveSensorEntries(entries: Set<String>)

        /** Load the main sensor serial (or null if none designated). */
        fun loadMainSensor(): String?

        /** Save the main sensor serial (null to clear). */
        fun saveMainSensor(serial: String?)
    }

    /**
     * In-memory persistence for unit testing. No SharedPreferences needed.
     */
    class InMemoryPersistence : SensorPersistence {
        var entries: MutableSet<String> = mutableSetOf()
        var mainSensor: String? = null

        override fun loadSensorEntries(): Set<String> = entries.toSet()
        override fun saveSensorEntries(entries: Set<String>) {
            this.entries = entries.toMutableSet()
        }
        override fun loadMainSensor(): String? = mainSensor
        override fun saveMainSensor(serial: String?) {
            mainSensor = serial
        }
    }

    // =========================================================================
    // Sensor State
    // =========================================================================

    /**
     * Snapshot of a managed sensor's state, exposed for UI/testing.
     */
    data class SensorState(
        val serial: String,
        val address: String?,
        val isMain: Boolean,
        val lastGlucoseTimeMs: Long,
        val lastConnectTimeMs: Long,
        val healthWriteBlocked: Boolean,
    )

    // =========================================================================
    // Internal Per-Sensor Tracking
    // =========================================================================

    /**
     * Internal bookkeeping for each managed sensor.
     *
     * In Phase 4, [bleManager] will be a real AiDexBleManager instance.
     * For Phase 3, we track the state that the manager needs to coordinate
     * without requiring Android BLE framework dependencies.
     */
    internal class ManagedSensor(
        val serial: String,
        var address: String? = null,
        var bleManager: SensorBleController? = null,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        /** Timestamp of last successful glucose reception (charcha[0] equivalent) */
        @Volatile var lastGlucoseTimeMs: Long = 0L

        /** Timestamp of last failed glucose attempt (charcha[1] equivalent) */
        @Volatile var lastFailureTimeMs: Long = 0L

        /** Timestamp when this manager was created */
        val createdAtMs: Long = createdAt

        /** Timestamp of last connectDevice() call */
        @Volatile var lastConnectTimeMs: Long = 0L

        /** Whether HealthConnect writes are blocked for this sensor (dedup) */
        @Volatile var healthWriteBlocked: Boolean = false

        /** Whether this sensor has been paused/stopped (blocks reconnect) */
        @Volatile var isPaused: Boolean = false
    }

    // =========================================================================
    // State
    // =========================================================================

    private val lock = Any()

    /** All managed sensors, keyed by serial number. */
    private val sensors = LinkedHashMap<String, ManagedSensor>()

    /** The serial of the main sensor (notifications/alerts only for this one). */
    private var _mainSensorSerial: String? = null

    /** Public read-only access to the main sensor serial. */
    val mainSensorSerial: String?
        get() = synchronized(lock) { _mainSensorSerial }

    /** Whether the manager has been started (loadFromPersistence called). */
    @Volatile var isStarted: Boolean = false
        private set

    // =========================================================================
    // Listeners
    // =========================================================================

    /**
     * Called when any sensor receives a glucose reading.
     * The Boolean indicates whether this is the main sensor.
     */
    var onGlucoseReading: ((reading: GlucoseReading, isMainSensor: Boolean) -> Unit)? = null

    /**
     * Called when a non-main sensor should be force-reconnected
     * (cross-sensor health check triggered).
     */
    var onShouldReconnect: ((serial: String) -> Unit)? = null

    /**
     * Called when the sensor list changes (add, remove, main sensor change).
     */
    var onSensorListChanged: (() -> Unit)? = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Load sensor list and main sensor designation from persistence.
     * Call once at startup. Idempotent.
     *
     * @return the list of sensor serials that were loaded
     */
    fun loadFromPersistence(): List<String> {
        synchronized(lock) {
            if (isStarted) return sensors.keys.toList()

            val entries = persistence.loadSensorEntries()
            for (entry in entries) {
                val parts = entry.split("|", limit = 2)
                val serial = parts[0]
                val address = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else null

                if (serial.isBlank()) continue
                if (sensors.containsKey(serial)) continue

                sensors[serial] = ManagedSensor(serial = serial, address = address, createdAt = clock())
            }

            _mainSensorSerial = persistence.loadMainSensor()

            // Validate: if the persisted main sensor isn't in the list, clear it
            if (_mainSensorSerial != null && !sensors.containsKey(_mainSensorSerial!!)) {
                _mainSensorSerial = null
                persistence.saveMainSensor(null)
            }

            isStarted = true
            return sensors.keys.toList()
        }
    }

    // =========================================================================
    // Sensor Addition
    // =========================================================================

    /**
     * Add a sensor for management. Persists to storage.
     *
     * This is the runtime hot-add path (equivalent to SensorBluetooth.addAiDexSensor).
     *
     * Does NOT automatically set this as the main sensor. The caller must explicitly
     * call [setMainSensor] if desired. This is a deliberate multi-sensor fix — see
     * SensorBluetooth.addAiDexSensor lines 878-884.
     *
     * @param serial Sensor serial number (bare or with "X-" prefix)
     * @param address BLE MAC address (optional, for faster scan matching)
     * @return true if the sensor was added, false if it was already registered
     */
    fun addSensor(serial: String, address: String? = null): Boolean {
        synchronized(lock) {
            if (serial.isBlank()) return false
            if (sensors.containsKey(serial)) return false

            val managed = ManagedSensor(serial = serial, address = address, createdAt = clock())
            sensors[serial] = managed

            // Persist
            persistSensorList()

            // If this is the first sensor and no main is set, auto-designate
            if (_mainSensorSerial == null && sensors.size == 1) {
                _mainSensorSerial = serial
                persistence.saveMainSensor(serial)
            }

            onSensorListChanged?.invoke()
            return true
        }
    }

    /**
     * Bulk-add sensors at startup (equivalent to SensorBluetooth.setDevices).
     *
     * Unlike [addSensor], this does NOT auto-designate a main sensor and does NOT
     * persist (the entries are assumed to come from an authoritative source like
     * Natives.activeSensors()).
     *
     * @param serials List of serial numbers to register
     * @return number of sensors actually added (excluding duplicates)
     */
    fun addSensorsBulk(serials: List<String>): Int {
        synchronized(lock) {
            var added = 0
            for (serial in serials) {
                if (serial.isBlank()) continue
                if (sensors.containsKey(serial)) continue
                sensors[serial] = ManagedSensor(serial = serial, createdAt = clock())
                added++
            }
            if (added > 0) {
                onSensorListChanged?.invoke()
            }
            return added
        }
    }

    // =========================================================================
    // Sensor Removal
    // =========================================================================

    /**
     * Result of a sensor removal operation.
     */
    data class RemovalResult(
        /** Whether the sensor was found and removed */
        val removed: Boolean,
        /** Whether the removed sensor was the main sensor (main designation cleared) */
        val wasMainSensor: Boolean,
        /** The BLE address of the removed sensor (for bond removal by caller) */
        val address: String?,
    )

    /**
     * Remove a sensor from management. Full teardown lifecycle.
     *
     * Equivalent to the 12-step forgetVendor() in AiDexSensor.kt:
     *
     *  1. Block auto-reconnect (isPaused = true)
     *  2. Clear main sensor designation if this was the main sensor
     *  3. Destroy BLE manager (disconnect, close, release resources)
     *  4. Remove from sensor registry
     *  5. Persist updated sensor list (prevents resurrection by loadFromPersistence)
     *  6. Reset HealthConnect dedup flags on remaining sensors
     *
     * Steps that require Android framework (remove BLE bond, clear native layer,
     * clear cached vendor params) are left to the caller via the [RemovalResult].
     *
     * @param serial Serial number of the sensor to remove
     * @return [RemovalResult] with details for the caller to complete cleanup
     */
    fun removeSensor(serial: String): RemovalResult {
        synchronized(lock) {
            val managed = sensors[serial]
                ?: return RemovalResult(removed = false, wasMainSensor = false, address = null)

            // Step 1: Block auto-reconnect
            managed.isPaused = true

            // Step 2: Clear main sensor if this was it
            val wasMain = _mainSensorSerial == serial
            if (wasMain) {
                _mainSensorSerial = null
                persistence.saveMainSensor(null)
            }

            // Step 3: Destroy BLE manager
            managed.bleManager?.destroy()
            managed.bleManager = null

            // Step 4: Remove from registry
            val address = managed.address
            sensors.remove(serial)

            // Step 5: Persist (synchronous equivalent — prevents resurrection)
            persistSensorList()

            // Step 6: Reset HealthConnect dedup on remaining sensors
            for (remaining in sensors.values) {
                remaining.healthWriteBlocked = false
            }

            onSensorListChanged?.invoke()
            return RemovalResult(removed = true, wasMainSensor = wasMain, address = address)
        }
    }

    /**
     * Remove all sensors. Calls [removeSensor] for each.
     *
     * @return number of sensors removed
     */
    fun removeAllSensors(): Int {
        synchronized(lock) {
            val serials = sensors.keys.toList()
            var count = 0
            for (serial in serials) {
                if (removeSensor(serial).removed) count++
            }
            return count
        }
    }

    // =========================================================================
    // Main Sensor Designation
    // =========================================================================

    /**
     * Set the main sensor. Only the main sensor triggers notifications, alerts,
     * and HealthConnect writes. Non-main sensors still store data and update charts.
     *
     * @param serial The sensor to designate as main, or null to clear
     * @return true if the designation changed
     */
    fun setMainSensor(serial: String?): Boolean {
        synchronized(lock) {
            // Validate: the serial must be in our sensor list (or null to clear)
            if (serial != null && !sensors.containsKey(serial)) return false
            if (_mainSensorSerial == serial) return false

            _mainSensorSerial = serial
            persistence.saveMainSensor(serial)
            onSensorListChanged?.invoke()
            return true
        }
    }

    /**
     * Check if a given serial is the main sensor.
     *
     * Uses loose matching (contains check) to handle prefix differences
     * (e.g., "X-ABC" vs "ABC"), matching SuperGattCallback.dowithglucose behavior.
     */
    fun isMainSensor(serial: String): Boolean {
        synchronized(lock) {
            val main = _mainSensorSerial ?: return true // No main set → treat all as main (safety)
            if (main.isEmpty()) return true
            return main == serial ||
                    serial.contains(main) ||
                    main.contains(serial)
        }
    }

    // =========================================================================
    // Glucose Reception & Cross-Sensor Health Checks
    // =========================================================================

    /**
     * Called when a sensor delivers a glucose reading.
     *
     * This is the central coordination point:
     *  1. Updates the sensor's last-glucose timestamp
     *  2. Determines if this is the main sensor
     *  3. Notifies the listener with main-sensor flag
     *  4. Triggers cross-sensor health checks (othersworking pattern)
     *
     * @param serial The sensor that produced the reading
     * @param reading The glucose reading
     */
    fun onGlucoseReceived(serial: String, reading: GlucoseReading) {
        val isMain: Boolean
        val staleSerials: List<String>

        synchronized(lock) {
            val managed = sensors[serial] ?: return
            val now = clock()

            // Update success timestamp
            managed.lastGlucoseTimeMs = now

            // Main sensor check
            isMain = isMainSensor(serial)

            // Cross-sensor health check: find stale sensors that need reconnect
            staleSerials = checkOtherSensorsHealth(serial, now)
        }

        // Deliver reading (outside lock to avoid deadlock with callbacks)
        onGlucoseReading?.invoke(reading, isMain)

        // Trigger reconnects for stale sensors (outside lock)
        for (staleSerial in staleSerials) {
            onShouldReconnect?.invoke(staleSerial)
        }
    }

    /**
     * Record a glucose failure for a sensor (charcha[1] equivalent).
     */
    fun onGlucoseFailed(serial: String) {
        synchronized(lock) {
            val managed = sensors[serial] ?: return
            managed.lastFailureTimeMs = clock()
        }
    }

    /**
     * Cross-sensor health check: when one sensor receives data, check all others.
     *
     * Mirrors SensorBluetooth.othersworking() + SuperGattCallback.shouldreconnect():
     *  - Skip the sensor that just reported
     *  - For each other sensor, check if it's stale:
     *    1. Created long enough ago (starttime < old)
     *    2. Last glucose too old (charcha[0] < old)
     *    3. Not recently attempted connect (debounce)
     *    4. Not paused
     *
     * @return list of serials that should be force-reconnected
     */
    private fun checkOtherSensorsHealth(reportingSerial: String, nowMs: Long): List<String> {
        if (sensors.size <= 1) return emptyList()

        val staleThreshold = nowMs - reconnectTimeoutMs + 20 // +20ms matches Android
        val stale = mutableListOf<String>()

        for ((serial, managed) in sensors) {
            if (serial == reportingSerial) continue
            if (managed.isPaused) continue

            // Three conditions from shouldreconnect():
            val createdLongEnoughAgo = managed.createdAtMs < staleThreshold
            val glucoseStale = managed.lastGlucoseTimeMs < staleThreshold
            val notRecentlyConnected = managed.lastConnectTimeMs < (nowMs - reconnectDebounceMs)

            if (createdLongEnoughAgo && glucoseStale && notRecentlyConnected) {
                stale.add(serial)
            }
        }
        return stale
    }

    // =========================================================================
    // HealthConnect Write Deduplication
    // =========================================================================

    /**
     * Check if a sensor is allowed to write to HealthConnect.
     *
     * First-writer-wins: when one sensor writes, all others are blocked.
     * This prevents duplicate glucose entries in HealthConnect when multiple
     * sensors are connected.
     *
     * @param serial The sensor requesting to write
     * @return true if this sensor should write to HealthConnect
     */
    fun shouldWriteHealth(serial: String): Boolean {
        synchronized(lock) {
            val managed = sensors[serial] ?: return false

            // If we're already blocked, deny
            if (managed.healthWriteBlocked) return false

            // Block all other sensors
            for ((otherSerial, otherManaged) in sensors) {
                if (otherSerial != serial) {
                    otherManaged.healthWriteBlocked = true
                }
            }
            return true
        }
    }

    /**
     * Reset HealthConnect dedup flags. Called when the current health writer
     * is removed or disconnects, allowing another sensor to take over.
     */
    fun resetHealthWriteFlags() {
        synchronized(lock) {
            for (managed in sensors.values) {
                managed.healthWriteBlocked = false
            }
        }
    }

    // =========================================================================
    // Scan Dispatch
    // =========================================================================

    /**
     * Find the managed sensor that matches a scanned device name.
     *
     * Two-pass matching (mirrors SensorBluetooth.getCallback):
     *  1. Exact address match (fastest, most reliable)
     *  2. Device name contains sensor serial (or vice versa)
     *
     * @param deviceName The advertised device name (may be null)
     * @param address The BLE MAC address
     * @return the serial of the matching sensor, or null if no match
     */
    fun matchScanResult(deviceName: String?, address: String?): String? {
        synchronized(lock) {
            // Pass 1: Address match
            if (address != null) {
                for ((serial, managed) in sensors) {
                    if (managed.address != null && managed.address.equals(address, ignoreCase = true)) {
                        return serial
                    }
                }
            }

            // Pass 2: Device name match
            if (deviceName != null) {
                for ((serial, managed) in sensors) {
                    if (matchDeviceName(deviceName, serial)) {
                        // Cache the address for faster future lookups
                        if (address != null && managed.address == null) {
                            managed.address = address
                            persistSensorList()
                        }
                        return serial
                    }
                }
            }

            return null
        }
    }

    /**
     * Check if a device name matches a sensor serial.
     *
     * AiDex sensors advertise device names containing the serial number.
     * Handles both bare serials and "X-" prefixed serials.
     */
    private fun matchDeviceName(deviceName: String, serial: String): Boolean {
        val bareSerial = if (serial.startsWith("X-")) serial.substring(2) else serial
        return deviceName.contains(serial, ignoreCase = true) ||
                deviceName.contains(bareSerial, ignoreCase = true)
    }

    /**
     * Check if all registered sensors have been found (have addresses).
     * Used to determine when scanning can stop.
     */
    fun allSensorsFound(): Boolean {
        synchronized(lock) {
            return sensors.values.all { it.address != null }
        }
    }

    // =========================================================================
    // Connect Tracking
    // =========================================================================

    /**
     * Record that a connect attempt was initiated for a sensor.
     * Updates the debounce timestamp for cross-sensor health checks.
     */
    fun onConnectAttempt(serial: String) {
        synchronized(lock) {
            sensors[serial]?.lastConnectTimeMs = clock()
        }
    }

    // =========================================================================
    // BLE Manager Binding
    // =========================================================================

    /**
     * Attach a BLE manager to a managed sensor.
     *
     * In Phase 4 integration, SensorBluetooth creates AiDexBleManager instances
     * and binds them here so the manager can coordinate lifecycle.
     */
    fun bindBleManager(serial: String, bleManager: SensorBleController) {
        synchronized(lock) {
            val managed = sensors[serial] ?: return
            managed.bleManager = bleManager
        }
    }

    /**
     * Get the BLE manager for a sensor (if bound).
     */
    fun getBleManager(serial: String): SensorBleController? {
        synchronized(lock) {
            return sensors[serial]?.bleManager
        }
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /** Number of registered sensors. */
    val sensorCount: Int
        get() = synchronized(lock) { sensors.size }

    /** List of all registered sensor serials, in insertion order. */
    fun getSensorSerials(): List<String> {
        synchronized(lock) {
            return sensors.keys.toList()
        }
    }

    /** Check if a sensor is registered. */
    fun hasSensor(serial: String): Boolean {
        synchronized(lock) {
            return sensors.containsKey(serial)
        }
    }

    /** Get the BLE address for a sensor. */
    fun getAddress(serial: String): String? {
        synchronized(lock) {
            return sensors[serial]?.address
        }
    }

    /** Update the BLE address for a sensor (e.g., after scan discovery). */
    fun updateAddress(serial: String, address: String) {
        synchronized(lock) {
            val managed = sensors[serial] ?: return
            if (managed.address != address) {
                managed.address = address
                persistSensorList()
            }
        }
    }

    /**
     * Get a snapshot of all sensor states for UI display.
     */
    fun getSensorStates(): List<SensorState> {
        synchronized(lock) {
            return sensors.values.map { managed ->
                SensorState(
                    serial = managed.serial,
                    address = managed.address,
                    isMain = isMainSensor(managed.serial),
                    lastGlucoseTimeMs = managed.lastGlucoseTimeMs,
                    lastConnectTimeMs = managed.lastConnectTimeMs,
                    healthWriteBlocked = managed.healthWriteBlocked,
                )
            }
        }
    }

    // =========================================================================
    // Reconciliation
    // =========================================================================

    /**
     * Reconcile the sensor list with an authoritative source.
     *
     * Equivalent to SensorBluetooth.updateDevicers(): merges the given list
     * with the current registry, adding new sensors and removing stale ones.
     *
     * @param authoritativeSerials The complete list of serials that should be active
     * @return pair of (added count, removed serials)
     */
    fun reconcile(authoritativeSerials: Set<String>): Pair<Int, List<String>> {
        synchronized(lock) {
            // Find sensors to remove (in registry but not in authoritative list)
            val toRemove = sensors.keys.filter { it !in authoritativeSerials }

            // Find sensors to add (in authoritative list but not in registry)
            val toAdd = authoritativeSerials.filter { it !in sensors && it.isNotBlank() }

            // Remove stale sensors
            val removedSerials = mutableListOf<String>()
            for (serial in toRemove) {
                val result = removeSensor(serial)
                if (result.removed) removedSerials.add(serial)
            }

            // Add new sensors
            var addedCount = 0
            for (serial in toAdd) {
                sensors[serial] = ManagedSensor(serial = serial, createdAt = clock())
                addedCount++
            }

            if (addedCount > 0) {
                persistSensorList()
                onSensorListChanged?.invoke()
            }

            return Pair(addedCount, removedSerials)
        }
    }

    // =========================================================================
    // Persistence Helpers
    // =========================================================================

    /**
     * Write current sensor list to persistence.
     * Format: each entry is "serial|address" (address may be empty).
     */
    private fun persistSensorList() {
        val entries = sensors.values.map { managed ->
            val addr = managed.address ?: ""
            "${managed.serial}|$addr"
        }.toSet()
        persistence.saveSensorEntries(entries)
    }
}
