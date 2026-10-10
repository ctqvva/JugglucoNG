package tk.glucodata.drivers.ottai

import android.content.Context
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

        fun reset() {
            finished.clear()
            onFinish = {}
        }

        @JvmStatic @Implementation
        fun __staticInitializer__() {}

        @JvmStatic @Implementation
        fun finishSensorById(sensorId: String): Boolean {
            onFinish()
            finished += sensorId
            return true
        }
    }
}
