package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adaptive V2's integration with the existing selection, snapshot and
 * calibration machinery.
 *
 * The compatibility assertions matter more than the new behaviour: a stored
 * selection made before V2 existed has to decode to exactly the model it always
 * did, and stock output must be untouched.
 */
class SibionicsAdaptiveV2SelectionTest {

    private fun context(selection: SibionicsAlgorithmSelection): SibionicsAlgorithmContext =
        SibionicsAlgorithmContext("v2-${selection.storageId}").apply {
            configure("46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE, selection)
        }

    private fun SibionicsAlgorithmContext.run(samples: Int, from: Int = 1): Float {
        var output = Float.NaN
        repeat(samples) { offset ->
            val index = from + offset
            output = process(
                rawMmol = 6f,
                temperatureC = 34f,
                index = index,
                mode = SibionicsAlgorithmMode.REPLAY,
                impedance = 2_900f,
                eventTimeMs = index * 60_000L,
            )
        }
        return output
    }

    @Test
    fun legacyStorageIdsAreUnchangedAndV2IsAddedAfterThem() {
        assertEquals(SibionicsAlgorithmSelection.STOCK, SibionicsAlgorithmSelection.fromStorage(0))
        assertEquals(SibionicsAlgorithmSelection.STOCK_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(1))
        assertEquals(SibionicsAlgorithmSelection.STATE_MODEL, SibionicsAlgorithmSelection.fromStorage(2))
        assertEquals(SibionicsAlgorithmSelection.STATE_MODEL_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(3))
        assertEquals(SibionicsAlgorithmSelection.BALANCED_TRACKER, SibionicsAlgorithmSelection.fromStorage(4))
        assertEquals(SibionicsAlgorithmSelection.BALANCED_TRACKER_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(5))
        assertEquals(SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR, SibionicsAlgorithmSelection.fromStorage(6))
        assertEquals(SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(7))
        assertEquals(SibionicsAlgorithmSelection.ADAPTIVE_V2, SibionicsAlgorithmSelection.fromStorage(8))
        assertEquals(SibionicsAlgorithmSelection.ADAPTIVE_V2_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(9))
    }

    @Test
    fun wideningTheModelMaskDoesNotRepointAnyLegacySelection() {
        // Every legacy id must still decode to the model it decoded to before
        // the mask widened from 6 to 14.
        val expected = mapOf(
            0 to SibionicsCustomAlgorithmModel.STOCK,
            1 to SibionicsCustomAlgorithmModel.STOCK,
            2 to SibionicsCustomAlgorithmModel.STATE_MODEL,
            3 to SibionicsCustomAlgorithmModel.STATE_MODEL,
            4 to SibionicsCustomAlgorithmModel.BALANCED_TRACKER,
            5 to SibionicsCustomAlgorithmModel.BALANCED_TRACKER,
            6 to SibionicsCustomAlgorithmModel.RESPONSIVE_ESTIMATOR,
            7 to SibionicsCustomAlgorithmModel.RESPONSIVE_ESTIMATOR,
            8 to SibionicsCustomAlgorithmModel.ADAPTIVE_V2,
            9 to SibionicsCustomAlgorithmModel.ADAPTIVE_V2,
        )
        expected.forEach { (storageId, model) ->
            assertEquals(
                "storageId=$storageId",
                model,
                SibionicsAlgorithmSelection.fromStorage(storageId).model,
            )
        }
    }

    @Test
    fun calibrationBitRoundTripsForV2LikeEveryOtherModel() {
        assertFalse(SibionicsAlgorithmSelection.ADAPTIVE_V2.calibrationEnabled)
        assertTrue(SibionicsAlgorithmSelection.ADAPTIVE_V2_CALIBRATED.calibrationEnabled)
        assertTrue(SibionicsAlgorithmSelection.ADAPTIVE_V2.customModelEnabled)
        assertEquals(
            SibionicsAlgorithmSelection.ADAPTIVE_V2_CALIBRATED,
            SibionicsAlgorithmSelection.ADAPTIVE_V2.withCalibration(true),
        )
        assertEquals(
            SibionicsAlgorithmSelection.ADAPTIVE_V2,
            SibionicsAlgorithmSelection.ADAPTIVE_V2_CALIBRATED.withCalibration(false),
        )
        assertEquals(
            SibionicsAlgorithmSelection.ADAPTIVE_V2_CALIBRATED,
            SibionicsAlgorithmSelection.STOCK_CALIBRATED
                .withModel(SibionicsCustomAlgorithmModel.ADAPTIVE_V2),
        )
        assertEquals(
            SibionicsAlgorithmSelection.STATE_MODEL,
            SibionicsAlgorithmSelection.ADAPTIVE_V2.withModel(SibionicsCustomAlgorithmModel.STATE_MODEL),
        )
    }

    @Test
    fun theDefaultSelectionIsUnchanged() {
        assertEquals(SibionicsAlgorithmSelection.STOCK_CALIBRATED, SibionicsAlgorithmSelection.DEFAULT)
        assertFalse(SibionicsAlgorithmSelection.DEFAULT.customModelEnabled)
    }

    @Test
    fun v2ProducesAValueAndAnUncertaintyOnceTheChemicalSignalExists() {
        val context = context(SibionicsAlgorithmSelection.ADAPTIVE_V2)
        val output = context.run(200)

        assertTrue("output=$output", output.isFinite() && output > 0f)
        val estimate = context.latestProbabilisticEstimate()
        assertNotNull(estimate)
        assertTrue(estimate!!.isUsable)
        val uncertainty = context.latestUncertaintyMmol()
        assertNotNull(uncertainty)
        assertTrue(uncertainty!!.isUsable)
        assertTrue("lower=${uncertainty.lower} output=$output", uncertainty.lower <= output + 0.05f)
        assertTrue("upper=${uncertainty.upper} output=$output", uncertainty.upper >= output - 0.05f)
        assertEquals(0.9f, uncertainty.intervalMass, 0.0001f)
    }

    @Test
    fun everyOtherModelReportsNoUncertainty() {
        listOf(
            SibionicsAlgorithmSelection.STOCK,
            SibionicsAlgorithmSelection.STOCK_CALIBRATED,
            SibionicsAlgorithmSelection.STATE_MODEL,
            SibionicsAlgorithmSelection.BALANCED_TRACKER,
            SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR,
        ).forEach { selection ->
            val context = context(selection)
            context.run(200)
            assertNull("selection=$selection", context.latestUncertaintyMmol())
            assertNull("selection=$selection", context.latestProbabilisticEstimate())
            assertTrue(
                "selection=$selection",
                context.probabilityBelowMmol(3.9f).isNaN(),
            )
        }
    }

    @Test
    fun v2DoesNotChangeStockOutput() {
        val stock = context(SibionicsAlgorithmSelection.STOCK)
        val v2 = context(SibionicsAlgorithmSelection.ADAPTIVE_V2)
        repeat(200) { offset ->
            val index = offset + 1
            val stockValue = stock.processStock(6f, 34f, index, SibionicsAlgorithmMode.REPLAY)
            val v2StockValue = v2.processStock(6f, 34f, index, SibionicsAlgorithmMode.REPLAY)
            assertEquals("index=$index", stockValue, v2StockValue, 0f)
        }
    }

    @Test
    fun v2SnapshotContinuesDeterministicallyThroughTheWrapper() {
        val original = context(SibionicsAlgorithmSelection.ADAPTIVE_V2)
        original.run(200)

        val restored = context(SibionicsAlgorithmSelection.ADAPTIVE_V2)
        assertTrue(restored.restore(original.snapshot()))

        val index = 201
        val expected = original.process(
            6.4f, 34f, index, SibionicsAlgorithmMode.REPLAY,
            impedance = 2_900f, eventTimeMs = index * 60_000L,
        )
        val actual = restored.process(
            6.4f, 34f, index, SibionicsAlgorithmMode.REPLAY,
            impedance = 2_900f, eventTimeMs = index * 60_000L,
        )

        assertEquals(expected, actual, 0f)
        assertEquals(
            original.latestProbabilisticEstimate(),
            restored.latestProbabilisticEstimate(),
        )
    }

    @Test
    fun switchingAwayFromV2ClearsItsPosterior() {
        val context = context(SibionicsAlgorithmSelection.ADAPTIVE_V2)
        context.run(200)
        assertNotNull(context.latestProbabilisticEstimate())

        context.setSelection(SibionicsAlgorithmSelection.STOCK)
        context.processStock(6f, 34f, 201, SibionicsAlgorithmMode.REPLAY)
        context.processPreparedMeasurement(
            stockMmol = 6f,
            measurementMmol = 6f,
            rawMmol = 6f,
            temperatureC = 34f,
            index = 201,
            impedance = 2_900f,
            eventTimeMs = 201 * 60_000L,
        )

        assertNull(context.latestProbabilisticEstimate())
        assertNull(context.latestUncertaintyMmol())
    }

    @Test
    fun v2ReportsLowThresholdProbabilityForFutureAlarmWork() {
        val context = context(SibionicsAlgorithmSelection.ADAPTIVE_V2)
        context.run(200)
        val probability = context.probabilityBelowMmol(3.9f)

        assertTrue("probability=$probability", probability.isFinite())
        assertTrue("probability=$probability", probability in 0f..1f)
    }

    @Test
    fun uncalibratedV2IgnoresAnchorsJustLikeUncalibratedStock() {
        val anchors = listOf(SibionicsCalibrationAnchor(6f, 9f, 60_000L))
        fun finalValue(selection: SibionicsAlgorithmSelection): Float {
            val context = context(selection)
            var output = Float.NaN
            repeat(200) { offset ->
                val index = offset + 1
                output = context.process(
                    rawMmol = 6f,
                    temperatureC = 34f,
                    index = index,
                    mode = SibionicsAlgorithmMode.REPLAY,
                    impedance = 2_900f,
                    eventTimeMs = index * 60_000L,
                    calibrationAnchors = anchors,
                )
            }
            return output
        }

        val uncalibrated = finalValue(SibionicsAlgorithmSelection.ADAPTIVE_V2)
        val calibrated = finalValue(SibionicsAlgorithmSelection.ADAPTIVE_V2_CALIBRATED)

        // Selecting V2 must not implicitly turn calibration on.
        assertTrue("uncalibrated=$uncalibrated", uncalibrated < 7f)
        assertTrue(
            "uncalibrated=$uncalibrated calibrated=$calibrated",
            calibrated > uncalibrated,
        )
    }
}
