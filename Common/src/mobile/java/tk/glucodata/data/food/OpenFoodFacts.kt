package tk.glucodata.data.food

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.Log

sealed interface OpenFoodFactsResult<out T> {
    data class Found<T>(val value: T) : OpenFoodFactsResult<T>
    data object NotFound : OpenFoodFactsResult<Nothing>
    data class Failed(val message: String?) : OpenFoodFactsResult<Nothing>
}

/**
 * Open Food Facts: packaged products from all over the world, named in the language on their
 * label. Open data, no key. Nothing is sent until the person asks: a search sends the typed
 * words, a scan sends the barcode, and neither carries anything about the person.
 *
 * Searches go to the project's search service, which answers in a fraction of a second; the
 * project asks apps to keep to about ten searches a minute, which is why the app searches on
 * a button and not on every keystroke.
 */
object OpenFoodFactsClient {
    private const val TAG = "OpenFoodFacts"
    private const val PRODUCT_URL = "https://world.openfoodfacts.org/api/v2/product/"
    private const val SEARCH_URL = "https://search.openfoodfacts.org/search"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BODY_BYTES = 1024 * 1024
    const val SEARCH_PAGE_SIZE = 24

    /** The project asks for "AppName/Version (contact)" so it can tell apps from scrapers. */
    val userAgent: String = "JugglucoNG/${BuildConfig.BASE_VERSION_NAME} (https://github.com/ctqvva/JugglucoNG)"

    fun productUrl(barcode: String, language: String): String =
        PRODUCT_URL + barcode + ".json?fields=" + OpenFoodFactsParser.fields(language)

    fun searchUrl(query: String, language: String): String {
        val languages = if (language == "en") "en" else "$language,en"
        return SEARCH_URL +
            "?q=" + URLEncoder.encode(query.trim(), "UTF-8") +
            "&langs=" + languages +
            "&page_size=" + SEARCH_PAGE_SIZE +
            "&fields=" + OpenFoodFactsParser.fields(language)
    }

    /** Blocking; call off the main thread. */
    fun product(barcode: String, language: String): OpenFoodFactsResult<FoodItem> =
        when (val body = get(productUrl(barcode, language))) {
            is OpenFoodFactsResult.Found -> OpenFoodFactsParser.parseProduct(body.value, language)
                ?.let { OpenFoodFactsResult.Found(it) }
                ?: OpenFoodFactsResult.NotFound
            is OpenFoodFactsResult.NotFound -> OpenFoodFactsResult.NotFound
            is OpenFoodFactsResult.Failed -> body
        }

    /** Blocking; call off the main thread. */
    fun search(query: String, language: String): OpenFoodFactsResult<List<FoodItem>> =
        when (val body = get(searchUrl(query, language))) {
            is OpenFoodFactsResult.Found -> OpenFoodFactsResult.Found(OpenFoodFactsParser.parseSearch(body.value, language))
            is OpenFoodFactsResult.NotFound -> OpenFoodFactsResult.Found(emptyList())
            is OpenFoodFactsResult.Failed -> body
        }

    private fun get(address: String): OpenFoodFactsResult<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> OpenFoodFactsResult.NotFound
                in 200..299 -> OpenFoodFactsResult.Found(connection.inputStream.readCapped())
                else -> OpenFoodFactsResult.Failed("HTTP $code")
            }
        } catch (t: Throwable) {
            Log.stack(TAG, "request", t)
            OpenFoodFactsResult.Failed(t.message)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun InputStream.readCapped(): String = use { stream ->
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_BODY_BYTES) break
            buffer.write(chunk, 0, read)
        }
        buffer.toString(Charsets.UTF_8.name())
    }
}

/**
 * Open Food Facts JSON to [FoodItem]. Pure, so it is tested without a network.
 *
 * A product without a carbohydrate figure is dropped: a food the journal cannot count is worse
 * than no result. The project reports `*_100g` for drinks too, so "per 100 ml" is read off the
 * product's quantity unit.
 */
object OpenFoodFactsParser {
    const val KEY_PREFIX = "off:"
    private val liquidQuantity = Regex("\\d\\s*(ml|cl|dl|l)\\b")
    private val servingWeight = Regex("(\\d+(?:[.,]\\d+)?)\\s*(g|ml)\\b", RegexOption.IGNORE_CASE)
    private val servingLabel = Regex("^\\s*1\\s+([\\p{L}][\\p{L} .'\\-]{1,24}?)\\s*(?:\\(|$)")

    /** The fields asked for; everything else stays on the server. */
    fun fields(language: String): String = listOf(
        "code", "product_name", "product_name_$language", "product_name_en", "generic_name", "brands",
        "quantity", "product_quantity", "product_quantity_unit",
        "serving_size", "serving_quantity", "serving_quantity_unit", "nutriments"
    ).distinct().joinToString(",")

