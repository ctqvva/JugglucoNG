package tk.glucodata.data.meal

import java.util.Locale

/**
 * One OCR line with its place on the photo. Heights let the name guesser prefer the big print on
 * a front-of-pack photo; the y/x order lets the table parser keep the label's reading order.
 */
data class OcrLine(
    val text: String,
    val top: Int = 0,
    val left: Int = 0,
    val height: Int = 0
)

/** What the nutrition-table parser could read, each value with the line it came from. */
data class LabelNutrition(
    val facts: NutritionFacts?,
    val basis: NutritionBasis,
    val servingText: String?,
    /** Lines that supplied a value, for the "derivation" view and for debugging bad OCR. */
    val evidence: Map<String, String>
) {
    val isUsable: Boolean get() = facts != null
}

/**
 * Deterministic reading of an OCR'd nutrition table. No model: keywords in the maintained
 * languages, the first number after the keyword, the column picked from the table header
 * ("per 100 g" before or after "per portion"). Unreadable values stay null — the form shows them
 * empty rather than inventing a zero — and everything goes through the user's confirmation.
 */
object NutritionLabelParser {
    private val carbsWords = listOf(
        "kohlenhydrate", "kohlenhydrat", "carbohydrate", "carbohydrates", "carbs", "glucides", "carboidrati",
        "koolhydraten", "węglowodany", "weglowodany", "hidratos de carbono", "hidratos", "углеводы", "вуглеводи",
        "вугляводы", "kolhydrater", "kolhydrat", "karbonhidrat", "karbohaydrayt", "нүүрс ус", "碳水化合物"
    )
    private val sugarsWords = listOf(
        "davon zucker", "zucker", "of which sugars", "sugars", "sugar", "dont sucres", "sucres", "di cui zuccheri",
        "zuccheri", "waarvan suikers", "suikers", "w tym cukry", "cukry", "dos quais açúcares", "açúcares", "сахара",
        "в том числе сахара", "цукри", "цукры", "varav sockerarter", "sockerarter", "şeker", "сахар", "糖"
    )
    private val fatWords = listOf(
        "fett", "fat", "matières grasses", "matieres grasses", "lipides", "grassi", "vet", "vetten", "tłuszcz", "tluszcz",
        "lípidos", "gordura", "gorduras", "жиры", "жири", "тлушчы", "fetter", "yağ", "yag", "dufan", "өөх тос", "脂肪"
    )
    private val saturatesWords = listOf(
        "gesättigte", "gesattigte", "saturates", "saturated", "acides gras saturés", "saturés", "saturi", "verzadigd",
        "verzadigde", "nasycone", "saturados", "насыщенные", "насичені", "mättat", "doymuş", "饱和"
    )
    private val fiberWords = listOf(
        "ballaststoffe", "fibre", "fiber", "fibres alimentaires", "fibres", "fibra", "fibre alimentari", "vezels",
        "voedingsvezels", "błonnik", "blonnik", "пищевые волокна", "клетчатка", "харчові волокна", "клітковина",
        "клятчатка", "kostfiber", "fibrer", "lif", "эслэг", "膳食纤维"
    )
    private val proteinWords = listOf(
        "eiweiß", "eiweiss", "eiweis", "protein", "proteins", "protéines", "proteines", "proteine", "eiwit", "eiwitten",
        "białko", "bialko", "proteína", "proteínas", "proteina", "белки", "белок", "білки", "бялкі", "porotiin",
        "уураг", "蛋白质"
    )
    private val polyolWords = listOf("mehrwertige alkohole", "polyols", "polyole", "polioli", "zuckeralkohole", "polyalcools")
    private val energyWords = listOf("brennwert", "energie", "energy", "énergie", "energia", "energi", "enerji", "энергетическая", "енергетична", "калорийность", "энергия", "tamar", "энерги", "能量")
    private val saltWords = listOf("salz", "salt", "sel", "sale", "zout", "sól", "sol", "соль", "сіль", "tuz", "盐")
    private val servingHeaderWords = listOf("portion", "serving", "porzione", "portie", "porcja", "porção", "порция", "порція", "порцыя", "porsiyon", "per piece", "pro stück", "je stück", "riegel", "bar", "份")

    private val numberRegex = Regex("""<?\s*(\d+(?:[.,]\d+)?)\s*(kcal|kj|g|mg|ml|%)?""", RegexOption.IGNORE_CASE)

    fun parseLines(lines: List<String>): LabelNutrition = parse(lines.map { OcrLine(it) })

