package tk.glucodata.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity
import tk.glucodata.data.calibration.CalibrationArchiveTestApplication
import tk.glucodata.data.calibration.CalibrationDatabase
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.data.calibration.MirrorCalibrationTransferNatives

/**
 * The 2026-11-06 report, on real Room and the real manager: a calibration added
 * for the past was in force for 19 seconds, the chart presented 691 minutes in
 * that time, and 263 that had no record sealed with its offset. Removing it left
 * them there. The minutes with no AiDex row kept their true value, because a
 * rewrite had released only AiDex's minutes, so the line came out in teeth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CalibrationArchiveTestApplication::class,
    shadows = [MirrorCalibrationTransferNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalibrationRecordReleaseTests {
    private companion object {
        const val OTTAI = "70D07EDCB416"
        const val AIDEX = "X-222227KUFY"
        const val MINUTE = ReadingDisplay.MINUTE_MS
        const val HOUR = 60L * MINUTE
    }

    private lateinit var database: HistoryDatabase
    private lateinit var repository: HistoryRepository
    private val display get() = database.readingDisplayDao()
    private val now = ReadingDisplay.minuteOf(System.currentTimeMillis())

    /** Written by hand, so [recordedAt] can say when: a real write stamps the clock. */
    private fun record(minute: Long, value: Float, recordedAt: Long, serial: String = OTTAI, viewMode: Int = 0) =
        ReadingDisplay(minute, serial, value, viewMode, 1L, recordedAt)

    private suspend fun recorded(): Map<Long, Float> =
        display.getAllSince(0L).associate { it.timestamp to it.displayMgdl }

    private suspend fun addPastCalibration(atMs: Long = now - 8 * HOUR, serial: String = OTTAI, raw: Boolean = false) {
        CalibrationManager.addCalibration(atMs, 100f, 90f, 138f, sensorId = serial, isRawMode = raw)
    }

    private suspend fun removeCalibration(serial: String = OTTAI) {
        CalibrationManager.deleteCalibration(CalibrationManager.calibrations.value.first { it.sensorId == serial })
    }

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        Applic.unit = 0
        MirrorCalibrationTransferNatives.savedUnit = 0
        MirrorCalibrationTransferNatives.onStartThreads = null
        SensorIdentity.invalidateCaches()
        CalibrationManager.init(context)
        CalibrationManager.clearAll()
        context.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationManager.init(context)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        database = HistoryDatabase.getInstance(context)
        repository = HistoryRepository(context)
        display.deleteAll()
    }

    @Test
    fun removingACalibrationTakesBackWhatItSealedAfterTheFact() = runBlocking {
        val seenBefore = now - 7 * HOUR
        val hole = now - 6 * HOUR
        display.sealAll(listOf(record(seenBefore, 80f, recordedAt = now - HOUR)))

        addPastCalibration()
        repository.recordPresentedMinutes(listOf(
            HistoryRepository.PresentedMinute(seenBefore, 118f, OTTAI, 0),
            HistoryRepository.PresentedMinute(hole, 118f, OTTAI, 0),
        ))
        assertEquals(mapOf(seenBefore to 80f, hole to 118f), recorded())

        removeCalibration()

        assertEquals(mapOf(seenBefore to 80f), recorded())
    }

    @Test
    fun theLimitIsThePointsArrivalNotItsTimestamp() = runBlocking {
        // The report: the point sat in the past and its offset reached the
        // minutes after it, which are the ones that sealed.
        val afterThePoint = now - 6 * HOUR
        val beforeThePoint = now - 10 * HOUR
        addPastCalibration(atMs = now - 8 * HOUR)
        val later = System.currentTimeMillis() + MINUTE
        display.sealAll(listOf(record(afterThePoint, 118f, later), record(beforeThePoint, 118f, later)))

        removeCalibration()

        assertEquals(emptyMap<Long, Float>(), recorded())
    }

    @Test
    fun switchingAPointOffLeavesItsRecordsAndRemovingItLaterTakesThem() = runBlocking {
        val hole = now - 6 * HOUR
        addPastCalibration()
        display.sealAll(listOf(record(hole, 118f, recordedAt = System.currentTimeMillis() + MINUTE)))

        val point = CalibrationManager.calibrations.value.single()
        CalibrationManager.updateCalibration(point.copy(isEnabled = false))
        assertEquals(mapOf(hole to 118f), recorded())
        CalibrationManager.updateCalibration(point.copy(isEnabled = true))
        assertEquals(mapOf(hole to 118f), recorded())

        CalibrationManager.deleteCalibration(CalibrationManager.calibrations.value.single())
        assertEquals(emptyMap<Long, Float>(), recorded())
    }

    @Test
    fun twoSensorsSharingAPointEachKeepTheirOwnRecord() = runBlocking {
        val ottaiHole = now - 6 * HOUR
        val aidexHole = now - 5 * HOUR
        val at = now - 8 * HOUR
        addPastCalibration(atMs = at, serial = OTTAI)
        // Recorded before the second sensor got the point: each keeps its own arrival.
        val betweenTheArrivals = System.currentTimeMillis()
        Thread.sleep(5)
        addPastCalibration(atMs = at, serial = AIDEX)
        val later = System.currentTimeMillis() + MINUTE
        display.sealAll(listOf(
            record(ottaiHole, 118f, betweenTheArrivals, serial = OTTAI),
            record(aidexHole, 118f, later, serial = AIDEX),
        ))

        removeCalibration(OTTAI)
        assertEquals(mapOf(aidexHole to 118f), recorded())

        removeCalibration(AIDEX)
        assertEquals(emptyMap<Long, Float>(), recorded())
    }

    @Test
    fun twoPointsAtTheSameMomentDoNotLoseEachOthersArrival() = runBlocking {
        val hole = now - 6 * HOUR
        val at = now - 8 * HOUR
        CalibrationManager.addCalibration(at, 100f, 90f, 138f, sensorId = OTTAI)
        CalibrationManager.addCalibration(at, 100f, 90f, 150f, sensorId = OTTAI)
        val later = System.currentTimeMillis() + MINUTE

        CalibrationManager.deleteCalibration(CalibrationManager.calibrations.value.first { it.userValue == 138f })
        display.sealAll(listOf(record(hole, 118f, later)))

        CalibrationManager.deleteCalibration(CalibrationManager.calibrations.value.single())
        assertEquals(emptyMap<Long, Float>(), recorded())
    }

    @Test
    fun aMinuteLivedThroughUnderTheCalibrationStaysLocked() = runBlocking {
        addPastCalibration()
        val lived = now + 2 * MINUTE
        display.sealAll(listOf(record(lived, 118f, recordedAt = now + 3 * MINUTE)))

        removeCalibration()

        assertEquals(mapOf(lived to 118f), recorded())
    }

    @Test
    fun theOtherSensorsMinutesAreNotTheCalibrationsToTakeBack() = runBlocking {
        val hole = now - 6 * HOUR
        addPastCalibration()
        display.sealAll(listOf(record(hole, 90f, recordedAt = System.currentTimeMillis() + MINUTE, serial = AIDEX)))

        removeCalibration()

        assertEquals(mapOf(hole to 90f), recorded())
    }

    @Test
    fun aCalibrationOfOneLaneLeavesTheOtherLanesRecords() = runBlocking {
        val autoMinute = now - 6 * HOUR
        val rawMinute = now - 5 * HOUR
        addPastCalibration(raw = true)
        val later = System.currentTimeMillis() + MINUTE
        display.sealAll(listOf(
            record(autoMinute, 90f, recordedAt = later, viewMode = 0),
            record(rawMinute, 91f, recordedAt = later, viewMode = 1),
        ))

        removeCalibration()

        assertEquals(mapOf(autoMinute to 90f), recorded())
    }

    @Test
    fun editingACalibrationReleasesWhatItsOldValueWrote() = runBlocking {
        val hole = now - 6 * HOUR
        addPastCalibration()
        display.sealAll(listOf(record(hole, 118f, recordedAt = System.currentTimeMillis() + MINUTE)))

        val point = CalibrationManager.calibrations.value.single()
        CalibrationManager.updateCalibration(point.copy(userValue = 150f))

        assertEquals(emptyMap<Long, Float>(), recorded())
    }

    @Test
    fun aMirrorSyncRewritingThePointsReleasesNothingAndKeepsTheirAge() = runBlocking {
        val hole = now - 6 * HOUR
        addPastCalibration()
        display.sealAll(listOf(record(hole, 118f, recordedAt = System.currentTimeMillis() + MINUTE)))

        val dao = CalibrationDatabase.getInstance(Applic.app).calibrationDao()
        val point = dao.getAllSync().single()
        dao.deleteAll()
        dao.insert(point.copy(id = point.id + 100))
        CalibrationManager.loadCalibrations()
        assertEquals(mapOf(hole to 118f), recorded())

        removeCalibration()
        assertEquals(emptyMap<Long, Float>(), recorded())
    }

    @Test
    fun aPointFromBeforeThisWasTrackedReleasesNothing() = runBlocking {
        val hole = now - 6 * HOUR
        val dao = CalibrationDatabase.getInstance(Applic.app).calibrationDao()
        dao.insert(tk.glucodata.data.calibration.CalibrationEntity(timestamp = now - 8 * HOUR, sensorId = OTTAI,
            sensorValue = 100f, sensorValueRaw = 90f, userValue = 138f, valueUnit = 0))
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        display.sealAll(listOf(record(hole, 118f, recordedAt = now)))

        removeCalibration()

        assertEquals(mapOf(hole to 118f), recorded())
    }

    @Test
    fun theAgeOfAPointSurvivesAProcessRestart() = runBlocking {
        val hole = now - 6 * HOUR
        addPastCalibration()
        display.sealAll(listOf(record(hole, 118f, recordedAt = System.currentTimeMillis() + MINUTE)))

        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        removeCalibration()

        assertEquals(emptyMap<Long, Float>(), recorded())
    }

    @Test
    fun aChartStillDrawingARemovedCalibrationCannotPutItBack() = runBlocking {
        val hole = now - 6 * HOUR
        addPastCalibration()
        val minute = HistoryRepository.PresentedMinute(hole, 118f, OTTAI, 0)
        val drawnUnder = CalibrationManager.revision.value
        assertEquals(1, repository.recordPresentedMinutes(listOf(minute), drawnUnder))

        removeCalibration()

        assertNull(repository.recordPresentedMinutes(listOf(minute), drawnUnder))
        assertEquals(emptyMap<Long, Float>(), recorded())

        val redrawn = minute.copy(displayMgdl = 80f)
        assertEquals(1, repository.recordPresentedMinutes(listOf(redrawn), CalibrationManager.revision.value))
        assertEquals(mapOf(hole to 80f), recorded())
    }
}
