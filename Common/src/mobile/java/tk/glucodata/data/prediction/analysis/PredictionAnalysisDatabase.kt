package tk.glucodata.data.prediction.analysis

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

internal const val PREDICTION_ANALYSIS_DATABASE_VERSION = 1

/**
 * Recorded forecasts, kept apart from the glucose history.
 *
 * A database of its own because it has a lifecycle of its own: it exists only once the user
 * turns on prediction analysis, it is wiped as a whole from the analysis screen, and nothing
 * else reads it. Keeping it out of [tk.glucodata.data.HistoryDatabase] also keeps the history's
 * long migration chain out of this feature's way, and this feature out of the history's.
 */
@Database(entities = [RecordedForecastEntity::class], version = PREDICTION_ANALYSIS_DATABASE_VERSION, exportSchema = true)
abstract class PredictionAnalysisDatabase : RoomDatabase() {
    abstract fun recordedForecastDao(): RecordedForecastDao

    companion object {
        private const val FILE_NAME = "prediction_analysis.db"

        @Volatile
        private var instance: PredictionAnalysisDatabase? = null

        fun getInstance(context: Context): PredictionAnalysisDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, PredictionAnalysisDatabase::class.java, FILE_NAME)
                    .build()
                    .also { instance = it }
            }

        /** Bytes the database takes on disk, its journal included. */
        fun sizeOnDisk(context: Context): Long {
            val file = context.getDatabasePath(FILE_NAME)
            return listOf(file, java.io.File(file.path + "-wal"), java.io.File(file.path + "-shm"))
                .filter { it.exists() }
                .sumOf { it.length() }
        }
    }
}