    fun parse(ocr: List<OcrLine>): LabelNutrition {
        val ordered = ocr.sortedWith(compareBy({ it.top }, { it.left })).map { it.text.trim() }.filter { it.isNotEmpty() }
        val evidence = mutableMapOf<String, String>()

        // Column order: where "100 g/ml" and "portion" sit in the header decides which number
        // on a row is the per-100 value. Default: first number.
        var basis = NutritionBasis.PER_100G
        var perHundredIndex = 0
        var servingText: String? = null
        for (line in ordered) {
            val lower = line.lowercase(Locale.ROOT).replace(' ', ' ')
            val hundredG = Regex("""100\s*g""").find(lower)
            val hundredMl = Regex("""100\s*ml""").find(lower)
            val hundred = hundredMl ?: hundredG
            if (hundred != null) {
                if (hundredMl != null) basis = NutritionBasis.PER_100ML
                val servingPos = servingHeaderWords.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull()
                if (servingPos != null && servingPos < hundred.range.first) perHundredIndex = 1
                Regex("""(\d+(?:[.,]\d+)?)\s*(g|ml)""").findAll(lower)
                    .firstOrNull { !it.groupValues[1].startsWith("100") }
                    ?.let { servingText = "${it.groupValues[1]} ${it.groupValues[2]}" }
                break
            }
        }

        fun numbersOn(line: String, afterKeyword: String): List<Pair<Float, String?>> {
            val lower = line.lowercase(Locale.ROOT)
            val start = lower.indexOf(afterKeyword).let { if (it >= 0) it + afterKeyword.length else 0 }
            return numberRegex.findAll(lower.substring(start)).mapNotNull { m ->
                val value = m.groupValues[1].replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
                value to m.groupValues[2].takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            }.toList()
        }

        fun pick(values: List<Pair<Float, String?>>, wantUnits: Set<String>): Float? {
            val candidates = values.filter { (_, unit) -> unit == null || unit in wantUnits }
            if (candidates.isEmpty()) return null
            // Percent columns (reference intake) are never the value we want.
            val filtered = candidates.filter { it.second != "%" }
            val index = perHundredIndex.coerceAtMost(filtered.size - 1)
            return filtered.getOrNull(index)?.first
        }

        fun findValue(words: List<String>, excludeWords: List<String> = emptyList(), units: Set<String> = setOf("g", "mg")): Pair<Float?, String?> {
            for (line in ordered) {
                val lower = line.lowercase(Locale.ROOT)
                val keyword = words.firstOrNull { w -> lower.contains(w) } ?: continue
                if (excludeWords.any { lower.contains(it) }) continue
                val numbers = numbersOn(line, keyword)
                val value = pick(numbers, units)
                if (value != null) {
                    val grams = if (numbers.firstOrNull { it.first == value }?.second == "mg") value / 1000f else value
                    return grams to line
                }
                // Value on the next line (OCR split the row): take the very next numeric line.
                val next = ordered.getOrNull(ordered.indexOf(line) + 1) ?: continue
                val nextNumbers = numbersOn(next, "")
                if (nextNumbers.isNotEmpty() && !nextNumbers.any { it.second == "kcal" || it.second == "kj" }) {
                    pick(nextNumbers, units)?.let { return it to "$line / $next" }
                }
            }
            return null to null
        }

        val (carbs, carbsLine) = findValue(carbsWords, excludeWords = sugarsWords.filter { it != "zucker" && it != "sugar" && it != "sugars" } + polyolWords + listOf("davon", "of which", "dont", "di cui", "waarvan", "w tym"))
        val (sugars, sugarsLine) = findValue(sugarsWords)
        val (fat, fatLine) = findValue(fatWords, excludeWords = saturatesWords)
        val (fiber, fiberLine) = findValue(fiberWords)
        val (protein, proteinLine) = findValue(proteinWords)
        val (polyols, polyolsLine) = findValue(polyolWords)

        var kcal: Float? = null
        var kcalLine: String? = null
        for ((index, line) in ordered.withIndex()) {
            val lower = line.lowercase(Locale.ROOT)
            if (!energyWords.any { lower.contains(it) } && !lower.contains("kcal")) continue
            // UK tables often put "2069kJ 517kJ" on one line and "496kcal 124kcal" on the next.
            val numbers = numbersOn(line, "")
            val nextLine = ordered.getOrNull(index + 1)
            val nextNumbers = nextLine?.let { numbersOn(it, "") }.orEmpty()
            val kcalValues = numbers.filter { it.second == "kcal" }.ifEmpty { nextNumbers.filter { it.second == "kcal" } }
            val kjValues = numbers.filter { it.second == "kj" }
            val chosen = kcalValues.getOrNull(perHundredIndex.coerceAtMost(kcalValues.size - 1))?.first
                ?: kjValues.getOrNull(perHundredIndex.coerceAtMost(kjValues.size - 1))?.first?.div(4.184f)
            if (chosen != null) {
                kcal = chosen
                kcalLine = if (numbers.none { it.second == "kcal" } && kcalValues.isNotEmpty()) "$line / $nextLine" else line
                break
            }
        }

        carbsLine?.let { evidence["carbs"] = it }
        sugarsLine?.let { evidence["sugars"] = it }
        fatLine?.let { evidence["fat"] = it }
        fiberLine?.let { evidence["fiber"] = it }
        proteinLine?.let { evidence["protein"] = it }
        polyolsLine?.let { evidence["polyols"] = it }
        kcalLine?.let { evidence["kcal"] = it }

        val facts = carbs?.let {
            NutritionFacts(
                carbsGrams = it,
                proteinGrams = protein,
                fatGrams = fat,
                fiberGrams = fiber,
                sugarsGrams = sugars,
                polyolsGrams = polyols,
                kcal = kcal
            )
        }
        return LabelNutrition(facts = facts, basis = basis, servingText = servingText, evidence = evidence)
    }

