package tk.glucodata.data.meal

import kotlinx.coroutines.Dispatchers
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.data.HistoryDatabase

/**
 * Meals, their items, and the product cache. Nothing here writes a journal entry: eating is
 * recorded through the journal repository with a `mealId`, which keeps the journal the single
 * log of facts.
 */
class MealRepository {
    private val database = HistoryDatabase.getInstance(Applic.app)
    private val dao = database.mealDao()

    fun observeMeals(): Flow<List<Meal>> = dao.observeMeals().map { rows -> rows.map(MealEntity::toModel) }

    fun observeCurrentMeal(): Flow<Meal?> = dao.observeCurrentMeal().map { it?.toModel() }

    fun observeMeal(id: Long): Flow<Meal?> = dao.observeMeal(id).map { it?.toModel() }

    fun observeItems(mealId: Long): Flow<List<MealItem>> =
        dao.observeItems(mealId).map { rows -> rows.map(MealItemEntity::toModel) }

    fun observeAllItems(): Flow<List<MealItem>> =
        dao.observeAllItems().map { rows -> rows.map(MealItemEntity::toModel) }

    suspend fun getMeal(id: Long): Meal? = dao.getMeal(id)?.toModel()

    suspend fun getItems(mealId: Long): List<MealItem> = dao.getItems(mealId).map(MealItemEntity::toModel)

    suspend fun createMeal(label: String): Long {
        val now = System.currentTimeMillis()
        return dao.upsertMeal(
            MealEntity(
                label = label.trim(),
                servings = null,
                cookedWeightGrams = null,
                createdAt = now,
                updatedAt = now,
                archivedAt = null
            )
        )
    }

