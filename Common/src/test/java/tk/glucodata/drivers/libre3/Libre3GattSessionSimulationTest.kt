package tk.glucodata.drivers.libre3

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.security.Key
import java.security.KeyStoreSpi
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.cert.Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.AlgorithmParameterSpec
import java.time.Duration
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.HealthConnect
import tk.glucodata.HealthConnectAccess
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.HistoryRepositoryBridge
import tk.glucodata.MainActivity
import tk.glucodata.SuperGattCallback

/**
 * The real driver over a scripted GATT link to a simulated sensor: connect, discovery, the
 * security CCCDs, full authorization, the data CCCD chain, live readings, the clinical and
 * history requests, a disconnect, and the cached-authorization reconnect. Every byte the driver
 * writes is decoded by the simulated sensor, which answers the way a Libre 3 does (and as the
 * captures in [Libre3CapturedVectorsTest] show). Synthetic keys only; no Android BLE stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Libre3ReviewApplication::class,
    shadows = [Libre3ReviewNatives::class, Libre3PublicationShadow::class, Libre3SimGattShadow::class],
)
class Libre3GattSessionSimulationTest {
    private val sensorId = "0RRC989AQ"
    private val address = "CC:22:DF:B8:F9:58"
    private val pin = Libre3Bytes.parseHex("3225ec72")!!
    private val life = 14_563
    private lateinit var context: Context
    private lateinit var manager: Libre3BleManager
    private lateinit var sim: SimulatedLibre3Sensor
    private val roomWrites = Collections.synchronizedList(mutableListOf<Long>())
    private var oldBridge: Any? = null
    private var oldHealth: Any? = null

    @Before fun setup() {
        Security.insertProviderAt(FakeAndroidKeyStore.Provider(), 1)
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(Libre3Persistence.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        Libre3ReviewNatives.reset()
        oldHealth = HealthConnectAccess.get()
        HealthConnectAccess.register(object : HealthConnect {
            override fun start(activity: MainActivity) {}
            override fun stop() {}
            override fun writeAll(sensorPtr: Long, sensorName: String) {}
        })
        oldBridge = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(HistoryRepositoryAccess)
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(
            HistoryRepositoryBridge::class.java.classLoader, arrayOf(HistoryRepositoryBridge::class.java),
        ) { _, method, args -> when (method.name) {
            "getHistoryTimestampsForSensorBlocking" -> roomWrites.toLongArray()
            "storeHistoryBatchWithSourceBlocking" -> true.also { roomWrites.addAll((args!![1] as LongArray).toList()) }
            "getHistoryForNotificationForSensor" -> emptyList<GlucosePoint>()
            else -> null
        } } as HistoryRepositoryBridge)

        // App identity: scalar 0x4444 and a certificate carrying its public key.
        val identity = Libre3AppIdentity.create("app", ByteArray(30) + byteArrayOf(0x44, 0x44), Libre3Bytes.parseHex(CERT_HEX)!!)
        val json = Libre3AppIdentity.toJson(listOf(identity)).toByteArray()
        Libre3Persistence.installIdentities(context, "sim", Libre3IdentityProvisioning.sha256Hex(json), json, "test")

        wear(life, 20_160)
    }

    /** A sensor that has been worn [lifeCount] minutes of its rated [wearMinutes]. */
    private fun wear(lifeCount: Int, wearMinutes: Int) {
        if (::manager.isInitialized) runCatching { manager.setPause(true); manager.close() }
        val identity = Libre3AppIdentity.create("app", ByteArray(30) + byteArrayOf(0x44, 0x44), Libre3Bytes.parseHex(CERT_HEX)!!)
        // Life count L is sent as its minute ends, at activation + (L + 1) min: now.
        val start = System.currentTimeMillis() - (lifeCount + 1) * 60_000L
        Libre3Persistence.saveSensor(context, Libre3SensorRecord(sensorId, address, pin, start, 60, wearMinutes, 0x6f0d8378L, "1.4.2.30", 1))
        sim = SimulatedLibre3Sensor(identity.staticPublicKey, pin, lifeCount)
        Libre3SimGattShadow.sim = sim
        manager = Libre3BleManager(sensorId)
        manager.patchSigningKeys = listOf(Libre3P256.encode(sim.signing.public as ECPublicKey))
        sim.driver = manager
    }

    @After fun cleanup() {
        runCatching { manager.setPause(true); manager.close() }
        Libre3SimGattShadow.sim = null
        HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HistoryRepositoryAccess, oldBridge)
        HealthConnectAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HealthConnectAccess, oldHealth)
        Security.removeProvider("AndroidKeyStore")
        FakeAndroidKeyStore.keys.clear()
        Applic.app = null
    }

    private fun field(name: String) = Libre3BleManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private val looper get() = (field("liveHandler").get(manager) as Handler).looper

    /** Runs the driver thread and the simulated sensor's replies until both are quiet. */
    private fun settle() {
        repeat(10_000) {
            shadowOf(looper).idle()
            val next = sim.events.pollFirst() ?: return
            next()
        }
        fail("the simulated link never went quiet")
    }

    private fun advance(ms: Long) {
        shadowOf(looper).idleFor(Duration.ofMillis(ms))
        settle()
    }

    private fun connect(): BluetoothGatt {
        val gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        sim.gatt = gatt
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }.set(manager, gatt)
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        settle()
        advance(300) // discovery waits 250 ms after the link comes up
        return gatt
    }

    private fun lifeTime(lifeCount: Int): Long =
        Libre3Persistence.loadSensor(context, sensorId)!!.startTimeMs + (lifeCount + 1) * 60_000L

    @Test fun firstPairStreamsBackfillsAndReconnectsWithCachedAuthorization() {
        val gatt = connect()

        // Full authorization, in the order the reference drivers and real sensors use.
        assertEquals(listOf(0x01, 0x02, 0x03, 0x09, 0x0D, 0x0E, 0x11, 0x08), sim.commands)
        assertArrayEquals("the app certificate reached the sensor", Libre3Bytes.parseHex(CERT_HEX), sim.appCertificate)
        assertEquals(Libre3BleManager.Phase.STREAMING, manager.phase)
        assertEquals(98f, field("lastReadingMgdl").get(manager) as Float)
        val cached = Libre3Persistence.loadCachedAuth(context, sensorId)!!
        assertArrayEquals(sim.kAuth, cached.first)
        assertEquals("sim/app", cached.second)
        // The data CCCDs, in the legacy driver's order, every one acknowledged.
        assertEquals(Libre3Gatt.DATA_NOTIFY_ORDER, sim.cccds.drop(Libre3Gatt.SECURITY_NOTIFY_ORDER.size))
        // Patch status asked for the missed minutes and the committed history.
        assertEquals(
            listOf(Libre3ControlCommand.clinicalFrom(life - 360), Libre3ControlCommand.historicalFrom(5)).map(Libre3Bytes::hex),
            sim.controls.map(Libre3Bytes::hex),
        )

        advance(2_500) // backfill flush
        for (minute in listOf(life - 2, life - 1, life)) {
            assertTrue("minute $minute stored", roomWrites.contains(lifeTime(minute)))
        }
        // History sits on the sensor's absolute five-minute grid: the page, the clinical
        // records' committed value and the live frame's committed value all land.
        for (minute in (14_515..14_545 step 5)) assertTrue("history $minute stored", roomWrites.contains(lifeTime(minute)))

        // Link loss, then a reconnect that must reuse the cached kAuth: no certificate exchange.
        manager.onConnectionStateChange(gatt, 19, BluetoothProfile.STATE_DISCONNECTED)
        settle()
        sim.reconnect(nextLife = life + 1, mgdl = 104)
        connect()
        assertEquals(listOf(0x11, 0x08), sim.commands)
        assertEquals(Libre3BleManager.Phase.STREAMING, manager.phase)
        assertEquals(104f, field("lastReadingMgdl").get(manager) as Float)
    }

    /** Nothing on the phone ends a Plus at day 14, nor either model at its rated end: the sensor does. */
    @Test fun readingsCrossDayFourteenAndDayFifteenUntilTheSensorStops() {
        val day = 24 * 60
        for ((wearMinutes, model) in listOf(14 * day to "Libre 3", 15 * day to "Libre 3 Plus")) {
            for (first in listOf(14 * day - 1, 14 * day, 14 * day + 1, 14 * day + 12 * 60 + 1, 15 * day - 1, 15 * day, 15 * day + 1)) {
                val case = "$model at minute $first"
                Libre3ReviewNatives.reset()
                roomWrites.clear()
                wear(first, wearMinutes)
                val gatt = connect()
                advance(2_500)
                assertEquals(case, Libre3BleManager.Phase.STREAMING, manager.phase)
                assertTrue("$case stored", roomWrites.contains(lifeTime(first)))
                assertTrue("$case native", Libre3ReviewNatives.nativeSeconds.contains(lifeTime(first) / 1000L))
                assertEquals(case, wearMinutes / day, Libre3ReviewNatives.wearDays)

                // The sensor reports the end of its rated wear and keeps sending.
                manager.onConnectionStateChange(gatt, 19, BluetoothProfile.STATE_DISCONNECTED)
                settle()
                sim.reconnect(nextLife = first + 1, mgdl = 104)
                sim.errorData = 5
                connect()
                advance(2_500)
                assertEquals(case, Libre3BleManager.Phase.STREAMING, manager.phase)
                assertEquals(case, 104f, field("lastReadingMgdl").get(manager) as Float)
                assertTrue("$case + 1 stored", roomWrites.contains(lifeTime(first + 1)))
                assertFalse(case, Libre3Persistence.isEnded(context, sensorId))

                val snapshot = manager.getManagedUiSnapshot(sensorId)!!
                assertEquals(case, model, snapshot.vendorModel)
                assertEquals(case, "FreeStyle $model", snapshot.displayName)
                assertEquals(case, snapshot.startTimeMs + wearMinutes * 60_000L, snapshot.officialEndMs)
                assertEquals(case, snapshot.officialEndMs, snapshot.expectedEndMs)
            }
        }
    }

    @Test fun onlyAShutDownSensorStopsReconnecting() {
        wear(15 * 24 * 60, 15 * 24 * 60)
        sim.errorData = 6
        connect()
        assertTrue(Libre3Persistence.isEnded(context, sensorId))
        assertFalse(manager.connectDevice(0))
    }

    @Test fun credentialsArrivingLaterWakeTheWaitingDriver() {
        tk.glucodata.SensorBluetooth.gattcallbacks.add(manager)
        try {
            // Removed credentials: the handshake stops and waits instead of retrying.
            Libre3Persistence.clearIdentities(context)
            settle()
            connect()
            assertTrue(manager.isWaitingForCredentials())
            assertTrue("no security command without credentials", sim.commands.isEmpty())
            awaitCredentialWorker()

            // Settings (download or file import) installs a set: the driver reconnects by itself.
            val identity = Libre3AppIdentity.create("app", ByteArray(30) + byteArrayOf(0x44, 0x44), Libre3Bytes.parseHex(CERT_HEX)!!)
            val json = Libre3AppIdentity.toJson(listOf(identity)).toByteArray()
            Libre3Persistence.installIdentities(context, "sim2", Libre3IdentityProvisioning.sha256Hex(json), json, "test")
            advance(300)
            assertFalse(manager.isWaitingForCredentials())

            connect()
            assertEquals(listOf(0x01, 0x02, 0x03, 0x09, 0x0D, 0x0E, 0x11, 0x08), sim.commands)
            assertEquals(Libre3BleManager.Phase.STREAMING, manager.phase)
        } finally {
            tk.glucodata.SensorBluetooth.gattcallbacks.remove(manager)
        }
    }

    /**
     * From a tester's trace: the chart asks for every point of a sensor no driver owns (the record
     * the removed driver left), each answer built this snapshot, and each snapshot decrypted the
     * cached kAuth through the Keystore. Thousands of those on the main thread were the ANRs.
     */
    @Test fun askingForTheUiSnapshotNeverTouchesTheKeystore() {
        connect()
        tk.glucodata.SensorBluetooth.gattcallbacks.add(manager)
        try {
            FakeAndroidKeyStore.reads.set(0)
            assertTrue(manager.getManagedUiSnapshot(sensorId)!!.isVendorPaired)
            repeat(3_000) {
                tk.glucodata.drivers.ManagedSensorRuntime.integratesUserCalibration(
                    Libre3IdentityAdapter.legacyNativeName(sensorId), false)
            }
            assertEquals(0, FakeAndroidKeyStore.reads.get())
            Libre3Persistence.clearCachedAuth(context, sensorId)
            assertFalse(manager.getManagedUiSnapshot(sensorId)!!.isVendorPaired)
        } finally {
            tk.glucodata.SensorBluetooth.gattcallbacks.remove(manager)
        }
    }

    /**
     * From the same trace: a link the killed process left up answered service discovery with an
     * empty table, and the driver waited for an NFC scan although the next connection was fine.
     */
    @Test fun anIncompleteServiceDiscoveryIsRetried() {
        sim.emptyDiscoveries = 1
        connect()
        assertTrue(sim.commands.isEmpty())
        assertTrue("the driver reconnects by itself", reconnectScheduled())
        assertTrue("the stale service table is dropped", sim.refreshes > 0)

        connect()
        assertEquals(Libre3BleManager.Phase.STREAMING, manager.phase)
    }

    @Test fun repeatedIncompleteDiscoveriesBackOffAndSayWhyButNeverStop() {
        val rejected = context.getString(tk.glucodata.R.string.libre3_status_services_missing)
        sim.emptyDiscoveries = Int.MAX_VALUE
        val delays = (1..14).map { attempt ->
            connect()
            assertTrue("attempt $attempt", reconnectScheduled())
            assertEquals(attempt >= 3, manager.getDetailedBleStatus() == rejected)
            reconnectDelay()
        }
        assertEquals(listOf(3_000L, 3_000L, 3_000L, 6_000L), delays.take(4))
        assertEquals(300_000L, delays.last())

        sim.emptyDiscoveries = 0
        connect()
        assertEquals(Libre3BleManager.Phase.STREAMING, manager.phase)
        assertEquals(3_000L, reconnectDelay())
    }

    private val reconnectRunnable get() = field("reconnectRunnable").get(manager) as Runnable
    private fun reconnectScheduled() = (field("liveHandler").get(manager) as Handler).hasCallbacks(reconnectRunnable)
    private fun reconnectDelay() = Libre3BleManager::class.java.getDeclaredMethod("reconnectDelayMs")
        .apply { isAccessible = true }.invoke(manager) as Long

    /** The background download started by the waiting driver; removed credentials end it at once. */
    private fun awaitCredentialWorker() {
        val busy = Libre3Registry::class.java.getDeclaredField("provisioning").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicBoolean
        val deadline = System.currentTimeMillis() + 5_000L
        while (busy.get()) {
            if (System.currentTimeMillis() > deadline) fail("credential download never finished")
            Thread.sleep(10L)
        }
    }

    companion object {
        /** Certificate whose public key belongs to scalar 0x4444 (also in Libre3FullAuthSimulationTest). */
        val CERT_HEX = "03000102030405060708090a0b0c0d0e0f1000005f92a79201000000000000000004a85b3aad6f3a346d85523141cb434e1caf4c642b2b3cc952cb07a635cb6a1df1bcbdadb66bae8eb25eca92f8b08b67be02cc736150dd7ffcd3f019c875f9ffde" + "0".repeat(128)
    }
}

