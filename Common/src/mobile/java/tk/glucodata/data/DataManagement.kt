package tk.glucodata.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Natives

/**
 * Handles data clearing operations for app maintenance.
 * Provides 3 levels of data clearing:
 * 1. Clear History only (Room database)
 * 2. Clear App Data (history + cache, keep settings)
 * 3. Factory Reset (everything including settings)
 */
object DataManagement {
    
    private const val TAG = "DataManagement"
    
    /**
     * Clear only the glucose history database.
     * Settings and native data are preserved.
     */
    suspend fun clearHistory(context: Context = Applic.app): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Clear Room database
                val db = HistoryDatabase.getInstance(context)
                db.clearAllTables()
                Log.d(TAG, "Cleared history database")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing history", e)
                false
            }
        }
    }
    
    /**
     * Clear sensor connections and data while preserving settings.
     * This deletes sensor directories and sensors.dat, but keeps:
     * - SharedPreferences (settings)
     * - Room database (glucose history)
     * - Other app configuration
     */
    suspend fun clearAppData(context: Context = Applic.app): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Disconnect all active sensors first
                try {
                    tk.glucodata.SensorBluetooth.mygatts()?.forEach { gatt ->
                        gatt.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting sensors", e)
                }
                
                // 2. Clear cache directories (safe, no settings here)
                context.cacheDir.deleteRecursively()
                context.codeCacheDir?.deleteRecursively()
                
                // 3. Selectively delete sensor data files only
                // Native code stores sensors in:
                // - sensors.dat (sensor list)
                // - Directories named after sensor serial (e.g., "E07A-XX...")
                // Settings are kept in a separate settings file
                val sensorPatterns = listOf(
                    "sensors.dat",
                    "logs",  // Log directory
                )
                val sensorDirPrefixes = listOf(
                    "E07A-",  // Libre 3
                    "E007-",  // Libre 2
                    "E00A-",  // Libre sensor
                    "LT",     // Sibionics
                )
                
                context.filesDir.listFiles()?.forEach { file ->
                    val shouldDelete = sensorPatterns.contains(file.name) ||
                        sensorDirPrefixes.any { file.name.startsWith(it) } ||
                        file.name.length == 16 && file.isDirectory  // Sensor dir (16-char serial)
                    
                    if (shouldDelete) {
                        val deleted = file.deleteRecursively()
                        Log.d(TAG, "Deleted sensor data: ${file.name} = $deleted")
                    } else {
                        Log.d(TAG, "Preserved: ${file.name}")
                    }
                }
                
                Log.d(TAG, "Cleared sensor data (preserved settings)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing sensor data", e)
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
