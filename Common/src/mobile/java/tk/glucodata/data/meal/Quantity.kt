package tk.glucodata.data.meal

import java.util.Locale

/**
 * A parsed amount, before it meets a nutrition basis. The parser is deterministic and offline; it
 * knows units and number words but nothing about the product, so resolving to a factor is a
 * separate step ([QuantityResolver]).
 */
sealed class Quantity {
    /** Absolute mass; [label] keeps the unit as typed ("kg", "oz") for the derivation line. */
    data class Mass(val grams: Float, val label: String = "g") : Quantity()

    /** Absolute volume; [label] keeps "Tasse", "EL", "cup" so the conversion stays visible. */
    data class Volume(val milliliters: Float, val label: String = "ml") : Quantity()

    /** A share of the whole package or batch: 1/3, 30 %, "die Hälfte", "eineinhalb Packungen". */
    data class Fraction(val value: Float) : Quantity()

    /** Whole servings as the source defines them. */
    data class Servings(val count: Float) : Quantity()

    /** Counted pieces with the word the user used ("Riegel", "Scheibe", "cubes"). */
    data class Pieces(val count: Float, val label: String) : Quantity()
}

sealed class QuantityParse {
    data class Parsed(val quantity: Quantity) : QuantityParse()

    /** The text was a bare number; only the caller knows whether that means grams or pieces. */
    data class Ambiguous(val candidates: List<Quantity>) : QuantityParse()

    object Unparsed : QuantityParse()
}

/**
 * Text → [Quantity]. German and English, number words, decimal comma and point, unicode and ascii
 * fractions, mixed numbers ("1 1/2"), percent. Unknown unit words after a number become
 * [Quantity.Pieces], which is what "2 Riegel" or "3 cubes" mean. A bare number is reported as
 * ambiguous rather than guessed.
 */
object QuantityParser {
    const val CUP_ML = 240f
    const val TABLESPOON_ML = 15f
    const val TEASPOON_ML = 5f
    const val FLUID_OUNCE_ML = 29.5735f
    const val OUNCE_GRAMS = 28.3495f
    const val POUND_GRAMS = 453.592f

    private val massUnits: Map<String, Float> = mapOf(
        "g" to 1f, "gr" to 1f, "gramm" to 1f, "gram" to 1f, "grams" to 1f, "gramme" to 1f, "grammes" to 1f,
        "kg" to 1000f, "kilo" to 1000f, "kilogramm" to 1000f, "kilogram" to 1000f, "kilograms" to 1000f,
        "mg" to 0.001f,
        "oz" to OUNCE_GRAMS, "ounce" to OUNCE_GRAMS, "ounces" to OUNCE_GRAMS,
        "lb" to POUND_GRAMS, "lbs" to POUND_GRAMS, "pound" to POUND_GRAMS, "pounds" to POUND_GRAMS,
        "pfund" to 500f
    )

    private val volumeUnits: Map<String, Float> = mapOf(
        "ml" to 1f, "milliliter" to 1f, "milliliters" to 1f, "millilitre" to 1f, "millilitres" to 1f,
        "cl" to 10f, "dl" to 100f,
        "l" to 1000f, "liter" to 1000f, "liters" to 1000f, "litre" to 1000f, "litres" to 1000f,
        "tasse" to CUP_ML, "tassen" to CUP_ML, "cup" to CUP_ML, "cups" to CUP_ML,
        "el" to TABLESPOON_ML, "esslöffel" to TABLESPOON_ML, "essloeffel" to TABLESPOON_ML,
        "tbsp" to TABLESPOON_ML, "tablespoon" to TABLESPOON_ML, "tablespoons" to TABLESPOON_ML,
        "tl" to TEASPOON_ML, "teelöffel" to TEASPOON_ML, "teeloeffel" to TEASPOON_ML,
        "tsp" to TEASPOON_ML, "teaspoon" to TEASPOON_ML, "teaspoons" to TEASPOON_ML,
        "floz" to FLUID_OUNCE_ML
    )

    // The UI's quantity chips use the localized serving/package words, so every maintained locale
    // has to be understood here, not just German and English.
    private val servingWords = setOf(
        "portion", "portionen", "port", "serving", "servings", "serv", "serve", "serves",
        "portions", "porzione", "porzioni", "portie", "porties", "porcja", "porcje", "porcji",
        "porção", "porções", "porcao", "porcoes", "порция", "порции", "порций", "порцию",
        "порція", "порції", "порцій", "порцію", "порцыя", "порцыі", "порцый", "порцыю",
        "porsiyon", "porc", "порц", "qayb", "qaybood", "份"
    )

