package tk.glucodata.drivers.ottai

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Optional anonymous check: production regional router/codec, no account or sensor access. */
fun main() {
    val deviceId = "ottai-watch:a:${UUID.randomUUID().toString().replace("-", "").take(16)}"
    val timestamp = System.currentTimeMillis()
    val headers = mapOf("deviceId" to deviceId, "appName" to "ottai-watch",
        "packageName" to "com.ottai.tag.watch", "ua" to "Android_Watch_Ottai_Arc",
        "country" to "US", "language" to "en", "timezone" to "0", "timeZoneName" to "UTC",
        "timestamp" to timestamp.toString(), "traceId" to UUID.randomUUID().toString())
    val seedSource = File("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiCloudClient.kt").readText()
    val seed = requireNotNull(Regex("const val SEED\\s*=\\s*\"([^\"]+)\"").find(seedSource)).groupValues[1]
    fun raw(method: String, url: String, body: String?, headers: Map<String, String>): OttaiNetSecurity.Reply {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            OttaiNetSecurity.Reply(status, stream?.bufferedReader()?.use { JSONObject(it.readText()) })
        } finally { connection.disconnect() }
    }
    for ((base, web) in listOf(OttaiConstants.API_BASE_GLOBAL to false,
        OttaiConstants.API_BASE_SYAI to false, OttaiConstants.WEB_BASE_SYAI to true)) {
        val common = if (web) mapOf("appName" to "cgm", "timestamp" to timestamp.toString(),
            "deviceFingerprinting" to UUID.randomUUID().toString().replace("-", ""),
            "country" to "US", "language" to "en", "ua" to "Android", "timezone" to "0",
            "traceId" to UUID.randomUUID().toString()) else headers
        val preimage = if (web) "cgm$timestamp$seed" else "ottai-watch$deviceId$timestamp$seed"
        val signature = OttaiCrypto.bytesToHex(MessageDigest.getInstance("MD5").digest(preimage.toByteArray()))
        val url = base + (if (web) "/user/apiToken" else OttaiConstants.EP_API_TOKEN) +
            "?signature=" + URLEncoder.encode(signature, "UTF-8")
        val route = requireNotNull(OttaiRegionalTransport.route(url))
        val client = OttaiRegionalTransport(route, common, send = ::raw)
        try {
            val response = requireNotNull(client.request("GET", null).json)
            check(response.getString("code") == "OK" && response.optString("data").isNotBlank()) {
                "$base: code=${response.optString("code")}"
            }
            println("$base: production secure exchange, encrypted GET and authenticated response passed.")
        } finally { client.close() }
    }
}
