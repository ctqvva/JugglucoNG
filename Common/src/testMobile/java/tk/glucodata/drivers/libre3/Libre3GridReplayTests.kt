package tk.glucodata.drivers.libre3

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.drivers.VirtualSensorNativeMirror

/**
 * A sensor's minutes keep the times they were stored under, in Room and in what the driver hands
 * native storage, however often the sensor replays them: after a restart, NFC rescans (one with a
 * corrected start), a removal and re-add, and on the watch it is handed to. Adjacent minutes carry
 * different values, so a replay dated on the next minute's time would show up as that minute's
 * value changing (natively) or as a minute missing or doubled (Room).
 *
 * Real Room and the real driver; native storage is the JNI boundary, whose slots
 * tools/test-resolved-glucose.py runs for the same times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Libre3ReviewApplication::class, shadows = [Libre3ReviewNatives::class, Libre3PublicationShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class Libre3GridReplayTests {
    private lateinit var context: Context
    private var manager: Libre3BleManager? = null
    private var oldBridge: Any? = null
    private val now = System.currentTimeMillis()
    // Two days in, on a whole second as the NFC activation reports it.
    private val start = (now / 1000L - 2 * 24 * 3600L) * 1000L
    private val lives = 2_860 until 2_866 // about 20 minutes ago
    private fun mgdl(life: Int) = 100 + 7 * (life - lives.first)

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        prefs().edit().clear().commit()
        Libre3ReviewNatives.reset()
        oldBridge = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(HistoryRepositoryAccess)
        HistoryRepositoryAccess.register(tk.glucodata.data.MobileHistoryRepositoryBridge)
    }

    @After fun cleanup() {
        stopDriver()
        HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HistoryRepositoryAccess, oldBridge)
        Applic.app = null
    }

    private fun prefs() = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
    private fun record(serial: String, startMs: Long = start) =
        Libre3SensorRecord(serial, "AA:BB:CC:DD:EE:01", byteArrayOf(1, 2, 3, 4), startMs, 60, 21_600, 123, "test", 1)

    private fun invoke(name: String, vararg args: Any?): Any? = Libre3BleManager::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(manager, *args)

    private fun stopDriver() {
        manager?.setPause(true)
        manager?.close()
        manager = null
    }

    /** A fresh driver, as after a restart, that receives these minutes again from the sensor. */
    private fun replay(serial: String, times: Int = 1) {
        stopDriver()
        manager = Libre3BleManager(serial)
        repeat(times) {
            lives.forEach { invoke("onClinical", Libre3ClinicalRecord(it, mgdl(it), 0)) }
            invoke("flushBackfill")
        }
    }

    private fun room(serial: String): Map<Long, Float> =
        HistoryRepositoryAccess.getHistoryForSensor(serial, 0L, false)!!.associate { it.timestamp to it.value }

    private fun assertStored(serial: String, timeOf: (Int) -> Long, step: String) {
        val expected = lives.associate { timeOf(it) to mgdl(it).toFloat() }
        assertEquals("Room after $step", expected, room(serial))
        // Native takes seconds, and mg/dL / 10 (VirtualSensorNativeMirror.nativeGlucose).
        val native = expected.entries.associate { (ms, value) -> ms / 1000L to VirtualSensorNativeMirror.nativeGlucose(value) }
        val wrong = Libre3ReviewNatives.nativeWrites.filter { (second, value) -> native[second] != value }
        assertEquals("native writes off their minute after $step", emptyList<Pair<Long, Float>>(), wrong)
        assertEquals("LibreView numbers from life count 0 after $step", timeOf(0) / 1000L, Libre3ReviewNatives.activationSec)
    }

    private fun exerciseEveryPath(serial: String, timeOf: (Int) -> Long) {
        replay(serial, times = 2)
        assertStored(serial, timeOf, "two replays")
        assertTrue(Libre3ReviewNatives.nativeWrites.isNotEmpty())

        replay(serial)
        assertStored(serial, timeOf, "a restart")

        Libre3Persistence.saveSensor(context, record(serial))
        manager!!.onNfcRecordUpdated()
        replay(serial)
        assertStored(serial, timeOf, "an NFC rescan")

        Libre3Persistence.saveSensor(context, record(serial, start + 2_000L))
        manager!!.onNfcRecordUpdated()
        replay(serial)
        assertStored(serial, timeOf, "a rescan reporting a corrected start")

        stopDriver()
        Libre3Persistence.removeSensor(context, serial)
        Libre3Persistence.saveSensor(context, record(serial))
        replay(serial)
        assertStored(serial, timeOf, "a removal and re-add")

        val handoff = Libre3Persistence.exportForWatch(context, serial)!!
        stopDriver()
        prefs().edit().clear().commit() // the watch has none of the phone's preferences
        assertEquals(serial, Libre3Persistence.importFromPhone(context, handoff))
        replay(serial)
        assertStored(serial, timeOf, "the watch handoff")
    }

    /**
     * Greptile's case: a preview sensor with an NFC start and no saved anchor, whose minutes an
     * earlier build stored at start + L min. Dated by the newer rule, a replay of L would take
     * L + 1's time: native would overwrite L + 1 with L's value and Room would drop it.
     */
    @Test fun aPreviewSensorsReplayKeepsEachMinuteOnItsOwnTime() {
        val serial = "0D1234PRE"
        val timeOf = { life: Int -> start + life * 60_000L }
        Libre3Persistence.saveSensor(context, record(serial))
        prefs().edit().remove("lifeZero.$serial").commit() // as an earlier build left it
        assertTrue(HistoryRepositoryAccess.storeHistoryBatchWithSourceBlocking(
            serial, lives.map(timeOf).toLongArray(), lives.map { mgdl(it).toFloat() }.toFloatArray(),
            FloatArray(lives.count()), GlucoseReadingSource.SENSOR,
        ))
        exerciseEveryPath(serial, timeOf)
    }

    /** A sensor first seen by this build: minute L is dated when it ends, a minute after start + L min. */
    @Test fun aNewSensorsLiveMinuteAndItsReplaysShareTheMinuteEnd() {
        val serial = "0D1234NEW"
        val timeOf = { life: Int -> start + (life + 1) * 60_000L }
        Libre3Persistence.saveSensor(context, record(serial))
        manager = Libre3BleManager(serial)
        lives.forEach { invoke("onRealtime", Libre3RealtimeReading(it, mgdl(it), 0, 0, 0, 0, 0, 0, 0, 0, 0)) }
        invoke("flushBackfill")
        assertStored(serial, timeOf, "the live frames")
        exerciseEveryPath(serial, timeOf)
    }
}
