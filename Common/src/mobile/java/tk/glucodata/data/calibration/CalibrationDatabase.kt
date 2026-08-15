package tk.glucodata.data.calibration

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CalibrationEntity::class], version = 5, exportSchema = false)
abstract class CalibrationDatabase : RoomDatabase() {
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        @Volatile
        private var INSTANCE: CalibrationDatabase? = null
        
        // Migration from version 1 to 2: Add isRawMode column
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calibrations ADD COLUMN isRawMode INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        // Migration from version 2 to 3: Add sensorValueRaw column (default to sensorValue for existing rows)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calibrations ADD COLUMN sensorValueRaw REAL NOT NULL DEFAULT 0")
                // Copy sensorValue to sensorValueRaw for existing calibrations
                database.execSQL("UPDATE calibrations SET sensorValueRaw = sensorValue")
            }
        }

        // Migration from version 3 to 4: Add journalEntryId column (null = entered by hand)
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calibrations ADD COLUMN journalEntryId INTEGER")
            }
        }

        // Migration from version 4 to 5: Add sensorValueStock column. 0 means
        // unknown, which is what every existing row is: the stock value behind
        // an old anchor is only recoverable from the source history of the
        // device that took it, exactly as before this column existed.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calibrations ADD COLUMN sensorValueStock REAL NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): CalibrationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CalibrationDatabase::class.java,
                    "calibration_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
