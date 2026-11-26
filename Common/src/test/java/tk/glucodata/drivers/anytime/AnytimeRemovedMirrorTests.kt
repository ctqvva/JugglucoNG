package tk.glucodata.drivers.anytime

import android.content.Context
import android.os.HandlerThread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import tk.glucodata.GlucoseAlarmHandler
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback

private const val SENSOR_ID = "AABBCCDDEE01"
private const val ADDRESS = "AA:BB:CC:DD:EE:01"
private const val DEVICE_NAME = "Anytime5252037585"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AnytimeMirrorTestApp::class, shadows = [AnytimeMirrorTestNatives::class])
class AnytimeRemovedMirrorTests {
    private lateinit var manager: AnytimeBleManager
    private val reading = AnytimeAlgorithm.Result(
        glucoseId = 400,
        mmol = 6.1f,
        mgdlTimes10 = 1100,
        ibNa = 5f,
        iwNa = 10f,
        temperatureC = 30f,
        trend = 0,
        errorCode = 0,
        warnCode = 0,
        source = AnytimeAlgorithm.Source.NATIVE,
    )

    @Before
    fun setUp() {
        Applic.app = RuntimeEnvironment.getApplication() as Applic
        AnytimeMirrorTestNatives.removed = true
        AnytimeMirrorTestNatives.acceptWrite = true
        AnytimeMirrorTestNatives.events.clear()
        AnytimeRegistry.addSensor(Applic.app, DEVICE_NAME, ADDRESS, DEVICE_NAME, connectNow = false)
        manager = AnytimeBleManager(SENSOR_ID, 0L)
        SensorBluetooth.gattcallbacks.add(manager)
        Notify.pureglucoseformat = "%.0f"
        SuperGattCallback.glucosealarms = object : GlucoseAlarmHandler {
            override fun handlealarm() = Unit
            override fun setLossAlarm() = Unit
            override fun setagealarm(numsec: Long, showtime: Long) {
                AnytimeMirrorTestNatives.events += "publish"
            }
        }
    }

    @After
    fun tearDown() {
        manager.setPause(true)
        SensorBluetooth.gattcallbacks.remove(manager)
        val thread = AnytimeBleManager::class.java.getDeclaredField("handlerThread").apply { isAccessible = true }
        (thread.get(manager) as HandlerThread).quit()
        SuperGattCallback.glucosealarms = null
        Applic.app = null
    }

    @Test
    fun firstLiveReadingRestoresAnExistingRemovedSlotAfterCredentialImport() {
        val credentials = checkNotNull(
            AnytimeCt5CredentialFile.encode(SENSOR_ID, ADDRESS, DEVICE_NAME, 0x48, intArrayOf(1, 2, 3, 4), "1234")
        )
        // Also cover import over an existing callback that already holds a native pointer.
        manager.dataptr = 42L
        assertEquals(SENSOR_ID, AnytimeRegistry.importCt5Credentials(Applic.app, credentials)?.sensorId)
        AnytimeRegistry.reconnectAfterCt5CredentialImport(Applic.app, SENSOR_ID)
        assertNull(SensorIdentity.resolveMainSensor())
        assertTrue(AnytimeMirrorTestNatives.events.isEmpty())

        commitReading()

        assertEquals(listOf("write", "revive", "publish"), AnytimeMirrorTestNatives.events)
        assertEquals(SENSOR_ID, SensorIdentity.resolveMainSensor())
    }

    @Test
    fun firstLiveReadingRestoresAReAddedSlot() {
        assertNull(SensorIdentity.resolveMainSensor())

        commitReading()

        assertEquals(listOf("write", "revive", "publish"), AnytimeMirrorTestNatives.events)
        assertEquals(SENSOR_ID, SensorIdentity.resolveMainSensor())
    }

    @Test
    fun failedNativeWriteLeavesTheReAddedSlotRemoved() {
        AnytimeMirrorTestNatives.acceptWrite = false

        commitReading()

        assertTrue(AnytimeMirrorTestNatives.events.contains("write"))
        assertTrue(AnytimeMirrorTestNatives.removed)
        assertNull(SensorIdentity.resolveMainSensor())
    }

    @Test
    fun healthySlotPublishesBeforeWritingTheNativeMirror() {
        AnytimeMirrorTestNatives.removed = false

        commitReading()

        assertEquals(listOf("publish", "write"), AnytimeMirrorTestNatives.events)
        assertFalse(AnytimeMirrorTestNatives.removed)
    }

    @Test
    fun manualReconnectReactivatesAnExistingNativePointer() {
        manager.dataptr = 42L

        manager.softReconnect()

        assertEquals(listOf("unfinish"), AnytimeMirrorTestNatives.events)
        assertFalse(AnytimeMirrorTestNatives.removed)
    }

    @Test
    fun terminationMarksTheMacNamedSlotRemovedAndRejectsALateReading() {
        AnytimeMirrorTestNatives.removed = false
        manager.terminateManagedSensor(wipeData = false)
        manager.softReconnect()

        commitReading(expected = false)

        assertEquals(listOf("remove"), AnytimeMirrorTestNatives.events)
        assertTrue(AnytimeMirrorTestNatives.removed)
        assertNull(SensorIdentity.resolveMainSensor())
    }

    private fun commitReading(expected: Boolean = true) {
        val method = AnytimeBleManager::class.java.getDeclaredMethod(
            "commitReading",
            AnytimeAlgorithm.Result::class.java,
            Long::class.javaPrimitiveType,
            Context::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        assertEquals(expected, method.invoke(manager, reading, System.currentTimeMillis(), Applic.app, true, false, false))
    }
}

class AnytimeMirrorTestApp : Applic() {
    override fun onCreate() = Unit
}

@Implements(value = Natives::class, callThroughByDefault = false)
class AnytimeMirrorTestNatives {
    companion object {
        val events = mutableListOf<String>()
        var removed = false
        var acceptWrite = true

        @JvmStatic @Implementation
        fun __staticInitializer__() = Unit

        @JvmStatic @Implementation
        fun lastsensorname(): String = SENSOR_ID

        @JvmStatic @Implementation
        fun currentsensorname(): String = SENSOR_ID

        @JvmStatic @Implementation
        fun activeSensors(): Array<String> = if (removed) emptyArray() else arrayOf(SENSOR_ID)

        @JvmStatic @Implementation
        fun sensorFinishedById(sensorId: String): Boolean = sensorId == SENSOR_ID && removed

        @JvmStatic @Implementation
        fun sensorRemovedByFullName(sensorId: String): Boolean = sensorId == SENSOR_ID && removed

        @JvmStatic @Implementation
        fun setSensorRemovedByFullName(sensorId: String, removed: Boolean) {
            assertEquals(SENSOR_ID, sensorId)
            this.removed = removed
            events += if (removed) "remove" else "revive"
        }

        @JvmStatic @Implementation
        fun unfinishSensor(dataptr: Long) {
            removed = false
            events += "unfinish"
        }

        @JvmStatic @Implementation
        fun addGlucoseStreamWithRawTemp(time: Long, glucose: Float, raw: Float, temp: Float, sensorId: String): Boolean {
            assertEquals(SENSOR_ID, sensorId)
            events += "write"
            return acceptWrite
        }
    }
}
