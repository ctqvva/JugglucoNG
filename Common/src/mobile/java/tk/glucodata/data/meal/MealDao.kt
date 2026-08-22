package tk.glucodata.data.meal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {
    @Query("SELECT * FROM meals ORDER BY (archivedAt IS NULL) DESC, updatedAt DESC, id DESC")
    fun observeMeals(): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE id = :id LIMIT 1")
    fun observeMeal(id: Long): Flow<MealEntity?>

    @Query("SELECT * FROM meals WHERE id = :id LIMIT 1")
    suspend fun getMeal(id: Long): MealEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeal(meal: MealEntity): Long

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun deleteMeal(id: Long)

    @Query("SELECT * FROM meal_items WHERE mealId = :mealId ORDER BY position ASC, id ASC")
    fun observeItems(mealId: Long): Flow<List<MealItemEntity>>

    @Query("SELECT * FROM meal_items ORDER BY mealId ASC, position ASC, id ASC")
    fun observeAllItems(): Flow<List<MealItemEntity>>

    @Query("SELECT * FROM meal_items WHERE mealId = :mealId ORDER BY position ASC, id ASC")
    suspend fun getItems(mealId: Long): List<MealItemEntity>

    @Query("SELECT * FROM meal_items WHERE id = :id LIMIT 1")
    suspend fun getItem(id: Long): MealItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: MealItemEntity): Long

    @Query("DELETE FROM meal_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("DELETE FROM meal_items WHERE mealId = :mealId")
    suspend fun deleteItemsForMeal(mealId: Long)

    @Query("SELECT * FROM meal_products WHERE barcode = :barcode LIMIT 1")
    suspend fun getProduct(barcode: String): MealProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProduct(product: MealProductEntity)

    @Query("UPDATE meal_products SET lastUsedAt = :usedAt WHERE barcode = :barcode")
    suspend fun touchProduct(barcode: String, usedAt: Long)

    @Query("SELECT * FROM meal_products ORDER BY lastUsedAt DESC LIMIT 200")
    fun observeProducts(): Flow<List<MealProductEntity>>

    @Query("SELECT * FROM meal_items WHERE barcode IS NULL ORDER BY updatedAt DESC LIMIT 200")
    fun observeManualItems(): Flow<List<MealItemEntity>>
}
