package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that `Specific.registerBridges()` really registers every shared bridge
 * (plan §6 Q1).
 *
 * A missing `register(...)` line makes a whole feature degrade to a silent no-op.
 * It has happened: merging #421 dropped the mobile `ComposeHostAccess` line, so
 * the Compose UI fell back to the legacy View UI. Every rebase of a Q1 batch
 * edits exactly this method, so the check is worth its cost.
 */
class BridgeRegistrationTest {
    @Test
    fun mobileSpecificRegistersEverySharedBridge() {
        Specific.registerBridges()

        assertTrue("TrendAccess", TrendAccess.isRegistered())
        assertTrue("CustomAlertAccess", CustomAlertAccess.isRegistered())
        assertTrue("JournalAccess", JournalAccess.isRegistered())
        assertTrue("JournalTreatmentUploadAccess", JournalTreatmentUploadAccess.isRegistered())
        assertTrue("NightscoutTreatmentImportAccess", NightscoutTreatmentImportAccess.isRegistered())
        assertTrue("ComposeHostAccess", tk.glucodata.ui.ComposeHostAccess.isRegistered())
        assertTrue("JournalSnapshotAccess", JournalSnapshotAccess.isRegistered())
        assertTrue("NotificationPredictionAccess", NotificationPredictionAccess.isRegistered())
        assertTrue("GlucoseUncertaintyAccess", GlucoseUncertaintyAccess.isRegistered())
        assertTrue("CalibrationProfileAccess", CalibrationProfileAccess.isRegistered())
        assertTrue("CalibrationAccess", CalibrationAccess.isRegistered())
        assertTrue("AlarmActivityAccess", tk.glucodata.ui.AlarmActivityAccess.isRegistered())
        assertTrue("SetColorsScreenAccess", SetColorsScreenAccess.isRegistered())
        assertTrue("GlucoseAlarmsAccess", GlucoseAlarmsAccess.isRegistered())
        assertTrue("LegacyScreensAccess", LegacyScreensAccess.isRegistered())
        assertTrue("BluetoothMeterAccess", BluetoothMeterAccess.isRegistered())
        assertTrue("NovoPenAccess", NovoPenAccess.isRegistered())
        assertTrue("LibreNumbersAccess", LibreNumbersAccess.isRegistered())
        assertTrue("HealthConnectAccess", HealthConnectAccess.isRegistered())
        assertTrue("HealthPermissionsAccess", HealthPermissionsAccess.isRegistered())
        assertTrue("NumberDataAccess", NumberDataAccess.isRegistered())
        // The complication colour screen is watch-only: the phone copy was a shell.
        assertFalse("ComplicationConfigAccess", ComplicationConfigAccess.isRegistered())
        assertFalse("GlucoseValueRefreshAccess", GlucoseValueRefreshAccess.isRegistered())
        assertFalse("OngoingNotificationAccess", OngoingNotificationAccess.isRegistered())
        // This one is real on both flavours, unlike the shells above.
        assertTrue("FloatingConfigScreenAccess", FloatingConfigScreenAccess.isRegistered())
        assertTrue("LibreviewJournalEntriesAccess", LibreviewJournalEntriesAccess.isRegistered())
        assertTrue("HistoryRepositoryAccess", HistoryRepositoryAccess.isRegistered())
        assertTrue("HistorySyncBridgeAccess", HistorySyncBridgeAccess.isRegistered())
        assertTrue("CloneRecoveryAccessBridge", CloneRecoveryAccessBridge.isRegistered())
        assertTrue("CloneOutgoingRecoveryAccessBridge", CloneOutgoingRecoveryAccessBridge.isRegistered())
    }

    @Test
    fun registeringTwiceKeepsTheSameNumbersHub() {
        // Specific.start() calls registerBridges() again after Applic.onCreate did. A second
        // AllData would leave Applic.numdata and AllDataAccess.phone() on different hubs.
        Specific.registerBridges()
        val first = NumberDataAccess.get()
        Specific.registerBridges()
        assertSame(first, NumberDataAccess.get())
    }

    @Test
    fun applicTakesTheRegisteredNumbersHubAfterRegistering() {
        // Applic.numdata is initialised when the Application is constructed, before onCreate
        // registers anything, so it starts as the no-op. onCreate must take the registered hub
        // right after registerBridges(), or every numdata call on the phone does nothing.
        val source = java.io.File("src/main/java/tk/glucodata/Applic.java").readText()
        val register = source.indexOf("Specific.registerBridges();")
        assertTrue("registerBridges() is called", register >= 0)
        // Within the next few lines of onCreate, not the field declaration further up.
        val next = source.substring(register, minOf(source.length, register + 400))
        assertTrue("numdata is taken after registerBridges()", next.contains("numdata = NumberDataAccess.get();"))
    }
}
