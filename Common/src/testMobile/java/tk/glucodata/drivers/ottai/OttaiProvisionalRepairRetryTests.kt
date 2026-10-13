package tk.glucodata.drivers.ottai

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import sun.misc.Unsafe
import tk.glucodata.GlucosePoint
import tk.glucodata.HistoryOperationCompletion
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.HistoryRepositoryBridge
import tk.glucodata.SuperGattCallback

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class OttaiProvisionalRepairRetryTests {
    @Test
    fun repairRecoversAfterThreeFailuresWithoutAnotherLiveReading() {
        // Skip the constructor's native BLE setup, but run the manager's real repair,
        // completion, delayed retry, reconciliation and native-mirror methods.
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val manager = unsafe.allocateInstance(OttaiBleManager::class.java) as OttaiBleManager
        val handler = Handler(Looper.getMainLooper())
        val looper = shadowOf(handler.looper)
        val serial = "AABBCCDDEEFF"
        SuperGattCallback::class.java.getDeclaredField("SerialNumber").apply { isAccessible = true }.set(manager, serial)
        set(manager, "handler", handler)
        set(manager, "provisionalHistoryLock", Any())
        set(manager, "provisionalHistoryByDataNo", linkedMapOf(
            40 to OttaiProvisionalHistoryPoint(40, 180_000L, 100f),
        ))
        set(manager, "provisionalCorrectionInFlight", true)
        set(manager, "materials", OttaiRegistry.DeviceMaterials("", "", "", 60_000L, "", 0))
        val nativeTimes = mutableListOf<Long>()
        val wakes = mutableListOf<Long>()
        set(manager, "nativeGlucoseMirror", OttaiNativeGlucoseMirror(
            writeNative = { time, _, _, _ -> nativeTimes.add(time); true },
            wakeNightscout = { _, time -> wakes.add(time) },
        ))
        val readingType = OttaiBleManager::class.java.declaredClasses.single { it.simpleName == "EmittedReading" }
        val corrected = readingType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(180_000L, 110f, 110f, true, true, true, true, 240_000L, 41, 30f)
        var attempts = 0
        var roomReads = 0
        var correctedRoom = emptyList<GlucosePoint>()
        val bridgeField = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }
        val previousBridge = bridgeField.get(null)
        val bridge = Proxy.newProxyInstance(HistoryRepositoryBridge::class.java.classLoader,
            arrayOf(HistoryRepositoryBridge::class.java)) { _, method, args ->
            when (method.name) {
                "replaceProvisionalHistoryAsync" -> {
                    attempts++
                    val stored = attempts >= 4
                    if (stored) {
                        val timestamps = args!![2] as LongArray
                        val values = args[3] as FloatArray
                        correctedRoom = timestamps.indices.map { GlucosePoint(timestamps[it], values[it], 0f) }
                    }
                    (args!!.last() as HistoryOperationCompletion).complete(stored)
                    null
                }
                "getHistoryForNotificationForSensor" -> { roomReads++; correctedRoom }
                else -> error("Unexpected repository operation: ${method.name}")
            }
        } as HistoryRepositoryBridge
        HistoryRepositoryAccess.register(bridge)
        try {
            OttaiBleManager::class.java.getDeclaredMethod("queueProvisionalHistoryCorrection", String::class.java, readingType)
                .apply { isAccessible = true }.invoke(manager, serial, corrected)
            looper.idle()
            repeat(2) { looper.idleFor(Duration.ofSeconds(1)) }
            assertEquals(3, attempts)
            assertTrue(get(manager, "provisionalCorrectionInFlight") as Boolean)
            assertEquals(0, roomReads)
            assertTrue(nativeTimes.isEmpty())
            // No live callback or explicit retry: only the manager's delayed work runs.
            looper.idleFor(Duration.ofSeconds(29))
            assertEquals(3, attempts)
            looper.idleFor(Duration.ofSeconds(1))
            assertEquals(4, attempts)
            assertFalse(get(manager, "provisionalCorrectionInFlight") as Boolean)
            assertEquals(1, roomReads)
            assertEquals(listOf(120L, 180L), nativeTimes)
            assertEquals(listOf(180_000L), wakes)
            looper.idleFor(Duration.ofMinutes(2))
            assertEquals(4, attempts)
        } finally {
            handler.removeCallbacksAndMessages(null)
            bridgeField.set(null, previousBridge)
        }
    }

    private fun set(manager: OttaiBleManager, name: String, value: Any) {
        OttaiBleManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    private fun get(manager: OttaiBleManager, name: String): Any? =
        OttaiBleManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)
}