/** A Libre 3 on the other end of the link. Replies are queued as GATT callbacks for [settle]. */
class SimulatedLibre3Sensor(private val appStaticPub: ByteArray, private val pin: ByteArray, private var life: Int) {
    val events = ConcurrentLinkedDeque<() -> Unit>()
    lateinit var driver: Libre3BleManager
    lateinit var gatt: BluetoothGatt
    val signing = Libre3P256.generateKeyPair()
    private val static = Libre3P256.generateKeyPair()
    private val ephemeral = Libre3P256.generateKeyPair()
    private val r1 = ByteArray(16) { (0xA0 + it).toByte() }
    private val nonce5 = ByteArray(7) { (0xB1 + it).toByte() }
    private val nonce6 = ByteArray(7) { (0xC1 + it).toByte() }
    private val kEnc = ByteArray(16) { (0x10 + it).toByte() }
    private val ivEnc = ByteArray(8) { (0x30 + it).toByte() }
    private val session = Libre3DataPlaneSession(kEnc, ivEnc)
    private var liveMgdl = 98
    var kAuth: ByteArray? = null
    var appCertificate: ByteArray? = null
    val commands = mutableListOf<Int>()
    val cccds = mutableListOf<UUID>()
    val controls = mutableListOf<ByteArray>()
    private val certWrites = mutableListOf<ByteArray>()
    private val challengeWrites = mutableListOf<ByteArray>()
    private var inbound = 1

