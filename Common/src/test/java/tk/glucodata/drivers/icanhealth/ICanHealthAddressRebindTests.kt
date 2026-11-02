package tk.glucodata.drivers.icanhealth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import java.util.UUID
import java.time.Duration
import org.robolectric.shadows.ShadowSystemClock
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
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback

/** Exercises the real discovery/read callbacks; native storage and the radio are substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanRebindApplication::class,
    shadows = [ICanRebindNatives::class, ICanRebindGatt::class])
class ICanHealthAddressRebindTests {
    private val sensor = "7BF00099C663AABB"
    private val oldAddress = "AA:BB:CC:DD:EE:01"
    private val candidateAddress = "AA:BB:CC:DD:EE:02"
    private lateinit var manager: ICanHealthBleManager
    private lateinit var oldDevice: BluetoothDevice
    private lateinit var gatt: BluetoothGatt
    private lateinit var radio: ICanRebindGatt
    private lateinit var context: Applic

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        ICanHealthRegistry.promoteSensorIdentity(context, sensor, sensor, oldAddress, sensor)
        manager = ICanHealthBleManager(sensor, 0L)
        oldDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(oldAddress)
        manager.setDevice(oldDevice)
        set("serialFromDevice", sensor)
        set("rawSerialFromDevice", sensor)
        set("vendorModelName", "iCan i3")
        set("vendorFirmwareVersion", "1.0")
        set("vendorSoftwareVersion", "1.0")
        set("createdAtMs", 0L)
        assertTrue(manager.matchDeviceName(sensor, candidateAddress))
        manager.setDevice(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(candidateAddress))
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        radio = Shadow.extract(gatt)
        radio.remoteDevice = manager.mActiveBluetoothDevice
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, gatt)
    }

    @After fun tearDown() {
        manager.destroy()
    }

    @Test fun missingCgmServiceRestoresDeviceAddressAndSavedIdentity() {
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        // A late callback from the rejected peripheral must not replace the saved identity.
        deliverSerial("7BF00099C664AABB")
        assertRolledBack()
    }

    @Test fun missingSerialCharacteristicCannotUseCachedIdentity() {
        addCgmSurface(withSerial = false)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        assertTrue(radio.reads.isEmpty())
    }

    @Test fun cachedMetadataStillReadsSerialAndPersistsAcceptedAddressAcrossRestart() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf(ICanHealthConstants.SERIAL_NUMBER), radio.reads)
        assertEquals(oldAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
        deliverSerial(sensor)
        assertEquals(candidateAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
        assertEquals(sensor, manager.SerialNumber)
        assertNull(get("pendingAddressRebind"))
        val restored = ICanHealthRegistry.createRestoredCallback(context, sensor, 0L) as ICanHealthBleManager
        try {
            assertEquals(candidateAddress, restored.mActiveDeviceAddress)
        } finally {
            restored.destroy()
        }
        // A cancelled deadline cannot undo an accepted address.
        (get("rebindTimeoutRunnable") as Runnable).run()
        assertEquals(candidateAddress, manager.mActiveDeviceAddress)
    }

    @Test fun differentSerialDoesNotRewriteIdentityOrPreferenceKeys() {
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val authKey = ICanHealthConstants.PREF_AUTH_USER_ID_PREFIX + sensor
        prefs.edit().putString(authKey, "123456789").commit()
        addCgmSurface(withAuth = true)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial("7BF00099C664AABB")
        assertRolledBack()
        assertEquals("123456789", prefs.getString(authKey, null))
        assertNull(ICanHealthRegistry.findRecord(context, "7BF00099C664AABB"))
    }

    @Test fun failedSerialReadRollsBackBeforeAuthenticationOrSetup() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial(sensor, BluetoothGatt.GATT_FAILURE)
        assertRolledBack()
    }

    @Test fun emptySerialReadRollsBackBeforeAuthenticationOrSetup() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial("")
        assertRolledBack()
    }

    @Test fun serialReadThatCannotStartRollsBack() {
        addCgmSurface()
        radio.readStarts = false
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
    }

    @Test fun serialReadTimeoutRollsBackInsteadOfDrainingIntoSetup() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        (get("gattOpTimeoutRunnable") as Runnable).run()
        assertRolledBack()
    }

    @Test fun failedDiscoveryRestoresOriginalDevice() {
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
        assertRolledBack()
    }

    @Test fun silentCandidateDeadlineRestoresOriginalDevice() {
        (get("rebindTimeoutRunnable") as Runnable).run()
        assertRolledBack()
    }

    @Test fun disconnectBeforeSerialVerificationRestoresOriginalDevice() {
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
        assertRolledBack()
    }

    @Test fun anotherScanCannotReplaceCandidateWhileIdentityReadIsPending() {
        assertFalse(manager.matchDeviceName(sensor, "AA:BB:CC:DD:EE:03"))
        manager.setDevice(oldDevice)
        assertEquals(candidateAddress, manager.mActiveDeviceAddress)
        assertEquals(candidateAddress, manager.mActiveBluetoothDevice.address)
    }

    @Test fun transientTimeoutPermitsAnotherAttemptAfterBackoff() {
        (get("rebindTimeoutRunnable") as Runnable).run()
        assertRolledBack()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertTrue(manager.matchDeviceName(sensor, candidateAddress))
    }

    @Test fun identityMismatchRemainsRejectedAfterBackoff() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial("7BF00099C664AABB")
        assertRolledBack()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertFalse(manager.matchDeviceName(sensor, candidateAddress))
    }

    @Test fun provisionalIdentityCannotAuthorizeAnotherAddress() {
        (get("rebindTimeoutRunnable") as Runnable).run()
        manager.SerialNumber = "ICN-7BF00099C663"
        set("serialFromDevice", null)
        set("rawSerialFromDevice", null)
        assertFalse(manager.matchDeviceName("ICN-7BF00099C663", "AA:BB:CC:DD:EE:03"))
        assertNull(get("pendingAddressRebind"))
        assertEquals(oldAddress, manager.mActiveDeviceAddress)
    }

    @Test fun waitingOldAddressGattYieldsToStaleMatchingAdvertisement() {
        restoreWaitingOldAddress()
        SuperGattCallback::class.java.getDeclaredField("foundtime").apply { isAccessible = true }
            .setLong(manager, 42L)
        assertTrue(manager.matchDeviceName(sensor, candidateAddress))
        assertTrue(radio.closed)
        assertNull(currentGatt())
        assertEquals(0L, SuperGattCallback::class.java.getDeclaredField("foundtime")
            .apply { isAccessible = true }.getLong(manager))
        manager.setDevice(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(candidateAddress))
        // The retired attempt can report CONNECTED after close, including before a replacement exists.
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertNull(currentGatt())
        assertEquals(ICanHealthBleManager.Phase.IDLE, manager.phase)
        assertEquals(candidateAddress, manager.mActiveDeviceAddress)
    }

    @Test fun unrelatedAdvertisementDoesNotRetireWaitingConnection() {
        restoreWaitingOldAddress()
        assertFalse(manager.matchDeviceName("7BF00099C664AABB", candidateAddress))
        assertFalse(radio.closed)
        assertSame(gatt, currentGatt())
    }

    @Test fun freshReadingDoesNotRetireWaitingConnection() {
        restoreWaitingOldAddress()
        set("lastGlucoseReceiptRealtimeMs", System.currentTimeMillis())
        assertFalse(manager.matchDeviceName(sensor, candidateAddress))
        assertFalse(radio.closed)
        assertSame(gatt, currentGatt())
    }

    @Test fun establishedConnectionDoesNotYieldToAnotherAddress() {
        restoreWaitingOldAddress()
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertTrue(manager.hasLocallyConnectedGatt())
        assertFalse(manager.matchDeviceName(sensor, candidateAddress))
        assertFalse(radio.closed)
    }

    @Test fun silentAttemptIsBoundedEvenBeforeNativeShellExists() {
        restoreWaitingOldAddress()
        val timeout = manager.javaClass.getDeclaredMethod("connectionAttemptTimeoutMillis")
            .apply { isAccessible = true }.invoke(manager) as Long
        assertTrue(timeout in 1L..120_000L)
        SuperGattCallback::class.java.getDeclaredMethod("watchConnectionAttempt", BluetoothGatt::class.java)
            .apply { isAccessible = true }.invoke(manager, gatt)
        assertSame(gatt, deadlineAttempt())
        set("uiPaused", true) // Isolate expiry from starting a real replacement radio attempt.
        SuperGattCallback::class.java.getDeclaredMethod("connectionAttemptExpired", BluetoothGatt::class.java)
            .apply { isAccessible = true }.invoke(manager, gatt)
        assertTrue(radio.closed)
        assertNull(currentGatt())
        assertNull(deadlineAttempt())
    }

    @Test fun connectedCallbackCancelsSilentAttemptDeadline() {
        restoreWaitingOldAddress()
        SuperGattCallback::class.java.getDeclaredMethod("watchConnectionAttempt", BluetoothGatt::class.java)
            .apply { isAccessible = true }.invoke(manager, gatt)
        assertSame(gatt, deadlineAttempt())
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertNull(deadlineAttempt())
        assertSame(gatt, currentGatt())
    }

    @Test fun emptyDiscoveryCanRecoverAfterBackoff() {
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        retrySameCandidate()
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial(sensor)
        assertEquals(candidateAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
    }

    @Test fun partialGenericDiscoveryCanRetry() {
        radio.discoveredServices.add(BluetoothGattService(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), BluetoothGattService.SERVICE_TYPE_PRIMARY))
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        retrySameCandidate()
    }

    @Test fun cgmWithoutMeasurementCanRetry() {
        radio.discoveredServices.add(BluetoothGattService(
            ICanHealthConstants.CGM_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY))
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        retrySameCandidate()
    }

    @Test fun missingDisSerialCanRecoverAfterBackoff() {
        addCgmSurface(withSerial = false)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertRolledBack()
        retrySameCandidate()
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial(sensor)
        assertEquals(candidateAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
    }

    @Test fun emptySerialCanRecoverAfterBackoff() {
        addCgmSurface()
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        deliverSerial("")
        assertRolledBack()
        retrySameCandidate()
    }

    @Test fun nonCgmRejectionRequiresTwoConclusiveDiscoveries() {
        fun discoverNonCgm() {
            radio.discoveredServices.add(BluetoothGattService(
                UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), BluetoothGattService.SERVICE_TYPE_PRIMARY))
            manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            assertRolledBack()
        }
        discoverNonCgm()
        retrySameCandidate()
        discoverNonCgm()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertFalse(manager.matchDeviceName(sensor, candidateAddress))
    }

    @Test fun mergedOnboardingPolicyStillSetsAsideThreeFailedAttemptsAndExpires() {
        restoreWaitingOldAddress()
        manager.SerialNumber = "ICN-ONBOARDING"
        set("serialFromDevice", null)
        set("rawSerialFromDevice", null)
        manager.setDevice(null)
        manager.searchforDeviceAddress()
        assertTrue(manager.matchDeviceName("GeelyVehicle995", candidateAddress))
        manager.setDevice(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(candidateAddress))
        repeat(3) { index ->
            newGatt(manager.mActiveBluetoothDevice)
            manager.onConnectionStateChange(gatt, 147, BluetoothProfile.STATE_DISCONNECTED)
            if (index < 2) assertEquals(candidateAddress, manager.mActiveDeviceAddress)
        }
        assertNull(manager.mActiveDeviceAddress)
        assertNull(manager.mActiveBluetoothDevice)
        assertFalse(manager.matchDeviceName("GeelyVehicle995", candidateAddress))
        assertTrue((get("rejectedOnboardingAddresses") as Set<*>).isEmpty())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        assertTrue(manager.matchDeviceName("GeelyVehicle995", candidateAddress))
    }

    private fun restoreWaitingOldAddress() {
        (get("handler") as Handler).removeCallbacks(get("rebindTimeoutRunnable") as Runnable)
        set("pendingAddressRebind", null)
        manager.close()
        manager.setDevice(oldDevice)
        newGatt(oldDevice)
    }

    private fun newGatt(device: BluetoothDevice) {
        gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        radio = Shadow.extract(gatt)
        radio.remoteDevice = device
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, gatt)
    }

    private fun currentGatt(): Any? = SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt")
        .apply { isAccessible = true }.get(manager)

    private fun deadlineAttempt(): Any? {
        val deadline = SuperGattCallback::class.java.getDeclaredField("connectDeadline")
            .apply { isAccessible = true }.get(manager)
        return deadline.javaClass.getDeclaredField("current").apply { isAccessible = true }.get(deadline)
    }

    private fun retrySameCandidate() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertTrue(manager.matchDeviceName(sensor, candidateAddress))
        manager.setDevice(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(candidateAddress))
        newGatt(manager.mActiveBluetoothDevice)
    }

    private fun assertRolledBack() {
        assertEquals(oldAddress, manager.mActiveDeviceAddress)
        assertSame(oldDevice, manager.mActiveBluetoothDevice)
        assertEquals(sensor, manager.SerialNumber)
        assertEquals(sensor, get("serialFromDevice"))
        assertEquals(sensor, get("rawSerialFromDevice"))
        assertEquals(oldAddress, ICanHealthRegistry.findRecord(context, sensor)?.address)
        assertEquals(oldAddress, ICanRebindNatives.address)
        assertNull(get("pendingAddressRebind"))
        assertEquals(ICanHealthBleManager.Phase.IDLE, manager.phase)
        assertFalse(manager.matchDeviceName(sensor, candidateAddress))
        assertTrue(radio.closed)
    }

    private fun addCgmSurface(withSerial: Boolean = true, withAuth: Boolean = false) {
        val cgm = BluetoothGattService(ICanHealthConstants.CGM_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        cgm.addCharacteristic(BluetoothGattCharacteristic(ICanHealthConstants.CGM_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0))
        if (withAuth) {
            val auth = BluetoothGattCharacteristic(ICanHealthConstants.CGM_SPECIFIC_OPS,
                BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            auth.addDescriptor(BluetoothGattDescriptor(ICanHealthConstants.CCCD,
                BluetoothGattDescriptor.PERMISSION_WRITE))
            cgm.addCharacteristic(auth)
        }
        radio.discoveredServices.add(cgm)
        if (withSerial) {
            val dis = BluetoothGattService(ICanHealthConstants.DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            dis.addCharacteristic(BluetoothGattCharacteristic(ICanHealthConstants.SERIAL_NUMBER,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            radio.discoveredServices.add(dis)
        }
    }

    @Suppress("DEPRECATION")
    private fun deliverSerial(serial: String, status: Int = BluetoothGatt.GATT_SUCCESS) {
        val characteristic = BluetoothGattCharacteristic(ICanHealthConstants.SERIAL_NUMBER,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        characteristic.value = serial.toByteArray(Charsets.US_ASCII)
        manager.onCharacteristicRead(gatt, characteristic, status)
    }

    private fun get(name: String): Any? = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(manager)

    private fun set(name: String, value: Any?) = manager.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(manager, value)
}

class ICanRebindApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class ICanRebindNatives {
    companion object {
        var address: String? = null
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun setDeviceAddress(dataptr: Long, value: String?) { address = value }
    }
}

@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class ICanRebindGatt {
    lateinit var remoteDevice: BluetoothDevice
    val discoveredServices = mutableListOf<BluetoothGattService>()
    val reads = mutableListOf<UUID>()
    var readStarts = true
    var closed = false
    @Implementation fun getDevice(): BluetoothDevice = remoteDevice
    @Implementation fun getServices(): List<BluetoothGattService> = discoveredServices
    @Implementation fun getService(uuid: UUID): BluetoothGattService? = discoveredServices.firstOrNull { it.uuid == uuid }
    @Implementation fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        reads.add(characteristic.uuid)
        return readStarts
    }
    @Implementation fun disconnect() {}
    @Implementation fun close() { closed = true }
}
