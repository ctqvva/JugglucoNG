package tk.glucodata.data.meal

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import tk.glucodata.BuildConfig
import tk.glucodata.Log

sealed class ProductLookupResult {
    data class Found(val product: ScannedProduct) : ProductLookupResult()
    object NotFound : ProductLookupResult()
    data class Failed(val message: String?) : ProductLookupResult()
}

/**
 * Open Food Facts read API. No key, open data, product reads are rate-limited to 15/min per IP,
 * which is why the caller caches by barcode and only comes here on a miss. Only the barcode
 * leaves the device.
 */
object OpenFoodFactsClient {
    private const val TAG = "OpenFoodFacts"
    private const val BASE_URL = "https://world.openfoodfacts.org/api/v2/product/"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BODY_BYTES = 512 * 1024

    /** OFF asks for "AppName/Version (contact)" so it can tell apps from scrapers. */
    val userAgent: String = "JugglucoNG/${BuildConfig.BASE_VERSION_NAME} (https://github.com/ctqvva/JugglucoNG)"

    fun productUrl(barcode: String): String =
        "$BASE_URL$barcode.json?fields=${OpenFoodFactsParser.REQUESTED_FIELDS}"

    /** Blocking; call off the main thread. */
    fun lookup(barcode: String): ProductLookupResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(productUrl(barcode))
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND -> ProductLookupResult.NotFound
                code in 200..299 -> {
                    val body = connection.inputStream.readCapped(MAX_BODY_BYTES)
                    val product = OpenFoodFactsParser.parse(barcode, body)
                    if (product != null) ProductLookupResult.Found(product) else ProductLookupResult.NotFound
                }
                else -> ProductLookupResult.Failed("HTTP $code")
            }
        } catch (t: Throwable) {
            Log.stack(TAG, "lookup $barcode", t)
            ProductLookupResult.Failed(t.message)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun InputStream.readCapped(maxBytes: Int): String {
        return use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > maxBytes) break
                buffer.write(chunk, 0, read)
            }
            buffer.toString(Charsets.UTF_8.name())
        }
    }
}
