package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class OttaiCloudHeadersTests {

    @Test
    fun legacyBindUsesUserIdContract() {
        val body = OttaiCloudClient.bindRequestBody(
            mac = "001122334455",
            deviceVersion = "V2.5.S2417.2",
            userId = "test-user",
            activeTimeMs = 123L,
            contract = OttaiCloudClient.BindContract.LEGACY,
        )

        assertEquals("test-user", body.getString("userId"))
        assertFalse(body.has("patientId"))
        assertFalse(body.has("newBindType"))
    }

    @Test
    fun v3BindUsesNullPatientContract() {
        val body = OttaiCloudClient.bindRequestBody(
            mac = "001122334455",
            deviceVersion = "E1.1.4(V1.7.S2530.1)",
            userId = "must-not-leak",
            activeTimeMs = 123L,
            contract = OttaiCloudClient.BindContract.V3,
        )

        assertTrue(body.has("patientId"))
        assertEquals(JSONObject.NULL, body.get("patientId"))
        assertFalse(body.has("userId"))
        assertEquals(2, body.getInt("newBindType"))
    }

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
    fun globalExpiredRecoveryUsesKnownBindVersionWithoutAccountMembership() {
        assertEquals(
            OttaiCloudClient.GLOBAL_MATERIAL_BIND_DEVICE_VERSION,
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_GLOBAL,
                null,
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
    }

    @Test
    fun v3BindOmitsSensorAuthUntilActiveAuthRuns() {
        val body = OttaiCloudClient.bindRequestBody(
            mac = "001122334455",
            deviceVersion = "E1.1.4(V1.7.S2530.1)",
            userId = null,
            activeTimeMs = 123L,
            contract = OttaiCloudClient.BindContract.V3,
            v3Auth = null,
        )
        assertFalse(body.has("sign"))
        assertFalse(body.has("colorBoxTailSn"))
        assertFalse(body.has("keyC"))
        assertFalse(body.has("boardType"))
    }

    @Test
    fun v3BindIncludesSensorAuthWhenPresent() {
        val body = OttaiCloudClient.bindRequestBody(
            mac = "001122334455",
            deviceVersion = "E1.1.4(V1.7.S2530.1)",
            userId = null,
            activeTimeMs = 123L,
            contract = OttaiCloudClient.BindContract.V3,
            v3Auth = OttaiCloudClient.V3BindAuth(
                sign = "abc123",
                colorBoxTailSn = "TAIL42",
                keyC = "CCEE",
                boardType = "M8",
            ),
        )
        assertEquals("abc123", body.getString("sign"))
        assertEquals("TAIL42", body.getString("colorBoxTailSn"))
        assertEquals("CCEE", body.getString("keyC"))
        assertEquals("M8", body.getString("boardType"))
        assertEquals(2, body.getInt("newBindType"))
    }

    @Test
    fun expiredRecoveryFallbackIsNotUsedForOtherFailuresOrCn() {
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_SYAI,
                null,
                "AppDevice_AlreadyUsed",
            ),
        )
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_GLOBAL,
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
    fun cnPhoneSessionUsesObservedPhoneIdentity() {
        val headers = OttaiCloudClient.cnPhoneHeaders(
            deviceId = "test-device",
            accessToken = "test-token",
            timestamp = 123L,
            traceId = "test-trace",
        )

        assertEquals("ottai_main", headers["applicationType"])
        assertEquals("ottai", headers["appName"])
        assertEquals("com.ottai.tag", headers["packageName"])
        assertEquals("263121", headers["versionCode"])
        assertEquals("1.55.0", headers["versionName"])
        assertEquals("ottai:a:test-device", headers["deviceId"])
        assertEquals("test-token", headers["Authorization"])
        assertEquals("PUT", OttaiCloudClient.TEMPORARY_MATERIAL_UNBIND_METHOD)
    }

    @Test
    fun sessionSignaturesRemainBoundToTheirIssuingIdentity() {
        val phone = OttaiCloudClient.signForProfile(
            OttaiRegistry.SessionProfile.CN_PHONE,
            "test-device",
            123L,
            "mac",
        )
        val watch = OttaiCloudClient.signForProfile(
            OttaiRegistry.SessionProfile.WATCH,
            "test-device",
            123L,
            "mac",
        )

        assertEquals("2eafe6b7007e1d80e323d2aa459bb873", phone)
        assertEquals("a622e50e56cf68097f7a54a319f59f1c", watch)
        assertFalse(phone == watch)
    }

    @Test
    fun legacyOrUnknownSessionProfileStaysOnWatchIdentity() {
        assertEquals(OttaiRegistry.SessionProfile.WATCH, OttaiRegistry.parseSessionProfile(null))
        assertEquals(OttaiRegistry.SessionProfile.WATCH, OttaiRegistry.parseSessionProfile("unknown"))
        assertEquals(
            OttaiRegistry.SessionProfile.CN_PHONE,
            OttaiRegistry.parseSessionProfile(OttaiRegistry.SessionProfile.CN_PHONE.name),
        )
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
