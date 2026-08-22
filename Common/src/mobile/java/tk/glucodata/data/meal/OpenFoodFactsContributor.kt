package tk.glucodata.data.meal

import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.Log

/** One photo to send with a contribution, typed the way Open Food Facts selects images. */
data class ContributionPhoto(val file: File, val kind: LabelPhotoKind)

enum class LabelPhotoKind(val storageValue: String, val offImageField: String?) {
    FRONT("front", "front"),
    NUTRITION("nutrition", "nutrition"),
    INGREDIENTS("ingredients", "ingredients"),
    PACKAGING("packaging", "packaging"),
    OTHER("other", null);

    companion object {
        fun fromStorage(value: String?): LabelPhotoKind = entries.firstOrNull { it.storageValue == value } ?: OTHER
    }
}

sealed class ContributionResult {
    data class Sent(val photosSent: Int, val photosFailed: Int) : ContributionResult()
    data class Rejected(val message: String?) : ContributionResult()
    data class Failed(val message: String?) : ContributionResult()
}

/**
 * Writes a product the user confirmed from a label photo back to Open Food Facts, so the next
 * person (or the next phone) scanning the same barcode gets a hit. Everything here is opt-in
 * and runs only after the user confirmed the values in the product form.
 *
 * Verified against the OFF OpenAPI (docs/api/ref/api.yaml, 2026-08-22): product fields go to
 * `POST /cgi/product_jqm2.pl` as multipart form data with `user_id`/`password`, photos to
 * `POST /cgi/product_image_upload.pl` with `imagefield=<kind>_<lang>` and the file in
 * `imgupload_<imagefield>`; `comment`, `app_name`, `app_version` and a privacy-preserving
 * `app_uuid` identify the contribution. Writes need an OFF account — there is no anonymous path.
 */
object OpenFoodFactsContributor {
    private const val TAG = "OffContribute"
    const val PRODUCTION_HOST = "https://world.openfoodfacts.org"
    private const val EDIT_PATH = "/cgi/product_jqm2.pl"
    private const val IMAGE_PATH = "/cgi/product_image_upload.pl"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    const val APP_NAME = "JugglucoNG"

    /** The multipart fields for one product, separated out so they can be unit-tested. */
    fun productFields(
        barcode: String,
        product: ScannedProduct,
        userId: String,
        password: String,
        languageCode: String,
        appUuid: String?,
        comment: String
    ): Map<String, String> {
        val lc = languageCode.lowercase(Locale.ROOT).take(2).ifBlank { "en" }
        val fields = linkedMapOf(
            "code" to barcode,
            "user_id" to userId,
            "password" to password,
            "lc" to lc,
            "lang" to lc,
            "product_name_$lc" to product.displayName,
            "comment" to comment,
            "app_name" to APP_NAME,
            "app_version" to BuildConfig.BASE_VERSION_NAME
        )
        appUuid?.takeIf { it.isNotBlank() }?.let { fields["app_uuid"] = it }
        product.brand?.takeIf { it.isNotBlank() }?.let { fields["brands"] = it }
        product.category?.takeIf { it.isNotBlank() }?.let { fields["categories"] = it }
        val ref = product.reference
        ref.netQuantity?.takeIf { it > 0f }?.let { net ->
            fields["quantity"] = "${formatNumber(net)} ${ref.netUnit?.symbol ?: "g"}"
        }
        ref.servingText?.takeIf { it.isNotBlank() }?.let { fields["serving_size"] = it }
            ?: ref.servingQuantity?.takeIf { it > 0f }?.let { q ->
                fields["serving_size"] = "${formatNumber(q)} ${ref.servingUnit?.symbol ?: "g"}"
            }
        fields["nutrition_data_per"] = when (ref.basis) {
            NutritionBasis.PER_SERVING -> "serving"
            else -> "100g"
        }
        val facts = product.facts
        fun put(name: String, value: Float?, unit: String = "g") {
            val v = value?.takeIf { it.isFinite() && it >= 0f } ?: return
            fields["nutriment_$name"] = formatNumber(v)
            fields["nutriment_${name}_unit"] = unit
        }
        put("carbohydrates", facts.carbsGrams)
        put("sugars", facts.sugarsGrams)
        put("fat", facts.fatGrams)
        put("saturated-fat", facts.saturatedFatGrams)
        put("salt", facts.saltGrams)
        put("fiber", facts.fiberGrams)
        put("proteins", facts.proteinGrams)
        put("polyols", facts.polyolsGrams)
        put("energy-kcal", facts.kcal, unit = "kcal")
        return fields
    }