    /** Multiples of the whole package: "eineinhalb Packungen", "half a pack", "2 Dosen". */
    private val packageWords = setOf(
        "packung", "packungen", "pack", "packs", "päckchen", "paeckchen", "packet", "packets",
        "package", "packages", "pck", "pkg", "dose", "dosen", "can", "cans", "flasche", "flaschen",
        "bottle", "bottles", "tüte", "tuete", "tüten", "tueten", "bag", "bags", "beutel",
        "box", "boxes", "schachtel", "schachteln",
        "paquet", "paquets", "confezione", "confezioni", "verpakking", "verpakkingen",
        "opakowanie", "opakowania", "embalagem", "embalagens", "упаковка", "упаковки", "упаковку",
        "упаковок", "упакоўка", "упакоўкі", "упакоўку", "förpackning", "förpackningar", "paket",
        "paketler", "багц", "baakad", "包"
    )
    // Not package words on purpose: "Tafel", "Riegel", "bar", "Glas", "Becher" — those are pieces
    // or vessels whose size the reference has to supply, so they fall through to Pieces.

    private val percentWords = setOf("%", "prozent", "percent", "pct")

    private val numberWords: Map<String, Float> = mapOf(
        "ein" to 1f, "eine" to 1f, "einen" to 1f, "einer" to 1f, "eins" to 1f, "einem" to 1f,
        "zwei" to 2f, "drei" to 3f, "vier" to 4f, "fünf" to 5f, "fuenf" to 5f, "sechs" to 6f,
        "sieben" to 7f, "acht" to 8f, "neun" to 9f, "zehn" to 10f, "elf" to 11f, "zwölf" to 12f,
        "zwoelf" to 12f,
        "a" to 1f, "an" to 1f, "one" to 1f, "two" to 2f, "three" to 3f, "four" to 4f, "five" to 5f,
        "six" to 6f, "seven" to 7f, "eight" to 8f, "nine" to 9f, "ten" to 10f, "eleven" to 11f,
        "twelve" to 12f,
        "halb" to 0.5f, "halbe" to 0.5f, "halben" to 0.5f, "halber" to 0.5f, "half" to 0.5f,
        "eineinhalb" to 1.5f, "anderthalb" to 1.5f, "zweieinhalb" to 2.5f, "dreieinhalb" to 3.5f,
        "viertel" to 0.25f, "quarter" to 0.25f, "dreiviertel" to 0.75f,
        "drittel" to 1f / 3f, "third" to 1f / 3f, "zweidrittel" to 2f / 3f
    )

    /** Stand-alone phrases that are a fraction of the whole. */
    private val fractionPhrases: Map<String, Float> = mapOf(
        "die hälfte" to 0.5f, "die haelfte" to 0.5f, "hälfte" to 0.5f, "haelfte" to 0.5f,
        "half" to 0.5f, "a half" to 0.5f, "one half" to 0.5f, "halb" to 0.5f, "halbe" to 0.5f,
        "ein drittel" to 1f / 3f, "drittel" to 1f / 3f, "a third" to 1f / 3f, "one third" to 1f / 3f,
        "zwei drittel" to 2f / 3f, "two thirds" to 2f / 3f,
        "ein viertel" to 0.25f, "viertel" to 0.25f, "a quarter" to 0.25f, "one quarter" to 0.25f,
        "drei viertel" to 0.75f, "dreiviertel" to 0.75f, "three quarters" to 0.75f,
        "alles" to 1f, "all" to 1f, "everything" to 1f, "ganz" to 1f, "ganze" to 1f, "whole" to 1f,
        "the whole thing" to 1f, "rest" to 1f, "der rest" to 1f, "the rest" to 1f
    )

    private val unicodeFractions: Map<Char, Float> = mapOf(
        '½' to 0.5f, '⅓' to 1f / 3f, '⅔' to 2f / 3f, '¼' to 0.25f, '¾' to 0.75f,
        '⅛' to 0.125f, '⅜' to 0.375f, '⅝' to 0.625f, '⅞' to 0.875f, '⅕' to 0.2f
    )

    fun parse(rawText: String): QuantityParse {
        val text = normalize(rawText)
        if (text.isEmpty()) return QuantityParse.Unparsed

        fractionPhrases[text]?.let { return QuantityParse.Parsed(Quantity.Fraction(it)) }

        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val (number, consumed, wasFractionLike) = parseNumber(tokens) ?: return QuantityParse.Unparsed
        val rest = tokens.drop(consumed)

        if (rest.isEmpty()) {
            return if (wasFractionLike) {
                QuantityParse.Parsed(Quantity.Fraction(number))
            } else {
                QuantityParse.Ambiguous(bareNumberCandidates(number))
            }
        }

        val unitToken = rest.first()
        val unitLower = unitToken.lowercase(Locale.ROOT)

        if (unitLower in percentWords) {
            return QuantityParse.Parsed(Quantity.Fraction(number / 100f))
        }
        massUnits[unitLower]?.let { grams ->
            return QuantityParse.Parsed(Quantity.Mass(number * grams, unitToken))
        }
        volumeUnits[unitLower]?.let { ml ->
            return QuantityParse.Parsed(Quantity.Volume(number * ml, unitToken))
        }
        if (unitLower == "fl" && rest.getOrNull(1)?.lowercase(Locale.ROOT) in setOf("oz", "ounce", "ounces")) {
            return QuantityParse.Parsed(Quantity.Volume(number * FLUID_OUNCE_ML, "fl oz"))
        }
        if (unitLower in servingWords) {
            return QuantityParse.Parsed(Quantity.Servings(number))
        }
        if (unitLower in packageWords) {
            return QuantityParse.Parsed(Quantity.Fraction(number))
        }
        // "1/3 of the pack", "ein Drittel der Packung"
        if (wasFractionLike && (unitLower in setOf("of", "der", "des", "vom", "von", "the"))) {
            return QuantityParse.Parsed(Quantity.Fraction(number))
        }
        return QuantityParse.Parsed(Quantity.Pieces(number, rest.joinToString(" ")))
    }

