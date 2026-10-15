package tk.glucodata.combined

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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import tk.glucodata.*
import tk.glucodata.alerts.AlertVolumeTestApplication
import tk.glucodata.ui.DisplayValueResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class,
    shadows = [CombinedSensorIntegrationTests.NativeSink::class, CombinedSensorIntegrationTests.PublicationSink::class])
class CombinedSensorIntegrationTests {
    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink {
        companion object {
            var current = "a"
            var allowShell = true
            var start = 0L
            val ids = linkedSetOf("a", "b")
            val nativeValues = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Float>>()
            @JvmStatic @Implementation fun lastsensorname() = current
            @JvmStatic @Implementation fun activeSensors() = ids.toTypedArray()
            @JvmStatic @Implementation fun setcurrentsensor(id: String) { current = id }
            @JvmStatic @Implementation fun ensureSensorShellWithCapacity(id: String, time: Long, capacity: Int): Long {
                if (!allowShell) return 0
                ids.add(id); start = time; return 1
            }
            @JvmStatic @Implementation fun str2sensorptr(id: String): Long = if (id in ids) 1 else 0
            @JvmStatic @Implementation fun getSensorStartmsecFromSensorptr(ptr: Long) = start * 1000
            @JvmStatic @Implementation fun addGlucoseStreamBatchWithRawTemp(times: LongArray, values: FloatArray,
                raw: FloatArray, temperatures: FloatArray, id: String): Int {
                values.forEach { nativeValues += id to it }
                return times.size
            }
        }
    }
    @Implements(SuperGattCallback::class)
    class PublicationSink {
        data class Published(val id: String?, val value: Float, val resolved: Boolean, val time: Long)
        companion object {
            val outputs = java.util.concurrent.CopyOnWriteArrayList<Published>()
            @JvmStatic @Implementation fun processExternalCurrentReading(id: String?, reading: LiveReadingLanes,
                rate: Float, time: Long, generation: Int) {
                outputs += Published(id, reading.resolvedValue, reading.isResolved, time)
            }
        }
    }
    @Before fun setup() {
        Applic.app = RuntimeEnvironment.getApplication() as AlertVolumeTestApplication
        Applic.unit = 0
        Applic.app.getSharedPreferences("tk.glucodata_preferences", 0).edit().clear().commit()
        CombinedSensorRegistry::class.java.getDeclaredField("cached").apply { isAccessible = true }
            .set(CombinedSensorRegistry, null)
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.invalidate()
        NativeSink.current = "a"; NativeSink.ids.clear(); NativeSink.ids.addAll(listOf("a", "b"))
        NativeSink.allowShell = true
        PublicationSink.outputs.clear()
        NativeSink.nativeValues.clear()
        Log.doLog = false
    }
    @After fun cleanup() {
        CombinedSensorRegistry.setEnabled(false, emptyList())
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.invalidate()
    }
    private fun drain(manager: CombinedSensorManager) {
        val executor = CombinedSensorManager::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(manager) as ScheduledExecutorService
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }
    private fun input(id: String, time: Long, mgdl: Float): CurrentDisplaySource.Snapshot {
        val value = if (Applic.unit == 1) mgdl / 18.0182f else mgdl
        val display = DisplayValueResolver.resolve(value, Float.NaN, 0, Applic.unit == 1)
        return CurrentDisplaySource.Snapshot(time, 0f, id, 0, 0, 0, "test", value, Float.NaN,
            value, mgdl.toInt(), Applic.unit == 1, display)
    }
    @Test fun enablingCreatesAndSelectsADerivedSourceAndDisablingRestoresThePhysicalSource() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val config = CombinedSensorRegistry.config()
        assertTrue(config.enabled)
        assertEquals(config.sensorId, SensorIdentity.resolveMainSensor())
        assertEquals(listOf(config.sensorId, "a", "b"), MultiSensorSelection.selectedOrder())
        val old = CombinedSensorRegistry.manager!!
        assertFalse(CombinedSensorRegistry.hasFreshOutput())
        CombinedSensorRegistry.setEnabled(false, emptyList())
        assertTrue(old.isStopped)
        assertEquals("a", SensorIdentity.resolveMainSensor())
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        assertNotSame(old, CombinedSensorRegistry.manager)
        var obsoletePublished = false
        CombinedSensorRegistry.publishIfCurrent(old) { obsoletePublished = true }
        assertFalse(obsoletePublished)
        MultiSensorSelection.setSelectedOrder(listOf("a"))
        assertEquals(listOf("a", "b"), CombinedSensorRegistry.config().inputs)
    }
    @Test fun normalPublicationReceivesTheCombinedResolvedPrimaryInEitherDisplayUnit() {
        for (unit in listOf(0, 1)) {
            Applic.unit = unit
            CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
            val manager = CombinedSensorRegistry.manager!!
            val now = System.currentTimeMillis()
            for (minute in 0..2) {
                val time = now - (2 - minute) * 60_000L
                manager.observe(input("a", time, 100f))
                manager.observe(input("b", time, 110f))
            }
            drain(manager)
            val published = PublicationSink.outputs.last()
            assertEquals(CombinedSensorRegistry.config().sensorId, published.id)
            assertTrue(published.resolved)
            assertEquals(if (unit == 1) 105f / 18.0182f else 105f, published.value, 0.001f)
            assertEquals(now, published.time)
            assertTrue(CombinedSensorRegistry.hasFreshOutput())
            assertEquals(10.5f, NativeSink.nativeValues.last().second, 0.001f)
            assertTrue(NativeSink.nativeValues.all { CombinedSensorRegistry.isCombined(it.first) })
            assertTrue(PublicationSink.outputs.all { CombinedSensorRegistry.isCombined(it.id) })
            CombinedSensorRegistry.setEnabled(false, emptyList())
            PublicationSink.outputs.clear()
        }
    }
    @Test fun failingToCreateNativeHistoryDoesNotEnableOrSelectTheMode() {
        NativeSink.allowShell = false
        assertThrows(IllegalStateException::class.java) { CombinedSensorRegistry.setEnabled(true, listOf("a", "b")) }
        assertFalse(CombinedSensorRegistry.config().enabled)
        assertEquals("a", NativeSink.current)
    }
    @Test fun identityIsOrderIndependentButRejectsRecursiveOrDuplicateInputs() {
        assertEquals(CombinedSensorRegistry.idFor(listOf("a", "b")), CombinedSensorRegistry.idFor(listOf("b", "a")))
        for (invalid in listOf(listOf("a", "a"), listOf("a", "COMB-123"), listOf("a"), listOf("a", ""))) {
            assertThrows(IllegalArgumentException::class.java) { CombinedSensorRegistry.setEnabled(true, invalid) }
        }
    }
}
