package tk.glucodata.drivers.ottai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OttaiOtaIdentityTests {
    @Test fun signedInGlobalAndSyaiAccountsAreEligibleAlongsideCnPhoneAccounts() {
        for (base in listOf(OttaiConstants.API_BASE_GLOBAL, OttaiConstants.API_BASE_SYAI)) {
            assertTrue(OttaiCloudClient.supportsOtaSession(base, OttaiRegistry.SessionProfile.WATCH, true))
            assertFalse(OttaiCloudClient.supportsOtaSession(base, OttaiRegistry.SessionProfile.WATCH, false))
        }
        assertTrue(OttaiCloudClient.supportsOtaSession(OttaiConstants.API_BASE, OttaiRegistry.SessionProfile.CN_PHONE, true))
        assertFalse(OttaiCloudClient.supportsOtaSession(OttaiConstants.API_BASE, OttaiRegistry.SessionProfile.WATCH, true))
        assertFalse(OttaiCloudClient.supportsOtaSession("https://example.invalid", OttaiRegistry.SessionProfile.WATCH, true))
    }

    @Test fun exchangeSignsTheCompleteOutgoingHeaderIdentityForNativeAndFallbackIds() {
        for (suffix in listOf("n:00000000-1111-2222-3333-444444444444", "g:" + "a".repeat(32))) {
            var exchanges = 0
            lateinit var connection: HttpURLConnection
            lateinit var request: JSONObject
            val client = OttaiCloudClient.OtaClient(OttaiConstants.API_BASE, OttaiCloudClient.cnPhoneHeaders(
                suffix, "synthetic-account-token", 0L, "synthetic-trace"), openConnection = { url ->
                object : HttpURLConnection(URL(url)) {
                    private val body = ByteArrayOutputStream()
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun disconnect() = Unit
                    override fun getOutputStream() = body
                    override fun getResponseCode(): Int {
                        exchanges++
                        request = JSONObject(body.toString("UTF-8"))
                        return 200
                    }
                    // Stop after verifying the exchange. No account or sensor endpoint is contacted.
                    override fun getInputStream() = ByteArrayInputStream("""{"code":"Error"}""".toByteArray())
                }.also { connection = it }
            })
            try {
                assertThrows(OttaiOtaCloudException::class.java) { client.judgeOta("001122334455", "V1.7") }
                assertEquals(1, exchanges)
                assertEquals("https://api.ottai.com/cgm/security/app/server/security/exchangeKey", connection.url.toString())
                val headerId = connection.getRequestProperty("deviceId")
                assertEquals("ottai:a:$suffix", headerId)
                assertEquals("synthetic-account-token", connection.getRequestProperty("Authorization"))
                // Independent server-side verification of the actual request, using its
                // HTTP identity rather than the registry's unformatted suffix. Assert outside
                // the HTTP callback because the request layer catches callback failures.
                val signed = headerId + request.getString("clientPubKeyX") +
                    request.getString("clientPubKeyY") + request.getLong("clientTimestamp")
                val expected = MessageDigest.getInstance("SHA-256").digest(signed.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, request.getString("clientSignature"))
            } finally { client.close() }
        }
    }
}
