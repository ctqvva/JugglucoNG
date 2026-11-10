package tk.glucodata.data.prediction.analysis

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Storage of recorded forecasts: round trip, one record per moment, retention, deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecordedForecastStoreTests {

    private lateinit var database: PredictionAnalysisDatabase
    private lateinit var dao: RecordedForecastDao
    private val t0 = 1_790_000_000_000L

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PredictionAnalysisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.recordedForecastDao()
    }

    @After
    fun close() = database.close()

    private fun record(origin: Long, model: ForecastModel = ForecastModel.STANDARD, values: FloatArray = floatArrayOf(101f, 102f, Float.NaN, 104f)) =
        RecordedForecastEntity(
            originTime = origin, recordedAt = origin + 20_000L, sensorSerial = "S1", lane = ForecastLane.CALIBRATED_AUTO,
            model = model.storageId, modelVersion = 3, configHash = 42L, shown = true, fallback = false,
            baselineMgdl = 100f, stepMinutes = 5, valuesMgdl = ForecastValues.encode(values),
            trendMgdlPerMin = 0.5f, plannedEntries = 1, journalEntries = 4,
        )

    @Test
    fun aRecordComesBackExactlyAsStored() = runBlocking {
        dao.insert(record(t0))
        val back = dao.between(t0, t0).single()
        assertEquals("S1", back.sensorSerial)
        assertEquals(ForecastLane.CALIBRATED_AUTO, back.lane)
        assertEquals(3, back.modelVersion)
        assertEquals(42L, back.configHash)
        assertEquals(0.5f, back.trendMgdlPerMin!!, 0f)
        assertArrayEquals(floatArrayOf(101f, 102f, Float.NaN, 104f), back.values, 0f)
        assertEquals(100f, back.valueAt(0), 0f)
        assertEquals(102f, back.valueAt(10), 0f)
        assertTrue(back.valueAt(15).isNaN())
        assertTrue("past the stored curve", back.valueAt(25).isNaN())
        assertTrue("off the step", back.valueAt(7).isNaN())
    }

    @Test
    fun aMomentIsRecordedOncePerModel() = runBlocking {
        assertTrue(dao.insert(record(t0)) > 0)
        assertEquals(-1L, dao.insert(record(t0, values = floatArrayOf(999f))))
        assertTrue(dao.insert(record(t0, model = ForecastModel.ADAPTIVE)) > 0)
        assertEquals(2, dao.count())
        assertArrayEquals("the first record is kept, not replaced", floatArrayOf(101f, 102f, Float.NaN, 104f),
            dao.between(t0, t0).first { it.model == ForecastModel.STANDARD.storageId }.values, 0f)
        assertEquals(t0, dao.latestOrigin(ForecastModel.STANDARD.storageId))
    }

    @Test
    fun retentionDeletesOnlyOlderRecords() = runBlocking {
        val day = 24L * 60 * 60_000
        dao.insert(record(t0 - 100 * day))
        dao.insert(record(t0 - 10 * day))
        dao.insert(record(t0))
        assertEquals(1, dao.deleteOlderThan(t0 - PredictionRecorder.RETENTION_DAYS * day))
        assertEquals(2, dao.count())
        assertEquals(t0 - 10 * day, dao.oldestOrigin())
    }

    @Test
    fun deletingEverythingEmptiesTheStore() = runBlocking {
        dao.insert(record(t0))
        dao.insert(record(t0, model = ForecastModel.ADAPTIVE))
        assertEquals(2, dao.deleteAll())
        assertEquals(0, dao.count())
        assertEquals(null, dao.latestOrigin(ForecastModel.STANDARD.storageId))
    }
}
