package tk.glucodata.drivers.ottai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OttaiRegistry as behaviour, over [FakePrefsContext]: what removeSensor carries over, keeps and
 * wipes, which records a restart reconnects, how an id finds its record, and what import/export
 * and the persistence migrations write. Each of these regresses silently on a device — a sensor
 * two weeks in reading "Ready to activate", no reconnect after a restart, a keyA-less duplicate —
 * so they are pinned here instead of by reading the source.
 */
class OttaiRegistryRemovalTests {
    private val ctx = FakePrefsContext()
    private val values get() = ctx.prefs.values

    private companion object {
        const val ID = "0123456789AB"
        const val ADDR = "01:23:45:67:89:AB"
        const val LEGACY_ID = "456789AB"
        const val DAY = 86_400_000L
        const val T = 1_787_000_000_000L // the start
        const val P = T + DAY             // a provisional guess, later than the start
        const val C = T - 60_000L         // when this app wrote the activation command
        const val B = T - 10 * DAY        // when this app bound the sensor for its materials
        val KEY_A = "0123456789abcdef".repeat(12)
        const val DRAFT_KEY = "ottai_draft_sensors"       // OttaiRegistry.PREF_DRAFT_SENSORS_KEY
        const val TEMP_BIND_PREFIX = "ottai_temp_bind_at_" // OttaiRegistry.PREF_TEMP_BIND_AT_PREFIX
    }

    private fun materials(activeTimeMs: Long, coefficient: String = "1,2,3", method: String = "m1") =
        OttaiRegistry.DeviceMaterials(
            keyAHex = KEY_A,
            method = method,
            coefficient = coefficient,
            activeTimeMs = activeTimeMs,
            deviceVersion = "V1",
            deviceId = 7,
            activeExpireTimeMs = 15 * DAY,
            retainTimeMs = 172_800_000L,
            preheatPeriodMs = 3_600_000L,
        )

    /** Raw "id|address|name" lines, for record sets ensureSensorRecord would (rightly) refuse to build. */
    private fun seedRecords(vararg lines: String) {
        values[OttaiConstants.PREF_SENSORS_KEY] = lines.toMutableSet()
    }

    private class FakeNatives(private val ptrs: Map<String, Long>) {
        val calls = mutableListOf<String>()
        fun getdataptr(name: String): Long { calls += "get:$name"; return ptrs[name] ?: 0L }
        fun finish(ptr: Long) { calls += "finish:$ptr" }
        fun free(ptr: Long) { calls += "free:$ptr" }
    }

    /** What the public removeSensor hands the seam, with the JNI calls faked. */
    private fun removeWithNatives(sensorId: String?, natives: FakeNatives, active: Array<String>) =
        OttaiRegistry.removeSensor(ctx, sensorId) { canonical ->
            OttaiRegistry.finishRemovedNativeMirror(
                canonical,
                OttaiRegistry.persistedRecords(ctx),
                { active },
                natives::getdataptr,
                natives::finish,
                natives::free,
            )
        }

    // ---- T08: removeSensor ----

    @Test
    fun prefsComeFromTheAppPreferencesFile() {
        OttaiRegistry.saveLastDataNo(ctx, ID, 5)
        assertEquals(5, OttaiRegistry.loadLastDataNo(ctx, ID))
        assertEquals(setOf("tk.glucodata_preferences"), ctx.requestedNames)
    }