    suspend fun updateMeal(
        id: Long,
        label: String? = null,
        servings: Float? = null,
        clearServings: Boolean = false,
        cookedWeightGrams: Float? = null,
        clearCookedWeight: Boolean = false
    ) {
        val existing = dao.getMeal(id) ?: return
        dao.upsertMeal(
            existing.copy(
                label = label?.trim()?.takeIf { it.isNotEmpty() } ?: existing.label,
                servings = when {
                    clearServings -> null
                    servings != null -> servings.takeIf { it > 0f }
                    else -> existing.servings
                },
                cookedWeightGrams = when {
                    clearCookedWeight -> null
                    cookedWeightGrams != null -> cookedWeightGrams.takeIf { it > 0f }
                    else -> existing.cookedWeightGrams
                },
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val existing = dao.getMeal(id) ?: return
        val now = System.currentTimeMillis()
        dao.upsertMeal(existing.copy(archivedAt = if (archived) now else null, updatedAt = now))
    }

    suspend fun deleteMeal(id: Long) {
        dao.deleteItemsForMeal(id)
        dao.deleteMeal(id)
    }

    /**
     * Adds a product to a meal with the amount the user typed and the resolution the UI already
     * computed (or null while a reference value is still missing).
     */
    suspend fun addItem(
        mealId: Long,
        product: ScannedProduct,
        quantityText: String,
        resolution: QuantityResolution.Resolved?
    ): Long {
        val now = System.currentTimeMillis()
        val position = dao.getItems(mealId).size
        val id = dao.upsertItem(
            product.toItemEntity(
                id = 0L,
                mealId = mealId,
                position = position,
                quantityText = quantityText,
                resolution = resolution,
                createdAt = now,
                updatedAt = now
            )
        )
        touchMeal(mealId, now)
        return id
    }

    suspend fun updateItem(
        itemId: Long,
        product: ScannedProduct,
        quantityText: String,
        resolution: QuantityResolution.Resolved?
    ) {
        val existing = dao.getItem(itemId) ?: return
        val now = System.currentTimeMillis()
        dao.upsertItem(
            product.toItemEntity(
                id = existing.id,
                mealId = existing.mealId,
                position = existing.position,
                quantityText = quantityText,
                resolution = resolution,
                createdAt = existing.createdAt,
                updatedAt = now
            )
        )
        touchMeal(existing.mealId, now)
    }

    suspend fun deleteItem(itemId: Long) {
        val existing = dao.getItem(itemId) ?: return
        dao.deleteItem(itemId)
        touchMeal(existing.mealId, System.currentTimeMillis())
    }

    private suspend fun touchMeal(mealId: Long, now: Long) {
        dao.getMeal(mealId)?.let { dao.upsertMeal(it.copy(updatedAt = now)) }
    }

    /**
     * Everything used before, most recent first: the product cache (scanned, label-read or typed
     * with a barcode) and, once per name, manual items without a barcode from earlier meals.
     */
    fun observeRecentProducts(): Flow<List<ScannedProduct>> =
        combine(dao.observeProducts(), dao.observeManualItems()) { products, manual ->
            val cached = products.map { it.toScannedProduct() }
            val seenManual = HashSet<String>()
            val manualProducts = manual.mapNotNull { item ->
                val key = "${item.displayName}|${item.brand.orEmpty()}".lowercase(Locale.ROOT)
                if (!seenManual.add(key)) null else item.toModel().toScannedProduct()
            }
            cached + manualProducts
        }

    /** Marks a cached product as used now, so the recent list keeps the cupboard regulars on top. */
    suspend fun touchProduct(barcode: String) {
        dao.touchProduct(barcode, System.currentTimeMillis())
    }

    /** The cached product for a barcode, if any; the cache is also where learned answers live. */
    suspend fun cachedProduct(barcode: String): ScannedProduct? = dao.getProduct(barcode)?.toScannedProduct()

    suspend fun cachedProductAge(barcode: String, now: Long = System.currentTimeMillis()): Long? =
        dao.getProduct(barcode)?.let { now - it.fetchedAt }

    /**
     * Cache-first lookup. A hit younger than [maxAgeMillis] is returned as is; an older one is
     * refreshed when the network answers and kept when it does not. Only the barcode leaves the
     * device, and only when [allowNetwork] says so.
     */
    suspend fun lookupProduct(
        barcode: String,
        allowNetwork: Boolean,
        maxAgeMillis: Long = DEFAULT_CACHE_MAX_AGE_MS
    ): ProductLookupOutcome = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = dao.getProduct(barcode)
        if (cached != null && now - cached.fetchedAt <= maxAgeMillis) {
            dao.touchProduct(barcode, now)
            return@withContext ProductLookupOutcome.Hit(cached.toScannedProduct(), fromCache = true)
        }
        if (!allowNetwork) {
            return@withContext if (cached != null) {
                dao.touchProduct(barcode, now)
                ProductLookupOutcome.Hit(cached.toScannedProduct(), fromCache = true)
            } else {
                ProductLookupOutcome.Offline
            }
        }
        // A recent miss is answered from memory: OFF allows 15 product reads a minute per IP,
        // and a code that is not in the database will not appear there in the next ten minutes.
        synchronized(recentMisses) {
            val missedAt = recentMisses[barcode]
            if (missedAt != null && now - missedAt < NEGATIVE_CACHE_MS) {
                return@withContext if (cached != null) {
                    ProductLookupOutcome.Hit(cached.toScannedProduct(), fromCache = true)
                } else {
                    ProductLookupOutcome.NotFound
                }
            }
        }
        when (val result = OpenFoodFactsClient.lookup(barcode)) {
            is ProductLookupResult.Found -> {
                val entity = result.product.toProductEntity(
                    barcode,
                    now,
                    cached,
                    preserveExistingPackageContents = true
                )
                dao.upsertProduct(entity)
                synchronized(recentMisses) { recentMisses.remove(barcode) }
                ProductLookupOutcome.Hit(entity.toScannedProduct(), fromCache = false)
            }
            ProductLookupResult.NotFound -> {
                synchronized(recentMisses) { recentMisses[barcode] = now }
                if (cached != null) ProductLookupOutcome.Hit(cached.toScannedProduct(), fromCache = true)
                else ProductLookupOutcome.NotFound
            }
            is ProductLookupResult.Failed -> {
                if (cached != null) ProductLookupOutcome.Hit(cached.toScannedProduct(), fromCache = true)
                else ProductLookupOutcome.Failed(result.message)
            }
        }
    }

    /** Stores a manually entered or corrected product so the barcode resolves next time. */
    suspend fun rememberProduct(barcode: String, product: ScannedProduct) {
        synchronized(recentMisses) { recentMisses.remove(barcode) }
        val now = System.currentTimeMillis()
        val existing = dao.getProduct(barcode)
        dao.upsertProduct(product.toProductEntity(barcode, existing?.fetchedAt ?: now, existing).copy(lastUsedAt = now))
    }

    /** Records a successful upload to Open Food Facts, so the sheet can say so and offer "send again". */
    suspend fun markContributed(barcode: String, at: Long = System.currentTimeMillis()) {
        val existing = dao.getProduct(barcode) ?: return
        dao.upsertProduct(existing.copy(contributedAt = at))
    }

    /** Writes a learned reference value (density, piece weight, net weight) back to the preset. */
    suspend fun learnReference(barcode: String, reference: NutritionReference) {
        val existing = dao.getProduct(barcode) ?: return
        dao.upsertProduct(
            existing.copy(
                densityGramsPerMl = reference.densityGramsPerMl ?: existing.densityGramsPerMl,
                pieceGrams = reference.pieceGrams ?: existing.pieceGrams,
                netQuantity = reference.netQuantity ?: existing.netQuantity,
                netUnit = reference.netUnit?.storageValue ?: existing.netUnit,
                packagePieces = reference.packagePieces ?: existing.packagePieces,
                packagePieceLabel = reference.packagePieceLabel ?: existing.packagePieceLabel,
                packagePiecesUserEdited = reference.packagePiecesUserEdited || existing.packagePiecesUserEdited,
                servingQuantity = reference.servingQuantity ?: existing.servingQuantity,
                servingUnit = reference.servingUnit?.storageValue ?: existing.servingUnit,
                lastUsedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val DEFAULT_CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val NEGATIVE_CACHE_MS = 10L * 60 * 1000

        /** Barcodes that Open Food Facts did not know, with the time of the answer; per process. */
        private val recentMisses = HashMap<String, Long>()
    }
}

sealed class ProductLookupOutcome {
    data class Hit(val product: ScannedProduct, val fromCache: Boolean) : ProductLookupOutcome()
    object NotFound : ProductLookupOutcome()
    object Offline : ProductLookupOutcome()
    data class Failed(val message: String?) : ProductLookupOutcome()
}

private fun ScannedProduct.toItemEntity(
    id: Long,
    mealId: Long,
    position: Int,
    quantityText: String,
    resolution: QuantityResolution.Resolved?,
    createdAt: Long,
    updatedAt: Long
): MealItemEntity = MealItemEntity(
    id = id,
    mealId = mealId,
    position = position,
    barcode = barcode,
    source = source.storageValue,
    displayName = displayName.trim(),
    brand = brand?.trim()?.takeIf { it.isNotEmpty() },
    basis = reference.basis.storageValue,
    carbsGrams = facts.carbsGrams,
    proteinGrams = facts.proteinGrams,
    fatGrams = facts.fatGrams,
    fiberGrams = facts.fiberGrams,
    sugarsGrams = facts.sugarsGrams,
    polyolsGrams = facts.polyolsGrams,
    kcal = facts.kcal,
    netQuantity = reference.netQuantity,
    netUnit = reference.netUnit?.storageValue,
    packagePieces = reference.packagePieces,
    packagePieceLabel = reference.packagePieceLabel,
    packagePiecesUserEdited = reference.packagePiecesUserEdited,
    servingText = reference.servingText,
    servingQuantity = reference.servingQuantity,
    servingUnit = reference.servingUnit?.storageValue,
    servingPieces = reference.servingPieces,
    servingPieceLabel = reference.servingPieceLabel,
    servingsPerBatch = reference.servingsPerBatch,
    densityGramsPerMl = reference.densityGramsPerMl,
    pieceGrams = reference.pieceGrams,
    quantityText = quantityText.trim(),
    factor = resolution?.factor,
    amountGrams = resolution?.grams,
    amountMilliliters = resolution?.milliliters,
    plausibilityFlags = NutritionPlausibilityFlag.encode(plausibility),
    createdAt = createdAt,
    updatedAt = updatedAt
)
