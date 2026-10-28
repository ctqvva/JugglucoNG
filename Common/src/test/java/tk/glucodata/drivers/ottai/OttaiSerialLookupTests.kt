package tk.glucodata.drivers.ottai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OttaiSerialLookupTests {
    @Test fun typedNonHexSerialUsesTheReadOnlyLookupAndReturnsACanonicalMac() {
        val calls = mutableListOf<String>()
        val result = OttaiCloudClient.resolveSetupSensorId(" x01269290mmr ", OttaiConstants.API_BASE_SYAI) { path, body ->
            calls += path
            assertEquals("X01269290MMR", body.getString("colorBoxTailSn"))
            assertTrue(body.isNull("mac"))
            assertEquals(setOf("mac", "colorBoxTailSn"), body.keys().asSequence().toSet())
            success("b4:89:31:17:f8:17")
        }
        assertEquals(listOf(OttaiConstants.EP_MAC_OR_SN_INFO_V2), calls)
        assertEquals("B4893117F817", result.sensorId)
        assertNull(result.failure)
    }

    @Test fun thePreviouslyWorkingTypedHexIdDoesNotNeedSerialResolution() {
        for (code in listOf("B4893117F817", "b4:89:31:17:f8:17")) {
            val result = OttaiCloudClient.resolveSetupSensorId(code, OttaiConstants.API_BASE_GLOBAL) { _, _ ->
                fail("Hex IDs must retain direct validation")
                error("unexpected lookup")
            }
            assertEquals("B4893117F817", result.sensorId)
        }
    }

    @Test fun cnUsesTheCurrentOfficialLookupRoute() {
        val result = OttaiCloudClient.resolveSetupSensorId("X01269290MMR", OttaiConstants.API_BASE) { path, _ ->
            assertEquals(OttaiConstants.EP_MAC_OR_SN_INFO, path)
            success("B4893117F817")
        }
        assertEquals("B4893117F817", result.sensorId)
    }

    @Test fun aRetiredRegionalLookupMayFallBackToTheCurrentRoute() {
        for (base in listOf(OttaiConstants.API_BASE_SYAI, OttaiConstants.API_BASE_GLOBAL)) {
            val paths = mutableListOf<String>()
            val result = OttaiCloudClient.resolveSetupSensorId("X01269290MMR", base) { path, _ ->
                paths += path
                if (paths.size == 1) failed("", 404) else success("B4893117F817")
            }
            assertEquals(listOf(OttaiConstants.EP_MAC_OR_SN_INFO_V2, OttaiConstants.EP_MAC_OR_SN_INFO), paths)
            assertEquals("B4893117F817", result.sensorId)
        }
    }

    @Test fun authenticationAndBusinessFailuresDoNotRetryOrAcceptTheirData() {
        for (code in listOf(OttaiCloudClient.BIZ_TOKEN_INVALID, "AppDevice_NotExist")) {
            var calls = 0
            val result = OttaiCloudClient.resolveSetupSensorId("X01269290MMR", OttaiConstants.API_BASE_SYAI) { _, _ ->
                calls++
                OttaiCloudClient.CloudRequestResult(
                    JSONObject("""{"data":{"mac":"B4893117F817"}}"""),
                    OttaiCloudClient.CloudFailure("rejected", code, 401),
                )
            }
            assertNull(result.sensorId)
            assertEquals(code, result.failure?.code)
            assertEquals(1, calls)
        }
    }

    @Test fun malformedOrMissingResolvedMacCannotBecomeASensorIdentity() {
        for (mac in listOf("", "X01269290MMR", "B4893117F817-extra", "null")) {
            val result = OttaiCloudClient.resolveSetupSensorId("X01269290MMR", OttaiConstants.API_BASE_SYAI) { _, _ -> success(mac) }
            assertNull(result.sensorId)
            assertEquals("sensor_lookup_invalid_response", result.failure?.text)
        }
    }

    @Test fun incompleteOrInvalidTypedCodesDoNotSendLookupRequests() {
        for (code in listOf("X01269290MM", "X01269290MMRR", "X01269!90MMR", "", "https://example.invalid")) {
            assertFalse(OttaiConstants.isBoxSerial(code))
            val result = OttaiCloudClient.resolveSetupSensorId(code, OttaiConstants.API_BASE_SYAI) { _, _ ->
                fail("An incomplete or invalid code must not trigger lookup")
                error("unexpected lookup")
            }
            assertNull(result.sensorId)
        }
    }

    @Test fun manualInputAcceptsTheSerialWithoutConvertingItToABleAddress() {
        assertEquals("X01269290MMR", OttaiConstants.extractSetupCode("x01269290mmr"))
        assertTrue(OttaiConstants.isBoxSerial("X01269290MMR"))
        assertFalse(OttaiConstants.looksLikeMac("X01269290MMR"))
        assertNull(OttaiConstants.normalizeBleAddress("X01269290MMR", allowPlain = true))
        assertEquals("B4893117F817", OttaiConstants.extractSetupCode("b4893117f817"))
    }

    @Test fun gs1SerialTakesPriorityOverTheNumericGtin() {
        assertEquals("X01269290MMR", OttaiConstants.extractSetupCode("(01)06912345678903(17)271231(21)X01269290MMR"))
        assertEquals("X01269290MMR", OttaiConstants.extractSetupCode("]d201069123456789031727123110BATCH\u001D21X01269290MMR"))
        assertEquals("B4893117F817", OttaiConstants.extractSetupCode("(01)06912345678903(21)B4893117F817"))
    }

    private fun success(mac: String) = OttaiCloudClient.CloudRequestResult(
        JSONObject().put("data", JSONObject().put("mac", mac)), null,
    )

    private fun failed(code: String, status: Int) = OttaiCloudClient.CloudRequestResult(
        null, OttaiCloudClient.CloudFailure("http=$status", code, status),
    )
}
