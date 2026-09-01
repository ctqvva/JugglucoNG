package tk.glucodata.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSensorStatusPolicyTests {
    @Test
    fun freshDataSuppressesStaleNativeStatus() {
        assertFalse(
            shouldShowDashboardSensorStatus(
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
                isFreshData = false,
                sensorStatus = "Disconnected",
                heroOwnsAwaitingStatus = true,
            )
        )
    }
}
