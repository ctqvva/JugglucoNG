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
import tk.glucodata.data.HistoryRepository.BucketReplaceOutcome

/** A batch that replaces whole intervals of a sensor's history, and nothing outside them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HistoryIntervalReplacementTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HistoryIntervalReplacementTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    private lateinit var database: HistoryDatabase
    private lateinit var repository: HistoryRepository
    private var previousApp: Applic? = null
    private val serial = "X-000000TEST"
    private val otherSerial = "X-000001TEST"
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

    private fun at(minute: Int) = base + minute * 60_000L

    private fun reading(minute: Int, value: Float, sensor: String = serial) = HistoryReading(
        timestamp = at(minute), sensorSerial = sensor, value = value, rawValue = 0f, rate = null,
        firstStoredAt = base,
    )

    private fun stored(sensor: String = serial): List<Pair<Int, Float>> = runBlocking {
        database.historyDao().getReadingsSinceForSensor(sensor, 0L)
            .map { ((it.timestamp - base) / 60_000L).toInt() to it.value }
    }

    private fun replace(batch: List<HistoryReading>, vararg intervals: LongRange) = runBlocking {
        repository.replaceSensorBuckets(serial, batch, 60_000L, replacedIntervalsMs = intervals.toList())
    }

    /** Real readings every 3 minutes; ghosts one minute after each, as a UTC offset leaves them. */
    private fun seed() = runBlocking {
        database.historyDao().insertAll(
            (0..30 step 3).map { reading(it, 100f + it) } + (1..31 step 3).map { reading(it, 300f) } +
                reading(10, 55f, otherSerial)
        )
    }

    @Test
    fun onlyTheReplacedIntervalLosesItsOtherRows() {
        seed()
        val batch = (9..21 step 3).map { reading(it, 200f + it) }

        assertEquals(BucketReplaceOutcome.STORED, replace(batch, at(9)..at(21)))

        val expected = (0..30 step 3).map { it to (if (it in 9..21) 200f + it else 100f + it) } +
            (1..31 step 3).filter { it !in 9..21 }.map { it to 300f }
        assertEquals(expected.sortedBy { it.first }, stored())
        assertEquals(listOf(10 to 55f), stored(otherSerial))
    }

    @Test
    fun rowsBetweenTwoReplacedIntervalsStay() {
        seed()
        val batch = listOf(3, 6, 18, 21).map { reading(it, 200f + it) }

        assertEquals(BucketReplaceOutcome.STORED, replace(batch, at(3)..at(6), at(18)..at(21)))

        // 9..15 had no replacement: kept, real and ghost alike.
        assertEquals(
            listOf(7 to 300f, 9 to 109f, 10 to 300f, 12 to 112f, 13 to 300f, 15 to 115f, 16 to 300f),
            stored().filter { it.first in 7..17 },
        )
        assertEquals(listOf(3 to 203f, 6 to 206f), stored().filter { it.first in 3..6 })
    }

    @Test
    fun aFailedWriteKeepsEveryRowItWouldHaveReplacedAndARetrySucceeds() {
        seed()
        val before = stored()
        val batch = (0..30 step 3).map { reading(it, 200f + it) }
        // Fails on the insert, after the interval was already deleted inside the transaction.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_history_insert BEFORE INSERT ON history_readings " +
                "BEGIN SELECT RAISE(ABORT, 'forced replacement failure'); END"
        )
        assertEquals(BucketReplaceOutcome.FAILED, replace(batch, at(0)..at(30)))
        assertEquals(before, stored())

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_history_insert")
        assertEquals(BucketReplaceOutcome.STORED, replace(batch, at(0)..at(30)))
        assertEquals((0..30 step 3).map { it to 200f + it } + (31 to 300f), stored())
    }

    @Test
    fun aBatchTheUserDeletedReplacesNothing() = runBlocking {
        seed()
        val batch = listOf(reading(3, 203f), reading(6, 206f))
        batch.forEach { repository.deleteReadingRows(listOf(serial), it.timestamp) }
        val before = stored()

        assertEquals(BucketReplaceOutcome.NOTHING_TO_STORE, replace(batch, at(3)..at(6)))
        assertEquals(before, stored())
    }

    @Test
    fun aDeletionIsFoundAnywhereInItsMinuteAndOnlyForItsSensor() = runBlocking {
        seed()
        repository.deleteReadingRows(listOf(serial), at(30))

        assertEquals(true, repository.isMinuteDeletedByUser(serial, at(30) + 417L))
        assertEquals(false, repository.isMinuteDeletedByUser(serial, at(29) + 59_999L))
        assertEquals(false, repository.isMinuteDeletedByUser(serial, at(31)))
        assertEquals(false, repository.isMinuteDeletedByUser(otherSerial, at(30)))
    }

    @Test
    fun replacingRowsKeepsTheirProvenance() = runBlocking {
        seed()
        replace((0..30 step 3).map { reading(it, 200f).copy(firstStoredAt = base + 1) }, at(0)..at(30))
        val rows = database.historyDao().getReadingsSinceForSensor(serial, 0L)
        assertEquals(setOf(base), rows.map { it.firstStoredAt }.toSet())
    }
}
