package tk.glucodata.ui.meal

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import java.util.Locale
import tk.glucodata.R
import tk.glucodata.data.meal.MissingReference
import tk.glucodata.data.meal.NutritionBasis
import tk.glucodata.data.meal.NutritionFacts
import tk.glucodata.data.meal.NutritionPlausibilityFlag
import tk.glucodata.data.meal.NutritionReference
import tk.glucodata.data.meal.NutritionSource
import tk.glucodata.data.meal.Quantity

/** Locale-aware number rendering shared by the meal screens. */
internal object MealFormat {
    fun grams(value: Float?, locale: Locale = Locale.getDefault(), digits: Int = 1): String {
        if (value == null || !value.isFinite()) return "–"
        val nf = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = 0
        }
        return nf.format(value.toDouble())
    }

    fun number(value: Float?, locale: Locale = Locale.getDefault(), digits: Int = 2): String = grams(value, locale, digits)

    /** "52 g / 100 g × 0,33 = 17,2 g" — the visible derivation for one macro. */
    fun macroDerivation(perBasis: Float, basis: NutritionBasis, factor: Float, basisWord: String, locale: Locale = Locale.getDefault()): String {
        val base = when (basis) {
            NutritionBasis.PER_100G -> "100 g"
            NutritionBasis.PER_100ML -> "100 ml"
            NutritionBasis.PER_SERVING, NutritionBasis.PER_BATCH -> basisWord
        }
        return "${grams(perBasis, locale)} g / $base × ${number(factor, locale)} = ${grams(perBasis * factor, locale)} g"
    }

    /** Short editor text for a float ("70", "6,5"). */
    fun editor(value: Float?): String {
        if (value == null || !value.isFinite()) return ""
        return if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
    }

    fun parseEditor(text: String): Float? =
        text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
}

@Composable
internal fun basisLabel(basis: NutritionBasis): String = when (basis) {
    NutritionBasis.PER_100G -> stringResource(R.string.meal_basis_per_100g)
    NutritionBasis.PER_100ML -> stringResource(R.string.meal_basis_per_100ml)
    NutritionBasis.PER_SERVING -> stringResource(R.string.meal_basis_per_serving)
    NutritionBasis.PER_BATCH -> stringResource(R.string.meal_basis_per_batch)
}

@Composable
internal fun sourceLabel(source: NutritionSource): String = when (source) {
    NutritionSource.OPEN_FOOD_FACTS -> stringResource(R.string.meal_source_off)
    NutritionSource.MANUAL -> stringResource(R.string.meal_source_manual)
    NutritionSource.MEAL -> stringResource(R.string.meal_title)
    NutritionSource.OCR_LABEL -> stringResource(R.string.meal_source_ocr)
}

@Composable
internal fun missingReferenceLabel(need: MissingReference): String = when (need) {
    MissingReference.DENSITY -> stringResource(R.string.meal_need_density)
    MissingReference.NET_QUANTITY -> stringResource(R.string.meal_need_net_quantity)
    MissingReference.SERVING_SIZE -> stringResource(R.string.meal_need_serving_size)
    MissingReference.PIECE_WEIGHT -> stringResource(R.string.meal_need_piece_weight)
    MissingReference.SERVINGS_PER_BATCH -> stringResource(R.string.meal_need_servings_per_batch)
    MissingReference.BATCH_WEIGHT -> stringResource(R.string.meal_need_batch_weight)
}

@Composable
internal fun missingReferenceAnswerLabel(need: MissingReference): String = when (need) {
    MissingReference.DENSITY -> stringResource(R.string.meal_answer_density)
    MissingReference.NET_QUANTITY -> stringResource(R.string.meal_answer_net_quantity)
    MissingReference.SERVING_SIZE -> stringResource(R.string.meal_answer_serving_size)
    MissingReference.PIECE_WEIGHT -> stringResource(R.string.meal_answer_piece_weight)
    MissingReference.SERVINGS_PER_BATCH -> stringResource(R.string.meal_servings_label)
    MissingReference.BATCH_WEIGHT -> stringResource(R.string.meal_cooked_weight_label)
}

@Composable
internal fun plausibilityLabel(flag: NutritionPlausibilityFlag): String = when (flag) {
    NutritionPlausibilityFlag.MACROS_EXCEED_BASIS -> stringResource(R.string.meal_plausibility_macros)
    NutritionPlausibilityFlag.ENERGY_MISMATCH -> stringResource(R.string.meal_plausibility_energy)
    NutritionPlausibilityFlag.INVALID_VALUE -> stringResource(R.string.meal_plausibility_invalid)
}

/** The quantity shortcuts that make sense for a reference, as typed text the parser accepts. */
internal fun quantityChipsFor(reference: NutritionReference, servingWord: String, pieceWord: String, packageWord: String): List<String> {
    val chips = mutableListOf<String>()
    when (reference.basis) {
        NutritionBasis.PER_100G -> { chips += "100 g"; chips += "50 g" }
        NutritionBasis.PER_100ML -> { chips += "100 ml"; chips += "250 ml" }
        NutritionBasis.PER_SERVING -> { chips += "1 $servingWord"; chips += "2 $servingWord" }
        NutritionBasis.PER_BATCH -> {
            if (reference.servingsPerBatch != null) { chips += "1 $servingWord"; chips += "2 $servingWord" }
            chips += "½"; chips += "⅓"; chips += "¼"
            if (reference.netQuantity != null) chips += "250 g"
        }
    }
    if (reference.basis != NutritionBasis.PER_BATCH) {
        val pieceLabel = reference.servingPieceLabel?.takeIf { reference.servingPieces != null }
        if (pieceLabel != null) {
            chips += "1 $pieceLabel"
            chips += "2 $pieceLabel"
        } else if (reference.pieceGrams != null) {
            chips += "1 $pieceWord"
        } else if (reference.servingQuantity != null && reference.basis != NutritionBasis.PER_SERVING) {
            chips += "1 $servingWord"
        }
        if (reference.netQuantity != null) {
            chips += "½ $packageWord"
            chips += "1 $packageWord"
        }
    }
    return chips.distinct()
}

internal fun Quantity.describe(servingWord: String, pieceWord: String): String = when (this) {
    is Quantity.Mass -> "${MealFormat.number(grams)} g"
    is Quantity.Volume -> "${MealFormat.number(milliliters)} ml"
    is Quantity.Fraction -> "${MealFormat.number(value * 100f)} %"
    is Quantity.Servings -> "${MealFormat.number(count)} $servingWord"
    is Quantity.Pieces -> "${MealFormat.number(count)} ${label.ifBlank { pieceWord }}"
}

internal fun NutritionFacts.summary(carbsWord: String, proteinWord: String, fatWord: String, locale: Locale = Locale.getDefault()): String {
    val parts = mutableListOf("${MealFormat.grams(carbsGrams, locale)} g $carbsWord")
    proteinGrams?.let { parts += "${MealFormat.grams(it, locale)} g $proteinWord" }
    fatGrams?.let { parts += "${MealFormat.grams(it, locale)} g $fatWord" }
    return parts.joinToString(" · ")
}
