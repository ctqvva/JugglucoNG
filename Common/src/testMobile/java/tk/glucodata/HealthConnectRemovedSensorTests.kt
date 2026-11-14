package tk.glucodata

import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import tk.glucodata.drivers.libre3.Libre3IdentityAdapter
import tk.glucodata.drivers.libre3.Libre3Persistence
import tk.glucodata.drivers.libre3.Libre3ReviewApplication
import tk.glucodata.drivers.libre3.Libre3SensorRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Libre3ReviewApplication::class, shadows = [HealthConnectRetainedRecordNatives::class])
class HealthConnectRemovedSensorTests {
    private val serial = "0D1234ABC"
    private val context get() = RuntimeEnvironment.getApplication() as Applic

    @Before fun pairSensor() {
        Applic.app = context
        context.getSharedPreferences(Libre3Persistence.PREFS_NAME, 0).edit().clear().commit()
        Libre3Persistence.saveSensor(context, Libre3SensorRecord(
            serial, "AA:BB:CC:DD:EE:FF", ByteArray(4), 1_000_000, 60, 21_600, 123, "test", 1,
        ))
    }

    @After fun cleanup() {
        context.getSharedPreferences(Libre3Persistence.PREFS_NAME, 0).edit().clear().commit()
        Applic.app = null
    }

    @Test fun removingPairingBeforeQueuedReplayKeepsPreviouslyExportedClientIds() = runBlocking {
        fun metadata(version: Long) = HealthConnectRecordMetadata.forReading(
            Metadata.unknownRecordingMethod(), HealthConnectRecordMetadata.prefixForSensor(1, serial, 1000),
            1000, 1120, version,
        )
        val original = metadata(10)
        assertEquals("libre3:$serial:1000:2", original.clientRecordId)
        val otherSensorStarted = CompletableDeferred<Unit>()
        val releaseOtherSensor = CompletableDeferred<Unit>()
        val replayed = CompletableDeferred<Metadata>()
        val queue = HealthConnectExportQueue(CoroutineScope(coroutineContext)) {
            if (it.sensorPtr == 2L) {
                otherSensorStarted.complete(Unit)
                releaseOtherSensor.await()
            } else replayed.complete(metadata(20))
            false
        }
        queue.request(2, "current")
        withTimeout(5_000) { otherSensorStarted.await() }
        queue.request(1, serial)
        assertTrue(Libre3IdentityAdapter.hasPersistedManagedRecord(serial))
        Libre3IdentityAdapter.removePersistedSensor(context, serial)
        assertFalse(Libre3IdentityAdapter.hasPersistedManagedRecord(serial))
        assertNull(Libre3Persistence.loadSensor(context, serial))
        releaseOtherSensor.complete(Unit)
        val replay = withTimeout(5_000) { replayed.await() }
        assertEquals(original.clientRecordId, replay.clientRecordId)
        assertTrue(replay.clientRecordVersion > original.clientRecordVersion)
        // A later export, even after this queue is gone, also keeps the same native identity.
        assertEquals(original.clientRecordId, metadata(30).clientRecordId)
    }

    @Test fun otherNativeFamiliesKeepTheirExistingUnkeyedMetadata() {
        assertNull(HealthConnectRecordMetadata.prefixForSensor(2, "other", 1000))
    }
}

@Implements(Natives::class, callThroughByDefault = false)
class HealthConnectRetainedRecordNatives {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() {}
        // JNI reads the retained native Info record. Pairing preferences do not own it.
        @JvmStatic @Implementation fun getSensorManagedFamilyFromSensorptr(ptr: Long): Int = if (ptr == 1L) 9 else 0
    }
}
