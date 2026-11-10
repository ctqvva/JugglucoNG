package tk.glucodata.data.food

/** The shelf a food was found on. The search lists them in this order. */
enum class FoodSource {
    /** The person's own foods: saved, or remembered from an earlier meal. */
    LIBRARY,

    /** The bundled everyday foods, named in the app's language. */
    COMMON,

    /** The UK composition tables that came with Juggluco. English only. */
    REFERENCE,

    /** A packaged product from Open Food Facts, fetched on request. */
    OPEN_FOOD_FACTS,

    /** Typed in from a label, not yet saved anywhere. */
    CUSTOM
}

/** A household measure a food can be counted in. */
enum class FoodPortionKind(val storageValue: String) {
    PIECE("piece"),
    SLICE("slice"),
    CUP("cup"),
    GLASS("glass"),
    TBSP("tbsp"),
    TSP("tsp"),
    SERVING("serving"),
    PACKAGE("package");

    companion object {
        fun fromStorage(value: String?): FoodPortionKind? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * One countable amount of a food: "a slice is 30 g".
 *
 * @param label the measure's own wording when the source has one ("Riegel", "biscuit"); the
 *   [kind]'s translated name is shown otherwise.
 */
data class FoodPortion(
    val kind: FoodPortionKind,
    val grams: Float,
    val label: String? = null
)

/**
 * A food as the search hands it over, whichever shelf it came from.
 *
 * The nutrients are for [referenceGrams] of it: 100 for the tables and for a label, the weight
 * of a serving for the person's own foods. When [referenceGrams] is null they are for one
 * serving whose weight nobody recorded ("Pizza, 60 g carbs"), and the food can only be counted
 * in servings.
 */
data class FoodItem(
    val key: String,
    val source: FoodSource,
    val name: String,
    val brand: String? = null,
    val referenceGrams: Float?,
    val carbs: Float,
    val protein: Float? = null,
    val fat: Float? = null,
    val fiber: Float? = null,
    val kcal: Float? = null,
    val portions: List<FoodPortion> = emptyList(),
    /** Sold and measured by volume, so amounts read in ml. */
    val isLiquid: Boolean = false,
    /** How long the person said this food takes; null leaves it to the estimate. */
    val absorptionMinutes: Int? = null,
    val libraryId: Long? = null,
    val barcode: String? = null,
    /** The amount this food was last eaten in, offered again the next time. */
    val lastAmount: FoodAmount? = null
) {
    val countedByWeight: Boolean get() = referenceGrams != null && referenceGrams > 0f

    /** Carbs in 100 g (or 100 ml), for comparing foods in a list. Null for a weightless serving. */
    val carbsPer100: Float? get() = referenceGrams?.takeIf { it > 0f }?.let { carbs * 100f / it }
}

/**
 * How much of a food: [quantity] of the unit at [portionIndex] in [FoodItem.portions], or of
 * the base unit when the index is [BASE_UNIT] — grams (ml for a liquid), or whole servings for
 * a food without a weight.
 */
data class FoodAmount(
    val quantity: Float,
    val portionIndex: Int = BASE_UNIT
) {
    companion object {
        const val BASE_UNIT = -1
    }
}

/** What an amount of a food comes to. */
data class FoodNutrients(
    val grams: Float?,
    val carbs: Float,
    val protein: Float?,
    val fat: Float?,
    val fiber: Float?,
    val kcal: Float?
)

object FoodMath {
    const val DEFAULT_GRAMS = 100f
    private const val MAX_FACTOR = 100f

    /** Grams (or ml) that [amount] of [food] weighs; null when the food has no weight. */
    fun grams(food: FoodItem, amount: FoodAmount): Float? {
        if (!food.countedByWeight) return null
        val unitGrams = food.portions.getOrNull(amount.portionIndex)?.grams ?: 1f
        return (amount.quantity * unitGrams).takeIf { it.isFinite() }?.coerceAtLeast(0f)
    }

    /** The share of the food's reference amount that [amount] is. */
    fun factor(food: FoodItem, amount: FoodAmount): Float {
        val reference = food.referenceGrams?.takeIf { it > 0f }
        val raw = if (reference != null) {
            (grams(food, amount) ?: 0f) / reference
        } else {
            amount.quantity
        }
        return raw.takeIf { it.isFinite() }?.coerceIn(0f, MAX_FACTOR) ?: 0f
    }

    fun nutrients(food: FoodItem, amount: FoodAmount): FoodNutrients {
        val factor = factor(food, amount)
        return FoodNutrients(
            grams = grams(food, amount),
            carbs = food.carbs * factor,
            protein = food.protein?.times(factor),
            fat = food.fat?.times(factor),
            fiber = food.fiber?.times(factor),
            kcal = food.kcal?.times(factor)
        )
    }

    /**
     * The amount a food starts at when it is added: what was eaten last time, else one of its
     * countable portions, else 100 g. A cup is skipped as a starting point: where people weigh
     * food it is not how a serving is thought of, and it stays one tap away.
     */
    fun defaultAmount(food: FoodItem): FoodAmount {
        food.lastAmount
            ?.takeIf { it.quantity > 0f && (it.portionIndex == FoodAmount.BASE_UNIT || it.portionIndex in food.portions.indices) }
            ?.let { return it }
        if (!food.countedByWeight) return FoodAmount(1f)
        val index = food.portions.indexOfFirst { it.kind != FoodPortionKind.CUP && it.grams > 0f }
        return if (index >= 0) FoodAmount(1f, index) else FoodAmount(DEFAULT_GRAMS)
    }

    /** One press of a stepper: 10 g at a time by weight, half a portion at a time by count. */
    fun step(food: FoodItem, portionIndex: Int): Float =
        if (portionIndex == FoodAmount.BASE_UNIT && food.countedByWeight) 10f else 0.5f

    /** [amount] moved one [step] in [direction], landing on the step's own grid and never at zero. */
    fun stepped(food: FoodItem, amount: FoodAmount, direction: Int): FoodAmount {
        val step = step(food, amount.portionIndex)
        val onGrid = kotlin.math.round(amount.quantity / step) * step
        val next = if (kotlin.math.abs(onGrid - amount.quantity) < step / 100f) {
            amount.quantity + direction * step
        } else if (direction > 0) {
            kotlin.math.ceil(amount.quantity / step) * step
        } else {
            kotlin.math.floor(amount.quantity / step) * step
        }
        return amount.copy(quantity = if (direction < 0) next.coerceIn(minOf(step, amount.quantity), amount.quantity) else next.coerceIn(step, maxOf(5000f, amount.quantity)))
    }

    /** The same weight counted in another unit, so switching unit keeps what is on the plate. */
    fun inUnit(food: FoodItem, amount: FoodAmount, portionIndex: Int): FoodAmount {
        if (portionIndex == amount.portionIndex) return amount
        val grams = grams(food, amount) ?: return FoodAmount(amount.quantity, portionIndex)
        val unitGrams = food.portions.getOrNull(portionIndex)?.grams?.takeIf { it > 0f } ?: 1f
        return FoodAmount(grams / unitGrams, portionIndex)
    }

    /**
     * How long a meal of these macros keeps arriving, when nobody said. The same estimate the
     * journal has always used for the composition tables, now made on what is actually eaten.
     */
    fun estimatedAbsorptionMinutes(carbs: Float, protein: Float?, fat: Float?): Int {
        val carbWindow = 45f + carbs.coerceAtLeast(0f) * 1.4f
        val macroWindow = tk.glucodata.data.journal.journalFoodTailDurationMinutes(protein, fat)
        return kotlin.math.round(maxOf(carbWindow, macroWindow)).toInt().coerceIn(45, 480)
    }
}

/** Input shared by the amount fields and transfer parser, including Arabic label numbers. */
object FoodNumbers {
    fun normalize(text: String): String = buildString {
        text.forEach { char ->
            val digit = Character.digit(char, 10)
            when {
                digit >= 0 -> append('0' + digit)
                char == ',' || char == '\u066b' -> append('.')
                char == '\u066c' -> Unit
                else -> append(char)
            }
        }
    }

    fun parse(text: String): Float? = normalize(text.trim()).toFloatOrNull()
        ?.takeIf { it.isFinite() && it >= 0f }
}
