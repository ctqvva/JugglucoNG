package tk.glucodata.drivers.ottai

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

    private fun newSession(): OttaiFirmwareSession {
        shadowOf(adapter).setEnabled(true)
        val constructor = OttaiFirmwareSession::class.java.getDeclaredConstructor(
            Context::class.java, String::class.java, String::class.java,
            OttaiRegistry.DeviceMaterials::class.java, OttaiFirmwareMode::class.java,
            Boolean::class.javaPrimitiveType,
            kotlin.jvm.functions.Function1::class.java,
        ).apply { isAccessible = true }
        val publish: (OttaiFirmwareState) -> Unit = {}
        return (constructor.newInstance(context, "AABBCCDDEEFF", address,
            OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.7.0", 1),
            OttaiFirmwareMode.PREACTIVATION, true, publish) as OttaiFirmwareSession).also { session = it }
    }

    private fun OttaiFirmwareSession.onHandler(action: () -> Unit) {
        val handler = OttaiFirmwareSession::class.java.getDeclaredField("handler")
            .apply { isAccessible = true }.get(this) as Handler
        val done = CountDownLatch(1)
        handler.post { action(); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
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

    @Test fun closingDuringTheSearchStopsTheScan() {
        val session = newSession()
        session.onHandler { session.connect {} }
        assertEquals(1, scanCallbacks().size)
        session.onHandler { session.disconnect() }
        assertTrue(scanCallbacks().isEmpty())
        assertNull(session.gatt())
    }
}
