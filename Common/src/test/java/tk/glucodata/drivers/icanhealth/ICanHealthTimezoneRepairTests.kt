package tk.glucodata.drivers.icanhealth

import android.content.Context
import android.os.Handler
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.HistoryReplaceOutcome
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.HistoryRepositoryBridge
import tk.glucodata.Natives

/** The driver's real flush of a pulled history batch; Room and native storage are substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanRebindApplication::class, shadows = [ICanTimezoneRepairNatives::class])
class ICanHealthTimezoneRepairTests {
    private val sensor = "7BF00099C663AABB"
    private val base = 1_700_000_040_000L
    private val intervalMs = 3 * 60_000L
    private lateinit var manager: ICanHealthBleManager
    private lateinit var context: Applic
    private val room = TreeMap<Long, Float>()
    private var roomReadable = true
    private var replaceFails = false
    private val deletedByUser = mutableSetOf<Long>()
    private var deletionsReadable = true
    private val committed = CountDownLatch(1)
    private val neverResumes = CountDownLatch(1)
    private var diesAfterCommit = false
    private var replacements = 0
    private var plainStores = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        prefs().edit().clear().commit()
        ICanTimezoneRepairNatives.reset()
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(HistoryRepositoryBridge::class.java.classLoader,
            arrayOf(HistoryRepositoryBridge::class.java)) { _, method, args ->
            when (method.name) {
                "replaceHistoryIntervalsBlocking" -> {
                    replacements++
                    if (replaceFails) return@newProxyInstance HistoryReplaceOutcome.FAILED
                    if ((args!![1] as LongArray).all { it in deletedByUser }) {
                        return@newProxyInstance HistoryReplaceOutcome.NOTHING_TO_STORE
                    }
                    @Suppress("UNCHECKED_CAST") val intervals = args[4] as List<LongRange>
                    intervals.forEach { room.subMap(it.first, true, it.last, true).clear() }
                    store(args)
                    if (diesAfterCommit) {
                        committed.countDown()
                        neverResumes.await()
                    }
                    HistoryReplaceOutcome.REPLACED
                }
                "storeHistoryBatchBlocking" -> { plainStores++; store(args!!); true }
                "getHistoryForNotificationForSensor" ->
                    if (roomReadable) room.tailMap(args!![1] as Long).map { (time, value) ->
                        GlucosePoint(time, value, 0f).also { it.sensorSerial = sensor }
                    } else emptyList<GlucosePoint>()
                "getHistoryTimestampsForSensorBlocking" -> if (roomReadable) room.keys.toLongArray() else null
                "isMinuteDeletedByUserBlocking" ->
                    if (deletionsReadable) deletedByUser.any { it / 60_000L == (args!![1] as Long) / 60_000L } else null
                "getLatestTimestampForSensorBlocking" -> room.lastEntry()?.key ?: 0L
                else -> error("Unexpected history operation: ${method.name}")
            }
        } as HistoryRepositoryBridge)
        prefsOf("icanhealth_native_rebuild").edit().clear().commit()
        manager = ICanHealthBleManager(sensor, 0L)
        shadowOf(handler().looper).pause()
    }

    @After fun tearDown() {
        manager.destroy()
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(HistoryRepositoryBridge::class.java.classLoader,
            arrayOf(HistoryRepositoryBridge::class.java)) { _, _, _ -> error("No history in this test") }
            as HistoryRepositoryBridge)
    }

    @Test fun aPartialSessionReplacesOnlyItselfInRoomAndInTheNativeMirror() {
        seedLive(0..200)
        room[at(110) + 60_000L] = 300f // a ghost between two re-pulled readings
        assertTrue(flush(100..140))

        assertEquals((0..200).map(::at), room.keys.toList())
        assertEquals(100f, room[at(99)]!!, 0f)
        assertEquals(200f, room[at(120)]!!, 0f)
        assertEquals(100f, room[at(141)]!!, 0f)
        // The rebase zeroes the whole native stream, so all of it has to come back.
        assertEquals(listOf(at(0) / 1000L), ICanTimezoneRepairNatives.rebases)
        assertEquals(room.keys.map { it / 1000L }, ICanTimezoneRepairNatives.stream.keys.toList())
        assertEquals(10f, ICanTimezoneRepairNatives.stream[at(200) / 1000L]!!, 0.001f)
        assertEquals(20f, ICanTimezoneRepairNatives.stream[at(120) / 1000L]!!, 0.001f)
        assertTrue(repaired())
    }

    @Test fun aReadingTheTransferSkippedKeepsItsLiveCopy() {
        seedLive(0..60)
        assertTrue(flush((0..60).filter { it != 30 }))
        assertEquals((0..60).map(::at), room.keys.toList())
        assertEquals(100f, room[at(30)]!!, 0f)
        assertEquals(200f, room[at(31)]!!, 0f)
        assertTrue(repaired())
    }

    @Test fun aFailedReplacementLeavesTheRepairPendingAndNativeUntouched() {
        seedLive(0..60)
        val before = TreeMap(room)
        replaceFails = true
        assertFalse(flush(0..40))
        assertEquals(before, room)
        assertFalse(repaired())
        assertTrue(ICanTimezoneRepairNatives.rebases.isEmpty())
        assertEquals(0, plainStores)

        replaceFails = false
        assertTrue(flush(0..40))
        assertEquals((0..60).map(::at), room.keys.toList())
        assertTrue(repaired())
    }

    @Test fun anInterruptedNativeRebuildFinishesAfterARestartWithoutAnotherBatch() {
        seedLive(0..60)
        ICanTimezoneRepairNatives.diesAfterRebase = true
        assertTrue(flush(0..40))
        assertTrue(repaired())
        assertTrue(rebuildOwed())
        assertTrue(ICanTimezoneRepairNatives.stream.isEmpty())

        ICanTimezoneRepairNatives.diesAfterRebase = false
        restart()
        // The sensor can send the session again: all of it is at or below the covered edge.
        assertTrue((0..40).all { overlaps(it) })
        manager.connectDevice(0L)
        shadowOf(handler().looper).idle()
        assertEquals(1, replacements)
        assertStreamMatchesRoom()
        assertFalse(rebuildOwed())
    }

    @Test fun aProcessThatDiesRightAfterTheRoomCommitStillRebuildsTheNativeStream() {
        seedLive(0..60)
        diesAfterCommit = true
        // A killed process runs nothing after the commit: no failure handling, no cleanup.
        thread(isDaemon = true) { flush(0..40) }
        assertTrue(committed.await(30, TimeUnit.SECONDS))
        assertTrue(ICanTimezoneRepairNatives.rebases.isEmpty())
        assertFalse(repaired())
        assertTrue(rebuildOwed())

        diesAfterCommit = false
        restart()
        manager.connectDevice(0L)
        shadowOf(handler().looper).idle()
        assertStreamMatchesRoom()
        assertFalse(rebuildOwed())
    }

    @Test fun unreadableHistoryIsNotTakenForAnEmptyOne() {
        seedLive(0..60)
        roomReadable = false
        ICanTimezoneRepairNatives.startMs = base - 30L * 24 * 60 * 60_000L
        ICanTimezoneRepairNatives.stream[base / 1000L - 60L] = 9f
        assertTrue(flush(0..40))
        assertTrue(ICanTimezoneRepairNatives.rebases.isEmpty())
        assertEquals(setOf(base / 1000L - 60L), ICanTimezoneRepairNatives.stream.keys.toSet())
        assertTrue(rebuildOwed())

        roomReadable = true
        retryOnTimer()
        assertStreamMatchesRoom()
        assertFalse(rebuildOwed())
    }

    @Test fun nativeStorageThatRefusesWritesIsRetriedUntilItTakesThem() {
        seedLive(0..60)
        ICanTimezoneRepairNatives.writesRefused = true
        assertTrue(flush(0..40))
        assertTrue(rebuildOwed())
        retryOnTimer()
        assertTrue(rebuildOwed())

        ICanTimezoneRepairNatives.writesRefused = false
        retryOnTimer()
        assertStreamMatchesRoom()
        assertFalse(rebuildOwed())
        assertEquals(1, replacements)
    }

    @Test fun oneRefusedNativeWriteIsRetried() {
        seedLive(0..60)
        ICanTimezoneRepairNatives.refusedAt = at(60) / 1000L
        assertTrue(flush(0..40))
        assertTrue(rebuildOwed())

        ICanTimezoneRepairNatives.refusedAt = 0L
        retryOnTimer()
        assertStreamMatchesRoom()
        assertFalse(rebuildOwed())
    }

    @Test fun readingsTheUserDeletedStayOutOfBothStores() {
        seedLive(0..60)
        deletedByUser += listOf(at(10), at(50))
        room.keys.removeAll(deletedByUser)
        assertTrue(flush(0..40))

        assertEquals((0..60).filter { it != 10 && it != 50 }.map(::at), room.keys.toList())
        assertStreamMatchesRoom()
        assertTrue(repaired())
    }

    @Test fun aBatchOfOnlyDeletedReadingsTouchesNeitherStore() {
        seedLive(41..60)
        ICanTimezoneRepairNatives.stream[at(50) / 1000L] = 10f
        deletedByUser += (0..40).map(::at)
        val before = TreeMap(room)
        assertTrue(flush(0..40))

        assertEquals(before, room)
        assertTrue(ICanTimezoneRepairNatives.rebases.isEmpty())
        assertEquals(setOf(at(50) / 1000L), ICanTimezoneRepairNatives.stream.keys.toSet())
        assertFalse(rebuildOwed())
        assertFalse(repaired())
    }

    @Test fun readingsOutsideTheReplacementKeepTheirTemperatures() {
        seedLive(0..60)
        seedNativeTemperatures(0..60, 31.5f)
        assertTrue(flush(20..40, temperatureC = 33.2f))

        for (slot in 0..60) {
            assertEquals("slot $slot", if (slot in 20..40) 33.2f else 31.5f, nativeTemperature(slot), 0.01f)
        }
    }

    @Test fun temperaturesOutliveARebuildThatDiedAfterClearingThem() {
        seedLive(0..60)
        seedNativeTemperatures(0..60, 31.5f)
        ICanTimezoneRepairNatives.diesAfterRebase = true
        assertTrue(flush(20..40, temperatureC = 33.2f))
        assertTrue(ICanTimezoneRepairNatives.temperatures.isEmpty())

        ICanTimezoneRepairNatives.diesAfterRebase = false
        restart()
        // A live reading reached the cleared stream before the rebuild ran again.
        room[at(61)] = 110f
        ICanTimezoneRepairNatives.write(at(61) / 1000L, 11f, 34f)
        manager.connectDevice(0L)
        shadowOf(handler().looper).idle()

        assertStreamMatchesRoom()
        assertEquals(31.5f, nativeTemperature(0), 0.01f)
        assertEquals(33.2f, nativeTemperature(30), 0.01f)
        assertEquals(34f, nativeTemperature(61), 0.01f)
    }

    @Test fun aLiveReadingTheUserDeletedStaysOutOfADelayedRebuild() {
        seedLive(0..60)
        ICanTimezoneRepairNatives.writesRefused = true
        assertTrue(flush(0..40))
        assertTrue(rebuildOwed())

        // Room keeps a live reading at its whole second.
        storeLive(120f, at(61) + 417L)
        room[at(61)] = 120f
        room.remove(at(61))
        deletedByUser += at(61)
        ICanTimezoneRepairNatives.writesRefused = false
        // Not knowing whether it was deleted must not bring it back either.
        deletionsReadable = false
        retryOnTimer()
        assertTrue(rebuildOwed())
        assertTrue(ICanTimezoneRepairNatives.stream.isEmpty())

        deletionsReadable = true
        retryOnTimer()
        assertStreamMatchesRoom()
        assertFalse(rebuildOwed())
    }

    @Test fun aLiveReadingRoomHoldsAtItsWholeSecondIsNotReplayedTwice() {
        seedLive(0..61)
        storeLive(120f, at(61) + 417L)
        deletionsReadable = false // not asked: Room has it
        assertTrue(flush(0..40))
        assertStreamMatchesRoom()
        assertFalse(rebuildOwed())
    }

    @Test fun aLiveReadingRoomDoesNotHoldYetSurvivesTheRebuild() {
        seedLive(0..60)
        storeLive(120f, at(61))
        assertTrue(flush(0..40))
        assertEquals((0..61).map { at(it) / 1000L }, ICanTimezoneRepairNatives.stream.keys.toList())
        assertEquals(12f, ICanTimezoneRepairNatives.stream[at(61) / 1000L]!!, 0.001f)
    }

    @Test fun aSmallBatchIsAnOrdinaryImport() {
        seedLive(0..60)
        assertTrue(flush(61..70))
        assertEquals(0, replacements)
        assertEquals(1, plainStores)
        assertFalse(repaired())
    }

    @Test fun onceRepairedABatchIsAnOrdinaryImport() {
        assertTrue(flush(0..40))
        assertTrue(repaired())
        assertTrue(flush(41..90))
        assertEquals(1, replacements)
        assertEquals(1, plainStores)
    }

    private fun at(slot: Int) = base + slot * intervalMs

    private fun seedLive(slots: IntRange) = slots.forEach { room[at(it)] = 100f }

    private fun store(args: Array<Any?>) {
        val times = args[1] as LongArray
        val values = args[2] as FloatArray
        times.indices.filter { times[it] !in deletedByUser }.forEach { room[times[it]] = values[it] }
    }

    /** Feeds re-pulled records through the driver's own batch and flushes it. */
    private fun flush(slots: Iterable<Int>, temperatureC: Float = Float.NaN): Boolean {
        val record = ICanHealthBleManager::class.java.getDeclaredMethod("recordHistoryBackfillSample",
            Int::class.java, Long::class.java, Float::class.java, Float::class.java, Float::class.java)
            .apply { isAccessible = true }
        slots.forEach { record.invoke(manager, (it + 1) * 3, at(it), 200f, Float.NaN, temperatureC) }
        return ICanHealthBleManager::class.java.getDeclaredMethod("flushHistoryBackfillBatch")
            .apply { isAccessible = true }.invoke(manager) as Boolean
    }

    private fun storeLive(glucoseMgdl: Float, timestampMs: Long) {
        ICanHealthBleManager::class.java.getDeclaredMethod("storeMeasurement", Float::class.java, Long::class.java)
            .apply { isAccessible = true }.invoke(manager, glucoseMgdl, timestampMs)
    }

    private fun restart() {
        manager.destroy()
        manager = ICanHealthBleManager(sensor, 0L)
        shadowOf(handler().looper).pause()
    }

    private fun handler() = ICanHealthBleManager::class.java.getDeclaredField("handler")
        .apply { isAccessible = true }.get(manager) as Handler

    private fun retryOnTimer() = shadowOf(handler().looper).idleFor(Duration.ofMinutes(2))

    private fun overlaps(slot: Int) = ICanHealthBleManager::class.java
        .getDeclaredMethod("shouldSkipHistoryOverlap", Int::class.java, Long::class.java)
        .apply { isAccessible = true }.invoke(manager, (slot + 1) * 3, at(slot)) as Boolean

    private fun assertStreamMatchesRoom() = assertEquals(
        room.map { (time, value) -> time / 1000L to value / 10f },
        ICanTimezoneRepairNatives.stream.toList(),
    )

    private fun seedNativeTemperatures(slots: IntRange, temperatureC: Float) =
        slots.forEach { ICanTimezoneRepairNatives.write(at(it) / 1000L, 10f, temperatureC) }

    private fun nativeTemperature(slot: Int) = ICanTimezoneRepairNatives.temperatures.getValue(at(slot) / 1000L)

    private fun rebuildOwed() = ICanHealthNativeRebuild.isOwed(context, sensor)

    private fun prefs() = prefsOf("tk.glucodata_preferences")

    private fun prefsOf(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun repaired() = prefs().getInt(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + sensor, 0) ==
        ICanHealthConstants.HISTORY_TIMEZONE_REPAIR_GENERATION
}

