package tk.glucodata.drivers.nightscout

import android.content.Context
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
import org.robolectric.annotation.RealObject
import tk.glucodata.*

/** Actual save/restore/disable/startup paths and callback roster; JNI and poll dispatch only
 * are shadowed so these tests cannot contact a server. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34], application = NightscoutLifecycleApplication::class,
    shadows = [NightscoutLifecycleNatives::class, NightscoutPollDispatch::class],
)
class NightscoutFollowerLifecycleTests {
    private val app get() = RuntimeEnvironment.getApplication() as NightscoutLifecycleApplication
    private val oldUrl = "https://old.example.com"
    private val newUrl = "https://new.example.com"
    private val oldId = NightscoutFollowerRegistry.deriveSensorId(oldUrl)
    private val newId = NightscoutFollowerRegistry.deriveSensorId(newUrl)
    private var previousApp: Applic? = null

    @Before fun setup() {
        previousApp = Applic.app
        Applic.app = app
        app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        ManagedCurrentSensor.clear()
        SensorBluetooth.gattcallbacks.clear()
        SensorBluetooth.blueone = null
        SensorIdentity.invalidateCaches()
        NightscoutLifecycleNatives.history.clear()
        NightscoutLifecycleNatives.active.clear()
        NightscoutLifecycleNatives.finished.clear()
        NightscoutLifecycleNatives.selected = ""
        NightscoutPollDispatch.connected.clear()
    }

    @After fun cleanup() {
        SensorBluetooth.mygatts().filterIsInstance<NightscoutFollowerManager>().forEach {
            it.terminateManagedSensor(false)
            it.close()
        }
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.clear()
        SensorIdentity.invalidateCaches()
        Applic.app = previousApp
    }

    private fun startOldFollower(): NightscoutFollowerManager {
        NightscoutFollowerRegistry.saveConfig(app, true, oldUrl, "")
        val old = NightscoutFollowerRegistry.restoreConfiguredFollower(app)!!
        ManagedCurrentSensor.set(oldId)
        NightscoutLifecycleNatives.history += oldId
        NightscoutLifecycleNatives.active += oldId
        NightscoutLifecycleNatives.selected = oldId.drop(5)
        return old
    }

    @Test fun savingANewUrlWithoutAConnectCallStartsTheReplacement() {
        val old = startOldFollower()
        NightscoutFollowerRegistry.saveConfig(app, true, newUrl, "secret")
        assertTrue(old.pauseRequested())
        assertEquals(listOf(newId), SensorBluetooth.mygatts().map { it.SerialNumber })
        assertEquals(listOf(newId), NightscoutPollDispatch.connected)
        assertEquals(newId, ManagedCurrentSensor.get())
        assertTrue(oldId in NightscoutLifecycleNatives.finished)
        assertTrue(oldId in NightscoutLifecycleNatives.history)
    }

    @Test fun enableWithConnectNowFalseStillStartsAUrlReplacement() {
        startOldFollower()
        NightscoutFollowerRegistry.enableFollowerSensor(app, newUrl, "", connectNow = false)
        assertEquals(listOf(newId), NightscoutPollDispatch.connected)
    }

    @Test fun disablingWithoutBluetoothRemovesTheCallbackAndKeepsHistory() {
        val old = startOldFollower()
        NightscoutFollowerRegistry.disableFollowerSensor(app)
        assertTrue(old.pauseRequested())
        assertTrue(SensorBluetooth.mygatts().isEmpty())
        assertFalse(NightscoutFollowerRegistry.loadConfig(app).enabled)
        assertNull(ManagedCurrentSensor.get())
        assertTrue(oldId in NightscoutLifecycleNatives.finished)
        assertTrue(oldId in NightscoutLifecycleNatives.history)
        assertTrue(NightscoutPollDispatch.connected.isEmpty())
    }

    @Test fun startupRetiresAStaleNativeFollowerWithoutReplacingAnUnrelatedManagedMain() {
        startOldFollower()
        NightscoutFollowerRegistry.saveConfig(app, true, newUrl, "")
        // Recreate the mixed selection from CurrentSensorSelection: a managed physical sensor
        // is selected before its mirror exists, leaving native current on the old follower.
        val physical = "AB1238871A25"
        SensorBluetooth.gattcallbacks.add(object : SuperGattCallback(physical, 0L, 0) {})
        ManagedCurrentSensor.set(physical)
        NightscoutLifecycleNatives.active += oldId
        NightscoutLifecycleNatives.selected = oldId.drop(5)
        NightscoutLifecycleNatives.finished.clear()
        NightscoutFollowerRegistry.reconcileOrphanedFollowers(app)
        assertEquals(physical, ManagedCurrentSensor.get())
        assertEquals(oldId.drop(5), NightscoutLifecycleNatives.selected)
        assertTrue(oldId in NightscoutLifecycleNatives.finished)
    }

    @Test fun replacingANonMainFollowerStillStartsItAndPreservesTheManagedPhysicalMain() {
        startOldFollower()
        val physical = "AB1238871A25"
        SensorBluetooth.gattcallbacks.add(object : SuperGattCallback(physical, 0L, 0) {})
        ManagedCurrentSensor.set(physical)
        assertEquals(physical, SensorIdentity.resolveMainSensor())
        NightscoutFollowerRegistry.saveConfig(app, true, newUrl, "")
        assertEquals(physical, ManagedCurrentSensor.get())
        assertEquals(listOf(newId), NightscoutPollDispatch.connected)
    }

    @Test fun disableClearsAPersistedSelectionEvenWithoutACallback() {
        NightscoutFollowerRegistry.saveConfig(app, true, oldUrl, "")
        ManagedCurrentSensor.set(oldId)
        NightscoutFollowerRegistry.disableFollowerSensor(app)
        assertNull(ManagedCurrentSensor.get())
    }

    @Test fun startupRecognizesALocalFollowerWhoseFirstHistoryImportNeverFinished() {
        startOldFollower()
        assertFalse(NightscoutFollowerRegistry.hasCompleteHistoryImport(app, oldId))
        NightscoutFollowerRegistry.saveConfig(app, true, newUrl, "")
        ManagedCurrentSensor.set(oldId)
        NightscoutLifecycleNatives.active += oldId
        NightscoutLifecycleNatives.finished.clear()
        NightscoutFollowerRegistry.reconcileOrphanedFollowers(app)
        assertEquals(newId, ManagedCurrentSensor.get())
        assertTrue(oldId in NightscoutLifecycleNatives.finished)
    }

    @Test fun savingOverALegacyIncompleteImportRecordsItsOwnership() {
        // Old versions had no ownership marker and an interrupted import had no complete flag.
        app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("nightscout_follower_enabled", true)
            .putString("nightscout_follower_url", oldUrl).commit()
        assertFalse(NightscoutFollowerRegistry.hasLocalFollower(app, oldId))
        NightscoutFollowerRegistry.saveConfig(app, true, newUrl, "")
        assertTrue(NightscoutFollowerRegistry.hasLocalFollower(app, oldId))
    }

    @Test fun startupLeavesAnUnownedCloneFollowerUntouched() {
        NightscoutFollowerRegistry.saveConfig(app, true, newUrl, "")
        ManagedCurrentSensor.set(oldId)
        NightscoutLifecycleNatives.history += oldId
        NightscoutLifecycleNatives.active += oldId
        NightscoutLifecycleNatives.selected = oldId.drop(5)
        NightscoutFollowerRegistry.reconcileOrphanedFollowers(app)
        assertEquals(oldId, ManagedCurrentSensor.get())
        assertTrue(NightscoutLifecycleNatives.finished.isEmpty())
    }
}

class NightscoutLifecycleApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = NightscoutFollowerManager::class, isInAndroidSdk = false)
class NightscoutPollDispatch {
    @RealObject private lateinit var follower: NightscoutFollowerManager
    @Implementation fun connectDevice(delayMillis: Long): Boolean {
        connected += follower.SerialNumber
        return true
    }
    companion object { val connected = mutableListOf<String>() }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class NightscoutLifecycleNatives {
    companion object {
        val history = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        val finished = mutableSetOf<String>()
        var selected = ""
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun activeSensors() = active.map { it.drop(5) }.toTypedArray()
        @JvmStatic @Implementation fun currentsensorname() = selected
        @JvmStatic @Implementation fun lastsensorname() = selected.ifBlank { active.firstOrNull()?.drop(5).orEmpty() }
        @JvmStatic @Implementation fun setcurrentsensor(name: String) { selected = name }
        @JvmStatic @Implementation fun resolveFullSensorName(name: String): String? =
            history.firstOrNull { it == name || it.drop(5) == name }
        @JvmStatic @Implementation fun finishSensorById(name: String): Boolean {
            finished += name
            active.remove(name)
            return name in history
        }
    }
}
