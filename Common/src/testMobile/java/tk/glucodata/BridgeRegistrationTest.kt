package tk.glucodata

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
        assertTrue("LegacyScreensAccess", LegacyScreensAccess.isRegistered())
        assertTrue("LibreviewJournalEntriesAccess", LibreviewJournalEntriesAccess.isRegistered())
        assertTrue("HistoryRepositoryAccess", HistoryRepositoryAccess.isRegistered())
        assertTrue("HistorySyncBridgeAccess", HistorySyncBridgeAccess.isRegistered())
        assertTrue("CloneRecoveryAccessBridge", CloneRecoveryAccessBridge.isRegistered())
        assertTrue("CloneOutgoingRecoveryAccessBridge", CloneOutgoingRecoveryAccessBridge.isRegistered())
    }
}
