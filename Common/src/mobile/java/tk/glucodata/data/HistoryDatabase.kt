package tk.glucodata.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tk.glucodata.data.journal.JournalDao
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import tk.glucodata.data.journal.JournalPendingDeleteEntity
import tk.glucodata.data.meal.MealDao
import tk.glucodata.data.meal.MealEntity
import tk.glucodata.data.meal.MealItemEntity
import tk.glucodata.data.meal.MealProductEntity

/**
 * Room database for independent glucose history storage.
 * This database is separate from the C++ native sensor data and
 * persists through "wipe sensor data" operations.
 *
 * Version history:
 *   v2 — original single-sensor schema (timestamp PK, value, rawValue, rate)
 *   v3 — multi-sensor: added sensorSerial column, auto-generated PK, composite unique index
 *   v4 — compatibility columns from a reverted Sibionics experiment (unused by current entity)
 *   v5 — dashboard journal entries and insulin presets
 *   v6 — insulin preset curves for richer activity modeling
 *   v7 — per-preset active-insulin participation flag
 *   v8 — per-reading delete tombstones to keep manual Room deletes durable
 *   v9 — per-sensor timestamp index for bounded dashboard/stats history queries
 *   v10 — Nightscout sync columns on journal entries + tombstone table for journal deletes
 *   v11 — journal food library and macro metadata for carb entries
 *   v12 — per-preset dose-calculation eligibility
 *   v13 — retry accounting on journal delete tombstones
 *   v14 — per-reading credible intervals for uncertainty-aware estimators
 *   v15 — per-reading record of the value actually displayed, so calibration
 *         changes stop rewriting the sensor's own stored numbers
 *   v16 — repair step: two branches each shipped a different "v13", so what a
 *         phone holds at v15 depends on which build it happened to install
 *   v17 — per-journal-entry LibreView delivery timestamp
 *   v18 — meals (composition + product cache) and the mealId correlation on journal entries
 *   v19 — contributedAt on the product cache (sent to Open Food Facts)
 *   v20 — saturated fat, salt and an OFF category on the product cache (Nutri-Score inputs)
 *   v21 — editable package piece counts for product and meal quantity resolution
 */