    private fun bareNumberCandidates(number: Float): List<Quantity> = buildList {
        add(Quantity.Servings(number))
        add(Quantity.Pieces(number, ""))
        add(Quantity.Mass(number))
        if (number > 0f && number <= 1f) add(Quantity.Fraction(number))
    }

    private fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim().lowercase(Locale.ROOT)) {
            val frac = unicodeFractions[ch]
            when {
                frac != null -> {
                    // "1½" → "1 ½"; keep the fraction as its decimal.
                    if (sb.isNotEmpty() && sb.last().isDigit()) sb.append(' ')
                    sb.append("frac:").append(frac)
                }
                ch == '%' -> sb.append(" %")
                ch == '×' || ch == 'x' && sb.isNotEmpty() && sb.last().isDigit() -> sb.append(' ')
                ch == '\t' || ch == '\n' -> sb.append(' ')
                // "70g", "250ml", "0.2kg": split the unit off the number.
                ch.isLetter() && sb.isNotEmpty() && sb.last().isDigit() -> { sb.append(' '); sb.append(ch) }
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Reads a leading number from the tokens: "1", "1,5", "1/3", "1 1/2", "drei", "eineinhalb",
     * "frac:0.5" (from unicode). Returns value, tokens consumed, and whether it was written as a
     * fraction or percent-like share (which makes a bare value a [Quantity.Fraction]).
     */
    private fun parseNumber(tokens: List<String>): Triple<Float, Int, Boolean>? {
        val first = tokens.firstOrNull() ?: return null
        var value: Float
        var consumed = 1
        var fractionLike = false

        val word = numberWords[first]
        when {
            word != null -> {
                value = word
                fractionLike = word < 1f && first !in setOf("halb", "halbe", "halben", "halber", "half")
                if (!fractionLike && word < 1f) fractionLike = true
            }
            first.startsWith("frac:") -> {
                value = first.removePrefix("frac:").toFloatOrNull() ?: return null
                fractionLike = true
            }
            first.contains('/') -> {
                val parts = first.split('/')
                if (parts.size != 2) return null
                val num = parseDecimal(parts[0]) ?: return null
                val den = parseDecimal(parts[1]) ?: return null
                if (den == 0f) return null
                value = num / den
                fractionLike = true
            }
            else -> {
                value = parseDecimal(first) ?: return null
            }
        }

        // Mixed numbers: "1 1/2", "1 ½", "one and a half", "ein halb"
        val next = tokens.getOrNull(consumed)
        if (next != null && !fractionLike && value == value.toInt().toFloat()) {
            val tail: Float? = when {
                next.contains('/') -> next.split('/').takeIf { it.size == 2 }?.let { p ->
                    val n = parseDecimal(p[0]); val d = parseDecimal(p[1])
                    if (n != null && d != null && d != 0f) n / d else null
                }
                next.startsWith("frac:") -> next.removePrefix("frac:").toFloatOrNull()
                next == "and" || next == "und" -> {
                    // "one and a half" / "zwei und ein halb"
                    val after = tokens.drop(consumed + 1)
                    val phrase = after.take(2).joinToString(" ")
                    when {
                        phrase == "a half" || phrase == "one half" || phrase == "ein halb" -> {
                            consumed += 2; 0.5f
                        }
                        after.firstOrNull() == "half" || after.firstOrNull() == "halb" -> {
                            consumed += 1; 0.5f
                        }
                        else -> null
                    }
                }
                else -> null
            }
            if (tail != null) {
                value += tail
                consumed += 1
            }
        }
        return Triple(value, consumed, fractionLike)
    }

    private fun parseDecimal(token: String): Float? {
        if (token.isEmpty()) return null
        // "1,5" and "1.5" both decimal; "1.000" is not a thousands separator here.
        val normalized = token.replace(',', '.')
        if (!normalized.all { it.isDigit() || it == '.' }) return null
        if (normalized.count { it == '.' } > 1) return null
        return normalized.toFloatOrNull()
    }
}
