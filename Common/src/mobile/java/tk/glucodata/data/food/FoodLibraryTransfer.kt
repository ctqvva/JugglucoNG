package tk.glucodata.data.food

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodInput

/**
 * The food library as a file: a table anyone can make in a spreadsheet, or have made for them.
 *
 * The export is CSV with the columns in [COLUMNS]. The import reads that, and is lenient about
 * everything a hand-made table gets differently: column order, the common names for a column
 * in a few languages, semicolons or tabs between cells, decimal commas, and a JSON list of
 * objects with the same names. Only a name and a carbohydrate figure are required.
 *
 * A row's numbers are for `per_grams` of the food; without that column they are taken as per
 * 100 g, which is how nutrition tables are written.
 */
object FoodLibraryTransfer {
    val COLUMNS = listOf(
        "name", "carbs", "protein", "fat", "per_grams", "portions",
        "absorption_min", "fiber", "kcal", "brand", "barcode", "is_liquid"
    )
    const val MIME_TYPE = "text/csv"
    const val DEFAULT_FILE_NAME = "food-library.csv"
    private const val DEFAULT_REFERENCE_GRAMS = 100f

    data class Parsed(val foods: List<JournalFoodInput>, val skipped: Int)

    private val aliases: Map<String, List<String>> = mapOf(
        "name" to listOf("name", "food", "title", "product", "название", "наименование", "продукт", "блюдо", "назва", "nazwa", "nom", "nome", "naam", "namn", "név", "ad"),
        "carbs" to listOf("carbs", "carb", "carbohydrates", "carbohydrate", "углеводы", "у", "вуглеводи", "вугляводы", "kohlenhydrate", "glucides", "carboidrati", "koolhydraten", "węglowodany", "kolhydrater", "szénhidrát", "karbonhidrat"),
        "protein" to listOf("protein", "proteins", "белки", "белок", "б", "білки", "бялкі", "eiweiß", "eiweiss", "protéines", "proteine", "eiwit", "białko", "fehérje"),
        "fat" to listOf("fat", "fats", "жиры", "жир", "ж", "жири", "тлушчы", "fett", "lipides", "grassi", "vet", "tłuszcz", "zsír", "yağ"),
        "per_grams" to listOf("per_grams", "per", "per_g", "serving_g", "serving", "portion", "portion_g", "weight", "grams", "g", "порция", "вес", "вага", "порція", "gewicht", "poids", "peso"),
        "portions" to listOf("portions", "measures"),
        "absorption_min" to listOf("absorption_min", "absorption", "minutes", "усвоение"),
        "fiber" to listOf("fiber", "fibre", "клетчатка", "клітковина", "ballaststoffe", "fibres", "fibra", "vezels", "błonnik", "rost"),
        "kcal" to listOf("kcal", "calories", "energy", "ккал", "калории", "калорії", "kalorien", "energie", "calorie", "kalorie", "kalória"),
        "brand" to listOf("brand", "бренд", "марка", "marke", "marque", "marca", "merk"),
        "is_liquid" to listOf("is_liquid", "isliquid", "liquid"),
        "barcode" to listOf("barcode", "code", "ean", "gtin", "штрихкод", "штрих-код")
    )
    private val columnByAlias: Map<String, String> =
        aliases.flatMap { (column, names) -> names.map { it to column } }.toMap()

    fun export(foods: List<JournalFood>): String {
        val out = StringBuilder(COLUMNS.joinToString(",")).append('\n')
        foods.filter { it.kept && !it.isArchived }.forEach { food ->
            out.append(
                listOf(
                    food.displayName,
                    number(food.carbsGrams),
                    food.proteinGrams?.let(::number).orEmpty(),
                    food.fatGrams?.let(::number).orEmpty(),
                    food.referenceGrams?.let(::number).orEmpty(),
                    FoodPortions.encode(food.portions),
                    food.absorptionMinutes.takeIf { food.hasOwnAbsorption }?.toString().orEmpty(),
                    food.fiberGrams?.let(::number).orEmpty(),
                    food.kcal?.let(::number).orEmpty(),
                    food.brand.orEmpty(),
                    food.barcode.orEmpty(),
                    food.isLiquid.toString()
                ).joinToString(",") { csvCell(it) }
            ).append('\n')
        }
        return out.toString()
    }

    fun parse(text: String): Parsed {
        val body = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return if (body.startsWith("[") || body.startsWith("{")) parseJson(body) else parseCsv(body)
    }

