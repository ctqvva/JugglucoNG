package tk.glucodata.drivers.ottai

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.SensorBluetooth

/**
 * Removing an Ottai sensor has to retire its native mirror shell as well. Left active, the
 * shell's short name had no owner once the record was gone and came back as a generic Libre
 * callback: a paused card titled "8871A25" that took a second Disconnect to clear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class, shadows = [OttaiRemovalNatives::class])
class OttaiRemovalNativeShellTests {
    private val sensorId = "AB1238871A25"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        SensorIdentity.invalidateCaches()
        OttaiRemovalNatives.reset()
    }

    @Test
    fun removalFinishesTheNativeShellWhileTheRecordStillClaimsIt() {
        OttaiRegistry.ensureSensorRecord(context, sensorId, "", "Ottai CGM")
        OttaiRemovalNatives.onFinish = {
            // Finishing after the record is gone leaves a window in which a device refresh sees
            // an active, unclaimed shell and builds the generic callback anyway.
            assertTrue(OttaiRegistry.persistedRecords(context).any { it.sensorId == sensorId })
        }

        OttaiRegistry.removeSensor(context, sensorId)

        assertEquals(listOf(sensorId), OttaiRemovalNatives.finished)
        assertTrue(OttaiRegistry.persistedRecords(context).isEmpty())
    }

    @Test
    fun removalFinishesTheShellNamedByTheRecordForAColonAddressId() {
        OttaiRegistry.ensureSensorRecord(context, sensorId, "", "Ottai CGM")

        OttaiRegistry.removeSensor(context, "AB:12:38:87:1A:25")

        assertEquals(listOf(sensorId), OttaiRemovalNatives.finished)
    }

    @Test
    fun removalDrainsRunningLiveWriteBeforeFinishingAndRejectsLateMirrors() = checkRunningWriteRemoval(live = true)

    @Test
    fun removalDrainsRunningHistoryWriteBeforeFinishingAndRejectsLateMirrors() = checkRunningWriteRemoval(live = false)

    private fun checkRunningWriteRemoval(live: Boolean) {
        OttaiRegistry.ensureSensorRecord(context, sensorId, "", "Ottai CGM")
        val manager = OttaiBleManager(sensorId, 0L)
        val materials = OttaiRegistry.DeviceMaterials("", "", "", 60_000L, "", 0)
        set(manager, "materials", materials)
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        SensorBluetooth.gattcallbacks.add(manager)
        OttaiRemovalNatives.onWrite = {
            enteredWrite.countDown()
            assertTrue("release native write", releaseWrite.await(5, TimeUnit.SECONDS))
        }
        try {
            val writer = pool.submit {
                if (live) mirrorLive(manager)
                else invoke(manager, "mirrorHistoryIntoNative", sensorId, listOf(reading()))
            }
            assertTrue("native write started", enteredWrite.await(5, TimeUnit.SECONDS))
            val removal = pool.submit { OttaiRegistry.removeSensor(context, sensorId) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (manager.isUiEnabled() && !removal.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertFalse("callback must stop before record removal", manager.isUiEnabled())
            assertFalse("removal must wait for the running native write", removal.isDone)
            assertTrue(OttaiRegistry.persistedRecords(context).any { it.sensorId == sensorId })
            assertTrue(OttaiRemovalNatives.finished.isEmpty())

            releaseWrite.countDown()
            writer.get(5, TimeUnit.SECONDS)
            removal.get(5, TimeUnit.SECONDS)
            assertTrue("native shell stays finished", OttaiRemovalNatives.shellFinished)
            assertEquals(listOf(sensorId), OttaiRemovalNatives.finished)
            assertTrue(OttaiRegistry.persistedRecords(context).isEmpty())

            val callsAfterRemoval = OttaiRemovalNatives.nativeWrites
            mirrorLive(manager)
            invoke(manager, "mirrorHistoryIntoNative", sensorId, listOf(reading()))
            invoke(manager, "reconcileNativeFromRoom", sensorId)
            invoke(manager, "ensureNativePresenceShell", "late-anchor")
            set(manager, "materials", materials.copy(activeTimeMs = 1_782_823_440_000L))
            invoke(manager, "repairSecondsUnitNativeStart", sensorId)
            set(manager, "activatedMaxActiveMs", TimeUnit.DAYS.toMillis(28))
            invoke(manager, "applyActivatedWearToNative", sensorId)
            assertEquals("late work must not touch native storage", callsAfterRemoval, OttaiRemovalNatives.nativeWrites)
            assertTrue(OttaiRemovalNatives.shellFinished)
        } finally {
            releaseWrite.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            SensorBluetooth.gattcallbacks.remove(manager)
            // Permanently close the constructor's handler thread even on an assertion failure.
            tk.glucodata.SuperGattCallback::class.java.getDeclaredField("stop").apply { isAccessible = true }
                .setBoolean(manager, true)
            manager.close()
        }
    }

    private fun reading(): Any {
        val type = OttaiBleManager::class.java.declaredClasses.single { it.simpleName == "EmittedReading" }
        return type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(120_000L, 110f, 110f, true, true, 1, 30f)
    }

    private fun mirrorLive(manager: OttaiBleManager) = invoke(manager, "mirrorLiveReadingIntoNative", sensorId, reading())

    private fun invoke(manager: OttaiBleManager, name: String, vararg args: Any) {
        OttaiBleManager::class.java.declaredMethods.single { it.name == name }
            .apply { isAccessible = true }.invoke(manager, *args)
    }

    private fun set(manager: OttaiBleManager, name: String, value: Any) {
        OttaiBleManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    @Test
    fun removingAnUnknownSensorFinishesNothing() {
        OttaiRegistry.ensureSensorRecord(context, sensorId, "", "Ottai CGM")

        OttaiRegistry.removeSensor(context, "CD4568871A25")

        assertTrue(OttaiRemovalNatives.finished.isEmpty())
        assertEquals(1, OttaiRegistry.persistedRecords(context).size)
    }
}

class OttaiRemovalTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class OttaiRemovalNatives {
    companion object {
        val finished = mutableListOf<String>()
        var onFinish: () -> Unit = {}
        var onWrite: () -> Unit = {}
        var shellFinished = false
        var nativeWrites = 0

        fun reset() {
            finished.clear()
            onFinish = {}
            onWrite = {}
            shellFinished = false
            nativeWrites = 0
        }

        @JvmStatic @Implementation
        fun __staticInitializer__() {}

        @JvmStatic @Implementation
        fun ensureSensorShellWithCapacity(sensorId: String, startSec: Long, minimumRecords: Int): Long {
            shellFinished = false
            nativeWrites++
            return 1L
        }

        @JvmStatic @Implementation
        fun addGlucoseStreamWithTemp(timestamp: Long, glucose: Float, temperature: Float, sensorId: String): Boolean {
            onWrite()
            shellFinished = false
            nativeWrites++
            return true
        }

        @JvmStatic @Implementation
        fun addGlucoseStreamBatchWithTemp(timestamps: LongArray, glucose: FloatArray, temperatures: FloatArray,
            sensorId: String): Int {
            onWrite()
            shellFinished = false
            nativeWrites++
            return timestamps.size
        }

        @JvmStatic @Implementation
        fun setSensorWearDays(sensorId: String, days: Int) {
            shellFinished = false
            nativeWrites++
        }

        @JvmStatic @Implementation
        fun finishSensorById(sensorId: String): Boolean {
            shellFinished = true
            onFinish()
            finished += sensorId
            return true
        }
    }
}
