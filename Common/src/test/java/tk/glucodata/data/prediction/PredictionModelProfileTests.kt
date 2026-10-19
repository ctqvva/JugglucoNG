package tk.glucodata.data.prediction

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionModelProfileTests {

    @Test
    fun singleProfilePreservesLegacyAllDayValues() {
        val profile = PredictionModelProfile.single(
            carbRatioGramsPerUnit = 12f,
            insulinSensitivityMgDlPerUnit = 65f
        )

        assertEquals(1, profile.blocks.size)
        assertEquals(0, profile.blocks.single().startMinuteOfDay)
        assertEquals(12f, profile.parametersAtMinute(0).carbRatioGramsPerUnit, 0f)
        assertEquals(65f, profile.parametersAtMinute(1439).insulinSensitivityMgDlPerUnit, 0f)
        assertEquals(35f, profile.parametersAtMinute(1439).carbAbsorptionGramsPerHour, 0f)
    }

    @Test
    fun addedPeriodInheritsThenResolvesAtExactBoundary() {
        val profile = PredictionModelProfile.single(10f, 54f)
            .addBlock(8 * 60)
            .updateBlock(
                startMinuteOfDay = 8 * 60,
                carbRatioGramsPerUnit = 15f,
                insulinSensitivityMgDlPerUnit = 72f,
                carbAbsorptionGramsPerHour = 24f
            )

        assertEquals(10f, profile.parametersAtMinute(7 * 60 + 59).carbRatioGramsPerUnit, 0f)
        assertEquals(15f, profile.parametersAtMinute(8 * 60).carbRatioGramsPerUnit, 0f)
        assertEquals(72f, profile.parametersAtMinute(23 * 60 + 59).insulinSensitivityMgDlPerUnit, 0f)
        assertEquals(24f, profile.parametersAtMinute(23 * 60 + 59).carbAbsorptionGramsPerHour, 0f)
    }

    @Test
    fun timestampResolutionUsesRequestedLocalTimeZone() {
        val profile = PredictionModelProfile.single(9f, 45f)
            .addBlock(18 * 60)
            .updateBlock(
                startMinuteOfDay = 18 * 60,
                carbRatioGramsPerUnit = 14f,
                insulinSensitivityMgDlPerUnit = 80f
            )
        val timestamp = 18L * 60L * 60L * 1000L

        assertEquals(
            14f,
            profile.parametersAt(timestamp, TimeZone.getTimeZone("UTC")).carbRatioGramsPerUnit,
            0f
        )
        assertEquals(
            9f,
            profile.parametersAt(timestamp, TimeZone.getTimeZone("GMT-05:00")).carbRatioGramsPerUnit,
            0f
        )
    }

    @Test
    fun movedAndRemovedPeriodsRemainGapFree() {
        val profile = PredictionModelProfile.single(10f, 54f)
            .addBlock(8 * 60)
            .addBlock(18 * 60)
            .updateBlock(8 * 60, carbRatioGramsPerUnit = 13f)
            .moveBlock(8 * 60, 9 * 60)
            .removeBlock(18 * 60)

        assertEquals(listOf(0, 9 * 60), profile.blocks.map { it.startMinuteOfDay })
        assertEquals(10f, profile.parametersAtMinute(8 * 60 + 59).carbRatioGramsPerUnit, 0f)
        assertEquals(13f, profile.parametersAtMinute(9 * 60).carbRatioGramsPerUnit, 0f)
        assertEquals(PredictionModelProfile.LAST_MINUTE_OF_DAY, profile.endMinuteFor(1))
    }

    @Test
    fun serializedProfileRoundTripsInSortedOrder() {
        val original = PredictionModelProfile.single(10f, 54f)
            .addBlock(18 * 60)
            .updateBlock(
                18 * 60,
                carbRatioGramsPerUnit = 8f,
                insulinSensitivityMgDlPerUnit = 40f,
                carbAbsorptionGramsPerHour = 20f
            )
            .addBlock(8 * 60)
            .updateBlock(8 * 60, carbRatioGramsPerUnit = 16f, insulinSensitivityMgDlPerUnit = 75f)

        val restored = PredictionModelProfile.decode(
            original.encode(),
            PredictionModelParameters(3f, 10f)
        )

        assertEquals(original, restored)
    }

    @Test
    fun legacyThreeFieldProfileInheritsGlobalAbsorption() {
        val restored = PredictionModelProfile.decode(
            "0,10.0,54.0;480,14.0,72.0",
            PredictionModelParameters(
                carbRatioGramsPerUnit = 9f,
                insulinSensitivityMgDlPerUnit = 45f,
                carbAbsorptionGramsPerHour = 42f
            )
        )

        assertEquals(42f, restored.parametersAtMinute(0).carbAbsorptionGramsPerHour, 0f)
        assertEquals(42f, restored.parametersAtMinute(480).carbAbsorptionGramsPerHour, 0f)
        assertEquals(4, restored.encode().substringBefore(';').split(',').size)
    }
    @Test
    fun unsavedProfileDecodesToTheDisplayedFallbackValues() {
        // No PROFILE_KEY in prefs means decode(null, fallback): the profile
        // must carry exactly the fallback values the screen displays. The
        // PRE_HIGH IOB coverage check relies on this instead of requiring a
        // saved profile (the model profile screen has no save button).
        val profile = PredictionModelProfile.decode(
            null,
            PredictionModelParameters(
                carbRatioGramsPerUnit = 10f,
                insulinSensitivityMgDlPerUnit = 54f
            )
        )
        assertEquals(54f, profile.parametersAtMinute(600).insulinSensitivityMgDlPerUnit, 0f)
        assertEquals(10f, profile.parametersAtMinute(600).carbRatioGramsPerUnit, 0f)
    }
}
