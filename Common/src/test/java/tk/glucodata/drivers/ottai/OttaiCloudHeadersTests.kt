package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun syaiExpiredRecoveryDoesNotTemporaryBindOnOutOfProduceTime() {
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_SYAI,
                null,
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_SYAI,
                OttaiCloudClient.SYAI_MATERIAL_BIND_DEVICE_VERSION,
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
    }

    @Test
    fun globalExpiredRecoveryDoesNotTemporaryBindOnOutOfProduceTime() {
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_GLOBAL,
                null,
                OttaiCloudClient.BIZ_OUT_OF_PRODUCE_TIME,
            ),
        )
        assertNull(
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_GLOBAL,
                OttaiCloudClient.GLOBAL_MATERIAL_BIND_DEVICE_VERSION,
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
    fun selectedSensorVersionWinsMaterialRecoveryExceptOutOfProduceTime() {
        assertEquals(
            "vE1.2.3(V1.7.SH2542.1)",
            OttaiCloudClient.materialBindDeviceVersion(
                OttaiConstants.API_BASE_SYAI,
                " vE1.2.3(V1.7.SH2542.1) ",
                "AppDevice_AlreadyUsed",
            ),
        )
        assertNull(
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
        val temporary = deviceResponse(activeTime = 1_757_000_000_000L)

        // No caller-supplied start survives: the list's bindTime can move with a re-bind (#34).
        assertEquals(0L, OttaiCloudClient.sanitizeTemporaryBindResponse(temporary).activeTime)
    }

    @Test
    fun aTemporaryBindStampReadsAsUnknown() {
        val boundAt = 1_757_000_000_000L
        val slack = OttaiCloudClient.TEMPORARY_BIND_STAMP_SLACK_MS
        // The bind's own stamp, a seconds-truncated echo, and anything later are the synthetic start.
        assertEquals(0L, OttaiCloudClient.activeTimeOutsideTemporaryBind(boundAt, boundAt))
        assertEquals(0L, OttaiCloudClient.activeTimeOutsideTemporaryBind(boundAt - 999L, boundAt))
        assertEquals(0L, OttaiCloudClient.activeTimeOutsideTemporaryBind(boundAt + 60_000L, boundAt))
        assertEquals(0L, OttaiCloudClient.activeTimeOutsideTemporaryBind(boundAt - slack, boundAt))
        // A real start older than the bind survives; with no bind on record nothing is touched.
        val tenDaysBefore = boundAt - 10L * 24 * 3_600_000L
        assertEquals(tenDaysBefore, OttaiCloudClient.activeTimeOutsideTemporaryBind(tenDaysBefore, boundAt))
        assertEquals(
            boundAt - slack - 1L,
            OttaiCloudClient.activeTimeOutsideTemporaryBind(boundAt - slack - 1L, boundAt),
        )
        assertEquals(boundAt, OttaiCloudClient.activeTimeOutsideTemporaryBind(boundAt, 0L))
        assertEquals(0L, OttaiCloudClient.activeTimeOutsideTemporaryBind(0L, boundAt))
    }

    @Test
    fun materialsNormalizeSecondsBeforeTheTemporaryBindCheck() {
        val boundAt = 1_700_000_183_000L
        // bindV3 echoes the bind's own stamp in epoch seconds: compared raw it is far older than
        // the ms stamp and would survive as a start at the bind moment.
        assertEquals(0L, OttaiCloudClient.materialsActiveTimeMs(boundAt / 1_000L, boundAt))
        assertEquals(0L, OttaiCloudClient.materialsActiveTimeMs(boundAt, boundAt))
        val tenDaysBeforeSec = (boundAt - 10L * 24 * 3_600_000L) / 1_000L
        assertEquals(
            tenDaysBeforeSec * 1_000L,
            OttaiCloudClient.materialsActiveTimeMs(tenDaysBeforeSec, boundAt),
        )
        // No temporary bind on record: only the unit is normalized.
        assertEquals(boundAt, OttaiCloudClient.materialsActiveTimeMs(boundAt / 1_000L, 0L))
        assertEquals(boundAt, OttaiCloudClient.materialsActiveTimeMs(boundAt, 0L))
        assertEquals(0L, OttaiCloudClient.materialsActiveTimeMs(0L, boundAt))
    }

    @Test
    fun forgedCnIpHeadersGoOnlyToTheCnBackend() {
        // Geoblock rule (35053c6c, confirmed on-device): a forged CN IP makes GLOBAL/SYAI reject the
        // session, while the CN backend refuses requests without one.
        for (base in listOf(
            OttaiConstants.API_BASE_GLOBAL,
            OttaiConstants.API_BASE_SYAI,
            OttaiConstants.API_BASE_SYAI_LEGACY,
            OttaiRegistry.normalizeApiBase(OttaiConstants.API_BASE_SYAI_LEGACY),
        )) {
            val headers = watchHeaders(base)
            for (name in forwardedIpHeaders) assertFalse("$name sent to $base", headers.containsKey(name))
        }
        val cn = watchHeaders(OttaiConstants.API_BASE)
        for (name in forwardedIpHeaders) assertEquals(name, OttaiConstants.CN_FORWARD_IP, cn[name])
    }

    @Test
    fun watchHeadersKeepTheRecoveredIdentityAndOrder() {
        val headers = watchHeaders(OttaiConstants.API_BASE, token = "test-token")
        assertEquals(
            listOf(
                "appName", "versionName", "versionCode", "packageName", "ua", "timezone", "timeZoneName",
                "language", "traceId", "timestamp", "country", "deviceId",
            ) + forwardedIpHeaders + "Authorization",
            headers.keys.toList(),
        )
        assertEquals("ottai-watch", headers["appName"])
        assertEquals("com.ottai.tag.watch", headers["packageName"])
        assertEquals("ottai-watch:a:test-device", headers["deviceId"])
        assertEquals("10800", headers["timezone"])
        assertEquals("Europe/Moscow", headers["timeZoneName"])
        assertEquals("ru", headers["language"])
        assertEquals("123", headers["timestamp"])
        assertEquals("test-token", headers["Authorization"])
    }

    @Test
    fun blankTokenSendsNoAuthorization() {
        assertFalse(watchHeaders(OttaiConstants.API_BASE_GLOBAL, token = "").containsKey("Authorization"))
        assertFalse(watchHeaders(OttaiConstants.API_BASE, token = "").containsKey("Authorization"))
    }

    @Test
    fun onlyTheCnBackendCanUseAStoredPhoneProfile() {
        val phone = OttaiRegistry.SessionProfile.CN_PHONE
        val watch = OttaiRegistry.SessionProfile.WATCH
        assertEquals(phone, OttaiCloudClient.profileFor(OttaiConstants.API_BASE, phone))
        assertEquals(watch, OttaiCloudClient.profileFor(OttaiConstants.API_BASE, watch))
        assertEquals(watch, OttaiCloudClient.profileFor(OttaiConstants.API_BASE_GLOBAL, phone))
        assertEquals(watch, OttaiCloudClient.profileFor(OttaiConstants.API_BASE_SYAI, phone))
        assertEquals(watch, OttaiCloudClient.profileFor(OttaiConstants.API_BASE_SYAI_LEGACY, phone))
    }

    @Test
    fun listDevicesParseFailureIsNotAnEmptyAccount() {
        assertNull(OttaiCloudClient.parseDeviceListPayload(JSONObject().put("code", "200")))
        assertNull(
            OttaiCloudClient.parseDeviceListPayload(
                JSONObject().put("code", "200").put("data", JSONObject()),
            ),
        )
        assertFalse(
            OttaiCloudClient.isCloudBizOk(JSONObject().put("code", "AuthFailed").put("data", JSONObject())),
        )
        // request() hands its callers a body only through classifyResponse: never an error stream's.
        assertNull(OttaiCloudClient.classifyResponse(502, """{"code":"200","data":{}}""").first)
        assertNotNull(OttaiCloudClient.classifyResponse(200, """{"code":"200","data":{}}""").first)
    }

    @Test
    fun listDevicesEmptyItemsIsAnEmptyAccount() {
        val resp = JSONObject()
            .put("code", "200")
            .put("data", JSONObject().put("items", org.json.JSONArray()))
        assertEquals(emptyList<OttaiCloudClient.DeviceSummary>(), OttaiCloudClient.parseDeviceListPayload(resp))
    }

    @Test
    fun listDevicesReadsMacAndVersionFromItems() {
        val items = org.json.JSONArray().put(
            JSONObject()
                .put("mac", "001122334455")
                .put("serialNo", "SN1")
                .put("deviceType", "cgm")
                .put("deviceVersion", "vE1.2.3")
                .put("bindTime", 10)
                .put("unbindTime", 0),
        )
        val resp = JSONObject().put("data", JSONObject().put("items", items))
        val parsed = OttaiCloudClient.parseDeviceListPayload(resp)!!
        assertEquals(1, parsed.size)
        assertEquals("001122334455", parsed[0].mac)
        assertEquals("vE1.2.3", parsed[0].deviceVersion)
        assertTrue(parsed[0].isActive)
    }

    @Test
    fun logoutUnbindIsOnlyForGlobalAndSyai() {
        assertFalse(OttaiCloudClient.shouldReleaseCloudBindingOnLogout(OttaiConstants.API_BASE))
        assertTrue(OttaiCloudClient.shouldReleaseCloudBindingOnLogout(OttaiConstants.API_BASE_GLOBAL))
        assertTrue(OttaiCloudClient.shouldReleaseCloudBindingOnLogout(OttaiConstants.API_BASE_SYAI))
        assertTrue(OttaiCloudClient.shouldReleaseCloudBindingOnLogout(OttaiConstants.API_BASE_SYAI_LEGACY))
    }

    @Test
    fun failedUnbindKeepsCredentialsUntilForcedLocalLogout() {
        assertTrue(
            OttaiCloudClient.logoutKeepsLocalCredentials(
                requiresUnbind = true,
                unbindSucceeded = false,
                forceLocal = false,
            ),
        )
        assertFalse(
            OttaiCloudClient.logoutKeepsLocalCredentials(
                requiresUnbind = true,
                unbindSucceeded = true,
                forceLocal = false,
            ),
        )
        assertFalse(
            OttaiCloudClient.logoutKeepsLocalCredentials(
                requiresUnbind = true,
                unbindSucceeded = false,
                forceLocal = true,
            ),
        )
        assertFalse(
            OttaiCloudClient.logoutKeepsLocalCredentials(
                requiresUnbind = false,
                unbindSucceeded = false,
                forceLocal = false,
            ),
        )
    }

    @Test
    fun unsignedWizardDefaultsToGlobalUntilASignInSaysOtherwise() {
        // Clean install: nothing stored either way.
        assertEquals(
            OttaiConstants.API_BASE_GLOBAL,
            OttaiRegistry.wizardApiBaseForUnsigned(null, null),
        )
        assertEquals(
            OttaiConstants.API_BASE_GLOBAL,
            OttaiRegistry.wizardApiBaseForUnsigned("", "   "),
        )
        // The region of the last successful sign-in wins for every region, including CN.
        assertEquals(
            OttaiConstants.API_BASE,
            OttaiRegistry.wizardApiBaseForUnsigned(OttaiConstants.API_BASE, OttaiConstants.API_BASE),
        )
        assertEquals(
            OttaiConstants.API_BASE_SYAI,
            OttaiRegistry.wizardApiBaseForUnsigned(OttaiConstants.API_BASE_SYAI, OttaiConstants.API_BASE),
        )
        assertEquals(
            OttaiConstants.API_BASE_GLOBAL,
            OttaiRegistry.wizardApiBaseForUnsigned(OttaiConstants.API_BASE_GLOBAL, null),
        )
        assertEquals(
            OttaiConstants.API_BASE_SYAI,
            OttaiRegistry.wizardApiBaseForUnsigned(OttaiConstants.API_BASE_SYAI_LEGACY, null),
        )
        // The runtime backend keeps mapping "nothing stored" to CN — that is an existing session's
        // host, not the wizard default, and CN runtime must not move.
        assertEquals(
            OttaiConstants.API_BASE,
            OttaiRegistry.normalizeApiBase(null),
        )
    }

    @Test
    fun logoutBackfillsTheRegionOnlyForALivePreKeySession() {
        // A token proves a sign-in; the legacy CN stamp left none behind it.
        assertTrue(OttaiRegistry.shouldBackfillLoginApiBaseOnLogout(null, "token"))
        assertTrue(OttaiRegistry.shouldBackfillLoginApiBaseOnLogout("  ", "token"))
        // The unsigned legacy stamp has nothing to prove the region with.
        assertFalse(OttaiRegistry.shouldBackfillLoginApiBaseOnLogout(null, ""))
        // A key a sign-in already wrote must not be overwritten by the runtime host.
        assertFalse(
            OttaiRegistry.shouldBackfillLoginApiBaseOnLogout(OttaiConstants.API_BASE_SYAI, "token"),
        )
    }

    @Test
    fun partialWebTokenIsNotASignInWorthReopeningOn() {
        // persistWebLogin gates the login-region key on ok, not on the bare token.
        assertFalse(OttaiCloudClient.LoginResult("u", "token", "").ok)
        assertTrue(OttaiCloudClient.LoginResult("u", "token", "secret").ok)
        assertFalse(OttaiCloudClient.shouldPersistWebLogin(OttaiCloudClient.LoginResult("u", "token", "")))
        assertTrue(OttaiCloudClient.shouldPersistWebLogin(OttaiCloudClient.LoginResult("u", "token", "secret")))
    }

    @Test
    fun unsignedWizardIgnoresTheCnHostLeftBehindByOlderSignOuts() {
        // Every sign-out up to 2026-08-18 stamped CN into the runtime pref, so CN there proves
        // nothing about what the user picked: a Global/Syai user must not land on the CN SMS form.
        assertEquals(
            OttaiConstants.API_BASE_GLOBAL,
            OttaiRegistry.wizardApiBaseForUnsigned(null, OttaiConstants.API_BASE),
        )
        // A non-CN runtime host could only come from a non-CN sign-in, so it is still trusted and
        // keeps a pre-key Syai/Global sign-out in its own region.
        assertEquals(
            OttaiConstants.API_BASE_SYAI,
            OttaiRegistry.wizardApiBaseForUnsigned(null, OttaiConstants.API_BASE_SYAI),
        )
        assertEquals(
            OttaiConstants.API_BASE_SYAI,
            OttaiRegistry.wizardApiBaseForUnsigned(null, OttaiConstants.API_BASE_SYAI_LEGACY),
        )
        assertEquals(
            OttaiConstants.API_BASE_GLOBAL,
            OttaiRegistry.wizardApiBaseForUnsigned(null, OttaiConstants.API_BASE_GLOBAL),
        )
        // A CN sign-in after the upgrade re-establishes CN, so the ignore rule is not a one-way trap.
        assertEquals(
            OttaiConstants.API_BASE,
            OttaiRegistry.wizardApiBaseForUnsigned(OttaiConstants.API_BASE, OttaiConstants.API_BASE),
        )
    }

    @Test
    fun materialFetchErrorBeatsInformationalStatus() {
        assertEquals(
            "fetch failed",
            OttaiConstants.preferredWizardStatus("Selected AABBCCDDEEFF", "fetch failed"),
        )
        assertEquals(
            "Selected AABBCCDDEEFF",
            OttaiConstants.preferredWizardStatus("Selected AABBCCDDEEFF", ""),
        )
        assertEquals(
            listOf("fetch failed", "Selected AABBCCDDEEFF"),
            OttaiConstants.wizardVisibleMessages("Selected AABBCCDDEEFF", "fetch failed"),
        )
        assertEquals(
            listOf("unbind failed"),
            OttaiConstants.wizardVisibleMessages("unbind failed", ""),
        )
    }

    @Test
    fun sequentialBleTapsRetargetRadioAndBleCopiedCloudId() {
        val first = OttaiConstants.applyUserBleTap(
            existingBle = "",
            existingCloudId = "",
            origin = OttaiConstants.CloudIdOrigin.UNKNOWN,
            scannedAddress = "AA:BB:CC:DD:EE:FF",
        )
        assertTrue(first.bleAssigned)
        assertEquals("AA:BB:CC:DD:EE:FF", first.bleAddress)
        assertEquals("AABBCCDDEEFF", first.cloudId)
        assertEquals(OttaiConstants.CloudIdOrigin.BLE, first.origin)
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            OttaiConstants.bleTapStatusAddress("", first.bleAddress, first.bleAssigned),
        )

        val second = OttaiConstants.applyUserBleTap(
            existingBle = first.bleAddress,
            existingCloudId = first.cloudId,
            origin = first.origin,
            scannedAddress = "11:22:33:44:55:66",
        )
        assertTrue(second.bleAssigned)
        assertEquals("11:22:33:44:55:66", second.bleAddress)
        assertEquals("112233445566", second.cloudId)
        assertEquals(
            "11:22:33:44:55:66",
            OttaiConstants.bleTapStatusAddress(first.bleAddress, second.bleAddress, second.bleAssigned),
        )
        assertNull(
            OttaiConstants.bleTapStatusAddress(
                second.bleAddress,
                second.bleAddress,
                bleAssigned = true,
            ),
        )
    }

    @Test
    fun usernameLoginDoesNotSendStoredJwtAndWipeIsNotLogout() {
        assertEquals("", OttaiCloudClient.PASSWORD_LOGIN_DEFAULT_AUTHORIZATION_OVERRIDE)
        assertEquals(
            "",
            OttaiCloudClient.resolvedAuthorization(
                OttaiCloudClient.PASSWORD_LOGIN_DEFAULT_AUTHORIZATION_OVERRIDE,
                "leftover-jwt",
            ),
        )
        assertEquals("web-jwt", OttaiCloudClient.resolvedAuthorization("web-jwt", "leftover-jwt"))
        assertEquals("stored-jwt", OttaiCloudClient.resolvedAuthorization(null, "stored-jwt"))
        assertTrue(OttaiCloudClient.shouldWipeLeftoverPartialSession("leftover-jwt", ""))
        assertFalse(OttaiCloudClient.shouldWipeLeftoverPartialSession("leftover-jwt", "secret"))
        assertFalse(OttaiCloudClient.shouldWipeLeftoverPartialSession("", ""))
    }

    @Test
    fun independentCloudIdSurvivesBleTransportTap() {
        val tapped = OttaiConstants.applyUserBleTap(
            existingBle = "",
            existingCloudId = "AA:BB:CC:DD:EE:FF",
            origin = OttaiConstants.CloudIdOrigin.INDEPENDENT,
            scannedAddress = "11:22:33:44:55:66",
        )
        assertEquals("11:22:33:44:55:66", tapped.bleAddress)
        assertEquals("AABBCCDDEEFF", tapped.cloudId)
        assertEquals(OttaiConstants.CloudIdOrigin.INDEPENDENT, tapped.origin)
        assertEquals(
            "AABBCCDDEEFF",
            OttaiConstants.cloudIdForBleSelection(
                "AA:BB:CC:DD:EE:FF",
                "11:22:33:44:55:66",
                OttaiConstants.CloudIdOrigin.INDEPENDENT,
            ),
        )
        assertTrue(OttaiConstants.shouldAssignScannedBleAddress("11:22:33:44:55:66"))
        assertFalse(OttaiConstants.shouldAssignScannedBleAddress(""))
    }

    private fun watchHeaders(apiBase: String, token: String = "test-token") = OttaiCloudClient.watchHeaders(
        apiBase = apiBase,
        deviceId = "test-device",
        token = token,
        ts = 123L,
        tzOffsetSec = 10_800,
        tzId = "Europe/Moscow",
        language = "ru",
    )

    private val forwardedIpHeaders = listOf("X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP")

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
