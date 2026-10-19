package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsAlgorithmExtraTests {

    private fun anchor(sensor: Float, reference: Float, timestampMs: Long) =
        SibionicsCalibrationAnchor(sensorMmol = sensor, referenceMmol = reference, timestampMs = timestampMs)

    @Test
    fun observationIsUsableOnlyForFinitePositiveSignals() {
        val usable = SibionicsSensorObservation(
            calibratedMmol = 7f,
            chemicalMmol = 7f,
            sensorStateCompensationMmol = 0f,
            qualityFlags = 0,
            factorySensitivity = 1.27f,
            activeSensitivity = 1.27f,
            sensorAgeMinutes = 10,
            family = 115,
        )
        assertTrue(usable.isUsable)
        assertFalse(usable.copy(calibratedMmol = Float.NaN).isUsable)
        assertFalse(usable.copy(calibratedMmol = 0f).isUsable)
        assertFalse(usable.copy(activeSensitivity = Float.NaN).isUsable)
        assertFalse(usable.copy(activeSensitivity = -1f).isUsable)
    }

    @Test
    fun onlyAdaptiveV2ProvidesUncertainty() {
        SibionicsCustomAlgorithmModel.entries.forEach { model ->
            assertEquals(model == SibionicsCustomAlgorithmModel.ADAPTIVE_V2, model.providesUncertainty)
        }
    }

    @Test
    fun unknownStorageSelectionFallsBackToStock() {
        assertEquals(SibionicsAlgorithmSelection.STOCK, SibionicsAlgorithmSelection.fromStorage(99))
        assertEquals(SibionicsAlgorithmSelection.STOCK, SibionicsAlgorithmSelection.fromStorage(-1))
        assertEquals(SibionicsAlgorithmSelection.STOCK, SibionicsAlgorithmSelection.fromStorage(10))
        assertEquals(SibionicsAlgorithmSelection.STOCK, SibionicsAlgorithmSelection.fromStorage(0))
        assertEquals(SibionicsCustomAlgorithmModel.STOCK, SibionicsCustomAlgorithmModel.fromStorage(999))
    }

    @Test
    fun integratedCalibrationReturnsStockWhenNoUsableAnchors() {
        val balanced = SibionicsBalancedAlgorithmContext()
        assertEquals(6.0f, balanced.applyIntegratedCalibration(6f, 0L, emptyList()), 0.0001f)
        assertEquals(6.0f, balanced.applyIntegratedCalibration(6f, 0L, listOf(anchor(0f, 7f, 0L))), 0.0001f)

        val responsive = SibionicsResponsiveAlgorithmContext()
        assertEquals(6.0f, responsive.applyIntegratedCalibration(6f, 0L, emptyList()), 0.0001f)

        val adaptive = SibionicsAdaptiveAlgorithmContext()
        assertEquals(6.0f, adaptive.applyIntegratedCalibration(6f, 0L, emptyList()), 0.0001f)
    }

    @Test
    fun integratedCalibrationAppliesSingleAnchorWithConfidence() {
        val expected = 6f + 1f * 0.82f
        assertEquals(expected, SibionicsBalancedAlgorithmContext().applyIntegratedCalibration(6f, 0L, listOf(anchor(6f, 7f, 0L))), 0.0001f)
        assertEquals(expected, SibionicsResponsiveAlgorithmContext().applyIntegratedCalibration(6f, 0L, listOf(anchor(6f, 7f, 0L))), 0.0001f)
        assertEquals(expected, SibionicsAdaptiveAlgorithmContext().applyIntegratedCalibration(6f, 0L, listOf(anchor(6f, 7f, 0L))), 0.0001f)
    }

    @Test
    fun integratedCalibrationClampsSingleAnchorCorrection() {
        // reference - sensor = 9 mmol/L, clamped to MAX_CALIBRATION_OFFSET (3).
        val expected = 6f + 3f * 0.82f
        assertEquals(expected, SibionicsBalancedAlgorithmContext().applyIntegratedCalibration(6f, 0L, listOf(anchor(1f, 10f, 0L))), 0.0001f)
    }

    @Test
    fun integratedCalibrationDropsFutureAnchors() {
        val eventTimeMs = 1_000L
        val tooFarFuture = anchor(6f, 7f, eventTimeMs + 60_001L)
        assertEquals(6.0f, SibionicsBalancedAlgorithmContext().applyIntegratedCalibration(6f, eventTimeMs, listOf(tooFarFuture)), 0.0001f)
        val boundary = anchor(6f, 7f, eventTimeMs + 60_000L)
        assertEquals(6.82f, SibionicsBalancedAlgorithmContext().applyIntegratedCalibration(6f, eventTimeMs, listOf(boundary)), 0.0001f)
    }

    @Test
    fun balancedProcessRejectsInvalidInputAndInitialises() {
        val ctx = SibionicsBalancedAlgorithmContext()
        ctx.configure(1.27f)
        assertTrue(ctx.process(Float.NaN, 6f, 32f, 1000f, 1, 0L, emptyList()).isNaN())
        assertTrue(ctx.process(0f, 6f, 32f, 1000f, 1, 0L, emptyList()).isNaN())
        assertEquals(7.0f, ctx.process(7f, 7f, 32f, 1000f, 1, 0L, emptyList()), 0.0001f)
        assertEquals(1, ctx.continuationIndex())
    }

    @Test
    fun balancedSnapshotRoundTripsAndRejectsMismatches() {
        val source = SibionicsBalancedAlgorithmContext()
        source.configure(1.27f)
        source.process(7f, 7f, 32f, 1000f, 1, 0L, emptyList())
        val snapshot = source.snapshot()

        val same = SibionicsBalancedAlgorithmContext()
        same.configure(1.27f)
        assertTrue(same.restore(snapshot))
        assertEquals(source.continuationIndex(), same.continuationIndex())

        val wrongSensitivity = SibionicsBalancedAlgorithmContext()
        wrongSensitivity.configure(1.5f)
        assertFalse(wrongSensitivity.restore(snapshot))

        assertFalse(SibionicsBalancedAlgorithmContext().restore(null))
        assertFalse(SibionicsBalancedAlgorithmContext().restore(ByteArray(0)))
        val corrupted = snapshot.copyOf()
        corrupted[0] = (corrupted[0] + 1).toByte()
        assertFalse(SibionicsBalancedAlgorithmContext().restore(corrupted))
    }

    @Test
    fun responsiveSnapshotRoundTripsAndCrossRestoreFails() {
        val source = SibionicsResponsiveAlgorithmContext()
        source.configure(1.27f)
        source.process(6f, 6f, 32f, 1000f, 1, 0L, emptyList())
        val snapshot = source.snapshot()

        val same = SibionicsResponsiveAlgorithmContext()
        same.configure(1.27f)
        assertTrue(same.restore(snapshot))
        assertEquals(1, same.continuationIndex())

        assertFalse(SibionicsResponsiveAlgorithmContext().restore(null))
        // Same magic, different snapshot version: balanced bytes must not load.
        assertFalse(SibionicsResponsiveAlgorithmContext().restore(SibionicsBalancedAlgorithmContext().snapshot()))
        assertFalse(SibionicsBalancedAlgorithmContext().restore(snapshot))
    }

    @Test
    fun responsiveProcessInitialises() {
        val ctx = SibionicsResponsiveAlgorithmContext()
        ctx.configure(1.27f)
        assertTrue(ctx.process(Float.NaN, 6f, 32f, 1000f, 1, 0L, emptyList()).isNaN())
        assertEquals(6.0f, ctx.process(6f, 6f, 32f, 1000f, 1, 0L, emptyList()), 0.0001f)
        assertEquals(1, ctx.continuationIndex())
    }

    @Test
    fun stockSelectionHasExactContinuationAlways() {
        val ctx = SibionicsAlgorithmContext("SN-TEST")
        ctx.configure(shortCode = "46HU804E", sensitivity = 1.27f, selection = SibionicsAlgorithmSelection.STOCK)
        assertNull(ctx.customContinuationIndex())
        assertTrue(ctx.hasExactContinuation(42))
    }
}
