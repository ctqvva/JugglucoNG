package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPointChangesTests {
    private fun point(id: Int = 1, timestamp: Long = 1_000L, user: Float = 120f, enabled: Boolean = true, unit: Int = 0) =
        CalibrationEntity(id = id, timestamp = timestamp, sensorId = "A", sensorValue = 100f,
            sensorValueRaw = 90f, userValue = user, isEnabled = enabled, valueUnit = unit)

    @Test fun anAddedPointArrivesAndARemovedOneGoes() {
        val kept = point(id = 1)
        val added = point(id = 2, timestamp = 2_000L)
        assertEquals(listOf(added), CalibrationPointChanges.between(listOf(kept), listOf(kept, added)).arrived)
        assertEquals(listOf(added), CalibrationPointChanges.between(listOf(kept, added), listOf(kept)).gone)
    }

    @Test fun rewritingUnchangedPointsUnderNewIdsIsNotARemoval() {
        assertTrue(CalibrationPointChanges.between(listOf(point(id = 1)), listOf(point(id = 9))).isEmpty)
    }

    @Test fun reFilingAPointUnderAnArchiveIdIsNotARemoval() {
        val moved = point().copy(sensorId = "A#archive")
        assertTrue(CalibrationPointChanges.between(listOf(point()), listOf(moved)).isEmpty)
    }

    @Test fun anEditIsTheOldPointGoingAndANewOneArriving() {
        val edited = point(user = 130f)
        val changes = CalibrationPointChanges.between(listOf(point()), listOf(edited))
        assertEquals(listOf(point()), changes.gone)
        assertEquals(listOf(edited), changes.arrived)
    }

    @Test fun disablingAPointTakesItOutOfForce() {
        val disabled = point(enabled = false)
        assertEquals(listOf(point()), CalibrationPointChanges.between(listOf(point()), listOf(disabled)).gone)
    }

    @Test fun aChangeOfDisplayUnitIsNotACalibrationChange() {
        val converted = point(user = 6.66f, unit = 1)
        assertTrue(CalibrationPointChanges.between(listOf(point()), listOf(converted)).isEmpty)
    }
}
