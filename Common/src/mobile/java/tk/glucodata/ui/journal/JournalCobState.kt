package tk.glucodata.ui.journal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.OutboundApiJournalSnapshot
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.journal.JournalEntry

/** Uses the notification's source selection and absorption calculation, off the UI thread. */
@Composable
internal fun rememberJournalCob(nowMillis: Long, entries: List<JournalEntry>): Float? {
    val revision by UiRefreshBus.revision.collectAsState(initial = 0L)
    val cob by produceState<Float?>(null, nowMillis, entries, revision) {
        value = withContext(Dispatchers.IO) {
            OutboundApiJournalSnapshot.broadcastIobSnapshot(nowMillis)
                ?.getOrNull(2)?.takeIf { it.isFinite() && it >= 0f }
        }
    }
    return cob
}
