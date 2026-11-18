package tk.glucodata.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity

/**
 * Deleting one reading from a row offers Undo. Undo has to put back exactly what the delete took:
 * the row itself, and no tombstone of its own left behind to make the next sync refuse it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DeleteReadingUndoTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeleteReadingUndoTests {
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
        HistoryRepository.resetTimestampIndexForTests()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        HistoryRepository.resetTimestampIndexForTests()
        Applic.app = previousApp
        SensorIdentity.invalidateCaches()
    }

    private fun reading(minute: Int) = HistoryReading(
        timestamp = base + minute * 60_000L,
        sensorSerial = serial,
        value = 100f + minute,
        rawValue = 98f + minute,
        rate = 0.5f,
        firstStoredAt = base,
    )

    @Test
    fun undoPutsTheReadingBackAndLiftsItsTombstone() = runBlocking {
        val dao = database.historyDao()
        dao.insertAll((0 until 3).map(::reading))
        val target = reading(1)

        val deleted = repository.deleteReadingRows(listOf(serial), target.timestamp)
        assertEquals(1, deleted.removedCount)
        assertEquals(2, dao.getCount())
        assertEquals(1, dao.isReadingDeleted(serial, target.timestamp))

        repository.restoreReadingRows(deleted)

        assertEquals(3, dao.getCount())
        assertEquals(0, dao.isReadingDeleted(serial, target.timestamp))
        val restored = dao.getSensorReadingsAtTimestamps(serial, listOf(target.timestamp)).single()
        assertEquals(target.value, restored.value)
        assertEquals(target.rawValue, restored.rawValue)
        assertEquals(target.rate, restored.rate)
        assertEquals(target.firstStoredAt, restored.firstStoredAt)
    }

    @Test
    fun undoKeepsATombstoneThatWasThereBefore() = runBlocking {
        val dao = database.historyDao()
        val target = reading(0)
        dao.insertAll(listOf(target))
        dao.insertDeletedReadings(listOf(DeletedHistoryReading(target.timestamp, serial, base)))

        val deleted = repository.deleteReadingRows(listOf(serial), target.timestamp)
        repository.restoreReadingRows(deleted)

        assertEquals(1, dao.getCount())
        assertEquals(1, dao.isReadingDeleted(serial, target.timestamp))
    }

    /**
     * Undo brings rows back under their old ids, which the index's id delta never sees. Restore A
     * and delete B between two refreshes and count, extents and max id all match what the index
     * already holds, so without an invalidate it kept A missing and B present.
     */
    @Test
    fun indexSeesTheRestoredReadingAndNotTheNextDeletedOne() = runBlocking {
        val dao = database.historyDao()
        dao.insertAll((0 until 5).map(::reading))
        val index = HistoryRepository.timestampIndex(dao)
        val a = reading(1)
        val b = reading(3)
        val minuteOf = { ts: Long -> ts / 60_000L }
        val covered = { ts: Long ->
            runBlocking { index.withIndex { it.hasReadingInMinute(listOf(serial), minuteOf(ts)) } }
        }

        assertTrue(covered(a.timestamp))
        val deletedA = repository.deleteReadingRows(listOf(serial), a.timestamp)
        assertFalse(covered(a.timestamp))

        repository.restoreReadingRows(deletedA)
        repository.deleteReadingRows(listOf(serial), b.timestamp)

        assertTrue(covered(a.timestamp))
        assertFalse(covered(b.timestamp))
    }

    /** Reads pages from the real table, then runs [afterPage] once: a write landing mid-rebuild. */
    private class MidRebuildDao(private val real: HistoryDao) : HistoryDao by real {
        var afterPage: (suspend () -> Unit)? = null

        override suspend fun getTimestampsForSensorPage(serial: String, afterTimestamp: Long, limit: Int): List<Long> {
            val page = real.getTimestampsForSensorPage(serial, afterTimestamp, limit)
            afterPage?.let { hook ->
                afterPage = null
                hook()
            }
            return page
        }
    }

    /**
     * The undo's invalidate can arrive while a rebuild is reading pages it already read before
     * the restore. Clearing a stale flag at the end of that rebuild swallowed it, and the index
     * kept A missing and B present until some unrelated write forced another rebuild.
     */
    @Test
    fun anInvalidateDuringARebuildIsNotLost() = runBlocking {
        val dao = database.historyDao()
        dao.insertAll((0 until 5).map(::reading))
        val hooked = MidRebuildDao(dao)
        val index = HistoryTimestampIndexTracker(hooked)
        val a = reading(1)
        val b = reading(3)
        val covered = { ts: Long ->
            runBlocking { index.withIndex { it.hasReadingInMinute(listOf(serial), ts / 60_000L) } }
        }

        assertTrue(covered(a.timestamp))
        val deletedA = repository.deleteReadingRows(listOf(serial), a.timestamp)
        hooked.afterPage = {
            repository.restoreReadingRows(deletedA)
            repository.deleteReadingRows(listOf(serial), b.timestamp)
            index.invalidate()
        }
        // This refresh rebuilds (one row fewer) and, mid-read, the undo and the next delete land.
        assertFalse(covered(a.timestamp))
        assertTrue(hooked.afterPage == null)

        assertTrue(covered(a.timestamp))
        assertFalse(covered(b.timestamp))
    }
}
