package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the input timing of the Adaptive V2 handoff.
 *
 * The defect this exists to prevent: V1.1.6A read `calibratedMmol` straight out
 * of the deconvolution's input slot, which the vendor only writes on its
 * five-sample correction stage. The chemical signal moved every minute while
 * the observation V2 was fed stood still for four minutes and then jumped —
 * 0-4 minutes of event-dependent lag applied *before* the estimator, which no
 * amount of estimator tuning can undo. V1.1.5G was already correct; this pins
 * both families to the same one-minute behaviour.
 *
 * The trace deliberately contains a sharp turning point, because a held value
 * is at its most damaging exactly there: a peak held for four minutes reads as
 * "still rising" long after the sensor has turned.
 */
class SibionicsObservationCadenceTest {

    /** Rises 5 minutes, turns, falls — one clean minute-resolved event. */
    private fun samples(minutes: Int): List<SibionicsSourceSample> =
        (1..minutes).map { i ->
            val phase = i % 120
            val raw = 5.0f + if (phase < 60) phase * 0.05f else (120 - phase) * 0.05f
            SibionicsSourceSample(i, i * 60_000L, raw, 34f, 2_900f, 0)
        }

    private fun rows(variant: SibionicsConstants.Variant) =
        SibionicsReplayHarness.replay(
            samples = samples(TRACE_MINUTES),
            variant = variant,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
        ).filter {
            it.index > SETTLE && it.calibratedMmol.isFinite() && it.chemicalMmol.isFinite()
        }

    /** Fraction of consecutive samples whose value is bit-for-bit unchanged. */
    private fun heldFraction(values: List<Float>): Double {
        var same = 0
        values.zipWithNext().forEach { (a, b) -> if (abs(a - b) < 1e-6f) same++ }
        return same.toDouble() / (values.size - 1)
    }

    @Test
    fun calibratedObservationMovesEveryMinuteInBothFamilies() {
        listOf(
            SibionicsConstants.Variant.SIBIONICS2 to "V116A",
            SibionicsConstants.Variant.CHINESE to "V115G",
        ).forEach { (variant, family) ->
            val rows = rows(variant)
            val held = heldFraction(rows.map { it.calibratedMmol })
            println("CADENCE %s heldCalibrated=%.3f heldChemical=%.3f".format(
                family, held, heldFraction(rows.map { it.chemicalMmol })))
            // A sample-and-held observation on a five-sample stage sits at 0.8.
            assertTrue("$family calibrated observation is sample-and-held: $held", held < 0.05)
        }
    }

    /**
     * The vendor's slow state updates every five minutes; the fast signal every
     * minute. The observation must carry the slow term and follow the fast one,
     * so a minute-level rise, turn and fall survive intact into V2.
     */
    @Test
    fun minuteDynamicsSurviveTheFiveMinuteCorrectionRefresh() {
        listOf(
            SibionicsConstants.Variant.SIBIONICS2 to "V116A",
            SibionicsConstants.Variant.CHINESE to "V115G",
        ).forEach { (variant, family) ->
            val rows = rows(variant)

            // Every minute of the chemical signal is reproduced in the
            // observation: same sign, same magnitude to within the drift the
            // stage refresh legitimately introduces.
            var reproduced = 0
            var compared = 0
            rows.zipWithNext().forEach { (a, b) ->
                val chemicalStep = b.chemicalMmol - a.chemicalMmol
                if (abs(chemicalStep) < 1e-4f) return@forEach
                compared++
                val calibratedStep = b.calibratedMmol - a.calibratedMmol
                if (calibratedStep * chemicalStep > 0f &&
                    abs(calibratedStep - chemicalStep) < 0.02f
                ) {
                    reproduced++
                }
            }
            assertTrue(
                "$family reproduced $reproduced/$compared minute steps",
                compared > 100 && reproduced >= compared * 0.98,
            )

            // The turning point must land on the same minute in both traces.
            fun turns(value: (SibionicsReplayHarness.Row) -> Float): List<Int> =
                rows.windowed(3).mapNotNull { (a, b, c) ->
                    b.index.takeIf { value(b) > value(a) && value(b) >= value(c) }
                }

            val chemicalTurns = turns { it.chemicalMmol }
            val calibratedTurns = turns { it.calibratedMmol }
            assertEquals("$family turning points drifted", chemicalTurns, calibratedTurns)
            assertTrue("$family found no turning points", chemicalTurns.size >= 3)
        }
    }

    private companion object {
        private const val TRACE_MINUTES = 900
        private const val SETTLE = 400
    }
}
