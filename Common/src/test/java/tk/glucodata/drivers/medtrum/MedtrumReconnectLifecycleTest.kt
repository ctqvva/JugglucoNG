package tk.glucodata.drivers.medtrum

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothGatt
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback

/**
 * After a disconnect the patch has to come back on its own. These cover the ways the first
 * version of the driver could not: a close() that killed its HandlerThread, a lost link that
 * forgot the device (forcing an unfiltered scan Android suppresses with the screen off), and a
 * stale GATT callback tearing down the live link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MedtrumTestApplication::class, shadows = [MedtrumTestNatives::class])
class MedtrumReconnectLifecycleTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private lateinit var context: Context
    private lateinit var manager: MedtrumBleManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        MedtrumPersistence.clear(context)
        manager = MedtrumBleManager("2329895446", 0L)
    }

    @After
    fun tearDown() {
        manager.terminateManagedSensor(wipeData = true)
    }

    @Test
    fun aTransientCloseKeepsTheDriverThread() {
        // SensorBluetooth.resetdataptr() calls close() and then connects the same driver again.
        manager.close()

        assertTrue(manager.isHandlerThreadRunning())
    }

    @Test
    fun pauseThenReconnectLeavesAWorkingThread() {
        manager.softDisconnect()
        manager.softReconnect()

        assertTrue(manager.isHandlerThreadRunning())
    }

    @Test
    fun aStoppedDriverQuitsItsThreadAndAReconnectStartsANewOne() {
        manager.terminateManagedSensor(wipeData = false)
        assertFalse(manager.isHandlerThreadRunning())

        manager.softReconnect()

        assertTrue(manager.isHandlerThreadRunning())
    }

    @Test
    fun aLostLinkKeepsTheDeviceForADirectReconnect() {
        val gatt = newGatt()
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        manager.onConnectionStateChange(gatt, 8 /* supervision timeout */, BluetoothProfile.STATE_DISCONNECTED)

        assertNotNull(manager.mActiveBluetoothDevice)
        assertEquals(address, manager.mActiveBluetoothDevice?.address)
        assertEquals(address, MedtrumPersistence.getLastBtAddress(context))
    }

    @Test
    fun repeatedFailedAttemptsFallBackToAScanAndThenBack() {
        manager.mActiveDeviceAddress = address
        val device = adapterDevice()
        manager.mActiveBluetoothDevice = device

        repeat(MedtrumReconnectPolicy.DEFAULT_MAX_DIRECT_FAILURES - 1) {
            failAttempt()
            assertNotNull("direct attempt ${it + 1} keeps the device", manager.mActiveBluetoothDevice)
        }
        failAttempt()
        assertNull("scan fallback clears the device", manager.mActiveBluetoothDevice)

        failAttempt()
        assertEquals(address, manager.mActiveBluetoothDevice?.address)
    }

    @Test
    fun aStaleDisconnectDoesNotTearDownTheLiveLink() {
        val live = newGatt()
        manager.onConnectionStateChange(live, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        val stale = newGatt()

        manager.onConnectionStateChange(stale, 133, BluetoothProfile.STATE_DISCONNECTED)

        assertSame(live, gattOf(manager))
        assertEquals(MedtrumBleManager.Phase.DISCOVERING, manager.phase)
    }

    @Test
    fun bluetoothOffResetsTheSessionSoTheReconnectIsNotSkipped() {
        val gatt = newGatt()
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        // What SensorBluetooth does on STATE_OFF for a managed driver; no DISCONNECTED follows.
        manager.closeGattTransport()
        manager.onBluetoothAdapterUnavailable()

        assertEquals(MedtrumBleManager.Phase.IDLE, manager.phase)
        assertEquals(address, manager.mActiveBluetoothDevice?.address)
        assertTrue(manager.isHandlerThreadRunning())
    }

    @Test
    fun replacingThePatchForgetsTheOldAddressAndDialect() {
        MedtrumPersistence.setSerialNumber(context, 0x8ADF6216L)
        MedtrumPersistence.setLastBtAddress(context, address)
        MedtrumPersistence.setLegacyAuth(context, true)

        MedtrumRegistry.addSensor(context, 0x12345678L)

        assertEquals("", MedtrumPersistence.getLastBtAddress(context))
        assertFalse(MedtrumPersistence.isLegacyAuth(context))
    }

    @Test
    fun reAddingTheSamePatchKeepsItsAddress() {
        MedtrumPersistence.setSerialNumber(context, 0x8ADF6216L)
        MedtrumPersistence.setLastBtAddress(context, address)

        MedtrumRegistry.addSensor(context, 0x8ADF6216L)

        assertEquals(address, MedtrumPersistence.getLastBtAddress(context))
    }

    @Test
    fun aLinkThatConnectedButDidNotFinishSetupKeepsADeadline() {
        val gatt = newGatt()
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        // Discovery, subscription and AUTH are all still ahead: without a pending deadline a stuck
        // step would leave the phase busy and every later connectDevice() a duplicate.
        assertEquals(MedtrumBleManager.Phase.DISCOVERING, manager.phase)
        assertTrue(manager.isAttemptDeadlineArmed())
    }

    @Test
    fun aDeviceWithoutTheMedtrumServiceIsRejectedAndNotRetried() {
        val gatt = newGatt()
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        // The scanned or remembered address answered but carries no CGMS service: a wrong device.
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(MedtrumBleManager.Phase.ERROR, manager.phase)
        assertFalse("a rejected device must not be retried automatically", manager.connectDevice(0))
        assertEquals("", MedtrumPersistence.getLastBtAddress(context))
    }

    @Test
    fun aCgmReadingBeforeAuthIsNotPublishedOrStreaming() {
        val gatt = newGatt()
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        // The patch can push CGM_DATA as soon as notifications are enabled, before AUTH.
        invokeOnCgmData(hex("0f09005f12000d005d064606390641060000f230"))

        assertEquals(MedtrumBleManager.Phase.DISCOVERING, manager.phase)
    }

    @Test
    fun aPendingFingerstickDoesNotSurviveADisconnect() {
        // With no readState yet, calibrateSensor parks the value until one arrives.
        assertTrue(manager.calibrateSensor(120))
        assertEquals(120, pendingCalibration())

        manager.softDisconnect()

        assertNull(pendingCalibration())
    }

    @Test
    fun liveReadingIsAStockLaneSoSoftwareCalibrationStillApplies() {
        val mgdl = MedtrumBleManager.liveLane(144f, unit = 0)
        assertFalse(mgdl.isResolved)
        assertEquals(144f, mgdl.stockAuto, 0f)

        val mmol = MedtrumBleManager.liveLane(144f, unit = 1)
        assertFalse(mmol.isResolved)
        assertEquals(144f / 18.0182f, mmol.stockAuto, 0.001f)
    }

    @Test
    fun addingAPatchRetiresTheDriverOfARejectedSerial() {
        val stale = MedtrumBleManager("305419896", 0L)
        SensorBluetooth.gattcallbacks.add(stale)
        try {
            MedtrumRegistry.addSensor(context, 0x8ADF6216L)

            assertFalse(SensorBluetooth.gattcallbacks.contains(stale))
            assertFalse(stale.isHandlerThreadRunning())
        } finally {
            SensorBluetooth.gattcallbacks.remove(stale)
        }
    }

    @Test
    fun findDriverReturnsTheRunningDriverForItsSerial() {
        SensorBluetooth.gattcallbacks.add(manager)
        try {
            assertSame(manager, MedtrumRegistry.findDriver("2329895446"))
            assertNull(MedtrumRegistry.findDriver("305419896"))
        } finally {
            SensorBluetooth.gattcallbacks.remove(manager)
        }
    }

    private fun failAttempt() {
        val gatt = newGatt()
        setGatt(manager, gatt)
        manager.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun invokeOnCgmData(data: ByteArray) {
        MedtrumBleManager::class.java
            .getDeclaredMethod("onCgmData", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(manager, data)
    }

    private fun pendingCalibration(): Int? =
        MedtrumBleManager::class.java.getDeclaredField("pendingCalibrationMgdl")
            .apply { isAccessible = true }
            .get(manager) as Int?

    private fun adapterDevice() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(address)

    private fun newGatt(): BluetoothGatt = ShadowBluetoothGatt.newInstance(adapterDevice())

    private fun gattField() = SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }

    private fun gattOf(manager: MedtrumBleManager) = gattField().get(manager)

    private fun setGatt(manager: MedtrumBleManager, gatt: BluetoothGatt?) = gattField().set(manager, gatt)
}

class MedtrumTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class MedtrumTestNatives {
    companion object {
        @JvmStatic @Implementation
        fun __staticInitializer__() {}
    }
}
