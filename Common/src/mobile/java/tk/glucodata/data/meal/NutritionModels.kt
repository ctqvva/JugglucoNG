package tk.glucodata.data.meal

/**
 * What one set of nutrition numbers refers to. A product label states its values per 100 g or
 * per 100 ml, a recipe per serving, and a cooked meal as a whole batch. The quantity engine never
 * assumes a basis; it is carried explicitly so "70 g" of a per-100-ml drink asks for a density
 * instead of silently dividing by 100.
 */
enum class NutritionBasis(val storageValue: String) {
    PER_100G("per_100g"),
    PER_100ML("per_100ml"),
    PER_SERVING("per_serving"),
    PER_BATCH("per_batch");

    companion object {
        fun fromStorage(value: String?): NutritionBasis =
            entries.firstOrNull { it.storageValue == value } ?: PER_100G
    }
}

/** Where a product's numbers came from; stored with the item so later review can tell them apart. */
enum class NutritionSource(val storageValue: String) {
    OPEN_FOOD_FACTS("off"),
    MANUAL("manual"),
    MEAL("meal"),
    /** Read off a label photo on the device and confirmed by the user. */
    OCR_LABEL("ocr:label");

    companion object {
        fun fromStorage(value: String?): NutritionSource =
            entries.firstOrNull { it.storageValue == value } ?: MANUAL
    }
}

enum class AmountUnit(val storageValue: String, val symbol: String) {
    GRAM("g", "g"),
    MILLILITER("ml", "ml");

    companion object {
        fun fromStorage(value: String?): AmountUnit? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * Macros for one basis amount (100 g, 100 ml, one serving, or the whole batch). Everything but
 * carbs is optional because labels and databases often leave it out; a missing value stays null
 * rather than becoming a zero that looks measured.
 */
data class NutritionFacts(
    val carbsGrams: Float,
    val proteinGrams: Float? = null,
    val fatGrams: Float? = null,
    val fiberGrams: Float? = null,
    val sugarsGrams: Float? = null,
    val polyolsGrams: Float? = null,
    val kcal: Float? = null,
    /** Label values Open Food Facts needs for a Nutri-Score; not used by the meal math. */
    val saturatedFatGrams: Float? = null,
    val saltGrams: Float? = null
) {
    fun scaled(factor: Float): NutritionFacts = NutritionFacts(
        carbsGrams = carbsGrams * factor,
        proteinGrams = proteinGrams?.times(factor),
        fatGrams = fatGrams?.times(factor),
        fiberGrams = fiberGrams?.times(factor),
        sugarsGrams = sugarsGrams?.times(factor),
        polyolsGrams = polyolsGrams?.times(factor),
        kcal = kcal?.times(factor),
        saturatedFatGrams = saturatedFatGrams?.times(factor),
        saltGrams = saltGrams?.times(factor)
    )

    operator fun plus(other: NutritionFacts): NutritionFacts = NutritionFacts(
        carbsGrams = carbsGrams + other.carbsGrams,
        proteinGrams = addNullable(proteinGrams, other.proteinGrams),
        fatGrams = addNullable(fatGrams, other.fatGrams),
        fiberGrams = addNullable(fiberGrams, other.fiberGrams),
        sugarsGrams = addNullable(sugarsGrams, other.sugarsGrams),
        polyolsGrams = addNullable(polyolsGrams, other.polyolsGrams),
        kcal = addNullable(kcal, other.kcal),
        saturatedFatGrams = addNullable(saturatedFatGrams, other.saturatedFatGrams),
        saltGrams = addNullable(saltGrams, other.saltGrams)
    )

    companion object {
        val ZERO = NutritionFacts(carbsGrams = 0f)

        private fun addNullable(a: Float?, b: Float?): Float? = when {
            a == null && b == null -> null
            else -> (a ?: 0f) + (b ?: 0f)
        }
    }
}

/**
 * The reference sizes the quantity engine needs to turn "1/3", "2 Portionen" or "2 Riegel" into a
 * multiple of the basis amount. Every field is optional: the resolver reports exactly which one is
 * missing, the UI asks that one question, and the answer is stored back here so the next contact
 * with the same product resolves immediately.
 *
 * @param netQuantity package net content, or for a batch the cooked weight.
 * @param servingQuantity the size of one serving in [servingUnit]; OFF's `serving_quantity`.
 * @param servingPieces how many pieces one serving is, from text like "2 Scheiben (50 g)".
 * @param servingsPerBatch recipe yield / the number of portions a meal was divided into.
 * @param densityGramsPerMl needed only when a volume meets a per-100-g basis or vice versa.
 * @param pieceGrams weight of one piece when it is not derivable from the serving text.
 */
data class NutritionReference(
    val basis: NutritionBasis,
    val netQuantity: Float? = null,
    val netUnit: AmountUnit? = null,
    val servingText: String? = null,
    val servingQuantity: Float? = null,
    val servingUnit: AmountUnit? = null,
    val servingPieces: Float? = null,
    val servingPieceLabel: String? = null,
    val servingsPerBatch: Float? = null,
    val densityGramsPerMl: Float? = null,
    val pieceGrams: Float? = null
) {
    /** Weight of one piece, either learned or derived from "1 Riegel (25 g)". */
    val effectivePieceGrams: Float?
        get() {
            pieceGrams?.takeIf { it > 0f }?.let { return it }
            val pieces = servingPieces?.takeIf { it > 0f } ?: return null
            val grams = servingQuantity?.takeIf { it > 0f && servingUnit == AmountUnit.GRAM } ?: return null
            return grams / pieces
        }

    /** Milliliters of one piece for liquids sold by the glass or can ("1 portion (330 ml)"). */
    val effectivePieceMilliliters: Float?
        get() {
            val pieces = servingPieces?.takeIf { it > 0f } ?: return null
            val ml = servingQuantity?.takeIf { it > 0f && servingUnit == AmountUnit.MILLILITER } ?: return null
            return ml / pieces
        }

    companion object {
        /** A meal is one batch; portions and cooked weight are optional refinements. */
        fun forMeal(servings: Float?, cookedWeightGrams: Float?): NutritionReference = NutritionReference(
            basis = NutritionBasis.PER_BATCH,
            netQuantity = cookedWeightGrams?.takeIf { it > 0f },
            netUnit = cookedWeightGrams?.takeIf { it > 0f }?.let { AmountUnit.GRAM },
            servingsPerBatch = servings?.takeIf { it > 0f }
        )
    }
}

/** A product as it arrives from a source, before it becomes a meal item. */
data class ScannedProduct(
    val barcode: String?,
    val displayName: String,
    val brand: String?,
    val source: NutritionSource,
    val facts: NutritionFacts,
    val reference: NutritionReference,
    /** Last successful upload to Open Food Facts, from the product cache; null = never. */
    val contributedAt: Long? = null,
    /** Free-text category for Open Food Facts ("Chocolate bars"); it needs one for a Nutri-Score. */
    val category: String? = null
) {
    /** Anything with a barcode that did not come from Open Food Facts itself can be sent there. */
    val canContribute: Boolean get() = barcode != null && source != NutritionSource.OPEN_FOOD_FACTS

    val plausibility: Set<NutritionPlausibilityFlag>
        get() = NutritionPlausibility.check(facts, reference.basis)
}