    /** Discoveries that still find none of the sensor's services. */
    var emptyDiscoveries = 0
    var errorData = 0
    var refreshes = 0
    fun discover() { hidden = emptyDiscoveries > 0; if (hidden) emptyDiscoveries-- }
    private var hidden = false
    fun service(id: UUID): BluetoothGattService? = services[id].takeUnless { hidden }

    val services: Map<UUID, BluetoothGattService> = mapOf(
        Libre3Gatt.DATA_SERVICE to service(Libre3Gatt.DATA_SERVICE, Libre3Gatt.DATA_NOTIFY_ORDER),
        Libre3Gatt.SECURITY_SERVICE to service(Libre3Gatt.SECURITY_SERVICE, Libre3Gatt.SECURITY_NOTIFY_ORDER),
    )

    private fun service(id: UUID, chars: List<UUID>) = BluetoothGattService(id, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        chars.forEach { c ->
            addCharacteristic(BluetoothGattCharacteristic(c,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE).apply {
                addDescriptor(BluetoothGattDescriptor(Libre3Gatt.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
            })
        }
    }

    private fun characteristic(id: UUID) = services.values.firstNotNullOf { it.getCharacteristic(id) }

    fun reconnect(nextLife: Int, mgdl: Int) {
        life = nextLife
        liveMgdl = mgdl
        commands.clear(); cccds.clear(); controls.clear(); certWrites.clear(); challengeWrites.clear()
    }

    private fun notify(id: UUID, value: ByteArray) {
        val ch = characteristic(id)
        events += { driver.onCharacteristicChanged(gatt, ch, value) }
    }

    /** Security messages arrive as `seq || up to 19 bytes`. */
    private fun notifyMessage(id: UUID, message: ByteArray) =
        (message.indices step 19).forEachIndexed { seq, off ->
            notify(id, byteArrayOf(seq.toByte()) + message.copyOfRange(off, minOf(off + 19, message.size)))
        }

    private fun reassemble(frags: List<ByteArray>) = frags.fold(ByteArray(0)) { acc, f -> acc + f.copyOfRange(2, 20) }

    private fun raw32(v: BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }

    private fun certificate(): ByteArray {
        val body = byteArrayOf(0x01) + ByteArray(10) { it.toByte() } + Libre3P256.encode(static.public as ECPublicKey)
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(signing.private); update(body); sign() }
        var i = 2
        val rLen = der[i + 1].toInt(); val r = BigInteger(1, der.copyOfRange(i + 2, i + 2 + rLen)); i += 2 + rLen
        val sLen = der[i + 1].toInt(); val s = BigInteger(1, der.copyOfRange(i + 2, i + 2 + sLen))
        return body + raw32(r) + raw32(s)
    }

    fun onCccd(id: UUID, gattDescriptor: BluetoothGattDescriptor) {
        cccds += id
        events += { driver.onDescriptorWrite(gatt, gattDescriptor, BluetoothGatt.GATT_SUCCESS) }
        // The sensor streams once the last data CCCD is freshly enabled.
        if (id == Libre3Gatt.PATCH_STATUS) {
            sendGlucose(life, liveMgdl)
            val status = Libre3Bytes.parseHex("9d280000e0000704e4381300")!!
            Libre3Bytes.putU16le(status, 2, errorData)
            Libre3Bytes.putU16le(status, 8, life)
            notify(Libre3Gatt.PATCH_STATUS, session.encryptInbound(Libre3Channel.PATCH_STATUS, status, inbound++))
        }
    }

    private fun sendGlucose(lifeCount: Int, mgdl: Int) {
        val p = Libre3Bytes.parseHex("e3386200d6ff0000f023d1386c000b62006c00200a9e03113d03100000")!!
        Libre3Bytes.putU16le(p, 0, lifeCount)
        Libre3Bytes.putU16le(p, 2, mgdl)
        Libre3Bytes.putU16le(p, 10, (lifeCount - 18) / 5 * 5) // committed history, on the 5-minute grid
        Libre3Bytes.putU16le(p, 15, mgdl)
        val frame = session.encryptInbound(Libre3Channel.GLUCOSE_DATA, p, inbound++)
        notify(Libre3Gatt.GLUCOSE_DATA, frame.copyOfRange(0, 15))
        notify(Libre3Gatt.GLUCOSE_DATA, frame.copyOfRange(15, frame.size))
    }

    fun onWrite(id: UUID, value: ByteArray) {
        val ch = characteristic(id)
        events += { driver.onCharacteristicWrite(gatt, ch, BluetoothGatt.GATT_SUCCESS) }
        when (id) {
            Libre3Gatt.SEC_CERT_DATA -> certWrites += value
            Libre3Gatt.SEC_CHALLENGE_DATA -> challengeWrites += value
            Libre3Gatt.SEC_COMMAND_RESPONSE -> onCommand(value[0].toInt() and 0xFF)
            Libre3Gatt.PATCH_CONTROL -> onControl(value)
            else -> error("write to ${id}")
        }
    }

    private fun onCommand(cmd: Int) {
        commands += cmd
        val response = Libre3Gatt.SEC_COMMAND_RESPONSE
        when (cmd) {
            0x01, 0x02, 0x0D -> certWrites.clear()
            0x03 -> {
                appCertificate = reassemble(certWrites).copyOf(162)
                certWrites.clear()
                notify(response, byteArrayOf(0x04))
            }
            0x09 -> {
                notify(response, byteArrayOf(0x0A, 140.toByte()))
                notifyMessage(Libre3Gatt.SEC_CERT_DATA, certificate())
            }
            0x0E -> {
                val phoneEphemeral = reassemble(certWrites).copyOf(65)
                val ze = Libre3P256.ecdh(ephemeral.private as ECPrivateKey, Libre3P256.publicKey(phoneEphemeral))
                val zs = Libre3P256.ecdh(static.private as ECPrivateKey, Libre3P256.publicKey(appStaticPub))
                kAuth = Libre3Auth.deriveAuthKey(ze, zs)
                notify(response, byteArrayOf(0x0F, 65))
                notifyMessage(Libre3Gatt.SEC_CERT_DATA, Libre3P256.encode(ephemeral.public as ECPublicKey))
            }
            0x11 -> {
                challengeWrites.clear()
                notify(response, byteArrayOf(0x08, 23))
                notifyMessage(Libre3Gatt.SEC_CHALLENGE_DATA, r1 + nonce5)
            }
            0x08 -> {
                val key = kAuth ?: error("phase 5 before any kAuth")
                val pt = Libre3AesCcm(key).decrypt(nonce5, reassemble(challengeWrites).copyOf(40), 4)
                check(pt.copyOfRange(0, 16).contentEquals(r1)) { "R1 not echoed" }
                check(pt.copyOfRange(32, 36).contentEquals(pin)) { "BLE PIN mismatch" }
                val r2 = pt.copyOfRange(16, 32)
                notify(response, byteArrayOf(0x08, 67))
                notifyMessage(Libre3Gatt.SEC_CHALLENGE_DATA, Libre3AesCcm(key).encrypt(nonce6, r2 + r1 + kEnc + ivEnc, 4) + nonce6)
            }
            else -> error("unknown security command %02x".format(cmd))
        }
    }

    private fun onControl(frame: ByteArray) {
        val seq = Libre3Bytes.u16le(frame, frame.size - 2)
        val plaintext = Libre3AesCcm(kEnc).decrypt(session.nonce(seq, 0), frame.copyOf(frame.size - 2), 4)
        controls += plaintext
        when (plaintext[1].toInt()) {
            0x01 -> for (minute in listOf(life - 2, life - 1)) { // clinical: the two minutes before now
                val rec = ByteArray(Libre3ClinicalRecord.SIZE)
                Libre3Bytes.putU16le(rec, 0, minute)
                Libre3Bytes.putU16le(rec, 10, 100 + (life - minute))
                Libre3Bytes.putU16le(rec, 12, 100)
                notify(Libre3Gatt.CLINICAL_DATA, session.encryptInbound(Libre3Channel.CLINICAL_DATA, rec, inbound++))
            }
            0x00 -> { // history: one page of six five-minute values
                val page = ByteArray(14)
                Libre3Bytes.putU16le(page, 0, (life - 45) / 5 * 5)
                for (i in 0 until 6) Libre3Bytes.putU16le(page, 2 + i * 2, 110 + i)
                notify(Libre3Gatt.HISTORIC_DATA, session.encryptInbound(Libre3Channel.HISTORIC_DATA, page, inbound++))
            }
        }
        notify(Libre3Gatt.PATCH_CONTROL, session.encryptInbound(Libre3Channel.PATCH_CONTROL, ByteArray(7), inbound++))
    }
}

/** The driver's BluetoothGatt, wired to [Libre3SimGattShadow.sim] instead of a radio. */
@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class Libre3SimGattShadow {
    companion object {
        @Volatile var sim: SimulatedLibre3Sensor? = null
    }

    @RealObject private lateinit var real: BluetoothGatt

    @Implementation fun getService(uuid: UUID): BluetoothGattService? = sim?.service(uuid)
    @Implementation fun discoverServices(): Boolean {
        val s = sim ?: return false
        s.discover()
        s.events += { s.driver.onServicesDiscovered(real, BluetoothGatt.GATT_SUCCESS) }
        return true
    }
    @Implementation fun refresh(): Boolean { sim?.let { it.refreshes++ }; return true }
    @Implementation fun setCharacteristicNotification(c: BluetoothGattCharacteristic, enable: Boolean): Boolean = true
    @Implementation fun writeDescriptor(d: BluetoothGattDescriptor): Boolean {
        sim?.onCccd(d.characteristic.uuid, d) ?: return false
        return true
    }
    @Suppress("DEPRECATION")
    @Implementation fun writeCharacteristic(c: BluetoothGattCharacteristic): Boolean {
        sim?.onWrite(c.uuid, c.value.copyOf()) ?: return false
        return true
    }
    @Implementation fun getDevice(): BluetoothDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("CC:22:DF:B8:F9:58")
    @Implementation fun connect(): Boolean = true
    @Implementation fun disconnect() {}
    @Implementation fun close() {}
    @Implementation fun readRemoteRssi(): Boolean = true
    @Implementation fun requestConnectionPriority(priority: Int): Boolean = true
}

/** Robolectric has no AndroidKeyStore; this stands in for it with ordinary in-memory AES keys. */
object FakeAndroidKeyStore {
    val keys = ConcurrentHashMap<String, Key>()
    val reads = java.util.concurrent.atomic.AtomicInteger()

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias].also { reads.incrementAndGet() }
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    class Generator : KeyGeneratorSpi() {
        private var alias = ""
        override fun engineInit(random: SecureRandom?) {}
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) {}
        override fun engineGenerateKey(): SecretKey =
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES").also { keys[alias] = it }
    }

    class Provider : @Suppress("DEPRECATION") java.security.Provider("AndroidKeyStore", 1.0, "in-memory AndroidKeyStore for tests") {
        init {
            put("KeyStore.AndroidKeyStore", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }
}