@Database(
    entities = [
        HistoryReading::class,
        DeletedHistoryReading::class,
        ReadingUncertainty::class,
        ReadingDisplay::class,
        JournalEntryEntity::class,
        JournalFoodEntity::class,
        JournalInsulinPresetEntity::class,
        JournalPendingDeleteEntity::class,
        MealEntity::class,
        MealItemEntity::class,
        MealProductEntity::class
    ],
    version = 21,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    
    abstract fun historyDao(): HistoryDao
    abstract fun journalDao(): JournalDao
    abstract fun readingUncertaintyDao(): ReadingUncertaintyDao
    abstract fun readingDisplayDao(): ReadingDisplayDao
    abstract fun mealDao(): MealDao
    
    companion object {
        private const val DATABASE_NAME = "glucose_history.db"

        @Volatile
        private var INSTANCE: HistoryDatabase? = null

        /**
         * Migration v2 → v3: Add sensorSerial column for multi-sensor support.
         *
         * Strategy: recreate the table with the new schema and copy existing data,
         * assigning all old rows to a default sensor serial "unknown".
         * A full re-sync from native will later re-tag them correctly.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the new table with auto-generated PK and sensorSerial
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS history_readings_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sensorSerial TEXT NOT NULL DEFAULT 'unknown',
                        value REAL NOT NULL,
                        rawValue REAL NOT NULL,
                        rate REAL
                    )
                """.trimIndent())
                
                // Copy existing data, defaulting sensorSerial to 'unknown'
                db.execSQL("""
                    INSERT INTO history_readings_new (timestamp, sensorSerial, value, rawValue, rate)
                    SELECT timestamp, 'unknown', value, rawValue, rate FROM history_readings
                """.trimIndent())
                
                // Drop old table and rename new one
                db.execSQL("DROP TABLE history_readings")
                db.execSQL("ALTER TABLE history_readings_new RENAME TO history_readings")
                
                // Create indices
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_history_readings_timestamp_sensorSerial ON history_readings (timestamp, sensorSerial)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_readings_sensorSerial ON history_readings (sensorSerial)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history_readings ADD COLUMN customValue REAL")
                db.execSQL("ALTER TABLE history_readings ADD COLUMN customRate REAL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sensorSerial TEXT,
                        entryType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        amount REAL,
                        glucoseValueMgDl REAL,
                        durationMinutes INTEGER,
                        intensity TEXT,
                        insulinPresetId INTEGER,
                        source TEXT NOT NULL,
                        sourceRecordId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_timestamp ON journal_entries (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_entryType ON journal_entries (entryType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_insulinPresetId ON journal_entries (insulinPresetId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_entries_sourceRecordId ON journal_entries (sourceRecordId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_insulin_presets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        onsetMinutes INTEGER NOT NULL,
                        durationMinutes INTEGER NOT NULL,
                        accentColor INTEGER NOT NULL,
                        isBuiltIn INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_insulin_presets_sortOrder ON journal_insulin_presets (sortOrder)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_insulin_presets ADD COLUMN curveJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_insulin_presets ADD COLUMN countsTowardIob INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE journal_insulin_presets SET countsTowardIob = 0 WHERE sortOrder IN (1, 10)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS history_deleted_readings (
                        timestamp INTEGER NOT NULL,
                        sensorSerial TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY(timestamp, sensorSerial)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_history_deleted_readings_sensorSerial " +
                        "ON history_deleted_readings (sensorSerial)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_history_readings_sensorSerial_timestamp " +
                        "ON history_readings (sensorSerial, timestamp)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN nsUploadedAt INTEGER")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN nsRemoteId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_pending_deletes (
                        entryId INTEGER PRIMARY KEY NOT NULL,
                        nsRemoteId TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN foodId INTEGER")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN proteinGrams REAL")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN fatGrams REAL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_foodId ON journal_entries (foodId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_foods (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        carbsGrams REAL NOT NULL,
                        proteinGrams REAL,
                        fatGrams REAL,
                        absorptionMinutes INTEGER NOT NULL,
                        accentColor INTEGER NOT NULL,
                        isBuiltIn INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_foods_isArchived_sortOrder ON journal_foods (isArchived, sortOrder)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_foods_displayName ON journal_foods (displayName)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE journal_insulin_presets " +
                        "ADD COLUMN useForCalculation INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "UPDATE journal_insulin_presets SET useForCalculation = 0 " +
                        "WHERE isBuiltIn = 1 AND sortOrder IN (1, 10)"
                )
            }
        }


        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE journal_pending_deletes ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE journal_pending_deletes ADD COLUMN lastAttemptAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
        /**
         * v13 → v14: uncertainty lives in its own table rather than as columns
         * on `history_readings`, which native re-sync rewrites. Nothing is
         * backfilled: readings written before this have no uncertainty, which
         * is the truthful answer, and they render as a plain line.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_uncertainty (
                        sensorSerial TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        lowerMgdl REAL NOT NULL,
                        upperMgdl REAL NOT NULL,
                        intervalMass REAL NOT NULL,
                        confidence REAL,
                        artifactProbability REAL,
                        PRIMARY KEY(sensorSerial, timestamp)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_reading_uncertainty_timestamp " +
                        "ON reading_uncertainty (timestamp)"
                )
            }
        }

        /**
         * Additive: the reading's displayed value moves to its own table.
         *
         * Nothing is backfilled here. Room migrations run on the database alone,
         * and deciding which existing rows carry a calibrated value needs the
         * calibration preferences — so the seeding is done once from
         * [HistoryRepository.seedDisplayRecordsFromOverwrittenHistory] instead,
         * where that state is readable.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_display (
                        sensorSerial TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        displayMgdl REAL NOT NULL,
                        viewMode INTEGER NOT NULL,
                        calibrationFingerprint INTEGER NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        PRIMARY KEY(sensorSerial, timestamp)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_reading_display_timestamp " +
                        "ON reading_display (timestamp)"
                )
            }
        }

        /**
         * v15 → v16: reconciles a database that passed v13 under a different meaning of it.
         *
         * The tombstone retry columns and the uncertainty table were both written as "v13",
         * on separate branches. A phone runs whichever it met first, and from then on it is
         * past 13 and can never be handed the other one — so the schema it actually holds
         * depends on which build it happened to install, and Room finds a column missing
         * that its entities require.
         *
         * This step asks the database what it has rather than assuming a history, and adds
         * only what is absent. On a phone that took the ordinary path every statement here
         * is a no-op, and nothing is dropped or rewritten in either case.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "journal_pending_deletes", "attempts")) {
                    db.execSQL(
                        "ALTER TABLE journal_pending_deletes ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if (!hasColumn(db, "journal_pending_deletes", "lastAttemptAt")) {
                    db.execSQL(
                        "ALTER TABLE journal_pending_deletes ADD COLUMN lastAttemptAt INTEGER NOT NULL DEFAULT 0"
                    )
                }
                // The other side of the same collision: a phone that took the tombstone
                // columns as its v13 reaches here by a different route. Both statements are
                // already IF NOT EXISTS in their own steps; repeating them costs nothing and
                // covers the ordering this branch cannot know about.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_uncertainty (
                        sensorSerial TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        lowerMgdl REAL NOT NULL,
                        upperMgdl REAL NOT NULL,
                        intervalMass REAL NOT NULL,
                        confidence REAL,
                        artifactProbability REAL,
                        PRIMARY KEY(sensorSerial, timestamp)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_reading_uncertainty_timestamp " +
                        "ON reading_uncertainty (timestamp)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_display (
                        sensorSerial TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        displayMgdl REAL NOT NULL,
                        viewMode INTEGER NOT NULL,
                        calibrationFingerprint INTEGER NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        PRIMARY KEY(sensorSerial, timestamp)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_reading_display_timestamp " +
                        "ON reading_display (timestamp)"
                )
            }
        }

        /**
         * v16 → v17: track LibreView delivery independently from Nightscout delivery.
         *
         * The column check also accepts databases created by an installed build of the
         * original PR branch, where this column briefly occupied version 13.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "journal_entries", "lvUploadedAt")) {
                    db.execSQL("ALTER TABLE journal_entries ADD COLUMN lvUploadedAt INTEGER")
                }
            }
        }

        /** What the database actually holds, rather than what its version number implies. */
        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            val cursor = db.query("PRAGMA table_info(`$table`)")
            try {
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (column.equals(cursor.getString(nameIndex), ignoreCase = true)) {
                        return true
                    }
                }
            } finally {
                cursor.close()
            }
            return false
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Earlier meal builds used v14-v17 for meal tables. Repair the
                // upstream fields they skipped before adding the meal schema.
                MIGRATION_15_16.migrate(db)
                MIGRATION_16_17.migrate(db)
                if (!hasColumn(db, "journal_entries", "mealId")) {
                    db.execSQL("ALTER TABLE journal_entries ADD COLUMN mealId INTEGER")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_mealId ON journal_entries (mealId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        servings REAL,
                        cookedWeightGrams REAL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        archivedAt INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meals_archivedAt ON meals (archivedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meals_updatedAt ON meals (updatedAt)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meal_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mealId INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        barcode TEXT,
                        source TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        brand TEXT,
                        basis TEXT NOT NULL,
                        carbsGrams REAL NOT NULL,
                        proteinGrams REAL,
                        fatGrams REAL,
                        fiberGrams REAL,
                        sugarsGrams REAL,
                        polyolsGrams REAL,
                        kcal REAL,
                        netQuantity REAL,
                        netUnit TEXT,
                        servingText TEXT,
                        servingQuantity REAL,
                        servingUnit TEXT,
                        servingPieces REAL,
                        servingPieceLabel TEXT,
                        servingsPerBatch REAL,
                        densityGramsPerMl REAL,
                        pieceGrams REAL,
                        quantityText TEXT NOT NULL,
                        factor REAL,
                        amountGrams REAL,
                        amountMilliliters REAL,
                        plausibilityFlags TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meal_items_mealId ON meal_items (mealId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meal_items_barcode ON meal_items (barcode)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meal_products (
                        barcode TEXT NOT NULL,
                        source TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        brand TEXT,
                        basis TEXT NOT NULL,
                        carbsGrams REAL NOT NULL,
                        proteinGrams REAL,
                        fatGrams REAL,
                        fiberGrams REAL,
                        sugarsGrams REAL,
                        polyolsGrams REAL,
                        kcal REAL,
                        netQuantity REAL,
                        netUnit TEXT,
                        servingText TEXT,
                        servingQuantity REAL,
                        servingUnit TEXT,
                        servingPieces REAL,
                        servingPieceLabel TEXT,
                        densityGramsPerMl REAL,
                        pieceGrams REAL,
                        plausibilityFlags TEXT,
                        fetchedAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        PRIMARY KEY(barcode)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meal_products_lastUsedAt ON meal_products (lastUsedAt)")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "meal_products", "contributedAt")) {
                    db.execSQL("ALTER TABLE meal_products ADD COLUMN contributedAt INTEGER")
                }
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "meal_products", "saturatedFatGrams")) {
                    db.execSQL("ALTER TABLE meal_products ADD COLUMN saturatedFatGrams REAL")
                }
                if (!hasColumn(db, "meal_products", "saltGrams")) {
                    db.execSQL("ALTER TABLE meal_products ADD COLUMN saltGrams REAL")
                }
                if (!hasColumn(db, "meal_products", "offCategory")) {
                    db.execSQL("ALTER TABLE meal_products ADD COLUMN offCategory TEXT")
                }
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "meal_items", "packagePieces")) {
                    db.execSQL("ALTER TABLE meal_items ADD COLUMN packagePieces REAL")
                }
                if (!hasColumn(db, "meal_items", "packagePieceLabel")) {
                    db.execSQL("ALTER TABLE meal_items ADD COLUMN packagePieceLabel TEXT")
                }
                if (!hasColumn(db, "meal_items", "packagePiecesUserEdited")) {
                    db.execSQL("ALTER TABLE meal_items ADD COLUMN packagePiecesUserEdited INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "meal_products", "packagePieces")) {
                    db.execSQL("ALTER TABLE meal_products ADD COLUMN packagePieces REAL")
                }
                if (!hasColumn(db, "meal_products", "packagePieceLabel")) {
                    db.execSQL("ALTER TABLE meal_products ADD COLUMN packagePieceLabel TEXT")
                }
                if (!hasColumn(db, "meal_products", "packagePiecesUserEdited")) {
                    db.execSQL("ALTER TABLE meal_products ADD COLUMN packagePiecesUserEdited INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        fun getInstance(context: Context): HistoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21
                )
                .build().also { INSTANCE = it }
            }

        @JvmStatic
        fun isCompatibleAtStartup(context: Context): Boolean {
            if (!context.getDatabasePath(DATABASE_NAME).isFile) return true

            return try {
                getInstance(context).openHelper.writableDatabase
                true
            } catch (error: RuntimeException) {
                android.util.Log.e(
                    "HistoryDatabase",
                    "Existing history database is incompatible with this build",
                    error
                )
                false
            }
        }
    }
}