    /** True for a line that is obviously table furniture, used by the name guesser to skip it. */
    internal fun looksLikeNutritionText(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return (carbsWords + sugarsWords + fatWords + fiberWords + proteinWords + energyWords + saltWords)
            .any { lower.contains(it) } || Regex("""\d\s*(g|kcal|kj|ml)\b""").containsMatchIn(lower)
    }
}

/** Net quantity read off the packaging: "500 g", "1 L", "330 ml", "6 x 25 g", "500g ℮". */
data class PackQuantity(val quantity: Float, val unit: AmountUnit, val evidence: String)

object PackQuantityParser {
    private val single = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b\s*(℮|e\b)?""", RegexOption.IGNORE_CASE)
    private val multi = Regex("""(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>): PackQuantity? {
        var best: PackQuantity? = null
        var bestScore = -1
        for (line in lines) {
            val lower = line.lowercase(Locale.ROOT)
            // Per-100 and per-serving amounts from a nutrition table are not the pack size.
            if (lower.contains("100 g") || lower.contains("100g") || lower.contains("100 ml") || lower.contains("100ml")) continue
            if (NutritionLabelParser.looksLikeNutritionText(line) && !lower.contains("℮") && !Regex("""\d\s*(kg|g|ml|cl|l)\s*e\b""").containsMatchIn(lower)) {
                // A nutrition row like "Fett 27 g" must not win; a lone "500 g e" may.
                if (Regex("""[a-zäöüß]{4,}""").containsMatchIn(lower.replace(Regex("""\d+(?:[.,]\d+)?\s*(kg|g|ml|cl|l)"""), ""))) continue
            }
            multi.find(lower)?.let { m ->
                val count = m.groupValues[1].toFloatOrNull() ?: return@let
                val each = m.groupValues[2].replace(',', '.').toFloatOrNull() ?: return@let
                val (q, u) = toBase(count * each, m.groupValues[3]) ?: return@let
                val score = 3
                if (score > bestScore) { best = PackQuantity(q, u, line.trim()); bestScore = score }
            }
            single.find(lower)?.let { m ->
                val value = m.groupValues[1].replace(',', '.').toFloatOrNull() ?: return@let
                val (q, u) = toBase(value, m.groupValues[2]) ?: return@let
                val hasE = m.groupValues[3].isNotEmpty()
                val score = if (hasE) 2 else 1
                if (score > bestScore) { best = PackQuantity(q, u, line.trim()); bestScore = score }
            }
        }
        return best
    }

    private fun toBase(value: Float, unit: String): Pair<Float, AmountUnit>? = when (unit.lowercase(Locale.ROOT)) {
        "g" -> value to AmountUnit.GRAM
        "kg" -> value * 1000f to AmountUnit.GRAM
        "ml" -> value to AmountUnit.MILLILITER
        "cl" -> value * 10f to AmountUnit.MILLILITER
        "l" -> value * 1000f to AmountUnit.MILLILITER
        else -> null
    }
}

/**
 * Name and brand candidates from a front-of-pack photo: the tallest text lines that are not
 * numbers, units or nutrition words. The user picks; nothing is chosen silently.
 */
object ProductNameGuesser {
    fun candidates(lines: List<OcrLine>, max: Int = 4): List<String> {
        return lines
            .asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.length in 2..60 }
            .filter { line -> line.text.count { it.isLetter() } >= line.text.length / 2 }
            .filter { !NutritionLabelParser.looksLikeNutritionText(it.text) }
            .filter { !Regex("""^\d+[\d.,]*\s*(g|ml|kg|l|%|kcal)?$""").matches(it.text.lowercase(Locale.ROOT)) }
            .sortedByDescending { it.height }
            .map { it.text }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(max)
            .toList()
    }
}
