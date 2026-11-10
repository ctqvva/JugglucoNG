package tk.glucodata.data.food

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tk.glucodata.data.journal.JournalFoodEntity
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

internal const val FOOD_LIBRARY_DATABASE_VERSION = 2

/**
 * What the food library knows about a food beyond the row in the journal's `journal_foods`
 * table: what its numbers refer to, the measures it comes in, where it came from and how it
 * has been used.
 *
 * The journal row keeps the name and the carbs, protein and fat of one reference amount, as
 * it always has; a food without a row here is an old-style preset counted in whole servings.
 */
@Entity(tableName = "food_details")
data class FoodDetailsEntity(
    @PrimaryKey
    val foodId: Long,
    /** The weight the journal row's nutrients are for; null when it was never recorded. */
    val referenceGrams: Float?,
    /** [FoodPortions] JSON; the decoder also accepts the original colon-separated shorthand. */
    val portions: String,
    val fiberGrams: Float?,
    val kcal: Float?,
    val brand: String?,
    val barcode: String?,
    /** The key the food had on the shelf it was taken from, so it is recognised there again. */
    val sourceKey: String?,
    val isLiquid: Boolean,
    /** Saved on purpose. False for a food that is only remembered because it was eaten. */
    val kept: Boolean,
    val useCount: Int,
    val lastUsedAt: Long,
    val lastQuantity: Float?,
    val lastPortionIndex: Int?,
    @ColumnInfo(defaultValue = "1") val hasOwnAbsorption: Boolean = true
)

/** Durable intent: replaying either half after interruption is idempotent. */
@Entity(tableName = "pending_food_write")
data class PendingFoodWrite(
    @PrimaryKey val operationId: Int = 1,
    @Embedded(prefix = "food_") val food: JournalFoodEntity,
    val detailsJson: String?
)

@Dao
interface FoodDetailsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun stage(write: PendingFoodWrite)

    @Query("SELECT * FROM pending_food_write WHERE operationId = 1")
    suspend fun pending(): PendingFoodWrite?

    @Query("DELETE FROM pending_food_write")
    suspend fun clearPending()

    @Transaction
    suspend fun finish(write: PendingFoodWrite) {
        val details = FoodDetailsBackup.fromJson(write.detailsJson?.let(::JSONArray)).singleOrNull()
        if (details == null) delete(write.food.id) else upsert(details)
        clearPending()
    }

    @Query("SELECT * FROM food_details")
    fun observeAll(): Flow<List<FoodDetailsEntity>>

    @Query("SELECT * FROM food_details")
    suspend fun getAll(): List<FoodDetailsEntity>

    @Query("SELECT * FROM food_details WHERE foodId = :foodId LIMIT 1")
    suspend fun get(foodId: Long): FoodDetailsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(details: FoodDetailsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(details: List<FoodDetailsEntity>)

    @Query("DELETE FROM food_details WHERE foodId = :foodId")
    suspend fun delete(foodId: Long)

    @Query("DELETE FROM food_details")
    suspend fun deleteAll()
}

/**
 * The food library's own database, apart from the glucose history.
 *
 * Apart because the history database carries every reading a person has, a long migration
 * chain, and builds that cannot open a newer version of it. Nothing about a food library is
 * worth a version bump there: with this file a build without the library still opens the
 * history, and still finds every food in `journal_foods` as the preset it used to be.
 */
@Database(entities = [FoodDetailsEntity::class, PendingFoodWrite::class], version = FOOD_LIBRARY_DATABASE_VERSION, exportSchema = true)
abstract class FoodLibraryDatabase : RoomDatabase() {
    abstract fun foodDetailsDao(): FoodDetailsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_details ADD COLUMN hasOwnAbsorption INTEGER NOT NULL DEFAULT 1")
                // Version 1 only knew this distinction for remembered shelf foods.
                db.execSQL("UPDATE food_details SET hasOwnAbsorption = 0 WHERE kept = 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_food_write (operationId INTEGER NOT NULL, food_id INTEGER NOT NULL, food_displayName TEXT NOT NULL, food_carbsGrams REAL NOT NULL, food_proteinGrams REAL, food_fatGrams REAL, food_absorptionMinutes INTEGER NOT NULL, food_accentColor INTEGER NOT NULL, food_isBuiltIn INTEGER NOT NULL, food_isArchived INTEGER NOT NULL, food_sortOrder INTEGER NOT NULL, food_createdAt INTEGER NOT NULL, food_updatedAt INTEGER NOT NULL, detailsJson TEXT, PRIMARY KEY(operationId))")
            }
        }

        private const val FILE_NAME = "food_library.db"

        @Volatile
        private var instance: FoodLibraryDatabase? = null

        fun getInstance(context: Context): FoodLibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, FoodLibraryDatabase::class.java, FILE_NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}

