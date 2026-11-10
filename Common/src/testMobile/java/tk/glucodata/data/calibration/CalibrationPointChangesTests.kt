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

    @Test fun removingOneOfTwoIdenticalPointsOnASensorIsARemoval() {
        val twin = point(id = 2)
        assertEquals(listOf(twin), CalibrationPointChanges.between(listOf(point(), twin), listOf(point())).gone)
    }

    @Test fun addingAPointIdenticalToOneOnTheSensorIsAnArrival() {
        val twin = point(id = 2)
        assertEquals(listOf(twin), CalibrationPointChanges.between(listOf(point()), listOf(point(), twin)).arrived)
    }

    @Test fun switchingAPointOffOrOnIsNotARemoval() {
        val disabled = point(enabled = false)
        val off = CalibrationPointChanges.between(listOf(point()), listOf(disabled))
        assertEquals(CalibrationPointChanges.Changes(emptyList(), emptyList(), listOf(disabled)), off)
        val on = CalibrationPointChanges.between(listOf(disabled), listOf(point()))
        assertEquals(CalibrationPointChanges.Changes(emptyList(), emptyList(), listOf(point())), on)
    }

    @Test fun aPointLeftInTheSamePositionIsNotSwitched() {
        assertTrue(CalibrationPointChanges.between(listOf(point(id = 1)), listOf(point(id = 9))).switched.isEmpty())
        val other = point(id = 2, timestamp = 2_000L)
        assertEquals(listOf(point(enabled = false)),
            CalibrationPointChanges.between(listOf(point(), other), listOf(point(enabled = false), other)).switched)
    }

    @Test fun anEditIsNotASwitchEvenWhenItAlsoTurnsThePointOff() {
        val edited = point(user = 130f, enabled = false)
        assertTrue(CalibrationPointChanges.between(listOf(point()), listOf(edited)).switched.isEmpty())
    }

    @Test fun whenAPointWasOffIsReadFromItsSwitches() {
        val switches = listOf(100L, 200L, 300L)
        assertFalse(CalibrationPointSwitches.isOffAt(switches, 99L))
        assertTrue(CalibrationPointSwitches.isOffAt(switches, 100L))
        assertTrue(CalibrationPointSwitches.isOffAt(switches, 199L))
        assertFalse(CalibrationPointSwitches.isOffAt(switches, 200L))
        assertFalse(CalibrationPointSwitches.isOffAt(switches, 299L))
        assertTrue(CalibrationPointSwitches.isOffAt(switches, 300L))
        assertFalse(CalibrationPointSwitches.isOffAt(emptyList(), 100L))
    }

    @Test fun onlyARealChangeOfPositionIsAdded() {
        assertEquals(listOf(100L), CalibrationPointSwitches.thenSwitched(emptyList(), off = true, at = 100L))
        assertEquals(listOf(100L, 200L), CalibrationPointSwitches.thenSwitched(listOf(100L), off = false, at = 200L))
        assertEquals(listOf(100L), CalibrationPointSwitches.thenSwitched(listOf(100L), off = true, at = 200L))
        // Off before anything was noted: on must not start the list as if it were off.
        assertEquals(emptyList<Long>(), CalibrationPointSwitches.thenSwitched(emptyList(), off = false, at = 100L))
    }

    @Test fun switchesSurviveBeingStored() {
        val switches = listOf(1_793_972_580_000L, 1_793_972_590_000L)
        assertEquals(switches, CalibrationPointSwitches.parse(CalibrationPointSwitches.encode(switches)))
        assertEquals(emptyList<Long>(), CalibrationPointSwitches.parse(null))
        assertEquals(emptyList<Long>(), CalibrationPointSwitches.parse(CalibrationPointSwitches.encode(emptyList())))
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
        val inMmol = point(user = 7.0f, unit = 1).copy(sensorValue = 5.5f, sensorValueRaw = 5.0f)
        val k = tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL
        fun CalibrationEntity.scaled(f: Float, unit: Int) = copy(sensorValue = sensorValue * f,
            sensorValueRaw = sensorValueRaw * f, userValue = userValue * f, valueUnit = unit)
        val inMgdl = inMmol.scaled(k, 0)
        val backAgain = inMgdl.scaled(1f / k, 1)
        assertFalse("the floats do not survive the trip", backAgain.userValue == inMmol.userValue &&
            backAgain.sensorValue == inMmol.sensorValue)
        assertTrue(CalibrationPointChanges.between(listOf(inMmol), listOf(inMgdl)).isEmpty)
        assertTrue(CalibrationPointChanges.between(listOf(inMmol), listOf(backAgain)).isEmpty)
        assertEquals(CalibrationPointChanges.identity(inMmol), CalibrationPointChanges.identity(backAgain))
    }

    @Test fun pointsAtTheSameMomentAreToldApartByTheirValue() {
        assertEquals(CalibrationPointChanges.identity(point(id = 1)), CalibrationPointChanges.identity(point(id = 2)))
        assertFalse(CalibrationPointChanges.identity(point()) == CalibrationPointChanges.identity(point(user = 150f)))
        assertFalse(CalibrationPointChanges.identity(point()) ==
            CalibrationPointChanges.identity(point().copy(isRawMode = true)))
    }
}
