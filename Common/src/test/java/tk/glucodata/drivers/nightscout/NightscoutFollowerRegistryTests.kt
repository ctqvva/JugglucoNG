package tk.glucodata.drivers.nightscout

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unit tests for NightscoutFollowerRegistry.applyAuth() HTTP header logic.
 *
 * Covers:
 * - Empty secret → no auth header set
 * - Raw 40-char SHA1 hex secret (Nightscout api-secret style)
 * - Plain text secret → SHA1 hashed
 * - Bearer token prefix
 * - token=<value> prefix
 */
class NightscoutFollowerRegistryTests {

    @Test
    fun followerSensorIdentityRecognizesPersistedHistoryRows() {
        assertTrue(NightscoutFollowerRegistry.isFollowerSensorId("NSF-EXAMPLE123"))
        assertTrue(NightscoutFollowerRegistry.isFollowerSensorId(" nsf-example123 "))
        assertFalse(NightscoutFollowerRegistry.isFollowerSensorId("LOCAL-EXAMPLE123"))
        assertFalse(NightscoutFollowerRegistry.isFollowerSensorId(null))
    }

    private fun applyAuthToMock(secret: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val connection = object : HttpURLConnection(URL("https://example.com")) {
            override fun setRequestProperty(key: String, value: String) {
                headers[key] = value
            }
            override fun getResponseCode() = 200
            override fun getInputStream() = null as java.io.InputStream?
            override fun getOutputStream() = null as java.io.OutputStream?
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
        }
        NightscoutFollowerRegistry.applyAuth(connection, secret)
        return headers
    }

    // ---------- applyAuth ----------

    @Test
    fun applyAuth_emptySecret_noHeaders() {
        val headers = applyAuthToMock("")
        assertTrue("no headers expected", headers.isEmpty())
    }

    @Test
    fun applyAuth_whitespaceOnlySecret_noHeaders() {
        val headers = applyAuthToMock("   ")
        assertTrue("no headers expected", headers.isEmpty())
    }

    @Test
    fun applyAuth_rawSha1Hex_lowercase_sentAsApiSecret() {
        val sha1hex = "a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e4f5a0b1"
        val headers = applyAuthToMock(sha1hex)
        assertEquals(sha1hex, headers["api-secret"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun applyAuth_rawSha1Hex_uppercase_sentAsApiSecret() {
        val sha1hex = "A0B1C2D3E4F5A0B1C2D3E4F5A0B1C2D3E4F5A0B1"
        val headers = applyAuthToMock(sha1hex)
        assertEquals(sha1hex, headers["api-secret"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun applyAuth_rawSha1Hex_mixedCase_sentAsApiSecret() {
        val sha1hex = "a0B1C2d3E4f5A0b1C2D3e4F5a0B1C2d3E4f5A0b1"
        val headers = applyAuthToMock(sha1hex)
        assertEquals(sha1hex, headers["api-secret"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun applyAuth_39charHex_hashed() {
        val notSha1 = "a".repeat(39)
        val headers = applyAuthToMock(notSha1)
        assertNotEquals(notSha1, headers["api-secret"])
        assertNotNull(headers["api-secret"])
    }

    @Test
    fun applyAuth_40charNonHex_hashed() {
        val notHex = "a".repeat(39) + "g"
        val headers = applyAuthToMock(notHex)
        assertNotEquals(notHex, headers["api-secret"])
        assertNotNull(headers["api-secret"])
    }

    @Test
    fun applyAuth_plainText_hashed() {
        val plain = "my-super-secret"
        val headers = applyAuthToMock(plain)
        // SHA-1 of "my-super-secret" (verified via: echo -n "my-super-secret" | sha1sum)
        assertEquals("93f6b7b158a389c82510986ffaef1460c93093f7", headers["api-secret"])
    }

    @Test
    fun applyAuth_bearerToken_forwardedAsAuthorization() {
        val headers = applyAuthToMock("Bearer my-token-123")
        assertEquals("Bearer my-token-123", headers["Authorization"])
        assertNull(headers["api-secret"])
    }

    @Test
    fun applyAuth_bearerPrefixCaseInsensitive() {
        val headers = applyAuthToMock("bearer my-token-456")
        // case-insensitive check; original casing of secret is preserved in header value
        assertEquals("bearer my-token-456", headers["Authorization"])
    }

    @Test
    fun applyAuth_tokenEqualsPrefix_strippedAndBearerAdded() {
        val headers = applyAuthToMock("token=abc-xyz")
        assertEquals("Bearer abc-xyz", headers["Authorization"])
    }

    @Test
    fun applyAuth_tokenEqualsCaseInsensitive() {
        val headers = applyAuthToMock("TOKEN=xyz-789")
        assertEquals("Bearer xyz-789", headers["Authorization"])
    }

    // ---------- normalizeUrl ----------

    @Test
    fun normalizeUrl_addsHttpsWhenMissing() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("example.com"))
        // trailing slash is stripped
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("example.com/"))
    }

    @Test
    fun normalizeUrl_preservesExistingScheme() {
        assertEquals("http://example.com", NightscoutFollowerRegistry.normalizeUrl("http://example.com"))
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("https://example.com"))
    }

    @Test
    fun normalizeUrl_stripsTrailingSlash() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("https://example.com/"))
        assertEquals("https://example.com/path", NightscoutFollowerRegistry.normalizeUrl("https://example.com/path/"))
    }

    @Test
    fun normalizeUrl_trimsWhitespace() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("  https://example.com  "))
    }

    @Test
    fun normalizeUrl_emptyReturnsEmpty() {
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl(""))
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl(null))
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl("   "))
    }

    // ---------- deriveSensorId ----------

    @Test
    fun deriveSensorId_prefixAndHash() {
        val id = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        assertTrue("should start with NSF-", id.startsWith("NSF-"))
        assertEquals(16, id.length) // "NSF-" (4) + 12 hex chars (6 bytes)
    }

    @Test
    fun deriveSensorId_sameUrlProducesSameId() {
        val id1 = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        val id2 = NightscoutFollowerRegistry.deriveSensorId("example.com")
        assertEquals(id1, id2)
    }

    @Test
    fun deriveSensorId_differentUrlsProduceDifferentIds() {
        val idA = NightscoutFollowerRegistry.deriveSensorId("https://site-a.com")
        val idB = NightscoutFollowerRegistry.deriveSensorId("https://site-b.com")
        assertNotEquals(idA, idB)
    }

    @Test
    fun deriveSensorId_unconfiguredReturnsUnconfiguredSuffix() {
        val id = NightscoutFollowerRegistry.deriveSensorId(null)
        assertTrue(id.endsWith("UNCONFIGURED"))
    }

    // ---------- matchesSensorId ----------

    @Test
    fun matchesSensorId_exactMatch() {
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "NSF-ABC123"))
    }

    @Test
    fun matchesSensorId_caseInsensitive() {
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("nsf-abc123", "NSF-ABC123"))
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "nsf-abc123"))
    }

    @Test
    fun matchesSensorId_whitespaceTrimmed() {
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("  NSF-ABC123  ", "NSF-ABC123"))
    }

    @Test
    fun matchesSensorId_emptyCandidate_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("", "NSF-ABC123"))
        assertFalse(NightscoutFollowerRegistry.matchesSensorId(null, "NSF-ABC123"))
    }

    @Test
    fun matchesSensorId_emptyExpected_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", ""))
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", null))
    }

    @Test
    fun matchesSensorId_mismatch_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "NSF-DEF456"))
    }
}