/** [FoodDetailsEntity] in the settings backup, beside the journal's own food rows. */
object FoodDetailsBackup {
    const val KEY = "foodDetails"

    fun toJson(details: List<FoodDetailsEntity>): JSONArray = JSONArray().also { array ->
        details.forEach { row ->
            val item = JSONObject()
                .put("foodId", row.foodId)
                .put("portions", row.portions)
                .put("isLiquid", row.isLiquid)
                .put("kept", row.kept)
                .put("hasOwnAbsorption", row.hasOwnAbsorption)
                .put("useCount", row.useCount)
                .put("lastUsedAt", row.lastUsedAt)
            row.referenceGrams?.let { item.put("referenceGrams", it.toDouble()) }
            row.fiberGrams?.let { item.put("fiberGrams", it.toDouble()) }
            row.kcal?.let { item.put("kcal", it.toDouble()) }
            row.brand?.let { item.put("brand", it) }
            row.barcode?.let { item.put("barcode", it) }
            row.sourceKey?.let { item.put("sourceKey", it) }
            row.lastQuantity?.let { item.put("lastQuantity", it.toDouble()) }
            row.lastPortionIndex?.let { item.put("lastPortionIndex", it) }
            array.put(item)
        }
    }

    fun fromJson(array: JSONArray?): List<FoodDetailsEntity> {
        if (array == null) return emptyList()
        fun JSONObject.float(key: String): Float? =
            if (has(key) && !isNull(key)) optDouble(key).toFloat().takeIf { it.isFinite() } else null
        fun JSONObject.text(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val foodId = item.optLong("foodId", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            FoodDetailsEntity(
                foodId = foodId,
                referenceGrams = item.float("referenceGrams")?.takeIf { it > 0f },
                portions = FoodPortions.encode(FoodPortions.decode(item.optString("portions"))),
                fiberGrams = item.float("fiberGrams"),
                kcal = item.float("kcal"),
                brand = item.text("brand"),
                barcode = item.text("barcode"),
                sourceKey = item.text("sourceKey"),
                isLiquid = item.optBoolean("isLiquid", false),
                kept = item.optBoolean("kept", true),
                useCount = item.optInt("useCount", 0),
                lastUsedAt = item.optLong("lastUsedAt", 0L),
                lastQuantity = item.float("lastQuantity"),
                lastPortionIndex = if (item.has("lastPortionIndex")) item.optInt("lastPortionIndex") else null,
                hasOwnAbsorption = item.optBoolean("hasOwnAbsorption", item.optBoolean("kept", true))
            )
        }
    }
}

/** The portions of a food as one line of text, for the database and for import and export. */
object FoodPortions {
    fun encode(portions: List<FoodPortion>): String = JSONArray().also { array ->
        portions.forEach { portion ->
            array.put(JSONObject().put("kind", portion.kind.storageValue).put("grams", portion.grams.toDouble())
                .also { item -> portion.label?.let { item.put("label", it) } })
        }
    }.toString()

    fun decode(text: String?): List<FoodPortion> {
        if (text.orEmpty().trimStart().startsWith("[")) {
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val kind = FoodPortionKind.fromStorage(item.optString("kind")) ?: return@mapNotNull null
                val grams = item.optDouble("grams").toFloat().takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
                FoodPortion(kind, grams, if (item.has("label")) item.optString("label") else null)
            }
        }
        return text.orEmpty().split(',').mapNotNull { part ->
            val fields = part.trim().split(':', limit = 3)
            val kind = FoodPortionKind.fromStorage(fields.getOrNull(0)) ?: return@mapNotNull null
            val grams = fields.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
            FoodPortion(kind, grams, fields.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}
