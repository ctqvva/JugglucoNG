package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQBootstrapSeedTests {
    @Test fun qrSensitivityUsesVendorTransmitterScaleAndTruncation() {
        assertEquals(29.3f, MQBootstrapSeed.normalizeSensitivity(2.93f, 1)!!, 0f)
        assertEquals(29.3f, MQBootstrapSeed.normalizeSensitivity(2.939f, 1)!!, 0f)
        assertEquals(2.939f, MQBootstrapSeed.normalizeSensitivity(2.939f, 0)!!, 0f)
    }

    @Test fun missingOrInvalidMetadataCannotBecomeACalibrationSeed() {
        assertNull(MQBootstrapSeed.normalizeSensitivity(2.93f, null))
        assertNull(MQBootstrapSeed.normalizeSensitivity(2.93f, 2))
        for (raw in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(MQBootstrapSeed.normalizeSensitivity(raw, 1))
        }
    }

    @Test fun firstReferenceUsesVendorInitializationInsteadOfSmoothingAgainstZero() {
        // Service startup arguments: [1, packet, current, 0, previous, sensitivity, 0, 2, 0, 720, 1].
        // Packet/current from the 2026-09-13 trace; scale here is explicitly the 10x configuration.
        val seed = MQBootstrapSeed.initialReference(1, 24, 175, 0.0, 29.3, 720, 1.0)!!
        assertEquals(173.0, seed.reviseCurrent2, 0.0)
        assertEquals(59, seed.glucoseTimes10Mmol)
        val calibrated = MQAlgorithm.calculateResult(1, 24.0, 24.0, 175.0, 0.0,
            0.0, seed.glucoseTimes10Mmol.toDouble(), 0.0, 720.0, 1.0)
        assertEquals(29.32, calibrated.kValue, 0.0)
        assertEquals(59, calibrated.glucoseTimes10Mmol)
        assertEquals(5.9, calibrated.glucoseMmol, 0.0)
        assertEquals((5.9 * MQConstants.MMOL_TO_MGDL).toFloat(), calibrated.mgdl, 0.001f)
    }

    @Test fun noReferenceIsInventedDuringWarmupOrWithoutCurrent() {
        assertNull(MQBootstrapSeed.initialReference(1, 18, 175, 0.0, 29.3, 720, 1.0))
        assertNull(MQBootstrapSeed.initialReference(1, 24, 0, 0.0, 29.3, 720, 1.0))
        assertNull(MQBootstrapSeed.initialReference(1, 24, -1, 0.0, 29.3, 720, 1.0))
        assertNull(MQBootstrapSeed.initialReference(1, 24, 175, 0.0, Double.NaN, 720, 1.0))
        assertNotNull(MQBootstrapSeed.initialReference(1, 19, 175, 0.0, 29.3, 720, 1.0))
    }
}
