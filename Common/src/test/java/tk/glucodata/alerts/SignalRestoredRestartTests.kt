package tk.glucodata.alerts

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.ui.DisplayValues

/** Real runtime + real preferences; display inputs and final Android/JNI effects are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SignalRestoredRestartTests.TestApplication::class,
    shadows = [SignalRestoredRestartTests.DisplaySource::class,
        SignalRestoredRestartTests.ConfigSource::class, SignalRestoredRestartTests.NativeSource::class,
        SignalRestoredRestartTests.AlarmSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SignalRestoredRestartTests {
    class TestApplication : Applic() { override fun onCreate() {} }

    @Implements(CurrentDisplaySource::class)
    class DisplaySource {
        companion object {
            @JvmStatic @Implementation
            fun resolveCurrent(maxAgeMillis: Long, preferredSensorId: String?, historyWindowMs: Long): CurrentDisplaySource.Snapshot? = null

            @JvmStatic @Implementation
            fun resolveIncomingReading(reading: LiveReadingLanes, rate: Float, time: Long, sensor: String?,
                generation: Int, index: Int, source: String, historyWindowMs: Long,
                preferIncomingSample: Boolean): CurrentDisplaySource.Snapshot = CurrentDisplaySource.Snapshot(
                time, rate, sensor, generation, index, 0, source, 120f, Float.NaN, 120f, 120, false,
                DisplayValues(120f, primaryStr = "120", fullFormatted = "120 mg/dL")
            )
        }
    }

    @Implements(AlertRepository::class)
    class ConfigSource {
        @Implementation
        fun loadConfig(type: AlertType) = AlertDefaults.defaultConfig(type, false)
            .copy(enabled = type == AlertType.SIGNAL_RESTORED)
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSource {
        companion object {
            @JvmStatic @Implementation fun readalarmsuspension(kind: Int): Short = 30
        }
    }

    @Implements(Notify::class)
    class AlarmSink {
        companion object {
            val delivered = mutableListOf<Int>()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun cancelRetrySession(kind: Int, reason: String) {}
            @JvmStatic @Implementation fun triggerSupplementalMessageAlert(kind: Int, message: String): Boolean {
                delivered += kind
                return true
            }
        }
    }

    @Before fun prepare() {
        Applic.app = RuntimeEnvironment.getApplication() as TestApplication
        Applic.app.getSharedPreferences("signal_restored_reading", Context.MODE_PRIVATE).edit().clear().commit()
        AlarmSink.delivered.clear()
    }

    @Test fun coldRuntimeWithoutCurrentSnapshotRestoresTheDurableOutageBaseline() {
        val now = System.currentTimeMillis()
        val old = now - 45 * 60_000L // Outside the display's 25-minute history window.
        val oldProcess = SignalRestoredAlertState(SignalRestoredReadingPreferences(Applic.app))
        assertFalse(oldProcess.onReading("sensor-A", old, old, 0L, 5 * 60_000L))

        // The real bootstrap sees no snapshot. Do not supply a timestamp to seed().
        assertNull(CurrentDisplaySource.resolveCurrent())
        AlertRuntimeManager::class.java.getDeclaredMethod("bootstrapLastReadingLocked")
            .apply { isAccessible = true }.invoke(AlertRuntimeManager)
        try {
            AlertRuntimeManager.onNewReading("sensor-A", LiveReadingLanes.resolved(120f), 0f, now, 0)
            assertEquals(listOf(AlertType.SIGNAL_RESTORED.id), AlarmSink.delivered)
            assertEquals(SignalRestoredReading("sensor-A", now),
                SignalRestoredReadingPreferences(Applic.app).load())
            AlertRuntimeManager.onNewReading("sensor-A", LiveReadingLanes.resolved(120f), 0f, now, 0)
            assertEquals(1, AlarmSink.delivered.size)
        } finally {
            val field = AlertRuntimeManager::class.java.getDeclaredField("monitorTask").apply { isAccessible = true }
            (field.get(AlertRuntimeManager) as? java.util.concurrent.ScheduledFuture<*>)?.cancel(false)
        }
    }

    @Test fun disabledReadingsPersistAndAColdStateDoesNotReplayTheirRecovery() {
        val now = System.currentTimeMillis()
        val oldProcess = SignalRestoredAlertState(SignalRestoredReadingPreferences(Applic.app))
        oldProcess.onReading("sensor-A", now - 60 * 60_000L, now - 60 * 60_000L, 0L, 5 * 60_000L)
        assertFalse(oldProcess.onReading("sensor-A", now - 60_000L, now - 60_000L, 0L, 5 * 60_000L))
        val coldState = SignalRestoredAlertState(SignalRestoredReadingPreferences(Applic.app))
        assertFalse(coldState.onReading("sensor-A", now, now, 30 * 60_000L, 5 * 60_000L))
    }

    @Test fun staleReplayCannotReplaceTheDurableAcceptedReading() {
        val now = System.currentTimeMillis()
        val store = SignalRestoredReadingPreferences(Applic.app)
        val old = now - 60 * 60_000L
        val state = SignalRestoredAlertState(store)
        state.onReading("sensor-A", old, old, 0L, 5 * 60_000L)
        assertFalse(state.onReading("sensor-A", old + 60_000L, now, 30 * 60_000L, 5 * 60_000L))
        assertEquals(SignalRestoredReading("sensor-A", old), store.load())
        assertTrue(SignalRestoredAlertState(SignalRestoredReadingPreferences(Applic.app))
            .onReading("sensor-A", now, now, 30 * 60_000L, 5 * 60_000L))
    }
}
