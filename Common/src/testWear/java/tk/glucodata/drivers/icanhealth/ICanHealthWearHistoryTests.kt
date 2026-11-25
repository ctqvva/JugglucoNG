package tk.glucodata.drivers.icanhealth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import java.time.Duration
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
import org.robolectric.shadow.api.Shadow
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback

/** The watch has no Room: whether history is stored is the native store's answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanRebindApplication::class,
    shadows = [ICanWearHistoryNatives::class, ICanRebindGatt::class])
class ICanHealthWearHistoryTests {
    private val sensor = "7BF00099C663AABB"
    private val startMs = (System.currentTimeMillis() / 1000L - 3600L) * 1000L
    private lateinit var context: Applic
    private lateinit var manager: ICanHealthBleManager
    private lateinit var gatt: BluetoothGatt
    private lateinit var handler: Handler

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        prefs().edit().clear()
            .putInt(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + sensor,
                ICanHealthConstants.HISTORY_TIMEZONE_REPAIR_GENERATION)
            .commit()
        ICanWearHistoryNatives.reset()
        manager = ICanHealthBleManager(sensor, 0L)
        handler = get("handler") as Handler
        shadowOf(handler.looper).pause()
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, gatt)
    }

    @After fun tearDown() {
        manager.destroy()
        ICanWearHistoryNatives.reset()
    }

    @Test fun aBatchTheNativeStoreRefusesIsKeptAndRetried() {
        ICanWearHistoryNatives.refuses = { true }
        transfer(3, 6, 9)
        assertTrue(unsaved())
        assertEquals(0, get("glucoseHistoryImportedRecordCount"))
        assertEquals(-1, get("persistedCoveredSequence"))
        assertEquals(-1, prefs().getInt(ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX + sensor, -1))

        ICanWearHistoryNatives.refuses = { false }
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(31))
        assertFalse(unsaved())
        assertEquals(listOf(3, 6, 9).map(::secondsOf), ICanWearHistoryNatives.rows.toList())
        assertEquals(9, get("persistedCoveredSequence"))
    }

    @Test fun anAcceptedBatchIsWrittenOnceAndCounted() {
        transfer(3, 6, 9)
        assertFalse(unsaved())
        assertEquals(3, get("glucoseHistoryImportedRecordCount"))
        assertEquals(3, ICanWearHistoryNatives.calls)
    }

    @Test fun recordsRefusedBeforeTheStoreRecoversAreNotLost() {
        ICanWearHistoryNatives.refuses = { ICanWearHistoryNatives.calls <= 2 }
        transfer(3, 6, 9)
        assertFalse(unsaved())
        assertEquals(listOf(3, 6, 9).map(::secondsOf), ICanWearHistoryNatives.rows.sorted())
    }

    @Test fun oneRecordOutsideTheStoresCapacityDoesNotHoldBackTheRest() {
        ICanWearHistoryNatives.refuses = { it == secondsOf(6) }
        transfer(3, 6, 9)
        assertFalse(unsaved())
        assertEquals(listOf(3, 9).map(::secondsOf), ICanWearHistoryNatives.rows.toList())
        assertEquals(9, get("persistedCoveredSequence"))
    }

    @Test fun aPendingTimezoneRepairLeavesTheReadingsAroundTheBatchAlone() {
        prefs().edit().remove(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + sensor).commit()
        set("historyTzRepairLoadedSensorId", null)
        val older = secondsOf(-60)
        val newer = secondsOf(100)
        ICanWearHistoryNatives.rows += listOf(older, newer)
        val sequences = (1..30).map { it * 3 }.toIntArray()
        transfer(*sequences)
        assertEquals(0, ICanWearHistoryNatives.rebases)
        assertEquals(listOf(older) + sequences.map(::secondsOf) + newer, ICanWearHistoryNatives.rows.sorted())
        assertEquals(sequences.size, ICanWearHistoryNatives.calls)
        assertEquals(30, get("glucoseHistoryImportedRecordCount"))
        assertEquals("not repaired, so not marked so", 0,
            prefs().getInt(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + sensor, 0))
    }

    @Test fun aSessionOlderThanTheMirrorWindowIsNotClearedByABackfill() {
        ICanWearHistoryNatives.startMs = startMs - Duration.ofDays(20).toMillis()
        val older = secondsOf(-600)
        ICanWearHistoryNatives.rows += older
        transfer(3, 6, 9)
        assertEquals(0, ICanWearHistoryNatives.rebases)
        assertEquals(listOf(older) + listOf(3, 6, 9).map(::secondsOf), ICanWearHistoryNatives.rows.sorted())
    }

    @Suppress("DEPRECATION")
    private fun transfer(vararg sequences: Int) {
        set("historyBackfillRequested", true)
        set("historyBackfillPhase", Class.forName(manager.javaClass.name + "\$HistoryBackfillPhase").enumConstants!!
            .first { (it as Enum<*>).name == "GLUCOSE" })
        val record = manager.javaClass.getDeclaredMethod("recordHistoryBackfillSample", Int::class.java,
            Long::class.java, Float::class.java, Float::class.java, Float::class.java).apply { isAccessible = true }
        sequences.forEach { record.invoke(manager, it, secondsOf(it) * 1000L, 100f, Float.NaN, Float.NaN) }
        val racp = BluetoothGattCharacteristic(ICanHealthConstants.RACP, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        racp.value = byteArrayOf(0x06, 0x00, 0xF1.toByte(), ICanHealthConstants.RACP_RESULT_SUCCESS.toByte())
        manager.onCharacteristicChanged(gatt, racp)
    }

    private fun secondsOf(sequence: Int) = startMs / 1000L + sequence * 60L

    private fun unsaved() = (get("historyImport") as ICanHealthHistoryImport).hasUnsaved()

    private fun prefs() = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)

    private fun get(name: String): Any? = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(manager)

    private fun set(name: String, value: Any?) = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(manager, value)
}

@Implements(value = Natives::class, callThroughByDefault = false)
class ICanWearHistoryNatives {
    companion object {
        var refuses: (Long) -> Boolean = { false }
        var calls = 0
        var rebases = 0
        var startMs = 0L
        val rows = LinkedHashSet<Long>()

        fun reset() {
            refuses = { false }
            calls = 0
            rebases = 0
            startMs = 0L
            rows.clear()
        }

        @JvmStatic @Implementation fun __staticInitializer__() {}

        @JvmStatic @Implementation fun activeSensors(): Array<String> = arrayOf("7BF00099C663AABB")

        @JvmStatic @Implementation fun str2sensorptr(name: String?): Long = 1L

        @JvmStatic @Implementation fun getSensorStartmsecFromSensorptr(sensorptr: Long): Long = startMs

        @JvmStatic @Implementation fun rebaseDirectStreamWindow(sensorId: String?, startTimeSec: Long) {
            rebases++
            rows.clear()
        }

        @JvmStatic @Implementation fun addGlucoseStreamWithTemp(
            time: Long, glucose: Float, temperatureC: Float, sensorId: String?,
        ): Boolean {
            calls++
            return !refuses(time) && run { rows.add(time); true }
        }
    }
}
