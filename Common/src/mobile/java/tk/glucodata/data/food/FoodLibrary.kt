package tk.glucodata.data.food

import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tk.glucodata.data.journal.JournalDao
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.data.journal.JournalFoodUse

/** Exact undo data, including portions and remembered quantities. */
data class DeletedFoods(val rows: List<JournalFoodEntity>, val details: List<FoodDetailsEntity>)

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
        journalDao.observeFoods().combine(detailsDao.observeAll()) { _, _ -> foods() }

    suspend fun foods(): List<JournalFood> = writes.withLock {
        recoverWrite()
        val byFood = detailsDao.getAll().associateBy { it.foodId }
        journalDao.getFoods().map { it.toModel(byFood[it.id]) }
    }

    suspend fun snapshot(): Pair<List<JournalFoodEntity>, List<FoodDetailsEntity>> = writes.withLock {
        recoverWrite()
        journalDao.getFoods() to detailsDao.getAll()
    }

    /** Acquire before a history transaction to keep every food writer in the same lock order. */
    suspend fun <T> withWrites(block: suspend () -> T): T = writes.withLock {
        recoverWrite()
        block()
    }

    /** Save a shelf product once, or keep its existing (possibly edited) library definition. */
    suspend fun keep(food: FoodItem, now: Long = System.currentTimeMillis()): Long = writes.withLock {
        recoverWrite()
        val rows = journalDao.getFoods()
        val details = detailsDao.getAll().associateBy { it.foodId }
        val existing = findExisting(food, rows, details)
        if (existing != null) {
            val old = details[existing.id]
            if (existing.isArchived || old?.kept == false) {
                write(existing.copy(isArchived = false, updatedAt = now), old?.copy(kept = true))
            }
            existing.id
        } else {
            upsertLocked(food.toLibraryInput((rows.maxOfOrNull { it.sortOrder } ?: 0) + 1), now)
        }
    }

    suspend fun upsert(input: JournalFoodInput, now: Long = System.currentTimeMillis()): Long = writes.withLock {
        recoverWrite()
        upsertLocked(input, now)
    }

    private suspend fun upsertLocked(input: JournalFoodInput, now: Long = System.currentTimeMillis()): Long {
        val existing = input.id?.let { journalDao.getFoodById(it) }
        val foodId = existing?.id ?: journalDao.nextFoodId()
        val row = JournalFoodEntity(
            id = foodId,
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
        val old = detailsDao.get(foodId)
        val portions = input.portions.filter { it.grams > 0f }
        write(
            row,
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
                hasOwnAbsorption = input.hasOwnAbsorption,
                useCount = old?.useCount ?: 0,
                lastUsedAt = old?.lastUsedAt ?: 0L,
                // An amount counted in a portion that the edit removed or moved means nothing now.
                lastQuantity = old?.lastQuantity?.takeIf { FoodPortions.decode(old.portions) == portions && (old.referenceGrams == null) == (input.referenceGrams == null) && old.isLiquid == input.isLiquid },
                lastPortionIndex = old?.lastPortionIndex?.takeIf { FoodPortions.decode(old.portions) == portions && (old.referenceGrams == null) == (input.referenceGrams == null) && old.isLiquid == input.isLiquid }
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
    suspend fun import(inputs: List<JournalFoodInput>): Pair<Int, Int> = writes.withLock {
        recoverWrite()
        val byName = journalDao.getFoods().associateByTo(HashMap()) { it.displayName.trim().lowercase(Locale.ROOT) }
        var nextSortOrder = (byName.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
        var added = 0
        var replaced = 0
        inputs.forEach { input ->
            val existing = byName[input.displayName.trim().lowercase(Locale.ROOT)]
            if (existing != null) {
                upsertLocked(
                    input.copy(
                        id = existing.id,
                        accentColor = existing.accentColor,
                        sortOrder = existing.sortOrder,
                        isArchived = existing.isArchived
                    )
                )
                replaced++
            } else {
                val id = upsertLocked(input.copy(id = null, sortOrder = nextSortOrder++))
                journalDao.getFoodById(id)?.let { byName[it.displayName.trim().lowercase(Locale.ROOT)] = it }
                added++
            }
        }
        added to replaced
    }

    suspend fun delete(foodId: Long) = writes.withLock {
        recoverWrite()
        deleteLocked(foodId)
    }

    suspend fun delete(ids: Set<Long>): DeletedFoods = writes.withLock {
        recoverWrite()
        val rows = journalDao.getFoods().filter { it.id in ids }
        val actualIds = rows.map { it.id }
        val details = detailsDao.getAll().filter { it.foodId in ids }
        // Older Android SQLite builds limit the bind parameters in an IN clause to 999.
        actualIds.chunked(500).forEach { batch ->
            journalDao.deleteFoodsByIds(batch)
            detailsDao.deleteIds(batch)
        }
        DeletedFoods(rows, details)
    }

    suspend fun undoDelete(deleted: DeletedFoods) = writes.withLock {
        recoverWrite()
        val byId = deleted.details.associateBy { it.foodId }
        deleted.rows.forEach { row ->
            // A backup restore may have filled the ID in the meantime. Undo must not replace it.
            if (journalDao.getFoodById(row.id) == null) write(row, byId[row.id])
        }
    }

    suspend fun setArchived(ids: Set<Long>, archived: Boolean, now: Long = System.currentTimeMillis()) = writes.withLock {
        recoverWrite()
        ids.toList().chunked(500).forEach { journalDao.setFoodsArchived(it, archived, now) }
    }

    /** Move the selected saved foods as one block, retaining both selected and unselected order. */
    suspend fun moveBefore(ids: Set<Long>, beforeId: Long?, now: Long = System.currentTimeMillis()) = writes.withLock {
        recoverWrite()
        val details = detailsDao.getAll().associateBy { it.foodId }
        val mine = journalDao.getFoods().filter { !it.isArchived && details[it.id]?.kept != false }
        val moving = mine.filter { it.id in ids }
        val remaining = mine.filter { it.id !in ids }
        if (moving.isEmpty() || beforeId in ids) return@withLock
        val target = if (beforeId == null) remaining.size else remaining.indexOfFirst { it.id == beforeId }
        if (target < 0) return@withLock // Destination removed or disabled while the chooser was open.
        val ordered = remaining.toMutableList().apply { addAll(target, moving) }
        journalDao.setFoodOrder(ordered.map { it.id }, now)
    }

    private suspend fun deleteLocked(foodId: Long) {
        journalDao.deleteFoodById(foodId)
        detailsDao.delete(foodId)
    }

    /** Restore exactly the supplied details, including their absence in older backups. */
    suspend fun restore(rows: List<JournalFoodEntity>, details: List<FoodDetailsEntity>, replaceAll: Boolean = false) = writes.withLock {
        recoverWrite()
        val byId = details.associateBy { it.foodId }
        rows.forEach { write(it, byId[it.id]) }
        if (replaceAll) {
            val ids = rows.mapTo(HashSet()) { it.id }
            journalDao.getFoods().filter { it.id !in ids }.forEach { deleteLocked(it.id) }
        }
    }

    private suspend fun write(row: JournalFoodEntity, details: FoodDetailsEntity?) {
        detailsDao.stage(PendingFoodWrite(food = row, detailsJson = details?.let { FoodDetailsBackup.toJson(listOf(it)).toString() }))
        recoverWrite()
    }

    private suspend fun recoverWrite() {
        val pending = detailsDao.pending() ?: return
        journalDao.upsertFood(pending.food)
        detailsDao.finish(pending)
    }

    /**
     * Records that [uses] were eaten and returns the library id of each, in order. A food the
     * library has not seen is added as remembered, not as saved; the oldest remembered foods
     * make room once there are more than [REMEMBERED_LIMIT].
     */
    suspend fun noteUses(uses: List<JournalFoodUse>, now: Long = System.currentTimeMillis()): List<Long> = writes.withLock {
        recoverWrite()
        if (uses.isEmpty()) return@withLock emptyList()
        var rows = journalDao.getFoods()
        val details = detailsDao.getAll().associateByTo(HashMap()) { it.foodId }
        val ids = uses.map { use ->
            val food = use.food
            val existing = findExisting(food, rows, details)
            val foodId = existing?.id ?: journalDao.nextFoodId()
            val row = if (existing != null && food.source == FoodSource.CUSTOM) existing.copy(
                carbsGrams = food.carbs, proteinGrams = food.protein, fatGrams = food.fat,
                absorptionMinutes = food.absorptionMinutes ?: FoodMath.nutrients(food, use.amount).let { FoodMath.estimatedAbsorptionMinutes(it.carbs, it.protein, it.fat) },
                updatedAt = now
            ) else existing ?: JournalFoodEntity(
                    id = foodId,
                    displayName = food.name.trim().take(MAX_NAME_LENGTH),
                    carbsGrams = food.carbs.coerceAtLeast(0f),
                    proteinGrams = food.protein?.coerceAtLeast(0f),
                    fatGrams = food.fat?.coerceAtLeast(0f),
                    absorptionMinutes = food.absorptionMinutes
                        ?: FoodMath.nutrients(food, use.amount).let { FoodMath.estimatedAbsorptionMinutes(it.carbs, it.protein, it.fat) },
                    accentColor = DEFAULT_ACCENT_COLOR,
                    isBuiltIn = false,
                    isArchived = false,
                    sortOrder = (rows.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    createdAt = now,
                    updatedAt = now
                )
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
                hasOwnAbsorption = food.absorptionMinutes != null,
                useCount = 0,
                lastUsedAt = 0L,
                lastQuantity = null,
                lastPortionIndex = null
            )
            val definition = if (food.source == FoodSource.CUSTOM) base.copy(
                referenceGrams = food.referenceGrams, portions = FoodPortions.encode(food.portions),
                fiberGrams = food.fiber, kcal = food.kcal, brand = food.brand,
                isLiquid = food.isLiquid, hasOwnAbsorption = food.absorptionMinutes != null, kept = true
            ) else base
            val updated = definition.copy(
                useCount = base.useCount + 1,
                lastUsedAt = now,
                lastQuantity = use.amount.quantity.takeIf { it.isFinite() && it > 0f },
                lastPortionIndex = use.amount.portionIndex
            )
            write(row, updated)
            rows = journalDao.getFoods()
            details[foodId] = updated
            foodId
        }
        details.values
            .filter { !it.kept }
            .sortedByDescending { it.lastUsedAt }
            .drop(REMEMBERED_LIMIT)
            // Whatever the clock says, a food of the meal being saved is not the one to forget.
            .filter { it.foodId !in ids }
            .forEach { deleteLocked(it.foodId) }
        ids
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
                row.carbsGrams == food.carbs &&
                row.proteinGrams == food.protein && row.fatGrams == food.fat &&
                details[row.id]?.fiberGrams == food.fiber && details[row.id]?.kcal == food.kcal &&
                details[row.id]?.isLiquid == food.isLiquid &&
                row.absorptionMinutes.takeIf { details[row.id]?.hasOwnAbsorption != false } == food.absorptionMinutes &&
                details[row.id]?.referenceGrams == food.referenceGrams
        }
    }

    companion object {
        // Shared across repository, transfer and restore instances.
        private val writes = Mutex()
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
            hasOwnAbsorption = details?.hasOwnAbsorption ?: true
        )
    }
}