@Implements(value = Natives::class, callThroughByDefault = false)
class ICanTimezoneRepairNatives {
    companion object {
        val rebases = mutableListOf<Long>()
        val stream = TreeMap<Long, Float>()
        val temperatures = TreeMap<Long, Float>()
        var diesAfterRebase = false
        var writesRefused = false
        var refusedAt = 0L
        var startMs = 0L

        fun reset() {
            rebases.clear()
            stream.clear()
            temperatures.clear()
            diesAfterRebase = false
            writesRefused = false
            refusedAt = 0L
            startMs = 0L
        }

        fun write(time: Long, glucose: Float, temperatureC: Float): Boolean {
            if (writesRefused || time == refusedAt) return false
            stream[time] = glucose
            if (temperatureC > 0f) temperatures[time] = temperatureC
            return true
        }

        @JvmStatic @Implementation fun str2sensorptr(name: String): Long = if (startMs > 0L) 1L else 0L
        @JvmStatic @Implementation fun getSensorStartmsecFromSensorptr(sensorPtr: Long): Long = startMs
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun rebaseDirectStreamWindow(sensorId: String, startTimeSec: Long) {
            rebases += startTimeSec
            stream.clear()
            temperatures.clear()
            check(!diesAfterRebase)
        }
        @JvmStatic @Implementation fun getStreamTemperatures(sensorName: String): LongArray =
            temperatures.map { (time, temperatureC) -> (time shl 16) or Math.round(temperatureC * 10f).toLong() }
                .toLongArray()
        @JvmStatic @Implementation fun addGlucoseStreamWithTemp(
            time: Long, glucose: Float, temperatureC: Float, sensorId: String,
        ): Boolean = write(time, glucose, temperatureC)
        @JvmStatic @Implementation fun addGlucoseStreamBatchWithTemp(
            times: LongArray, glucoses: FloatArray, temperaturesC: FloatArray, sensorId: String,
        ): Int = times.indices.count { write(times[it], glucoses[it], temperaturesC[it]) }
    }
}
