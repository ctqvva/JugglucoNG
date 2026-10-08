package tk.glucodata

import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.drivers.ManagedSensorViewModeStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExternalCurrentReadingTests.TestApplication::class,
    shadows = [ExternalCurrentReadingTests.PublicationSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExternalCurrentReadingTests {
    class TestApplication : Applic() {
        override fun onCreate() { /* No native initialization in the JVM harness. */ }
    }

    // Run the real publisher and resolver; intercept only the final side effects.
    @Implements(SuperGattCallback::class)
    class PublicationSink {
        companion object {
            var value = Float.NaN
            var timestamp = 0L

            @JvmStatic
            @Implementation
            fun dowithglucose(serial: String, mgdl: Int, glucose: Float, rate: Float, alarm: Int,
                time: Long, start: Long, timeout: Long, generation: Int, reading: LiveReadingLanes) {
                value = glucose
                timestamp = time
            }
        }
    }

    @Test
    fun pendingSibionicsWritePublishesIncomingLanesAtTheirOwnTimestamp() {
        val previousApp = Applic.app
        val previousUnit = Applic.unit
        val previousAlarms = SuperGattCallback.glucosealarms
        val bridgeField = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }
        val previousBridge = bridgeField.get(null)
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        SuperGattCallback.glucosealarms = object : GlucoseAlarmHandler {
            override fun handlealarm() = Unit
            override fun setLossAlarm() = Unit
            override fun setagealarm(numsec: Long, showtime: Long) = Unit
        }
        val serial = "GS1-live-test"
        val time = System.currentTimeMillis()
        var room = emptyList<GlucosePoint>()
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(HistoryRepositoryBridge::class.java.classLoader,
            arrayOf(HistoryRepositoryBridge::class.java)) { _, method, _ ->
            when (method.name) {
                "getHistoryForNotificationForSensor" -> room
                else -> error("Unexpected repository operation: ${method.name}")
            }
        } as HistoryRepositoryBridge)
        try {
            for (isMmol in listOf(false, true)) {
                Applic.unit = if (isMmol) 1 else 0
                val scale = if (isMmol) 18.0182f else 1f
                for (viewMode in 0..3) {
                    ManagedSensorViewModeStore.write(Applic.app, serial, viewMode)
                    for (incoming in listOf(55f, 220f)) {
                        // Both lanes previously in range. The async write has not landed.
                        room = listOf(GlucosePoint(time - 60_000L, 120f / scale, 115f / scale))
                        val lanes = LiveReadingLanes.stock(incoming / scale, (incoming - 5f) / scale)
                        PublicationSink.value = Float.NaN
                        SuperGattCallback.processExternalCurrentReading(serial, lanes, 0f, time, 0)
                        val expected = (if (viewMode and 1 == 0) incoming else incoming - 5f) / scale
                        assertEquals("pending write, view=$viewMode, mmol=$isMmol", expected, PublicationSink.value, 0.001f)
                        assertEquals(time, PublicationSink.timestamp)
                        // The same publication must agree once Room contains the sample.
                        room = room + GlucosePoint(time, incoming / scale, (incoming - 5f) / scale)
                        SuperGattCallback.processExternalCurrentReading(serial, lanes, 0f, time, 0)
                        assertEquals(expected, PublicationSink.value, 0.001f)
                        assertEquals(time, PublicationSink.timestamp)
                    }
                }
            }
        } finally {
            ManagedSensorViewModeStore.clear(Applic.app, serial)
            bridgeField.set(null, previousBridge)
            SuperGattCallback.glucosealarms = previousAlarms
            Applic.unit = previousUnit
            Applic.app = previousApp
        }
    }
}
