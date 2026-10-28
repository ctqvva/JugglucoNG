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

    /** Switch the real singleton/Room store between two simulated installations. */
    private suspend fun useDeviceProfile(profile: String, sensorId: String) {
        CalibrationDatabase.getInstance(Applic.app).calibrationDao().deleteAll()
        Applic.app.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationManager.init(Applic.app)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        CalibrationProfileAccess.importMirrorProfileFromJson(profile, sensorId)
    }

    private fun exported(sensorId: String): String =
        requireNotNull(CalibrationProfileAccess.exportProfileForSensorAsJson(sensorId))

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

    @Test
    fun receiverEnteredRawAndAutoPointsReachAnEmptyMasterForEverySensorFamily() = runBlocking {
        for (id in listOf("SIBI:0683013AQT9", "3MH01HD0P14", "X-12345678", "AABBCCDDEEFF")) {
            val emptyMaster = JSONObject().put("sensorId", id).put("revision", 0L)
                .put("calibrations", org.json.JSONArray()).toString()
            useDeviceProfile(emptyMaster, id)
            CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = id)
            CalibrationManager.addCalibration(2_000L, 8f, 7f, 9f, sensorId = id, isRawMode = true)
            val receiver = exported(id)

            // Master pushes its empty profile before asking for the return data.
            CalibrationProfileAccess.importMirrorProfileFromJson(emptyMaster, id)
            assertEquals(2, CalibrationManager.calibrations.value.size)
            val returned = exported(id)
            assertEquals(JSONObject(receiver).getLong("revision"), JSONObject(returned).getLong("revision"))

            useDeviceProfile(emptyMaster, id)
            CalibrationProfileAccess.importMirrorProfileFromJson(returned, id)
            val master = CalibrationManager.calibrations.value
            assertEquals(7f, master.single { !it.isRawMode }.userValue, 0f)
            assertEquals(9f, master.single { it.isRawMode }.userValue, 0f)
            val convergedMaster = exported(id)

            useDeviceProfile(returned, id)
            CalibrationProfileAccess.importMirrorProfileFromJson(convergedMaster, id)
            assertEquals(JSONObject(returned).getLong("revision"), JSONObject(exported(id)).getLong("revision"))
            assertEquals(2, CalibrationManager.calibrations.value.size)
        }
    }

    @Test
    fun receiverDeletionSurvivesReloadAndClearsTheMasterOnReconnect() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        val original = exported(sensor)
        CalibrationManager.deleteCalibration(CalibrationManager.calibrations.value.single())
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.init(Applic.app)
        val receiverDeletion = exported(sensor)

        CalibrationProfileAccess.importMirrorProfileFromJson(original, sensor)
        assertTrue(CalibrationManager.calibrations.value.isEmpty())
        assertEquals(listOf(sensor), CalibrationProfileAccess.mirrorProfileSensorIds().toList())

        useDeviceProfile(original, sensor)
        CalibrationProfileAccess.importMirrorProfileFromJson(receiverDeletion, sensor)
        assertTrue(CalibrationManager.calibrations.value.isEmpty())
        assertEquals(JSONObject(receiverDeletion).getLong("revision"), JSONObject(exported(sensor)).getLong("revision"))
    }

    @Test
    fun masterEditAfterReceiverSnapshotRejectsThatStaleReturn() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor)
        val receiverSnapshot = exported(sensor)
        CalibrationManager.updateCalibration(CalibrationManager.calibrations.value.single().copy(userValue = 8f))
        val master = exported(sensor)

        CalibrationProfileAccess.importMirrorProfileFromJson(receiverSnapshot, sensor)

        assertEquals(8f, CalibrationManager.calibrations.value.single().userValue, 0f)
        assertEquals(JSONObject(master).getLong("revision"), JSONObject(exported(sensor)).getLong("revision"))
    }

    @Test
    fun displaySwitchBeforeExportPreservesTheStoredUnitsAcrossReloadAndNewPoints() = runBlocking {
        val factor = tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL
        for (initialUnit in listOf(0, 1)) {
            CalibrationManager.clearAll()
            Applic.unit = initialUnit
            val scale = if (initialUnit == 1) 1f / factor else 1f
            CalibrationManager.addCalibration(1_000L, 100f * scale, 90f * scale,
                120f * scale, sensorId = sensor, sensorValueStock = 95f * scale)
            Applic.unit = 1 - initialUnit
            // No screen reload or unit-change callback precedes the background export.
            val switched = exported(sensor)
            CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
            CalibrationManager.loadCalibrations()
            val reloaded = exported(sensor)
            assertEquals(JSONObject(switched).getJSONArray("calibrations").toString(),
                JSONObject(reloaded).getJSONArray("calibrations").toString())
            val currentScale = if (Applic.unit == 1) 1f / factor else 1f
            CalibrationManager.addCalibration(100_000L, 110f * currentScale, 100f * currentScale,
                130f * currentScale, sensorId = sensor)
            val mixed = exported(sensor)
            Applic.unit = 0
            useDeviceProfile(JSONObject().put("sensorId", sensor).put("revision", 0L).toString(), sensor)
            CalibrationProfileAccess.importMirrorProfileFromJson(mixed, sensor)
            val old = CalibrationManager.calibrations.value.single { it.timestamp == 1_000L }
            assertEquals(100f, old.sensorValue, 0.001f)
            assertEquals(90f, old.sensorValueRaw, 0.001f)
            assertEquals(95f, old.sensorValueStock, 0.001f)
            assertEquals(120f, old.userValue, 0.001f)
            assertEquals(130f, CalibrationManager.calibrations.value.single { it.timestamp == 100_000L }.userValue, 0.001f)
        }
    }

    @Test
    fun legacyRowsAreLabelledBeforeChangingTheDisplayPreference() = runBlocking {
        val dao = CalibrationDatabase.getInstance(Applic.app).calibrationDao()
        dao.insert(CalibrationEntity(timestamp = 1_000L, sensorId = sensor,
            sensorValue = 100f, sensorValueRaw = 90f, userValue = 120f, valueUnit = -1))
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        Applic.app.setunit(1)
        assertEquals(0, dao.getAllSync().single().valueUnit)
        val row = JSONObject(exported(sensor)).getJSONArray("calibrations").getJSONObject(0)
        assertEquals(120.0 / tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL,
            row.getDouble("userValue"), 0.001)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        Applic.unit = 0
        assertEquals(120f, CalibrationManager.calibrations.value.single().userValue, 0.001f)
    }

    @Test
    fun nativeStartupRestoresSavedUnitsBeforeCloneCanLoadLegacyPoints() = runBlocking {
        val dao = CalibrationDatabase.getInstance(Applic.app).calibrationDao()
        // Exercise both background load paths before Notify is constructed.
        for (loadExplicitly in listOf(false, true)) {
            for (savedUnit in listOf(0, 1)) {
                dao.deleteAll()
                val scale = if (savedUnit == 1) 1f / tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL else 1f
                dao.insert(CalibrationEntity(timestamp = 1_000L, sensorId = sensor,
                    sensorValue = 100f * scale, sensorValueRaw = 90f * scale,
                    sensorValueStock = 95f * scale, userValue = 120f * scale, valueUnit = -1))
                CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
                Applic.unit = 0 // Process-start default, before Notify restores the preference.
                MirrorCalibrationTransferNatives.savedUnit = savedUnit
                MirrorCalibrationTransferNatives.onStartThreads = {
                    if (loadExplicitly) runBlocking { CalibrationManager.loadCalibrations() }
                    val profile = JSONObject(exported(sensor))
                    assertEquals(if (savedUnit == 1) "mmol/L" else "mg/dL", profile.getString("units"))
                    val row = profile.getJSONArray("calibrations").getJSONObject(0)
                    assertEquals(100.0 * scale, row.getDouble("sensorValue"), 0.001)
                    assertEquals(90.0 * scale, row.getDouble("sensorValueRaw"), 0.001)
                    assertEquals(95.0 * scale, row.getDouble("sensorValueStock"), 0.001)
                    assertEquals(120.0 * scale, row.getDouble("userValue"), 0.001)
                }
                try {
                    assertTrue(tk.glucodata.nums.numio.setlibrary(Applic.app))
                    assertEquals(savedUnit, dao.getAllSync().single().valueUnit)
                    // Once Notify applies the same preference, no further conversion occurs.
                    Applic.unit = savedUnit
                    CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
                    assertEquals(120f * scale, CalibrationManager.calibrations.value.single().userValue, 0.001f)
                } finally {
                    MirrorCalibrationTransferNatives.onStartThreads = null
                }
            }
        }
    }

    @Test
    fun concurrentReturnsCannotPublishAnOlderRevisionAfterANewerReturn() {
        val context = Applic.app
        val original = context.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE)
        val revisionField = CalibrationManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val olderSaving = java.util.concurrent.CountDownLatch(1)
        val releaseOlder = java.util.concurrent.CountDownLatch(1)
        val olderRevision = System.currentTimeMillis() + 100_000L
        val newerRevision = olderRevision + 1
        val wrapped = object : android.content.SharedPreferences by original {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = original.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor {
                        if (key.startsWith("calibration_profile_revision_") && value == olderRevision) {
                            olderSaving.countDown()
                            check(releaseOlder.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                        editor.putLong(key, value)
                        return this
                    }
                }
            }
        }
        fun profile(revision: Long, value: Int): String = JSONObject().put("sensorId", sensor)
            .put("revision", revision).put("units", "mg/dL")
            .put("calibrations", org.json.JSONArray().put(JSONObject().put("timestamp", 1_000L)
                .put("sensorValue", 100).put("sensorValueRaw", 90).put("userValue", value))).toString()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        revisionField.set(CalibrationManager, wrapped)
        try {
            val older = pool.submit { CalibrationManager.importMirrorProfileFromJsonBlocking(profile(olderRevision, 120)) }
            assertTrue(olderSaving.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val newerStarted = java.util.concurrent.CountDownLatch(1)
            val newer = pool.submit {
                newerStarted.countDown()
                CalibrationManager.importMirrorProfileFromJsonBlocking(profile(newerRevision, 130))
            }
            assertTrue(newerStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // Without the cross-connection lock this return finishes before the older
            // revision is published. With it the newer return waits, then wins.
            try { newer.get(1, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { }
            releaseOlder.countDown()
            older.get(10, java.util.concurrent.TimeUnit.SECONDS)
            newer.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(newerRevision, JSONObject(exported(sensor)).getLong("revision"))
            assertEquals(130f, CalibrationManager.calibrations.value.single().userValue, 0f)
        } finally {
            releaseOlder.countDown()
            pool.shutdownNow()
            revisionField.set(CalibrationManager, original)
        }
    }

    @Test
    fun receiverProfileConvertsAllPointLanesFromMmolToMgdlOnTheMaster() = runBlocking {
        Applic.unit = 1
        CalibrationManager.addCalibration(1_000L, 6f, 5f, 7f, sensorId = sensor, sensorValueStock = 5.5f)
        val receiver = exported(sensor)
        val empty = JSONObject().put("sensorId", sensor).put("revision", 0L).toString()
        Applic.unit = 0
        useDeviceProfile(empty, sensor)

        CalibrationProfileAccess.importMirrorProfileFromJson(receiver, sensor)

        val point = CalibrationManager.calibrations.value.single()
        val factor = tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL
        assertEquals(6f * factor, point.sensorValue, 0.001f)
        assertEquals(5f * factor, point.sensorValueRaw, 0.001f)
        assertEquals(5.5f * factor, point.sensorValueStock, 0.001f)
        assertEquals(7f * factor, point.userValue, 0.001f)
    }

    @Test
    fun receiverProfileConvertsFromMgdlToMmolAndKeepsLegacyProfilesCompatible() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 108f, 90f, 126f, sensorId = sensor)
        val receiver = exported(sensor)
        Applic.unit = 1
        useDeviceProfile(JSONObject().put("sensorId", sensor).put("revision", 0L).toString(), sensor)
        CalibrationProfileAccess.importMirrorProfileFromJson(receiver, sensor)
        val factor = tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL
        assertEquals(126f / factor, CalibrationManager.calibrations.value.single().userValue, 0.001f)

        val legacy = JSONObject(receiver).apply { remove("units") }.toString()
        useDeviceProfile(JSONObject().put("sensorId", sensor).put("revision", 0L).toString(), sensor)
        CalibrationProfileAccess.importMirrorProfileFromJson(legacy, sensor)
        assertEquals(126f, CalibrationManager.calibrations.value.single().userValue, 0f)
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class MirrorCalibrationTransferNatives {
    companion object {
        var savedUnit = 0
        var onStartThreads: (() -> Unit)? = null
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun getunit(): Int = savedUnit
        @JvmStatic @Implementation fun startthreads() { onStartThreads?.invoke() }
        @JvmStatic @Implementation fun resolveFullSensorName(id: String): String? =
            when (id) {
                "3MH01HD0P14", "E007-3MH01HD0P14" -> "E007-3MH01HD0P14"
                "0683013AQT9", "SIBI:0683013AQT9" -> "SIBI:0683013AQT9"
                else -> null
            }
    }
}
