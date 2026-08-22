package tk.glucodata.data.meal

import org.json.JSONObject
import java.util.Locale

/**
 * Open Food Facts v2 product JSON → [ScannedProduct]. Pure and testable; the network lives in
 * [OpenFoodFactsClient].
 *
 * One quirk worth knowing: OFF reports `nutrition_data_per: "100g"` and `*_100g` keys for
 * liquids too, so the per-100-ml basis is inferred from the product's quantity or serving unit.
 */
object OpenFoodFactsParser {
    /** The product fields the app asks for; anything else is left on the server. */
    const val REQUESTED_FIELDS = "code,product_name,product_name_de,product_name_en,generic_name," +
        "brands,quantity,product_quantity,product_quantity_unit,serving_size,serving_quantity," +
        "serving_quantity_unit,nutrition_data_per,nutriments"

    fun parse(barcode: String, body: String): ScannedProduct? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return parse(barcode, root)
    }

    fun parse(barcode: String, root: JSONObject): ScannedProduct? {
        if (root.optInt("status", 0) != 1) return null
        val product = root.optJSONObject("product") ?: return null
        val nutriments = product.optJSONObject("nutriments") ?: JSONObject()

        val quantityUnit = unitOf(product.optString("product_quantity_unit"))
        val servingUnit = unitOf(product.optString("serving_quantity_unit"))
        val quantityText = product.optString("quantity").lowercase(Locale.ROOT)
        val liquidByText = Regex("\\d\\s*(ml|cl|dl|l)\\b").containsMatchIn(quantityText)
        val isLiquid = quantityUnit == AmountUnit.MILLILITER ||
            (quantityUnit == null && (servingUnit == AmountUnit.MILLILITER || liquidByText))

        val per = product.optString("nutrition_data_per").lowercase(Locale.ROOT)
        val has100 = nutriments.hasFinite("carbohydrates_100g")
        val hasServing = nutriments.hasFinite("carbohydrates_serving")
        val suffix: String
        val basis: NutritionBasis
        when {
            has100 -> {
                suffix = "_100g"
                basis = if (isLiquid) NutritionBasis.PER_100ML else NutritionBasis.PER_100G
            }
            hasServing && per == "serving" -> {
                suffix = "_serving"
                basis = NutritionBasis.PER_SERVING
            }
            hasServing -> {
                suffix = "_serving"
                basis = NutritionBasis.PER_SERVING
            }
            else -> return null
        }

        val facts = NutritionFacts(
            carbsGrams = nutriments.finite("carbohydrates$suffix") ?: return null,
            proteinGrams = nutriments.finite("proteins$suffix"),
            fatGrams = nutriments.finite("fat$suffix"),
            fiberGrams = nutriments.finite("fiber$suffix"),
            sugarsGrams = nutriments.finite("sugars$suffix"),
            polyolsGrams = nutriments.finite("polyols$suffix"),
            kcal = nutriments.finite("energy-kcal$suffix")
                ?: nutriments.finite("energy-kj$suffix")?.let { it / 4.184f },
            saturatedFatGrams = nutriments.finite("saturated-fat$suffix"),
            saltGrams = nutriments.finite("salt$suffix")
        )

        val servingText = product.optString("serving_size").trim().takeIf { it.isNotEmpty() }
        val servingSpec = ServingSizeParser.parse(servingText)
        val servingQuantity = product.optFinite("serving_quantity") ?: servingSpec?.quantity
        val resolvedServingUnit = when {
            product.optFinite("serving_quantity") != null -> servingUnit ?: servingSpec?.unit
                ?: if (isLiquid) AmountUnit.MILLILITER else AmountUnit.GRAM
            servingSpec?.quantity != null -> servingSpec.unit
            else -> null
        }

        val netQuantity = product.optFinite("product_quantity")
        val netUnit = when {
            netQuantity == null -> null
            quantityUnit != null -> quantityUnit
            isLiquid -> AmountUnit.MILLILITER
            else -> AmountUnit.GRAM
        }

        val name = listOf("product_name", "product_name_de", "product_name_en", "generic_name")
            .map { product.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "EAN $barcode"
        val brand = product.optString("brands").split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }

        return ScannedProduct(
            barcode = barcode,
            displayName = name,
            brand = brand,
            source = NutritionSource.OPEN_FOOD_FACTS,
            facts = facts,
            reference = NutritionReference(
                basis = basis,
                netQuantity = netQuantity?.takeIf { it > 0f },
                netUnit = netUnit,
                servingText = servingText,
                servingQuantity = servingQuantity?.takeIf { it > 0f },
                servingUnit = resolvedServingUnit,
                servingPieces = servingSpec?.pieces,
                servingPieceLabel = servingSpec?.pieceLabel
            )
        )
    }

    private fun unitOf(raw: String?): AmountUnit? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "g", "gr", "gram", "grams" -> AmountUnit.GRAM
        "ml", "millilitre", "milliliter" -> AmountUnit.MILLILITER
        // OFF normalises product_quantity to g/ml, but be tolerant.
        "l", "cl", "dl" -> AmountUnit.MILLILITER
        "kg", "mg" -> AmountUnit.GRAM
        else -> null
    }

    private fun JSONObject.hasFinite(key: String): Boolean = finite(key) != null

    private fun JSONObject.finite(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        val number = when (value) {
            is Number -> value.toFloat()
            is String -> value.replace(',', '.').toFloatOrNull()
            else -> null
        } ?: return null
        return number.takeIf { it.isFinite() }
    }

    private fun JSONObject.optFinite(key: String): Float? = finite(key)
}
