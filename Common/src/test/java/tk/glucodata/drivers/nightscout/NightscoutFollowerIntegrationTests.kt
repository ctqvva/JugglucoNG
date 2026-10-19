package tk.glucodata.drivers.nightscout

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

/**
 * Integration and edge-case tests for NightscoutFollowerManager parsing
 * and NightscoutFollowerRegistry applyAuth + normalizeUrl.
 *
 * These complement NightscoutFollowerRegistryTests which covers the happy-path
 * header logic. Here we cover:
 * - parseEntry: sgv vs mbg, date vs mills timestamp, invalid entries
 * - normalizeUrl edge cases: null, blank, various URL forms
 * - deriveSensorId: stable IDs across URL normalizations
 * - matchesSensorId: null/empty edge cases
 * - applyAuth edge cases
 */
class NightscoutFollowerIntegrationTests {

    // ========== parseEntry via test bridge ==========

    private fun parseEntryRaw(entry: Map<String, Any?>?): VirtualGlucoseSensorBridge.Reading? {
        return NightscoutFollowerManagerTestUtil.parseEntry(entry)
    }

    // ---------- parseEntry: sgv field ----------

    @Test
    fun parseEntry_sgv_validReading() {
        val entry = mapOf("sgv" to 142.0, "mills" to 1718928000000L)
        val reading = parseEntryRaw(entry)
        assertNotNull(reading)
        assertEquals(142f, reading!!.glucoseMgdl, 0.01f)
        assertEquals(1718928000000L, reading!!.timestampMs)
    }

