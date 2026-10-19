package tk.glucodata.drivers.anytime

import ist.com.sdk.EDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AnytimeProfileTests {

    @Test
    fun ct3YuwellUsesThreeMinuteCadenceAndFourteenDayProfile() {
        val profile = AnytimeProfileResolver.resolve("SN26-test")

        assertEquals(AnytimeConstants.Family.CT3_YUWELL, profile.family)
        assertEquals(3, profile.readingIntervalMinutes)
        assertEquals(14, profile.ratedLifetimeDays)
        assertEquals(6740, profile.endNumber)
    }

    @Test
    fun shorterVendorProfileStillUsesThreeMinuteCadence() {
        val profile = AnytimeProfileResolver.resolve("SN28-test")

        assertEquals(AnytimeConstants.Family.CT3_YUWELL, profile.family)
        assertEquals(3, profile.readingIntervalMinutes)
        assertEquals(7, profile.ratedLifetimeDays)
        assertEquals(3380, profile.endNumber)
    }

    @Test
    fun sn91UltrasonicResolvesToItsOwnFamilyNotTheUnknownFallback() {
        val profile = AnytimeProfileResolver.resolve("SN9150002398")

        assertEquals(AnytimeConstants.Family.CT3_ULTRASONIC, profile.family)
        assertNotEquals(AnytimeConstants.Family.UNKNOWN, profile.family)
        assertNotEquals(AnytimeConstants.Family.CT5, profile.family)
    }

    /**
     * `EDevice` and `FAMILY_TABLE` are two hand-maintained copies of the same
     * vendor catalog. A prefix present in one and missing from the other resolves
     * to `DEVICE_UNKNOWN`/`FAMILY_UNKNOWN` on one path while the other path
     * classifies it correctly — the SN91 gap hid a CT3-Ultrasonic sensor behind
     * the CT5 handshake. Keep them in lockstep.
     */
    @Test
    fun edeviceAndFamilyTableCoverTheSamePrefixes() {
        val fromTable = AnytimeConstants.FAMILY_TABLE.map { it.prefix }.toSet()
        val fromEnum = EDevice.values()
            .filter { it != EDevice.DEVICE_UNKNOWN }
            .map { it.getNameStart() }
            .toSet()

        assertEquals(fromEnum, fromTable)
    }

    @Test
    fun familyTableAlgorithmsMatchTheVendorEnum() {
        EDevice.values()
            .filter { it != EDevice.DEVICE_UNKNOWN }
            .forEach { device ->
                val entry = AnytimeConstants.FAMILY_TABLE.firstOrNull { it.prefix == device.getNameStart() }
                    ?: return@forEach
                assertEquals("${device.name} algorithm drifted from FAMILY_TABLE", device.getAlgorithm(), entry.algorithm)
                assertEquals("${device.name} endNumber drifted", device.getEndNumber(), entry.endNumber)
            }
    }
}
