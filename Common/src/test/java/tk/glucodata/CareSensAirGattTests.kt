package tk.glucodata

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt
import tk.glucodata.drivers.caresens.CareSensAirEngine
import tk.glucodata.drivers.caresens.CareSensAirOutput
import tk.glucodata.drivers.caresens.CareSensAirProtocol
import tk.glucodata.drivers.caresens.CareSensAirVersion
import tk.glucodata.drivers.caresens.littleEndian
import tk.glucodata.drivers.caresens.wireBytes

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = CareSensTestApplication::class,
    shadows = [CareSensNativeShadow::class, CareSensGattShadow::class, CareSensLiveShadow::class],
)
class CareSensAirGattTests {
    @get:Rule val folder = TemporaryFolder()
    private val worker = ScheduledThreadPoolExecutor(1)
    private lateinit var directory: File
    private lateinit var driver: CareSensAirGattCallback
    private lateinit var peer: Peer

    @Before
    fun setUp() {
        Applic.app = RuntimeEnvironment.getApplication() as Applic
        SystemClock.setCurrentTimeMillis(1_709_726_400_000L)
        CareSensNativeShadow.address = null
        CareSensNativeShadow.start = 0L
        CareSensNativeShadow.writes.clear()
        CareSensLiveShadow.deliveries.clear()
        directory = folder.newFolder()
        driver = newDriver()
        peer = Peer()
    }

