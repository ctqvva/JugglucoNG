package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun switchingAPointOffOrOnIsNotARemoval() {
        val disabled = point(enabled = false)
        assertTrue(CalibrationPointChanges.between(listOf(point()), listOf(disabled)).isEmpty)
        assertTrue(CalibrationPointChanges.between(listOf(disabled), listOf(point())).isEmpty)
    }

    @Test fun makingAJournalPointTheUsersOwnChangesItsLanes() {
        val journal = point().copy(journalEntryId = 7L)
        assertEquals(listOf(journal), CalibrationPointChanges.between(listOf(journal), listOf(point())).gone)
    }

    @Test fun theJournalEntryAPointCameFromDoesNotMatter() {
        val journal = point().copy(journalEntryId = 7L)
        assertTrue(CalibrationPointChanges.between(listOf(journal), listOf(journal.copy(journalEntryId = 8L))).isEmpty)
    }

    @Test fun aNewStockValueMovesThePointInAnIntegratingFit() {
        val restocked = point().copy(sensorValueStock = 95f)
        assertEquals(listOf(point()), CalibrationPointChanges.between(listOf(point()), listOf(restocked)).gone)
    }

    @Test fun removingAPointFromOneSensorWhileAnotherKeepsItIsARemoval() {
        val other = point(id = 2).copy(sensorId = "B")
        assertEquals(listOf(point()), CalibrationPointChanges.between(listOf(point(), other), listOf(other)).gone)
    }

    @Test fun reFilingOneOfTwoSensorsCopiesIsNotARemoval() {
        val other = point(id = 2).copy(sensorId = "B")
        val moved = point().copy(sensorId = "A#archive")
        assertTrue(CalibrationPointChanges.between(listOf(point(), other), listOf(moved, other)).isEmpty)
    }

    @Test fun anOlderReadCannotFollowANewerOne() {
        val order = CalibrationLoadOrder()
        val older = order.begin()
        val newer = order.begin()
        assertTrue(order.mayApply(newer))
        assertFalse(order.mayApply(older))
        assertTrue(order.mayApply(order.begin()))
    }

    @Test fun readsInOrderAllApply() {
        val order = CalibrationLoadOrder()
        assertTrue(order.mayApply(order.begin()))
        assertTrue(order.mayApply(order.begin()))
    }

    @Test fun aChangeOfDisplayUnitIsNotACalibrationChange() {
        val converted = point(user = 6.66f, unit = 1)
        assertTrue(CalibrationPointChanges.between(listOf(point()), listOf(converted)).isEmpty)
    }
}
