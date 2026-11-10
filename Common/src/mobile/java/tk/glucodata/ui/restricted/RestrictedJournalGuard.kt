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
 * - Deleting an entry is deleting data: it needs full mode.
 * - With "calibrate from journal BG" on, a blood-glucose entry is a calibration point. Adding,
 *   changing or removing one needs full mode unless calibration is allowed. Background
 *   meter imports do not come through here and keep working.
 *
 * Refused, the editor stays open with what was typed, and a short message says full mode is
 * in Settings: going there would throw the entry away.
 */
internal class RestrictedJournalGuard(private val needFullMode: (() -> Unit) -> Unit) {
    fun save(existing: JournalEntry?, savedTypes: Collection<JournalEntryType>, action: () -> Unit) {
        val state = RestrictedMode.current()
        val needsFullMode = state.locked && !state.canCalibrate &&
            journalChangeTouchesCalibration(existing?.type, savedTypes) &&
            CalibrationManager.shouldCalibrateFromJournal()
        if (needsFullMode) needFullMode(action) else action()
    }

    fun delete(action: () -> Unit) {
        if (RestrictedMode.current().canClearData) action() else needFullMode(action)
    }
}

/** Whether saving these entries over [existingType] can add, move or drop a calibration point. */
internal fun journalChangeTouchesCalibration(
    existingType: JournalEntryType?,
    savedTypes: Collection<JournalEntryType>,
): Boolean = existingType == JournalEntryType.FINGERSTICK || JournalEntryType.FINGERSTICK in savedTypes

@Composable
internal fun rememberRestrictedJournalGuard(): RestrictedJournalGuard {
    val guard = rememberFullModeGuard()
    return remember(guard) { RestrictedJournalGuard(guard) }
}
