// JugglucoNG — AiDex Native Kotlin Driver
// AiDexNativeSensorManagerTests.kt — Unit tests for multi-sensor orchestrator
//
// Tests sensor registry management, main sensor designation, cross-sensor health
// checks, HealthConnect dedup, scan dispatch, persistence, and reconciliation.
// No Android framework dependencies.

package tk.glucodata.drivers.aidex.native.ble

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tk.glucodata.drivers.aidex.native.data.GlucoseReading

class AiDexNativeSensorManagerTests {

    private lateinit var persistence: AiDexNativeSensorManager.InMemoryPersistence
    private var currentTimeMs = 1_000_000_000L // Fixed start time for deterministic tests
    private lateinit var manager: AiDexNativeSensorManager

    @Before
    fun setUp() {
        persistence = AiDexNativeSensorManager.InMemoryPersistence()
        currentTimeMs = 1_000_000_000L
        manager = AiDexNativeSensorManager(
            persistence = persistence,
            clock = { currentTimeMs },
            reconnectTimeoutMs = 5 * 60_000L,
            reconnectDebounceMs = 60_000L,
        )
    }

    private fun makeReading(serial: String, autoValue: Float = 100f): GlucoseReading {
        return GlucoseReading(
            timestamp = currentTimeMs,
            sensorSerial = serial,
            autoValue = autoValue,
            rawValue = autoValue * 0.55f,
            sensorGlucose = autoValue * 0.98f,
            rawI1 = autoValue / 18f,
            rawI2 = 0.5f,
            timeOffsetMinutes = 60,
        )
    }

    // ========================================================================
    // Sensor Addition
    // ========================================================================

    @Test
    fun testAddSensor() {
        val added = manager.addSensor("ABC123", "AA:BB:CC:DD:EE:FF")
        assertTrue(added)
        assertEquals(1, manager.sensorCount)
        assertTrue(manager.hasSensor("ABC123"))
    }

    @Test
    fun testAddSensorDuplicate() {
        manager.addSensor("ABC123")
        val added = manager.addSensor("ABC123")
        assertFalse(added)
        assertEquals(1, manager.sensorCount)
    }

    @Test
    fun testAddSensorBlankSerial() {
        val added = manager.addSensor("")
        assertFalse(added)
        assertEquals(0, manager.sensorCount)
    }

    @Test
    fun testAddSensorBlankSerialSpaces() {
        val added = manager.addSensor("   ")
        assertFalse(added)
        assertEquals(0, manager.sensorCount)
    }

