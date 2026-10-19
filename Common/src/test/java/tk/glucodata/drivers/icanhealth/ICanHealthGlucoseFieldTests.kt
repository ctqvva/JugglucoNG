package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shared glucose-field decode.
 *
 * Three call sites used to carry byte-identical copies of this arithmetic; these tests exist so
 * the single remaining copy cannot drift, and so the high nibble stays out of the value.
 */
class ICanHealthGlucoseFieldTests {

    /** Build the two wire bytes for a 12-bit value and a 4-bit high nibble. */
    private fun decode(mantissa: Int, nibble: Int, direct: Boolean = false) =
        ICanHealthConstants.decodeGlucoseField(
            low = mantissa and 0xFF,
            high = ((nibble and 0x0F) shl 4) or ((mantissa ushr 8) and 0x0F),
            directGlucoseEncoding = direct,
        )

    @Test
    fun twelveBitValueIsHundredthsOfMmol() {
        val field = decode(780, 0x0)
        assertEquals(780, field.mantissa)
        assertEquals(7.80f, field.glucoseMmolL, 0.01f)
        assertEquals(140.5f, field.glucoseMgdl, 0.5f)
    }

    @Test
    fun theHighNibbleNeverChangesTheValue() {
        // Captures show this nibble constant per sensor and different between sensors (0x1 on one
        // i6, 0x0 on another), so it cannot be an exponent or a per-reading state. Reading it as
        // an exponent once scaled 317 to 3170 mg/dL and got every reading discarded as
        // out-of-range, so every nibble must decode identically.
        val expected = decode(527, 0x0).glucoseMmolL
        (0x0..0xF).forEach { nibble ->
            assertEquals(
                "nibble 0x${nibble.toString(16)} changed the value",
                expected,
                decode(527, nibble).glucoseMmolL,
                0.0001f
            )
        }
    }

    @Test
    fun theNibbleIsStillReportedForDiagnosis() {
        assertEquals(0x1, decode(527, 0x1).nibble)
        assertEquals(0x0, decode(527, 0x0).nibble)
        assertEquals(0xF, decode(527, 0xF).nibble)
    }

    @Test
    fun valueKeepsAllTwelveBitsUnsigned() {
        // 2525 has bit 11 set; a signed read would make it negative.
        assertEquals(2525, decode(2525, 0xF).mantissa)
        assertEquals(4095, decode(4095, 0x0).mantissa)
    }

    @Test
    fun directEncodingUsesAllSixteenBitsAndReportsNoNibble() {
        val field = ICanHealthConstants.decodeGlucoseField(
            low = 0xDD,
            high = 0x09,
            directGlucoseEncoding = true,
        )
        assertEquals(2525, field.mantissa)
        assertEquals(0, field.nibble)
    }
}