    @Test
    fun removalCarriesTheStartKeepsTheLifetimeFactsAndWipesTheRest() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        OttaiRegistry.saveDraftRecord(ctx, ID, ADDR, "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(T)))
        OttaiRegistry.saveProvisionalActiveTime(ctx, ID, P)
        OttaiRegistry.saveAcceptedMaxActive(ctx, ID, 28 * DAY)
        assertTrue(OttaiRegistry.saveActivateCommandIssued(ctx, ID, true))
        OttaiRegistry.saveActivationCommandAt(ctx, ID, C)
        assertTrue(OttaiRegistry.saveTemporaryBindAtMs(ctx, ID, B))
        OttaiRegistry.saveRecordSize(ctx, ID, 9)
        OttaiRegistry.saveLastDataNo(ctx, ID, 900)
        OttaiRegistry.saveHistoryHoles(ctx, ID, "10:20:1")
        OttaiRegistry.setActivationAttempted(ctx, ID, true)
        OttaiRegistry.saveContinuityBaseline(ctx, ID, 900, T, 5.5f, 1234)
        OttaiRegistry.appendTemperatureHistory(ctx, ID, listOf(OttaiRegistry.TemperatureRecord(900, T, 33.5f)))
        OttaiRegistry.setV3CredentialBootstrapPending(ctx, ID, true)
        OttaiRegistry.saveLastValidatedDeviceVersion(ctx, ID, "V3")
        values["ottai_activated_maxactive_$ID"] = 20 * DAY // the pre-1.0.6 key

        val calls = mutableListOf<String>()
        var recordPresentDuringFinish = false
        OttaiRegistry.removeSensor(ctx, ID) { canonical ->
            calls += canonical
            recordPresentDuringFinish = OttaiRegistry.persistedRecords(ctx).any { it.matchesId(canonical) }
        }

        assertEquals(listOf(ID), calls)
        assertTrue("the shell is finished while the record still exists", recordPresentDuringFinish)
        // Every key left, read before any loader can migrate one. A new per-sensor key has to be
        // put on the wipe list or here, deliberately.
        assertEquals(
            setOf(
                OttaiConstants.PREF_SENSORS_KEY,
                DRAFT_KEY,
                OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + ID,
                OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + ID,
                OttaiConstants.PREF_ACTIVATE_CMD_ISSUED_PREFIX + ID,
                OttaiConstants.PREF_ACTIVATION_COMMAND_AT_PREFIX + ID,
                OttaiConstants.PREF_RECORD_SIZE_PREFIX + ID,
                TEMP_BIND_PREFIX + ID,
            ),
            values.keys,
        )
        assertTrue(OttaiRegistry.persistedRecords(ctx).isEmpty())
        assertTrue(OttaiRegistry.draftRecords(ctx).isEmpty())
        // The confirmed start wins over the older provisional guess.
        assertEquals(T, OttaiRegistry.loadProvisionalActiveTime(ctx, ID))
        assertEquals(28 * DAY, OttaiRegistry.loadAcceptedMaxActive(ctx, ID))
        assertTrue(OttaiRegistry.loadActivateCommandIssued(ctx, ID))
        assertEquals(C, OttaiRegistry.loadActivationCommandAt(ctx, ID))
        assertEquals(B, OttaiRegistry.loadTemporaryBindAtMs(ctx, ID))
        assertEquals(9, OttaiRegistry.loadRecordSize(ctx, ID))
        assertEquals(OttaiRegistry.DeviceMaterials("", "", "", 0L, "", 0), OttaiRegistry.loadMaterials(ctx, ID))
        assertEquals(-1, OttaiRegistry.loadLastDataNo(ctx, ID))
        assertTrue(OttaiRegistry.loadHistoryHoles(ctx, ID).isEmpty())
        assertFalse(OttaiRegistry.loadActivationAttempted(ctx, ID))
        assertNull(OttaiRegistry.loadContinuityBaseline(ctx, ID))
        assertTrue(OttaiRegistry.loadTemperatureHistory(ctx, ID).isEmpty())
        assertFalse(OttaiRegistry.isV3CredentialBootstrapPending(ctx, ID))
        assertNull(OttaiRegistry.loadLastValidatedDeviceVersion(ctx, ID))
    }

