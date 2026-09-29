package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

/** Every cloud material (keyA, method, coefficient, lifetime) passes through parseDeviceResp. */
class OttaiDeviceRespParseTests {

    private fun parse(json: String, requireKeyA: Boolean = true) =
        OttaiCloudClient.parseDeviceResp(JSONObject(json), requireKeyA)

    @Test
    fun methodVoWinsAndBlankFieldsFallBackToTheDeviceVo() {
        val resp = parse(
            """
            {"code":"200","data":{
              "cgmDeviceRespVO":{"mac":"001122334455","keyA":"ka","method":"","coefficient":"vo-coeff",
                "methodUpdateTime":11,"coeffUpdateTime":22,"produceTime":33,"activeTime":1700000183000,
                "activeExpireTime":1296000000,"preheatPeriodTime":3600000,"retainTime":0,
                "deviceVersion":"E1.2.3","id":7},
              "cgmDeviceMethodVO":{"method":"mvo-method","coefficient":"","methodUpdateTime":44,"coeffUpdateTime":0}
            }}
            """,
        )!!
        assertEquals("001122334455", resp.mac)
        assertEquals("ka", resp.keyA)
        assertEquals("mvo-method", resp.method)
        assertEquals("vo-coeff", resp.coefficient)
        assertEquals(44L, resp.methodUpdateTime)
        // 0 in the method VO is "absent", not a time: the device VO's stamp decrypts the coefficient.
        assertEquals(22L, resp.coeffUpdateTime)
        assertEquals(33L, resp.produceTime)
        assertEquals(1_700_000_183_000L, resp.activeTime)
        assertEquals(1_296_000_000L, resp.activeExpireTime)
        assertEquals(3_600_000L, resp.preheatPeriodTime)
        assertEquals(0L, resp.retainTime)
        assertEquals("E1.2.3", resp.deviceVersion)
        assertEquals(7, resp.deviceId)
    }

    @Test
    fun flatDataUnderResultIsTheDeviceVo() {
        val resp = parse("""{"result":{"mac":"AABBCCDDEEFF","keyA":"ka","method":"m","coefficient":"c"}}""")!!
        assertEquals("AABBCCDDEEFF", resp.mac)
        assertEquals("m", resp.method)
        assertEquals("c", resp.coefficient)
        assertEquals(0L, resp.activeTime)
        assertEquals(0, resp.deviceId)
    }

    @Test
    fun blankKeyAIsRefusedUnlessTheCallerDoesNotNeedIt() {
        val json = """{"data":{"mac":"001122334455","keyA":"","deviceVersion":"E1.1.4"}}"""
        assertNull(parse(json))
        val metadata = parse(json, requireKeyA = false)
        assertNotNull(metadata)
        assertEquals("001122334455", metadata!!.mac)
        assertEquals("", metadata.keyA)
        // The literal string "null" (what Android's org.json yields for a JSON null) is no key.
        assertNull(parse("""{"data":{"keyA":"null"}}"""))
    }

    @Test
    fun noPayloadIsNoDevice() {
        assertNull(parse("""{"code":"200"}"""))
        assertNull(parse("""{"code":"200","data":"text"}""", requireKeyA = false))
    }

    @Test
    fun looseLongsAcceptStringsAndReadNullOrGarbageAsUnknown() {
        val resp = parse(
            """
            {"data":{"keyA":"ka","activeTime":"1700000183000","produceTime":null,
              "activeExpireTime":"abc","deviceVersion":"null","mac":"null"}}
            """,
        )!!
        assertEquals(1_700_000_183_000L, resp.activeTime)
        assertEquals(0L, resp.produceTime)
        assertEquals(0L, resp.activeExpireTime)
        assertEquals("", resp.deviceVersion)
        assertEquals("", resp.mac)
    }
}
