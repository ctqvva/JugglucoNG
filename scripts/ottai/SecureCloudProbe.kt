package tk.glucodata.drivers.ottai

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Manual anonymous check using the production CN router and codec. No sensor/account access. */
fun main() {
    val deviceId = "ottai:a:n:${UUID.randomUUID()}"
    val timestamp = System.currentTimeMillis()
    val headers = mapOf("deviceId" to deviceId, "appName" to "ottai", "applicationType" to "ottai_main",
        "packageName" to "com.ottai.tag", "ua" to "Android", "timestamp" to timestamp.toString())
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
    val signature = OttaiCrypto.bytesToHex(MessageDigest.getInstance("MD5")
        .digest("ottai$deviceId$timestamp$seed".toByteArray()))
    val route = requireNotNull(OttaiRegionalTransport.route(OttaiConstants.API_BASE + OttaiConstants.EP_API_TOKEN +
        "?signature=" + URLEncoder.encode(signature, "UTF-8"), headers))
    val client = OttaiRegionalTransport(route, headers, send = ::raw)
    val response = try { requireNotNull(client.request("GET", null).json) } finally { client.close() }
    check(response.getString("code") == "OK" && response.optString("data").isNotBlank())
    println("Production CN router: secure exchange, encrypted GET and authenticated response passed (anonymous identity).")
}