    /** Blocking; call off the main thread. Returns what happened so the UI can say it. */
    fun contribute(
        barcode: String,
        product: ScannedProduct,
        userId: String,
        password: String,
        languageCode: String,
        appUuid: String?,
        comment: String,
        photos: List<ContributionPhoto>,
        host: String = PRODUCTION_HOST
    ): ContributionResult {
        val fields = productFields(barcode, product, userId, password, languageCode, appUuid, comment)
        val response = postMultipart("$host$EDIT_PATH", fields, file = null, fileField = null)
            ?: return ContributionResult.Failed("no response")
        if (response.first !in 200..299) return ContributionResult.Failed("HTTP ${response.first}")
        val json = runCatching { JSONObject(response.second) }.getOrNull()
        val status = json?.optInt("status", -1) ?: -1
        if (status != 1) {
            return ContributionResult.Rejected(json?.optString("status_verbose")?.takeIf { it.isNotBlank() } ?: response.second.take(120))
        }
        val lc = languageCode.lowercase(Locale.ROOT).take(2).ifBlank { "en" }
        var sent = 0
        var failed = 0
        for (photo in photos) {
            val imageField = photo.kind.offImageField?.let { "${it}_$lc" } ?: "other"
            val photoFields = linkedMapOf(
                "code" to barcode,
                "user_id" to userId,
                "password" to password,
                "imagefield" to imageField,
                "comment" to comment,
                "app_name" to APP_NAME,
                "app_version" to BuildConfig.BASE_VERSION_NAME
            )
            appUuid?.takeIf { it.isNotBlank() }?.let { photoFields["app_uuid"] = it }
            val result = postMultipart("$host$IMAGE_PATH", photoFields, file = photo.file, fileField = "imgupload_$imageField")
            if (result != null && result.first in 200..299 && imageUploadSucceeded(result.second)) sent++ else failed++
        }
        return ContributionResult.Sent(sent, failed)
    }

    /**
     * The image endpoint does not answer `status: 1` like the product endpoint; it says
     * `"status": "status ok"` and returns an `imgid` (docs/api/ref/responses/add_photo_to_existing_product.yaml).
     */
    fun imageUploadSucceeded(body: String): Boolean {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val status = json.opt("status")
        return when (status) {
            is Number -> status.toInt() == 1
            is String -> status.trim().lowercase(Locale.ROOT).let { it == "status ok" || it.startsWith("status ok") } || json.has("imgid")
            else -> json.has("imgid")
        }
    }

    private fun formatNumber(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    /** Returns (HTTP code, body) or null on a transport error. */
    private fun postMultipart(url: String, fields: Map<String, String>, file: File?, fileField: String?): Pair<Int, String>? {
        var connection: HttpURLConnection? = null
        return try {
            val boundary = "----JugglucoNG" + System.currentTimeMillis().toString(16)
            connection = (URL(url).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", OpenFoodFactsClient.userAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(connection.outputStream).use { out ->
                for ((name, value) in fields) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n")
                    out.writeBytes("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                if (file != null && fileField != null) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n")
                    out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                    file.inputStream().use { it.copyTo(out) }
                    out.writeBytes("\r\n")
                }
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to body
        } catch (t: Throwable) {
            Log.stack(TAG, "POST $url", t)
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
