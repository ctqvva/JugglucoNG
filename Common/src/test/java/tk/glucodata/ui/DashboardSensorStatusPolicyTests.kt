package tk.glucodata.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSensorStatusPolicyTests {
    @Test
    fun freshDataSuppressesStaleNativeStatus() {
        assertFalse(
            shouldShowDashboardSensorStatus(
                isCloneSource = true,
                isFreshData = true,
                sensorStatus = "Disconnected",
                heroOwnsAwaitingStatus = false,
            )
        )
    }

    @Test
    fun staleDataMayShowMeaningfulNativeStatus() {
        assertTrue(
            shouldShowDashboardSensorStatus(
                isCloneSource = true,
                isFreshData = false,
                sensorStatus = "Disconnected",
                heroOwnsAwaitingStatus = false,
            )
        )
    }

    @Test
    fun awaitingHeroKeepsItsMoreUsefulStatus() {
        assertFalse(
            shouldShowDashboardSensorStatus(
                isCloneSource = true,
                isFreshData = false,
                sensorStatus = "Disconnected",
                heroOwnsAwaitingStatus = true,
            )
        )
    }

    @Test
    fun freshNonCloneSensorKeepsItsMeaningfulStatus() {
        assertTrue(
            shouldShowDashboardSensorStatus(
                isCloneSource = false,
                isFreshData = true,
                sensorStatus = "Calibration required",
                heroOwnsAwaitingStatus = false,
            )
        )
    }
}
