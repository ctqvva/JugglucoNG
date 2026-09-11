package tk.glucodata.data.meal

import java.util.Locale

/** What a serving-size string like "1 Riegel (25 g)" or "2 Scheiben (60g)" says. */
data class ServingSpec(
    val pieces: Float?,
    val pieceLabel: String?,
    val quantity: Float?,
    val unit: AmountUnit?
)

/**
 * Parses Open Food Facts `serving_size` text. The numeric `serving_quantity` field is preferred
 * when present; this parser supplies the piece count and label ("1 Riegel"), and falls back to
 * the bracketed amount when the numeric field is missing.
 */
object ServingSizeParser {
    private val amountRegex = Regex("(\\d+(?:[.,]\\d+)?)\\s*(g|gr|gramm|gram|grams|kg|ml|millilitre|milliliter|cl|dl|l)\\b", RegexOption.IGNORE_CASE)
    private val leadingCountRegex = Regex("^\\s*(\\d+(?:[.,]\\d+)?|½|¼|¾|⅓)\\s*([\\p{L}][\\p{L} .-]*?)\\s*(?:\\(|$|,|\\d)")

    fun parse(text: String?): ServingSpec? {
        val source = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var quantity: Float? = null
        var unit: AmountUnit? = null
        // Take the last amount in the text: "1 Riegel (25 g)" → 25 g; "30 g" → 30 g.
        amountRegex.findAll(source).lastOrNull()?.let { m ->
            val number = m.groupValues[1].replace(',', '.').toFloatOrNull()
            val unitText = m.groupValues[2].lowercase(Locale.ROOT)
            if (number != null) {
                when (unitText) {
                    "g", "gr", "gramm", "gram", "grams" -> { quantity = number; unit = AmountUnit.GRAM }
                    "kg" -> { quantity = number * 1000f; unit = AmountUnit.GRAM }
                    "ml", "millilitre", "milliliter" -> { quantity = number; unit = AmountUnit.MILLILITER }
                    "cl" -> { quantity = number * 10f; unit = AmountUnit.MILLILITER }
                    "dl" -> { quantity = number * 100f; unit = AmountUnit.MILLILITER }
                    "l" -> { quantity = number * 1000f; unit = AmountUnit.MILLILITER }
                }
            }
        }
        var pieces: Float? = null
        var label: String? = null
        leadingCountRegex.find(source)?.let { m ->
            val countText = m.groupValues[1]
            val count = when (countText) {
                "½" -> 0.5f; "¼" -> 0.25f; "¾" -> 0.75f; "⅓" -> 1f / 3f
                else -> countText.replace(',', '.').toFloatOrNull()
            }
            val word = m.groupValues[2].trim().trimEnd('.', ',')
            val wordLower = word.lowercase(Locale.ROOT)
            val isUnitWord = wordLower in setOf(
                "g", "gr", "gramm", "gram", "grams", "kg", "ml", "cl", "dl", "l", "millilitre", "milliliter"
            )
            if (count != null && word.isNotEmpty() && !isUnitWord) {
                pieces = count
                label = word
            }
        }
        if (quantity == null && pieces == null) return null
        return ServingSpec(pieces = pieces, pieceLabel = label, quantity = quantity, unit = unit)
    }
}
