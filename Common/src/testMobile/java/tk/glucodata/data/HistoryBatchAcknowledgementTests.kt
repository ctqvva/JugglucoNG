package tk.glucodata.data

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.HistoryReplaceOutcome
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.HistoryRepositoryBridge
import tk.glucodata.HistorySyncAccess
import tk.glucodata.HistoryWriteOutcome
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus

/** What a driver is told about a batch, through the same entry points it uses. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34], application = HistoryBatchAcknowledgementTests.TestApplication::class,
    shadows = [HistoryBatchAcknowledgementTests.NoRefresh::class],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HistoryBatchAcknowledgementTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    @Implements(UiRefreshBus::class)
    class NoRefresh {
        companion object {
            @JvmStatic @Implementation fun requestDataRefresh() = Unit
        }
    }

    private lateinit var database: HistoryDatabase
    private var previousApp: Applic? = null
    private var previousBridge: Any? = null
    private val bridgeField =
        HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }
    private val serial = "X-ACKNOWLEDGED"
    private val base = 1_700_000_040_000L

    @Before
    fun setUp() {
        previousApp = Applic.app
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        previousBridge = bridgeField.get(HistoryRepositoryAccess)
        HistoryRepositoryAccess.register(MobileHistoryRepositoryBridge)
        SensorIdentity.invalidateCaches()
        database = HistoryDatabase.getInstance(Applic.app)
        runBlocking {
            database.historyDao().deleteAllReadings()
            database.historyDao().deleteAllDeletedReadings()
        }
    }

    @After
    fun tearDown() {
        bridgeField.set(HistoryRepositoryAccess, previousBridge as HistoryRepositoryBridge?)
        Applic.app = previousApp
        SensorIdentity.invalidateCaches()
    }

    private fun times(vararg minutes: Int) = LongArray(minutes.size) { base + minutes[it] * 60_000L }
    private fun values(size: Int, value: Float) = FloatArray(size) { value }

    private fun stored(): Map<Int, Float> = runBlocking {
        database.historyDao().getSensorReadingsInTimeRange(serial, 0L, Long.MAX_VALUE)
            .associate { ((it.timestamp - base) / 60_000L).toInt() to it.value }
    }

    private fun submitAndWait(timestamps: LongArray, value: Float): HistoryWriteOutcome {
        val settled = CountDownLatch(1)
        val outcome = AtomicReference<HistoryWriteOutcome>()
        val accepted = HistorySyncAccess.submitSensorHistoryBatch(
            serial, timestamps, values(timestamps.size, value), FloatArray(timestamps.size),
        ) {
            outcome.set(it)
            settled.countDown()
        }
        assertEquals(HistoryWriteOutcome.QUEUED, accepted)
        assertTrue("settled", settled.await(20, TimeUnit.SECONDS))
        return outcome.get()
    }

    @Test
    fun theOutcomeIsAnnouncedOnceTheRowsAreInTheTable() {
        assertEquals(HistoryWriteOutcome.PERSISTED, submitAndWait(times(0, 1), 111f))
        assertEquals(mapOf(0 to 111f, 1 to 111f), stored())
    }

    @Test
    fun deletedReadingsAreReportedAsSkipped() {
        runBlocking {
            database.historyDao().insertDeletedReadings(listOf(DeletedHistoryReading(base, serial, base)))
        }
        assertEquals(HistoryWriteOutcome.SKIPPED, submitAndWait(times(0), 111f))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun refusedAndEmptySubmissionsAreFinalAtOnce() {
        val never = tk.glucodata.HistoryWriteListener { error("already final") }
        assertEquals(
            HistoryWriteOutcome.FAILED,
            HistorySyncAccess.submitSensorHistoryBatch(serial, times(0), values(2, 1f), values(1, 0f), never),
        )
        assertEquals(
            HistoryWriteOutcome.FAILED,
            HistorySyncAccess.submitSensorHistoryBatch(" ", times(0), values(1, 1f), values(1, 0f), never),
        )
        assertEquals(
            HistoryWriteOutcome.SKIPPED,
            HistorySyncAccess.submitSensorHistoryBatch(serial, times(0), values(1, Float.NaN), values(1, 0f), never),
        )
    }

    @Test
    fun aDeviceWithoutRoomHistorySkips() {
        bridgeField.set(HistoryRepositoryAccess, null)
        assertEquals(
            HistoryWriteOutcome.SKIPPED,
            HistorySyncAccess.submitSensorHistoryBatch(serial, times(0), values(1, 1f), values(1, 0f), null),
        )
        assertTrue(HistorySyncAccess.storeSensorHistoryBatchAsync(serial, times(0), values(1, 1f), values(1, 0f)))
    }

    @Test
    fun aBlockingRewriteIsNotUndoneByABatchQueuedBeforeIt() {
        // A Sibionics rebuild: the batch of the old algorithm is still in its coalescing window.
        HistorySyncAccess.storeSensorHistoryBatchAsync(serial, times(0, 1), values(2, 111f), FloatArray(2))
        assertTrue(
            HistorySyncAccess.storeSensorHistoryBatchBlocking(serial, times(0), values(1, 222f), FloatArray(1)),
        )
        // The other minute of the queued batch still lands.
        runBlocking { HistoryRepository.batchWriter.flushPending() }
        assertEquals(mapOf(0 to 222f, 1 to 111f), stored())
        assertEquals(0, HistoryRepository.batchWriter.pendingCount(serial))
    }

    @Test
    fun aReplacedIntervalAlsoDropsWhatWasQueuedInsideIt() = runBlocking {
        // 1 and 2 are ghosts inside the interval the sensor re-delivers; 5 is outside it.
        HistorySyncAccess.storeSensorHistoryBatchAsync(serial, times(0, 1, 2, 5), values(4, 111f), FloatArray(4))
        assertEquals(
            HistoryReplaceOutcome.REPLACED,
            HistorySyncAccess.replaceSensorHistoryIntervalsBlocking(
                serial, times(0, 3), values(2, 222f), FloatArray(2), listOf(base..base + 3 * 60_000L),
            ),
        )
        HistoryRepository.batchWriter.flushPending()
        assertEquals(mapOf(0 to 222f, 3 to 222f, 5 to 111f), stored())
    }

    @Test
    fun aReplacementThatStoredNothingLeavesTheQueueAlone() = runBlocking {
        database.historyDao().insertDeletedReadings(listOf(DeletedHistoryReading(base, serial, base)))
        HistorySyncAccess.storeSensorHistoryBatchAsync(serial, times(1), values(1, 111f), FloatArray(1))
        assertEquals(
            HistoryReplaceOutcome.NOTHING_TO_STORE,
            HistorySyncAccess.replaceSensorHistoryIntervalsBlocking(
                serial, times(0), values(1, 222f), FloatArray(1), listOf(base..base + 3 * 60_000L),
            ),
        )
        HistoryRepository.batchWriter.flushPending()
        assertEquals(mapOf(1 to 111f), stored())
    }

    @Test
    fun clearingTheHistoryAlsoClearsWhatIsStillQueued() = runBlocking {
        HistorySyncAccess.storeSensorHistoryBatchAsync(serial, times(0), values(1, 111f), FloatArray(1))
        HistoryRepository.clearingPendingWrites { database.clearHistoryRows(ClearScope.READINGS, it) }
        HistoryRepository.batchWriter.flushPending()
        assertTrue(stored().isEmpty())
        // Tombstoned with the rest, so the sensor sending it again changes nothing.
        assertEquals(HistoryWriteOutcome.SKIPPED, submitAndWait(times(0), 111f))
    }

    @Test
    fun deletingASensorsHistoryDropsItsQueuedBatch() = runBlocking {
        HistorySyncAccess.storeSensorHistoryBatchAsync(serial, times(0), values(1, 111f), FloatArray(1))
        HistoryRepository().deleteForSensor(serial)
        HistoryRepository.batchWriter.flushPending()
        assertTrue(stored().isEmpty())
    }
}