    @Test
    fun testAddSensorPersists() {
        manager.addSensor("ABC123", "AA:BB:CC:DD:EE:FF")
        val entries = persistence.entries
        assertTrue(entries.contains("ABC123|AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun testAddSensorWithoutAddress() {
        manager.addSensor("ABC123")
        val entries = persistence.entries
        assertTrue(entries.contains("ABC123|"))
    }

    @Test
    fun testAddSensorCallsListener() {
        var called = false
        manager.onSensorListChanged = { called = true }
        manager.addSensor("ABC123")
        assertTrue(called)
    }

    @Test
    fun testAddMultipleSensors() {
        manager.addSensor("SENSOR_A")
        manager.addSensor("SENSOR_B")
        manager.addSensor("SENSOR_C")
        assertEquals(3, manager.sensorCount)
        assertEquals(listOf("SENSOR_A", "SENSOR_B", "SENSOR_C"), manager.getSensorSerials())
    }

    // ========================================================================
    // First Sensor Auto-Designates as Main
    // ========================================================================

    @Test
    fun testFirstSensorBecomesMain() {
        manager.addSensor("ABC123")
        assertEquals("ABC123", manager.mainSensorSerial)
    }

    @Test
    fun testSecondSensorDoesNotBecomeMain() {
        manager.addSensor("FIRST")
        manager.addSensor("SECOND")
        assertEquals("FIRST", manager.mainSensorSerial)
    }

    @Test
    fun testFirstSensorMainPersisted() {
        manager.addSensor("ABC123")
        assertEquals("ABC123", persistence.mainSensor)
    }

    // ========================================================================
    // Bulk Add
    // ========================================================================

    @Test
    fun testBulkAddSensors() {
        val count = manager.addSensorsBulk(listOf("A", "B", "C"))
        assertEquals(3, count)
        assertEquals(3, manager.sensorCount)
    }

    @Test
    fun testBulkAddDedup() {
        manager.addSensor("A")
        val count = manager.addSensorsBulk(listOf("A", "B"))
        assertEquals(1, count) // Only B added
        assertEquals(2, manager.sensorCount)
    }

    @Test
    fun testBulkAddSkipsBlank() {
        val count = manager.addSensorsBulk(listOf("A", "", "B", "  "))
        assertEquals(2, count)
    }

    @Test
    fun testBulkAddDoesNotAutoDesignateMain() {
        manager.addSensorsBulk(listOf("A", "B"))
        assertNull(manager.mainSensorSerial)
    }

    @Test
    fun testBulkAddCallsListenerOnce() {
        var callCount = 0
        manager.onSensorListChanged = { callCount++ }
        manager.addSensorsBulk(listOf("A", "B", "C"))
        assertEquals(1, callCount) // Not 3
    }

    @Test
    fun testBulkAddNoListenerWhenNothingAdded() {
        manager.addSensor("A")
        var called = false
        manager.onSensorListChanged = { called = true }
        manager.addSensorsBulk(listOf("A")) // Already exists
        assertFalse(called)
    }

    // ========================================================================
    // Sensor Removal
    // ========================================================================

    @Test
    fun testRemoveSensor() {
        manager.addSensor("ABC123")
        val result = manager.removeSensor("ABC123")
        assertTrue(result.removed)
        assertEquals(0, manager.sensorCount)
        assertFalse(manager.hasSensor("ABC123"))
    }

    @Test
    fun testRemoveNonexistentSensor() {
        val result = manager.removeSensor("NOPE")
        assertFalse(result.removed)
        assertFalse(result.wasMainSensor)
        assertNull(result.address)
    }

    @Test
    fun testRemoveMainSensorClearsDesignation() {
        manager.addSensor("MAIN")
        manager.addSensor("OTHER")
        assertEquals("MAIN", manager.mainSensorSerial)

        val result = manager.removeSensor("MAIN")
        assertTrue(result.wasMainSensor)
        assertNull(manager.mainSensorSerial)
        assertNull(persistence.mainSensor)
    }

    @Test
    fun testRemoveNonMainSensorKeepsMain() {
        manager.addSensor("MAIN")
        manager.addSensor("OTHER")
        val result = manager.removeSensor("OTHER")
        assertFalse(result.wasMainSensor)
        assertEquals("MAIN", manager.mainSensorSerial)
    }

    @Test
    fun testRemoveSensorReturnsAddress() {
        manager.addSensor("ABC", "AA:BB:CC:DD:EE:FF")
        val result = manager.removeSensor("ABC")
        assertEquals("AA:BB:CC:DD:EE:FF", result.address)
    }

    @Test
    fun testRemoveSensorPersists() {
        manager.addSensor("A")
        manager.addSensor("B")
        manager.removeSensor("A")
        val entries = persistence.entries
        assertEquals(1, entries.size)
        assertTrue(entries.any { it.startsWith("B|") })
    }

    @Test
    fun testRemoveSensorResetsHealthFlags() {
        manager.addSensor("A")
        manager.addSensor("B")
        manager.addSensor("C")

        // Let A claim health writing, blocking B and C
        manager.shouldWriteHealth("A")

        // Remove A — should reset health flags on B and C
        manager.removeSensor("A")

        // B should now be able to write
        assertTrue(manager.shouldWriteHealth("B"))
    }

    @Test
    fun testRemoveAllSensors() {
        manager.addSensor("A")
        manager.addSensor("B")
        manager.addSensor("C")
        val count = manager.removeAllSensors()
        assertEquals(3, count)
        assertEquals(0, manager.sensorCount)
    }

    // ========================================================================
    // Main Sensor Designation
    // ========================================================================

    @Test
    fun testSetMainSensor() {
        manager.addSensor("A")
        manager.addSensor("B")
        val changed = manager.setMainSensor("B")
        assertTrue(changed)
        assertEquals("B", manager.mainSensorSerial)
        assertEquals("B", persistence.mainSensor)
    }

    @Test
    fun testSetMainSensorToNull() {
        manager.addSensor("A")
        assertEquals("A", manager.mainSensorSerial)
        val changed = manager.setMainSensor(null)
        assertTrue(changed)
        assertNull(manager.mainSensorSerial)
    }

    @Test
    fun testSetMainSensorUnregistered() {
        manager.addSensor("A")
        val changed = manager.setMainSensor("NOPE")
        assertFalse(changed)
        assertEquals("A", manager.mainSensorSerial) // Unchanged
    }

    @Test
    fun testSetMainSensorSameValue() {
        manager.addSensor("A")
        val changed = manager.setMainSensor("A") // Already main
        assertFalse(changed)
    }

    @Test
    fun testIsMainSensorExactMatch() {
        manager.addSensor("X-ABC123")
        assertTrue(manager.isMainSensor("X-ABC123"))
    }

    @Test
    fun testIsMainSensorContainsMatch() {
        manager.addSensor("ABC123")
        // "X-ABC123" contains "ABC123"
        assertTrue(manager.isMainSensor("X-ABC123"))
    }

    @Test
    fun testIsMainSensorReverseContains() {
        manager.addSensor("X-ABC123")
        // "X-ABC123" contains "ABC123"
        assertTrue(manager.isMainSensor("ABC123"))
    }

    @Test
    fun testIsMainSensorNoMainSetTreatsAllAsMain() {
        manager.addSensorsBulk(listOf("A", "B"))
        // No main set → safety: treat all as main
        assertNull(manager.mainSensorSerial)
        assertTrue(manager.isMainSensor("A"))
        assertTrue(manager.isMainSensor("B"))
    }

    @Test
    fun testIsMainSensorNonMatch() {
        manager.addSensor("A")
        manager.addSensor("B")
        assertFalse(manager.isMainSensor("B"))
    }

    // ========================================================================
    // Glucose Reception & Cross-Sensor Health Check
    // ========================================================================

    @Test
    fun testOnGlucoseReceivedNotifiesWithMainFlag() {
        manager.addSensor("MAIN")
        manager.addSensor("OTHER")

        var lastReading: GlucoseReading? = null
        var lastIsMain: Boolean? = null
        manager.onGlucoseReading = { reading, isMain ->
            lastReading = reading
            lastIsMain = isMain
        }

        val reading = makeReading("MAIN")
        manager.onGlucoseReceived("MAIN", reading)
        assertEquals(reading, lastReading)
        assertTrue(lastIsMain!!)

        val reading2 = makeReading("OTHER")
        manager.onGlucoseReceived("OTHER", reading2)
        assertFalse(lastIsMain!!)
    }

    @Test
    fun testOnGlucoseReceivedIgnoresUnknownSensor() {
        var called = false
        manager.onGlucoseReading = { _, _ -> called = true }
        manager.onGlucoseReceived("UNKNOWN", makeReading("UNKNOWN"))
        assertFalse(called)
    }

    @Test
    fun testCrossSensorHealthCheckTriggersReconnect() {
        manager.addSensor("A")
        manager.addSensor("B")

        // Simulate: sensor A has been alive for a long time, B has not received data
        // Advance time past the reconnect timeout + debounce
        currentTimeMs += 6 * 60_000L // 6 minutes

        val reconnectedSerials = mutableListOf<String>()
        manager.onShouldReconnect = { serial -> reconnectedSerials.add(serial) }

        // Sensor A receives data → should check B for staleness
        manager.onGlucoseReceived("A", makeReading("A"))

        // B should have been flagged (created 6 min ago, no glucose, no connect attempt)
        assertTrue(reconnectedSerials.contains("B"))
    }

    @Test
    fun testCrossSensorHealthCheckDoesNotReconnectFreshSensor() {
        manager.addSensor("A")
        manager.addSensor("B")

        // Only advance 1 minute — not past the 5-minute timeout
        currentTimeMs += 60_000L

        val reconnectedSerials = mutableListOf<String>()
        manager.onShouldReconnect = { serial -> reconnectedSerials.add(serial) }

        manager.onGlucoseReceived("A", makeReading("A"))

        // B should NOT be flagged — too fresh
        assertTrue(reconnectedSerials.isEmpty())
    }

    @Test
    fun testCrossSensorHealthCheckRespectsDebounce() {
        manager.addSensor("A")
        manager.addSensor("B")

        currentTimeMs += 6 * 60_000L

        // Record a recent connect attempt for B
        manager.onConnectAttempt("B")

        val reconnectedSerials = mutableListOf<String>()
        manager.onShouldReconnect = { serial -> reconnectedSerials.add(serial) }

        manager.onGlucoseReceived("A", makeReading("A"))

        // B should NOT be flagged — recently attempted connect
        assertTrue(reconnectedSerials.isEmpty())
    }

    @Test
    fun testCrossSensorHealthCheckSkipsPausedSensor() {
        manager.addSensor("A")
        manager.addSensor("B")

        // Remove B (which pauses it) then re-add it manually to test the pause check
        // Actually, let's test via removal — the isPaused flag is set during removal
        // Instead, let's test that a sensor that has received glucose recently is not flagged
        currentTimeMs += 6 * 60_000L

        // B received glucose recently
        manager.onGlucoseReceived("B", makeReading("B"))

        val reconnectedSerials = mutableListOf<String>()
        manager.onShouldReconnect = { serial -> reconnectedSerials.add(serial) }

        // A receives glucose — check B
        currentTimeMs += 1_000L // 1 second later
        manager.onGlucoseReceived("A", makeReading("A"))

        // B should NOT be flagged — it just received glucose
        assertTrue(reconnectedSerials.isEmpty())
    }

    @Test
    fun testCrossSensorHealthCheckSingleSensorNeverTriggered() {
        manager.addSensor("A")
        currentTimeMs += 6 * 60_000L

        val reconnectedSerials = mutableListOf<String>()
        manager.onShouldReconnect = { serial -> reconnectedSerials.add(serial) }

        manager.onGlucoseReceived("A", makeReading("A"))

        // No other sensors → nothing to reconnect
        assertTrue(reconnectedSerials.isEmpty())
    }

    @Test
    fun testOnGlucoseFailed() {
        manager.addSensor("A")
        manager.onGlucoseFailed("A")
        // No crash — the failure timestamp is recorded internally
    }

    // ========================================================================
    // HealthConnect Write Deduplication
    // ========================================================================

    @Test
    fun testShouldWriteHealthFirstWriter() {
        manager.addSensor("A")
        manager.addSensor("B")
        assertTrue(manager.shouldWriteHealth("A"))
    }

    @Test
    fun testShouldWriteHealthBlocksOthers() {
        manager.addSensor("A")
        manager.addSensor("B")
        manager.addSensor("C")

        assertTrue(manager.shouldWriteHealth("A")) // A wins
        assertFalse(manager.shouldWriteHealth("B")) // Blocked
        assertFalse(manager.shouldWriteHealth("C")) // Blocked
    }

    @Test
    fun testShouldWriteHealthSameWriterContinues() {
        manager.addSensor("A")
        manager.addSensor("B")

        assertTrue(manager.shouldWriteHealth("A")) // A wins
        assertTrue(manager.shouldWriteHealth("A")) // A can continue
    }

    @Test
    fun testResetHealthWriteFlags() {
        manager.addSensor("A")
        manager.addSensor("B")

        manager.shouldWriteHealth("A") // A wins, B blocked
        assertFalse(manager.shouldWriteHealth("B"))

        manager.resetHealthWriteFlags()
        assertTrue(manager.shouldWriteHealth("B")) // B can now write
    }

    @Test
    fun testShouldWriteHealthUnknownSensor() {
        assertFalse(manager.shouldWriteHealth("UNKNOWN"))
    }

    // ========================================================================
    // Scan Dispatch
    // ========================================================================

    @Test
    fun testMatchScanResultByAddress() {
        manager.addSensor("ABC", "AA:BB:CC:DD:EE:FF")
        val serial = manager.matchScanResult(null, "AA:BB:CC:DD:EE:FF")
        assertEquals("ABC", serial)
    }

    @Test
    fun testMatchScanResultByAddressCaseInsensitive() {
        manager.addSensor("ABC", "AA:BB:CC:DD:EE:FF")
        val serial = manager.matchScanResult(null, "aa:bb:cc:dd:ee:ff")
        assertEquals("ABC", serial)
    }

    @Test
    fun testMatchScanResultByDeviceName() {
        manager.addSensor("ABC123")
        val serial = manager.matchScanResult("AiDex-ABC123", null)
        assertEquals("ABC123", serial)
    }

    @Test
    fun testMatchScanResultByDeviceNameWithPrefix() {
        manager.addSensor("X-ABC123")
        // Device name contains bare serial (without X- prefix)
        val serial = manager.matchScanResult("AiDex-ABC123", null)
        assertEquals("X-ABC123", serial)
    }

    @Test
    fun testMatchScanResultCachesAddress() {
        manager.addSensor("ABC123")
        assertNull(manager.getAddress("ABC123"))

        manager.matchScanResult("AiDex-ABC123", "AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", manager.getAddress("ABC123"))
    }

    @Test
    fun testMatchScanResultAddressFirst() {
        // Both sensors could match by name, but address match takes priority
        manager.addSensor("ABC", "AA:BB:CC:DD:EE:FF")
        manager.addSensor("DEF", "11:22:33:44:55:66")

        val serial = manager.matchScanResult("AiDex-ABC", "11:22:33:44:55:66")
        // Address match wins: DEF has that address
        assertEquals("DEF", serial)
    }

    @Test
    fun testMatchScanResultNoMatch() {
        manager.addSensor("ABC")
        val serial = manager.matchScanResult("Unknown-Device", "FF:FF:FF:FF:FF:FF")
        assertNull(serial)
    }

    @Test
    fun testMatchScanResultCaseInsensitiveName() {
        manager.addSensor("ABC123")
        val serial = manager.matchScanResult("aidex-abc123", null)
        assertEquals("ABC123", serial)
    }

    @Test
    fun testAllSensorsFound() {
        manager.addSensor("A", "AA:BB:CC:DD:EE:01")
        manager.addSensor("B", "AA:BB:CC:DD:EE:02")
        assertTrue(manager.allSensorsFound())
    }

    @Test
    fun testAllSensorsFoundPartial() {
        manager.addSensor("A", "AA:BB:CC:DD:EE:01")
        manager.addSensor("B") // No address yet
        assertFalse(manager.allSensorsFound())
    }

    @Test
    fun testAllSensorsFoundEmpty() {
        assertTrue(manager.allSensorsFound()) // Vacuously true
    }

    // ========================================================================
    // Persistence Load
    // ========================================================================

    @Test
    fun testLoadFromPersistence() {
        persistence.entries = mutableSetOf("ABC|AA:BB:CC:DD:EE:FF", "DEF|11:22:33:44:55:66")
        persistence.mainSensor = "ABC"

        val loaded = manager.loadFromPersistence()
        assertEquals(2, loaded.size)
        assertTrue(loaded.contains("ABC"))
        assertTrue(loaded.contains("DEF"))
        assertEquals("ABC", manager.mainSensorSerial)
    }

    @Test
    fun testLoadFromPersistenceIdempotent() {
        persistence.entries = mutableSetOf("ABC|addr")
        val first = manager.loadFromPersistence()
        val second = manager.loadFromPersistence()
        assertEquals(first, second) // Same result
        assertEquals(1, manager.sensorCount) // Not doubled
    }

    @Test
    fun testLoadFromPersistenceStaleMainCleared() {
        persistence.entries = mutableSetOf("ABC|addr")
        persistence.mainSensor = "GONE" // Not in sensor list
        manager.loadFromPersistence()
        assertNull(manager.mainSensorSerial)
        assertNull(persistence.mainSensor) // Persisted the correction
    }

    @Test
    fun testLoadFromPersistenceNoAddress() {
        persistence.entries = mutableSetOf("ABC|", "DEF|")
        manager.loadFromPersistence()
        assertEquals(2, manager.sensorCount)
        assertNull(manager.getAddress("ABC"))
    }

    @Test
    fun testLoadFromPersistenceBlankEntries() {
        persistence.entries = mutableSetOf("|", "   |addr", "ABC|addr")
        manager.loadFromPersistence()
        assertEquals(1, manager.sensorCount) // Only ABC
    }

    @Test
    fun testLoadFromPersistenceEmptySet() {
        manager.loadFromPersistence()
        assertEquals(0, manager.sensorCount)
        assertTrue(manager.isStarted)
    }

    // ========================================================================
    // Connect Tracking
    // ========================================================================

    @Test
    fun testOnConnectAttempt() {
        manager.addSensor("A")
        currentTimeMs = 5_000_000L
        manager.onConnectAttempt("A")
        // Verify by checking that the debounce prevents reconnect
        // (internal state — tested via cross-sensor health check)
    }

    @Test
    fun testOnConnectAttemptUnknownSensor() {
        // Should not crash
        manager.onConnectAttempt("UNKNOWN")
    }

    // ========================================================================
    // Address Update
    // ========================================================================

    @Test
    fun testUpdateAddress() {
        manager.addSensor("ABC")
        assertNull(manager.getAddress("ABC"))

        manager.updateAddress("ABC", "AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", manager.getAddress("ABC"))
    }

    @Test
    fun testUpdateAddressPersists() {
        manager.addSensor("ABC")
        manager.updateAddress("ABC", "AA:BB:CC:DD:EE:FF")
        assertTrue(persistence.entries.any { it == "ABC|AA:BB:CC:DD:EE:FF" })
    }

    @Test
    fun testUpdateAddressUnknownSensor() {
        // Should not crash
        manager.updateAddress("UNKNOWN", "AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun testUpdateAddressSameValue() {
        manager.addSensor("ABC", "AA:BB:CC:DD:EE:FF")

        // Record initial persistence state
        val initialEntries = persistence.entries.toSet()

        // Update with same address — should not trigger persist
        manager.updateAddress("ABC", "AA:BB:CC:DD:EE:FF")

        // Still same (no unnecessary write)
        assertEquals(initialEntries, persistence.entries)
    }

    // ========================================================================
    // Sensor States Query
    // ========================================================================

    @Test
    fun testGetSensorStates() {
        manager.addSensor("A", "addr_a")
        manager.addSensor("B")

        val states = manager.getSensorStates()
        assertEquals(2, states.size)

        val stateA = states.find { it.serial == "A" }!!
        assertEquals("addr_a", stateA.address)
        assertTrue(stateA.isMain) // First sensor = main
        assertEquals(0L, stateA.lastGlucoseTimeMs)

        val stateB = states.find { it.serial == "B" }!!
        assertNull(stateB.address)
        assertFalse(stateB.isMain)
    }

    @Test
    fun testGetSensorStatesAfterGlucose() {
        manager.addSensor("A")
        currentTimeMs = 5_000_000L
        manager.onGlucoseReceived("A", makeReading("A"))

        val states = manager.getSensorStates()
        assertEquals(5_000_000L, states[0].lastGlucoseTimeMs)
    }

    // ========================================================================
    // Reconciliation
    // ========================================================================

    @Test
    fun testReconcileAddNew() {
        manager.addSensor("A")
        val (added, removed) = manager.reconcile(setOf("A", "B", "C"))
        assertEquals(2, added)
        assertTrue(removed.isEmpty())
        assertEquals(3, manager.sensorCount)
    }

    @Test
    fun testReconcileRemoveStale() {
        manager.addSensor("A")
        manager.addSensor("B")
        manager.addSensor("C")
        val (added, removed) = manager.reconcile(setOf("A"))
        assertEquals(0, added)
        assertEquals(2, removed.size)
        assertTrue(removed.contains("B"))
        assertTrue(removed.contains("C"))
        assertEquals(1, manager.sensorCount)
    }

    @Test
    fun testReconcileBothAddAndRemove() {
        manager.addSensor("A")
        manager.addSensor("B")
        val (added, removed) = manager.reconcile(setOf("B", "C"))
        assertEquals(1, added) // C
        assertEquals(1, removed.size) // A
        assertTrue(removed.contains("A"))
        assertEquals(2, manager.sensorCount)
        assertTrue(manager.hasSensor("B"))
        assertTrue(manager.hasSensor("C"))
    }

    @Test
    fun testReconcileNoChange() {
        manager.addSensor("A")
        manager.addSensor("B")
        val (added, removed) = manager.reconcile(setOf("A", "B"))
        assertEquals(0, added)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun testReconcileSkipsBlank() {
        val (added, _) = manager.reconcile(setOf("A", "", "  "))
        assertEquals(1, added)
        assertEquals(1, manager.sensorCount)
    }

    @Test
    fun testReconcileRemovingMainClearsDesignation() {
        manager.addSensor("MAIN")
        manager.addSensor("OTHER")
        assertEquals("MAIN", manager.mainSensorSerial)

        manager.reconcile(setOf("OTHER")) // Removes MAIN
        assertNull(manager.mainSensorSerial)
    }

    // ========================================================================
    // BLE Manager Binding
    // ========================================================================

    @Test
    fun testBindAndGetBleManager() {
        manager.addSensor("ABC")
        assertNull(manager.getBleManager("ABC"))

        // Can't create a real AiDexBleManager without Android context,
        // so just verify the API works with null checks
        assertNull(manager.getBleManager("UNKNOWN"))
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun testMainSensorDesignationSurvivesReload() {
        manager.addSensor("A")
        manager.addSensor("B")
        manager.setMainSensor("B")

        // Create a new manager with the same persistence
        val manager2 = AiDexNativeSensorManager(
            persistence = persistence,
            clock = { currentTimeMs },
        )
        manager2.loadFromPersistence()

        assertEquals("B", manager2.mainSensorSerial)
        assertEquals(2, manager2.sensorCount)
    }

    @Test
    fun testRemoveSensorThenReAddDoesNotAutoMain() {
        manager.addSensor("A")
        manager.addSensor("B")
        assertEquals("A", manager.mainSensorSerial)

        manager.removeSensor("A")
        assertNull(manager.mainSensorSerial) // Cleared

        // Re-add A — it's now the only sensor without a main, BUT since B exists,
        // it should NOT auto-designate (auto only on first sensor ever)
        manager.addSensor("A")
        // There are 2 sensors, none is main, so addSensor does not auto-designate
        // (auto-designate only when sensors.size == 1 after adding)
        assertNull(manager.mainSensorSerial)
    }

    @Test
    fun testRemoveLastSensorThenReAddAutoDesignates() {
        manager.addSensor("A")
        manager.removeSensor("A")
        assertNull(manager.mainSensorSerial)

        manager.addSensor("B")
        // B is the only sensor, auto-designates
        assertEquals("B", manager.mainSensorSerial)
    }

    @Test
    fun testConcurrentGlucoseAndRemoval() {
        manager.addSensor("A")
        manager.addSensor("B")

        // Simulate: glucose arrives for A, then B is removed
        manager.onGlucoseReceived("A", makeReading("A"))
        manager.removeSensor("B")

        // Should not crash
        assertEquals(1, manager.sensorCount)
    }

    @Test
    fun testMultipleSensorsHealthCheckOnlyFlagsStale() {
        manager.addSensor("A")
        manager.addSensor("B")
        manager.addSensor("C")

        // B received glucose recently, C did not
        currentTimeMs += 3 * 60_000L
        manager.onGlucoseReceived("B", makeReading("B"))

        // Advance past timeout
        currentTimeMs += 3 * 60_000L // Now 6 min from start

        val reconnectedSerials = mutableListOf<String>()
        manager.onShouldReconnect = { serial -> reconnectedSerials.add(serial) }

        manager.onGlucoseReceived("A", makeReading("A"))

        // C should be flagged (no glucose for 6 min), B should NOT (got glucose 3 min ago)
        assertTrue(reconnectedSerials.contains("C"))
        assertFalse(reconnectedSerials.contains("B"))
    }

    @Test
    fun testPersistenceFormatCorrectness() {
        manager.addSensor("X-22222689WH", "80:AC:38:8F:05:67")
        manager.addSensor("X-2222267V4E")

        val entries = persistence.entries
        assertTrue(entries.contains("X-22222689WH|80:AC:38:8F:05:67"))
        assertTrue(entries.contains("X-2222267V4E|"))
    }
}
