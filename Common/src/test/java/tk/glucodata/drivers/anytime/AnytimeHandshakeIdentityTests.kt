package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Test

class AnytimeHandshakeIdentityTests {
    private val sn91 = "SN9150002398"
    private val sensorId = "D4FE28EB506B"

    @Test
    fun connectedSn91OverridesCachedCt5Name() {
        // The trace sees SN91 at connect but repeatedly sends CT5 setID afterwards.
        for (cached in listOf("Anytime", "Anytime  4pro", AnytimeConstants.DEFAULT_DISPLAY_NAME)) {
            val selected = AnytimeConstants.resolveHandshakeName(cached, sn91, "CGM Sensor", sensorId)

            assertEquals(sn91, selected)
            assertEquals(AnytimeConstants.Family.CT3_ULTRASONIC, AnytimeProfileResolver.resolve(selected).family)
        }
    }

    @Test
    fun activeSn91OverridesCachedCt5WhenConnectedNameIsMissing() {
        assertEquals(sn91, AnytimeConstants.resolveHandshakeName("Anytime", null, sn91, sensorId))
    }

    @Test
    fun connectedNameWinsWhenGattNameChangesAfterConnect() {
        assertEquals(sn91, AnytimeConstants.resolveHandshakeName("Anytime", sn91, "Anytime", sensorId))
    }

    @Test
    fun sn91RemainsUltrasonicWhenReconnectNameChangesToAnytime4pro() {
        val selected = AnytimeConstants.resolveHandshakeName("Anytime  4pro", sn91, "Anytime  4pro", sensorId)
        // beginHandshake saves this result. Later connections and process restarts
        // must keep it even when both Bluetooth name sources now report the brand.
        repeat(2) {
            val reconnected = AnytimeConstants.resolveHandshakeName(selected, "Anytime  4pro", "Anytime  4pro", sensorId)
            assertEquals(sn91, reconnected)
            assertEquals(AnytimeConstants.Family.CT3_ULTRASONIC, AnytimeProfileResolver.resolve(reconnected).family)
        }
    }

    @Test
    fun knownSnSerialBeatsTheSharedBrandName() {
        assertEquals(sn91, AnytimeConstants.resolveHandshakeName("Anytime", "Anytime  4pro", null, sn91))
    }

    @Test
    fun ct5StillResolvesWhenNoSpecificSnIdentityExists() {
        for (name in listOf("Anytime", "Anytime  4pro")) {
            val selected = AnytimeConstants.resolveHandshakeName("", name, null, sensorId)
            assertEquals(name, selected)
            assertEquals(AnytimeConstants.Family.CT5, AnytimeProfileResolver.resolve(selected).family)
            assertEquals(name, AnytimeConstants.resolveHandshakeName(selected, null, "CGM Sensor", sensorId))
        }
    }

    @Test
    fun everySpecificVendorPrefixBeatsTheSharedBrand() {
        AnytimeConstants.FAMILY_TABLE.filter { it.family != AnytimeConstants.Family.CT5 }.forEach { entry ->
            val name = "${entry.prefix}-test"
            assertEquals(name, AnytimeConstants.resolveHandshakeName(name, "Anytime", null, sensorId))
            assertEquals(name, AnytimeConstants.resolveHandshakeName("Anytime", name, null, sensorId))
        }
    }

    @Test
    fun unknownLiveNamesRetainCachedFamilyOnStoredAddressReconnect() {
        for (cached in listOf(sn91, "SN08402458", "Anytime")) {
            assertEquals(cached, AnytimeConstants.resolveHandshakeName(cached, null, "CGM Sensor", sensorId))
            // beginHandshake persists the selected name; another process sees the same family.
            val selected = AnytimeConstants.resolveHandshakeName("Anytime", cached, null, sensorId)
            assertEquals(cached, AnytimeConstants.resolveHandshakeName(selected, null, null, sensorId))
        }
    }

    @Test
    fun namesAreTrimmedAndUnclassifiedNamesHaveADeterministicFallback() {
        assertEquals(sn91, AnytimeConstants.resolveHandshakeName("Anytime", "  $sn91  ", null, sensorId))
        assertEquals("CGM Sensor", AnytimeConstants.resolveHandshakeName("unknown", " CGM Sensor ", null, sensorId))
        assertEquals("", AnytimeConstants.resolveHandshakeName(null, " ", null, null))
    }
}
