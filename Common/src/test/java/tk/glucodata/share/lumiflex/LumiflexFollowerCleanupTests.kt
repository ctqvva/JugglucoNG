package tk.glucodata.share.lumiflex

import android.content.Intent
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
import org.robolectric.shadows.ShadowLooper
import tk.glucodata.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class, shadows = [LumiflexFollowerCleanupTests.NativeInputs::class])
class LumiflexFollowerCleanupTests {
    @Implements(Natives::class)
    class NativeInputs {
        companion object {
            val history = mutableSetOf<String>()
            val active = mutableListOf<String>()
            var selected = ""
            var reportShortSelection = false
            @JvmStatic @Implementation fun getDeviceAddress(pointer: Long, fresh: Boolean): String? = null
            @JvmStatic @Implementation fun getSensorStartmsec(pointer: Long) = 0L
            @JvmStatic @Implementation fun setmaxsensors(count: Int) = Unit
            @JvmStatic @Implementation fun activeSensors(): Array<String> = active.toTypedArray()
            @JvmStatic @Implementation fun currentsensorname() =
                if (reportShortSelection && selected.startsWith("LMF-")) selected.takeLast(11) else selected
            @JvmStatic @Implementation fun lastsensorname() = selected.ifBlank { active.firstOrNull().orEmpty() }
            @JvmStatic @Implementation fun setcurrentsensor(name: String) { selected = name }
            @JvmStatic @Implementation fun resolveFullSensorName(name: String): String? =
                history.firstOrNull { it == name || it.takeLast(11) == name }
            @JvmStatic @Implementation fun finishSensorById(name: String): Boolean { active.remove(name); return name in history }
            @JvmStatic @Implementation fun str2sensorptr(name: String) = 0L
        }
    }
    private val app get() = RuntimeEnvironment.getApplication() as LumiflexCloudTestApplication
    private val settings get() = LumiflexCloudSettings(app)
    private var previousApp: Applic? = null
    private lateinit var serial: String

    @Before fun setup() {
        previousApp = Applic.app; Applic.app = app
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.invalidate(); SensorIdentity.invalidateCaches()
        settings.clear(); settings.startSession("reader", "token", "user"); settings.enabled = true
        settings.selectMode(true, FollowerInfo(dataProviderId = "patient"))
        serial = LumiflexFollowerRegistry.sensorId(app)
        NativeInputs.history.clear(); NativeInputs.active.clear(); NativeInputs.selected = ""
        NativeInputs.reportShortSelection = false
        NativeInputs.history += listOf(serial, "3MH00000000")
        NativeInputs.active += listOf(serial, "3MH00000000")
        assertNotNull(LumiflexFollowerRegistry.reconcile(app))
        assertEquals(serial, ManagedCurrentSensor.get())
    }
    @After fun cleanup() {
        SensorBluetooth.gattcallbacks.filterIsInstance<LumiflexFollowerManager>().forEach { it.terminateManagedSensor(false); it.close() }
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.clear(); ManagedCurrentSensor.invalidate(); SensorIdentity.invalidateCaches()
        Applic.app = previousApp
    }

    @Test fun expiredAlarmRetiresTheFollowerAndSelectsAnAvailableSensorWithoutDeletingHistory() {
        settings.invalidateSession(settings.session())
        app.sendBroadcast(Intent(app, LumiflexFollowerPollReceiver::class.java).setAction(LumiflexFollowerPollReceiver.ACTION))
        ShadowLooper.idleMainLooper()
        assertTrue(SensorBluetooth.gattcallbacks.none { it is LumiflexFollowerManager })
        assertNull(ManagedCurrentSensor.get())
        assertEquals("3MH00000000", NativeInputs.selected)
        assertFalse(serial in NativeInputs.active)
        assertTrue(serial in NativeInputs.history)
    }

    @Test fun expirationOfTheOnlySourceClearsSelectionRatherThanReselectingItsRetiredMirror() {
        NativeInputs.active.remove("3MH00000000")
        settings.invalidateSession(settings.session())
        LumiflexFollowerRegistry.reconcile(app)
        assertNull(ManagedCurrentSensor.get())
        assertEquals("", NativeInputs.selected)
        assertTrue(NativeInputs.active.isEmpty())
        assertTrue(serial in NativeInputs.history)
    }

    @Test fun historicalSelectionSurvivesFollowerCleanupWithAnActiveAlternative() {
        val historical = "3MH00000001"
        NativeInputs.history += historical
        SensorBluetooth.setCurrentSensorSelection(historical)
        assertFalse(historical in NativeInputs.active)
        assertTrue(SensorBluetooth.gattcallbacks.none { it.SerialNumber == historical })
        settings.invalidateSession(settings.session())
        LumiflexFollowerRegistry.reconcile(app)
        assertEquals(historical, NativeInputs.selected)
        assertNull(ManagedCurrentSensor.get())
        assertTrue(SensorBluetooth.gattcallbacks.none { it is LumiflexFollowerManager })
        assertFalse(serial in NativeInputs.active)
    }

    @Test fun historicalSelectionSurvivesFollowerCleanupWithoutActiveAlternatives() {
        val historical = "3MH00000001"
        NativeInputs.history += historical
        NativeInputs.active.remove("3MH00000000")
        SensorBluetooth.setCurrentSensorSelection(historical)
        settings.invalidateSession(settings.session())
        LumiflexFollowerRegistry.reconcile(app)
        assertTrue(NativeInputs.active.isEmpty())
        assertEquals(historical, NativeInputs.selected)
        assertNull(ManagedCurrentSensor.get())
        assertTrue(historical in NativeInputs.history)
    }

    @Test fun expiredFollowersShortNativeAliasIsNotPreservedAsAHistoricalPhysicalSensor() {
        NativeInputs.reportShortSelection = true
        settings.invalidateSession(settings.session())
        LumiflexFollowerRegistry.reconcile(app)
        assertEquals("3MH00000000", NativeInputs.selected)
        assertNull(ManagedCurrentSensor.get())
        assertFalse(serial in NativeInputs.active)
    }
}
