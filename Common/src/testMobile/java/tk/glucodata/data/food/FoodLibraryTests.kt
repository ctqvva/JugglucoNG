package tk.glucodata.data.food

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.data.journal.JournalFoodUse

/** The library over real databases: what a meal teaches it, and what an edit and an import keep. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class FoodLibraryTests {
    private lateinit var history: HistoryDatabase
    private lateinit var details: FoodLibraryDatabase
    private lateinit var library: FoodLibrary

    private val apple = FoodItem(
        key = "common:apple", source = FoodSource.COMMON, name = "Apple", referenceGrams = 100f, carbs = 11.4f,
        protein = 0.3f, fat = 0.2f, fiber = 2.4f, kcal = 52f, portions = listOf(FoodPortion(FoodPortionKind.PIECE, 182f))
    )

    @Before
    fun open() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        history = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build()
        details = Room.inMemoryDatabaseBuilder(context, FoodLibraryDatabase::class.java).allowMainThreadQueries().build()
        library = FoodLibrary(history.journalDao(), details.foodDetailsDao())
    }

    @After
    fun close() {
        history.close()
        details.close()
    }

    @Test
    fun aFoodThatWasEatenIsRememberedWithItsAmount() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1.5f, 0))), now = 1000L).single()
        val food = library.foods().single()
        assertEquals(id, food.id)
        assertEquals("Apple", food.displayName)
        assertFalse(food.kept)
        assertEquals("common:apple", food.sourceKey)
        assertEquals(100f, food.referenceGrams)
        assertEquals(1000L, food.lastUsedAt)
        assertEquals(FoodAmount(1.5f, 0), food.lastAmount)
        // What the picker gets back the next time: the same food, starting at the same amount.
        val again = food.toFoodItem()
        assertEquals(id, again.libraryId)
        assertEquals(FoodAmount(1.5f, 0), FoodMath.defaultAmount(again))
        assertNull("an estimate is not the person's own figure", again.absorptionMinutes)
    }

    @Test
    fun eatingItAgainIsTheSameFood() = runBlocking {
        val first = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0))), now = 1000L).single()
        // Found on the shelf again, not through the library.
        val second = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(150f))), now = 2000L).single()
        assertEquals(first, second)
        val food = library.foods().single()
        assertEquals(2, food.useCount)
        assertEquals(FoodAmount(150f), food.lastAmount)
    }

    @Test
    fun aScannedProductIsRecognisedByItsBarcode() = runBlocking {
        val product = FoodItem(key = "off:3017620422003", source = FoodSource.OPEN_FOOD_FACTS, name = "Nutella", referenceGrams = 100f, carbs = 57.5f, barcode = "3017620422003")
        val first = library.noteUses(listOf(JournalFoodUse(product, FoodAmount(15f))), now = 1L).single()
        val typedIn = customItem("Nutella jar", 57f).copy(barcode = "3017620422003")
        assertEquals(first, library.noteUses(listOf(JournalFoodUse(typedIn, FoodAmount(20f))), now = 2L).single())
        assertEquals(57f, library.foods().single().carbsGrams, 0f)
    }

    @Test
    fun aFoodTypedFromALabelIsKeptAndNotDoubled() = runBlocking {
        val first = library.noteUses(listOf(JournalFoodUse(customItem("Grandma's pie", 38f), FoodAmount(120f))), now = 1L).single()
        val second = library.noteUses(listOf(JournalFoodUse(customItem("grandma's pie", 38f), FoodAmount(90f))), now = 2L).single()
        assertEquals(first, second)
        assertTrue(library.foods().single().kept)
        // Other numbers under the same name are another food.
        library.noteUses(listOf(JournalFoodUse(customItem("Grandma's pie", 20f), FoodAmount(90f))), now = 3L)
        assertEquals(2, library.foods().size)
    }

    @Test
    fun anOldPresetStaysAPresetWhenUsed() = runBlocking {
        val presetId = history.journalDao().upsertFood(
            JournalFoodEntity(displayName = "Pizza", carbsGrams = 60f, proteinGrams = 25f, fatGrams = 30f, absorptionMinutes = 330, accentColor = 1, isBuiltIn = true, isArchived = false, sortOrder = 7, createdAt = 0, updatedAt = 0)
        )
        val preset = library.foods().single()
        assertTrue(preset.kept)
        assertNull(preset.referenceGrams)
        assertEquals(330, preset.toFoodItem().absorptionMinutes)
        assertEquals(presetId, library.noteUses(listOf(JournalFoodUse(preset.toFoodItem(), FoodAmount(0.5f))), now = 5L).single())
        val used = library.foods().single()
        assertTrue(used.kept)
        assertNull(used.referenceGrams)
        assertEquals(FoodAmount(0.5f), used.lastAmount)
    }

    @Test
    fun onlyTheNewestRememberedFoodsStay() = runBlocking {
        val kept = library.upsert(JournalFoodInput(displayName = "Mine", carbsGrams = 1f, absorptionMinutes = 90, accentColor = 0, referenceGrams = 100f))
        repeat(FoodLibrary.REMEMBERED_LIMIT + 5) { index ->
            val food = apple.copy(key = "common:food$index", name = "Food $index")
            library.noteUses(listOf(JournalFoodUse(food, FoodAmount(100f))), now = 10L + index)
        }
        val foods = library.foods()
        assertEquals(FoodLibrary.REMEMBERED_LIMIT + 1, foods.size)
        assertTrue(foods.any { it.id == kept })
        assertTrue(foods.none { it.displayName == "Food 0" })
        assertTrue(foods.any { it.displayName == "Food ${FoodLibrary.REMEMBERED_LIMIT + 4}" })
        assertEquals(foods.size, details.foodDetailsDao().getAll().size)
    }

    @Test
    fun savingARememberedFoodKeepsItAndWhatWasLearnt() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0))), now = 9L).single()
        val remembered = library.foods().single()
        library.upsert(
            JournalFoodInput(
                id = id, displayName = "Apple, mine", carbsGrams = 12f, absorptionMinutes = 60, accentColor = 5,
                sortOrder = remembered.sortOrder, referenceGrams = 100f, portions = remembered.portions, kept = true
            )
        )
        val saved = library.observe().first().single()
        assertEquals("Apple, mine", saved.displayName)
        assertTrue(saved.kept)
        assertEquals("common:apple", saved.sourceKey)
        assertEquals(1, saved.useCount)
        assertEquals(FoodAmount(1f, 0), saved.lastAmount)
        assertEquals(60, saved.toFoodItem().absorptionMinutes)
    }

    @Test
    fun anAmountInARemovedPortionIsForgotten() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0))), now = 9L).single()
        library.upsert(JournalFoodInput(id = id, displayName = "Apple", carbsGrams = 11.4f, absorptionMinutes = 60, accentColor = 5, referenceGrams = 100f))
        assertNull(library.foods().single().lastAmount)
    }

    @Test
    fun anImportReplacesByNameAndAddsTheRest() = runBlocking {
        val id = library.upsert(JournalFoodInput(displayName = "Borscht", carbsGrams = 4f, absorptionMinutes = 90, accentColor = 42, sortOrder = 3))
        val (added, replaced) = library.import(
            listOf(
                JournalFoodInput(displayName = "borscht", carbsGrams = 4.3f, absorptionMinutes = 120, accentColor = 0, referenceGrams = 300f),
                JournalFoodInput(displayName = "Syrniki", carbsGrams = 18.5f, absorptionMinutes = 120, accentColor = 0, referenceGrams = 100f),
                JournalFoodInput(displayName = "Syrniki", carbsGrams = 19f, absorptionMinutes = 120, accentColor = 0, referenceGrams = 100f)
            )
        )
        assertEquals(1, added)
        assertEquals(2, replaced)
        val foods = library.foods()
        assertEquals(2, foods.size)
        val borscht = foods.first { it.id == id }
        assertEquals(4.3f, borscht.carbsGrams, 0.001f)
        assertEquals(42, borscht.accentColor)
        assertEquals(300f, borscht.referenceGrams)
        assertEquals(19f, foods.first { it.id != id }.carbsGrams, 0.001f)
    }

    @Test
    fun deletingAFoodDeletesWhatWasKnownAboutIt() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0)))).single()
        library.delete(id)
        assertTrue(library.foods().isEmpty())
        assertTrue(details.foodDetailsDao().getAll().isEmpty())
    }

    private fun customItem(name: String, carbs: Float) =
        FoodItem(key = "custom:${System.nanoTime()}", source = FoodSource.CUSTOM, name = name, referenceGrams = 100f, carbs = carbs)

    @Test
    fun bulkDeleteAndUndoPreserveExactDefinitionsAndUsage() = runBlocking {
        val first = library.keep(apple)
        library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1.5f, 0))), now = 999L)
        val second = library.keep(apple.copy(key = "common:pear", name = "Pear"))
        val before = library.snapshot()
        val removed = library.delete(setOf(first, second, Long.MAX_VALUE))
        assertTrue(library.foods().isEmpty())
        assertTrue(details.foodDetailsDao().getAll().isEmpty())
        val newer = library.keep(apple.copy(key = "common:new", name = "New"))
        assertTrue("undo IDs must not be recycled", newer > second)
        library.undoDelete(removed)
        val after = library.snapshot()
        assertEquals(before.first, after.first.filter { it.id != newer })
        assertEquals(before.second.sortedBy { it.foodId }, after.second.filter { it.foodId != newer }.sortedBy { it.foodId })
        assertEquals(FoodAmount(1.5f, 0), library.foods().first { it.id == first }.lastAmount)
    }

    @Test
    fun undoDeleteDoesNotOverwriteAnInterveningRestore() = runBlocking {
        val id = library.keep(apple)
        val removed = library.delete(setOf(id))
        val replacement = removed.rows.single().copy(displayName = "Restored definition", carbsGrams = 70f)
        library.restore(listOf(replacement), emptyList())
        library.undoDelete(removed)
        assertEquals("Restored definition", library.foods().single().displayName)
        assertEquals(70f, library.foods().single().carbsGrams, 0f)
        assertTrue(details.foodDetailsDao().getAll().isEmpty())
    }

    @Test
    fun movingASelectionPreservesBothOrdersAndAllNutrients() = runBlocking {
        val ids = (1..6).map { library.keep(apple.copy(key = "food:$it", name = "Food $it")) }
        library.noteUses(listOf(JournalFoodUse(library.foods()[3].toFoodItem(), FoodAmount(77f))), now = 300L)
        val before = library.snapshot()
        // The selection is deliberately unordered; the stored relative order wins.
        library.moveBefore(setOf(ids[4], ids[2]), ids[1])
        assertEquals(listOf(ids[0], ids[2], ids[4], ids[1], ids[3], ids[5]), library.foods().map { it.id })
        assertEquals(before.second.sortedBy { it.foodId }, library.snapshot().second.sortedBy { it.foodId })
        val originals = before.first.associateBy { it.id }
        library.snapshot().first.forEach { row ->
            val original = originals.getValue(row.id)
            assertEquals(original, row.copy(sortOrder = original.sortOrder, updatedAt = original.updatedAt))
        }
        library.moveBefore(setOf(ids[2], ids[4]), null)
        assertEquals(listOf(ids[0], ids[1], ids[3], ids[5], ids[2], ids[4]), library.foods().map { it.id })
    }

    @Test
    fun bulkArchiveAndStaleMoveRequestsDoNotChangeFoodDetailsOrLoseRows() = runBlocking {
        val first = library.keep(apple)
        val second = library.keep(apple.copy(key = "common:pear", name = "Pear"))
        library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(125f))), now = 100L)
        val beforeDetails = details.foodDetailsDao().getAll()
        library.setArchived(setOf(first, second, 9999), true)
        assertTrue(library.foods().all { it.isArchived })
        library.moveBefore(setOf(first), second) // Neither is in My foods while disabled.
        library.setArchived(setOf(first, second), false)
        assertFalse(library.foods().any { it.isArchived })
        library.moveBefore(setOf(first), 9999)
        assertEquals(listOf(first, second), library.foods().map { it.id })
        assertEquals(beforeDetails, details.foodDetailsDao().getAll())
        assertEquals(FoodAmount(125f), library.foods().first { it.id == first }.lastAmount)
    }

    @Test
    fun bulkActionsHandleMoreThanOneSqliteBatch() = runBlocking {
        history.journalDao().insertFoods((1L..1100L).map { id ->
            JournalFoodEntity(id = id, displayName = "Food $id", carbsGrams = 12f,
                proteinGrams = null, fatGrams = null, absorptionMinutes = 90, accentColor = 0,
                isBuiltIn = false, isArchived = false, sortOrder = id.toInt(), createdAt = 1, updatedAt = 1)
        })
        val ids = (1L..1100L).toSet()
        library.setArchived(ids, true)
        assertEquals(1100, library.foods().count { it.isArchived })
        val removed = library.delete(ids)
        assertEquals(1100, removed.rows.size)
        assertTrue(library.foods().isEmpty())
    }

    @Test
    fun singleAndBulkDeletePreserveBuiltinDefaultsWhileAllowingDisable() = runBlocking {
        val builtin = library.upsert(JournalFoodInput(displayName = "Default", carbsGrams = 15f,
            absorptionMinutes = 60, accentColor = 0, isBuiltIn = true))
        val custom = library.keep(apple)
        library.delete(builtin)
        assertEquals(2, library.foods().size)
        val removed = library.delete(setOf(builtin, custom))
        assertEquals(listOf(custom), removed.rows.map { it.id })
        assertEquals(builtin, library.foods().single().id)
        library.setArchived(setOf(builtin), true)
        assertTrue(library.foods().single().isArchived)
    }

    @Test
    fun customDefinitionsCompareAllMacrosAndKeepAbsorptionInferred() = runBlocking {
        val food = customItem("Label", 20f).copy(protein = 2f, fat = 1f)
        library.noteUses(listOf(JournalFoodUse(food, FoodAmount(25f))))
        val first = library.foods().single()
        assertFalse(first.hasOwnAbsorption)
        assertNull(first.toFoodItem().absorptionMinutes)
        assertEquals(FoodMath.estimatedAbsorptionMinutes(5f, 0.5f, 0.25f), first.absorptionMinutes)
        library.noteUses(listOf(JournalFoodUse(food.copy(protein = 3f), FoodAmount(50f))))
        library.noteUses(listOf(JournalFoodUse(food.copy(fat = 3f), FoodAmount(50f))))
        assertEquals(3, library.foods().size)
    }

    @Test
    fun concurrentImportsAcrossLibraryInstancesDoNotDuplicateOrLoseUses() = runBlocking {
        val input = JournalFoodInput(displayName = "Same", carbsGrams = 10f, absorptionMinutes = 90, accentColor = 0, referenceGrams = 100f)
        val other = FoodLibrary(history.journalDao(), details.foodDetailsDao())
        val jobs = (1..10).map { index ->
            async(kotlinx.coroutines.Dispatchers.Default) { (if (index % 2 == 0) library else other).import(listOf(input)) }
        }
        jobs.forEach { it.await() }
        assertEquals(1, library.foods().size)
        val item = library.foods().single().toFoodItem()
        val uses = (1..10).map { index ->
            async(kotlinx.coroutines.Dispatchers.Default) { (if (index % 2 == 0) library else other).noteUses(listOf(JournalFoodUse(item, FoodAmount(20f)))) }
        }
        uses.forEach { it.await() }
        assertEquals(10, library.foods().single().useCount)
    }

    @Test
    fun oldHistoryRestoreClearsOnlyDetailsOfReplacedFoods() = runBlocking {
        val oldId = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0)))).single()
        library.noteUses(listOf(JournalFoodUse(apple.copy(key = "common:other", name = "Other"), FoodAmount(1f, 0))))
        val row = history.journalDao().getFoodById(oldId)!!.copy(displayName = "Old serving", carbsGrams = 60f)
        library.restore(listOf(row), emptyList())
        val restored = library.foods().first { it.id == oldId }
        assertNull(restored.referenceGrams)
        assertTrue(restored.portions.isEmpty())
        assertNull(restored.barcode)
        assertNull(details.foodDetailsDao().get(oldId))
        assertEquals(1, details.foodDetailsDao().getAll().size)
    }

    @Test
    fun pendingWriteRecoversBeforeAndAfterTheHistoryCommit() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0)))).single()
        val row = history.journalDao().getFoodById(id)!!.copy(displayName = "Edited", carbsGrams = 42f)
        val detail = details.foodDetailsDao().get(id)!!.copy(referenceGrams = 250f)
        val pending = PendingFoodWrite(food = row, detailsJson = FoodDetailsBackup.toJson(listOf(detail)).toString())
        // Process interruption with only the intent committed.
        details.foodDetailsDao().stage(pending)
        val fresh = FoodLibrary(history.journalDao(), details.foodDetailsDao())
        assertEquals(250f, fresh.foods().single().referenceGrams)
        assertEquals(42f, fresh.foods().single().carbsGrams, 0f)
        assertNull(details.foodDetailsDao().pending())
        // Interruption after the history commit but before the details commit; replay is safe.
        details.foodDetailsDao().stage(pending)
        history.journalDao().upsertFood(row)
        assertEquals("Edited", fresh.foods().single().displayName)
        assertEquals(1, fresh.foods().size)
        assertNull(details.foodDetailsDao().pending())
    }

    @Test
    fun deletingTheHighestIdDoesNotReuseItsJournalAssociation() = runBlocking {
        val first = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0)))).single()
        library.delete(first)
        val second = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(1f, 0)))).single()
        assertTrue(second > first)
    }

    @Test
    fun englishAliasStillFindsARememberedTranslatedFood() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple.copy(name = "Яблоко", carbs = 15f), FoodAmount(75f)))).single()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val results = FoodCatalog.search(context, "apple", library.foods())
        val owned = results.library.single { it.libraryId == id }
        assertEquals("Яблоко", owned.name)
        assertEquals(15f, owned.carbs, 0f)
        assertEquals(FoodAmount(75f), owned.lastAmount)
        assertTrue(results.common.none { it.key == "common:apple" })
    }

    @Test
    fun versionOneDetailsMigrateWithoutTouchingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "food-library-v1-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val schema = org.json.JSONObject(java.io.File("schemas/tk.glucodata.data.food.FoodLibraryDatabase/1.json").readText())
            val table = schema.getJSONObject("database").getJSONArray("entities").getJSONObject(0)
            db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", "food_details"))
            db.execSQL("INSERT INTO food_details VALUES (1, 100, 'piece:182', NULL, NULL, NULL, NULL, 'common:apple', 0, 0, 1, 1, 1, 0)")
            db.version = 1
        }
        val migrated = Room.databaseBuilder(context, FoodLibraryDatabase::class.java, name)
            .addMigrations(FoodLibraryDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val row = migrated.foodDetailsDao().getAll().single()
            assertEquals(100f, row.referenceGrams)
            assertFalse(row.hasOwnAbsorption)
            assertNull(migrated.foodDetailsDao().pending())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test
    fun changingWeightToVolumeForgetsTheRememberedAmount() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(75f)))).single()
        library.upsert(JournalFoodInput(id = id, displayName = "Apple drink", carbsGrams = 11.4f,
            absorptionMinutes = 90, accentColor = 0, referenceGrams = 100f, portions = apple.portions, isLiquid = true))
        val saved = library.foods().single()
        assertTrue(saved.isLiquid)
        assertNull(saved.lastAmount)
        assertNull(details.foodDetailsDao().get(id)!!.lastPortionIndex)
    }

    @Test
    fun snapshotsAndObserversKeepTheSameSavedFoodOrder() = runBlocking {
        val last = library.upsert(JournalFoodInput(displayName = "Last", carbsGrams = 1f, absorptionMinutes = 90, accentColor = 0, sortOrder = 20))
        val first = library.upsert(JournalFoodInput(displayName = "First", carbsGrams = 1f, absorptionMinutes = 90, accentColor = 0, sortOrder = 1))
        assertEquals(listOf(first, last), library.foods().map { it.id })
        assertEquals(listOf(first, last), library.observe().first().map { it.id })
        assertEquals(listOf(first, last), FoodCatalog.suggestions(library.foods()).mine.map { it.libraryId })
    }

    @Test
    @Config(shadows = [RefreshSink::class, OutboundSink::class])
    fun cloneImportWaitsForLibraryAllocationAndKeepsItsOwnFood() = runBlocking {
        val realDao = history.journalDao()
        val allocated = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pausedDao = object : tk.glucodata.data.journal.JournalDao by realDao {
            override suspend fun nextFoodId(): Long {
                val id = realDao.nextFoodId()
                allocated.complete(Unit)
                release.await()
                return id
            }
        }
        val editingLibrary = FoodLibrary(pausedDao, details.foodDetailsDao())
        val input = JournalFoodInput(displayName = "Local edit", carbsGrams = 20f, absorptionMinutes = 90, accentColor = 0)
        val editing = async { editingLibrary.upsert(input) }
        allocated.await()
        val file = java.io.File.createTempFile("clone-food-race", ".gz")
        val incoming = JournalFoodEntity(displayName = "Clone food", carbsGrams = 60f, proteinGrams = null, fatGrams = null,
            absorptionMinutes = 90, accentColor = 0, isBuiltIn = false, isArchived = false, sortOrder = 0, createdAt = 1, updatedAt = 1)
        val entry = tk.glucodata.data.journal.JournalEntryEntity(timestamp = 1, sensorSerial = null, entryType = "carbs", title = "Imported",
            note = null, amount = 60f, glucoseValueMgDl = null, durationMinutes = 90, intensity = null, insulinPresetId = null,
            source = "manual", sourceRecordId = null, createdAt = 1, updatedAt = 1)
        val record = tk.glucodata.data.journal.CloneJournalEntryRecord("a".repeat(32), "clone:11111111-1111-1111-1111-111111111111:journal:1:carbs", entry, null, incoming)
        val types = tk.glucodata.data.CloneGlucoseRecoveryRecords.recordTypes + tk.glucodata.data.journal.CloneJournalRecoveryRecords.recordTypes
        val stats = tk.glucodata.CloneRecoveryPackageIO.write(file, types) { sink ->
            sink.write(tk.glucodata.data.journal.CloneJournalRecoveryRecords.ENTRY, tk.glucodata.data.journal.CloneJournalRecoveryRecords.encode(record))
        }
        val manifest = tk.glucodata.CloneRecoveryManifest(
            tk.glucodata.CloneHistoryRecoveryProtocol.PROTOCOL_VERSION, "b".repeat(32),
            tk.glucodata.CloneRecoveryDirection.SEND_TO_RECEIVER, tk.glucodata.CloneRecoveryMode.ONLY_MISSING,
            tk.glucodata.CloneRecoveryCategories.GLUCOSE or tk.glucodata.CloneRecoveryCategories.JOURNAL,
            stats.compressedBytes, stats.uncompressedBytes, stats.recordCounts, stats.sha256)
        try {
            val importing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                tk.glucodata.data.CloneHistoryRecoveryStore(history, library).importPackage(file, manifest, "clone")
            }
            assertNull("Clone import must wait while library ID allocation is paused",
                kotlinx.coroutines.withTimeoutOrNull(1000) { importing.await(); true })
            assertTrue(realDao.getEntries().isEmpty())
            release.complete(Unit)
            val localId = editing.await()
            importing.await()
            val imported = realDao.getEntries().single()
            assertTrue(imported.foodId != localId)
            assertEquals("Clone food", realDao.getFoodById(imported.foodId!!)!!.displayName)
            assertEquals("Local edit", realDao.getFoodById(localId)!!.displayName)
            assertEquals(2, library.foods().size)
        } finally { release.complete(Unit); file.delete() }
    }

    // Exercise the real package reader, Clone importer, Room transaction and food allocation.
    // Native notification delivery and outbound refresh are outside this storage regression.
    @org.robolectric.annotation.Implements(tk.glucodata.UiRefreshBus::class)
    class RefreshSink {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation fun requestDataRefresh() {}
        }
    }

    @org.robolectric.annotation.Implements(tk.glucodata.OutboundApiJournalSnapshot::class)
    class OutboundSink {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation fun mirroredJournalChanged() {}
        }
    }

    @Test
    fun savingAForeignNamedProductIsIdempotentAndKeepsItsDefinition() = runBlocking {
        val product = apple.copy(key = "off:3017620422003", source = FoodSource.OPEN_FOOD_FACTS,
            name = "Chaussons aux pommes", barcode = "3017620422003")
        val jobs = (1..5).map { async(kotlinx.coroutines.Dispatchers.Default) { library.keep(product) } }
        val ids = jobs.map { it.await() }
        assertEquals(1, ids.distinct().size)
        val id = ids.first()
        assertEquals(1, library.foods().size)
        assertEquals(product.name, library.foods().single().displayName)
        assertFalse(library.foods().single().hasOwnAbsorption)
        library.upsert(product.toLibraryInput(10).copy(id = id, displayName = "Моя слойка", carbsGrams = 25f))
        assertEquals(id, library.keep(product.copy(key = "off:other", name = "Apple pastry")))
        assertEquals("Моя слойка", library.foods().single().displayName)
        assertEquals(25f, library.foods().single().carbsGrams, 0f)
    }

    @Test
    fun keepingARememberedFoodPreservesItsAmountAndUsage() = runBlocking {
        val id = library.noteUses(listOf(JournalFoodUse(apple, FoodAmount(75f))), now = 100L).single()
        assertEquals(id, library.keep(apple))
        val kept = library.foods().single()
        assertTrue(kept.kept)
        assertEquals(1, kept.useCount)
        assertEquals(FoodAmount(75f), kept.lastAmount)
        assertFalse(kept.hasOwnAbsorption)
    }

}