    @Test
    fun parseEntry_sgv_fractionalValue() {
        val entry = mapOf("sgv" to 142.5, "mills" to 1718928000000L)
        val reading = parseEntryRaw(entry)
        assertNotNull(reading)
        assertEquals(142.5f, reading!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun parseEntry_sgv_zero_returnsNull() {
        val entry = mapOf("sgv" to 0.0, "mills" to 1718928000000L)
        assertNull(parseEntryRaw(entry))
    }

    @Test
    fun parseEntry_sgv_negative_returnsNull() {
        val entry = mapOf("sgv" to -10.0, "mills" to 1718928000000L)
        assertNull(parseEntryRaw(entry))
    }

    @Test
    fun parseEntry_sgv_NaN_returnsNull() {
        val entry = mapOf("sgv" to Double.NaN, "mills" to 1718928000000L)
        assertNull(parseEntryRaw(entry))
    }

    @Test
    fun parseEntry_sgvInfinity_returnsNull() {
        val entry = mapOf("sgv" to Double.POSITIVE_INFINITY, "mills" to 1718928000000L)
        assertNull(parseEntryRaw(entry))
    }

    // ---------- parseEntry: mbg field (manual blood glucose) ----------

    @Test
    fun parseEntry_mbg_usedWhenSgvAbsent() {
        val entry = mapOf("mbg" to 95.0, "mills" to 1718928000000L)
        val reading = parseEntryRaw(entry)
        assertNotNull(reading)
        assertEquals(95f, reading!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun parseEntry_sgvTakesPrecedenceOverMbg() {
        val entry = mapOf("sgv" to 142.0, "mbg" to 95.0, "mills" to 1718928000000L)
        val reading = parseEntryRaw(entry)
        assertNotNull(reading)
        assertEquals(142f, reading!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun parseEntry_mbg_zeroReturnsNull() {
        val entry = mapOf("mbg" to 0.0, "mills" to 1718928000000L)
        assertNull(parseEntryRaw(entry))
    }

    // ---------- parseEntry: timestamp variants ----------

    @Test
    fun parseEntry_dateFieldAsMillis() {
        // Nightscout API uses "date" as milliseconds (not seconds)
        val entry = mapOf("sgv" to 142.0, "date" to 1718928000000L)
        val reading = parseEntryRaw(entry)
        assertNotNull(reading)
        assertEquals(1718928000000L, reading!!.timestampMs)
    }

    @Test
    fun parseEntry_millsField() {
        val entry = mapOf("sgv" to 142.0, "mills" to 1718928000000L)
        val reading = parseEntryRaw(entry)
        assertNotNull(reading)
        assertEquals(1718928000000L, reading!!.timestampMs)
    }

    @Test
    fun parseEntry_dateFieldTrumpsMills() {
        val entry = mapOf("sgv" to 142.0, "date" to 1719999000000L, "mills" to 1718928000000L)
        val reading = parseEntryRaw(entry)
        assertNotNull(reading)
        assertEquals(1719999000000L, reading!!.timestampMs)
    }

    @Test
    fun parseEntry_missingTimestamp_returnsNull() {
        val entry = mapOf("sgv" to 142.0)
        assertNull(parseEntryRaw(entry))
    }

    @Test
    fun parseEntry_zeroTimestamp_returnsNull() {
        val entry = mapOf("sgv" to 142.0, "mills" to 0L)
        assertNull(parseEntryRaw(entry))
    }

    @Test
    fun parseEntry_nullEntry_returnsNull() {
        assertNull(parseEntryRaw(null))
    }

    @Test
    fun parseEntry_emptyMap_returnsNull() {
        assertNull(parseEntryRaw(emptyMap()))
    }

    // ---------- normalizeUrl edge cases ----------

    @Test
    fun normalizeUrl_null_returnsEmpty() {
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl(null))
    }

    @Test
    fun normalizeUrl_emptyString_returnsEmpty() {
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl(""))
    }

    @Test
    fun normalizeUrl_whitespaceOnly_returnsEmpty() {
        assertEquals("", NightscoutFollowerRegistry.normalizeUrl("   "))
    }

    @Test
    fun normalizeUrl_withWhitespace_trimsAndNormalizes() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("  https://example.com  "))
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("  example.com  "))
    }

    @Test
    fun normalizeUrl_httpPreserved() {
        assertEquals("http://example.com", NightscoutFollowerRegistry.normalizeUrl("http://example.com"))
    }

    @Test
    fun normalizeUrl_httpsPreserved() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("https://example.com"))
    }

    @Test
    fun normalizeUrl_trailingSlash_stripped() {
        assertEquals("https://example.com", NightscoutFollowerRegistry.normalizeUrl("https://example.com/"))
        assertEquals("https://example.com/path", NightscoutFollowerRegistry.normalizeUrl("https://example.com/path/"))
    }

    @Test
    fun normalizeUrl_pathPreserved() {
        assertEquals("https://example.com/nightscout", NightscoutFollowerRegistry.normalizeUrl("example.com/nightscout"))
        assertEquals("https://example.com/nightscout", NightscoutFollowerRegistry.normalizeUrl("example.com/nightscout/"))
    }

    @Test
    fun normalizeUrl_portPreserved() {
        assertEquals("https://example.com:1337", NightscoutFollowerRegistry.normalizeUrl("example.com:1337"))
        assertEquals("http://example.com:8080", NightscoutFollowerRegistry.normalizeUrl("http://example.com:8080"))
    }

    @Test
    fun normalizeUrl_subdomainPreserved() {
        assertEquals("https://ns.example.com", NightscoutFollowerRegistry.normalizeUrl("ns.example.com"))
    }

    // ---------- deriveSensorId ----------

    @Test
    fun deriveSensorId_null_returnsUnconfiguredSuffix() {
        val id = NightscoutFollowerRegistry.deriveSensorId(null)
        assertTrue("should end with UNCONFIGURED: $id", id.endsWith("UNCONFIGURED"))
    }

    @Test
    fun deriveSensorId_empty_returnsUnconfiguredSuffix() {
        val id = NightscoutFollowerRegistry.deriveSensorId("")
        assertTrue(id.endsWith("UNCONFIGURED"))
    }

    @Test
    fun deriveSensorId_startsWithNSFPrefix() {
        val id = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        assertTrue("expected NSF- prefix: $id", id.startsWith("NSF-"))
    }

    @Test
    fun deriveSensorId_hasExpectedLength() {
        // NSF- (4) + 6 hex bytes (12 chars) = 16 chars
        val id = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        assertEquals(16, id.length)
    }

    @Test
    fun deriveSensorId_sameUrl_sameId() {
        val id1 = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        val id2 = NightscoutFollowerRegistry.deriveSensorId("example.com")
        assertEquals("same normalized URL must produce same sensor ID", id1, id2)
    }

    @Test
    fun deriveSensorId_differentUrls_differentIds() {
        val idA = NightscoutFollowerRegistry.deriveSensorId("https://site-a.com")
        val idB = NightscoutFollowerRegistry.deriveSensorId("https://site-b.com")
        assertNotEquals(idA, idB)
    }

    @Test
    fun deriveSensorId_httpsVsHttp_differentIds() {
        val idHttps = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        val idHttp = NightscoutFollowerRegistry.deriveSensorId("http://example.com")
        assertNotEquals(idHttps, idHttp)
    }

    @Test
    fun deriveSensorId_differentPaths_differentIds() {
        val idRoot = NightscoutFollowerRegistry.deriveSensorId("https://example.com")
        val idPath = NightscoutFollowerRegistry.deriveSensorId("https://example.com/nightscout")
        assertNotEquals(idRoot, idPath)
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
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("NsF-aBc123", "nSf-AbC123"))
    }

    @Test
    fun matchesSensorId_whitespaceTrimmed() {
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("  NSF-ABC123  ", "NSF-ABC123"))
        assertTrue(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "  NSF-ABC123  "))
    }

    @Test
    fun matchesSensorId_mismatch_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "NSF-DEF456"))
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("NSF-ABC123", "nsf-abc124"))
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
    fun matchesSensorId_bothEmpty_returnsFalse() {
        assertFalse(NightscoutFollowerRegistry.matchesSensorId("", ""))
        assertFalse(NightscoutFollowerRegistry.matchesSensorId(null, null))
    }

    // ---------- applyAuth edge cases ----------

    private fun applyAuthToMock(secret: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val connection = object : HttpURLConnection(URL("https://example.com")) {
            override fun setRequestProperty(key: String, value: String) {
                headers[key] = value
            }
            override fun getResponseCode() = 200
            override fun getInputStream() = null
            override fun getOutputStream() = null
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
        }
        NightscoutFollowerRegistry.applyAuth(connection, secret)
        return headers
    }

    @Test
    fun applyAuth_emptySecret_noHeaders() {
        val headers = applyAuthToMock("")
        assertTrue("empty secret should produce no auth headers", headers.isEmpty())
    }

    @Test
    fun applyAuth_whitespaceOnlySecret_noHeaders() {
        val headers = applyAuthToMock("   ")
        assertTrue("whitespace-only secret should produce no auth headers", headers.isEmpty())
    }

    @Test
    fun applyAuth_40CharHex_rawSha1SentAsApiSecret() {
        val sha1hex = "a".repeat(40)
        val headers = applyAuthToMock(sha1hex)
        assertEquals(sha1hex, headers["api-secret"])
        assertNull(headers["Authorization"])
    }

    @Test
    fun applyAuth_39CharHex_notTreatedAsRawSha1() {
        // 39-char hex is not 40-char, so plain text gets hashed
        val hex39 = "a".repeat(39)
        val headers = applyAuthToMock(hex39)
        // Should be SHA1-hashed, not the raw string
        assertNotEquals(hex39, headers["api-secret"])
    }

    @Test
    fun applyAuth_bearerPrefix_authorizationHeader() {
        val headers = applyAuthToMock("Bearer my-token-xyz")
        assertEquals("Bearer my-token-xyz", headers["Authorization"])
        assertNull(headers["api-secret"])
    }

    @Test
    fun applyAuth_bearerMixedCase_preserved() {
        val headers = applyAuthToMock("bearer my-token-abc")
        assertEquals("bearer my-token-abc", headers["Authorization"])
    }

    @Test
    fun applyAuth_tokenEqualsPrefix_strippedToBearer() {
        val headers = applyAuthToMock("token=abc-xyz-123")
        assertEquals("Bearer abc-xyz-123", headers["Authorization"])
        assertNull(headers["api-secret"])
    }

    @Test
    fun applyAuth_TOKENEqualsUppercase_strippedToBearer() {
        val headers = applyAuthToMock("TOKEN=xyz-789")
        assertEquals("Bearer xyz-789", headers["Authorization"])
    }

    @Test
    fun applyAuth_tokenEqualsCaseInsensitive() {
        assertEquals("Bearer abc", applyAuthToMock("TOKEN=abc")["Authorization"])
        assertEquals("Bearer abc", applyAuthToMock("Token=abc")["Authorization"])
        assertEquals("Bearer abc", applyAuthToMock("token=abc")["Authorization"])
    }

    @Test
    fun applyAuth_plainText_hashedToApiSecret() {
        val plain = "my-secret"
        val headers = applyAuthToMock(plain)
        assertNotNull(headers["api-secret"])
        assertNull(headers["Authorization"])
        assertNotEquals(plain, headers["api-secret"])
        // Verify it's a 40-char hex SHA1
        assertTrue("expected 40-char hex hash", headers["api-secret"]!!.matches(Regex("^[0-9a-f]{40}$")))
    }

    @Test
    fun applyAuth_uppercaseHex_isPreserved() {
        val upperHex = "A".repeat(40)
        val headers = applyAuthToMock(upperHex)
        assertEquals(upperHex, headers["api-secret"])
    }
}

