package tk.glucodata.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.DataSmoothing
import tk.glucodata.SensorIdentity
import tk.glucodata.chart.HistoryChartModelBuilder
import tk.glucodata.chart.MainSensorOwnership
import tk.glucodata.drivers.ManagedSensorRuntime

/** Synthetic smoothed records: only the middle value equals its sensor source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SmoothedRecordedHistoryTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmoothedRecordedHistoryTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    private lateinit var database: HistoryDatabase
    private lateinit var repository: HistoryRepository
    private var previousApp: Applic? = null
    private val serial = "X-000000TEST"
    private val base = 1_700_000_040_000L
    private lateinit var readings: List<HistoryReading>
    private lateinit var records: List<ReadingDisplay>

    @Before fun setUp() = runBlocking {
        previousApp = Applic.app
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE).edit()
            .putInt("algorithmAppliedMode_$serial", 9).commit()
        SensorIdentity.invalidateCaches()
        assertTrue(ManagedSensorRuntime.integratesUserCalibration(serial, false))
        database = Room.inMemoryDatabaseBuilder(Applic.app, HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = HistoryRepository(database)
        val rows = javaClass.getResourceAsStream("/smoothed-recorded-history.tsv")!!.bufferedReader().useLines { lines ->
            lines.drop(1).map { it.split('\t').map(String::toFloat) }.toList()
        }
        readings = rows.mapIndexed { index, row ->
            HistoryReading(timestamp = base + index * 60_000L, sensorSerial = serial,
                value = row[0], rawValue = row[1], rate = null)
        }
        records = rows.mapIndexed { index, row ->
            ReadingDisplay(base + index * 60_000L, serial, row[2], 2, 1L, base)
        }
        database.historyDao().insertAll(readings)
        database.readingDisplayDao().sealAll(records)
        Unit
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        Applic.app = previousApp
        SensorIdentity.invalidateCaches()
    }

    @Test fun chartAndStatsQueriesKeepEveryRecordIncludingSmoothedValues() = runBlocking {
        val expected = records.map { it.displayMgdl }
        val histories = listOf(
            repository.getHistoryForDisplaySensor(serial, base),
            repository.getHistoryFlowForDisplaySensor(serial, base).first(),
            repository.getDisplayHistoryForStats(serial, base),
            repository.getDisplayHistoryFlowForStats(serial, base).first()
        )
        histories.forEach { history ->
            assertEquals(expected, history.map { it.sealedDisplayValue })
            val source = history.map { point ->
                tk.glucodata.GlucosePoint(point.timestamp, point.value, point.rawValue).also {
                    it.sensorSerial = serial
                    it.sealedDisplayValue = point.sealedDisplayValue ?: Float.NaN
                    it.sealedDisplayViewMode = point.sealedDisplayViewMode ?: -1
                }
            }
            val smoothed = DataSmoothing.smoothNativePoints(source, 3, false)
            for (mode in 0..3) {
                val model = HistoryChartModelBuilder.build(
                    listOf(HistoryChartModelBuilder.SeriesInput(serial, true, mode, 0, smoothed)),
                    MainSensorOwnership.NONE, HistoryChartModelBuilder.Calibration { _, _, _, _ -> null }
                )
                assertEquals(expected, model.primary!!.runs.flatMap { it.points }.map { it.value })
            }
        }
        assertEquals(readings.map { it.value }, database.historyDao().getReadingsSince(base).map { it.value })
    }

    @Test fun replayingUnchangedSensorNumbersMustKeepSmoothedRecords() = runBlocking {
        assertTrue(repository.storeReadingsReplacingSensorBuckets(serial, readings, 60_000L,
            invalidateAutoDisplayRecords = true))
        assertEquals(records, database.readingDisplayDao().getAllSince(base))
        assertEquals(records.map { it.displayMgdl },
            repository.getHistoryForDisplaySensor(serial, base).map { it.sealedDisplayValue })
    }

    @Test fun aRealRewriteInvalidatesOnlyTheChangedSourcesInTheSameTransaction() = runBlocking {
        val changed = readings.mapIndexed { index, row -> if (index == 2) row.copy(value = row.value + 12f) else row }
        assertTrue(repository.storeReadingsReplacingSensorBuckets(serial, changed, 60_000L,
            invalidateAutoDisplayRecords = true))
        assertEquals(records.filterIndexed { index, _ -> index != 2 }, database.readingDisplayDao().getAllSince(base))
        assertEquals(changed.map { it.value }, database.historyDao().getReadingsSince(base).map { it.value })
    }

    @Test fun failedReplacementCannotInvalidateRecords() = runBlocking {
        // Fail after the transaction has invalidated a record and deleted the
        // old source row, so this exercises rollback rather than input guards.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_history_insert BEFORE INSERT ON history_readings " +
                "BEGIN SELECT RAISE(ABORT, 'forced replacement failure'); END"
        )
        val changed = readings.mapIndexed { index, row -> if (index == 2) row.copy(value = row.value + 12f) else row }
        assertFalse(repository.storeReadingsReplacingSensorBuckets(serial, changed, 60_000L,
            invalidateAutoDisplayRecords = true))
        assertEquals(records, database.readingDisplayDao().getAllSince(base))
        assertEquals(readings.map { it.value }, database.historyDao().getReadingsSince(base).map { it.value })
    }
}
