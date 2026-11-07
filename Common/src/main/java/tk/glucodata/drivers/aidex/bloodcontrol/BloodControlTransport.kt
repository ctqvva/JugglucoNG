package tk.glucodata.drivers.aidex.bloodcontrol

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

internal class BloodControlResponse(val status: Int, val body: ByteArray, val signature: String?)

internal fun interface BloodControlTransport {
    fun post(path: String, body: ByteArray): BloodControlResponse
}

/** A dedicated CA trust store; platform hostname verification remains enabled. */
internal class BloodControlHttpsTransport(
    ca: ByteArray,
    private val bearer: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val exchange: (String, String, ByteArray, SSLSocketFactory, String) -> BloodControlResponse = ::exchangeHttps,
) : BloodControlTransport {
    private val ssl = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("bloodcontrol", CertificateFactory.getInstance("X.509")
                .generateCertificate(ca.inputStream()))
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        init(null, managers.trustManagers, null)
    }.socketFactory
    private var preferSecondaryUntil = 0L

    @Synchronized
    override fun post(path: String, body: ByteArray): BloodControlResponse {
        val primary = "https://5.23.53.98"
        val secondary = "https://178.253.22.116"
        val routes = if (now() < preferSecondaryUntil) listOf(secondary, primary) else listOf(primary, secondary)
        for ((index, route) in routes.withIndex()) {
            try {
                val response = exchange(route, path, body, ssl, bearer)
                // A reachable gateway can still fail independently of the other route. Do not
                // parse its body here (gateway errors may be unsigned); the provider verifies
                // whichever response is finally returned before it parses or installs keys.
                if (response.status in 500..599) {
                    if (index != routes.lastIndex) continue
                    return response
                }
                preferSecondaryUntil = if (route == secondary) now() + 15 * 60_000 else 0
                return response
            } catch (e: IOException) {
                if (index == routes.lastIndex || !canFallback(e)) throw e
            }
        }
        throw IOException("No route")
    }

    companion object {
        internal fun canFallback(e: IOException): Boolean = when (e) {
            is SocketTimeoutException -> !e.message.orEmpty().contains("read timed out", ignoreCase = true)
            is ConnectException, is NoRouteToHostException, is SSLException -> true
            else -> false
        }
    }
}

private fun exchangeHttps(
    route: String, path: String, body: ByteArray, ssl: SSLSocketFactory, bearer: String,
): BloodControlResponse {
    val connection = (URL(route + path).openConnection() as HttpsURLConnection).apply {
        sslSocketFactory = ssl
        instanceFollowRedirects = false
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 12_000
        doOutput = true
        setRequestProperty("Authorization", "Bearer $bearer")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept-Encoding", "identity")
        setFixedLengthStreamingMode(body.size)
    }
    try {
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1_048_576) throw IOException("Response too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: byteArrayOf()
        return BloodControlResponse(status, bytes, connection.getHeaderField("X-BC-Server-Signature"))
    } finally {
        connection.disconnect()
    }
}