    @After
    fun tearDown() {
        driver.free()
        worker.shutdownNow()
        worker.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun activationPairsAndPublishesAndRestartRequestsOnlyMissingRecords() {
        peer.connect()
        peer.verify("SYNTHETIC123")
        drain()
        assertEquals(ADDRESS, CareSensNativeShadow.address)
        peer.software()
        peer.subscribe("c4dec61c")
        peer.expectWrite(CareSensAirProtocol.registerApp(true))
        peer.notify("c4dec61c", CareSensAirProtocol.registerApp(false))
        drain()
        assertTrue(driver.pairingRequest())
        assertArrayEquals(PIN, Shadows.shadowOf(peer.device).pin)
        Shadows.shadowOf(peer.device).setBondState(BluetoothDevice.BOND_BONDED)
        driver.bonded()

        peer.startSensor()
        peer.notify("c4de9b74", wireBytes("c4010100"))
        peer.expectWrite(wireBytes("c501"))
        peer.reading(1)
        peer.expectEvent("close")
        assertEquals(listOf(Triple(1, 101, true)), CareSensNativeShadow.writes.toList())

        driver.free()
        driver = newDriver()
        peer = Peer(bonded = true)
        peer.connect()
        peer.verify("SYNTHETIC123")
        peer.software()
        peer.subscribe("c4dec61c")
        peer.expectWrite(CareSensAirProtocol.registerApp(false))
        peer.notify("c4dec61c", CareSensAirProtocol.registerApp(false))
        peer.authenticate()
        peer.expectWrite(wireBytes("c40101000000"))
        peer.notify("c4de9b74", wireBytes("c4010200"))
        peer.expectWrite(wireBytes("c501"))
        peer.reading(2)
        peer.reading(3)
        peer.expectEvent("close")
        assertEquals(
            listOf(Triple(1, 101, true), Triple(1, 101, false), Triple(2, 102, false), Triple(3, 103, true)),
            CareSensNativeShadow.writes.toList(),
        )
    }

    @Test
    fun restartDeliversOnlyTheNewestPendingFreshReadingOnce() {
        peer = Peer(bonded = true)
        peer.register(unused = true)
        peer.startSensor()
        peer.notify("c4de9b74", wireBytes("c4010300"))
        peer.expectWrite(wireBytes("c501"))
        peer.reading(1, timestamp = System.currentTimeMillis() / 1_000 - 900)
        peer.reading(2)
        drain()
        assertEquals(listOf(Triple(1, 101, false)), CareSensNativeShadow.writes.toList())
        assertTrue(CareSensLiveShadow.deliveries.isEmpty())

        restartAtCursor(2)
        assertEquals(listOf(1_020L), CareSensLiveShadow.deliveries.toList())
        assertEquals(Triple(2, 102, true), CareSensNativeShadow.writes.last())

        restartAtCursor(2)
        assertEquals(listOf(1_020L), CareSensLiveShadow.deliveries.toList())
        assertEquals(Triple(2, 102, false), CareSensNativeShadow.writes.last())
    }

    @Test
    fun anExpiredPendingReadingRestoresAsHistory() {
        peer = Peer(bonded = true)
        peer.register(unused = true)
        peer.startSensor()
        peer.notify("c4de9b74", wireBytes("c4010200"))
        peer.expectWrite(wireBytes("c501"))
        peer.reading(1, timestamp = System.currentTimeMillis() / 1_000 - 900)
        drain()
        assertTrue(CareSensNativeShadow.writes.isEmpty())

        restartAtCursor(1)
        assertEquals(listOf(Triple(1, 101, false)), CareSensNativeShadow.writes.toList())
        assertTrue(CareSensLiveShadow.deliveries.isEmpty())
    }

    private fun restartAtCursor(cursor: Int) {
        driver.free()
        driver = newDriver()
        peer = Peer(bonded = true)
        peer.register(unused = false)
        peer.authenticate()
        peer.expectWrite(CareSensAirProtocol.requestRecords(cursor.toLong()))
    }

    @Test
    fun aSuffixMatchWithTheWrongSerialCannotBindOrPair() {
        peer.connect()
        assertTrue(driver.matchDeviceName("CSAir C123", ADDRESS))
        peer.verify("DIFFERSXC123")
        peer.expectEvent("close")
        assertNull(CareSensNativeShadow.address)
        assertNull(driver.mActiveBluetoothDevice)
        assertFalse(driver.matchDeviceName("CSAir C123", ADDRESS))
        assertEquals(false, driver.pairingRequest())
    }

    @Test
    fun excludesAllIncompatibleCandidatesWhileDiscoveringAnotherAddress() {
        CareSensNativeShadow.address = ADDRESS
        peer = Peer(missing = "c4de9ee4")
        peer.connect(expected = "close")
        assertNull(CareSensNativeShadow.address)
        assertNull(driver.mActiveDeviceAddress)
        assertFalse(driver.matchDeviceName("CSAir C123", ADDRESS))
        driver.foundtime = 1L // A scan matched the cached address before the rejection.
        driver.setDevice(peer.device)
        assertNull(driver.mActiveBluetoothDevice)
        assertEquals(0L, driver.foundtime)

        val second = "AA:BB:CC:DD:EE:81"
        peer = Peer(address = second, service = "c4dea000")
        peer.connect(expected = "close")
        assertFalse(driver.matchDeviceName("CSAir C123", second))
        assertFalse(driver.matchDeviceName("CSAir C123", ADDRESS))

        val good = "AA:BB:CC:DD:EE:82"
        assertTrue(driver.matchDeviceName("CSAir C123", good))
        peer = Peer(address = good)
        peer.connect()
        peer.verify("SYNTHETIC123")
        drain()
        assertEquals(good, CareSensNativeShadow.address)
    }

    @Test
    fun modernSensorWithoutRegistrationIsRejectedButDiscoveryFailureCanRetry() {
        peer = Peer(missing = "c4dec61c")
        peer.connect(expected = "close", discoveryStatus = BluetoothGatt.GATT_FAILURE)
        assertTrue(driver.matchDeviceName("CSAir C123", ADDRESS))

        peer = Peer(missing = "c4dec61c")
        peer.connect()
        peer.verify("SYNTHETIC123")
        peer.software()
        peer.expectEvent("close")
        assertFalse(driver.matchDeviceName("CSAir C123", ADDRESS))
        assertNull(CareSensNativeShadow.address)
    }

    private fun newDriver() = CareSensAirGattCallback("2912SYNTHETIC123", 1L, directory, {
        var processed = 0
        CareSensAirEngine { sequence, time, _, _ ->
            CareSensAirOutput(sequence, time, 100.0 + ++processed, 0.0, 0, 1)
        }
    }, worker)

    private fun drain() = worker.submit {}.get(5, TimeUnit.SECONDS)

    private inner class Peer(
        bonded: Boolean = false,
        address: String = ADDRESS,
        missing: String? = null,
        service: String = "c4de9a20",
    ) {
        val device = ShadowBluetoothDevice.newInstance(address)
        private val gatt = ShadowBluetoothGatt.newInstance(device)
        private val radio = Shadow.extract<CareSensGattShadow>(gatt)
        private val characteristics = listOf("c4de8544", "c4de89ae", "c4dec61c", "c4de9b74", "c4de9ee4")
            .filterNot { it == missing }.associateWith { name ->
                BluetoothGattCharacteristic(uuid(name), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).also {
                    it.addDescriptor(BluetoothGattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), 0))
                }
            }

