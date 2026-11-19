package tk.glucodata.drivers.ottai

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * A QR-scanned or typed sensor ID reaches the setup connect as a bare address Android has never
 * seen advertising; a nearby-list pick has just been seen. Both must connect the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class, shadows = [OttaiRemovalNatives::class])
class OttaiFirmwareConnectTests {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val address = "AA:BB:CC:DD:EE:FF"
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var session: OttaiFirmwareSession? = null

    @After fun tearDown() {
        session?.close(false)
    }

    private fun newSession(publish: (OttaiFirmwareState) -> Unit = {}): OttaiFirmwareSession {
        shadowOf(adapter).setEnabled(true)
        val constructor = OttaiFirmwareSession::class.java.getDeclaredConstructor(
            Context::class.java, String::class.java, String::class.java,
            OttaiRegistry.DeviceMaterials::class.java, OttaiFirmwareMode::class.java,
            Boolean::class.javaPrimitiveType,
            kotlin.jvm.functions.Function1::class.java,
        ).apply { isAccessible = true }
        return (constructor.newInstance(context, "AABBCCDDEEFF", address,
            OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.7.0", 1),
            OttaiFirmwareMode.PREACTIVATION, true, publish) as OttaiFirmwareSession).also { session = it }
    }

    private fun OttaiFirmwareSession.handler(): Handler =
        OttaiFirmwareSession::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(this) as Handler

