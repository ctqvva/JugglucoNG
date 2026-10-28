package tk.glucodata.data.calibration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.CalibrationProfileAccess
import tk.glucodata.SensorIdentity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CalibrationArchiveTestApplication::class,
    shadows = [MirrorCalibrationTransferNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalibrationProfileJournalOverlapTests {
    private val sensor = "SIBI:0683013AQT9"
    private val alias = "0683013AQT9"
    private val time = 1_000_000L
    private val dao get() = CalibrationDatabase.getInstance(Applic.app).calibrationDao()

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        Applic.unit = 0
        SensorIdentity.invalidateCaches()
        CalibrationManager.init(context)
        CalibrationManager.clearAll()
        context.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationManager.init(context)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        CalibrationProfileAccess.register(MobileCalibrationProfileBridge)
    }

    private fun journal(entry: Long, offset: Long = 0, serial: String = sensor) = CalibrationEntity(
        timestamp = time + offset, sensorId = serial, sensorValue = 100f,
        sensorValueRaw = 90f, userValue = 120f, journalEntryId = entry,
    )

    private fun profile(raw: Boolean = false, enabled: Boolean = true): String = JSONObject()
        .put("sensorId", alias).put("revision", time + 1).put("units", "mg/dL")
        .put("calibrations", JSONArray().put(JSONObject()
            .put("timestamp", time).put("sensorValue", 100).put("sensorValueRaw", 90)
            .put("userValue", 125).put("isRawMode", raw).put("isEnabled", enabled)))
        .toString()

    private suspend fun assertReplacement(raw: Boolean, enabled: Boolean = true) {
        dao.insertAll(listOf(
            journal(1, -60_000), journal(2, 0), journal(3, 60_000),
            journal(4, -60_001), journal(5, 60_001), journal(6, serial = "OTHER-SENSOR"),
        ))
        CalibrationManager.loadCalibrations()

        CalibrationProfileAccess.importMirrorProfileFromJson(profile(raw, enabled), alias)

        val stored = dao.getAllSync()
        assertEquals(setOf(4L, 5L, 6L), stored.mapNotNull { it.journalEntryId }.toSet())
        val manual = stored.single { it.journalEntryId == null }
        assertEquals(raw, manual.isRawMode)
        assertEquals(enabled, manual.isEnabled)
        assertEquals(125f, manual.userValue, 0f)
        assertEquals(stored, CalibrationManager.calibrations.value)
        // Integrated algorithms consume these triples, so the overlap must not
        // survive as an extra anchor in either calibration lane.
        assertEquals(if (enabled) 3 else 2,
            CalibrationManager.getActiveCalibrationAnchors(sensor, raw).size / 3)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        assertEquals(if (enabled) 3 else 2,
            CalibrationManager.getActiveCalibrationAnchors(alias, raw).size / 3)
    }

    @Test fun autoImportKeepsOneAnchorPerFingerstickAndPreservesUnrelatedJournalPoints() = runBlocking {
        assertReplacement(raw = false)
    }

    @Test fun rawImportUsesTheSameManualPrecedenceAndInclusiveSixtySecondBoundary() = runBlocking {
        assertReplacement(raw = true)
    }

    @Test fun disabledManualPointStillTakesPrecedenceAsInJournalReconciliation() = runBlocking {
        assertReplacement(raw = false, enabled = false)
    }

    @Test fun mergingAProfileAlsoPrefersTheManualPointOverAnIdenticalJournalPoint() = runBlocking {
        dao.insert(journal(1).copy(userValue = 125f))
        CalibrationManager.loadCalibrations()

        CalibrationManager.importProfileFromJson(profile(), replaceExisting = false, overrideSensorId = sensor)

        val stored = dao.getAllSync()
        assertEquals(1, stored.size)
        assertNull(stored.single().journalEntryId)
        assertEquals(125f, stored.single().userValue, 0f)
    }

    @Test fun aStaleMirrorProfileDoesNotPruneJournalPointsForAnImportThatWasRejected() = runBlocking {
        CalibrationManager.addCalibration(time + 120_000, 100f, 90f, 130f, sensorId = sensor)
        dao.insert(journal(1))
        CalibrationManager.loadCalibrations()

        CalibrationProfileAccess.importMirrorProfileFromJson(profile(), sensor)

        assertEquals(2, dao.getAllSync().size)
        assertEquals(1L, dao.getAllSync().single { it.journalEntryId != null }.journalEntryId)
    }

    @Test fun aJournalPlanCreatedBeforeImportCannotReinsertTheOverlappingPoint() = runBlocking {
        val oldPlan = JournalCalibrationPolicy.Plan(inserts = listOf(journal(1)))
        CalibrationProfileAccess.importMirrorProfileFromJson(profile(), sensor)

        CalibrationManager.applyJournalCalibrationPlan(oldPlan, "planned before mirror import")

        assertEquals(1, dao.getAllSync().size)
        assertNull(dao.getAllSync().single().journalEntryId)
        assertEquals(1, CalibrationManager.getActiveCalibrationAnchors(sensor, false).size / 3)
    }
}
