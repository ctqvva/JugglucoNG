package tk.glucodata.data.meal

import java.text.NumberFormat
import java.util.Locale

/** The single piece of reference information that stands between a quantity and a factor. */
enum class MissingReference {
    DENSITY,
    NET_QUANTITY,
    SERVING_SIZE,
    PIECE_WEIGHT,
    SERVINGS_PER_BATCH,
    BATCH_WEIGHT
}

sealed class QuantityResolution {
    /**
     * @param factor multiplier for the basis amount's nutrition values.
     * @param grams physical mass when it is known, for display ("1/3 → 33 g").
     * @param milliliters physical volume when it is known.
     * @param steps the derivation, one readable segment per conversion.
     */
    data class Resolved(
        val factor: Float,
        val grams: Float?,
        val milliliters: Float?,
        val steps: List<String>
    ) : QuantityResolution()

    /** One targeted question; the answer goes into the [NutritionReference] and is kept. */
    data class Missing(val need: MissingReference, val steps: List<String>) : QuantityResolution()

    data class Ambiguous(val candidates: List<Quantity>) : QuantityResolution()

    object Unparsed : QuantityResolution()
}

/**
 * Quantity + reference → factor. The rule is: convert only when the dimensions differ, ask only
 * for the one missing link, never guess. Mass against per-100-g is direct; volume against per-100-ml
 * is direct; crossing between them needs a density; shares need the net quantity; servings need the
 * serving size; pieces need a piece weight.
 */
object QuantityResolver {

    fun resolve(text: String, reference: NutritionReference, locale: Locale = Locale.getDefault()): QuantityResolution {
        return when (val parse = QuantityParser.parse(text)) {
            QuantityParse.Unparsed -> QuantityResolution.Unparsed
            is QuantityParse.Ambiguous -> QuantityResolution.Ambiguous(
                parse.candidates.filter { resolve(it, reference, locale) is QuantityResolution.Resolved }
                    .ifEmpty { parse.candidates }
            )
            is QuantityParse.Parsed -> resolve(parse.quantity, reference, locale)
        }
    }

    fun resolve(quantity: Quantity, reference: NutritionReference, locale: Locale = Locale.getDefault()): QuantityResolution {
        val fmt = Formatter(locale)
        return when (quantity) {
            is Quantity.Mass -> fromMass(quantity.grams, listOf(fmt.amount(quantity.grams, "g", quantity.label)), reference, fmt)
            is Quantity.Volume -> fromVolume(quantity.milliliters, listOf(fmt.volumeIntro(quantity)), reference, fmt)
            is Quantity.Fraction -> fromFraction(quantity.value, reference, fmt)
            is Quantity.Servings -> fromServings(quantity.count, reference, fmt)
            is Quantity.Pieces -> fromPieces(quantity, reference, fmt)
        }
    }

    private fun fromMass(grams: Float, steps: List<String>, ref: NutritionReference, fmt: Formatter): QuantityResolution {
        return when (ref.basis) {
            NutritionBasis.PER_100G -> resolved(grams / 100f, grams, null, steps + fmt.factorStep(grams, "g", 100f))
            NutritionBasis.PER_100ML -> {
                val density = ref.densityGramsPerMl?.takeIf { it > 0f }
                    ?: return QuantityResolution.Missing(MissingReference.DENSITY, steps)
                val ml = grams / density
                resolved(ml / 100f, grams, ml, steps + fmt.densityStep(ml, "ml", density) + fmt.factorStep(ml, "ml", 100f))
            }
            NutritionBasis.PER_SERVING -> {
                val servingGrams = ref.servingGrams()
                    ?: return QuantityResolution.Missing(MissingReference.SERVING_SIZE, steps)
                resolved(grams / servingGrams, grams, null, steps + fmt.factorStep(grams, "g", servingGrams, fmt.servingWord()))
            }
            NutritionBasis.PER_BATCH -> {
                val batchGrams = ref.netGrams()
                    ?: return QuantityResolution.Missing(MissingReference.BATCH_WEIGHT, steps)
                resolved(grams / batchGrams, grams, null, steps + fmt.factorStep(grams, "g", batchGrams))
            }
        }
    }

