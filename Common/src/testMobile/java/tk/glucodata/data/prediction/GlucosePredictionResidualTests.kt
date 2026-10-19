package tk.glucodata.data.prediction

import kotlin.math.abs
import kotlin.math.exp
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.ui.GlucosePoint

class GlucosePredictionResidualTests {

    private val baselineTime = 1_700_000_000_000L
    private val sensitivity = 54f

    private fun settings(momentum: Boolean) = PredictiveSimulationSettings(
        enabled = true,
        trendMomentumEnabled = momentum,
        insulinSensitivityMgDlPerUnit = sensitivity
    )

    private fun history(valueAtMinutesBeforeBaseline: (Float) -> Float): List<GlucosePoint> {
        return (9 downTo 0).map { step ->
            val minutesBefore = step * 5f
            GlucosePoint(
                value = valueAtMinutesBeforeBaseline(minutesBefore),
                time = "",
                timestamp = baselineTime - (minutesBefore * 60_000f).toLong()
            )
        }
    }

    private fun entry(
        type: JournalEntryType,
        timestamp: Long,
        amount: Float? = null,
        durationMinutes: Int? = null,
        intensity: JournalIntensity? = null,
        insulinPresetId: Long? = null
    ) = JournalEntry(
        id = 1L,
        timestamp = timestamp,
        sensorSerial = null,
        type = type,
        title = "",
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = durationMinutes,
        intensity = intensity,
        insulinPresetId = insulinPresetId,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.MANUAL,
        sourceRecordId = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun predictAt(
        minutesAhead: Int,
        history: List<GlucosePoint>,
        entries: List<JournalEntry> = emptyList(),
        presets: Map<Long, JournalInsulinPreset> = emptyMap(),
        momentum: Boolean
    ): Float {
        val points = buildGlucosePrediction(
            history = history,
            journalEntries = entries,
            insulinPresetsById = presets,
            unit = "mg/dl",
            targetLow = 70f,
            targetHigh = 180f,
            settings = settings(momentum)
        )
        val target = baselineTime + minutesAhead * 60_000L
        return points.first { it.timestamp == target }.value
    }

    @Test
    fun momentumStillExtrapolatesUnexplainedSlope() {
        // Falling 1 mg/dl per minute with nothing logged: the whole slope is
        // unexplained, so momentum must carry it exactly as before.
        val falling = history { minutesBefore -> 150f - 1f * (45f - minutesBefore) }
        val withMomentum = predictAt(30, falling, momentum = true)
        val withoutMomentum = predictAt(30, falling, momentum = false)
        val expectedTrend = -1f * 30f * exp(-30f / 70f)
        assertEquals(expectedTrend, withMomentum - withoutMomentum, 1f)
    }

    @Test
    fun insulinExplainedFallIsNotDoubleCounted() {
        // Triangle action curve 0:0 -> 60:1 -> 120:0 integrates to a closed-form
        // cumulative fraction of t^2/7200 while t <= 60. A 2 U bolus at the start
        // of the regression window whose measured fall tracks that model exactly
        // must leave no residual slope for momentum to extrapolate.
        val preset = JournalInsulinPreset(
            id = 7L,
            displayName = "test-rapid",
            onsetMinutes = 0,
            durationMinutes = 120,
            accentColor = 0,
            curveJson = "0:0;60:1;120:0",
            isBuiltIn = false,
            isArchived = false,
            countsTowardIob = true,
            sortOrder = 0
        )
        val bolusTime = baselineTime - 45L * 60_000L
        val bolus = entry(
            JournalEntryType.INSULIN,
            timestamp = bolusTime,
            amount = 2f,
            insulinPresetId = 7L
        )
        val totalDrop = 2f * sensitivity
        val explained = history { minutesBefore ->
            val elapsed = 45f - minutesBefore
            150f - totalDrop * (elapsed * elapsed / 7200f)
        }
        val presets = mapOf(7L to preset)
        val entries = listOf(bolus)

        val withoutMomentum = predictAt(30, explained, entries, presets, momentum = false)
        assertTrue(
            "journal model should keep projecting the bolus fall",
            withoutMomentum < explained.last().value
        )
        for (minutesAhead in intArrayOf(30, 60, 90)) {
            val on = predictAt(minutesAhead, explained, entries, presets, momentum = true)
            val off = predictAt(minutesAhead, explained, entries, presets, momentum = false)
            assertTrue(
                "momentum re-added a model-explained fall at +$minutesAhead (diff ${on - off})",
                abs(on - off) < 0.6f
            )
        }
    }

    @Test
    fun activityExplainedFallIsNotDoubleCounted() {
        // Intense activity drops sensitivity * 0.22 per hour, linearly over its
        // duration: 0.198 mg/dl per minute here. A measured fall matching that
        // exactly must not be extrapolated a second time.
        val activityStart = baselineTime - 45L * 60_000L
        val activity = entry(
            JournalEntryType.ACTIVITY,
            timestamp = activityStart,
            durationMinutes = 240,
            intensity = JournalIntensity.INTENSE
        )
        val modeledSlope = sensitivity * 0.22f / 60f
        val explained = history { minutesBefore -> 140f - modeledSlope * (45f - minutesBefore) }
        val on = predictAt(30, explained, listOf(activity), momentum = true)
        val off = predictAt(30, explained, listOf(activity), momentum = false)
        assertTrue(
            "momentum re-added a model-explained activity fall (diff ${on - off})",
            abs(on - off) < 0.6f
        )
    }

    @Test
    fun unexplainedShareOfTheFallSurvivesResidualization() {
        // Measured fall = the modelled activity fall plus 0.5 mg/dl per minute
        // from an unlogged cause: momentum must keep exactly the unlogged share.
        val activityStart = baselineTime - 45L * 60_000L
        val activity = entry(
            JournalEntryType.ACTIVITY,
            timestamp = activityStart,
            durationMinutes = 240,
            intensity = JournalIntensity.INTENSE
        )
        val modeledSlope = sensitivity * 0.22f / 60f
        val mixed = history { minutesBefore -> 140f - (modeledSlope + 0.5f) * (45f - minutesBefore) }
        val on = predictAt(30, mixed, listOf(activity), momentum = true)
        val off = predictAt(30, mixed, listOf(activity), momentum = false)
        val expectedResidualTrend = -0.5f * 30f * exp(-30f / 70f)
        assertEquals(expectedResidualTrend, on - off, 1f)
    }

    @Test
    fun journalEffectUsesProfileActiveAtEntryTime() {
        val flatHistory = history { 140f }
        val carbs = entry(
            type = JournalEntryType.CARBS,
            timestamp = baselineTime,
            amount = 20f,
            durationMinutes = 60
        )
        val timedProfile = PredictionModelProfile.single(10f, 54f)
            .addBlock(12 * 60)
            .updateBlock(
                startMinuteOfDay = 12 * 60,
                carbRatioGramsPerUnit = 20f,
                insulinSensitivityMgDlPerUnit = 40f
            )
        val common = settings(momentum = false).copy(horizonMinutes = 60)
        val legacy = buildGlucosePrediction(
            history = flatHistory,
            journalEntries = listOf(carbs),
            insulinPresetsById = emptyMap(),
            unit = "mg/dl",
            targetLow = 70f,
            targetHigh = 180f,
            settings = common
        ).last().value
        val profiled = buildGlucosePrediction(
            history = flatHistory,
            journalEntries = listOf(carbs),
            insulinPresetsById = emptyMap(),
            unit = "mg/dl",
            targetLow = 70f,
            targetHigh = 180f,
            settings = common.copy(
                modelProfile = timedProfile,
                profileTimeZone = TimeZone.getTimeZone("UTC")
            )
        ).last().value

        // At 22:13 UTC, the second block applies. The modeled meal rise changes
        // from 20/10*54 = 108 mg/dL to 20/20*40 = 40 mg/dL.
        assertEquals(68f, legacy - profiled, 0.6f)
    }
}
