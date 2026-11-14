package tk.glucodata.drivers.libre3

import android.content.Context
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.*
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

/** Exercises the actual persistence, manager and bridge with software stand-ins for JNI and Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Libre3ReviewApplication::class, shadows = [Libre3ReviewNatives::class])
class Libre3ReviewRegressionTest {
    private lateinit var context: Context
    private lateinit var manager: Libre3BleManager
    private var oldBridge: Any? = null
    private var timestamps: LongArray? = longArrayOf()
    private var storeSucceeds = true
    private val sensor = "0D1234ABC"
    private val now = 1_793_000_000_000L
    private fun field(name: String) = Libre3BleManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(manager)
    private fun set(name: String, value: Any?) = field(name).set(manager, value)
    private fun invoke(name: String, vararg args: Any?): Any? = Libre3BleManager::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(manager, *args)
    private fun kind(name: String): Any = Class.forName("tk.glucodata.drivers.libre3.Libre3BleManager\$ControlKind")
        .enumConstants.single { it.toString() == name }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        Libre3ReviewNatives.reset()
        manager = Libre3BleManager(sensor)
        // Existing native shell in this fixture. Window geometry is tested separately.
        set("nativeWindowChecked", true)
        oldBridge = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(HistoryRepositoryAccess)
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(
            HistoryRepositoryBridge::class.java.classLoader, arrayOf(HistoryRepositoryBridge::class.java),
        ) { _, method, _ -> when (method.name) {
            "getHistoryTimestampsForSensorBlocking" -> timestamps
            "storeHistoryBatchWithSourceBlocking" -> storeSucceeds
            "getHistoryForNotificationForSensor" -> emptyList<GlucosePoint>()
            else -> null
        } } as HistoryRepositoryBridge)
    }

    @After fun cleanup() {
        manager.setPause(true)
        manager.close()
        HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HistoryRepositoryAccess, oldBridge)
        Applic.app = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun minutes() = get("minuteBackfill") as MutableList<VirtualGlucoseSensorBridge.Reading>
    @Suppress("UNCHECKED_CAST")
    private fun history() = get("historicBackfill") as MutableList<VirtualGlucoseSensorBridge.Reading>
    private fun reading(time: Long = System.currentTimeMillis() - 60_000L) = VirtualGlucoseSensorBridge.Reading(time, 123f)

    @Test fun clinicalReplayUsesStockNativeSlotsEvenWhenRoomAlreadyHasTheMinute() {
        val r = reading()
        timestamps = longArrayOf(r.timestampMs)
        minutes().add(r)
        invoke("flushBackfill")
        assertEquals(1, Libre3ReviewNatives.stockWrites)
        assertEquals(0, Libre3ReviewNatives.resolvedWrites)
        assertTrue(minutes().isEmpty())
    }

    @Test fun failedRoomStoreRetainsBatchAndDoesNotPersistProgress() {
        minutes().add(reading())
        set("lastLifeCount", 101)
        storeSucceeds = false
        invoke("flushBackfill")
        assertEquals(0, Libre3Persistence.getLastLifeCount(context, sensor))
        assertEquals(1, minutes().size)
        storeSucceeds = true
        invoke("flushBackfill")
        assertEquals(101, Libre3Persistence.getLastLifeCount(context, sensor))
        assertTrue(minutes().isEmpty())
    }

    @Test fun failedTimestampLookupDoesNotOverwriteNativeMinuteSlots() {
        history().add(reading())
        set("lastHistoricLifeCount", 100)
        timestamps = null
        invoke("flushBackfill")
        assertEquals(0, Libre3ReviewNatives.stockWrites)
        assertEquals(0, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
        assertEquals(1, history().size)
    }

    @Test fun timeoutAbandonsQueuedHistoricRequestSoLateCompletionCannotSettleIt() {
        set("lastLifeCount", 70)
        set("lastHistoricLifeCount", 50)
        set("controlInFlight", kind("CLINICAL"))
        set("requestedHistoricCommitted", 100)
        @Suppress("UNCHECKED_CAST")
        val queue = get("controlQueue") as ArrayDeque<Pair<Any, ByteArray>>
        queue.addLast(kind("HISTORIC") to Libre3ControlCommand.historicalFrom(50))
        (get("controlTimeoutRunnable") as Runnable).run()
        assertNull(get("controlInFlight"))
        assertTrue(queue.isEmpty())
        invoke("onControlResponse") // late/duplicate completion with no active request
        assertEquals(50, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
        assertEquals(70, Libre3Persistence.getLastLifeCount(context, sensor))
    }

    @Test fun certificateRejectionStopsAutomaticConnectionAttempts() {
        invoke("onHandshakeFailed", Libre3Handshake.Action.Failed(Libre3Handshake.FailureKind.SENSOR_CERTIFICATE, "unknown signer"))
        assertTrue(get("deviceRejected") as Boolean)
        assertFalse(manager.connectDevice(0))
        assertEquals(Libre3BleManager.Phase.IDLE, manager.phase)
    }

    @Test fun watchTakeoverIsRefusedForPersistedLibre3Sensors() {
        Libre3Persistence.saveSensor(context, Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", ByteArray(4), now, 60, 21600, 1, "test", 1))
        assertFalse(ManagedSensorHandoff.supportsSensor(sensor))
        assertTrue(ManagedSensorHandoff.supportsSensor("unrelated"))
    }

    @Test fun removalClearsAllCachedKeysAndRejectsWorkStartedBeforeRemoval() {
        val revision = Libre3Persistence.credentialRevision(context)
        context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("identity_payload.old", "sealed")
            .putString("$sensor.kauth", "sealed")
            .putString("other.kauth", "sealed").commit()
        Libre3Persistence.clearIdentities(context)
        val keys = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).all.keys
        assertFalse(keys.any { it.endsWith(".kauth") || it.startsWith("identity_payload.") })
        assertFalse(Libre3Persistence.saveCachedAuth(context, sensor, ByteArray(16), "old/test", revision))
        val result = runCatching { Libre3Persistence.installIdentities(context, "old", "0".repeat(64), byteArrayOf(), "test", revision) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertFalse(Libre3Persistence.hasIdentities(context))
    }
}

class Libre3ReviewApplication : Applic() { override fun onCreate() {} }

@Implements(value = Natives::class, callThroughByDefault = false)
class Libre3ReviewNatives {
    companion object {
        var stockWrites = 0
        var resolvedWrites = 0
        fun reset() { stockWrites = 0; resolvedWrites = 0 }
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun str2sensorptr(serial: String): Long = 1L
        @JvmStatic @Implementation fun getSensorStartmsecFromSensorptr(ptr: Long): Long = System.currentTimeMillis() - 86_400_000L
        @JvmStatic @Implementation fun addGlucoseStreamBatchWithRawTemp(t: LongArray, g: FloatArray, r: FloatArray, temp: FloatArray, serial: String): Int {
            stockWrites += t.size; return t.size
        }
        @JvmStatic @Implementation fun addResolvedGlucoseStreamBatchWithRawTemp(t: LongArray, g: FloatArray, r: FloatArray, temp: FloatArray, serial: String): Int {
            resolvedWrites += t.size; return t.size
        }
    }
}
