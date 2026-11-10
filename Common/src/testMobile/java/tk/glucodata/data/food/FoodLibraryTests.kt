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
        library.noteUses(listOf(JournalFoodUse(apple.copy(name = "Яблоко"), FoodAmount(100f))))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val results = FoodCatalog.search(context, "apple", library.foods())
        assertTrue(results.library.isNotEmpty() || results.common.any { it.key == "common:apple" })
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

}
