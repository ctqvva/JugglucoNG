package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.journal.AapsJournalImport

class AapsJournalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AapsJournalImport.isEnabled(context)) {
            Log.i(
                TAG,
                "AAPS journal broadcast ignored: import disabled action=${intent.action.orEmpty()} " +
                    "extras=${AapsJournalImport.describeExtras(intent)}"
            )
            return
        }

        val pendingResult = goAsync()
        importScope.launch {
            try {
                val result = AapsJournalImport.handleIntent(context.applicationContext, intent)
                Log.i(
                    TAG,
                    "AAPS journal import action=${intent.action.orEmpty()} extras=${AapsJournalImport.describeExtras(intent)} " +
                        "payloads=${result.treatmentObjects} imported=${result.importedEntries} " +
                        "deleted=${result.deletedEntries} skipped=${result.skippedTreatments}"
                )
                if (result.importedEntries > 0 || result.deletedEntries > 0) {
                    UiRefreshBus.requestDataRefresh()
                }
            } catch (t: Throwable) {
                Log.stack(TAG, "AAPS journal import failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AapsJournalReceiver"
        val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
