package tk.glucodata.ui.restricted

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import tk.glucodata.RestrictedMode
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType

/**
 * The journal editor's save and delete on a restricted phone, shared by every place the
 * editor opens (dashboard, History, Journal).
 *
 * - Deleting an entry is deleting data: it asks for full mode.
 * - With "calibrate from journal BG" on, a blood-glucose entry is a calibration point. Adding,
 *   changing or removing one asks for full mode unless calibration is allowed. Background
 *   meter imports do not come through here and keep working.
 */
internal class RestrictedJournalGuard(private val requestFullMode: (() -> Unit) -> Unit) {
    fun save(existing: JournalEntry?, savedTypes: Collection<JournalEntryType>, action: () -> Unit) {
        val state = RestrictedMode.current()
        val needsFullMode = state.locked && !state.canCalibrate &&
            journalChangeTouchesCalibration(existing?.type, savedTypes) &&
            CalibrationManager.shouldCalibrateFromJournal()
        if (needsFullMode) requestFullMode(action) else action()
    }

    fun delete(action: () -> Unit) {
        if (RestrictedMode.current().canClearData) action() else requestFullMode(action)
    }
}

/** Whether saving these entries over [existingType] can add, move or drop a calibration point. */
internal fun journalChangeTouchesCalibration(
    existingType: JournalEntryType?,
    savedTypes: Collection<JournalEntryType>,
): Boolean = existingType == JournalEntryType.FINGERSTICK || JournalEntryType.FINGERSTICK in savedTypes

@Composable
internal fun rememberRestrictedJournalGuard(): RestrictedJournalGuard {
    val requestFullMode = LocalRequestFullMode.current
    return remember(requestFullMode) { RestrictedJournalGuard(requestFullMode) }
}
