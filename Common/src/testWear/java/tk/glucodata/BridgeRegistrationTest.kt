package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch side of [BridgeRegistrationTest]'s phone check: `Specific.registerBridges()`
 * must register the bridges the watch is supposed to have (plan §6 Q1), and must NOT
 * register the phone-only ones. The watch intentionally has no journal, uncertainty,
 * notification-prediction, calibration or legacy-screen bridges.
 */
class BridgeRegistrationTest {
    @Test
    fun wearSpecificRegistersTheBridgesTheWatchHas() {
        Specific.registerBridges()

        assertTrue("TrendAccess", TrendAccess.isRegistered())
        assertTrue("ComposeHostAccess", tk.glucodata.ui.ComposeHostAccess.isRegistered())
        assertTrue("CalibrationAccess", CalibrationAccess.isRegistered())
        assertTrue("AlarmActivityAccess", tk.glucodata.ui.AlarmActivityAccess.isRegistered())
        assertTrue("ComplicationConfigAccess", ComplicationConfigAccess.isRegistered())
        assertTrue("GlucoseValueRefreshAccess", GlucoseValueRefreshAccess.isRegistered())
        assertTrue("OngoingNotificationAccess", OngoingNotificationAccess.isRegistered())
        assertTrue("FloatingConfigScreenAccess", FloatingConfigScreenAccess.isRegistered())
        assertTrue("SetColorsScreenAccess", SetColorsScreenAccess.isRegistered())
        assertTrue("GlucoseAlarmsAccess", GlucoseAlarmsAccess.isRegistered())
        // The phone-only legacy screens are absent, not a silent no-op (plan P2).
        assertFalse("LegacyScreensAccess", LegacyScreensAccess.isRegistered())
        assertFalse("BluetoothMeterAccess", BluetoothMeterAccess.isRegistered())
        assertFalse("NovoPenAccess", NovoPenAccess.isRegistered())
        assertFalse("LibreNumbersAccess", LibreNumbersAccess.isRegistered())
        assertFalse("HealthConnectAccess", HealthConnectAccess.isRegistered())
        assertFalse("HealthPermissionsAccess", HealthPermissionsAccess.isRegistered())
        assertFalse("NumberDataAccess", NumberDataAccess.isRegistered())
    }
}
