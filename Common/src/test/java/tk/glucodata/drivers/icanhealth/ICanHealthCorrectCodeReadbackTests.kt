package tk.glucodata.drivers.icanhealth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Handler
import java.time.Duration
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
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
import org.robolectric.shadows.ShadowSystemClock
import tk.glucodata.Applic
import tk.glucodata.SuperGattCallback

/** Real F2/F6 callbacks and GATT queue, with only the radio/native storage substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanRebindApplication::class,
    shadows = [ICanRebindNatives::class, ICanCorrectCodeGatt::class])
class ICanHealthCorrectCodeReadbackTests {
    private val serial = "ABC0000005900"
    private lateinit var manager: ICanHealthBleManager
    private lateinit var gatt: BluetoothGatt
    private lateinit var radio: ICanCorrectCodeGatt
    private lateinit var characteristic: BluetoothGattCharacteristic
    private lateinit var handler: Handler

    @Before fun setUp() {
        Applic.app = RuntimeEnvironment.getApplication() as Applic
        manager = ICanHealthBleManager(serial, 0L)
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        radio = Shadow.extract(gatt)
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, gatt)
        val service = BluetoothGattService(ICanHealthConstants.CGM_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        characteristic = BluetoothGattCharacteristic(ICanHealthConstants.CGM_SPECIFIC_OPS,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE, 0)
        service.addCharacteristic(characteristic)
        radio.service = service
        set("cgmService", service)
        set("charSpecificOps", characteristic)
        set("isAuthenticated", true)
        set("postAuthSetupStarted", true)
        set("startCommandIssuedThisConnection", true)
        set("phase", ICanHealthBleManager.Phase.STREAMING)
        set("lastSensorInfoMarker", 0xffff)
        set("onboardingDeviceSn", serial)
        set("lastAuthChallenge", byteArrayOf(1, 2, 3, 4))
        handler = get("handler") as Handler
        shadowOf(handler.looper).pause()
    }

    @After fun tearDown() { manager.destroy() }

    @Test fun onlyTheFinalAckRequestsF6AndItsReplyUpdatesTheUiSnapshot() {
        armChain(index = 0)
        repeat(2) {
            notify(0x1c, 0xf2, 1)
            shadowOf(handler.looper).idleFor(Duration.ofMillis(250))
            assertEquals(0, readbackCount())
            manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
            shadowOf(handler.looper).idle()
        }
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        assertEquals(1, readbackCount())
        assertEquals("F6 0xFFFF", manager.getSensorDetailTelemetry())
        // Duplicate final acknowledgements must not request another readback.
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        assertEquals(1, readbackCount())
        notify(0x1c, 0xf6, 1, 0x4e, 0x02)
        assertEquals("F6 0x024E (590), x13 0.590",
            manager.getManagedUiSnapshot(serial)!!.sensorDetailTelemetry)
        assertFalse(get("correctCodeChainActive") as Boolean)
    }

    @Test fun anUnchangedF6ReplyDoesNotStartAnotherWriteChain() {
        val table = (1..25).joinToString(prefix = "{", postfix = "}") { "\"x$it\":1.0" }
        Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit()
            .putString(ICanHealthConstants.PREF_CGM_CODE_PREFIX + serial, table).commit()
        armChain()
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        notify(0x1c, 0xf6, 1, 0xff, 0xff)
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        shadowOf(handler.looper).idle()
        assertFalse(get("correctCodeChainActive") as Boolean)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(25))
        assertEquals("F6 0xFFFF", manager.getSensorDetailTelemetry())
        assertFalse(get("correctCodeChainActive") as Boolean)
        assertEquals(1, radio.writes.size)
        assertEquals(1, readbackCount())
    }

    @Test fun aRejectedChunkDoesNotRequestF6() {
        armChain()
        notify(0x1c, 0xf2, 0)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
        assertFalse(get("correctCodeChainActive") as Boolean)
    }

    @Test fun readbackWaitsForTheFinalGattWriteToFinish() {
        armChain(completeWrite = false)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        shadowOf(handler.looper).idle()
        assertEquals(1, readbackCount())
    }

    @Test fun aFinalAckBeforeAFailedGattCompletionDoesNotRequestF6() {
        armChain(index = 1)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(250))
        // The real queue has dispatched the last F2, but Android has not completed it.
        assertEquals(1, radio.writes.size)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_FAILURE)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
        assertFalse(get("correctCodeChainActive") as Boolean)
    }

    @Test fun aFailedFinalWriteBeforeItsAckDoesNotRequestF6() {
        armChain(completeWrite = false)
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_FAILURE)
        // Deliver the ack before the handler has processed the posted failure cleanup.
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
        assertFalse(get("correctCodeChainActive") as Boolean)
    }

    @Test fun anEarlyFinalAckStillTimesOutWithoutTheGattWriteCallback() {
        armChain(completeWrite = false)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(25))
        assertEquals(0, readbackCount())
        assertFalse(get("correctCodeChainActive") as Boolean)
    }

    @Test fun aTimedOutEarlierWriteCannotCompleteTheFinalChunkWithItsLateCallback() {
        armChain(index = 1, completeWrite = false)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(250))
        // The final chunk is queued behind the previous F2's outstanding GATT callback.
        assertTrue(radio.writes.isEmpty())
        (get("gattOpTimeoutRunnable") as Runnable).run()
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
        assertTrue("No queued chunk may be dispatched on the timed-out GATT", radio.writes.isEmpty())
        assertNull(SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt")
            .apply { isAccessible = true }.get(manager))
        assertNull(get("gattWriteInFlight"))
        assertTrue(radio.closed)
        assertFalse(get("correctCodeChainActive") as Boolean)
        assertEquals(ICanHealthBleManager.Phase.IDLE, manager.phase)
        assertTrue((get("scheduledReconnectAtMs") as Long) > System.currentTimeMillis())
    }

    @Test fun aLateFailureFromTheTimedOutGattCannotAbortAReplacementWrite() {
        armChain(index = 1, completeWrite = false)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(250))
        (get("gattOpTimeoutRunnable") as Runnable).run()
        val retiredGatt = gatt
        replaceTransport()
        armChain(completeWrite = false)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        manager.onCharacteristicWrite(retiredGatt, characteristic, BluetoothGatt.GATT_FAILURE)
        shadowOf(handler.looper).idle()
        assertTrue(get("correctCodeChainActive") as Boolean)
        assertEquals(0, readbackCount())
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        shadowOf(handler.looper).idle()
        assertEquals(1, readbackCount())
    }

    @Test fun anObsoleteTimeoutCannotRetireTheNextOperation() {
        armChain(index = 1, completeWrite = false)
        val obsoleteTimeout = get("gattOpTimeoutRunnable") as Runnable
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        shadowOf(handler.looper).idle()
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(250))
        obsoleteTimeout.run()
        assertFalse(radio.closed)
        assertTrue(get("correctCodeChainActive") as Boolean)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        shadowOf(handler.looper).idle()
        assertEquals(1, readbackCount())
    }

    @Test fun aTimedOutPausedConnectionDoesNotScheduleReconnect() {
        armChain(completeWrite = false)
        set("uiPaused", true)
        (get("gattOpTimeoutRunnable") as Runnable).run()
        assertTrue(radio.closed)
        assertEquals(0L, get("scheduledReconnectAtMs"))
        assertFalse(get("correctCodeChainActive") as Boolean)
    }

    @Test fun repeatedTimeoutsBackOffAcrossConnectionResetsAndRemainCapped() {
        val expectedDelays = listOf(8_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
        for ((index, expected) in expectedDelays.withIndex()) {
            if (index > 0) replaceTransport()
            armChain(completeWrite = false)
            timeoutAndAssertRetryDelay(expected)
        }
    }

    @Test fun anIsolatedTimeoutAfterAQuietIntervalUsesTheQuickRetryAgain() {
        repeat(3) {
            if (it > 0) replaceTransport()
            armChain(completeWrite = false)
            (get("gattOpTimeoutRunnable") as Runnable).run()
        }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        replaceTransport()
        armChain(completeWrite = false)
        timeoutAndAssertRetryDelay(8_000L)
    }

    @Test fun pausingBeforeThePostedAckDoesNotRequestF6() {
        armChain()
        notify(0x1c, 0xf2, 1)
        set("uiPaused", true)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
    }

    @Test fun aFailedChunkWriteDoesNotRequestF6() {
        armChain(index = 0)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(250))
        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_FAILURE)
        shadowOf(handler.looper).idle()
        assertFalse(get("correctCodeChainActive") as Boolean)
        assertEquals(0, readbackCount())
    }

    @Test fun disconnectBeforeThePostedAckCannotReadFromAReplacementGatt() {
        armChain()
        notify(0x1c, 0xf2, 1)
        manager.close()
        val replacement = Shadow.newInstanceOf(BluetoothGatt::class.java)
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, replacement)
        shadowOf(handler.looper).idle()
        assertEquals(0, readbackCount())
        val replacementRadio: ICanCorrectCodeGatt = Shadow.extract(replacement)
        assertTrue(replacementRadio.writes.isEmpty())
        // A late response from the retired connection must not change telemetry either.
        notify(0x1c, 0xf6, 1, 0x4e, 0x02)
        assertEquals("", manager.getSensorDetailTelemetry())
    }

    @Test fun aFailedReadbackWriteLeavesTheLastReportedMarkerAndDoesNotRetry() {
        armChain()
        radio.writeStarts = false
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(25))
        assertEquals(1, readbackCount())
        assertFalse(get("correctCodeChainActive") as Boolean)
        assertEquals("F6 0xFFFF", manager.getSensorDetailTelemetry())
    }

    @Test fun aRepairChainAlsoReadsBackTheSensorsActualMarker() {
        armChain()
        set("correctCodeRepairChain", true)
        set("lastSensorInfoMarker", 580)
        notify(0x1c, 0xf2, 1)
        shadowOf(handler.looper).idle()
        assertEquals(1, readbackCount())
        notify(0x1c, 0xf6, 1, 0x4e, 0x02)
        assertEquals("F6 0x024E (590), x13 0.590", manager.getSensorDetailTelemetry())
    }

    private fun armChain(index: Int = 2, completeWrite: Boolean = true) {
        val chunks = List(3) { byteArrayOf(0xf2.toByte(), 0x10) + ByteArray(16) { it.toByte() } }
        set("correctCodeChainActive", true)
        set("correctCodeChunks", chunks)
        set("correctCodeNextChunk", index)
        set("correctCodePendingChunk", chunks[index])
        set("correctCodeFactorySerial", serial)
        set("correctCodeChallenge", byteArrayOf(1, 2, 3, 4))
        // Dispatch through the real queue so callbacks refer to the actual write operation.
        manager.javaClass.getDeclaredMethod("sendCorrectCodeChunk", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(manager, index)
        if (completeWrite) {
            manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
            shadowOf(handler.looper).idle()
        }
        radio.writes.clear()
    }

    private fun replaceTransport() {
        val service = radio.service!!
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        radio = Shadow.extract(gatt)
        radio.service = service
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, gatt)
        set("cgmService", service)
        set("charSpecificOps", characteristic)
        set("isAuthenticated", true)
        set("postAuthSetupStarted", true)
        set("startCommandIssuedThisConnection", true)
        set("phase", ICanHealthBleManager.Phase.STREAMING)
        set("lastSensorInfoMarker", 0xffff)
        set("lastAuthChallenge", byteArrayOf(1, 2, 3, 4))
    }

    private fun timeoutAndAssertRetryDelay(expected: Long) {
        val before = System.currentTimeMillis()
        (get("gattOpTimeoutRunnable") as Runnable).run()
        val after = System.currentTimeMillis()
        val deadline = get("scheduledReconnectAtMs") as Long
        assertTrue("Expected a $expected ms retry, got a deadline in ${deadline - after} ms",
            deadline in (before + expected)..(after + expected))
    }

    @Suppress("DEPRECATION")
    private fun notify(vararg bytes: Int) {
        characteristic.value = bytes.map { it.toByte() }.toByteArray()
        manager.onCharacteristicChanged(gatt, characteristic)
    }

    private fun readbackCount() = radio.writes.count {
        it.contentEquals(ICanHealthConstants.CMD_REQUEST_SENSOR_INFO)
    }

    private fun get(name: String): Any? = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(manager)

    private fun set(name: String, value: Any?) = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(manager, value)
}

@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class ICanCorrectCodeGatt {
    var service: BluetoothGattService? = null
    val writes = mutableListOf<ByteArray>()
    var writeStarts = true
    var closed = false
    @Implementation fun getService(uuid: UUID): BluetoothGattService? = service?.takeIf { it.uuid == uuid }
    @Implementation fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        writes.add(characteristic.value.copyOf())
        return writeStarts
    }
    @Implementation fun disconnect() {}
    @Implementation fun close() { closed = true }
}