    private fun OttaiFirmwareSession.onHandler(action: () -> Unit) {
        val done = CountDownLatch(1)
        handler().post { action(); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun OttaiFirmwareSession.awaitRetirement() {
        val owner = handler().looper.thread
        owner.join(5_000L)
        assertFalse("retirement stops the session handler", owner.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun OttaiFirmwareSession.gatt(): BluetoothGatt? =
        OttaiFirmwareSession::class.java.getDeclaredField("gatt").apply { isAccessible = true }
            .get(this) as BluetoothGatt?

    private fun scanCallbacks(): Set<ScanCallback> = shadowOf(adapter.bluetoothLeScanner).scanCallbacks

    @Test fun onlyAnAddressAndroidHasNotSeenWaitsForItsAdvertisement() {
        assertTrue(ottaiFirmwareNeedsAdvertisement(BluetoothDevice.DEVICE_TYPE_UNKNOWN))
        assertFalse(ottaiFirmwareNeedsAdvertisement(BluetoothDevice.DEVICE_TYPE_LE))
        assertFalse(ottaiFirmwareNeedsAdvertisement(BluetoothDevice.DEVICE_TYPE_DUAL))
    }

    @Test fun unseenAddressConnectsFromTheScannedAdvertisement() {
        val session = newSession()
        session.onHandler { session.connect {} }
        assertNull("no blind connect to an address the stack has not seen", session.gatt())
        val scan = scanCallbacks().single()

        val seen = adapter.getRemoteDevice(address)
        shadowOf(seen).setType(BluetoothDevice.DEVICE_TYPE_LE)
        scan.onScanResult(0, ScanResult(seen, null, -60, 0L))
        session.onHandler {}

        assertNotNull(session.gatt())
        assertTrue("scan stops once the sensor is found", scanCallbacks().isEmpty())
    }

    @Test fun seenAddressConnectsDirectlyLikeAListPick() {
        shadowOf(adapter.getRemoteDevice(address)).setType(BluetoothDevice.DEVICE_TYPE_LE)
        val session = newSession()
        session.onHandler { session.connect {} }
        assertNotNull(session.gatt())
        assertTrue(scanCallbacks().isEmpty())
    }

    @Test fun releasingForOrdinaryConnectClosesTheGattBeforeReturning() {
        shadowOf(adapter.getRemoteDevice(address)).setType(BluetoothDevice.DEVICE_TYPE_LE)
        val session = newSession()
        session.onHandler { session.connect {} }
        val gatt = requireNotNull(session.gatt())

        runBlocking { session.closeAndAwaitRelease() }

        assertTrue(shadowOf(gatt).isClosed)
        assertNull(session.gatt())
    }

    @Test fun closingDuringTheSearchStopsTheScan() {
        val session = newSession()
        session.onHandler { session.connect {} }
        assertEquals(1, scanCallbacks().size)
        session.onHandler { session.disconnect() }
        assertTrue(scanCallbacks().isEmpty())
        assertNull(session.gatt())
    }

    @Test fun aTimeoutClosesTheFirstGattAndRetriesOnlyFromTheSameConnectableAdvertisement() {
        val states = mutableListOf<OttaiFirmwareState>()
        val sensor = adapter.getRemoteDevice(address)
        shadowOf(sensor).setType(BluetoothDevice.DEVICE_TYPE_LE)
        val session = newSession { states += it }
        session.onHandler { session.connect {} }
        val first = requireNotNull(session.gatt())
        shadowOf(first).gattCallback.onConnectionStateChange(first, 147, BluetoothProfile.STATE_DISCONNECTED)
        session.onHandler {}
        assertTrue(shadowOf(first).isClosed)
        assertNull(session.gatt())
        val scan = scanCallbacks().single()
        fun result(device: BluetoothDevice, connectable: Boolean) = ScanResult(
            device, if (connectable) 1 else 0, 1, 0, 0, 127, -60, 0, null, 0L,
        )
        scan.onScanResult(0, result(adapter.getRemoteDevice("AA:BB:CC:DD:EE:01"), true))
        scan.onScanResult(0, result(sensor, false))
        session.onHandler {}
        assertNull(session.gatt())

        scan.onScanResult(0, result(sensor, true))
        session.onHandler {}
        val retry = requireNotNull(session.gatt())
        assertNotSame(first, retry)
        assertTrue(scanCallbacks().isEmpty())

        shadowOf(first).gattCallback.onConnectionStateChange(first, 147, BluetoothProfile.STATE_DISCONNECTED)
        session.onHandler {}
        assertSame(retry, session.gatt())
        shadowOf(retry).gattCallback.onConnectionStateChange(retry, 147, BluetoothProfile.STATE_DISCONNECTED)
        session.awaitRetirement()
        assertTrue(scanCallbacks().isEmpty())
        assertNull(session.gatt())
        assertEquals("setup_connect_timeout_status=147", states.single().errorCode)
    }

    @Test fun timeoutAfterLinkEstablishmentDoesNotRestartSetup() {
        val states = mutableListOf<OttaiFirmwareState>()
        shadowOf(adapter.getRemoteDevice(address)).setType(BluetoothDevice.DEVICE_TYPE_LE)
        val session = newSession { states += it }
        session.onHandler { session.connect {} }
        val gatt = requireNotNull(session.gatt())
        val callback = shadowOf(gatt).gattCallback
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.onConnectionStateChange(gatt, 147, BluetoothProfile.STATE_DISCONNECTED)
        session.awaitRetirement()

        assertTrue(scanCallbacks().isEmpty())
        assertEquals("disconnected_status=147", states.single().errorCode)
    }

    @Test fun postUpdateWaitsForSameConnectableAdvertAndRescansOnceAfterLinkFailure() {
        val states = mutableListOf<OttaiFirmwareState>()
        val sensor = adapter.getRemoteDevice(address)
        shadowOf(sensor).setType(BluetoothDevice.DEVICE_TYPE_LE)
        val session = newSession { states += it }
        session.onHandler { session.connectAfterUpdate { fail("GATT discovery did not succeed") } }
        assertNull(session.gatt())
        val scan = scanCallbacks().single()
        fun result(device: BluetoothDevice, connectable: Boolean = true) = ScanResult(
            device, if (connectable) 1 else 0, 1, 0, 0, 127, -60, 0, null, 0L,
        )
        scan.onScanResult(0, result(adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")))
        scan.onScanResult(0, result(sensor, connectable = false))
        session.onHandler {}
        assertNull(session.gatt())

        scan.onScanResult(0, result(sensor))
        session.onHandler {}
        val first = requireNotNull(session.gatt())
        shadowOf(first).gattCallback.onConnectionStateChange(first, 147, BluetoothProfile.STATE_DISCONNECTED)
        session.onHandler {}
        assertTrue(shadowOf(first).isClosed)
        assertNull(session.gatt())
        assertTrue(scanCallbacks().isEmpty())
        shadowOf(session.handler().looper).idleFor(Duration.ofMillis(1_500))

        val retryScan = scanCallbacks().single()
        assertNotSame(scan, retryScan)
        scan.onScanResult(0, result(sensor))
        session.onHandler {}
        assertNull(session.gatt())
        retryScan.onScanResult(0, result(sensor))
        session.onHandler {}
        val retry = requireNotNull(session.gatt())
        assertNotSame(first, retry)
        shadowOf(retry).gattCallback.onConnectionStateChange(retry, 147, BluetoothProfile.STATE_DISCONNECTED)
        session.awaitRetirement()
        assertTrue(scanCallbacks().isEmpty())
        assertEquals("disconnected_status=147", states.single().errorCode)
    }

    @Test fun postUpdateRejectsUnrecognizedServicesWithoutRescanning() {
        val states = mutableListOf<OttaiFirmwareState>()
        val sensor = adapter.getRemoteDevice(address)
        val session = newSession { states += it }
        session.onHandler { session.connectAfterUpdate { fail("unexpected GATT profile was accepted") } }
        val result = ScanResult(sensor, 1, 1, 0, 0, 127, -60, 0, null, 0L)
        scanCallbacks().single().onScanResult(0, result)
        session.onHandler {}

        val gatt = requireNotNull(session.gatt())
        val shadowGatt = shadowOf(gatt)
        // Device Information exists, but there is no Ottai revision characteristic or OTA profile.
        shadowGatt.addDiscoverableService(
            BluetoothGattService(OttaiConstants.SERVICE_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY),
        )
        shadowGatt.gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        session.onHandler {}
        session.onHandler {} // Drain the MTU callback posted during connection.
        // Discovery retires the owner. Advance time without asking its looper to run afterward.
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_500))
        session.awaitRetirement()

        assertTrue(shadowGatt.isClosed)
        assertTrue(scanCallbacks().isEmpty())
        assertEquals("services_missing", states.single().errorCode)
    }

    @Test fun acceptedFinishWithDisconnectQueuedBeforeAckCompletesOnce() {
        val sensor = adapter.getRemoteDevice(address)
        shadowOf(sensor).setType(BluetoothDevice.DEVICE_TYPE_LE)
        val session = newSession()
        session.onHandler { session.connect {} }
        val gatt = requireNotNull(session.gatt())
        val service = BluetoothGattService(OttaiConstants.SERVICE_OTA, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for (uuid in listOf(OttaiConstants.CHAR_OTA_CONTROL, OttaiConstants.CHAR_OTA_DATA)) {
            service.addCharacteristic(BluetoothGattCharacteristic(uuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        }
        shadowOf(gatt).addDiscoverableService(service)
        val callback = shadowOf(gatt).gattCallback
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        session.onHandler {}
        // Drain the MTU callback posted during connection before advancing the clock. Run late,
        // it re-arms the discovery delay past the advance, the finish write then finds the
        // connect still pending, and the session retires.
        session.onHandler {}
        shadowOf(session.handler().looper).idleFor(Duration.ofMillis(2_500))
        session.onHandler {}
        val outcomes = mutableListOf<OttaiOtaFinish>()
        session.onHandler {
            // The shadow ACKs writes immediately. Queue the reboot callback first so the
            // session processes it after submitWrite returns but before processing the ACK.
            callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
            session.finishUpdate { outcomes += it.getOrThrow() }
        }
        session.onHandler {}
        assertArrayEquals(byteArrayOf(OttaiOtaProtocol.FINISH), shadowOf(gatt).latestWrittenBytes)
        assertEquals(listOf(OttaiOtaFinish.DISCONNECTED), outcomes)
    }
}
