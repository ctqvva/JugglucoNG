package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A calibration row stores the raw value that was on screen next to the auto one. A driver without
 * a raw lane reports that lane as `NaN` (iCan's `resolveRawLaneValue` returns `NaN` on every path),
 * and SQLite keeps a non-finite REAL as NULL while the column is NOT NULL — so an un-sanitised NaN
 * would make the insert fail instead of recording the calibration.
 */
class CalibrationInputSanitizationTests {

    @Test
    fun finiteRawValueIsStoredUnchanged() {
        assertEquals(5.5f, CalibrationMath.sensorValueRawForStorage(5.5f), 0.0001f)
        assertEquals(120f, CalibrationMath.sensorValueRawForStorage(120f), 0.0001f)
    }

    @Test
    fun missingRawLaneBecomesZero() {
        // What iCan's resolveRawLaneValue returns.
        assertEquals(0f, CalibrationMath.sensorValueRawForStorage(Float.NaN), 0f)
    }

    @Test
    fun nonFiniteRawValuesBecomeZero() {
        assertEquals(0f, CalibrationMath.sensorValueRawForStorage(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, CalibrationMath.sensorValueRawForStorage(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun nonPositiveRawValuesBecomeZero() {
        assertEquals(0f, CalibrationMath.sensorValueRawForStorage(0f), 0f)
        assertEquals(0f, CalibrationMath.sensorValueRawForStorage(-1f), 0f)
    }
}
