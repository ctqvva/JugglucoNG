package tk.glucodata.data.calibration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.CalibrationProfileAccess
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity

/** Real Room/profile bridge; JNI is absent, as it is for a Room-only sensor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CalibrationArchiveTestApplication::class,
    shadows = [MirrorCalibrationTransferNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MirrorCalibrationTransferTests {
    private val sensor = "AABBCCDDEEFF"

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        SensorIdentity.invalidateCaches()
        CalibrationManager.init(context)
        CalibrationManager.clearAll()
        context.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationManager.init(context)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        CalibrationProfileAccess.register(MobileCalibrationProfileBridge)
    }

    @Test
    fun roomOnlyManualPointsAreDiscoverableThroughTheNativeBridge() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        CalibrationManager.addCalibration(2_000L, 8f, 7f, 9f, sensorId = sensor, isRawMode = true)

        assertEquals(listOf(sensor), CalibrationProfileAccess.mirrorProfileSensorIds().toList())
        val profile = JSONObject(requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensor)))
        assertEquals(sensor, profile.getString("sensorId"))
        val rows = profile.getJSONArray("calibrations")
        assertEquals(2, rows.length())
        assertTrue(rows.getJSONObject(0).getBoolean("isRawMode"))
        assertEquals(9.0, rows.getJSONObject(0).getDouble("userValue"), 0.0)
        assertFalse(rows.getJSONObject(1).getBoolean("isRawMode"))
    }

    @Test
    fun deletingLastPointStillOffersAnEmptyNewerProfileAfterReload() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        val oldProfile = JSONObject(requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensor)))
        CalibrationManager.deleteCalibration(CalibrationManager.calibrations.value.single())
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.init(Applic.app)

        assertEquals(listOf(sensor), CalibrationProfileAccess.mirrorProfileSensorIds().toList())
        val deleted = JSONObject(requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensor)))
        assertEquals(0, deleted.getJSONArray("calibrations").length())
        assertTrue(deleted.getLong("revision") > oldProfile.getLong("revision"))
    }

    @Test
    fun backgroundDiscoveryLoadsPersistedPointsBeforeTheCalibrationScreenOpens() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)

        assertEquals(listOf(sensor), CalibrationProfileAccess.mirrorProfileSensorIds().toList())
        assertEquals(1, JSONObject(requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensor)))
            .getJSONArray("calibrations").length())
    }

    @Test
    fun journalOnlyRowsAreNotOfferedAsManualProfiles() = runBlocking {
        CalibrationManager.restoreAll(listOf(CalibrationEntity(
            timestamp = 1_000L, sensorId = sensor, sensorValue = 6f, sensorValueRaw = 5f,
            userValue = 7f, journalEntryId = 42L,
        )))
        // restoreAll is a user edit; remove its revision to model journal reconciliation.
        Applic.app.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(CalibrationProfileAccess.mirrorProfileSensorIds().isEmpty())
    }

    @Test
    fun transferredManualProfileDoesNotLoseToOrDeleteNewerLocalJournalPoints() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        val profile = requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensor))
        // Switch to receiver state: only a newer, independently derived journal point.
        CalibrationManager.clearAll()
        Applic.app.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationDatabase.getInstance(Applic.app).calibrationDao().insert(CalibrationEntity(
            timestamp = System.currentTimeMillis() + 60_000L, sensorId = sensor,
            sensorValue = 8f, sensorValueRaw = 7f, userValue = 9f, journalEntryId = 42L,
        ))
        CalibrationManager.loadCalibrations()

        CalibrationProfileAccess.importMirrorProfileFromJson(profile, sensor)

        val rows = CalibrationManager.calibrations.value
        assertEquals(2, rows.size)
        assertEquals(7f, rows.single { it.journalEntryId == null }.userValue, 0f)
        assertEquals(9f, rows.single { it.journalEntryId == 42L }.userValue, 0f)
        assertEquals(JSONObject(profile).getLong("revision"), JSONObject(requireNotNull(
            CalibrationProfileAccess.exportProfileForSensorAsJson(sensor))).getLong("revision"))
    }

    @Test
    fun importedDeletionClearsManualPointsAndPreservesJournalPoints() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        val revision = JSONObject(requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensor)))
            .getLong("revision")
        CalibrationDatabase.getInstance(Applic.app).calibrationDao().insert(CalibrationEntity(
            timestamp = 2_000L, sensorId = sensor, sensorValue = 8f, sensorValueRaw = 7f,
            userValue = 9f, journalEntryId = 42L,
        ))
        CalibrationManager.loadCalibrations()
        val deletion = JSONObject().put("sensorId", sensor).put("revision", revision + 1)
            .put("calibrations", org.json.JSONArray()).toString()

        CalibrationProfileAccess.importMirrorProfileFromJson(deletion, sensor)

        assertEquals(42L, CalibrationManager.calibrations.value.single().journalEntryId)
    }

    @Test
    fun staleAndDuplicateTransfersCannotOverwriteANewerLocalManualEdit() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        val profile = requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensor))
        CalibrationProfileAccess.importMirrorProfileFromJson(profile, sensor)
        assertEquals(1, CalibrationManager.calibrations.value.size)
        CalibrationManager.updateCalibration(CalibrationManager.calibrations.value.single().copy(userValue = 8f))

        CalibrationProfileAccess.importMirrorProfileFromJson(profile, sensor)

        assertEquals(8f, CalibrationManager.calibrations.value.single().userValue, 0f)
    }

    @Test
    fun canonicalAndNativeShortNamesShareRevisionAndReplacement() = runBlocking {
        val short = "3MH01HD0P14"
        val full = "E007-$short"
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = full)
        val fullProfile = JSONObject(requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(full)))
        val aliasProfile = JSONObject(requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(short)))
        assertEquals(fullProfile.getLong("revision"), aliasProfile.getLong("revision"))
        fullProfile.put("revision", fullProfile.getLong("revision") + 1)

        CalibrationProfileAccess.importMirrorProfileFromJson(fullProfile.toString(), short)

        assertEquals(short, CalibrationManager.calibrations.value.single().sensorId)
        CalibrationManager.deleteCalibration(CalibrationManager.calibrations.value.single())
        CalibrationProfileAccess.importMirrorProfileFromJson(aliasProfile.toString(), full)
        assertTrue("an old alias must not resurrect a deleted calibration", CalibrationManager.calibrations.value.isEmpty())
    }

    @Test
    fun sibionicsProfileKeepsItsNamespaceWithoutAReceiverBleRegistration() = runBlocking {
        val full = "SIBI:0683013AQT9"
        val short = "0683013AQT9"
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = full)
        val profile = requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(full))
        assertEquals(listOf(full), CalibrationProfileAccess.mirrorProfileSensorIds().toList())

        CalibrationManager.clearAll()
        Applic.app.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationProfileAccess.importMirrorProfileFromJson(profile, full)

        assertEquals(full, CalibrationManager.calibrations.value.single().sensorId)
        assertTrue(CalibrationManager.hasCalibrationPointsForMode(false, full))
        assertTrue(CalibrationManager.hasCalibrationPointsForMode(false, short))
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class MirrorCalibrationTransferNatives {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun resolveFullSensorName(id: String): String? =
            when (id) {
                "3MH01HD0P14", "E007-3MH01HD0P14" -> "E007-3MH01HD0P14"
                "0683013AQT9", "SIBI:0683013AQT9" -> "SIBI:0683013AQT9"
                else -> null
            }
    }
}
