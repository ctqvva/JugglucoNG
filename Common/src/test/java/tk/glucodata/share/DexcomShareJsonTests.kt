package tk.glucodata.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DexcomShareJsonTests {

    @Test
    fun dateStringFloorsMillisecondsToWholeSeconds() {
        assertEquals("/Date(1760870400000)/", DexcomShareJson.dateString(1760870400999L))
        assertEquals("/Date(1760870400000)/", DexcomShareJson.dateString(1760870400000L))
    }

    @Test
    fun valueIsClampedToTheShareRange() {
        assertEquals(40, DexcomShareJson.clampMgdl(20))
        assertEquals(40, DexcomShareJson.clampMgdl(40))
        assertEquals(123, DexcomShareJson.clampMgdl(123))
        assertEquals(400, DexcomShareJson.clampMgdl(400))
        assertEquals(400, DexcomShareJson.clampMgdl(401))
    }

    @Test
    fun trendIndexPassesThroughZeroToSevenAndFallsBackToZero() {
        for (index in 0..7) {
            assertEquals(index, DexcomShareJson.normalizeTrend(index))
        }
        assertEquals(0, DexcomShareJson.normalizeTrend(-1))
        assertEquals(0, DexcomShareJson.normalizeTrend(9))
    }

    @Test
    fun authBodyCarriesAccountPasswordAndApplicationId() {
        val root = JSONObject(DexcomShareJson.authBody("someone@example.com", "secret"))
        assertEquals("someone@example.com", root.getString("accountName"))
        assertEquals("secret", root.getString("password"))
        assertEquals(DexcomShareJson.APPLICATION_ID, root.getString("applicationId"))
    }

    @Test
    fun uploadBodyHasTheShareShape() {
        val egvs = listOf(
            ShareEgv(1760870400000L, 123, ExchangeTrendIndex.FLAT),
            ShareEgv(1760870460000L, 401, ExchangeTrendIndex.DOUBLE_DOWN),
        )
        val root = JSONObject(DexcomShareJson.uploadBody("SM00000000", egvs))

        assertEquals("SM00000000", root.getString("SN"))
        assertEquals(-5, root.getInt("TA"))
        val array = root.getJSONArray("Egvs")
        assertEquals(2, array.length())

        val first = array.getJSONObject(0)
        assertEquals(4, first.getInt("Trend"))
        assertEquals(123, first.getInt("Value"))
        assertEquals("/Date(1760870400000)/", first.getString("ST"))
        assertEquals(first.getString("ST"), first.getString("DT"))

        val second = array.getJSONObject(1)
        assertEquals(7, second.getInt("Trend"))
        assertEquals(400, second.getInt("Value"))
        assertEquals("/Date(1760870460000)/", second.getString("DT"))
    }

    @Test
    fun parseJsonStringAcceptsQuotedWhitespacedAndBareValues() {
        assertEquals("abc", DexcomShareJson.parseJsonString("\"abc\""))
        assertEquals("abc", DexcomShareJson.parseJsonString(" \"abc\" "))
        assertEquals("abc", DexcomShareJson.parseJsonString("abc"))
        assertEquals("", DexcomShareJson.parseJsonString(""))
        assertEquals("", DexcomShareJson.parseJsonString("  "))
    }

    @Test
    fun aJsonNullSessionIsEmptyRatherThanTheWordNull() {
        // Dexcom answers an account that does not exist in the chosen region with a bare null.
        // "null" is not a session id: treating it as one would re-login once per flush forever.
        assertEquals("", DexcomShareJson.parseJsonString("null"))
        assertEquals("", DexcomShareJson.parseJsonString("NULL"))
        assertEquals("", DexcomShareJson.parseJsonString(" null "))
        assertEquals("", DexcomShareJson.parseJsonString("\"null\""))
    }
}

/** The Dexcom ordinal for a trend, spelled out for the tests that assert on it. */
private object ExchangeTrendIndex {
    const val FLAT = 4
    const val DOUBLE_DOWN = 7
}