    @Test
    fun withoutAConfirmedStartTheProvisionalOneIsCarried() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(0L)))
        OttaiRegistry.saveProvisionalActiveTime(ctx, ID, P)
        OttaiRegistry.removeSensor(ctx, ID) {}
        assertEquals(P, OttaiRegistry.loadProvisionalActiveTime(ctx, ID))
    }

    @Test
    fun withNoStartAtAllNothingIsParked() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(0L)))
        OttaiRegistry.removeSensor(ctx, ID) {}
        assertFalse(values.containsKey(OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + ID))
    }

    @Test
    fun aNullIdTouchesNothing() {
        OttaiRegistry.removeSensor(ctx, null) { throw AssertionError("finish for a null id") }
        assertTrue(values.isEmpty())
    }

    @Test
    fun aFailingFinishStillRemovesTheSensor() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(T)))
        OttaiRegistry.removeSensor(ctx, ID) { error("jni") }
        assertTrue(OttaiRegistry.persistedRecords(ctx).isEmpty())
        assertEquals("", OttaiRegistry.loadMaterials(ctx, ID).keyAHex)
        assertEquals(T, OttaiRegistry.loadProvisionalActiveTime(ctx, ID))
    }

    /** Review F7, as the public overload wires it: only a removal that drops a managed record finishes. */
    @Test
    fun theShellIsFinishedOnlyWhenAManagedRecordIsDropped() {
        val natives = FakeNatives(mapOf(ID to 42L))
        OttaiRegistry.saveDraftRecord(ctx, ID, ADDR, "Ottai")
        removeWithNatives(ID, natives, arrayOf("56789AB"))
        assertTrue("draft only: no shell of a managed sensor to retire", natives.calls.isEmpty())
        assertTrue(OttaiRegistry.draftRecords(ctx).isEmpty())

        removeWithNatives("0000DEADBEEF", natives, arrayOf("56789AB"))
        assertTrue("unknown id", natives.calls.isEmpty())

        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(T)))
        // The native short alias names the same shell but drops no record, so both stay.
        removeWithNatives("56789AB", natives, arrayOf("56789AB"))
        assertTrue(natives.calls.isEmpty())
        assertEquals(listOf(ID), OttaiRegistry.persistedRecords(ctx).map { it.sensorId })
        assertEquals(KEY_A, OttaiRegistry.loadMaterials(ctx, ID).keyAHex)

        removeWithNatives(ID, natives, arrayOf("56789AB"))
        assertEquals(listOf("get:$ID", "finish:42", "free:42"), natives.calls)
        assertTrue(OttaiRegistry.persistedRecords(ctx).isEmpty())
    }

    // ---- T29: restore authorization and record identity ----

    @Test
    fun restoreListsOnlyAuthorizedRecords() {
        val a = "6083DA000001" // cloud or recovered start
        val b = "6083DA000002" // only a provisional start (vendor-activated, cloud activeTime 0)
        val c = "6083DA000003" // only the activation-attempted marker
        val d = "6083DA000004" // keyA but nothing that says it may connect
        for (id in listOf(a, b, c, d)) OttaiRegistry.ensureSensorRecord(ctx, id, "", "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, a, materials(T)))
        assertTrue(OttaiRegistry.saveMaterials(ctx, b, materials(0L)))
        OttaiRegistry.saveProvisionalActiveTime(ctx, b, P)
        OttaiRegistry.setActivationAttempted(ctx, c, true)
        assertTrue(OttaiRegistry.saveMaterials(ctx, d, materials(0L)))

        val ids = OttaiManagedSensorIdentityAdapter.persistedSensorIds(ctx)
        assertEquals(3, ids.size)
        assertEquals(setOf(a, b, c), ids.toSet())
        assertTrue(OttaiRegistry.isManagedConnectionAuthorized(ctx, "60:83:DA:00:00:02"))
        assertFalse(OttaiRegistry.isManagedConnectionAuthorized(ctx, "60:83:DA:00:00:04"))
    }

    @Test
    fun aLegacyShortDuplicateIsRestoredOnceUnderTheMaterialId() {
        seedRecords("$LEGACY_ID||Ottai", "$ID|$ADDR|Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(T)))
        assertEquals(KEY_A, values[OttaiConstants.PREF_KEYA_PREFIX + ID])
        assertEquals(listOf(ID), OttaiManagedSensorIdentityAdapter.persistedSensorIds(ctx))
    }

    @Test
    fun findRecordMatchesSuffixesOfSixOrMoreCharacters() {
        seedRecords("$ID||Ottai", "0123456789CD||Ottai")
        values[OttaiConstants.PREF_KEYA_PREFIX + ID] = KEY_A
        assertEquals(ID, OttaiRegistry.findRecord(ctx, "56789AB")?.sensorId)
        assertEquals("0123456789CD", OttaiRegistry.findRecord(ctx, "56789CD")?.sensorId)
        assertEquals(ID, OttaiRegistry.findRecord(ctx, ADDR)?.sensorId)
        assertNull("5 characters are too few to be an alias", OttaiRegistry.findRecord(ctx, "789AB"))
        assertNull(OttaiRegistry.findRecord(ctx, " "))
        assertNull(OttaiRegistry.findRecord(null, ID))
    }

    @Test
    fun theRecordOwningKeyAWins() {
        seedRecords("$LEGACY_ID||Ottai", "$ID||Ottai")
        values[OttaiConstants.PREF_KEYA_PREFIX + LEGACY_ID] = KEY_A
        assertEquals(LEGACY_ID, OttaiRegistry.findRecord(ctx, ID)?.sensorId)
        assertEquals(LEGACY_ID, OttaiRegistry.resolveCanonicalSensorId(ctx, ADDR))
    }

    @Test
    fun ensureSensorRecordNeverDuplicatesAndKeepsTheAddress() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        // Known only by its BLE address: the address match keeps the existing id.
        OttaiRegistry.ensureSensorRecord(ctx, "OTHERID", ADDR, "Ottai")
        assertEquals(listOf(OttaiRegistry.SensorRecord(ID, ADDR, "Ottai")), OttaiRegistry.persistedRecords(ctx))
        // A blank or plain-hex address is no BLE address: the stored one stays.
        OttaiRegistry.ensureSensorRecord(ctx, ID, "", "x")
        OttaiRegistry.ensureSensorRecord(ctx, ID, ID, "y")
        assertEquals(listOf(OttaiRegistry.SensorRecord(ID, ADDR, "y")), OttaiRegistry.persistedRecords(ctx))
    }

    @Test
    fun materialsResolveThroughTheRecordFromAnyAlias() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, "56789AB", materials(T)))
        assertEquals(KEY_A, values[OttaiConstants.PREF_KEYA_PREFIX + ID])
        assertFalse(values.containsKey(OttaiConstants.PREF_KEYA_PREFIX + "56789AB"))
        assertEquals(KEY_A, OttaiRegistry.loadMaterials(ctx, ADDR).keyAHex)
        assertEquals(T, OttaiRegistry.loadMaterials(ctx, ADDR).activeTimeMs)
    }

    // ---- T30: import / export ----

    private fun exportFile(
        sensorId: String = ADDR,
        keyA: String = KEY_A,
        activeTimeMs: Long = 1_787_489_036L,
        acceptedMaxActiveMs: Long? = 28 * DAY,
    ): String = JSONObject().apply {
        put("v", 1)
        put("sensorId", sensorId)
        put("bleAddress", ADDR)
        put("keyAHex", keyA)
        put("method", "m1")
        put("coefficient", "1,2,3")
        put("activeTimeMs", activeTimeMs)
        acceptedMaxActiveMs?.let { put("acceptedMaxActiveMs", it) }
        put("deviceVersion", "V1")
        put("deviceId", 7)
    }.toString()

    @Test
    fun importWritesOnlyADraftAndNormalizesTheStart() {
        assertEquals(ID, OttaiRegistry.importJson(ctx, exportFile()))
        assertTrue("no managed record before the user presses Connect", OttaiRegistry.persistedRecords(ctx).isEmpty())
        assertEquals(
            listOf(OttaiRegistry.SensorRecord(ID, ADDR, OttaiConstants.DEFAULT_DISPLAY_NAME)),
            OttaiRegistry.draftRecords(ctx),
        )
        val m = OttaiRegistry.loadMaterials(ctx, ID)
        assertEquals(KEY_A, m.keyAHex)
        assertEquals("seconds become ms", 1_787_489_036_000L, m.activeTimeMs)
        assertEquals(28 * DAY, OttaiRegistry.loadAcceptedMaxActive(ctx, ID))
        // A later file that says nothing about the lifetime, or nonsense, keeps the stored one.
        assertEquals(ID, OttaiRegistry.importJson(ctx, exportFile(acceptedMaxActiveMs = null)))
        assertEquals(28 * DAY, OttaiRegistry.loadAcceptedMaxActive(ctx, ID))
        assertEquals(ID, OttaiRegistry.importJson(ctx, exportFile(acceptedMaxActiveMs = 5L)))
        assertEquals(28 * DAY, OttaiRegistry.loadAcceptedMaxActive(ctx, ID))
    }

    @Test
    fun importRejectsABadKeyAAndWritesNothing() {
        assertNull(OttaiRegistry.importJson(ctx, exportFile(keyA = KEY_A.drop(2))))
        assertNull(OttaiRegistry.importJson(ctx, exportFile(keyA = "zz" + KEY_A.drop(2))))
        assertNull(OttaiRegistry.importJson(ctx, exportFile(sensorId = "")))
        assertNull(OttaiRegistry.importJson(ctx, "not json"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun importKeysTheLifetimeByTheResolvedRecord() {
        seedRecords("$LEGACY_ID||Ottai")
        values[OttaiConstants.PREF_KEYA_PREFIX + LEGACY_ID] = KEY_A
        assertEquals(ID, OttaiRegistry.importJson(ctx, exportFile()))
        assertEquals(28 * DAY, values[OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + LEGACY_ID])
        assertFalse(values.containsKey(OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + ID))
        assertEquals(1_787_489_036_000L, values[OttaiConstants.PREF_ACTIVE_TIME_PREFIX + LEGACY_ID])
    }

    @Test
    fun exportNeverCarriesTheProvisionalStart() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(0L)))
        OttaiRegistry.saveProvisionalActiveTime(ctx, ID, P)
        OttaiRegistry.saveAcceptedMaxActive(ctx, ID, 28 * DAY)
        val o = JSONObject(OttaiRegistry.exportJson(ctx, ADDR)!!)
        assertFalse(o.has("provisionalActiveTimeMs"))
        assertEquals(0L, o.getLong("activeTimeMs"))
        assertEquals(ID, o.getString("sensorId"))
        assertEquals(ADDR, o.getString("bleAddress"))
        assertEquals(KEY_A, o.getString("keyAHex"))
        assertEquals(28 * DAY, o.getLong("acceptedMaxActiveMs"))
    }

    @Test
    fun exportOfALegacyShortRecordStillNamesTheMac() {
        seedRecords("$LEGACY_ID||Ottai")
        values[OttaiConstants.PREF_KEYA_PREFIX + LEGACY_ID] = KEY_A
        val o = JSONObject(OttaiRegistry.exportJson(ctx, ID)!!)
        assertEquals(ID, o.getString("sensorId"))
        assertEquals(KEY_A, o.getString("keyAHex"))
    }

    @Test
    fun exportWithoutKeyAIsNull() {
        OttaiRegistry.ensureSensorRecord(ctx, ID, ADDR, "Ottai")
        assertNull(OttaiRegistry.exportJson(ctx, ID))
    }

    // ---- T30: persistence migrations and the hole ledger ----

    @Test
    fun loadMaterialsMigratesSecondsAndDropsAnImpossibleLifetime() {
        values[OttaiConstants.PREF_ACTIVE_TIME_PREFIX + ID] = 1_787_489_036L
        values[OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + ID] = 4_204_901_547_000L // an epoch, not a duration
        val m = OttaiRegistry.loadMaterials(ctx, ID)
        assertEquals(1_787_489_036_000L, m.activeTimeMs)
        assertEquals(1_787_489_036_000L, values[OttaiConstants.PREF_ACTIVE_TIME_PREFIX + ID])
        assertEquals(0L, m.activeExpireTimeMs)
        assertFalse(values.containsKey(OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + ID))
    }

    @Test
    fun theLegacyAcceptedLifetimeIsMigratedOnce() {
        values["ottai_activated_maxactive_$ID"] = 28 * DAY
        assertEquals(28 * DAY, OttaiRegistry.loadAcceptedMaxActive(ctx, ID))
        assertEquals(28 * DAY, values[OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + ID])
        assertFalse(values.containsKey("ottai_activated_maxactive_$ID"))

        values["ottai_activated_maxactive_6083DA000009"] = 5L
        assertEquals(0L, OttaiRegistry.loadAcceptedMaxActive(ctx, "6083DA000009"))
        assertTrue("an invalid legacy value is dropped, not migrated", values.keys.none { it.endsWith("6083DA000009") })
    }

    @Test
    fun saveMaterialsKeepsWhatABlankUpdateDoesNotKnow() {
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(T)))
        OttaiRegistry.setV3CredentialBootstrapPending(ctx, ID, true)
        assertTrue(OttaiRegistry.saveMaterials(ctx, ID, materials(0L, coefficient = "", method = "").copy(activeExpireTimeMs = 5L)))
        val m = OttaiRegistry.loadMaterials(ctx, ID)
        assertEquals(T, m.activeTimeMs)
        assertEquals("1,2,3", m.coefficient)
        assertEquals("m1", m.method)
        assertFalse("an invalid lifetime is removed, not stored", values.containsKey(OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + ID))
        assertFalse(OttaiRegistry.isV3CredentialBootstrapPending(ctx, ID))
    }

    @Test
    fun theHoleLedgerDropsMalformedEntriesAndABlankSaveClearsIt() {
        OttaiRegistry.saveHistoryHoles(ctx, ID, "10:20:1;30:31:0;bad;5:5:0;-1:4:0;7:9:x")
        assertEquals(listOf(Triple(10, 20, 1), Triple(30, 31, 0)), OttaiRegistry.loadHistoryHoles(ctx, ADDR))
        OttaiRegistry.saveHistoryHoles(ctx, ID, "")
        assertFalse(values.containsKey(OttaiConstants.PREF_HISTORY_HOLES_PREFIX + ID))
        assertTrue(OttaiRegistry.loadHistoryHoles(ctx, ID).isEmpty())
    }
}
