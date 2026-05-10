package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeQrTests {

    @Test
    fun parse_acceptsYuwellProductUdiAsMetadataOnly() {
        val parsed = AnytimeQr.parse("0116975124206236112602191728021910CQ6212")
        assertNotNull(parsed)
        parsed!!

        assertEquals(AnytimeQrCalibration.Format.UDI, parsed.format)
        assertFalse(parsed.isFactoryCalibration)
        assertEquals(0.30f, parsed.k, 0.0001f)
        assertEquals(50f, parsed.r, 0.0001f)
        assertEquals(AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS, parsed.lifeTime)
        assertEquals(2, parsed.productMonth)
        assertEquals(2026, parsed.productYear)
        assertEquals("CQ6212", parsed.marketNo)
        assertEquals("16975124206236", parsed.serialNo)
        assertEquals(0, parsed.voltageFlag)
    }

    @Test
    fun parse_acceptsParenthesizedYuwellProductUdi() {
        val parsed = AnytimeQr.parse("(01)16975124206236(11)260219(17)280219(10)CQ6212")
        assertNotNull(parsed)
        parsed!!

        assertEquals(AnytimeQrCalibration.Format.UDI, parsed.format)
        assertFalse(parsed.isFactoryCalibration)
        assertEquals("CQ6212", parsed.marketNo)
    }

    @Test
    fun parse_acceptsOfficialManualCode() {
        val parsed = AnytimeQr.parse("AB34567")
        assertNotNull(parsed)
        parsed!!

        assertEquals(AnytimeQrCalibration.Format.MANUAL, parsed.format)
        assertTrue(parsed.isFactoryCalibration)
        assertEquals(3.45f, parsed.k, 0.0001f)
        assertEquals(6f, parsed.r, 0.0001f)
    }

    @Test
    fun parse_manualCodeWithLeadingLetterUsesVoltageModeOne() {
        val parsed = AnytimeQr.parse("a61061B")
        assertNotNull(parsed)
        parsed!!

        assertEquals(AnytimeQrCalibration.Format.MANUAL, parsed.format)
        assertTrue(parsed.isFactoryCalibration)
        assertEquals(1.06f, parsed.k, 0.0001f)
        assertEquals(1f, parsed.r, 0.0001f)
        assertEquals(1, parsed.voltageFlag)
    }

    @Test
    fun parse_manualCodeWithLeadingDigitUsesVoltageModeZero() {
        val parsed = AnytimeQr.parse("111061B")
        assertNotNull(parsed)
        parsed!!

        assertEquals(1.06f, parsed.k, 0.0001f)
        assertEquals(1f, parsed.r, 0.0001f)
        assertEquals(0, parsed.voltageFlag)
    }

    @Test
    fun parse_acceptsOfficialScannerPatternD() {
        val parsed = AnytimeQr.parse("Q1B2031234561234567ZZ")
        assertNotNull(parsed)
        parsed!!

        assertEquals(AnytimeQrCalibration.Format.D, parsed.format)
        assertTrue(parsed.isFactoryCalibration)
        assertEquals(1.23f, parsed.k, 0.0001f)
        assertEquals(45.6f, parsed.r, 0.0001f)
        assertEquals(1, parsed.voltageFlag)
    }
}
