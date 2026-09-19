package tk.glucodata.data.meal

import kotlin.math.abs

enum class NutritionPlausibilityFlag(val storageValue: String) {
    /** Carbs + protein + fat + fiber add up to more than the 100 g / 100 ml they describe. */
    MACROS_EXCEED_BASIS("macros_exceed_basis"),
    /** Stated energy is far from what 4/4/9 (+2 for fiber) predicts from the macros. */
    ENERGY_MISMATCH("energy_mismatch"),
    /** A macro is negative or not a finite number. */
    INVALID_VALUE("invalid_value");

    companion object {
        fun fromStorage(value: String?): NutritionPlausibilityFlag? =
            entries.firstOrNull { it.storageValue == value }

        fun decode(csv: String?): Set<NutritionPlausibilityFlag> =
            csv?.split(',')?.mapNotNull { fromStorage(it.trim()) }?.toSet().orEmpty()

        fun encode(flags: Set<NutritionPlausibilityFlag>): String? =
            flags.takeIf { it.isNotEmpty() }?.joinToString(",") { it.storageValue }
    }
}

/**
 * Local sanity checks on nutrition numbers. Open Food Facts is partly user-maintained, so values
 * are checked the same way an AI answer would be: the sum of macros per 100 g cannot exceed 100 g,
 * and the stated kcal should match 4/4/9 within a tolerance. A flag marks the product in the UI;
 * it never silently alters a value.
 */
object NutritionPlausibility {
    const val ENERGY_TOLERANCE_FRACTION = 0.15f
    /** Below this many kcal the relative check is meaningless (water, diet drinks). */
    const val ENERGY_ABSOLUTE_TOLERANCE_KCAL = 15f
    private const val BASIS_GRAMS = 100f
    /** Rounding on labels lets the sum land a little over 100. */
    private const val MACRO_SUM_SLACK = 1.5f

    fun check(facts: NutritionFacts, basis: NutritionBasis): Set<NutritionPlausibilityFlag> {
        val flags = mutableSetOf<NutritionPlausibilityFlag>()
        val values = listOfNotNull(
            facts.carbsGrams, facts.proteinGrams, facts.fatGrams, facts.fiberGrams,
            facts.sugarsGrams, facts.polyolsGrams, facts.kcal
        )
        if (values.any { !it.isFinite() || it < 0f }) {
            flags += NutritionPlausibilityFlag.INVALID_VALUE
            return flags
        }
        val carbs = facts.carbsGrams
        val protein = facts.proteinGrams ?: 0f
        val fat = facts.fatGrams ?: 0f
        val fiber = facts.fiberGrams ?: 0f
        if (basis == NutritionBasis.PER_100G || basis == NutritionBasis.PER_100ML) {
            if (carbs + protein + fat + fiber > BASIS_GRAMS + MACRO_SUM_SLACK) {
                flags += NutritionPlausibilityFlag.MACROS_EXCEED_BASIS
            }
            if ((facts.sugarsGrams ?: 0f) > carbs + MACRO_SUM_SLACK) {
                flags += NutritionPlausibilityFlag.MACROS_EXCEED_BASIS
            }
        }
        val kcal = facts.kcal
        if (kcal != null) {
            // EU labels: carbohydrates exclude fiber, fiber counts 2 kcal/g. Polyols sit inside
            // carbs on EU labels at 2.4 kcal/g; treating them as 4 keeps the estimate slightly
            // high, which the tolerance absorbs.
            val expected = carbs * 4f + protein * 4f + fat * 9f + fiber * 2f
            val tolerance = maxOf(expected * ENERGY_TOLERANCE_FRACTION, ENERGY_ABSOLUTE_TOLERANCE_KCAL)
            if (abs(expected - kcal) > tolerance) {
                flags += NutritionPlausibilityFlag.ENERGY_MISMATCH
            }
        }
        return flags
    }
}
