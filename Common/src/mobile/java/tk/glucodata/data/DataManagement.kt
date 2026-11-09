package tk.glucodata.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Natives
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
 * 2. Factory reset (everything including settings)
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
     * Factory reset - clear EVERYTHING.
     * App returns to first-run state.
     * Settings, history, cache, and native data all wiped.
     */
    suspend fun factoryReset(context: Context = Applic.app): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Clear Room database
                val db = HistoryDatabase.getInstance(context)
                db.clearAllTables()
                
                // 2. Clear all cache
                context.cacheDir.deleteRecursively()
                context.codeCacheDir?.deleteRecursively()
                
                // 3. Clear SharedPreferences
                val prefsDir = context.applicationInfo.dataDir + "/shared_prefs"
                val prefsFolder = java.io.File(prefsDir)
                prefsFolder.deleteRecursively()
                
                // 4. Clear native data files
                clearNativeDataFiles(context)
                
                Log.d(TAG, "Factory reset complete")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during factory reset", e)
                false
            }
        }
    }
    
    /**
     * Helper to clear ALL native C++ data files.
     * Deletes everything in filesDir to fully wipe sensor data, calibrations, etc.
     */
    private fun clearNativeDataFiles(context: Context) {
        try {
            // Delete ALL files in filesDir (not just specific extensions)
            // This is where C++ stores sensor data, calibrations, etc.
            context.filesDir.listFiles()?.forEach { file ->
                val deleted = file.deleteRecursively()
                Log.d(TAG, "Deleted ${file.name}: $deleted")
            }
            
            // Also clear databases directory
            context.databaseList()?.forEach { dbName ->
                context.deleteDatabase(dbName)
                Log.d(TAG, "Deleted database: $dbName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing native files", e)
        }
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
