package tk.glucodata

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.TimeUnit
import tk.glucodata.drivers.api.ApiGlucoseSourceManager
import tk.glucodata.drivers.mq.MQFollowerManager
import tk.glucodata.drivers.nightscout.NightscoutFollowerManager
import tk.glucodata.share.lumiflex.LumiflexFollowerManager

/**
 * The managed scan's timeout stops the scan and schedules a retry. That retry has to stay
 * guarded until it runs, or a connect attempt in between schedules a second scan beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NativeHistoryTestApplication::class,
    shadows = [ScanRetryNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SensorBluetoothScanRetryTests {

    private class CountingScanner : Scanner {
        var starts = 0
        var stops = 0
        override fun init() = true
        override fun start(): Boolean {
            starts++
            return true
        }
        override fun stop() {
            stops++
        }
    }

    private lateinit var blue: SensorBluetooth
    private val scanner = CountingScanner()
    private var previous: SensorBluetooth? = null

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        shadowOf(manager.adapter).setEnabled(true)
        blue = SensorBluetooth()
        blue.scanner = scanner
        previous = SensorBluetooth.blueone
        SensorBluetooth.blueone = blue
    }

    @After
    fun tearDown() {
        blue.stopScan(false)
        SensorBluetooth.blueone = previous
    }

    private fun generation(): Long =
        SensorBluetooth::class.java.getDeclaredField("scanGeneration").let {
            it.isAccessible = true
            it.getLong(blue)
        }

    private fun timeout(generation: Long) {
        SensorBluetooth::class.java.getDeclaredMethod("scanTimedOut", java.lang.Long.TYPE).let {
            it.isAccessible = true
            it.invoke(blue, generation)
        }
    }

    @Test
    fun timeoutRetryStaysPendingSoASecondStartIsRefused() {
        blue.mScanning = true
        timeout(generation())

        assertEquals(1, scanner.stops)
        assertFalse(blue.mScanning)
        assertTrue("retry is guarded until it runs", SensorBluetooth.scanActiveOrPending())
        val retry = blue.scanFuture
        assertNotNull(retry)

        blue.scanStarter(2 * 60_000L) // due after the retry
        assertSame("no second scan scheduled beside the retry", retry, blue.scanFuture)
        assertFalse(retry!!.isCancelled)
    }

    @Test
    fun stopCancelsThePendingRetry() {
        blue.mScanning = true
        timeout(generation())
        val retry = blue.scanFuture!!

        blue.stopScan(false)
        assertTrue(retry.isCancelled)
        assertFalse(SensorBluetooth.scanActiveOrPending())
    }

    @Test
    fun anEarlierRequestMovesThePendingRetryForward() {
        blue.mScanning = true
        timeout(generation()) // retry pending a minute out
        val retry = blue.scanFuture!!

        // The zero-delay scan runs on Applic.scheduler right away and, with nothing in the
        // roster, clears scanstart. runScan is synchronized on blue, so holding its monitor
        // keeps the worker out until the pending state has been checked.
        synchronized(blue) {
            blue.scanStarter(0L) // Forget in the Bluetooth diagnostics
            val sooner = blue.scanFuture!!
            assertNotSame(retry, sooner)
            assertTrue("the old retry is retired, not left to start a second scan", retry.isCancelled)
            assertTrue(sooner.getDelay(TimeUnit.MILLISECONDS) <= 1_000L)
            assertTrue(SensorBluetooth.scanActiveOrPending())
        }
    }

    private fun roster(serial: String, bluetooth: Boolean) = object : SuperGattCallback(serial, 0L, 0) {
        override fun usesBluetoothDiscovery() = bluetooth
    }

    @Test
    fun aFollowerDoesNotKeepTheScanLookingForIt() {
        val sensor = roster("SENSOR1", bluetooth = true)
        val follower = roster("FOLLOW1", bluetooth = false)
        val roster = listOf<SuperGattCallback>(sensor, follower)
        assertFalse(SensorBluetooth.everyScanTargetFound(roster))

        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        sensor.mActiveBluetoothDevice = manager.adapter.getRemoteDevice("00:11:22:33:44:55")
        assertTrue("the only Bluetooth sensor is found", SensorBluetooth.everyScanTargetFound(roster))

        assertTrue(SensorBluetooth.hasScanTargets(roster))
        assertFalse("followers alone give the scan nothing to find", SensorBluetooth.hasScanTargets(listOf(follower)))
    }

    @Test
    fun networkFollowersOptOutOfDiscovery() {
        for (type in listOf(
            NightscoutFollowerManager::class.java,
            MQFollowerManager::class.java,
            LumiflexFollowerManager::class.java,
            ApiGlucoseSourceManager::class.java,
        )) {
            assertSame(type.simpleName, type, type.getMethod("usesBluetoothDiscovery").declaringClass)
        }
    }

    @Test
    fun staleTimeoutDoesNotStopANewerScan() {
        blue.mScanning = true
        val old = generation()
        blue.stopScan(false)
        blue.scanStarter(60_000L)
        blue.mScanning = true // the newer scan is up

        timeout(old)
        assertTrue(blue.mScanning)
        assertEquals("only the explicit stop reached the scanner", 1, scanner.stops)
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class ScanRetryNatives {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun getAndroid13(): Boolean = false
    }
}
