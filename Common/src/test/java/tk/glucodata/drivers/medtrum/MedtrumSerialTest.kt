package tk.glucodata.drivers.medtrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The serial printed on a Medtrum patch is hexadecimal — the vendor app stores the same value
 * as a decimal long (captured patch: label "8adf6216", `SensorSn = 2329895446`).
 */
class MedtrumSerialTest {

    @Test
    fun parsesTheLabelHex() {
        assertEquals(2329895446L, MedtrumPersistence.parseSerialNumber("8ADF6216"))
        assertEquals(2329895446L, MedtrumPersistence.parseSerialNumber("8adf6216"))
        assertEquals(2329895446L, MedtrumPersistence.parseSerialNumber("  8adf6216  "))
    }

    @Test
    fun acceptsLeadingZerosAndTheFullUint32Range() {
        assertEquals(15L, MedtrumPersistence.parseSerialNumber("0000000F"))
        assertEquals(0xFFFFFFFFL, MedtrumPersistence.parseSerialNumber("ffffffff"))
    }

    @Test
    fun rejectsEmptyGarbageTooLongAndZero() {
        assertNull(MedtrumPersistence.parseSerialNumber(null))
        assertNull(MedtrumPersistence.parseSerialNumber(""))
        assertNull(MedtrumPersistence.parseSerialNumber("   "))
        assertNull(MedtrumPersistence.parseSerialNumber("GG"))
        assertNull(MedtrumPersistence.parseSerialNumber("8ADF6216A")) // 9 digits
        assertNull(MedtrumPersistence.parseSerialNumber("0"))
        assertNull(MedtrumPersistence.parseSerialNumber("0x8ADF6216"))
    }

    @Test
    fun canonicalIdIsTheDecimalFormTheVendorAppStores() {
        val sn = MedtrumPersistence.parseSerialNumber("8ADF6216")!!
        assertEquals("2329895446", MedtrumPersistence.canonicalSensorId(sn))
        assertEquals("2329895446", sn.toString())
    }

    @Test
    fun echoShowsTheLabelForm() {
        assertEquals("8ADF6216", MedtrumPersistence.formatSerialHex(2329895446L))
    }

    @Test
    fun canonicalIdStillLooksLikeAMedtrumSensorId() {
        val sn = MedtrumPersistence.parseSerialNumber("8ADF6216")!!
        assertEquals(true, MedtrumPersistence.isMedtrumSensorId(MedtrumPersistence.canonicalSensorId(sn)))
    }
}
