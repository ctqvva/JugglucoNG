package tk.glucodata.drivers.ottai

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import java.util.UUID
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
import org.robolectric.shadow.api.Shadow
import tk.glucodata.Applic
import tk.glucodata.SuperGattCallback

/** Replays the Nov 5 width flip through decryption, persistence and the live poll callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class,
    shadows = [OttaiRemovalNatives::class, OttaiRecordWidthGatt::class])
class OttaiRecordWidthTests {
    private val sensor = "70D07EDCB416"
    private val key = "00".repeat(16)
    private lateinit var manager: OttaiBleManager
    private lateinit var gatt: BluetoothGatt
    private val context: Applic get() = RuntimeEnvironment.getApplication() as Applic

    // Exact decrypted page from the supplied trace: [5335,5352), 17 records and 7 pad bytes.
    private val lowCurrentPage = hex(
        "00000000d7140000" +
            "ce0344e0898a13660cc60344e0798b13740cae0344e0698c137e0c870344e0598d13890c" +
            "870344e0498e13920c870344e0398f139b0c6f0344e0299013a30c770344e0199113ac0c" +
            "7f0344e0099213b50c770344e0f99213bd0c770344e0e99313c80c6f0344e0d99413cd0c" +
            "7f0344e0c99513d60c870344e0b99613d50c9f0344e0a99713c50cbe0344e0999813bd0c" +
            "dd0344e0899913b50c" + "00".repeat(7),
    )
    private val liveNotify = hex("000000002458ffff1f3c34e115ba47c50b" + "00".repeat(7))

    @Before
    fun setUp() {
        Applic.app = context
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        OttaiRemovalNatives.reset()
        OttaiRecordWidthGatt.reads = 0
        manager = OttaiBleManager(sensor, 0L)
        // Leave the formula empty: these tests exercise framing without publishing glucose.
        set("materials", OttaiRegistry.DeviceMaterials("", "", "", 0L, "E1.1.4(V1.7.S2530.1)", 0))
        set("sessionKeyHex", key)
        set("phase", OttaiBleManager::class.java.declaredClasses.single { it.simpleName == "Phase" }
            .enumConstants!!.single { it.toString() == "STREAMING" })
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }.set(manager, gatt)
    }

    @After
    fun tearDown() {
        SuperGattCallback::class.java.getDeclaredField("stop").apply { isAccessible = true }.setBoolean(manager, true)
        manager.close()
    }

    @Test
    fun lowCurrentHistoryDoesNotProveEightByteRecords() {
        assertEquals(168, lowCurrentPage.size)
        assertEquals(0 to 4, OttaiParser.recordSizeEvidence(lowCurrentPage))
        assertNull(OttaiParser.contentRecordSize(lowCurrentPage))
        for (version in listOf("", "E1.1.4(V1.7.S2530.1)")) {
            val records = OttaiParser.frameRecords(lowCurrentPage, version, learned = 9).map(OttaiParser::parseRecord)
            assertEquals(17, records.size)
            assertEquals((5335..5351).toList(), records.map { it.dataNo })
            assertEquals(listOf(974, 966, 942), records.take(3).map { it.rawCurrent })
            assertEquals(989, records.last().rawCurrent)
        }
    }

    @Test
    fun liveReadThenLowCurrentHistoryKeepsTheNextLiveSampleAndPersistedWidth() {
        // A decisive nine-record read repairs an old persisted eight-byte choice.
        set("learnedRecordSize", 8)
        OttaiRegistry.saveRecordSize(context, sensor, 8)
        val read = hex("000000001858ffff" + "1f3c34e115ba47c50b".repeat(9) + "00".repeat(7))
        deliver(read, live = true, source = "read")
        assertEquals(9, get("learnedRecordSize"))
        deliver(lowCurrentPage, live = false, source = "notify")
        assertEquals(9, get("learnedRecordSize"))
        assertEquals(9, OttaiRegistry.loadRecordSize(context, sensor))
        notify(liveNotify)
        val records = OttaiParser.frameRecords(liveNotify, "", get("learnedRecordSize") as Int)
        assertEquals(1, records.size)
        assertEquals(22564, OttaiParser.parseRecord(records.single()).dataNo)
        assertTrue(get("lastLiveNotifyAtMs") as Long > 0L)
    }

    @Test
    fun misframedHistoryRestoresUnknownWidthInMemoryAndPreferences() {
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(deviceVersion = "E1.2.3"))
        // No content evidence, but the version seeds nine before the impossible frame is judged.
        val badPage = hex("000000000a000000" + "000000000000001027".repeat(8))
        assertNull(OttaiParser.contentRecordSize(badPage))
        val readings = OttaiParser.frameRecords(badPage, "E1.2.3").map { OttaiParser.toReading(it, "", emptyList(), 0L) }
        assertTrue(OttaiOutputFilter.isMisframedFrame(readings))
        deliver(badPage, live = false, source = "notify")
        assertEquals(0, get("learnedRecordSize"))
        assertEquals(0, OttaiRegistry.loadRecordSize(context, sensor))
    }

    @Test
    fun unreadableNotificationsDoNotSuppressTheFallbackPoll() {
        set("learnedRecordSize", 8)
        set("lastLiveNotifyAtMs", 1L)
        notify(liveNotify) // Wrong width: records.last() is padding, with runtime/current zero.
        assertEquals(1L, get("lastLiveNotifyAtMs"))
        (get("livePollRunnable") as Runnable).run()
        assertEquals(1, OttaiRecordWidthGatt.reads)
    }

    @Test
    fun earlyPaddingAndEmptyOrUndecryptableNotificationsDoNotSuppressPolling() {
        set("learnedRecordSize", 8)
        val early = liveNotify.copyOf().also { it[4] = 60; it[5] = 0 }
        val readings = OttaiParser.frameRecords(early, "", 8).map { OttaiParser.toReading(it, "", emptyList(), 0L) }
        assertTrue(readings.last().valid) // Runtime zero is still within the two-hour sanity skew.
        notify(early)
        notify(ByteArray(8))
        notifyCipher(ByteArray(15))
        assertEquals(0L, get("lastLiveNotifyAtMs"))
        (get("livePollRunnable") as Runnable).run()
        assertEquals(1, OttaiRecordWidthGatt.reads)
    }

    @Test
    fun decodedLowCurrentNotifySuppressesPollingButAReadDoesNot() {
        set("learnedRecordSize", 9)
        val low = hex("000000002458ffffce0344e0898a13660c" + "00".repeat(7))
        deliver(low, live = true, source = "read")
        assertEquals(0L, get("lastLiveNotifyAtMs"))
        notify(low) // The later raw-current output gate must not control notification health.
        assertTrue(get("lastLiveNotifyAtMs") as Long > 0L)
        (get("livePollRunnable") as Runnable).run()
        assertEquals(0, OttaiRecordWidthGatt.reads)
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun deliver(payload: ByteArray, live: Boolean, source: String) {
        OttaiBleManager::class.java.getDeclaredMethod("handleGlucosePayload", ByteArray::class.java,
            Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
            .invoke(manager, OttaiCrypto.encryptPayload(payload, key)!!, live, source)
    }
    private fun notify(payload: ByteArray) = notifyCipher(OttaiCrypto.encryptPayload(payload, key)!!)
    private fun notifyCipher(cipher: ByteArray) {
        val characteristic = BluetoothGattCharacteristic(OttaiConstants.CHAR_GLUCOSE_LIVE,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply { value = cipher }
        manager.onCharacteristicChanged(gatt, characteristic)
    }
    private fun set(name: String, value: Any) = OttaiBleManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(manager, value)
    private fun get(name: String): Any? = OttaiBleManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(manager)
}

@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class OttaiRecordWidthGatt {
    @Implementation
    fun getService(uuid: UUID): BluetoothGattService? = if (uuid == OttaiConstants.SERVICE_CGM) {
        BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(OttaiConstants.CHAR_GLUCOSE_LIVE,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        }
    } else null

    @Implementation
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        reads++
        return true
    }

    companion object { var reads = 0 }
}
