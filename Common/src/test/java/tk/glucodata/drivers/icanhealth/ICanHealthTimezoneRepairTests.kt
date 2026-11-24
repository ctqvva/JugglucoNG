package tk.glucodata.drivers.icanhealth

import android.content.Context
import java.lang.reflect.Proxy
import java.util.TreeMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
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
                    if (replaceFails) return@newProxyInstance false
                    @Suppress("UNCHECKED_CAST") val intervals = args!![4] as List<LongRange>
                    intervals.forEach { room.subMap(it.first, true, it.last, true).clear() }
                    store(args)
                    true
                }
                "storeHistoryBatchBlocking" -> { plainStores++; store(args!!); true }
                "getHistoryForNotificationForSensor" ->
                    if (roomReadable) room.tailMap(args!![1] as Long).map { (time, value) ->
                        GlucosePoint(time, value, 0f).also { it.sensorSerial = sensor }
                    } else emptyList<GlucosePoint>()
                "getHistoryTimestampsForSensorBlocking" -> if (roomReadable) room.keys.toLongArray() else null
                "getLatestTimestampForSensorBlocking" -> room.lastEntry()?.key ?: 0L
                else -> error("Unexpected history operation: ${method.name}")
            }
        } as HistoryRepositoryBridge)
        manager = ICanHealthBleManager(sensor, 0L)
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

    @Test fun aRepairInterruptedBeforeTheNativeMirrorRunsAgainAfterARestart() {
        seedLive(0..60)
        ICanTimezoneRepairNatives.rebaseFails = true
        assertTrue(flush(0..40))
        assertFalse(repaired())

        ICanTimezoneRepairNatives.rebaseFails = false
        manager.destroy()
        manager = ICanHealthBleManager(sensor, 0L)
        assertTrue(flush(0..40))
        assertEquals(2, replacements)
        assertEquals(room.keys.map { it / 1000L }, ICanTimezoneRepairNatives.stream.keys.toList())
        assertTrue(repaired())
    }

    @Test fun unreadableHistoryIsNotTakenForAnEmptyOne() {
        seedLive(0..60)
        roomReadable = false
        assertTrue(flush(0..40))
        assertTrue(ICanTimezoneRepairNatives.rebases.isEmpty())
        assertFalse(repaired())
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
        times.indices.forEach { room[times[it]] = values[it] }
    }

    /** Feeds re-pulled records through the driver's own batch and flushes it. */
    private fun flush(slots: Iterable<Int>): Boolean {
        val record = ICanHealthBleManager::class.java.getDeclaredMethod("recordHistoryBackfillSample",
            Int::class.java, Long::class.java, Float::class.java, Float::class.java, Float::class.java)
            .apply { isAccessible = true }
        slots.forEach { record.invoke(manager, (it + 1) * 3, at(it), 200f, Float.NaN, Float.NaN) }
        return ICanHealthBleManager::class.java.getDeclaredMethod("flushHistoryBackfillBatch")
            .apply { isAccessible = true }.invoke(manager) as Boolean
    }

    private fun prefs() = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)

    private fun repaired() = prefs().getInt(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + sensor, 0) ==
        ICanHealthConstants.HISTORY_TIMEZONE_REPAIR_GENERATION
}

@Implements(value = Natives::class, callThroughByDefault = false)
class ICanTimezoneRepairNatives {
    companion object {
        val rebases = mutableListOf<Long>()
        val stream = TreeMap<Long, Float>()
        var rebaseFails = false

        fun reset() {
            rebases.clear()
            stream.clear()
            rebaseFails = false
        }

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun rebaseDirectStreamWindow(sensorId: String, startTimeSec: Long) {
            check(!rebaseFails)
            rebases += startTimeSec
            stream.clear()
        }
        @JvmStatic @Implementation fun addGlucoseStreamWithTemp(
            time: Long, glucose: Float, temperatureC: Float, sensorId: String,
        ): Boolean {
            stream[time] = glucose
            return true
        }
    }
}
