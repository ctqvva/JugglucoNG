package tk.glucodata.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.data.food.FoodLibrary
import tk.glucodata.data.food.FoodLibraryDatabase
import tk.glucodata.data.journal.JournalFoodRestoreCommit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsJournalRestoreTests {
    private lateinit var context: Context
    private lateinit var history: HistoryDatabase
    private lateinit var foods: FoodLibraryDatabase
    private val journal get() = history.journalDao()
    private val details get() = foods.foodDetailsDao()

    @Volatile
    private var onHistoryQuery: ((String) -> Unit)? = null

    @Before
    fun open() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        history = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> onHistoryQuery?.invoke(sql) }, Executor { it.run() })
            .build()
        foods = Room.inMemoryDatabaseBuilder(context, FoodLibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        setSingleton(HistoryDatabase::class.java, "INSTANCE", history)
        setSingleton(FoodLibraryDatabase::class.java, "instance", foods)
        SettingsExporter.importPayload(context, payload(10)).getOrThrow()
        Unit
    }

    @After
    fun close() {
        setSingleton(HistoryDatabase::class.java, "INSTANCE", null)
        setSingleton(FoodLibraryDatabase::class.java, "instance", null)
        history.close()
        foods.close()
    }

    @Test
    fun malformedJournalIsRejectedBeforeAnySettingsOrJournalWrites() = runBlocking {
        val before = snapshot()
        val prefs = context.getSharedPreferences("restore_test", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putBoolean("preserve", true).commit())
        val backup = payload(20)
        backup.getJSONObject("journalData").getJSONArray("entries").put("malformed record")

        assertTrue(runCatching { SettingsExporter.validatePayload(backup) }.isFailure)
        val file = File.createTempFile("settings-restore", ".json", context.cacheDir)
        try {
            file.writeText(backup.toString())
            assertTrue(SettingsExporter.importFromJson(context, Uri.fromFile(file)).isFailure)
        } finally {
            file.delete()
        }
        assertEquals(before, snapshot())
        assertTrue(prefs.getBoolean("preserve", false))
    }

    @Test
    fun malformedOptionalFieldsAndDuplicateIdentityAreRejected() = runBlocking {
        val before = snapshot()
        val invalidJournals: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("entries").getJSONObject(0).put("amount", "invalid") },
            { it.put("cloneTombstones", JSONObject()) },
            { it.getJSONArray("foodDetails").getJSONObject(0).put("foodId", 0) },
            { it.getJSONArray("entries").put(entry(21).put("sourceRecordId", "entry-20")) }
        )
        for (makeInvalid in invalidJournals) {
            val backup = payload(20)
            makeInvalid(backup.getJSONObject("journalData"))
            assertTrue(SettingsExporter.importPayload(context, backup).isFailure)
            assertEquals(before, snapshot())
        }
    }

    @Test
    fun insertionFailureRollsBackAllJournalTablesAndFoodDetails() = runBlocking {
        val before = snapshot()
        history.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_entry_insert BEFORE INSERT ON journal_entries WHEN NEW.id = 21
            BEGIN SELECT RAISE(ABORT, 'journal insert failed'); END
            """.trimIndent()
        )
        val backup = payload(20)
        backup.getJSONObject("journalData").getJSONArray("entries").put(entry(21))

        val failure = SettingsExporter.importPayload(context, backup).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("journal insert failed"))
        assertEquals(before, snapshot())
        assertNull(details.getRestoreToken())
    }

    @Test
    fun cancellationAfterDeletingEntriesRollsBackBothDatabases() = runBlocking {
        val before = snapshot()
        val restoring = async(start = CoroutineStart.LAZY) { SettingsExporter.importPayload(context, payload(20)) }
        onHistoryQuery = { sql ->
            // The preceding entry delete has executed when Room issues the preset delete.
            if (sql == "DELETE FROM journal_insulin_presets") restoring.cancel()
        }
        restoring.join()
        onHistoryQuery = null

        assertTrue(restoring.isCancelled)
        assertEquals(before, snapshot())
        assertNull(details.getRestoreToken())
    }

    @Test
    fun foodDetailsFailureLeavesExistingJournalAndFoodLibraryIntact() = runBlocking {
        val before = snapshot()
        foods.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_food_insert BEFORE INSERT ON food_details WHEN NEW.foodId = 20
            BEGIN SELECT RAISE(ABORT, 'food details insert failed'); END
            """.trimIndent()
        )

        val failure = SettingsExporter.importPayload(context, payload(20)).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("food details insert failed"))
        assertEquals(before, snapshot())
        assertNull(details.getRestoreToken())
    }

    @Test
    fun successfulRestoreCompletesBothDatabasesAndLegacyRestoreKeepsFoods() = runBlocking {
        val summary = SettingsExporter.importPayload(context, payload(20)).getOrThrow()
        assertEquals(1, summary.journalEntries)
        assertEquals(1, summary.journalInsulinPresets)
        assertEquals(1, summary.journalFoods)
        assertEquals(20L, journal.getEntries().single().id)
        assertEquals(20L, journal.getInsulinPresets().single().id)
        assertEquals(120L, journal.getCloneJournalTombstones().single().entryId)
        assertEquals(20L, journal.getFoods().single().id)
        assertEquals(20L, details.getAll().single().foodId)
        assertNull(details.getRestoreToken())

        val legacy = payload(30).put("schemaVersion", 2)
        legacy.getJSONObject("journalData").apply {
            remove("foods")
            remove("foodDetails")
            remove("cloneTombstones")
        }
        assertEquals(0, SettingsExporter.importPayload(context, legacy).getOrThrow().journalFoods)
        assertEquals(30L, journal.getEntries().single().id)
        assertEquals(20L, journal.getFoods().single().id)
        assertEquals(20L, details.getAll().single().foodId)
    }

    @Test
    fun interruptedRestoreRecoversFromTheDurableJournalCommit() = runBlocking {
        val original = details.getAll().single()
        val replacement = original.copy(referenceGrams = 200f)

        details.stageRestore("before-commit", listOf(replacement))
        assertEquals(listOf(original), FoodLibrary(journal, details).snapshot().second)
        assertNull(details.getRestoreToken())

        details.stageRestore("after-commit", listOf(replacement))
        history.withTransaction {
            journal.upsertFood(journal.getFoods().single().copy(displayName = "Replaced"))
            journal.recordFoodRestoreCommit(JournalFoodRestoreCommit(token = "after-commit"))
        }
        val (rows, recoveredDetails) = FoodLibrary(journal, details).snapshot()
        assertEquals("Replaced", rows.single().displayName)
        assertEquals(listOf(replacement), recoveredDetails)
        assertNull(details.getRestoreToken())
    }

    @Test
    fun cleanupFailureKeepsACompletedRestoreAndCanBeRetried() = runBlocking {
        val sqlite = foods.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER fail_restore_cleanup BEFORE DELETE ON pending_food_restore
            BEGIN SELECT RAISE(ABORT, 'restore cleanup failed'); END
            """.trimIndent()
        )
        SettingsExporter.importPayload(context, payload(20)).getOrThrow()
        assertEquals(20L, journal.getEntries().single().id)
        assertEquals(20L, details.getAll().single().foodId)
        assertEquals(journal.getFoodRestoreCommit(), details.getRestoreToken())

        sqlite.execSQL("DROP TRIGGER fail_restore_cleanup")
        assertEquals(20L, FoodLibrary(journal, details).foods().single().id)
        assertNull(details.getRestoreToken())
    }

    private suspend fun snapshot(): List<List<Any>> = listOf(
        journal.getEntries(),
        journal.getInsulinPresets(),
        journal.getCloneJournalTombstones(),
        journal.getFoods(),
        details.getAll()
    )

    private fun payload(id: Int): JSONObject {
        val journal = JSONObject()
            .put("entries", JSONArray().put(entry(id)))
            .put("insulinPresets", JSONArray().put(JSONObject()
                .put("id", id).put("displayName", "Insulin $id")
                .put("onsetMinutes", 15).put("durationMinutes", 300).put("accentColor", 0)))
            .put("foods", JSONArray().put(JSONObject()
                .put("id", id).put("displayName", "Food $id").put("carbsGrams", 20)))
            .put("foodDetails", JSONArray().put(JSONObject()
                .put("foodId", id).put("referenceGrams", 100).put("brand", "Brand $id")))
            .put("cloneTombstones", JSONArray().put(JSONObject().put("entryId", id + 100).put("deletedAt", 3)))
        return JSONObject()
            .put("schema", SettingsExporter.SCHEMA)
            .put("schemaVersion", 4)
            .put("sharedPreferences", JSONObject())
            .put("nativeSettingsFiles", JSONObject())
            .put("journalData", journal)
    }

    private fun entry(id: Int) = JSONObject()
        .put("id", id).put("timestamp", 1).put("entryType", "note")
        .put("title", "Note $id").put("sourceRecordId", "entry-$id")

    private fun setSingleton(type: Class<*>, field: String, database: RoomDatabase?) {
        type.getDeclaredField(field).apply { isAccessible = true }.set(null, database)
    }
}
