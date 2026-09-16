package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSensitivityExtraTests {

    @Test
    fun digitTokensDecodeToSensitivity() {
        assertEquals(1.27f, SibionicsSensitivity.tryDecode("1270")!!, 0.0001f)
        assertEquals(0.8f, SibionicsSensitivity.tryDecode("0800")!!, 0.0001f)
        assertEquals(2.5f, SibionicsSensitivity.tryDecode("2500")!!, 0.0001f)
        assertNull(SibionicsSensitivity.tryDecode("2501"))
        assertNull(SibionicsSensitivity.tryDecode("0799"))
    }

    @Test
    fun shortOrNullTokensAreRejected() {
        assertNull(SibionicsSensitivity.tryDecode(null))
        assertNull(SibionicsSensitivity.tryDecode(""))
        assertNull(SibionicsSensitivity.tryDecode("12"))
    }

    @Test
    fun alphaTokenDecodes() {
        val value = SibionicsSensitivity.tryDecode("46HU804E")
        assertTrue(value != null && value in SibionicsSensitivity.MIN_SENSITIVITY..SibionicsSensitivity.MAX_SENSITIVITY)
    }

    @Test
    fun everyVariantFallbackCodeDecodesInRange() {
        SibionicsConstants.Variant.entries.forEach { variant ->
            val value = SibionicsSensitivity.tryDecode(variant.fallbackShortCode)
            assertTrue("${variant.name} fallback", value != null)
            assertTrue("${variant.name} range", value!! in SibionicsSensitivity.MIN_SENSITIVITY..SibionicsSensitivity.MAX_SENSITIVITY)
        }
    }

    @Test
    fun sensitivityForPrefersTokenThenVariantFallback() {
        val direct = SibionicsSensitivity.tryDecode("46HU804E")!!
        assertEquals(direct, SibionicsSensitivity.sensitivityFor("46HU804E", SibionicsConstants.Variant.EU), 0.0001f)
        val fallback = SibionicsSensitivity.tryDecode(SibionicsConstants.Variant.EU.fallbackShortCode)!!
        assertEquals(fallback, SibionicsSensitivity.sensitivityFor(null, SibionicsConstants.Variant.EU), 0.0001f)
    }

    @Test
    fun deriveShortCodePrefersDecodableEightCharPrefix() {
        val fallback = "ZZZZZZZZ"
        assertEquals(
            SibionicsConstants.Variant.EU.fallbackShortCode,
            SibionicsSensitivity.deriveShortCode(SibionicsConstants.Variant.EU.fallbackShortCode, fallback),
        )
    }

    @Test
    fun deriveShortCodeDigitsAndLongNamesAndLtAndNull() {
        val fallback = "ZZZZZZZZ"
        assertEquals("12345678", SibionicsSensitivity.deriveShortCode("123456789012345", fallback))
        assertEquals("00000000", SibionicsSensitivity.deriveShortCode("123", fallback))
        assertEquals("00000000", SibionicsSensitivity.deriveShortCode("AB", fallback))
        assertEquals("5678LT12", SibionicsSensitivity.deriveShortCode("LT12345678", fallback))
        assertEquals(fallback, SibionicsSensitivity.deriveShortCode(null, fallback))
    }
}
