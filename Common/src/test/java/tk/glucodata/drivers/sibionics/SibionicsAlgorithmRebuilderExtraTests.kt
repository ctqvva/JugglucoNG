package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsAlgorithmRebuilderExtraTests {

    private fun sample(index: Int) = SibionicsSourceSample(
        index = index,
        timestampMs = 1_700_000_000_000L + index * 60_000L,
        rawMmol = 8f,
        temperatureC = 34f,
        impedance = 1_000f,
        variantId = SibionicsConstants.Variant.CHINESE.ordinal,
    )

    private fun rebuildMmol(samples: List<SibionicsSourceSample>) = SibionicsAlgorithmRebuilder.rebuild(
        sensorId = "unit",
        sourceSamples = samples,
        selection = SibionicsAlgorithmSelection.STOCK,
        variant = SibionicsConstants.Variant.CHINESE,
        shortCode = "46HU804EBJ4",
        sensitivity = 1.4f,
        unitIsMmol = true,
    ) { _, _ -> error("stock mode must not request integrated calibration") }

    @Test
    fun mgdlDisplayPathMatchesMmolPathForStock() {
        val samples = (1..8).map(::sample)
        val mmol = rebuildMmol(samples)
        val mgdl = SibionicsAlgorithmRebuilder.rebuild(
            sensorId = "unit",
            sourceSamples = samples,
            selection = SibionicsAlgorithmSelection.STOCK,
            variant = SibionicsConstants.Variant.CHINESE,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            unitIsMmol = false,
        ) { _, _ -> error("stock mode must not request integrated calibration") }

        assertEquals(mmol.readings.size, mgdl.readings.size)
        assertTrue(mmol.readings.isNotEmpty())
        mmol.readings.zip(mgdl.readings).forEach { (a, b) ->
            assertEquals(a.glucoseMgdl, b.glucoseMgdl, 0.0001f)
        }
    }

    @Test
    fun wrongSizedCalibrationSeriesFallsBackToStock() {
        var invoked = false
        val replay = SibionicsAlgorithmRebuilder.rebuild(
            sensorId = "cal",
            sourceSamples = (1..6).map(::sample),
            selection = SibionicsAlgorithmSelection.STOCK_CALIBRATED,
            variant = SibionicsConstants.Variant.CHINESE,
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            unitIsMmol = true,
        ) { values, timestamps ->
            invoked = true
            assertEquals(values.size, timestamps.size)
            FloatArray(values.size - 1) { 5.7f }
        }

        assertTrue(invoked)
        assertTrue(replay.readings.isNotEmpty())
    }

    @Test
    fun baselineRejectsMalformedInputs() {
        val start = 1_700_000_000_000L
        assertNull(
            SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
                displayStock = floatArrayOf(8f),
                timestamps = longArrayOf(start, start + 60_000L),
                packedAnchors = doubleArrayOf(13.0, 10.0, start.toDouble()),
            )
        )
        assertNull(
            SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
                displayStock = floatArrayOf(),
                timestamps = longArrayOf(),
                packedAnchors = doubleArrayOf(13.0, 10.0, start.toDouble()),
            )
        )
        assertNull(
            SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
                displayStock = floatArrayOf(8f),
                timestamps = longArrayOf(start),
                packedAnchors = doubleArrayOf(13.0, 10.0),
            )
        )
        assertNull(
            SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
                displayStock = floatArrayOf(8f),
                timestamps = longArrayOf(start),
                packedAnchors = doubleArrayOf(13.0, 10.0, 0.0),
            )
        )
    }

    @Test
    fun baselineSkipsNonUsableStockValues() {
        val start = 1_700_000_000_000L
        assertNull(
            SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
                displayStock = floatArrayOf(Float.NaN, -1f),
                timestamps = longArrayOf(start, start + 60_000L),
                packedAnchors = doubleArrayOf(13.0, 10.0, start.toDouble()),
            )
        )
    }

    @Test
    fun baselineCollapsesDuplicateNearestTimestamps() {
        val start = 1_700_000_000_000L
        val baseline = SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
            displayStock = floatArrayOf(8f, 8.2f, 8.4f),
            timestamps = longArrayOf(start, start + 60_000L, start + 120_000L),
            packedAnchors = doubleArrayOf(
                13.0, 10.0, (start + 65_000L).toDouble(),
                14.0, 11.0, (start + 70_000L).toDouble(),
            ),
        )!!

        assertEquals(1, baseline.values.size)
        assertEquals(8.2f, baseline.values[0], 0.001f)
        assertEquals(start + 60_000L, baseline.timestamps[0])
    }

    @Test
    fun baselineIgnoresAnchorsBeyondTheMatchWindow() {
        val start = 1_700_000_000_000L
        assertNull(
            SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
                displayStock = floatArrayOf(8f, 8.2f),
                timestamps = longArrayOf(start, start + 60_000L),
                packedAnchors = doubleArrayOf(13.0, 10.0, (start + 20L * 60_000L).toDouble()),
            )
        )
    }
}
