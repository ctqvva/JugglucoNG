package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.drivers.icanhealth.ICanHealthConstants

class SensorIdentityTests {
    @Before
    fun setUp() {
        ManagedCurrentSensor.clear()
    }

    @After
    fun tearDown() {
        ManagedCurrentSensor.clear()
    }

    @Test
    fun resolveAvailableMainSensor_prefersSelectedMainWhenStillActive() {
        assertEquals(
            "current-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = "current-sensor",
                preferredSensorId = "replacement-sensor",
                activeSensors = arrayOf("current-sensor", "replacement-sensor", "other-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_prefersPreferredWhenSelectedMainIsBlank() {
        assertEquals(
            "replacement-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "replacement-sensor",
                activeSensors = arrayOf("replacement-sensor", "other-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_fallsBackToFirstActiveWhenCachedSensorIsGone() {
        assertEquals(
            "replacement-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "stale-sensor",
                activeSensors = arrayOf("replacement-sensor", "other-sensor", "third-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_keepsManagedWhenStillActive() {
        ManagedCurrentSensor.set("X-2222268RXN")

        assertEquals(
            "X-2222268RXN",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "F0FD4509C7C2",
                activeSensors = arrayOf("F0FD4509C7C2", "X-2222268RXN")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_ignoresStaleManagedWhenActiveSensorExists() {
        ManagedCurrentSensor.set("X-2222268RXN")

        assertEquals(
            "F0FD4509C7C2",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = null,
                activeSensors = arrayOf("F0FD4509C7C2")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_keepsPreferredWhenNoActiveSensorsRemain() {
        assertEquals(
            "historical-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "historical-sensor",
                activeSensors = emptyArray()
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_returnsNullWhenNothingIsKnown() {
        assertNull(
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = null,
                activeSensors = emptyArray()
            )
        )
    }

    @Test
    fun placeholderIdentity_neverBecomesASensorOrMainSelection() {
        ManagedCurrentSensor.set("?")

        assertNull(SensorIdentity.resolveAppSensorId("?"))
        assertNull(
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = "?",
                preferredSensorId = null,
                activeSensors = arrayOf("?")
            )
        )
        assertEquals(
            "real-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = "?",
                preferredSensorId = null,
                activeSensors = arrayOf("?", "real-sensor")
            )
        )
    }

    @Test
    fun usableIdentity_acceptsVendorFormatsButRejectsPlaceholdersAndControls() {
        listOf(
            "SIBI:0683013AQT9",
            "ICN-8760080A2604",
            "X-222227JR7C",
            "P225043JMV",
            "46HU804EBJ4",
        ).forEach { assertTrue(SensorIdentity.isUsableSensorId(it)) }

        assertFalse(SensorIdentity.isUsableSensorId("?"))
        assertFalse(SensorIdentity.isUsableSensorId("bad\u001Didentity"))
    }

    @Test
    fun matches_recognizesAidexCanonicalAndAlias() {
        assertTrue(SensorIdentity.matches("X-222227JR7C", "222227JR7C"))
        assertTrue(SensorIdentity.matches("222227JR7C", "X-222227JR7C"))
    }

    @Test
    fun matches_recognizesIcanCanonicalAndNativeAlias() {
        val canonical = "8760080A00070000"
        val alias = ICanHealthConstants.nativeShortSensorAlias(canonical)
        assertTrue(alias != null && SensorIdentity.matches(canonical, alias))
        assertTrue(alias != null && SensorIdentity.matches(alias, canonical))
    }

    @Test
    fun distinctLogicalSensorIds_prefersCanonicalManagedIds() {
        assertEquals(
            listOf("8760080A00070000", "X-222227JR7C"),
            SensorIdentity.distinctLogicalSensorIds(
                listOf(
                    "80A00070000",
                    "8760080A00070000",
                    "222227JR7C",
                    "X-222227JR7C",
                )
            )
        )
    }

    @Test
    fun resolveRoomStorageSensorId_usesNativeShortAliasForLongNativeNames() {
        assertEquals(
            "1YL08230BFY",
            SensorIdentity.resolveRoomStorageSensorId("240601YL08230BFY")
        )
        assertEquals(
            "0671014ATR8",
            SensorIdentity.resolveRoomStorageSensorId("1P2250671014ATR8")
        )
    }

    @Test
    fun resolveRoomStorageSensorId_keepsManagedCanonicalIds() {
        assertEquals(
            "X-222227JR7C",
            SensorIdentity.resolveRoomStorageSensorId("X-222227JR7C")
        )
        assertEquals(
            "8760080A00070000",
            SensorIdentity.resolveRoomStorageSensorId("8760080A00070000")
        )
    }
    // --- matches() memoization (regex/registry work off the per-reading path) ---

    @Test
    fun matches_isStableAcrossRepeatedCalls() {
        // Distinct ids that do not resolve to each other: the answer must stay false
        // whether it came from the cache or from a fresh resolution.
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-beta"))
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-beta"))
        SensorIdentity.invalidateCaches()
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-beta"))
    }

    @Test
    fun matches_trivialEqualityStillShortCircuits() {
        // Must not depend on the cache, and must stay case-insensitive.
        assertTrue(SensorIdentity.matches("SENSOR-ALPHA", "sensor-alpha"))
        SensorIdentity.invalidateCaches()
        assertTrue(SensorIdentity.matches("SENSOR-ALPHA", "sensor-alpha"))
    }

    @Test
    fun matches_survivesCacheInvalidation() {
        assertTrue(SensorIdentity.matches("sensor-alpha", "sensor-alpha"))
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-gamma"))
        SensorIdentity.invalidateCaches()
        assertTrue(SensorIdentity.matches("sensor-alpha", "sensor-alpha"))
        assertFalse(SensorIdentity.matches("sensor-alpha", "sensor-gamma"))
    }

    @Test
    fun matches_blankExpectedStillMatchesAnything() {
        assertTrue(SensorIdentity.matches("sensor-alpha", null))
        assertTrue(SensorIdentity.matches("sensor-alpha", ""))
    }

}
