package tk.glucodata.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Display strings must follow the app/device locale, while TTS strings must stay
 * dot-delimited so speech engines read mmol values as decimals.
 */
class DisplayValueResolverTests {
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun useCommaDecimalLocale() {
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun resolve_mmolPrimaryStr_usesLocaleSeparator() {
        val dv = DisplayValueResolver.resolve(
            autoValue = 8.5f,
            rawValue = 15.7f,
            viewMode = 0,
            isMmol = true,
            unitLabel = "mmol/L"
        )

        assertEquals("8,5", dv.primaryStr)
        assertEquals("8,5 mmol/L", dv.fullFormatted)
    }

    @Test
    fun resolve_mmolSecondaryAndTertiaryStrings_useLocaleSeparator() {
        val dv = DisplayValueResolver.resolve(
            autoValue = 8.5f,
            rawValue = 15.7f,
            viewMode = 2,
            isMmol = true,
            unitLabel = "mmol/L",
            calibratedValue = 7.4f
        )

        assertEquals("7,4", dv.primaryStr)
        assertEquals("8,5", dv.secondaryStr)
        assertEquals("15,7", dv.tertiaryStr)
        assertEquals("7,4 · 8,5 · 15,7 mmol/L", dv.fullFormatted)
    }

    @Test
    fun resolve_unitLabelEmpty_hasNoTrailingSpace() {
        val dv = DisplayValueResolver.resolve(
            autoValue = 5.5f,
            rawValue = 5.5f,
            viewMode = 0,
            isMmol = true,
            unitLabel = ""
        )

        assertEquals("5,5", dv.fullFormatted)
    }

    @Test
    fun resolve_viewMode2_noCalibrated_autoPrimary() {
        val dv = DisplayValueResolver.resolve(
            autoValue = 8.5f,
            rawValue = 15.7f,
            viewMode = 2,
            isMmol = true,
            calibratedValue = null
        )

        assertEquals("8,5", dv.primaryStr)
        assertEquals("15,7", dv.secondaryStr)
    }

    @Test
    fun resolve_nonGlucoseRawSentinel_isOmitted() {
        val dv = DisplayValueResolver.resolve(
            autoValue = 115f,
            rawValue = 11_557f,
            viewMode = 2,
            isMmol = false,
        )

        assertEquals("115", dv.primaryStr)
        assertEquals(null, dv.secondaryStr)
        assertEquals("115", dv.fullFormatted)
    }

    @Test
    fun formatForSpeech_mmolOneDecimal_usesDotSeparator() {
        assertEquals("15.7", DisplayValueResolver.formatForSpeech(15.7f, isMmol = true))
        assertEquals("0.0", DisplayValueResolver.formatForSpeech(0.0f, isMmol = true))
        assertEquals("5.0", DisplayValueResolver.formatForSpeech(5.0f, isMmol = true))
        assertEquals("33.3", DisplayValueResolver.formatForSpeech(33.3f, isMmol = true))
    }

    @Test
    fun formatForSpeech_mgdLNoDecimal_usesInteger() {
        assertEquals("157", DisplayValueResolver.formatForSpeech(157.0f, isMmol = false))
        assertEquals("100", DisplayValueResolver.formatForSpeech(100.0f, isMmol = false))
        assertEquals("65", DisplayValueResolver.formatForSpeech(65.0f, isMmol = false))
    }

    @Test
    fun resolve_mgdLPrimaryStr_hasNoDecimalSeparator() {
        val dv = DisplayValueResolver.resolve(
            autoValue = 157f,
            rawValue = 157f,
            viewMode = 0,
            isMmol = false,
            unitLabel = "mg/dL"
        )

        assertEquals("157", dv.primaryStr)
        assertFalse(dv.primaryStr.contains("."))
        assertFalse(dv.primaryStr.contains(","))
    }

    @Test
    fun resolve_invalidValues_returnDoubleDash() {
        val nan = DisplayValueResolver.resolve(
            autoValue = Float.NaN,
            rawValue = Float.NaN,
            viewMode = 0,
            isMmol = true,
            unitLabel = "mmol/L"
        )
        val negative = DisplayValueResolver.resolve(
            autoValue = -1f,
            rawValue = -1f,
            viewMode = 0,
            isMmol = false,
            unitLabel = "mg/dL"
        )

        assertEquals("--", nan.primaryStr)
        assertEquals("--", negative.primaryStr)
    }

    @Test
    fun resolve_rawModeWithoutRawInput_keepsARecordedValueButNotALiveCalibration() {
        // AiDEX stores rawValue = 0 when its raw signal is unusable. A minute
        // recorded on the auto line and viewed after a switch to raw keeps its
        // record, as the chart does; a live calibration of nothing does not.
        val recorded = DisplayValueResolver.resolve(
            autoValue = 6.1f,
            rawValue = 0f,
            viewMode = 1,
            isMmol = true,
            calibratedValue = 5.4f,
            calibratedIsRecorded = true
        )
        assertEquals(5.4f, recorded.primaryValue, 0.001f)

        val live = DisplayValueResolver.resolve(
            autoValue = 6.1f,
            rawValue = 0f,
            viewMode = 1,
            isMmol = true,
            calibratedValue = 5.4f
        )
        assertEquals(6.1f, live.primaryValue, 0.001f)
    }

    @Test
    fun resolve_rawPlusAutoWithoutRawInput_keepsAutoBesideTheRecord() {
        for (isMmol in listOf(false, true)) {
            val auto = if (isMmol) 6.1f else 110f
            val recorded = if (isMmol) 5.4f else 97f
            for (raw in listOf(0f, Float.NaN, -1f, 11_557f)) {
                for (hideSource in listOf(false, true)) {
                    val result = DisplayValueResolver.resolve(
                        autoValue = auto,
                        rawValue = raw,
                        viewMode = 3,
                        isMmol = isMmol,
                        calibratedValue = recorded,
                        hideInitialWhenCalibrated = hideSource,
                        calibratedIsRecorded = true
                    )
                    assertEquals(recorded, result.primaryValue, 0.001f)
                    assertEquals(auto, result.secondaryValue)
                    assertEquals(null, result.tertiaryValue)
                    val expected = if (isMmol) "5,4 · 6,1" else "97 · 110"
                    assertEquals(expected, result.fullFormatted)
                    assertEquals(if (isMmol) "6,1" else "110", result.secondaryStr)
                }
            }
        }
    }
}