    private fun fromVolume(ml: Float, steps: List<String>, ref: NutritionReference, fmt: Formatter): QuantityResolution {
        return when (ref.basis) {
            NutritionBasis.PER_100ML -> resolved(ml / 100f, null, ml, steps + fmt.factorStep(ml, "ml", 100f))
            NutritionBasis.PER_100G -> {
                val density = ref.densityGramsPerMl?.takeIf { it > 0f }
                    ?: return QuantityResolution.Missing(MissingReference.DENSITY, steps)
                val grams = ml * density
                resolved(grams / 100f, grams, ml, steps + fmt.densityStep(grams, "g", density) + fmt.factorStep(grams, "g", 100f))
            }
            NutritionBasis.PER_SERVING -> {
                val servingMl = ref.servingMilliliters()
                if (servingMl != null) {
                    return resolved(ml / servingMl, null, ml, steps + fmt.factorStep(ml, "ml", servingMl, fmt.servingWord()))
                }
                val servingGrams = ref.servingGrams()
                    ?: return QuantityResolution.Missing(MissingReference.SERVING_SIZE, steps)
                val density = ref.densityGramsPerMl?.takeIf { it > 0f }
                    ?: return QuantityResolution.Missing(MissingReference.DENSITY, steps)
                val grams = ml * density
                resolved(grams / servingGrams, grams, ml, steps + fmt.densityStep(grams, "g", density) + fmt.factorStep(grams, "g", servingGrams, fmt.servingWord()))
            }
            NutritionBasis.PER_BATCH -> {
                val batchMl = ref.netMilliliters()
                if (batchMl != null) {
                    return resolved(ml / batchMl, null, ml, steps + fmt.factorStep(ml, "ml", batchMl))
                }
                val batchGrams = ref.netGrams()
                    ?: return QuantityResolution.Missing(MissingReference.BATCH_WEIGHT, steps)
                val density = ref.densityGramsPerMl?.takeIf { it > 0f }
                    ?: return QuantityResolution.Missing(MissingReference.DENSITY, steps)
                val grams = ml * density
                resolved(grams / batchGrams, grams, ml, steps + fmt.densityStep(grams, "g", density) + fmt.factorStep(grams, "g", batchGrams))
            }
        }
    }

    private fun fromFraction(value: Float, ref: NutritionReference, fmt: Formatter): QuantityResolution {
        val intro = listOf(fmt.share(value))
        return when (ref.basis) {
            NutritionBasis.PER_BATCH -> resolved(value, ref.netGrams()?.times(value), ref.netMilliliters()?.times(value), intro)
            NutritionBasis.PER_100G, NutritionBasis.PER_100ML -> {
                val net = ref.netQuantity?.takeIf { it > 0f }
                val unit = ref.netUnit
                if (net == null || unit == null) return QuantityResolution.Missing(MissingReference.NET_QUANTITY, intro)
                val amount = value * net
                val steps = intro + fmt.amountOf(amount, unit.symbol, net)
                when (unit) {
                    AmountUnit.GRAM -> fromMass(amount, steps, ref, fmt)
                    AmountUnit.MILLILITER -> fromVolume(amount, steps, ref, fmt)
                }
            }
            NutritionBasis.PER_SERVING -> {
                ref.servingsPerBatch?.takeIf { it > 0f }?.let { servings ->
                    return resolved(value * servings, null, null, intro + fmt.servingsOf(value * servings, servings))
                }
                val net = ref.netQuantity?.takeIf { it > 0f }
                val unit = ref.netUnit
                if (net == null || unit == null) return QuantityResolution.Missing(MissingReference.SERVINGS_PER_BATCH, intro)
                val amount = value * net
                val steps = intro + fmt.amountOf(amount, unit.symbol, net)
                when (unit) {
                    AmountUnit.GRAM -> fromMass(amount, steps, ref, fmt)
                    AmountUnit.MILLILITER -> fromVolume(amount, steps, ref, fmt)
                }
            }
        }
    }

    private fun fromServings(count: Float, ref: NutritionReference, fmt: Formatter): QuantityResolution {
        val intro = listOf(fmt.servings(count))
        return when (ref.basis) {
            NutritionBasis.PER_SERVING -> resolved(
                count,
                ref.servingGrams()?.times(count),
                ref.servingMilliliters()?.times(count),
                intro
            )
            NutritionBasis.PER_BATCH -> {
                val perBatch = ref.servingsPerBatch?.takeIf { it > 0f }
                    ?: return QuantityResolution.Missing(MissingReference.SERVINGS_PER_BATCH, intro)
                resolved(count / perBatch, ref.netGrams()?.times(count / perBatch), ref.netMilliliters()?.times(count / perBatch), intro + fmt.factorStep(count, fmt.servingWord(), perBatch))
            }
            NutritionBasis.PER_100G, NutritionBasis.PER_100ML -> {
                val quantity = ref.servingQuantity?.takeIf { it > 0f }
                val unit = ref.servingUnit
                if (quantity == null || unit == null) return QuantityResolution.Missing(MissingReference.SERVING_SIZE, intro)
                val amount = count * quantity
                val steps = intro + fmt.amount(amount, unit.symbol, null, perServing = quantity)
                when (unit) {
                    AmountUnit.GRAM -> fromMass(amount, steps, ref, fmt)
                    AmountUnit.MILLILITER -> fromVolume(amount, steps, ref, fmt)
                }
            }
        }
    }

