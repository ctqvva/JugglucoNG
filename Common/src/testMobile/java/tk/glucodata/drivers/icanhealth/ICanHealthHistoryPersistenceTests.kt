package tk.glucodata.drivers.icanhealth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import tk.glucodata.Applic
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.HistoryReplaceOutcome
import tk.glucodata.HistoryRepositoryBridge
import tk.glucodata.HistorySyncBridge
import tk.glucodata.HistorySyncBridgeAccess
import tk.glucodata.SuperGattCallback

/** RACP completion, reconnect and retry through the real manager, with Room refusing writes on demand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanRebindApplication::class,
    shadows = [ICanRebindNatives::class, ICanHistoryGatt::class])
class ICanHealthHistoryPersistenceTests {
    private val sensor = "7BF00099C663AABB"
    private val runningSequence = 480
    private val startMs = System.currentTimeMillis() - 60L * 60_000L
    private lateinit var context: Applic
    private lateinit var manager: ICanHealthBleManager
    private lateinit var gatt: BluetoothGatt
    private lateinit var racp: BluetoothGattCharacteristic
    private lateinit var handler: Handler
    private var oldHistoryBridge: Any? = null
    private var oldSyncBridge: Any? = null

    private var roomFails = true
    private var writes = 0
    private var replacements = 0
    private var nativeMerges = 0
    private var roomReadable = true
    private var deletedByUser = false
    private val rows = sortedMapOf<Long, Float>()
    private val writtenTo = ArrayList<Any?>()
    private var duringWrite: () -> Unit = {}

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        prefs().edit().clear()
            .putInt(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + sensor,
                ICanHealthConstants.HISTORY_TIMEZONE_REPAIR_GENERATION)
            .commit()
        oldHistoryBridge = bridgeField(HistoryRepositoryAccess::class.java).get(HistoryRepositoryAccess)
        oldSyncBridge = bridgeField(HistorySyncBridgeAccess::class.java).get(HistorySyncBridgeAccess)
        HistoryRepositoryAccess.register(proxy(HistoryRepositoryBridge::class.java) { name, args ->
            when (name) {
                "storeHistoryBatchBlocking" -> {
                    writes++
                    writtenTo += args[0]
                    duringWrite()
                    if (roomFails) false else {
                        val timestamps = args[1] as LongArray
                        val values = args[2] as FloatArray
                        timestamps.indices.forEach { rows[timestamps[it]] = values[it] }
                        true
                    }
                }
                "replaceHistoryIntervalsBlocking" -> {
                    replacements++
                    if (roomFails) {
                        HistoryReplaceOutcome.FAILED
                    } else {
                        val timestamps = args[1] as LongArray
                        val values = args[2] as FloatArray
                        @Suppress("UNCHECKED_CAST")
                        val intervals = args[4] as List<LongRange>
                        intervals.forEach { rows.subMap(it.first, it.last + 1L).clear() }
                        timestamps.indices.forEach { rows[timestamps[it]] = values[it] }
                        HistoryReplaceOutcome.REPLACED
                    }
                }
                "getHistoryForNotificationForSensor" -> emptyList<tk.glucodata.GlucosePoint>()
                "getLatestTimestampForSensorBlocking" -> rows.keys.lastOrNull() ?: 0L
                "getHistoryTimestampsForSensorBlocking" -> if (roomReadable) {
                    rows.keys.filter { it in (args[1] as Long)..(args[2] as Long) }.toLongArray()
                } else null
                "isMinuteDeletedByUserBlocking" -> deletedByUser
                else -> null
            }
        })
        HistorySyncBridgeAccess.register(proxy(HistorySyncBridge::class.java) { name, _ ->
            if (name == "mergeFullSyncForSensor") nativeMerges++
            null
        })
        manager = ICanHealthBleManager(sensor, 0L)
        handler = get("handler") as Handler
        shadowOf(handler.looper).pause()
        racp = BluetoothGattCharacteristic(ICanHealthConstants.RACP, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        connect()
    }

    @After fun tearDown() {
        manager.destroy()
        bridgeField(HistoryRepositoryAccess::class.java).set(HistoryRepositoryAccess, oldHistoryBridge)
        bridgeField(HistorySyncBridgeAccess::class.java).set(HistorySyncBridgeAccess, oldSyncBridge)
    }

    @Test fun aFailedRoomWriteMovesNeitherTheCounterNorTheCoveredEdge() {
        transfer(3, 6, 9)
        assertEquals(1, writes)
        assertEquals(0, get("glucoseHistoryImportedRecordCount"))
        assertEquals(-1, get("persistedCoveredSequence"))
        assertEquals(-1, prefs().getInt(ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX + sensor, -1))
        assertTrue(unsaved())
        assertEquals("the native merge is skipped for this sensor and cannot stand in", 0, nativeMerges)
        assertFalse(get("suppressAutomaticHistoryBackfill") as Boolean)
        assertEquals(ICanHealthBleManager.Phase.STREAMING, manager.phase)
    }

    @Test fun aStoredBatchMovesBoth() {
        roomFails = false
        transfer(3, 6, 9)
        assertEquals(3, get("glucoseHistoryImportedRecordCount"))
        assertEquals(9, prefs().getInt(ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX + sensor, -1))
        assertEquals(listOf(3, 6, 9).map(::timeOf), rows.keys.toList())
        assertFalse(unsaved())
    }

    @Test fun theBatchIsRetriedUntilRoomTakesIt() {
        transfer(3, 6, 9)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(31))
        assertEquals(2, writes)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(31))
        assertEquals("repeated failures back off", 2, writes)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(31))
        assertEquals(3, writes)
        assertTrue(unsaved())
        assertTrue(rows.isEmpty())

        roomFails = false
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(121))
        assertEquals(listOf(3, 6, 9).map(::timeOf), rows.keys.toList())
        assertEquals(9, get("persistedCoveredSequence"))
        assertFalse(unsaved())
        shadowOf(handler.looper).idleFor(Duration.ofHours(1))
        assertEquals("nothing left to retry", 4, writes)
    }

    @Test fun theBatchSurvivesAReconnectAndIsWrittenBeforeTheNextRequest() {
        transfer(3, 6, 9)
        disconnect()
        assertTrue(unsaved())
        // A live reading arrived meanwhile: from here on the sensor is never asked for seq 3..9 again.
        set("lastHandledLiveSequence", 15)
        roomFails = false
        connect()
        call("requestHistoryBackfill")
        assertEquals(listOf(3, 6, 9).map(::timeOf), rows.keys.toList())
        assertFalse(unsaved())
        assertEquals(18, get("pendingGlucoseHistoryStartSequence"))
    }

    @Test fun readingTheRangeAgainAfterAReconnectDoesNotDuplicateRows() {
        transfer(3, 6, 9)
        disconnect()
        connect()
        call("requestHistoryBackfill")
        assertEquals(3, get("pendingGlucoseHistoryStartSequence"))
        roomFails = false
        receive(90_000L, 3, 6, 9, 12)
        complete()
        assertEquals(listOf(timeOf(3), timeOf(6), timeOf(9), timeOf(12) + 90_000L), rows.keys.toList())
        assertEquals(4, get("glucoseHistoryImportedRecordCount"))
    }

    @Test fun aTransferCutByADisconnectIsNotStoredAndIsRequestedAgain() {
        roomFails = false
        begin()
        receive(0L, 3, 6)
        disconnect()
        assertFalse(unsaved())
        assertEquals(0, writes)
        connect()
        call("requestHistoryBackfill")
        assertEquals(3, get("pendingGlucoseHistoryStartSequence"))
        receive(0L, 3, 6, 9)
        complete()
        assertEquals(listOf(3, 6, 9).map(::timeOf), rows.keys.toList())
    }

    @Test fun aSoftDisconnectKeepsTheBatchButRemovingTheSensorDropsIt() {
        transfer(3, 6, 9)
        manager.softDisconnect()
        assertTrue(unsaved())
        manager.terminateManagedSensor(false)
        assertFalse(unsaved())
        roomFails = false
        shadowOf(handler.looper).idleFor(Duration.ofHours(1))
        assertTrue(rows.isEmpty())
    }

    @Test fun aRefusedTransactionalRepairIsRetriedWithoutTouchingNewerLiveRows() {
        prefs().edit().remove(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + sensor).commit()
        set("historyTzRepairLoadedSensorId", null)
        val sequences = (1..30).map { it * 3 }.toIntArray()
        val ghostMs = timeOf(4)
        rows[ghostMs] = 300f
        transfer(*sequences)

        assertEquals(1, replacements)
        assertEquals(mapOf(ghostMs to 300f), rows)
        assertEquals(0, get("glucoseHistoryImportedRecordCount"))
        assertTrue(get("historyTzRepairPending") as Boolean)
        assertTrue(unsaved())

        val liveMs = timeOf(100)
        rows[liveMs] = 120f
        roomFails = false
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(31))
        assertEquals(2, replacements)
        assertEquals(sequences.map(::timeOf) + liveMs, rows.keys.toList())
        assertEquals(30, get("glucoseHistoryImportedRecordCount"))
        assertFalse(unsaved())
        assertFalse(get("historyTzRepairPending") as Boolean)
    }

    @Test fun aMissingFrozenEdgeIsRequestedAndStoredWithItsOriginalTime() {
        roomFails = false
        val timestamp = seedFrozenEdge()
        assertEquals(runningSequence, call("resolveAutomaticGlucoseHistoryStartSequence"))
        begin()
        deliverMeasurement(runningSequence)
        assertEquals(0L, get("lastGlucoseReceiptRealtimeMs"))
        assertEquals(0L, get("latestDriverReadingTimestampMs"))
        complete()

        assertEquals(listOf(timestamp), rows.keys.toList())
        assertNull(call("resolveAutomaticGlucoseHistoryStartSequence"))
    }

    @Test fun aStoredDeletedOrUnreadableFrozenEdgeDoesNotReopenCoverage() {
        val timestamp = seedFrozenEdge()
        rows[timestamp - 100] = 100f
        assertNull(call("resolveAutomaticGlucoseHistoryStartSequence"))
        rows.clear()
        deletedByUser = true
        assertNull(call("resolveAutomaticGlucoseHistoryStartSequence"))
        deletedByUser = false
        roomReadable = false
        assertNull(call("resolveAutomaticGlucoseHistoryStartSequence"))
    }

    @Test fun aRunningConnectionWithNoFirstReadingTimesOut() {
        set("phase", ICanHealthBleManager.Phase.POST_AUTH_SETUP)
        call("maybeHandleQueueDrained")
        val radio = Shadow.extract<ICanHistoryGatt>(gatt)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(209))
        assertEquals(0, radio.disconnects)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(1))
        assertEquals(1, radio.disconnects)
    }

    @Test fun repeatedFrozenReadingsNeitherRefreshNorRestampTheStream() {
        val timestamp = seedFrozenEdge()
        rows[timestamp] = 100f
        set("phase", ICanHealthBleManager.Phase.POST_AUTH_SETUP)
        call("maybeHandleQueueDrained")
        val radio = Shadow.extract<ICanHistoryGatt>(gatt)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(180))
        deliverMeasurement(runningSequence)
        deliverMeasurement(runningSequence)
        assertEquals(0, radio.disconnects)
        assertEquals(0L, get("lastGlucoseReceiptRealtimeMs"))
        assertEquals(timestamp, get("persistedCoveredTimestampMs"))
        assertEquals(0L, get("latestDriverReadingTimestampMs"))
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(30))
        assertEquals(1, radio.disconnects)
    }

    @Test fun anAdvancingLiveReadingStartsANewWatchdogWindow() {
        set("currentSequenceNumber", runningSequence)
        set("sessionStartEpochMs", System.currentTimeMillis() - runningSequence * 60_000L)
        set("phase", ICanHealthBleManager.Phase.POST_AUTH_SETUP)
        call("maybeHandleQueueDrained")
        val radio = Shadow.extract<ICanHistoryGatt>(gatt)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(180))
        deliverMeasurement(runningSequence + 3)
        assertTrue((get("lastGlucoseReceiptRealtimeMs") as Long) > 0L)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(209))
        assertEquals(0, radio.disconnects)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(1))
        assertEquals(1, radio.disconnects)
    }

    @Test fun distinctCoveredHistoryKeepsTheTransferAliveButRepeatsDoNot() {
        val timestamp = seedFrozenEdge()
        rows[timestamp] = 100f
        set("phase", ICanHealthBleManager.Phase.POST_AUTH_SETUP)
        call("maybeHandleQueueDrained")
        begin()
        val radio = Shadow.extract<ICanHistoryGatt>(gatt)
        val first = runningSequence - 9
        val second = runningSequence - 12

        shadowOf(handler.looper).idleFor(Duration.ofSeconds(180))
        deliverMeasurement(first)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(30))
        assertEquals(0, radio.disconnects)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(150))
        deliverMeasurement(second)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(30))
        assertEquals(0, radio.disconnects)
        assertEquals(0, (get("historyImport") as ICanHealthHistoryImport).incomingCount)
        assertEquals(0L, get("lastGlucoseReceiptRealtimeMs"))

        shadowOf(handler.looper).idleFor(Duration.ofSeconds(110))
        deliverMeasurement(first)
        deliverMeasurement(second)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(69))
        assertEquals(0, radio.disconnects)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(1))
        assertEquals(1, radio.disconnects)
    }

    @Test fun aRetryRunningWhileAnotherSensorConnectsFinishesForItsOwnSensorFirst() {
        val other = "7BF00099C663CCDD"
        prefs().edit().putInt(ICanHealthConstants.PREF_HISTORY_TZ_REPAIR_PREFIX + other,
            ICanHealthConstants.HISTORY_TIMEZONE_REPAIR_GENERATION).commit()
        transfer(3, 6, 9)
        roomFails = false
        lateinit var connecting: Thread
        duringWrite = {
            connecting = startBlocked {
                manager.javaClass.getDeclaredMethod("handleResolvedSerial", String::class.java)
                    .apply { isAccessible = true }.invoke(manager, other)
            }
            assertEquals(sensor, manager.SerialNumber)
        }
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(31))
        connecting.join(10_000L)

        assertEquals(other, manager.SerialNumber)
        assertEquals(listOf<Any?>(sensor, sensor), writtenTo)
        assertEquals(9, prefs().getInt(ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX + sensor, -1))
        assertEquals(-1, prefs().getInt(ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX + other, -1))
        assertFalse(unsaved())
        shadowOf(handler.looper).idleFor(Duration.ofHours(1))
        assertEquals("nothing is owed to the new sensor", 2, writes)
    }

    @Test fun aBatchStillWaitingWhenAnotherSensorConnectsIsNeverWrittenToIt() {
        transfer(3, 6, 9)
        manager.javaClass.getDeclaredMethod("handleResolvedSerial", String::class.java)
            .apply { isAccessible = true }.invoke(manager, "7BF00099C663CCDD")
        roomFails = false
        shadowOf(handler.looper).idleFor(Duration.ofHours(1))
        assertTrue(rows.isEmpty())
        assertEquals(1, writes)
    }

    @Test fun removingTheSensorWaitsForTheWriteAlreadyRunning() {
        transfer(3, 6, 9)
        roomFails = false
        lateinit var removal: Thread
        duringWrite = { removal = startBlocked { manager.terminateManagedSensor(true) } }
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(31))
        removal.join(10_000L)
        assertFalse(removal.isAlive)
        assertFalse(unsaved())
        shadowOf(handler.looper).idleFor(Duration.ofHours(1))
        assertEquals(2, writes)
    }

    /** Runs [action] on another thread and returns once it waits for the write lock. */
    private fun startBlocked(action: () -> Unit): Thread = Thread(action).apply {
        start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1L)
        assertEquals(Thread.State.WAITING, state)
    }

    private fun seedFrozenEdge(): Long {
        val timestamp = ((System.currentTimeMillis() - 12 * 60_000L) / 60_000L) * 60_000L + 30_000L
        prefs().edit()
            .putInt(ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX + sensor, runningSequence)
            .putLong(ICanHealthConstants.PREF_HISTORY_EDGE_TIMESTAMP_PREFIX + sensor, timestamp)
            .commit()
        manager.javaClass.getDeclaredMethod("loadPersistedCoveredEdge", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(manager, true)
        set("currentSequenceNumber", runningSequence)
        set("sessionStartEpochMs", timestamp - runningSequence * 60_000L)
        set("currentSequenceObservedAtMs", System.currentTimeMillis())
        return timestamp
    }

    @Suppress("DEPRECATION")
    private fun deliverMeasurement(sequence: Int) {
        val challenge = byteArrayOf(0x21, 0x33, 0x45, 0x57)
        val iv = ByteArray(16).apply {
            this[1] = challenge[0]
            this[6] = challenge[1]
            this[10] = challenge[2]
            this[15] = challenge[3]
        }
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII)!!
        val low = sequence.toByte()
        val high = (sequence ushr 8).toByte()
        val plaintext = byteArrayOf(0x0D, 0x80.toByte(), 0xE4.toByte(), 0x01, low, high, 0x01, 0, 0, 0, 0, 0, 0)
        set("aesKey", key)
        set("lastAuthChallenge", challenge)
        val characteristic = BluetoothGattCharacteristic(
            ICanHealthConstants.CGM_MEASUREMENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0,
        )
        characteristic.value = byteArrayOf(low, high, ICanHealthConstants.MEASUREMENT_TYPE_BYTE) +
            ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, iv)!!
        manager.onCharacteristicChanged(gatt, characteristic)
    }

    private fun transfer(vararg sequences: Int) {
        begin()
        receive(0L, *sequences)
        complete()
    }

    private fun begin() {
        set("historyBackfillRequested", true)
        val phase = Class.forName(manager.javaClass.name + "\$HistoryBackfillPhase").enumConstants!!
            .first { (it as Enum<*>).name == "GLUCOSE" }
        set("historyBackfillPhase", phase)
    }

    private fun receive(shiftMs: Long, vararg sequences: Int) = sequences.forEach {
        manager.javaClass.getDeclaredMethod("recordHistoryBackfillSample", Int::class.java, Long::class.java,
            Float::class.java, Float::class.java, Float::class.java).apply { isAccessible = true }
            .invoke(manager, it, timeOf(it) + shiftMs, 100f, Float.NaN, Float.NaN)
    }

    @Suppress("DEPRECATION")
    private fun complete() {
        racp.value = byteArrayOf(0x06, 0x00, 0xF1.toByte(), ICanHealthConstants.RACP_RESULT_SUCCESS.toByte())
        manager.onCharacteristicChanged(gatt, racp)
    }

    private fun connect() {
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        Shadow.extract<ICanHistoryGatt>(gatt).service =
            BluetoothGattService(ICanHealthConstants.CGM_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, gatt)
        set("charRacp", racp)
        set("isAuthenticated", true)
        set("shouldRequestAuthenticatedHistoryBackfill", true)
        set("launcherState", ICanHealthConstants.LAUNCHER_STATE_RUNNING)
        set("currentSequenceNumber", 60)
        set("sessionStartEpochMs", startMs)
    }

    private fun disconnect() {
        set("uiPaused", true) // no radio: only the state the disconnect leaves behind matters here
        manager.onConnectionStateChange(gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        set("uiPaused", false)
    }

    private fun timeOf(sequence: Int) = startMs + sequence * 60_000L

    private fun unsaved() = (get("historyImport") as ICanHealthHistoryImport).hasUnsaved()

    private fun prefs() = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)

    private fun bridgeField(type: Class<*>) = type.getDeclaredField("bridge").apply { isAccessible = true }

    private fun <T> proxy(type: Class<T>, answer: (String, Array<Any?>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            answer(method.name, args ?: emptyArray())
        },
    )!!

    private fun call(name: String) = manager.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(manager)

    private fun get(name: String): Any? = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(manager)

    private fun set(name: String, value: Any?) = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(manager, value)
}

@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class ICanHistoryGatt {
    var service: BluetoothGattService? = null
    var disconnects = 0
    @Implementation fun getDevice(): BluetoothDevice? = null
    @Implementation fun getService(uuid: java.util.UUID): BluetoothGattService? = service
    @Implementation fun disconnect() { disconnects++ }
    @Implementation fun close() {}
}
