package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class OttaiCloudHeadersTests {

    @Test
    fun legacyBindKeepsProvenBodyShape() {
        val body = OttaiCloudClient.bindRequestBody(
            mac = "001122334455",
            deviceVersion = "V2.5.S2417.2",
            userId = "test-user",
            activeTime = 123_456L,
            contract = OttaiCloudClient.BindContract.LEGACY,
        )

        assertEquals("001122334455", body.getString("mac"))
        assertEquals("cgm", body.getString("deviceType"))
        assertEquals("test-user", body.getString("userId"))
        assertEquals(123_456L, body.getLong("activeTime"))
        assertFalse(body.has("patientId"))
        assertFalse(body.has("newBindType"))
    }

    @Test
    fun v3BindMatchesOfficialRecoveredShape() {
        // Official builder branch B (0x7aef88): {mac, deviceType:cgm, deviceVersion,
        // activeTime(seconds), userId, newBindType:2} — no patientId, no auth-field echo.
        val body = OttaiCloudClient.bindRequestBody(
            mac = "001122334455",
            deviceVersion = "E1.1.4(V1.7.S2530.1)",
            userId = "test-user",
            activeTime = 123_456L,
            contract = OttaiCloudClient.BindContract.V3,
        )

        assertEquals("cgm", body.getString("deviceType"))
        assertEquals("E1.1.4(V1.7.S2530.1)", body.getString("deviceVersion"))
        assertEquals("test-user", body.getString("userId"))
        assertEquals(123_456L, body.getLong("activeTime"))
        assertEquals(2, body.getInt("newBindType"))
        assertFalse(body.has("patientId"))
        assertFalse(body.has("sign"))
        assertFalse(body.has("colorBoxTailSn"))
        assertFalse(body.has("keyC"))
        assertFalse(body.has("boardType"))
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
    fun cgmAuthVerifyUsesRecoveredMd5Signer() {
        // CONFIRMED from live Frida capture and caller disassembly:
        // appName + deviceId + mac + paramStr + shaInfo + timestamp_ms + SEED.
        // See AGENTS/docs/protocols/ottai/cgmauth-sign-formula.md
        assertEquals(
            "f400087fa9b5e142bd8553630e3a5c0f",
            OttaiCloudClient.cgmAuthVerifySign(
                profile = OttaiRegistry.SessionProfile.CN_PHONE,
                deviceId = "n:b143d5c5-53b4-3ae0-880f-10320c96b111",
                timestampMillis = 1_787_444_389_880L,
                mac = "6CA04230E260",
                paramStr = "d300030078dbfd00c837d1a611a21c5ad81a932022be36a4bc5e13e8b35446edd69568bfcc23f45172966df4e1c9d28488a08066c891f2ef5f44902f872950b9134b3772",
                shaInfo = "0000000000000000000000000000000000000000000000000000000000000000",
            )
        )
    }

    @Test
    fun cgmAuthHeaderTimestampUsesSignerMilliseconds() {
        val timestampMillis = 1_234_567_890L
        val headers = OttaiCloudClient.cnPhoneHeaders(
            deviceId = "test-device",
            accessToken = "test-token",
            timestamp = timestampMillis,
            traceId = "test-trace",
        )
        assertEquals(timestampMillis.toString(), headers["timestamp"])
    }

    @Test
    fun cgmAuthVerifyUsesSignBodyField() {
        val body = OttaiCloudClient.cgmAuthVerifyRequestBody(
            mac = "18690ADED9B3",
            paramStr = "01ab",
            shaInfo = "aabb",
            sign = "signed",
            timestampMillis = 1_234_567_890L,
        )

        assertEquals("18690ADED9B3", body.getString("mac"))
        assertEquals("01AB", body.getString("paramStr"))
        assertEquals("AABB", body.getString("shaInfo"))
        assertEquals("signed", body.getString("sign"))
        assertEquals("1234567890", body.getString("timestamp"))
        assertFalse(body.has("signature"))
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
    fun cnHeaderUsesOfficialIdentitySuffixAndRejectsBuildId() {
        val official = "A1B2C3D4E5F6G7H8J9K0LMNOPQRSTUVW"
        val native = "n:8d4b44d5-df3a-3421-8866-9ade268285b2"
        assertEquals("g:$official", OttaiRegistry.cnHeaderDeviceId(" $official ", "legacy"))
        assertEquals("g:$official", OttaiRegistry.cnHeaderDeviceId("g:$official", "legacy"))
        assertEquals(native, OttaiRegistry.cnHeaderDeviceId(native, "legacy"))
        assertEquals(native, OttaiRegistry.cnHeaderDeviceId("ottai:a:$native", "legacy"))
        assertEquals("legacy", OttaiRegistry.cnHeaderDeviceId("CP41.260731.005.B1", "legacy"))
        assertEquals("legacy", OttaiRegistry.cnHeaderDeviceId("1234567890abcdef", "legacy"))
        assertEquals("legacy", OttaiRegistry.cnHeaderDeviceId("", "legacy"))
        assertEquals("legacy", OttaiRegistry.cnHeaderDeviceId(null, "legacy"))
    }

    @Test
    fun cnNativeDeviceIdMatchesOfficialUuidDerivation() {
        assertEquals(
            "8d4b44d5-df3a-3421-8866-9ade268285b2",
            OttaiRegistry.nativeCnDeviceId(
                androidId = "android-id",
                board = "board",
                brand = "brand",
                device = "device",
                model = "model",
                product = "product",
            ),
        )
        assertNull(
            OttaiRegistry.nativeCnDeviceId(
                androidId = "9774d56d682e549c",
                board = "board",
                brand = "brand",
                device = "device",
                model = "model",
                product = "product",
            ),
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
