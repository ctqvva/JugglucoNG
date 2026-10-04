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

    @Test
    fun snSnNameWinsOverGenericAnytimeBrandName() {
        // Live trace juggluco-trace-20261004-194106.log: the SN91 unit renames itself
        // "Anytime  4pro" after connecting, and the CT5 catch-all "Anytime" prefix used
        // to shadow the specific SN91 family, looping the CT5 handshake forever.
        val resolved = AnytimeConstants.resolveHandshakeName("Anytime  4pro", "SN9150002398")

        assertEquals("SN9150002398", resolved)
        assertEquals(AnytimeConstants.Family.CT3_ULTRASONIC, AnytimeConstants.resolveFamily(resolved).family)
    }

    @Test
    fun genericAnytimeNameStillResolvesForRealCt5() {
        val resolved = AnytimeConstants.resolveHandshakeName("", "Anytime  5pro", "FC:12:34:56:78:90")

        assertEquals("Anytime  5pro", resolved)
        assertEquals(AnytimeConstants.Family.CT5, AnytimeConstants.resolveFamily(resolved).family)
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
