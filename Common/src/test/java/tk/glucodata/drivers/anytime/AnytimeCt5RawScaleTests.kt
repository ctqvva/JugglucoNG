package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeCt5RawScaleTests {

    /** The last eight readings this sensor's firmware computed before it stopped. */
    private val lastGoodPairs = listOf(
        8.06f to 107f, 8.24f to 109f, 8.27f to 110f, 8.69f to 115f,
        8.89f to 118f, 9.04f to 120f, 8.77f to 116f, 9.07f to 120f,
    )

    private fun scaleFrom(pairs: List<Pair<Float, Float>>, repeats: Int) =
        AnytimeCt5RawScale().apply {
            repeat(repeats) { pairs.forEach { (iw, mgdl) -> observe(iw, mgdl) } }
        }

    @Test
    fun noEstimateUntilEnoughOfAWindowHasBeenSeen() {
        val s = scaleFrom(lastGoodPairs, repeats = 4) // 32 samples

        assertTrue(s.scale.isNaN())
        assertTrue("must not guess from a handful of points", s.estimateMgdl(7.0f).isNaN())
    }

    @Test
    fun learnsTheSensorsOwnScaleAndReproducesItsGlucose() {
        val s = scaleFrom(lastGoodPairs, repeats = 40) // 320 samples

        assertFalse(s.scale.isNaN())
        // Vendor output over that window is mg/dL = 13.26 x Iw, +/-0.28%.
        assertEquals(13.26f, s.scale, 0.05f)
        // And it reproduces the readings it was taught, which is the floor for trusting it.
        lastGoodPairs.forEach { (iw, mgdl) ->
            assertEquals(mgdl, s.estimateMgdl(iw), mgdl * 0.02f)
        }
    }

    @Test
    fun estimatesCurrentsTheTransmitterNeverComputedFor() {
        val s = scaleFrom(lastGoodPairs, repeats = 40)

        // Terminal frames ran Iw 5.44-7.49 nA, entirely below the fitted range;
        // the concurrent Ottai read 81-86 mg/dL over the same window.
        assertEquals(72f, s.estimateMgdl(5.44f), 3f)
        assertEquals(99f, s.estimateMgdl(7.49f), 3f)
    }

    @Test
    fun theWindowForgetsSoSensitivityDriftIsTracked() {
        val s = AnytimeCt5RawScale(windowSize = 300)
        repeat(300) { s.observe(10f, 150f) }   // scale 15
        assertEquals(15f, s.scale, 0.01f)

        repeat(300) { s.observe(10f, 130f) }   // sensor drifts to scale 13
        assertEquals("the old window must age out entirely", 13f, s.scale, 0.01f)
    }

    @Test
    fun warmupAndErroredRecordsCannotPoisonTheScale() {
        val s = scaleFrom(lastGoodPairs, repeats = 40)
        val before = s.scale

        repeat(50) { s.observe(6.0f, 0f) }      // warm-up: no glucose
        repeat(50) { s.observe(0f, 110f) }      // no current
        repeat(50) { s.observe(6.0f, 600f) }    // ratio 100, physically absurd

        assertEquals(before, s.scale, 0.001f)
    }

    @Test
    fun restoredScaleIsUsableImmediatelyButOnlyIfCredible() {
        val restored = AnytimeCt5RawScale().apply { restore(13.26f, samples = 500) }
        assertEquals(13.26f, restored.scale, 0.001f)
        assertEquals(99f, restored.estimateMgdl(7.49f), 3f)

        // A scale persisted from too little evidence, or an implausible one, is ignored.
        assertTrue(AnytimeCt5RawScale().apply { restore(13.26f, samples = 10) }.scale.isNaN())
        assertTrue(AnytimeCt5RawScale().apply { restore(200f, samples = 500) }.scale.isNaN())
    }

    @Test
    fun absurdEstimatesAreWithheldRatherThanClamped() {
        val s = scaleFrom(lastGoodPairs, repeats = 40)

        assertTrue(s.estimateMgdl(0f).isNaN())
        assertTrue(s.estimateMgdl(Float.NaN).isNaN())
        assertTrue("60 nA would imply ~800 mg/dL", s.estimateMgdl(60f).isNaN())
    }
}
