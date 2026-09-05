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
    fun rawValuesAreProducedBeforeAnythingHasBeenLearned() {
        val s = scaleFrom(lastGoodPairs, repeats = 4) // 32 samples

        // The learned scale is withheld until a window justifies it...
        assertTrue(s.scale.isNaN())
        assertFalse(s.isLearned)
        // ...but the raw lane still converts, on the family figure. A sensor that
        // reports nothing at all is the failure this exists to prevent.
        assertEquals(AnytimeCt5RawScale.DEFAULT_SCALE, s.effectiveScale, 0.001f)
        assertEquals(7.0f * AnytimeCt5RawScale.DEFAULT_SCALE, s.estimateMgdl(7.0f), 0.5f)
    }

    @Test
    fun theSensorsOwnScaleReplacesTheFamilyDefault() {
        val s = scaleFrom(lastGoodPairs, repeats = 40)

        assertTrue(s.isLearned)
        assertEquals(13.26f, s.effectiveScale, 0.05f)
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
    fun theFamilyDefaultIsCloseEnoughForATerminatedSensorButNotAHealthyOne() {
        // Why estimates are confined to a transmitter whose id has stopped advancing.
        // Mid-life this sensor ran a scale near 15.2; the family default is 13.3, so
        // substituting it for a working sensor's own errored reading would be ~13% low.
        val default = AnytimeCt5RawScale().estimateMgdl(8.0f)
        val midLifeTruth = 15.2f * 8.0f

        assertEquals(13.3f * 8.0f, default, 0.5f)
        assertTrue("family default is not a stand-in for a live sensor's own scale",
            (midLifeTruth - default) / midLifeTruth > 0.10f)
    }

    @Test
    fun aRestoredScaleSurvivesTheNextReadingAndTheNextRestart() {
        val s = AnytimeCt5RawScale().apply { restore(13.26f, samples = 500) }

        // It used to evaporate here: one live reading put the window at 2 samples,
        // below the threshold, and the scale silently reverted to the family default.
        s.observe(9.04f, 120f)
        assertTrue(s.isLearned)
        assertEquals(13.26f, s.effectiveScale, 0.05f)

        // And it must persist with its real evidence, or the next restart rejects it.
        assertTrue("persisted sample count must justify a later restore",
            s.samples >= AnytimeCt5RawScale.MIN_SAMPLES)
        val roundTripped = AnytimeCt5RawScale().apply { restore(s.scale, s.samples) }
        assertTrue(roundTripped.isLearned)
    }

    @Test
    fun aFullLiveWindowTakesOverFromTheRestoredScale() {
        val s = AnytimeCt5RawScale(windowSize = 300).apply { restore(13.26f, samples = 500) }
        repeat(300) { s.observe(10f, 150f) }   // the sensor now reads scale 15

        assertEquals("live evidence must win once there is enough of it", 15f, s.scale, 0.01f)
    }

    @Test
    fun absurdEstimatesAreWithheldRatherThanClamped() {
        val s = scaleFrom(lastGoodPairs, repeats = 40)

        assertTrue(s.estimateMgdl(0f).isNaN())
        assertTrue(s.estimateMgdl(Float.NaN).isNaN())
        assertTrue("60 nA would imply ~800 mg/dL", s.estimateMgdl(60f).isNaN())
    }
}
