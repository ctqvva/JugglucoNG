package tk.glucodata.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import tk.glucodata.data.journal.deleteAllJournalEntries

/**
 * "Clear history": what it removes, what it leaves, and that what it removed stays removed.
 *
 * The readings half has to survive a sync. A sensor that keeps its own history is read back into
 * Room, so clearing without a tombstone per row was undone within seconds on every Libre; these
 * pin that the tombstones are written and that the store refuses the rows afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ClearHistoryTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClearHistoryTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    private lateinit var database: HistoryDatabase
    private lateinit var repository: HistoryRepository
    private var previousApp: Applic? = null
    private val serial = "X-000000TEST"
    private val base = 1_700_000_040_000L

    @Before
    fun setUp() {
        previousApp = Applic.app
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        SensorIdentity.invalidateCaches()
        database = Room.inMemoryDatabaseBuilder(Applic.app, HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = HistoryRepository(database)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        Applic.app = previousApp
        SensorIdentity.invalidateCaches()
    }

    private fun reading(minute: Int) = HistoryReading(
        timestamp = base + minute * 60_000L,
        sensorSerial = serial,
        value = 100f + minute,
        rawValue = 98f + minute,
        rate = null,
    )

    private fun seedReadings(count: Int): List<HistoryReading> = runBlocking {
        val rows = (0 until count).map(::reading)
        database.historyDao().insertAll(rows)
        database.readingDisplayDao().sealAll(
            rows.map { ReadingDisplay(it.timestamp, serial, it.value, 2, 1L, base) }
        )
        database.readingUncertaintyDao().insertAll(
            rows.map { ReadingUncertainty(serial, it.timestamp, it.value - 5f, it.value + 5f, 0.9f, null, null) }
        )
        rows
    }

    private fun entry(
        id: Long,
        source: JournalEntrySource,
        type: JournalEntryType = JournalEntryType.INSULIN,
        glucose: Float? = null,
        nsRemoteId: String? = null,
    ) = JournalEntryEntity(
        id = id,
        timestamp = base + id * 60_000L,
        sensorSerial = serial,
        entryType = type.storageValue,
        title = "entry $id",
        note = null,
        amount = if (glucose == null) 3f else null,
        glucoseValueMgDl = glucose,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        source = source.storageValue,
        sourceRecordId = null,
        recoveryId = null,
        createdAt = 0L,
        updatedAt = 0L,
        nsRemoteId = nsRemoteId,
    )

    private fun seedJournalLibraries() = runBlocking {
        database.journalDao().upsertInsulinPreset(
            JournalInsulinPresetEntity(
                displayName = "Rapid", onsetMinutes = 15, durationMinutes = 240, accentColor = 0,
                curveJson = "[]", isBuiltIn = false, isArchived = false, countsTowardIob = true, sortOrder = 0,
            )
        )
        database.journalDao().upsertFood(
            JournalFoodEntity(
                displayName = "Apple", carbsGrams = 12f, proteinGrams = null, fatGrams = null,
                absorptionMinutes = 60, accentColor = 0, isBuiltIn = false, isArchived = false,
                sortOrder = 0, createdAt = 0L, updatedAt = 0L,
            )
        )
    }

    // ── Readings ──

    @Test
    fun clearingReadingsRemovesThemAndWhatHangsOffThem() = runBlocking {
        seedReadings(3)

        val removed = repository.clearAllReadings()

        assertEquals(3, removed)
        assertEquals(0, database.historyDao().getCount())
        assertEquals(0, database.readingDisplayDao().getCount())
        assertTrue(database.readingUncertaintyDao().getBetween(0L, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun clearingReadingsLeavesTheJournalAndItsLibraries() = runBlocking {
        seedReadings(2)
        seedJournalLibraries()
        database.journalDao().upsertEntry(entry(1, JournalEntrySource.MANUAL))

        repository.clearAllReadings()

        assertEquals(1, database.journalDao().getEntries().size)
        assertEquals(1, database.journalDao().getInsulinPresets().size)
        assertEquals(1, database.journalDao().getFoods().size)
    }

    @Test
    fun clearedReadingsAreNotStoredAgainWhenASyncOffersThem() = runBlocking {
        val rows = seedReadings(3)
        repository.clearAllReadings()

        // What the next native sync does: offers everything the sensor still holds.
        repository.storeReadings(rows)
        assertEquals("a cleared reading came back", 0, database.historyDao().getCount())

        // Readings that arrive after the clear are untouched by it.
        repository.storeReadings(listOf(reading(10)))
        assertEquals(1, database.historyDao().getCount())
    }

    @Test
    fun syncResumesAfterWhatWasClearedInsteadOfFromZero() = runBlocking {
        val rows = seedReadings(5)
        val newest = rows.maxOf { it.timestamp }
        assertEquals(newest, repository.getSyncTailForSensor(serial))

        repository.clearAllReadings()

        assertEquals("nothing stored", 0L, repository.getLatestTimestampForSensor(serial))
        assertEquals("but sync must not restart from zero", newest, repository.getSyncTailForSensor(serial))
    }

    @Test
    fun aNewerTombstoneFromAPeerDoesNotMoveTheSyncTailPastStoredReadings() = runBlocking {
        val rows = seedReadings(3)
        val storedNewest = rows.maxOf { it.timestamp }
        // A Clone peer deleted a reading this phone never stored, a day later than anything here.
        database.historyDao().insertDeletedReadings(
            listOf(DeletedHistoryReading(storedNewest + 24 * 60 * 60_000L, serial, 1L))
        )

        assertEquals(
            "native readings between the two are still missing and must be read",
            storedNewest,
            repository.getSyncTailForSensor(serial),
        )
    }

    @Test
    fun clearingAnEmptyStoreIsHarmless() = runBlocking {
        assertEquals(0, repository.clearAllReadings())
        assertEquals(0L, repository.getSyncTailForSensor(serial))
    }

    // ── Both halves in one transaction ──

    @Test
    fun clearingBothRemovesReadingsAndEntriesTogether() = runBlocking {
        seedReadings(2)
        database.journalDao().upsertEntry(entry(1, JournalEntrySource.MANUAL))

        val deleted = database.clearHistoryRows(ClearScope.BOTH)

        assertEquals(1, deleted?.count)
        assertEquals(0, database.historyDao().getCount())
        assertTrue(database.journalDao().getEntries().isEmpty())
    }

    @Test
    fun clearingOneScopeLeavesTheOtherAlone() = runBlocking {
        seedReadings(2)
        database.journalDao().upsertEntry(entry(1, JournalEntrySource.MANUAL))

        val readingsOnly = database.clearHistoryRows(ClearScope.READINGS)
        assertEquals("the journal was not part of it", null, readingsOnly)
        assertEquals(0, database.historyDao().getCount())
        assertEquals(1, database.journalDao().getEntries().size)

        seedReadings(2)
        val journalOnly = database.clearHistoryRows(ClearScope.JOURNAL)
        assertEquals(1, journalOnly?.count)
        assertEquals(2, database.historyDao().getCount())
        assertTrue(database.journalDao().getEntries().isEmpty())
    }

    @Test
    fun clearingBothLeavesTheReadingsAloneWhenTheJournalHalfFails() = runBlocking {
        seedReadings(3)
        database.journalDao().upsertEntry(entry(1, JournalEntrySource.MANUAL))
        // Make the journal half fail partway through the transaction.
        database.openHelper.writableDatabase.execSQL("ALTER TABLE journal_entries RENAME TO journal_entries_gone")

        val failure = runCatching { database.clearHistoryRows(ClearScope.BOTH) }.exceptionOrNull()

        assertTrue("the clear should have failed", failure != null)
        assertEquals("readings must not be half-cleared", 3, database.historyDao().getCount())
        assertTrue(
            "no tombstones from the rolled-back clear",
            database.historyDao().getLatestDeletedTimestampForSensors(listOf(serial)) == null,
        )
        assertEquals(3, database.readingDisplayDao().getCount())
    }

    // ── Journal ──

    @Test
    fun clearingTheJournalRemovesEntriesButKeepsInsulinTypesAndFoods() = runBlocking {
        seedJournalLibraries()
        seedReadings(2)
        database.journalDao().upsertEntry(entry(1, JournalEntrySource.MANUAL))
        database.journalDao().upsertEntry(entry(2, JournalEntrySource.MANUAL, JournalEntryType.CARBS))

        val deleted = database.deleteAllJournalEntries()

        assertEquals(2, deleted.count)
        assertTrue(deleted.affectsIob)
        assertTrue(database.journalDao().getEntries().isEmpty())
        assertEquals(1, database.journalDao().getInsulinPresets().size)
        assertEquals(1, database.journalDao().getFoods().size)
        assertEquals("readings are not the journal's", 2, database.historyDao().getCount())
    }

    @Test
    fun clearingTheJournalFollowsTheRulesOfDeletingOneEntry() = runBlocking {
        val dao = database.journalDao()
        dao.upsertEntry(entry(1, JournalEntrySource.MANUAL, nsRemoteId = "ns-1"))
        dao.upsertEntry(entry(2, JournalEntrySource.MANUAL))
        // Came from Nightscout itself: nothing of ours is stored there to delete.
        dao.upsertEntry(entry(3, JournalEntrySource.NIGHTSCOUT, nsRemoteId = "ns-3"))

        val deleted = database.deleteAllJournalEntries(now = 42L)

        assertEquals(3, deleted.count)
        assertTrue("a Clone peer is told about the manual entries", deleted.cloneTombstoned)
        assertEquals(setOf(1L, 2L), dao.getCloneJournalTombstones().map { it.entryId }.toSet())
        assertEquals(
            "only an entry we sent to Nightscout is queued for deletion there",
            listOf("ns-1"),
            dao.getPendingNightscoutDeletes().map { it.nsRemoteId },
        )
    }

    @Test
    fun clearingTheJournalReportsFingersticksSoCalibrationsCanFollow() = runBlocking {
        database.journalDao().upsertEntry(
            entry(1, JournalEntrySource.MANUAL, JournalEntryType.FINGERSTICK, glucose = 110f)
        )

        val deleted = database.deleteAllJournalEntries()

        assertTrue(deleted.hadGlucose)
    }

    @Test
    fun clearingAnEmptyJournalReportsNothing() = runBlocking {
        val deleted = database.deleteAllJournalEntries()

        assertEquals(0, deleted.count)
        assertTrue(!deleted.affectsIob && !deleted.hadGlucose && !deleted.cloneTombstoned)
    }
}
