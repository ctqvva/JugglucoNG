package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.drivers.sibionics.SibionicsGs3ProtocolTest.Companion.bytes

class SibionicsGs3IdentityTest {
    private val expected = bytes("AA BB CC 0D 0E 0F")

    private fun serial(text: String) = SibionicsGs3Identity.macFromSerialNumber(text.toByteArray())

    @Test
    fun `serial number string forms`() {
        assertArrayEquals(expected, serial("AA:BB:CC:0D:0E:0F"))
        assertArrayEquals(expected, serial("aa-bb-cc-0d-0e-0f"))
        assertArrayEquals(expected, serial("AABBCC0D0E0F"))
        assertArrayEquals(expected, serial("GS3 AABBCC0D0E0F\u0000"))
    }

    @Test
    fun `a longer hex run is not a mac`() {
        assertNull(serial("0123456789ABCDEF"))
        assertNull(serial("GS3-0001"))
    }

    @Test
    fun `six binary bytes are the mac itself`() {
        assertArrayEquals(bytes("01 92 F3 04 85 06"), SibionicsGs3Identity.macFromSerialNumber(bytes("01 92 F3 04 85 06")))
    }

    @Test
    fun `placeholder identities are refused`() {
        assertNull(serial("00:00:00:00:00:00"))
        assertNull(serial("FFFFFFFFFFFF"))
        assertNull(SibionicsGs3Identity.macFromSerialNumber(ByteArray(0)))
        assertNull(SibionicsGs3Identity.macFromSerialNumber(null))
    }

    @Test
    fun `advertisement prefers manufacturer data then service data`() {
        val manufacturer = expected + bytes("99 98")
        val service = bytes("01 02 03 04 05 06")
        assertArrayEquals(expected, SibionicsGs3Identity.macFromAdvertisement(manufacturer, service))
        assertArrayEquals(service, SibionicsGs3Identity.macFromAdvertisement(bytes("01 02"), service))
        assertArrayEquals(service, SibionicsGs3Identity.macFromAdvertisement(null, service))
        assertNull(SibionicsGs3Identity.macFromAdvertisement(null, bytes("01 02 03")))
    }

    @Test
    fun `format and parse round trip`() {
        assertEquals("AA:BB:CC:0D:0E:0F", SibionicsGs3Identity.format(expected))
        assertArrayEquals(expected, SibionicsGs3Identity.parse("AA:BB:CC:0D:0E:0F"))
        assertNull(SibionicsGs3Identity.parse(""))
    }
}
