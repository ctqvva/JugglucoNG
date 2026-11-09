package tk.glucodata.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.journal.DeletedJournalEntries
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.journal.deleteAllJournalEntries

/** What "Clear history" removes. The three are exclusive: the dialog offers one of them. */
enum class ClearScope { READINGS, JOURNAL, BOTH }

/**
 * Handles data clearing operations for app maintenance.
 * Provides 2 levels of data clearing:
 * 1. Clear history: glucose readings, journal entries, or both. Settings, sensors and the
 *    journal's libraries (insulin presets, foods) stay.
 * 2. Factory reset: the system erases the whole app (see [factoryReset])
 */
object DataManagement {
    
    private const val TAG = "DataManagement"
    
    /**
     * Clear glucose readings, journal entries, or both.
     *
     * Readings are tombstoned as they go, so the next sync from a sensor's own store cannot
     * bring them back; see [HistoryRepository.clearAllReadings]. Journal entries leave the way
     * a deleted entry does, with Clone and Nightscout told.
     *
     * All of it is one database transaction ([clearHistoryRows]): a failure leaves the readings
     * and the journal as they were, never one of the two cleared. Clearing readings also waits
     * its turn among the native syncs ([HistorySync.catchUpThen]). It runs to the end even if
     * the caller leaves the screen.
     */
    suspend fun clearHistory(
        scope: ClearScope = ClearScope.READINGS,
        context: Context = Applic.app,
    ): Boolean {
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                val database = HistoryDatabase.getInstance(context)
                val deletedJournal = if (scope == ClearScope.JOURNAL) {
                    database.clearHistoryRows(scope)
                } else {
                    HistorySync.catchUpThen { database.clearHistoryRows(scope) }
                }
                deletedJournal?.let { JournalRepository().announceDeletion(it) }
                UiRefreshBus.requestDataRefresh()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing history ($scope)", e)
                false
            }
        }
    }
    
    /**
     * Factory reset: asks the system to erase the app, exactly as "Clear data" does in Android's
     * own settings.
     *
     * The system stops the app first and then removes its databases, preferences, files,
     * cache, scheduled alarms and granted permissions, so nothing running here can write a
     * file back in the middle of it. Deleting those files from inside a live process could:
     * a sensor callback or worker that touched a preference after its file was gone would
     * recreate it from the copy held in memory, with the old settings in it.
     *
     * On success the process is killed, so a caller normally never sees `true`; the app starts
     * from its first-run state the next time it is opened. `false` means the request was
     * refused and nothing was erased.
     *
     * @param eraseUserData the request itself, replaceable so a test need not erase the app
     */
    suspend fun factoryReset(
        context: Context = Applic.app,
        eraseUserData: (Context) -> Boolean = ::askSystemToEraseUserData,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                eraseUserData(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error during factory reset", e)
                false
            }
        }
    }

    private fun askSystemToEraseUserData(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.clearApplicationUserData()
    }
}

/**
 * The database half of [DataManagement.clearHistory]: the readings and the journal entries of
 * [scope] go in one transaction, so a failure in either leaves both as they were.
 *
 * @return what the journal clear removed, for [JournalRepository.announceDeletion] to pass on once
 * this has committed; `null` when the journal was not part of [scope]
 */
internal suspend fun HistoryDatabase.clearHistoryRows(scope: ClearScope): DeletedJournalEntries? =
    withTransaction {
        if (scope != ClearScope.JOURNAL) {
            HistoryRepository(this@clearHistoryRows).clearAllReadings()
        }
        if (scope != ClearScope.READINGS) deleteAllJournalEntries() else null
    }
