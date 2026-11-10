package tk.glucodata.data.food

import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tk.glucodata.data.journal.JournalDao
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.data.journal.JournalFoodUse

/**
 * The person's foods: the journal's `journal_foods` rows joined with what [FoodLibraryDatabase]
 * knows about each.
 *
 * Besides the foods saved on purpose it remembers the ones that were eaten, with the amount,
 * so the next meal starts from "what I had last time" instead of from a search.
 */
class FoodLibrary(
    private val journalDao: JournalDao,
    private val detailsDao: FoodDetailsDao
) {
    fun observe(): Flow<List<JournalFood>> =
        journalDao.observeFoods().combine(detailsDao.observeAll()) { foods, details ->
            val byFood = details.associateBy { it.foodId }
            foods.map { it.toModel(byFood[it.id]) }
        }

    suspend fun foods(): List<JournalFood> {
        val byFood = detailsDao.getAll().associateBy { it.foodId }
        return journalDao.getFoods().map { it.toModel(byFood[it.id]) }
    }

    suspend fun upsert(input: JournalFoodInput, now: Long = System.currentTimeMillis()): Long {
        val existing = input.id?.let { journalDao.getFoodById(it) }
        val foodId = journalDao.upsertFood(
            JournalFoodEntity(
                id = existing?.id ?: 0L,
                displayName = input.displayName.trim(),
                carbsGrams = input.carbsGrams.coerceAtLeast(0f),
                proteinGrams = input.proteinGrams?.coerceAtLeast(0f),
                fatGrams = input.fatGrams?.coerceAtLeast(0f),
                absorptionMinutes = input.absorptionMinutes.coerceIn(15, 480),
                accentColor = input.accentColor,
                isBuiltIn = existing?.isBuiltIn ?: input.isBuiltIn,
                isArchived = input.isArchived,
                sortOrder = input.sortOrder,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        ).let { rowId -> existing?.id ?: rowId }
        val old = detailsDao.get(foodId)
        val portions = input.portions.filter { it.grams > 0f }
        detailsDao.upsert(
            FoodDetailsEntity(
                foodId = foodId,
                referenceGrams = input.referenceGrams?.takeIf { it > 0f },
                portions = FoodPortions.encode(portions),
                fiberGrams = input.fiberGrams,
                kcal = input.kcal,
                brand = input.brand?.trim()?.takeIf { it.isNotEmpty() },
                barcode = input.barcode,
                sourceKey = input.sourceKey ?: old?.sourceKey,
                isLiquid = input.isLiquid,
                kept = input.kept,
                useCount = old?.useCount ?: 0,
                lastUsedAt = old?.lastUsedAt ?: 0L,
                // An amount counted in a portion that the edit removed or moved means nothing now.
                lastQuantity = old?.lastQuantity?.takeIf { old.portions == FoodPortions.encode(portions) },
                lastPortionIndex = old?.lastPortionIndex?.takeIf { old.portions == FoodPortions.encode(portions) }
            )
        )
        return foodId
    }

    /**
     * Adds [inputs] as saved foods. A food whose name the library already has is replaced in
     * place, keeping its colour, its position and what the library learnt about its use.
     *
     * @return how many were new, and how many replaced a food.
     */
    suspend fun import(inputs: List<JournalFoodInput>): Pair<Int, Int> {
        val byName = journalDao.getFoods().associateByTo(HashMap()) { it.displayName.trim().lowercase() }
        var nextSortOrder = (byName.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
        var added = 0
        var replaced = 0
        inputs.forEach { input ->
            val existing = byName[input.displayName.trim().lowercase()]
            if (existing != null) {
                upsert(
                    input.copy(
                        id = existing.id,
                        accentColor = existing.accentColor,
                        sortOrder = existing.sortOrder,
                        isArchived = existing.isArchived
                    )
                )
                replaced++
            } else {
                val id = upsert(input.copy(id = null, sortOrder = nextSortOrder++))
                journalDao.getFoodById(id)?.let { byName[it.displayName.trim().lowercase()] = it }
                added++
            }
        }
        return added to replaced
    }

    suspend fun delete(foodId: Long) {
        journalDao.deleteFoodById(foodId)
        detailsDao.delete(foodId)
    }

    /**
     * Records that [uses] were eaten and returns the library id of each, in order. A food the
     * library has not seen is added as remembered, not as saved; the oldest remembered foods
     * make room once there are more than [REMEMBERED_LIMIT].
     */
    suspend fun noteUses(uses: List<JournalFoodUse>, now: Long = System.currentTimeMillis()): List<Long> {
        if (uses.isEmpty()) return emptyList()
        var rows = journalDao.getFoods()
        val details = detailsDao.getAll().associateByTo(HashMap()) { it.foodId }
        val ids = uses.map { use ->
            val food = use.food
            val existing = findExisting(food, rows, details)
            val foodId = existing?.id ?: journalDao.upsertFood(
                JournalFoodEntity(
                    displayName = food.name.trim().take(MAX_NAME_LENGTH),
                    carbsGrams = food.carbs.coerceAtLeast(0f),
                    proteinGrams = food.protein?.coerceAtLeast(0f),
                    fatGrams = food.fat?.coerceAtLeast(0f),
                    absorptionMinutes = food.absorptionMinutes
                        ?: FoodMath.estimatedAbsorptionMinutes(food.carbs, food.protein, food.fat),
                    accentColor = DEFAULT_ACCENT_COLOR,
                    isBuiltIn = false,
                    isArchived = false,
                    sortOrder = (rows.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    createdAt = now,
                    updatedAt = now
                )
            ).also { rows = journalDao.getFoods() }
            val old = details[foodId]
            val base = old ?: FoodDetailsEntity(
                foodId = foodId,
                referenceGrams = if (existing != null) null else food.referenceGrams?.takeIf { it > 0f },
                portions = if (existing != null) "" else FoodPortions.encode(food.portions),
                fiberGrams = food.fiber,
                kcal = food.kcal,
                brand = food.brand,
                barcode = food.barcode,
                sourceKey = food.key.takeIf { existing == null && food.source != FoodSource.CUSTOM },
                isLiquid = food.isLiquid,
                // An old preset is the person's own; so is a food they typed in from a label.
                kept = existing != null || food.source == FoodSource.CUSTOM,
                useCount = 0,
                lastUsedAt = 0L,
                lastQuantity = null,
                lastPortionIndex = null
            )
            val updated = base.copy(
                useCount = base.useCount + 1,
                lastUsedAt = now,
                lastQuantity = use.amount.quantity.takeIf { it.isFinite() && it > 0f },
                lastPortionIndex = use.amount.portionIndex
            )
            detailsDao.upsert(updated)
            details[foodId] = updated
            foodId
        }
        details.values
            .filter { !it.kept }
            .sortedByDescending { it.lastUsedAt }
            .drop(REMEMBERED_LIMIT)
            // Whatever the clock says, a food of the meal being saved is not the one to forget.
            .filter { it.foodId !in ids }
            .forEach { delete(it.foodId) }
        return ids
    }

    private fun findExisting(
        food: FoodItem,
        rows: List<JournalFoodEntity>,
        details: Map<Long, FoodDetailsEntity>
    ): JournalFoodEntity? {
        food.libraryId?.let { id -> rows.firstOrNull { it.id == id } }?.let { return it }
        if (food.source != FoodSource.CUSTOM) {
            details.values.firstOrNull { it.sourceKey == food.key }
                ?.let { match -> rows.firstOrNull { it.id == match.foodId } }
                ?.let { return it }
        }
        food.barcode?.let { barcode ->
            details.values.firstOrNull { it.barcode == barcode }
                ?.let { match -> rows.firstOrNull { it.id == match.foodId } }
                ?.let { return it }
        }
        if (food.source != FoodSource.CUSTOM) return null
        // Typed in again with the same name and numbers: the same food, not a second copy.
        return rows.firstOrNull { row ->
            row.displayName.equals(food.name.trim(), ignoreCase = true) &&
                abs(row.carbsGrams - food.carbs) < 0.05f &&
                details[row.id]?.referenceGrams == food.referenceGrams
        }
    }

    companion object {
        const val REMEMBERED_LIMIT = 60
        const val MAX_NAME_LENGTH = 120

        /** The journal's own colour for food; a food gets another only when the person picks one. */
        const val DEFAULT_ACCENT_COLOR = 0xFF5F8B5D.toInt()

        fun JournalFoodEntity.toModel(details: FoodDetailsEntity?): JournalFood = JournalFood(
            id = id,
            displayName = displayName,
            carbsGrams = carbsGrams,
            proteinGrams = proteinGrams,
            fatGrams = fatGrams,
            absorptionMinutes = absorptionMinutes,
            accentColor = accentColor,
            isBuiltIn = isBuiltIn,
            isArchived = isArchived,
            sortOrder = sortOrder,
            referenceGrams = details?.referenceGrams?.takeIf { it > 0f },
            portions = FoodPortions.decode(details?.portions),
            fiberGrams = details?.fiberGrams,
            kcal = details?.kcal,
            brand = details?.brand,
            barcode = details?.barcode,
            sourceKey = details?.sourceKey,
            isLiquid = details?.isLiquid ?: false,
            kept = details?.kept ?: true,
            useCount = details?.useCount ?: 0,
            lastUsedAt = details?.lastUsedAt ?: 0L,
            lastAmount = details?.lastQuantity?.let { quantity ->
                FoodAmount(quantity, details.lastPortionIndex ?: FoodAmount.BASE_UNIT)
            },
            hasOwnAbsorption = details == null || details.kept
        )
    }
}
