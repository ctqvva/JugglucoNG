package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthDriverTests {

    private open class FakeDriver : ICanHealthDriver {
        override var viewMode: Int = 0
        override fun getStartTimeMs(): Long = 0L
        override fun getOfficialEndMs(): Long = 0L
        override fun getExpectedEndMs(): Long = 0L
        override fun isSensorExpired(): Boolean = false
        override fun getSensorRemainingHours(): Int = -1
        override fun getSensorAgeHours(): Int = -1
        override fun getReadingIntervalMinutes(): Int = 3
        override fun calibrateSensor(glucoseMgDl: Int): Boolean = false
        override val vendorFirmwareVersion: String = ""
        override val vendorModelName: String = ""
    }

    @Test
    fun defaultContractValues() {
        val driver = FakeDriver()
        assertTrue(driver.canConnectWithoutDataptr())
        assertTrue(driver.managesLiveRoomStorage())
        assertFalse(driver.shouldUseSharedCurrentSensorHandoffOnTerminate())
        assertEquals("", driver.getLifecycleSummary())
        assertTrue(driver.isUiEnabled())
        assertEquals("", driver.getPassiveConnectionStatus())
        assertNull(driver.getCurrentSnapshot(60_000L))
        assertNull(driver.getManagedCurrentSnapshot(60_000L))
        assertFalse(driver.supportsDisplayModes())
        assertFalse(driver.supportsManualCalibration())
    }

    @Test
    fun displayAndManualSupportTrackRawAndSensorFlags() {
        val driver = object : FakeDriver() {
            override fun supportsRawDisplayModes(): Boolean = true
            override fun supportsSensorCalibration(): Boolean = true
        }
        assertTrue(driver.supportsDisplayModes())
        assertTrue(driver.supportsManualCalibration())
    }

    @Test
    fun managedCurrentSnapshotMapsRawValue() {
        val driver = object : FakeDriver() {
            override fun getCurrentSnapshot(maxAgeMillis: Long) = ICanHealthCurrentSnapshot(
                timeMillis = 1_700_000_000_000L,
                glucoseValue = 6.5f,
                rawValue = 6.1f,
                rate = 0.2f,
                sensorGen = 4,
            )
        }
        val mapped = driver.getManagedCurrentSnapshot(60_000L)!!
        assertEquals(1_700_000_000_000L, mapped.timeMillis)
        assertEquals(6.5f, mapped.glucoseValue, 0.0001f)
        assertEquals(6.1f, mapped.rawGlucoseValue, 0.0001f)
        assertEquals(0.2f, mapped.rate, 0.0001f)
        assertEquals(4, mapped.sensorGen)
        assertTrue(mapped.calibratedGlucoseValue.isNaN())
    }

    @Test
    fun softOperationsAreNoOps() {
        val driver = FakeDriver()
        driver.softDisconnect()
        driver.softReconnect()
        driver.terminateManagedSensor(wipeData = true)
    }
}
