package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeQrTests {

    @Test
    fun matchesCanonicalOrKnownNativeAlias_acceptsNativeSuffixAlias() {
        assertTrue(AnytimeConstants.matchesCanonicalOrKnownNativeAlias("F0FD4509C7C2", "509C7C2"))
        assertTrue(AnytimeConstants.matchesCanonicalOrKnownNativeAlias("509C7C2", "F0:FD:45:09:C7:C2"))
        assertFalse(AnytimeConstants.matchesCanonicalOrKnownNativeAlias("F0FD4509C7C2", "09C7C2"))
        assertFalse(AnytimeConstants.matchesCanonicalOrKnownNativeAlias("F0FD4509C7C2", "09C7D0"))
    }

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
    fun parse_acceptsObservedTrailingKrFactoryCode() {
        val parsed = AnytimeQr.parse("a645210531368109100A4")
        assertNotNull(parsed)
        parsed!!

        assertEquals(AnytimeQrCalibration.Format.C, parsed.format)
        assertTrue(parsed.isFactoryCalibration)
        assertEquals(1.09f, parsed.k, 0.0001f)
        assertEquals(1.0f, parsed.r, 0.0001f)
        assertEquals(1, parsed.voltageFlag)
    }

    @Test
    fun parse_acceptsZeroZeroLeadCt5Ssn() {
        // Real querySSN answer that stalled a fresh CT5 bind before setParameters.
        val parsed = AnytimeQr.parse("0056131041576115100F1")
        assertNotNull(parsed)
        parsed!!

        assertTrue(parsed.isFactoryCalibration)
        assertEquals(1.15f, parsed.k, 0.0001f)
        assertEquals(1.0f, parsed.r, 0.0001f)
        assertEquals(0, parsed.voltageFlag)
    }

    @Test
    fun ct5QuerySsnFrameFromTraceDecodesToKr() {
        // RX op=0x3F from the 2026-09-26 trace; the session cipher key was 237.
        val frame = hex("3F 57 57 54 55 57 A9 A8 57 54 A8 54 55 AA A8 57 AB A8 57 57 7A A8")
        val ssn = AnytimeFrames.parseCt5QuerySsnResponse(frame, 237)
        assertEquals("0056131041576115100F1", ssn)

        val parsed = AnytimeQr.parse(ssn)!!
        assertEquals(1.15f, parsed.k, 0.0001f)
        assertEquals(1.0f, parsed.r, 0.0001f)
    }

    @Test
    fun parse_zeroZeroLeadOutsideTheVendorPatternIsRejected() {
        // Month "00" is not a valid month token in the vendor regex.
        assertNull(AnytimeQr.parse("0056001041576115100F1"))
    }

    @Test
    fun parse_formatBLeadIsNotReadAsSsnLayout() {
        // 21-char Format B code: must keep the scanner's K/R, not the trailing layout.
        val parsed = AnytimeQr.parse("2142121234561234561AB")
        assertNotNull(parsed)
        parsed!!

        assertEquals(AnytimeQrCalibration.Format.B, parsed.format)
        assertEquals(1.23f, parsed.k, 0.0001f)
        assertEquals(45.6f, parsed.r, 0.0001f)
    }

    @Test
    fun ct5SetupCalibration_fallsBackToDefaultWithoutStoredCalibration() {
        val chosen = AnytimeQr.ct5SetupCalibration(null, voltageFlag = 0)

        assertEquals(AnytimeQrCalibration.Format.DEFAULT, chosen.format)
        assertEquals(AnytimeConstants.CT5_DEFAULT_K, chosen.k, 0.0001f)
        assertEquals(AnytimeConstants.CT5_DEFAULT_R, chosen.r, 0.0001f)
        assertTrue(chosen.hasTransmitterKr)
        assertFalse(chosen.isFactoryCalibration)
    }

    @Test
    fun ct5SetupCalibration_neverSendsUdiPlaceholders() {
        val udi = AnytimeQr.parse("0116975124206236112602191728021910CQ6212")!!
        assertFalse(udi.hasTransmitterKr)

        val chosen = AnytimeQr.ct5SetupCalibration(udi, voltageFlag = 0)
        assertEquals(AnytimeQrCalibration.Format.DEFAULT, chosen.format)
        assertEquals(AnytimeConstants.CT5_DEFAULT_K, chosen.k, 0.0001f)
    }

    @Test
    fun ct5SetupCalibration_keepsAScannedFactoryCode() {
        val scanned = AnytimeQr.parse("a645210531368109100A4")!!
        assertSame(scanned, AnytimeQr.ct5SetupCalibration(scanned, voltageFlag = 1))
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

    private fun hex(text: String): ByteArray =
        text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
