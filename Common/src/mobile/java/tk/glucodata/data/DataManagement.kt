package tk.glucodata.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.journal.JournalRepository

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
     * Runs to the end even if the caller leaves the screen: an interrupted clear would roll back
     * one half and leave the other done.
     */
    suspend fun clearHistory(
        scope: ClearScope = ClearScope.READINGS,
        context: Context = Applic.app,
    ): Boolean {
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                if (scope != ClearScope.JOURNAL) {
                    HistoryRepository(context).clearAllReadings()
                }
                if (scope != ClearScope.READINGS) {
                    JournalRepository().deleteAllEntries()
                }
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
