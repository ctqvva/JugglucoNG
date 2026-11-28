package tk.glucodata.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.data.food.FoodLibraryDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsExporterValidationTests {
    private lateinit var context: Context
    private lateinit var history: HistoryDatabase
    private lateinit var foods: FoodLibraryDatabase

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        history = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        foods = Room.inMemoryDatabaseBuilder(context, FoodLibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        setSingleton(HistoryDatabase::class.java, "INSTANCE", history)
        setSingleton(FoodLibraryDatabase::class.java, "instance", foods)

        clearTestFiles()
        seedExistingState()
    }

    @After
    fun tearDown() {
        setSingleton(HistoryDatabase::class.java, "INSTANCE", null)
        setSingleton(FoodLibraryDatabase::class.java, "instance", null)
        history.close()
        foods.close()
        clearTestFiles()
    }

    @Test
    fun missingSharedPreferencesSectionIsRejectedAndPreservesExistingPreferences() = runBlocking {
        val backup = validV4Payload().apply { remove("sharedPreferences") }

        val fromPayload = SettingsExporter.importPayload(context, backup)
        assertTrue(fromPayload.isFailure)
        assertExistingStatePreserved()

        val fromFile = importJsonText(backup.toString())
        assertTrue(fromFile.isFailure)
        assertExistingStatePreserved()
    }

    @Test
    fun intentionallyEmptySharedPreferencesIsAcceptedAndClearsDiscoveredPreferences() = runBlocking {
        val backup = validV4Payload()
            .put("sharedPreferences", JSONObject())
            .put("nativeSettingsFiles", JSONObject())

        val result = importJsonText(backup.toString())

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(0, summary.sharedPreferenceFiles)
        assertEquals(0, summary.preferenceValues)
        assertFalse(
            context.getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE).contains("sensor_id")
        )
        assertFalse(
            context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).contains("low_alarm")
        )
    }

    @Test
    fun missingPreferenceValuesObjectIsRejectedWhileEmptyValuesObjectIsAccepted() = runBlocking {
        val missingValues = validV4Payload().put(
            "sharedPreferences",
            JSONObject().put(SENSOR_PREFS, JSONObject())
        )
        assertTrue(SettingsExporter.importPayload(context, missingValues).isFailure)
        assertExistingStatePreserved()

        val emptyValues = validV4Payload().put(
            "sharedPreferences",
            JSONObject().put(SENSOR_PREFS, JSONObject().put("values", JSONObject()))
        )
        val result = SettingsExporter.importPayload(context, emptyValues)
        assertTrue(result.isSuccess)
        assertFalse(
            context.getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE).contains("sensor_id")
        )
    }

    @Test
    fun wrongJsonTypesInBackupSectionsAndValuesAreRejectedWithoutMutation() = runBlocking {
        val malformedPayloads = listOf<Pair<String, () -> JSONObject>>(
            "non-string schema" to { validV4Payload().put("schema", 123) },
            "string schemaVersion" to { validV4Payload().put("schemaVersion", "4") },
            "float schemaVersion" to { validV4Payload().put("schemaVersion", 4.5) },
            "boolean schemaVersion" to { validV4Payload().put("schemaVersion", true) },
            "null sharedPreferences" to { validV4Payload().put("sharedPreferences", JSONObject.NULL) },
            "array sharedPreferences" to { validV4Payload().put("sharedPreferences", JSONArray()) },
            "non-object pref file" to {
                validV4Payload().put("sharedPreferences", JSONObject().put(SENSOR_PREFS, "bad"))
            },
            "array pref values" to {
                validV4Payload().put(
                    "sharedPreferences",
                    JSONObject().put(SENSOR_PREFS, JSONObject().put("values", JSONArray()))
                )
            },
            "path traversal pref name" to {
                validV4Payload().put(
                    "sharedPreferences",
                    JSONObject().put("../escape", JSONObject().put("values", JSONObject()))
                )
            },
            "boolean coerced from string" to {
                payloadWithPref("flag", JSONObject().put("type", "boolean").put("value", "true"))
            },
            "int coerced from string" to {
                payloadWithPref("count", JSONObject().put("type", "int").put("value", "42"))
            },
            "int coerced from float" to {
                payloadWithPref("count", JSONObject().put("type", "int").put("value", 42.5))
            },
            "int overflow" to {
                payloadWithPref("count", JSONObject().put("type", "int").put("value", Long.MAX_VALUE))
            },
            "long coerced from string" to {
                payloadWithPref("ts", JSONObject().put("type", "long").put("value", "100"))
            },
            "long coerced from float" to {
                payloadWithPref("ts", JSONObject().put("type", "long").put("value", 100.25))
            },
            "float coerced from string" to {
                payloadWithPref("ratio", JSONObject().put("type", "float").put("value", "1.5"))
            },
            "float overflow to infinity" to {
                payloadWithPref("ratio", JSONObject().put("type", "float").put("value", 1e308))
            },
            "string coerced from number" to {
                payloadWithPref("name", JSONObject().put("type", "string").put("value", 123))
            },
            "string missing value" to {
                payloadWithPref("name", JSONObject().put("type", "string"))
            },
            "string_set with numeric element" to {
                payloadWithPref(
                    "tags",
                    JSONObject().put("type", "string_set").put("value", JSONArray().put("ok").put(9))
                )
            },
            "null type with non-null value" to {
                payloadWithPref("empty", JSONObject().put("type", "null").put("value", "unexpected"))
            },
            "unsupported preference type" to {
                payloadWithPref("weird", JSONObject().put("type", "binary").put("value", "abc"))
            },
            "missing nativeSettingsFiles" to {
                validV4Payload().apply { remove("nativeSettingsFiles") }
            },
            "array nativeSettingsFiles" to {
                validV4Payload().put("nativeSettingsFiles", JSONArray())
            },
            "unsupported native file name" to {
                validV4Payload().put(
                    "nativeSettingsFiles",
                    JSONObject().put("evil.dat", nativeFileEntry("data"))
                )
            },
            "non-string native base64" to {
                validV4Payload().put(
                    "nativeSettingsFiles",
                    JSONObject().put("settings.dat", JSONObject().put("base64", true))
                )
            },
            "missing journalData in v4" to {
                validV4Payload().apply { remove("journalData") }
            },
            "wrong journalData type" to {
                validV4Payload().put("journalData", JSONArray())
            },
            "wrong journalData type in v1" to {
                validV1Payload().put("journalData", "not-an-object")
            },
            "missing journal entries" to {
                validV4Payload().apply { getJSONObject("journalData").remove("entries") }
            },
            "wrong journal entry timestamp type" to {
                validV4Payload().apply {
                    getJSONObject("journalData").getJSONArray("entries").getJSONObject(0)
                        .put("timestamp", "1700000000000")
                }
            },
            "wrong journal entry amount type" to {
                validV4Payload().apply {
                    getJSONObject("journalData").getJSONArray("entries").getJSONObject(0)
                        .put("amount", "not-a-float")
                }
            },
            "wrong insulin preset onset type" to {
                validV4Payload().apply {
                    getJSONObject("journalData").getJSONArray("insulinPresets").getJSONObject(0)
                        .put("onsetMinutes", "15")
                }
            },
            "wrong food carbs type" to {
                validV4Payload().apply {
                    getJSONObject("journalData").getJSONArray("foods").getJSONObject(0)
                        .put("carbsGrams", "20")
                }
            },
            "invalid food detail foodId" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 0))
                    )
                }
            },
            "boolean food detail referenceGrams" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("referenceGrams", true))
                    )
                }
            },
            "non-positive food detail referenceGrams" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("referenceGrams", 0))
                    )
                }
            },
            "string food detail fiberGrams" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("fiberGrams", "5"))
                    )
                }
            },
            "boolean food detail kcal" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("kcal", false))
                    )
                }
            },
            "string food detail isLiquid" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("isLiquid", "yes"))
                    )
                }
            },
            "string food detail useCount" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("useCount", "2"))
                    )
                }
            },
            "string food detail lastPortionIndex" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("lastPortionIndex", "0"))
                    )
                }
            },
            "numeric food detail brand" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("brand", 42))
                    )
                }
            },
            "numeric food detail portions" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(JSONObject().put("foodId", 20).put("portions", 123))
                    )
                }
            },
            "malformed food detail portions json" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put(
                        "foodDetails",
                        JSONArray().put(
                            JSONObject().put("foodId", 20).put("portions", """[{"kind":"invalid","grams":10}]""")
                        )
                    )
                }
            },
            "wrong cloneTombstones type" to {
                validV4Payload().apply {
                    getJSONObject("journalData").put("cloneTombstones", JSONObject())
                }
            }
        )

        malformedPayloads.forEach { (label, build) ->
            val payload = build()
            assertTrue("Expected validatePayload to fail for: $label", runCatching {
                SettingsExporter.validatePayload(payload)
            }.isFailure)
            val result = SettingsExporter.importPayload(context, payload)
            assertTrue("Expected importPayload to fail for: $label", result.isFailure)
            assertExistingStatePreserved()
        }
    }

    @Test
    fun unsupportedSchemaAndSchemaVersionsAreRejectedWithoutMutation() = runBlocking {
        val invalidPayloads = listOf(
            validV4Payload().apply { remove("schema") },
            validV4Payload().put("schema", "tk.glucodata.unknown"),
            validV4Payload().put("schemaVersion", 0),
            validV4Payload().put("schemaVersion", -1),
            validV4Payload().put("schemaVersion", 99)
        )

        invalidPayloads.forEach { payload ->
            assertTrue(importJsonText(payload.toString()).isFailure)
            assertExistingStatePreserved()
        }
    }

    @Test
    fun truncatedAndTrailingGarbageBackupsFailWithoutMutation() = runBlocking {
        val validJson = validV4Payload().toString(2)
        val truncatedPlain = listOf(
            "",
            "{",
            """{"schema":"tk.glucodata.settings-export","schemaVersion":4""",
            validJson.substring(0, validJson.length / 2),
            "[]",
            "$validJson trailing-garbage"
        )

        truncatedPlain.forEach { raw ->
            assertTrue("Expected failure for raw input: $raw", importJsonText(raw).isFailure)
            assertExistingStatePreserved()
        }

        // Also verify truncated gzip-compressed settings backup fails cleanly.
        val gzipped = ByteArrayOutputStream().also { out ->
            ExportPackageCodec.writeUtf8(out, validJson, ExportCompression.GZIP)
        }.toByteArray()
        val truncatedGzip = gzipped.copyOf(gzipped.size / 2)
        assertTrue(importBytes(truncatedGzip, ".json.gz").isFailure)
        assertExistingStatePreserved()
    }

    @Test
    fun invalidBase64InNativeSettingsFailsPreflightBeforeMutatingPreferences() = runBlocking {
        // Characters outside the base64 alphabet (which MIME decoder would silently strip) must fail.
        val invalidBase64Values = listOf("not!valid@base64#", "a", "====", "   \n\t ")

        invalidBase64Values.forEach { encoded ->
            val backup = validV4Payload().put(
                "nativeSettingsFiles",
                JSONObject().put("settings.dat", JSONObject().put("base64", encoded))
            )
            assertTrue(runCatching { SettingsExporter.validatePayload(backup) }.isFailure)
            assertTrue(SettingsExporter.importPayload(context, backup).isFailure)
            assertExistingStatePreserved()
        }
    }

    @Test
    fun failedNativeSettingsRestorePreservesExistingPreferencesAndNativeFiles() = runBlocking {
        val replacement = validV4Payload()
            .put(
                "sharedPreferences",
                JSONObject().put(
                    SENSOR_PREFS,
                    JSONObject().put(
                        "values",
                        JSONObject().put("sensor_id", pref("string", "REPLACED"))
                    )
                )
            )
            .put(
                "nativeSettingsFiles",
                JSONObject()
                    .put("settings.dat", nativeFileEntry("new-settings-bytes"))
                    .put("backup.dat", nativeFileEntry("new-backup-bytes"))
            )

        // 1. Failure during staging on the second file leaves live native files & prefs intact.
        val stagingFailure = SettingsExporter.importPayloadForTest(
            context = context,
            payload = replacement,
            writeNativeFile = { file, bytes ->
                if (file.name.contains("backup.dat")) throw IOException("simulated disk full")
                file.writeBytes(bytes)
            }
        )
        assertTrue(stagingFailure.isFailure)
        assertExistingStatePreserved()
        assertNoTempFilesLeft()

        // 2. Failure during commit after settings.dat was written in-place rolls back settings.dat
        //    on the same inode so live native mmaps remain valid.
        val settingsFile = File(context.filesDir, "settings.dat")
        val settingsInodeBefore = fileKey(settingsFile)
        val commitFailure = SettingsExporter.importPayloadForTest(
            context = context,
            payload = replacement,
            replaceNativeFile = { staged, target, bytes, existedBefore ->
                if (target.name == "backup.dat") throw IOException("simulated replace failure")
                if (existedBefore) {
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.seek(0L)
                        raf.write(bytes)
                        raf.setLength(bytes.size.toLong())
                    }
                    staged.delete()
                } else if (!staged.renameTo(target)) {
                    target.writeBytes(bytes)
                    staged.delete()
                }
            }
        )
        assertTrue(commitFailure.isFailure)
        assertExistingStatePreserved()
        assertEquals(settingsInodeBefore, fileKey(settingsFile))
        assertNoTempFilesLeft()

        // 3. Failure when a target path is not a regular file (directory) preserves other state.
        val orBackupDir = File(context.filesDir, "orbackup.dat").apply { mkdirs() }
        try {
            val backupWithDirConflict = validV4Payload().put(
                "nativeSettingsFiles",
                JSONObject()
                    .put("settings.dat", nativeFileEntry("new-settings-bytes"))
                    .put("orbackup.dat", nativeFileEntry("new-orbackup-bytes"))
            )
            val dirFailure = SettingsExporter.importPayload(context, backupWithDirConflict)
            assertTrue(dirFailure.isFailure)
            assertExistingStatePreserved()
            assertNoTempFilesLeft()
        } finally {
            orBackupDir.deleteRecursively()
        }
    }

    @Test
    fun journalFailureAfterSettingsWritesRollsBackPreferencesAndKeepsMappedNativeInode() = runBlocking {
        val settingsFile = File(context.filesDir, "settings.dat")
        val settingsInodeBefore = fileKey(settingsFile)
        history.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_journal_insert BEFORE INSERT ON journal_entries WHEN NEW.id = 99
            BEGIN SELECT RAISE(ABORT, 'simulated journal write failure'); END
            """.trimIndent()
        )
        val backup = validV4Payload(entryId = 99L)
            .put(
                "sharedPreferences",
                JSONObject().put(
                    SENSOR_PREFS,
                    JSONObject().put(
                        "values",
                        JSONObject().put("sensor_id", pref("string", "SHOULD_ROLL_BACK"))
                    )
                )
            )
            .put(
                "nativeSettingsFiles",
                JSONObject()
                    .put("settings.dat", nativeFileEntry("new-settings-should-not-touch-live"))
                    .put("orbackup.dat", nativeFileEntry("created-file-should-not-exist"))
            )

        val result = SettingsExporter.importPayload(context, backup)

        assertTrue(result.isFailure)
        assertExistingStatePreserved()
        assertEquals(settingsInodeBefore, fileKey(settingsFile))
        assertFalse(File(context.filesDir, "orbackup.dat").exists())
        assertNoTempFilesLeft()
    }

    @Test
    fun validOlderBackupSchemasRemainCompatible() = runBlocking {
        // Schema v1: no journalData section at all.
        val v1Result = importJsonText(validV1Payload().toString())
        assertTrue(v1Result.isSuccess)
        assertEquals(
            "v1-sensor",
            context.getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE).getString("sensor_id", null)
        )
        assertEquals(1, history.journalDao().getEntries().size)

        // Schema v2: has journalData with entries + insulinPresets, but no foods / foodDetails / tombstones.
        val v2 = JSONObject()
            .put("schema", SettingsExporter.SCHEMA)
            .put("schemaVersion", 2)
            .put(
                "sharedPreferences",
                JSONObject().put(
                    SENSOR_PREFS,
                    JSONObject().put("values", JSONObject().put("sensor_id", pref("string", "v2-sensor")))
                )
            )
            .put("nativeSettingsFiles", JSONObject().put("settings.dat", nativeFileEntry("v2-native")))
            .put(
                "journalData",
                JSONObject()
                    .put("entries", JSONArray().put(journalEntryJson(20L, "v2 entry")))
                    .put("insulinPresets", JSONArray().put(insulinPresetJson(20L, "v2 insulin")))
            )
        val v2Result = importJsonText(v2.toString())
        assertTrue(v2Result.isSuccess)
        assertEquals("v2-sensor", context.getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE).getString("sensor_id", null))
        assertArrayEquals("v2-native".toByteArray(), File(context.filesDir, "settings.dat").readBytes())
        assertEquals(20L, history.journalDao().getEntries().single().id)
        // Foods from before v2 restore remain intact because v2 has no foods key.
        assertEquals(1, history.journalDao().getFoods().size)

        // Schema v3: includes foods, omits foodDetails and cloneTombstones.
        val v3 = JSONObject()
            .put("schema", SettingsExporter.SCHEMA)
            .put("schemaVersion", 3)
            .put(
                "sharedPreferences",
                JSONObject().put(
                    SENSOR_PREFS,
                    JSONObject().put(
                        "values",
                        JSONObject()
                            .put("sensor_id", pref("string", "v3-sensor"))
                            .put("enabled", pref("boolean", true))
                            .put("count", pref("int", 7))
                            .put("updated_at", pref("long", 1_700_000_000_000L))
                            .put("scale", pref("float", 1.25))
                            .put("nullable_str", pref("string", JSONObject.NULL))
                            .put("tags", pref("string_set", JSONArray().put("a").put("b")))
                            .put("removed", JSONObject().put("type", "null").put("value", JSONObject.NULL))
                    )
                )
            )
            .put(
                "nativeSettingsFiles",
                JSONObject().put(
                    "settings.dat",
                    JSONObject().put(
                        "base64",
                        Base64.encodeToString("v3-native-wrapped-payload".toByteArray(), Base64.DEFAULT)
                    )
                )
            )
            .put(
                "journalData",
                JSONObject()
                    .put("entries", JSONArray().put(journalEntryJson(30L, "v3 entry")))
                    .put("insulinPresets", JSONArray().put(insulinPresetJson(30L, "v3 insulin")))
                    .put("foods", JSONArray().put(foodJson(30L, "v3 food")))
            )
        val v3Result = importJsonText(v3.toString())
        assertTrue(v3Result.isSuccess)
        val prefs = context.getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE)
        assertEquals("v3-sensor", prefs.getString("sensor_id", null))
        assertTrue(prefs.getBoolean("enabled", false))
        assertEquals(7, prefs.getInt("count", 0))
        assertEquals(1_700_000_000_000L, prefs.getLong("updated_at", 0L))
        assertEquals(1.25f, prefs.getFloat("scale", 0f), 0.0001f)
        assertEquals(setOf("a", "b"), prefs.getStringSet("tags", emptySet()))
        assertEquals(30L, history.journalDao().getFoods().single().id)
    }

    @Test
    fun embeddedSettingsInExportPackageWorksWithoutJournalData() = runBlocking {
        val embedded = validV4Payload().apply { remove("journalData") }
        assertTrue(SettingsExporter.importPayload(context, embedded, requireJournalData = true).isFailure)
        assertExistingStatePreserved()

        val result = SettingsExporter.importPayload(context, embedded, requireJournalData = false)
        assertTrue(result.isSuccess)
    }

    private suspend fun seedExistingState() {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        File(prefsDir, "$SENSOR_PREFS.xml").writeText("<map/>")
        File(prefsDir, "$ALARM_PREFS.xml").writeText("<map/>")

        assertTrue(
            context.getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE).edit()
                .clear()
                .putString("sensor_id", "ORIGINAL_SENSOR")
                .putInt("interval", 5)
                .putBoolean("sync_enabled", true)
                .putStringSet("channels", setOf("ble", "cloud"))
                .commit()
        )
        assertTrue(
            context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit()
                .clear()
                .putInt("low_alarm", 70)
                .commit()
        )

        context.filesDir.mkdirs()
        File(context.filesDir, "settings.dat").writeBytes(ORIGINAL_SETTINGS_DAT)
        File(context.filesDir, "backup.dat").writeBytes(ORIGINAL_BACKUP_DAT)
        File(context.filesDir, "orbackup.dat").delete()

        val initial = validV4Payload(entryId = 10L)
            .put(
                "sharedPreferences",
                JSONObject()
                    .put(
                        SENSOR_PREFS,
                        JSONObject().put(
                            "values",
                            JSONObject()
                                .put("sensor_id", pref("string", "ORIGINAL_SENSOR"))
                                .put("interval", pref("int", 5))
                                .put("sync_enabled", pref("boolean", true))
                                .put("channels", pref("string_set", JSONArray().put("ble").put("cloud")))
                        )
                    )
                    .put(
                        ALARM_PREFS,
                        JSONObject().put(
                            "values",
                            JSONObject().put("low_alarm", pref("int", 70))
                        )
                    )
            )
            .put(
                "nativeSettingsFiles",
                JSONObject()
                    .put("settings.dat", nativeFileEntry(String(ORIGINAL_SETTINGS_DAT)))
                    .put("backup.dat", nativeFileEntry(String(ORIGINAL_BACKUP_DAT)))
            )
        SettingsExporter.importPayload(context, initial).getOrThrow()
    }

    private suspend fun assertExistingStatePreserved() {
        assertExistingPreferencesAndNativeFilesPreserved()

        assertEquals(listOf(10L), history.journalDao().getEntries().map { it.id })
        assertEquals(listOf(10L), history.journalDao().getInsulinPresets().map { it.id })
        assertEquals(listOf(10L), history.journalDao().getFoods().map { it.id })
        assertEquals(listOf(10L), foods.foodDetailsDao().getAll().map { it.foodId })
        assertEquals(listOf(110L), history.journalDao().getCloneJournalTombstones().map { it.entryId })
    }

    private fun assertExistingPreferencesAndNativeFilesPreserved() {
        val sensorPrefs = context.getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE)
        assertEquals("ORIGINAL_SENSOR", sensorPrefs.getString("sensor_id", null))
        assertEquals(5, sensorPrefs.getInt("interval", -1))
        assertTrue(sensorPrefs.getBoolean("sync_enabled", false))
        assertEquals(setOf("ble", "cloud"), sensorPrefs.getStringSet("channels", emptySet()))

        val alarmPrefs = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
        assertEquals(70, alarmPrefs.getInt("low_alarm", -1))

        assertArrayEquals(ORIGINAL_SETTINGS_DAT, File(context.filesDir, "settings.dat").readBytes())
        assertArrayEquals(ORIGINAL_BACKUP_DAT, File(context.filesDir, "backup.dat").readBytes())
    }

    private fun assertNoTempFilesLeft() {
        val leftover = context.filesDir.listFiles { file ->
            file.name.endsWith(".restore.tmp") || file.name.endsWith(".rollback.tmp")
        }.orEmpty()
        assertTrue("Unexpected leftover temp files: ${leftover.map { it.name }}", leftover.isEmpty())
    }

    private fun fileKey(file: File): Any? =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()

    private suspend fun importJsonText(text: String): Result<SettingsExporter.ImportSummary> =
        importBytes(text.toByteArray(Charsets.UTF_8), ".json")

    private suspend fun importBytes(bytes: ByteArray, suffix: String): Result<SettingsExporter.ImportSummary> {
        val file = File.createTempFile("settings-validation-", suffix, context.cacheDir)
        return try {
            file.writeBytes(bytes)
            SettingsExporter.importFromJson(context, Uri.fromFile(file))
        } finally {
            file.delete()
        }
    }

    private fun payloadWithPref(key: String, valueObj: JSONObject): JSONObject =
        validV4Payload().put(
            "sharedPreferences",
            JSONObject().put(
                SENSOR_PREFS,
                JSONObject().put("values", JSONObject().put(key, valueObj))
            )
        )

    private fun validV1Payload(): JSONObject = JSONObject()
        .put("schema", SettingsExporter.SCHEMA)
        .put("schemaVersion", 1)
        .put(
            "sharedPreferences",
            JSONObject().put(
                SENSOR_PREFS,
                JSONObject().put("values", JSONObject().put("sensor_id", pref("string", "v1-sensor")))
            )
        )
        .put("nativeSettingsFiles", JSONObject())

    private fun validV4Payload(entryId: Long = 20L): JSONObject = JSONObject()
        .put("schema", SettingsExporter.SCHEMA)
        .put("schemaVersion", 4)
        .put(
            "sharedPreferences",
            JSONObject().put(
                SENSOR_PREFS,
                JSONObject().put("values", JSONObject().put("sensor_id", pref("string", "UPDATED")))
            )
        )
        .put(
            "nativeSettingsFiles",
            JSONObject().put("settings.dat", nativeFileEntry("updated-settings"))
        )
        .put(
            "journalData",
            JSONObject()
                .put("entries", JSONArray().put(journalEntryJson(entryId, "Entry $entryId")))
                .put("insulinPresets", JSONArray().put(insulinPresetJson(entryId, "Insulin $entryId")))
                .put("foods", JSONArray().put(foodJson(entryId, "Food $entryId")))
                .put("foodDetails", JSONArray().put(JSONObject().put("foodId", entryId).put("brand", "Brand")))
                .put("cloneTombstones", JSONArray().put(JSONObject().put("entryId", entryId + 100).put("deletedAt", 5L)))
        )

    private fun journalEntryJson(id: Long, title: String): JSONObject = JSONObject()
        .put("id", id)
        .put("timestamp", 1_700_000_000_000L)
        .put("entryType", "note")
        .put("title", title)

    private fun insulinPresetJson(id: Long, name: String): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", name)
        .put("onsetMinutes", 15)
        .put("durationMinutes", 300)
        .put("accentColor", 0xFF112233.toInt())

    private fun foodJson(id: Long, name: String): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", name)
        .put("carbsGrams", 25.0)

    private fun pref(type: String, value: Any): JSONObject =
        JSONObject().put("type", type).put("value", value)

    private fun nativeFileEntry(content: String): JSONObject = JSONObject().put(
        "base64",
        Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    )

    private fun clearTestFiles() {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        File(prefsDir, "$SENSOR_PREFS.xml").delete()
        File(prefsDir, "$ALARM_PREFS.xml").delete()
        listOf("settings.dat", "backup.dat", "orbackup.dat").forEach { name ->
            File(context.filesDir, name).deleteRecursively()
            File(context.filesDir, ".$name.restore.tmp").delete()
            File(context.filesDir, ".$name.rollback.tmp").delete()
        }
    }

    private fun setSingleton(type: Class<*>, field: String, database: RoomDatabase?) {
        type.getDeclaredField(field).apply { isAccessible = true }.set(null, database)
    }

    companion object {
        private const val SENSOR_PREFS = "test_sensor_prefs"
        private const val ALARM_PREFS = "test_alarm_prefs"
        private val ORIGINAL_SETTINGS_DAT = "original-settings-dat".toByteArray(Charsets.UTF_8)
        private val ORIGINAL_BACKUP_DAT = "original-backup-dat".toByteArray(Charsets.UTF_8)
    }
}
