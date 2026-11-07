package tk.glucodata.data.calibration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity

/**
 * A journal-derived calibration has to stay gone once the user removes it. Its
 * entry is still in the journal, and opening the dashboard reconciles the table
 * against the journal, so the point used to return within seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CalibrationArchiveTestApplication::class,
    shadows = [MirrorCalibrationTransferNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class JournalCalibrationDismissalTests {
    private val sensor = "OTTAI-1"
    private val now = 1_700_000_000_000L

    private fun minutesAgo(minutes: Long) = now - minutes * 60_000L

    private val first = JournalCalibrationPolicy.JournalBloodGlucose(1L, minutesAgo(10), 120f)
    private val second = JournalCalibrationPolicy.JournalBloodGlucose(2L, minutesAgo(40), 130f)

    private val history = (0..24).map { step ->
        JournalCalibrationPolicy.SensorLanes(minutesAgo(step * 5L), 100f, 96f)
    }

    private val rows get() = CalibrationManager.getCachedCalibrations()
    private fun row(entryId: Long) = rows.single { it.journalEntryId == entryId }

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        Applic.unit = 0
        SensorIdentity.invalidateCaches()
        CalibrationManager.init(context)
        CalibrationManager.clearAll()
        setFeature(context, enabled = true)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
    }

    /** Straight to preferences: the public setter would also start a real sync. */
    private fun setFeature(context: Context, enabled: Boolean) {
        context.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("calibration_from_journal_bg", enabled).commit()
        CalibrationManager.init(context)
    }

    /** One reconciliation pass, as JournalCalibrationSync runs it. */
    private suspend fun sync(vararg entries: JournalCalibrationPolicy.JournalBloodGlucose) {
        val plan = JournalCalibrationPolicy.plan(
            entries = entries.toList(),
            history = history,
            existing = rows,
            sensorId = sensor,
            isMmol = false,
            nowMillis = now,
            matchesSensor = CalibrationManager::calibrationMatchesSensor,
            dismissedTimestamps = CalibrationManager.dismissedJournalTimestamps(0L),
        )
        CalibrationManager.applyJournalCalibrationPlan(plan, "test")
    }

    @Test
    fun clearAllStaysClearedAcrossTheNextSync() = runBlocking {
        sync(first, second)
        assertEquals(2, rows.size)

        CalibrationManager.clearAll()
        sync(first, second)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun deletingOnePointKeepsTheOthersAndStaysDeleted() = runBlocking {
        sync(first, second)

        CalibrationManager.deleteCalibration(row(1L))
        sync(first, second)

        assertEquals(listOf(2L), rows.map { it.journalEntryId })
    }

    @Test
    fun aRemovedPointStaysGoneAfterARestart() = runBlocking {
        sync(first)
        CalibrationManager.deleteCalibration(row(1L))

        CalibrationManager.init(Applic.app)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        sync(first)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun anEntryLoggedAfterClearingStillBecomesAPoint() = runBlocking {
        sync(first)
        CalibrationManager.clearAll()

        sync(first, second)

        assertEquals(listOf(2L), rows.map { it.journalEntryId })
    }

    @Test
    fun undoBringsAPointBackAsJournalDerivedAndItStays() = runBlocking {
        sync(first)
        val removed = row(1L)
        CalibrationManager.deleteCalibration(removed)

        CalibrationManager.restoreCalibration(removed)
        sync(first)

        assertEquals(removed, rows.single())
    }

    @Test
    fun undoOfClearAllBringsEveryPointBackAndTheyStay() = runBlocking {
        sync(first, second)
        val removed = rows
        CalibrationManager.clearAll()

        CalibrationManager.restoreAll(removed)
        sync(first, second)

        assertEquals(removed.toSet(), rows.toSet())
    }

    @Test
    fun undoingAManualPointKeepsARemovalMadeAtTheSameMoment() = runBlocking {
        sync(first)
        CalibrationManager.deleteCalibration(row(1L))
        // A corrected point keeps its entry's exact time, here on the other sensor.
        CalibrationManager.addCalibration(first.timestamp, 100f, 96f, 118f, sensorId = "OTTAI-2")
        val manual = rows.single()

        CalibrationManager.deleteCalibration(manual)
        CalibrationManager.restoreCalibration(manual)
        sync(first)

        assertEquals(manual, rows.single())
    }

    @Test
    fun undoOfClearAllKeepsARemovalMadeAtTheSameMoment() = runBlocking {
        sync(first)
        CalibrationManager.deleteCalibration(row(1L))
        CalibrationManager.addCalibration(first.timestamp, 100f, 96f, 118f, sensorId = "OTTAI-2")
        val manual = rows.single()

        CalibrationManager.clearAll()
        CalibrationManager.restoreAll(listOf(manual))
        sync(first)

        assertEquals(manual, rows.single())
    }

    @Test
    fun removingAManualPointDoesNotLetTheEntryBesideItTakeOver() = runBlocking {
        CalibrationManager.addCalibration(first.timestamp + 20_000L, 100f, 96f, 118f, sensorId = sensor)
        sync(first)
        assertNull(rows.single().journalEntryId)

        CalibrationManager.deleteCalibration(rows.single())
        sync(first)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun movingACorrectedPointDoesNotLeaveADuplicateBehind() = runBlocking {
        sync(first)
        val moved = row(1L).copy(timestamp = minutesAgo(30), userValue = 125f, journalEntryId = null)

        CalibrationManager.updateCalibration(moved)
        sync(first)

        assertEquals(moved, rows.single())
    }

    @Test
    fun switchingTheFeatureOffForgetsWhatWasRemoved() = runBlocking {
        sync(first)
        CalibrationManager.deleteCalibration(row(1L))

        CalibrationManager.purgeJournalCalibrations()
        sync(first)

        assertEquals(listOf(1L), rows.map { it.journalEntryId })
    }

    @Test
    fun nothingIsRecordedWhileTheFeatureIsOff() = runBlocking {
        setFeature(Applic.app, enabled = false)
        CalibrationManager.addCalibration(first.timestamp, 100f, 96f, 118f, sensorId = sensor)

        CalibrationManager.deleteCalibration(rows.single())

        assertTrue(CalibrationManager.dismissedJournalTimestamps(0L).isEmpty())
    }

    @Test
    fun momentsPastTheLookbackAreDropped() = runBlocking {
        sync(first, second)
        CalibrationManager.clearAll()

        assertEquals(setOf(first.timestamp), CalibrationManager.dismissedJournalTimestamps(minutesAgo(20)))
        assertEquals(setOf(first.timestamp), CalibrationManager.dismissedJournalTimestamps(0L))
    }
}
