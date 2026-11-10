package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the default contract of [CalibrationProvider] (plan §6 Q1). These defaults are what a
 * flavour without a calibration engine got from the old reflective `CalibrationAccess`, so a
 * change here changes what the watch displays or lets a mutation report success it never applied.
 */
class CalibrationProviderDefaultsTest {
    private object AbsentEngine : CalibrationProvider {
        override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean = false

        override fun getCalibratedValue(
            value: Float,
            timestamp: Long,
            isRawMode: Boolean,
            emitDiagnostics: Boolean,
            sensorId: String?,
        ): Float = value
    }

    @Test
    fun anAbsentEngineLeavesEveryReadAtItsNeutralDefault() {
        val engine = AbsentEngine
        assertFalse(engine.shouldHideInitialWhenCalibrated())
        assertEquals(0, engine.getActiveCalibrationAnchors("sensor", false).size)
        assertFalse(engine.shouldOverwriteSensorValues())
        assertEquals(0L, engine.getRevision())
        assertEquals(0, engine.getIntegratedCalibrationAnchors("sensor", false).size)
        assertTrue(engine.isCalibrationStateLoaded())
        assertEquals(
            tk.glucodata.data.calibration.CalibrationTuning.DEFAULT,
            engine.tuningForMode(false),
        )
        assertEquals(0L, engine.getIntegratedCalibrationFingerprint("sensor", false))
    }

    @Test
    fun anAbsentEngineDoesNotClaimAMutationWasApplied() {
        val engine = AbsentEngine
        assertFalse(engine.setEnabledForMode(false, true, null))
        assertFalse(engine.clearAllBlocking())
        assertFalse(engine.deleteCalibrationAtBlocking(1L))
        assertFalse(engine.updateCalibrationUserValueBlocking(1L, 5f))
        assertFalse(engine.addCalibrationFromWearAtBlocking(1L, 5f, Float.NaN))
    }

    @Test
    fun anAbsentEngineLeavesAnIntegratedSeriesUntouched() {
        val engine = AbsentEngine
        val values = floatArrayOf(1f, 2f)
        val timestamps = longArrayOf(10L, 20L)
        assertTrue(
            engine.getIntegratedCalibratedSeries(values, timestamps, false, null).contentEquals(values),
        )
    }
}
