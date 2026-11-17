package tk.glucodata.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
}
