package tk.glucodata.data.meal

data class Meal(
    val id: Long,
    val label: String,
    val servings: Float?,
    val cookedWeightGrams: Float?,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?
) {
    val isArchived: Boolean get() = archivedAt != null
    val reference: NutritionReference get() = NutritionReference.forMeal(servings, cookedWeightGrams)
}

data class MealItem(
    val id: Long,
    val mealId: Long,
    val position: Int,
    val barcode: String?,
    val source: NutritionSource,
    val displayName: String,
    val brand: String?,
    val facts: NutritionFacts,
    val reference: NutritionReference,
    val quantityText: String,
    val factor: Float?,
    val amountGrams: Float?,
    val amountMilliliters: Float?,
    val plausibility: Set<NutritionPlausibilityFlag>
) {
    val isResolved: Boolean get() = factor != null
    /** Nutrition of the amount actually used in the meal, or null while unresolved. */
    val contribution: NutritionFacts? get() = factor?.let(facts::scaled)

    fun toScannedProduct(): ScannedProduct = ScannedProduct(
        barcode = barcode,
        displayName = displayName,
        brand = brand,
        source = source,
        facts = facts,
        reference = reference
    )
}

/** Sums of a meal's resolved items, plus how many items could not be counted. */
data class MealTotals(
    val facts: NutritionFacts,
    val resolvedItems: Int,
    val unresolvedItems: Int
) {
    val isComplete: Boolean get() = unresolvedItems == 0
}

object MealMath {
    fun totals(items: List<MealItem>): MealTotals {
        var sum = NutritionFacts.ZERO
        var resolved = 0
        var unresolved = 0
        for (item in items) {
            val contribution = item.contribution
            if (contribution == null) {
                unresolved++
            } else {
                sum += contribution
                resolved++
            }
        }
        return MealTotals(
            facts = if (resolved == 0) NutritionFacts.ZERO.copy(proteinGrams = null, fatGrams = null) else sum,
            resolvedItems = resolved,
            unresolvedItems = unresolved
        )
    }

    /** Per-portion facts when the cook has said how many portions the batch is. */
    fun perServing(totals: MealTotals, servings: Float?): NutritionFacts? {
        val count = servings?.takeIf { it > 0f } ?: return null
        return totals.facts.scaled(1f / count)
    }
}

internal fun MealEntity.toModel(): Meal = Meal(
    id = id,
    label = label,
    servings = servings,
    cookedWeightGrams = cookedWeightGrams,
    createdAt = createdAt,
    updatedAt = updatedAt,
    archivedAt = archivedAt
)

internal fun MealItemEntity.toModel(): MealItem = MealItem(
    id = id,
    mealId = mealId,
    position = position,
    barcode = barcode,
    source = NutritionSource.fromStorage(source),
    displayName = displayName,
    brand = brand,
    facts = NutritionFacts(
        carbsGrams = carbsGrams,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        fiberGrams = fiberGrams,
        sugarsGrams = sugarsGrams,
        polyolsGrams = polyolsGrams,
        kcal = kcal
    ),
    reference = NutritionReference(
        basis = NutritionBasis.fromStorage(basis),
        netQuantity = netQuantity,
        netUnit = AmountUnit.fromStorage(netUnit),
        servingText = servingText,
        servingQuantity = servingQuantity,
        servingUnit = AmountUnit.fromStorage(servingUnit),
        servingPieces = servingPieces,
        servingPieceLabel = servingPieceLabel,
        servingsPerBatch = servingsPerBatch,
        densityGramsPerMl = densityGramsPerMl,
        pieceGrams = pieceGrams
    ),
    quantityText = quantityText,
    factor = factor,
    amountGrams = amountGrams,
    amountMilliliters = amountMilliliters,
    plausibility = NutritionPlausibilityFlag.decode(plausibilityFlags)
)

internal fun MealProductEntity.toScannedProduct(): ScannedProduct = ScannedProduct(
    barcode = barcode,
    displayName = displayName,
    brand = brand,
    source = NutritionSource.fromStorage(source),
    facts = NutritionFacts(
        carbsGrams = carbsGrams,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        fiberGrams = fiberGrams,
        sugarsGrams = sugarsGrams,
        polyolsGrams = polyolsGrams,
        kcal = kcal
    ),
    reference = NutritionReference(
        basis = NutritionBasis.fromStorage(basis),
        netQuantity = netQuantity,
        netUnit = AmountUnit.fromStorage(netUnit),
        servingText = servingText,
        servingQuantity = servingQuantity,
        servingUnit = AmountUnit.fromStorage(servingUnit),
        servingPieces = servingPieces,
        servingPieceLabel = servingPieceLabel,
        densityGramsPerMl = densityGramsPerMl,
        pieceGrams = pieceGrams
    )
)

internal fun ScannedProduct.toProductEntity(barcode: String, now: Long, existing: MealProductEntity?): MealProductEntity =
    MealProductEntity(
        barcode = barcode,
        source = source.storageValue,
        displayName = displayName,
        brand = brand,
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
        servingText = reference.servingText,
        servingQuantity = reference.servingQuantity,
        servingUnit = reference.servingUnit?.storageValue,
        servingPieces = reference.servingPieces,
        servingPieceLabel = reference.servingPieceLabel,
        // Learned answers survive a refresh from the source.
        densityGramsPerMl = reference.densityGramsPerMl ?: existing?.densityGramsPerMl,
        pieceGrams = reference.pieceGrams ?: existing?.pieceGrams,
        plausibilityFlags = NutritionPlausibilityFlag.encode(plausibility),
        fetchedAt = now,
        lastUsedAt = now
    )
