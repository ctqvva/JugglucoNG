package tk.glucodata.drivers.ottai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiAccountBindingTests {
    @Test
    fun currentBindingIsIncludedEvenWhenAbsentFromHistoryAndWithoutCredentials() {
        val result = snapshot(
            history = """{"data":{"items":[{"mac":"18690ADED9B3","unbindTime":100}]}}""",
            binding = """{"data":{"cgmDeviceRespVO":{"mac":"70:D0:7E:25:52:DB"}}}""",
        )
        assertNull(result.failure)
        val devices = result.devices!!
        assertEquals(listOf("70D07E2552DB", "18690ADED9B3"), devices.map { it.mac })
        assertTrue(devices.first().isActive)
        assertFalse(devices.last().isActive)
    }

    @Test
    fun currentBindingOverridesPastHistoryAndRetainsPickerMetadata() {
        val result = snapshot(
            history = """{"data":{"items":[
                {"mac":"70D07E2552DB","serialNo":"saved serial","deviceVersion":"V3","bindTime":123,"unbindTime":456},
                {"mac":"70:D0:7E:25:52:DB","unbindTime":789}
            ]}}""",
            binding = """{"data":{"cgmDeviceRespVO":{"mac":"70d07e2552db"}}}""",
        )
        val device = result.devices!!.single()
        assertTrue(device.isActive)
        assertEquals("saved serial", device.serialNo)
        assertEquals("V3", device.deviceVersion)
        assertEquals(123L, device.bindTime)
    }

    @Test
    fun oldActiveHistoryCannotOverrideTheCurrentBinding() {
        val result = snapshot(
            history = """{"data":{"items":[{"mac":"18690ADED9B3","unbindTime":0}]}}""",
            binding = """{"result":{"mac":"70D07E2552DB"}}""",
        )
        assertEquals(listOf("70D07E2552DB"), result.devices!!.filter { it.isActive }.map { it.mac })
    }

    @Test
    fun explicitEmptyCurrentBindingMakesHistoryRowsNonBinding() {
        listOf("""{"data":null}""", """{"data":{}}""").forEach { binding ->
            val result = snapshot(
                history = """{"data":{"items":[{"mac":"70D07E2552DB","unbindTime":0}]}}""",
                binding = binding,
            )
            assertNull(result.failure)
            assertFalse(result.devices!!.single().isActive)
        }
    }

    @Test
    fun unfamiliarBindingShapeIsAnErrorRatherThanUnbound() {
        val result = snapshot("""{"data":{"items":[]}}""", """{"data":{"unknownDevice":{}}}""")
        assertNull(result.devices)
        assertNotNull(result.failure)
    }

    @Test
    fun bindingLookupFailureDoesNotUseHistoricalActivity() {
        val failure = OttaiCloudClient.CloudFailure("expired", OttaiCloudClient.BIZ_TOKEN_INVALID)
        val result = OttaiCloudClient.accountDevicesResult(
            response("""{"data":{"items":[{"mac":"70D07E2552DB","unbindTime":0}]}}"""),
            OttaiCloudClient.CloudRequestResult(null, failure),
        )
        assertNull(result.devices)
        assertEquals(failure, result.failure)
    }

    @Test
    fun currentBindingRemainsActionableWhenHistoryIsUnavailable() {
        val result = OttaiCloudClient.accountDevicesResult(
            OttaiCloudClient.CloudRequestResult(null, OttaiCloudClient.CloudFailure("history unavailable")),
            response("""{"data":{"mac":"70D07E2552DB"}}"""),
        )
        assertNull(result.failure)
        assertTrue(result.devices!!.single().isActive)
    }

    @Test
    fun invalidHistorySessionIsNotMaskedByAnotherSuccessfulResponse() {
        val failure = OttaiCloudClient.CloudFailure("expired", OttaiCloudClient.BIZ_TOKEN_INVALID)
        val result = OttaiCloudClient.accountDevicesResult(
            OttaiCloudClient.CloudRequestResult(null, failure),
            response("""{"data":{"mac":"70D07E2552DB"}}"""),
        )
        assertNull(result.devices)
        assertEquals(failure, result.failure)
    }

    private fun snapshot(history: String, binding: String) =
        OttaiCloudClient.accountDevicesResult(response(history), response(binding))

    private fun response(json: String) = OttaiCloudClient.CloudRequestResult(JSONObject(json), null)
}