    private fun parseJson(body: String): Parsed {
        val array = runCatching {
            if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                val root = JSONObject(body)
                root.keys().asSequence().mapNotNull { root.optJSONArray(it) }.firstOrNull() ?: JSONArray()
            }
        }.getOrElse { return Parsed(emptyList(), 0) }
        var skipped = 0
        val foods = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index)
            val row = HashMap<String, String>()
            item?.keys()?.forEach { key ->
                column(key)?.let { column -> if (!item.isNull(key)) row.putIfAbsent(column, item.opt(key).toString()) }
            }
            val hasReference = item?.keys()?.asSequence()?.any { column(it) == "per_grams" } == true
            toInput(row, hasReference) ?: run { skipped++; null }
        }
        return Parsed(foods, skipped)
    }

    private fun parseCsv(body: String): Parsed {
        val lines = csvRecords(body).filter { it.isNotBlank() }
        val headerLine = lines.firstOrNull() ?: return Parsed(emptyList(), 0)
        val delimiter = listOf('\t', ';', ',').maxBy { candidate -> headerLine.count { it == candidate } }
        val header = splitCsv(headerLine, delimiter).map(::column)
        if (header.none { it == "name" } || header.none { it == "carbs" }) return Parsed(emptyList(), lines.size - 1)
        var skipped = 0
        val foods = lines.drop(1).mapNotNull { line ->
            val cells = splitCsv(line, delimiter)
            val row = HashMap<String, String>()
            header.forEachIndexed { index, column ->
                val cell = cells.getOrNull(index)?.trim().orEmpty()
                if (column != null && cell.isNotEmpty()) row.putIfAbsent(column, cell)
            }
            // Quoted decimal commas are part of a completed cell, whichever delimiter was used.
            toInput(row, hasReference = "per_grams" in header)
                ?: run { skipped++; null }
        }
        return Parsed(foods, skipped)
    }

    private fun column(header: String): String? =
        columnByAlias[header.trim().trim('"').lowercase(Locale.ROOT).replace(' ', '_').substringBefore(',').substringBefore('(').trim('_')]

    /**
     * @param hasReference whether the table has a weight column at all. Without one the numbers
     *   are per 100 g; with one, a row that leaves it empty is a serving of unrecorded weight.
     */
    private fun toInput(row: Map<String, String>, hasReference: Boolean): JournalFoodInput? {
        fun value(column: String): Float? = row[column]?.let { parseNumber(it) }
        val name = row["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val carbs = value("carbs")?.takeIf { it >= 0f } ?: return null
        val protein = value("protein")?.takeIf { it >= 0f }
        val fat = value("fat")?.takeIf { it >= 0f }
        val reference = if (hasReference) value("per_grams")?.takeIf { it > 0f } else DEFAULT_REFERENCE_GRAMS
        return JournalFoodInput(
            displayName = name.take(FoodLibrary.MAX_NAME_LENGTH),
            carbsGrams = carbs,
            proteinGrams = protein,
            fatGrams = fat,
            absorptionMinutes = value("absorption_min")?.toInt()?.coerceIn(15, 480)
                ?: FoodMath.estimatedAbsorptionMinutes(carbs, protein, fat),
            accentColor = FoodLibrary.DEFAULT_ACCENT_COLOR,
            referenceGrams = reference,
            portions = if (reference != null) FoodPortions.decode(row["portions"]) else emptyList(),
            fiberGrams = value("fiber")?.takeIf { it >= 0f },
            kcal = value("kcal")?.takeIf { it >= 0f },
            brand = row["brand"]?.trim()?.takeIf { it.isNotEmpty() },
            barcode = ProductBarcode.normalize(row["barcode"]),
            isLiquid = row["is_liquid"]?.lowercase(Locale.ROOT) in listOf("true", "1", "yes"),
            hasOwnAbsorption = value("absorption_min") != null,
            kept = true
        )
    }

    /** "12,5", "12.5 g", "100г" */
    private fun parseNumber(cell: String): Float? {
        val text = FoodNumbers.normalize(cell)
        return Regex("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?").find(text)?.value?.toFloatOrNull()?.takeIf { it.isFinite() }
    }

    private fun number(value: Float): String = value.toString().removeSuffix(".0")

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun csvRecords(text: String): List<String> {
        val records = ArrayList<String>()
        val record = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> {
                    record.append("\"\"")
                    index++
                }
                char == '"' -> { quoted = !quoted; record.append(char) }
                !quoted && (char == '\n' || char == '\r') -> {
                    records += record.toString()
                    record.setLength(0)
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> record.append(char)
            }
            index++
        }
        if (record.isNotEmpty()) records += record.toString()
        return records
    }

    internal fun splitCsv(line: String, delimiter: Char): List<String> {
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
                char == '"' -> quoted = !quoted
                !quoted && char == delimiter -> { cells += cell.toString(); cell.setLength(0) }
                else -> cell.append(char)
            }
            index++
        }
        cells += cell.toString()
        return cells
    }
}