        init {
            radio.peer = device
            val primary = BluetoothGattService(uuid(service), BluetoothGattService.SERVICE_TYPE_PRIMARY)
            characteristics.values.forEach { primary.addCharacteristic(it) }
            radio.discovered = listOf(primary)
            Shadows.shadowOf(device).setBondState(if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE)
        }

        fun connect(expected: String = "read:c4de8544", discoveryStatus: Int = BluetoothGatt.GATT_SUCCESS) {
            driver.setDevice(device)
            SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }.set(driver, gatt)
            driver.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
            expectEvent("mtu")
            driver.onMtuChanged(gatt, 512, 0)
            expectEvent("discover")
            driver.onServicesDiscovered(gatt, discoveryStatus)
            expectEvent(expected)
        }

        fun register(unused: Boolean) {
            connect()
            verify("SYNTHETIC123")
            software()
            subscribe("c4dec61c")
            expectWrite(CareSensAirProtocol.registerApp(unused))
            notify("c4dec61c", CareSensAirProtocol.registerApp(false))
        }

        fun startSensor() {
            authenticate()
            expectWrite(wireBytes("c00200000000"))
            val start = wireBytes("c002") + ByteArray(12)
            start.littleEndian().putFloat(2, 0.10067f).putFloat(6, 1.49594f).putInt(10, 1800)
            notify("c4de9ee4", start)
            expectWrite(wireBytes("c201"))
            factory()
            expectWrite(wireBytes("c40100000000"))
        }

        fun verify(serial: String) = read("c4de8544", serial)

        fun software() {
            expectEvent("read:c4de89ae")
            read("c4de89ae", "1.5")
        }

        fun authenticate() {
            subscribe("c4de9b74")
            subscribe("c4de9ee4")
            expectWrite(checkNotNull(CareSensAirProtocol.authentication(CareSensAirVersion.V1_5, "SYNTHETIC123")))
            val app = wireBytes("c001") + ByteArray(30)
            app.littleEndian().putInt(2, (System.currentTimeMillis() / 1_000).toInt()).putShort(11, 30_000)
            app[31] = 1
            notify("c4de9ee4", app)
        }

        fun subscribe(channel: String) {
            expectEvent("subscribe:$channel")
            val descriptor = characteristics.getValue(channel).descriptors.single()
            driver.onDescriptorWrite(gatt, descriptor, 0)
        }

        fun factory() {
            val data = ByteArray(434)
            data.littleEndian().putFloat(5, 3.5f).putShort(55, 300)
                .putFloat(63, 500f).putFloat(67, 40f).put(71, 3)
            var crc = 0xFFFF
            for (byte in data) {
                crc = crc xor ((byte.toInt() and 0xFF) shl 8)
                repeat(8) { crc = ((crc shl 1) xor if ((crc and 0x8000) == 0) 0 else 0x1021) and 0xFFFF }
            }
            notify("c4de9ee4", wireBytes("c201") + data.copyOf(72))
            notify("c4de9ee4", wireBytes("c202") + data.copyOfRange(72, 229))
            notify("c4de9ee4", wireBytes("c202") + data.copyOfRange(229, data.size))
            notify("c4de9ee4", wireBytes("c203") + byteArrayOf((crc ushr 8).toByte(), crc.toByte()))
        }

        fun reading(sequence: Int, timestamp: Long = System.currentTimeMillis() / 1_000) {
            val raw = ByteArray(84)
            raw.littleEndian().put(0, 0xC5.toByte()).put(1, 1)
                .putInt(12, sequence).putInt(16, timestamp.toInt())
                .putShort(22, 3_700)
            notify("c4de9b74", raw)
        }

        fun notify(channel: String, bytes: ByteArray) =
            driver.onCharacteristicChanged(gatt, characteristics.getValue(channel), bytes)

        fun expectWrite(bytes: ByteArray) {
            assertArrayEquals(bytes, radio.writes.poll(5, TimeUnit.SECONDS))
        }

        fun expectEvent(name: String) {
            assertEquals(name, radio.events.poll(5, TimeUnit.SECONDS))
        }

        private fun read(channel: String, value: String) =
            driver.onCharacteristicRead(gatt, characteristics.getValue(channel), value.toByteArray(Charsets.US_ASCII), 0)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:80"
        val PIN = "123456".toByteArray(Charsets.US_ASCII)
        fun uuid(prefix: String): UUID = UUID.fromString("$prefix-5a9d-11e9-8647-d663bd873d93")
    }
}

class CareSensTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(SuperGattCallback::class)
class CareSensLiveShadow {
    @Implementation
    fun handleGlucoseResult(response: Long, timestampMillis: Long) {
        deliveries.add(response)
    }

    companion object {
        val deliveries = LinkedBlockingQueue<Long>()
    }
}

@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class CareSensGattShadow {
    lateinit var peer: BluetoothDevice
    lateinit var discovered: List<BluetoothGattService>
    val writes = LinkedBlockingQueue<ByteArray>()
    val events = LinkedBlockingQueue<String>()

    @Implementation fun getDevice(): BluetoothDevice = peer
    @Implementation fun getServices(): List<BluetoothGattService> = discovered
    @Implementation fun requestMtu(mtu: Int): Boolean = events.add("mtu")
    @Implementation fun discoverServices(): Boolean = events.add("discover")
    @Implementation fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean =
        events.add("read:${characteristic.uuid.toString().take(8)}")
    @Implementation fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean =
        events.add("subscribe:${descriptor.characteristic.uuid.toString().take(8)}")
    @Implementation fun setCharacteristicNotification(ch: BluetoothGattCharacteristic, enabled: Boolean): Boolean = true
    @Implementation fun writeCharacteristic(ch: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Int {
        writes.add(bytes.copyOf())
        return BluetoothStatusCodes.SUCCESS
    }
    @Implementation fun disconnect() {}
    @Implementation fun close() { events.add("close") }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class CareSensNativeShadow {
    companion object {
        @Volatile var address: String? = null
        @Volatile var start = 0L
        val writes = LinkedBlockingQueue<Triple<Int, Int, Boolean>>()

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun getDeviceAddress(pointer: Long, scanned: Boolean): String? = address
        @JvmStatic @Implementation fun setDeviceAddress(pointer: Long, value: String?) { address = value }
        @JvmStatic @Implementation fun airGetPin(pointer: Long): ByteArray = "123456".toByteArray()
        @JvmStatic @Implementation fun airManagedSetStart(pointer: Long, seconds: Long): Boolean {
            start = seconds
            return true
        }
        @JvmStatic @Implementation
        fun airManagedPublish(pointer: Long, sequence: Int, time: Long, glucose: Int, rate: Float, live: Boolean): Long {
            writes.add(Triple(sequence, glucose, live))
            return if (live) glucose * 10L else 1L
        }
    }
}
