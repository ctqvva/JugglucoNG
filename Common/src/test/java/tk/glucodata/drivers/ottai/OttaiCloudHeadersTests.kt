package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OttaiCloudHeadersTests {

    @Test
    fun syaiExpiredRecoveryUsesKnownBindVersionWithoutAccountMembership() {
        assertEquals(
            OttaiCloudClient.SYAI_MATERIAL_BIND_DEVICE_VERSION,
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_SYAI,
                null,
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
    }

    @Test
    fun syaiRecoveryFallbackIsNotUsedForOtherFailuresOrBackends() {
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_SYAI,
                null,
                "AppDevice_AlreadyUsed",
            ),
        )
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE,
                null,
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_GLOBAL,
                null,
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
    }

    @Test
    fun selectedSensorVersionAlwaysWinsMaterialRecovery() {
        assertEquals(
            "vE1.2.3(V1.7.SH2542.1)",
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_SYAI,
                " vE1.2.3(V1.7.SH2542.1) ",
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
    }

    @Test
    fun syaiWebAccountUsesGlobalMobileApi() {
        assertEquals(
            "https://api.syai.com",
            OttaiCloudClient.webBaseToMobile(OttaiConstants.WEB_BASE_SYAI),
        )
    }

    @Test
    fun legacySyaiMobileApiIsMigrated() {
        assertEquals(
            OttaiConstants.API_BASE_SYAI,
            OttaiRegistry.normalizeApiBase("https://ru.syai.com"),
        )
    }

    @Test
    fun nonSyaiApiBaseIsUnchanged() {
        assertEquals(
            OttaiConstants.API_BASE_GLOBAL,
            OttaiRegistry.normalizeApiBase(OttaiConstants.API_BASE_GLOBAL),
        )
    }

    @Test
    fun syaiGetUserIncludesRequiredDeviceIdentity() {
        val headers = OttaiCloudClient.webGetUserHeaders(
            webBase = "https://www.syai.com/api/cgm/web",
            ts = 123L,
            accessToken = "test-token",
        )

        assertEquals("cgm", headers["appName"])
        assertEquals("5", headers["versionCode"])
        assertEquals("8", headers["deviceId"])
        assertEquals("Bearer test-token", headers["Authorization"])
    }

    @Test
    fun ottaiGetUserRetainsExistingDeviceIdentity() {
        val headers = OttaiCloudClient.webGetUserHeaders(
            webBase = "https://www.ottai.com/api/cgm/web",
            ts = 123L,
            accessToken = "test-token",
        )

        assertEquals("ottai-seas", headers["appName"])
        assertEquals("253201", headers["versionCode"])
        assertEquals("8", headers["deviceId"])
        assertEquals("Bearer test-token", headers["Authorization"])
    }

    @Test
    fun expiredMaterialRecoveryUsesObservedPhoneIdentity() {
        val headers = OttaiCloudClient.temporaryMaterialHeaders(
            deviceId = "test-device",
            accessToken = "test-token",
            timestamp = 123L,
            traceId = "test-trace",
        )

        assertEquals("ottai_main", headers["applicationType"])
        assertEquals("ottai", headers["appName"])
        assertEquals("com.ottai.tag", headers["packageName"])
        assertEquals("260721", headers["versionCode"])
        assertEquals("ottai:a:test-device", headers["deviceId"])
        assertEquals("test-token", headers["Authorization"])
        assertEquals("PUT", OttaiCloudClient.TEMPORARY_MATERIAL_UNBIND_METHOD)
    }

    @Test
    fun temporaryBindTimeIsNeverUsedAsHistoricalStart() {
        val temporary = deviceResponse(activeTime = 999_999L)

        assertEquals(
            123_000L,
            OttaiCloudClient.sanitizeTemporaryBindResponse(temporary, 123_000L).activeTime,
        )
        assertEquals(
            0L,
            OttaiCloudClient.sanitizeTemporaryBindResponse(temporary, 0L).activeTime,
        )
        assertFalse(
            OttaiCloudClient.sanitizeTemporaryBindResponse(temporary, 0L).activeTime == temporary.activeTime,
        )
    }

    private fun deviceResponse(activeTime: Long) = OttaiCloudClient.DeviceResp(
        mac = "001122334455",
        keyA = "key",
        method = "method",
        coefficient = "coefficient",
        produceTime = 0L,
        methodUpdateTime = 0L,
        coeffUpdateTime = 0L,
        activeTime = activeTime,
        activeExpireTime = 0L,
        preheatPeriodTime = 0L,
        retainTime = 0L,
        deviceVersion = "V2.5.S2417.2",
        deviceId = 1,
    )
}
