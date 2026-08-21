package tk.glucodata.data.meal

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A meal is composition plus, later, plans — never a consumption event. What was eaten and when
 * lives in `journal_entries` rows that point back here through `mealId`.
 */
@Entity(
    tableName = "meals",
    indices = [
        Index(value = ["archivedAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class MealEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    /** How many portions the batch was divided into; null = not decided. */
    val servings: Float?,
    /** Weight of the cooked batch, so a weighed plate can be entered in grams; optional. */
    val cookedWeightGrams: Float?,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?
)

/**
 * One product (or manual line) in a meal with a snapshot of its nutrition basis and the amount the
 * cook used. Values are copied from the product so a later cache refresh cannot rewrite history.
 */
@Entity(
    tableName = "meal_items",
    indices = [
        Index(value = ["mealId"]),
        Index(value = ["barcode"])
    ]
)
data class MealItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mealId: Long,
    val position: Int,
    val barcode: String?,
    val source: String,
    val displayName: String,
    val brand: String?,
    val basis: String,
    val carbsGrams: Float,
    val proteinGrams: Float?,
    val fatGrams: Float?,
    val fiberGrams: Float?,
    val sugarsGrams: Float?,
    val polyolsGrams: Float?,
    val kcal: Float?,
    val netQuantity: Float?,
    val netUnit: String?,
    val servingText: String?,
    val servingQuantity: Float?,
    val servingUnit: String?,
    val servingPieces: Float?,
    val servingPieceLabel: String?,
    val servingsPerBatch: Float?,
    val densityGramsPerMl: Float?,
    val pieceGrams: Float?,
    /** The amount as typed ("1/3", "2 Riegel", "70 g"); re-resolvable later. */
    val quantityText: String,
    /** Multiplier for the basis values; null while the quantity is still unresolved. */
    val factor: Float?,
    val amountGrams: Float?,
    val amountMilliliters: Float?,
    val plausibilityFlags: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Product cache keyed by barcode, and at the same time the product preset: what the database
 * said, plus what the user answered once (density, piece weight, net weight) so the next scan of
 * the same product needs no question.
 */
@Entity(
    tableName = "meal_products",
    indices = [
        Index(value = ["lastUsedAt"])
    ]
)
data class MealProductEntity(
    @PrimaryKey
    val barcode: String,
    val source: String,
    val displayName: String,
    val brand: String?,
    val basis: String,
    val carbsGrams: Float,
    val proteinGrams: Float?,
    val fatGrams: Float?,
    val fiberGrams: Float?,
    val sugarsGrams: Float?,
    val polyolsGrams: Float?,
    val kcal: Float?,
    val netQuantity: Float?,
    val netUnit: String?,
    val servingText: String?,
    val servingQuantity: Float?,
    val servingUnit: String?,
    val servingPieces: Float?,
    val servingPieceLabel: String?,
    val densityGramsPerMl: Float?,
    val pieceGrams: Float?,
    val plausibilityFlags: String?,
    val fetchedAt: Long,
    val lastUsedAt: Long
)