    private fun fromPieces(quantity: Quantity.Pieces, ref: NutritionReference, fmt: Formatter): QuantityResolution {
        val intro = listOf(fmt.pieces(quantity))
        // A serving that is itself a piece ("1 Riegel (25 g)") makes pieces a serving count.
        val servingPieces = ref.servingPieces?.takeIf { it > 0f }
        if (ref.basis == NutritionBasis.PER_SERVING && servingPieces != null) {
            val servings = quantity.count / servingPieces
            return resolved(servings, ref.servingGrams()?.times(servings), ref.servingMilliliters()?.times(servings), intro + fmt.servingsOf(servings, null))
        }
        ref.effectivePieceGrams?.let { pieceGrams ->
            val grams = quantity.count * pieceGrams
            return fromMass(grams, intro + fmt.amount(grams, "g", null, perPiece = pieceGrams), ref, fmt)
        }
        ref.effectivePieceMilliliters?.let { pieceMl ->
            val ml = quantity.count * pieceMl
            return fromVolume(ml, intro + fmt.amount(ml, "ml", null, perPiece = pieceMl), ref, fmt)
        }
        return QuantityResolution.Missing(MissingReference.PIECE_WEIGHT, intro)
    }

    private fun resolved(factor: Float, grams: Float?, ml: Float?, steps: List<String>): QuantityResolution {
        if (!factor.isFinite() || factor < 0f) return QuantityResolution.Unparsed
        return QuantityResolution.Resolved(factor, grams, ml, steps)
    }

    private fun NutritionReference.servingGrams(): Float? =
        servingQuantity?.takeIf { it > 0f && servingUnit == AmountUnit.GRAM }
            ?: servingQuantity?.takeIf { it > 0f && servingUnit == AmountUnit.MILLILITER }
                ?.let { ml -> densityGramsPerMl?.takeIf { it > 0f }?.let { ml * it } }

    private fun NutritionReference.servingMilliliters(): Float? =
        servingQuantity?.takeIf { it > 0f && servingUnit == AmountUnit.MILLILITER }

    private fun NutritionReference.netGrams(): Float? =
        netQuantity?.takeIf { it > 0f && netUnit == AmountUnit.GRAM }
            ?: netQuantity?.takeIf { it > 0f && netUnit == AmountUnit.MILLILITER }
                ?.let { ml -> densityGramsPerMl?.takeIf { it > 0f }?.let { ml * it } }

    private fun NutritionReference.netMilliliters(): Float? =
        netQuantity?.takeIf { it > 0f && netUnit == AmountUnit.MILLILITER }

    /** Locale-aware number rendering for the derivation line; language-neutral otherwise. */
    class Formatter(private val locale: Locale) {
        private val numbers: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }

        fun number(value: Float): String = numbers.format(value.toDouble())

        fun amount(value: Float, unit: String, label: String?, perServing: Float? = null, perPiece: Float? = null): String {
            val core = "${number(value)} $unit"
            return when {
                label != null && label != unit -> "$label = $core"
                perServing != null -> "× ${number(perServing)} $unit = $core"
                perPiece != null -> "× ${number(perPiece)} $unit = $core"
                else -> core
            }
        }

        fun amountOf(value: Float, unit: String, net: Float): String = "× ${number(net)} $unit = ${number(value)} $unit"

        fun volumeIntro(volume: Quantity.Volume): String {
            val core = "${number(volume.milliliters)} ml"
            return if (volume.label.lowercase(Locale.ROOT) == "ml") core else "${volume.label} = $core"
        }

        fun densityStep(result: Float, unit: String, density: Float): String =
            "ρ ${number(density)} g/ml → ${number(result)} $unit"

        fun factorStep(amount: Float, unit: String, basis: Float, basisWord: String? = null): String {
            val basisText = basisWord ?: "${number(basis)} $unit"
            return "${number(amount)} $unit ÷ $basisText = × ${number(amount / basis)}"
        }

        fun share(value: Float): String = "× ${number(value)}"

        fun servings(count: Float): String = "${number(count)} ${servingWord()}"

        fun servingsOf(servings: Float, perBatch: Float?): String =
            if (perBatch != null) "× ${number(perBatch)} = ${number(servings)} ${servingWord()}" else "= ${number(servings)} ${servingWord()}"

        fun pieces(quantity: Quantity.Pieces): String =
            "${number(quantity.count)} ${quantity.label.ifBlank { "×" }}".trim()

        fun servingWord(): String = when (locale.language) {
            "de" -> "Portion"
            else -> "serving"
        }
    }
}