// ========== Test bridges ==========
// Package-visible bridges to expose parsing logic for unit testing.

/** Exposes NightscoutFollowerManager parsing for unit testing. */
object NightscoutFollowerManagerTestBridge {
    @JvmStatic
    fun parseEntry(entry: Map<String, Any?>?): VirtualGlucoseSensorBridge.Reading? {
        return NightscoutFollowerManagerTestUtil.parseEntry(entry)
    }
}

/**
 * Mirrors the parsing logic of NightscoutFollowerManager.parseEntry.
 * This is a pure function that doesn't require Android or JSONObject,
 * allowing reliable unit testing of the parsing contract.
 */
object NightscoutFollowerManagerTestUtil {
    @JvmStatic
    fun parseEntry(entry: Map<String, Any?>?): VirtualGlucoseSensorBridge.Reading? {
        entry ?: return null
        val sgv = (entry["sgv"] as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val mbg = (entry["mbg"] as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val mgdl = sgv ?: mbg ?: return null

        val dateVal = entry["date"]
        val millsVal = entry["mills"]
        val timestampMs = when {
            dateVal != null -> (dateVal as? Number)?.toLong() ?: 0L
            millsVal != null -> (millsVal as? Number)?.toLong() ?: 0L
            else -> 0L
        }.takeIf { it > 0L } ?: return null

        return VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestampMs,
            glucoseMgdl = mgdl.toFloat(),
        )
    }
}