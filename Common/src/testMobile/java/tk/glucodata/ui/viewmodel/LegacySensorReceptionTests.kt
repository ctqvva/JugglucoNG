package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySensorReceptionTests {
    @Test fun disconnectedLibreDoesNotClaimConnectionFromEnabledStreamingOrNativeHistory() {
        for (status in listOf("", "Receiving old values: 80", "Sensor Error")) {
            assertFalse(
                isLegacySensorReceiving(
                    isPaused = false,
                    localGattConnected = false,
                    nativeStatus = status,
                    streamingEnabled = true,
                )
            )
        }
    }

    @Test fun localGattCanConnectBeforeTheStreamingFlagChangesButPauseStillWins() {
        assertTrue(isLegacySensorReceiving(false, true, "", false))
        assertFalse(isLegacySensorReceiving(true, true, "", true))
    }

    @Test fun otherLegacyDriversKeepTheirExistingNativeStatusAndStreamingRules() {
        assertTrue(isLegacySensorReceiving(false, null, "Receiving", false))
        assertTrue(isLegacySensorReceiving(false, null, "", true))
        assertFalse(isLegacySensorReceiving(false, null, "", false))
        assertFalse(isLegacySensorReceiving(true, null, "Receiving", true))
    }
}
