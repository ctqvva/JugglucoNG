package tk.glucodata.data.food

import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tk.glucodata.data.HistoryDatabase

private const val COMMIT_TABLE = "settings_food_restore_commit"
private const val STAGE_TABLE = "settings_food_restore_stage"
private const val PREVIOUS_TABLE = "settings_food_restore_previous"
private const val DETAIL_COLUMNS = "foodId, referenceGrams, portions, fiberGrams, kcal, brand, barcode, " +
    "sourceKey, isLiquid, kept, useCount, lastUsedAt, lastQuantity, lastPortionIndex, hasOwnAbsorption"

/** The caller writes the receipt in the same SQLite transaction as the journal replacement. */
internal fun HistoryDatabase.recordFoodRestoreCommit(token: String) {
    val db = openHelper.writableDatabase
    check(db.inTransaction())
    db.execSQL("CREATE TABLE IF NOT EXISTS $COMMIT_TABLE (id INTEGER PRIMARY KEY, token TEXT NOT NULL)")
    db.execSQL("INSERT OR REPLACE INTO $COMMIT_TABLE (id, token) VALUES (1, ?)", arrayOf(token))
}

/**
 * Restore bookkeeping uses auxiliary SQLite tables created only on demand. They are
 * outside both Room schemas, so released builds can still open the databases.
 * All staging, commit, and recovery calls run under the food library write lock.
 */
internal class FoodRestoreRecovery(
    private val history: HistoryDatabase,
    private val food: FoodLibraryDatabase
) {
    suspend fun replace(
        details: List<FoodDetailsEntity>,
        commitJournal: suspend (String) -> Unit
    ) {
        val token = UUID.randomUUID().toString()
        stage(token, details)
        try {
            commitJournal(token)
        } catch (failure: Throwable) {
            try {
                withContext(NonCancellable) { recover() }
            } catch (recoveryFailure: Throwable) {
                failure.addSuppressed(recoveryFailure)
            }
            throw failure
        }
        try {
            withContext(NonCancellable) { finish(committed = true) }
        } catch (failure: Exception) {
            Log.w("FoodLibrary", "Could not clear committed food restore", failure)
        }
    }

    internal suspend fun stage(token: String, details: List<FoodDetailsEntity>) {
        food.withTransaction {
            val db = food.openHelper.writableDatabase
            db.execSQL("CREATE TABLE $STAGE_TABLE (token TEXT NOT NULL)")
            db.execSQL("INSERT INTO $STAGE_TABLE (token) VALUES (?)", arrayOf(token))
            db.execSQL("CREATE TABLE $PREVIOUS_TABLE AS SELECT $DETAIL_COLUMNS FROM food_details")
            food.foodDetailsDao().deleteAll()
            food.foodDetailsDao().upsertAll(details)
        }
    }

    suspend fun recover() {
        val committed = withContext(Dispatchers.IO) {
            val token = food.openHelper.writableDatabase.readToken(STAGE_TABLE) ?: return@withContext null
            history.openHelper.writableDatabase.readToken(COMMIT_TABLE) == token
        } ?: return
        finish(committed)
    }

    private suspend fun finish(committed: Boolean) {
        food.withTransaction {
            val db = food.openHelper.writableDatabase
            if (!committed) {
                food.foodDetailsDao().deleteAll()
                db.execSQL("INSERT INTO food_details ($DETAIL_COLUMNS) SELECT $DETAIL_COLUMNS FROM $PREVIOUS_TABLE")
            }
            db.execSQL("DROP TABLE $PREVIOUS_TABLE")
            db.execSQL("DROP TABLE $STAGE_TABLE")
        }
    }

    private fun SupportSQLiteDatabase.readToken(table: String): String? {
        val exists = query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { it.moveToFirst() }
        if (!exists) return null
        return query("SELECT token FROM $table").use { cursor ->
            check(cursor.moveToFirst()) { "Missing food restore token in $table" }
            cursor.getString(0)
        }
    }
}