    fun parseProduct(body: String, language: String): FoodItem? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("status", 0) != 1) return null
        return parse(root.optJSONObject("product") ?: return null, language)
    }

    fun parseSearch(body: String, language: String): List<FoodItem> {
        val hits = runCatching { JSONObject(body).optJSONArray("hits") }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until hits.length()).mapNotNull { index ->
            hits.optJSONObject(index)?.let { parse(it, language) }?.takeIf { seen.add(it.key) }
        }
    }

    fun parse(product: JSONObject, language: String): FoodItem? {
        val barcode = product.optString("code").trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val name = listOf("product_name_$language", "product_name", "product_name_en", "generic_name")
            .map { product.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val nutriments = product.optJSONObject("nutriments") ?: return null

        val servingText = product.optString("serving_size").trim()
        val servingWeightMatch = servingWeight.find(servingText)
        val servingQuantity = product.finite("serving_quantity")
            ?: servingWeightMatch?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
        val quantityUnit = product.optString("product_quantity_unit").lowercase(Locale.ROOT)
        val servingUnit = product.optString("serving_quantity_unit").lowercase(Locale.ROOT)
            .ifEmpty { servingWeightMatch?.groupValues?.get(2)?.lowercase(Locale.ROOT).orEmpty() }
        val isLiquid = when {
            quantityUnit == "ml" -> true
            quantityUnit == "g" -> false
            servingUnit == "ml" -> true
            else -> liquidQuantity.containsMatchIn(product.optString("quantity").lowercase(Locale.ROOT))
        }

        val per100 = nutriments.finite("carbohydrates_100g")
        val perServing = nutriments.finite("carbohydrates_serving")
        val suffix: String
        val referenceGrams: Float?
        when {
            per100 != null -> {
                suffix = "_100g"
                referenceGrams = 100f
            }
            perServing != null -> {
                suffix = "_serving"
                referenceGrams = servingQuantity?.takeIf { it > 0f }
            }
            else -> return null
        }
        val carbs = nutriments.finite("carbohydrates$suffix")?.takeIf { it >= 0f } ?: return null

        val portions = buildList {
            if (referenceGrams != null) {
                servingQuantity?.takeIf { it > 0f && it != 100f }?.let { grams ->
                    add(FoodPortion(FoodPortionKind.SERVING, grams, servingLabel.find(servingText)?.groupValues?.get(1)?.trim()))
                }
                product.finite("product_quantity")
                    ?.takeIf { it > 0f && it != servingQuantity && it <= 3000f }
                    ?.let { add(FoodPortion(FoodPortionKind.PACKAGE, it)) }
            }
        }

        return FoodItem(
            key = KEY_PREFIX + barcode,
            source = FoodSource.OPEN_FOOD_FACTS,
            name = name,
            brand = product.brand(),
            referenceGrams = referenceGrams,
            carbs = carbs,
            protein = nutriments.finite("proteins$suffix")?.takeIf { it >= 0f },
            fat = nutriments.finite("fat$suffix")?.takeIf { it >= 0f },
            fiber = nutriments.finite("fiber$suffix")?.takeIf { it >= 0f },
            kcal = (nutriments.finite("energy-kcal$suffix") ?: nutriments.finite("energy-kj$suffix")?.let { it / 4.184f })
                ?.takeIf { it >= 0f },
            portions = portions,
            isLiquid = isLiquid,
            barcode = barcode
        )
    }

    /** The product page gives brands as "A, B"; the search service as ["A", "B"]. */
    private fun JSONObject.brand(): String? {
        val value = opt("brands")
        val first = when (value) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }.firstOrNull { it.isNotBlank() }
            is String -> value.split(',').firstOrNull { it.isNotBlank() }
            else -> null
        }
        return first?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.finite(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        val number = when (val value = opt(key)) {
            is Number -> value.toFloat()
            is String -> value.replace(',', '.').toFloatOrNull()
            else -> null
        }
        return number?.takeIf { it.isFinite() }
    }
}

/**
 * Retail barcodes as a scanner or a keyboard delivers them: EAN-8, UPC-A, EAN-13, GTIN-14.
 * Anything else, a sensor's QR code included, fails the check digit and is turned away before
 * it is looked up.
 */
object ProductBarcode {
    /** The lookup key (UPC-A padded to 13 digits), or null. */
    fun normalize(raw: String?): String? {
        val digits = raw?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        if (digits.length !in setOf(8, 12, 13, 14)) return null
        if (!hasValidCheckDigit(digits)) return null
        return if (digits.length == 12) "0$digits" else digits
    }

    fun hasValidCheckDigit(digits: String): Boolean {
        if (digits.length < 8 || !digits.all(Char::isDigit)) return false
        var sum = 0
        for (i in digits.length - 2 downTo 0) {
            val digit = digits[i] - '0'
            sum += if ((digits.length - 2 - i) % 2 == 0) digit * 3 else digit
        }
        return (10 - sum % 10) % 10 == digits.last() - '0'
    }
}
