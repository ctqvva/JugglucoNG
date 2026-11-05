# Lumiflex Cloud Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Lumiflex vendor-cloud channel to JNG that logs the patient into `cloud.lumiflex.ru`, uploads glucose, and manages followers — mirroring `share/ican`.

**Architecture:** A self-contained Kotlin package `tk.glucodata.share.lumiflex` (constants, crypto, client, models, error, queue, uploader, settings, follower manager) driven by the existing `HistoryRepository` backfill hook, plus two Compose settings screens. Network is `HttpURLConnection` + `org.json`; every POST body and every encrypted response is wrapped in the vendor RSA envelope.

**Tech Stack:** Kotlin, Android, `org.json`, `java.security`/`javax.crypto`, Robolectric + JUnit4 for tests, Jetpack Compose (Material3) for UI.

**Spec:** `docs/superpowers/specs/2026-11-01-lumiflex-cloud-design.md`

## Global Constraints

- No new Gradle dependencies; use `HttpURLConnection`, `org.json`, `java.security`, `javax.crypto`.
- Package: `tk.glucodata.share.lumiflex`. Tests: `Common/src/test/java/tk/glucodata/share/lumiflex/`.
- Base URL `https://cloud.lumiflex.ru`, prefix `/api-x`, brand `lumi`, vendor version `2.4.0`.
- Upload body key is `records`; `frontRecordId` = MD5(`sensorId` + `timeOffset` minutes); `timeOffset` is minutes since sensor start; `dstOffset` is a String; never send `mainDeviceId`, `sensorStartTime`, `trendValue`, `cgmSn`.
- No tokens, passwords or glucose values in logs.
- Feature is inert when `LumiflexCloudSettings.isEnabled(context)` is false.
- Never commit unless the partner asks.

## Review Focus

- A row with `mgdl <= 0` or a sensor that is not the one JNG paired must not reach the vendor (`onHistoryStored` filter).
- `timeOffset` must never be negative or derived from an unknown sensor start (skip the row instead of sending a bogus minute).
- A 401 during a batch must stop the whole drain and clear the token, not drop the queue.
- Envelope chunking must survive payloads larger than one RSA block (a 100-record batch is far above the 117-byte limit).
- Retry must not duplicate records: `frontRecordId` is stable across attempts for the same reading.

---

### Task 1: Constants and RSA envelope

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudConstants.kt`
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudCrypto.kt`
- Test: `Common/src/test/java/tk/glucodata/share/lumiflex/LumiflexCloudCryptoTests.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object LumiflexCloudConstants` with: `BASE_URL`, `API_PREFIX`, `VENDOR_VERSION`, `APP_INFO`, `BRAND`, `HEADER_VERSION`, `HEADER_BRAND`, `HEADER_TOKEN`, `HEADER_ENCRYPTION`, `HEADER_APP_VERSION_CHECK`, `HEADER_APP_INFO`, `HEADER_ACCEPT_LANGUAGE`, `ENCRYPTION_ENABLED`, `RSA_PUBLIC_KEY`, `RSA_PRIVATE_KEY`, `PATH_LOGIN`, `PATH_USER_INFO`, `PATH_LOGOUT`, `PATH_DEVICE_REGISTER`, `PATH_CGM_SAVE`, `PATH_FOLLOWER_LIST`, `PATH_FOLLOWER_SAVE`, `PATH_FOLLOWER_UPDATE`, `PATH_FOLLOWER_BY_ID`, `PATH_FOLLOWER_DELETE`, `BATCH_SIZE`, `QUEUE_CAP`.
  - `object LumiflexCloudCrypto` with `fun encryptEnvelope(plainJson: String): String`, `fun decryptEnvelope(body: String): String?`, `fun versionCheckHeader(userId: String, nowMs: Long): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumiflexCloudCryptoTests {

    @Test
    fun envelopeRoundTripsSmallPayload() {
        val plain = """{"userName":"a@b.c","password":"pw"}"""
        val envelope = LumiflexCloudCrypto.encryptEnvelope(plain)
        val encrypted = JSONObject(envelope).getString("encryptData")
        assertTrue(encrypted.isNotEmpty())
        assertEquals(plain, LumiflexCloudCrypto.decryptEnvelope(envelope))
    }

    @Test
    fun envelopeChunksPayloadLargerThanOneBlock() {
        val plain = JSONObject().put("blob", "x".repeat(2000)).toString()
        assertEquals(plain, LumiflexCloudCrypto.decryptEnvelope(LumiflexCloudCrypto.encryptEnvelope(plain)))
    }

    @Test
    fun decryptReturnsNullForNonEnvelopeBody() {
        assertNull(LumiflexCloudCrypto.decryptEnvelope("""{"code":200,"data":{}}"""))
    }

    @Test
    fun versionCheckHeaderCarriesVersionTimestampAndUser() {
        val decrypted = LumiflexCloudCrypto.decryptEnvelope(
            LumiflexCloudCrypto.versionCheckHeader("user-1", 1_700_000_000_000L),
        )
        val json = JSONObject(decrypted!!)
        assertEquals("2.4.0", json.getString("version"))
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
        assertEquals("user-1", json.getString("userId"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.*"`
Expected: FAIL — unresolved references `LumiflexCloudCrypto`, `LumiflexCloudConstants`.

- [ ] **Step 3: Implement constants and crypto**

`LumiflexCloudConstants.kt`: plain `object` with the names above. Values: `BASE_URL = "https://cloud.lumiflex.ru"`, `API_PREFIX = "/api-x"`, `VENDOR_VERSION = "2.4.0"`, `APP_INFO = "com.microtech.aidexx.lumi.mmoll,2.4.0"`, `BRAND = "lumi"`, header names `version`/`brand`/`x-token`/`encryption`/`app-version-check`/`app-info`/`Accept-Language`, `ENCRYPTION_ENABLED = "enabled"`, paths `"/user/loginByPassword"`, `"/user/getUserInfo"`, `"/user/logout"`, `"/cgmDevice/userDeviceRegister"`, `"/cgmRecord/saveCgmRecord"`, `"/userAuthorization/findUserAuthorizationList"`, `"/userAuthorization/saveOrUpdateUserAuthorization"`, `"/userAuthorization/updateAuthorizationInfo"`, `"/userAuthorization/findAuthorizationInfoById"`, `"/userAuthorization/deleteByIds"`, `BATCH_SIZE = 100`, `QUEUE_CAP = 5000`. RSA keys copied verbatim from `docs/lumiflex-cloud-recon.md` §3 (`RSA_PUBLIC_KEY` public, `RSA_PRIVATE_KEY` private, single constants with a one-line comment that they ship in every official install).

`LumiflexCloudCrypto.kt`: `encryptEnvelope` parses nothing — take the plain string, RSA-encrypt with `RSA/ECB/PKCS1Padding` in chunks of `key.modulus.bitLength()/8 - 11`, base64 the concatenation, return `JSONObject().put("encryptData", b64).toString()`. `decryptEnvelope` returns `null` when the body has no `encryptData`; otherwise RSA-decrypt blockwise (`blockSize = modulus.bitLength()/8`) and return UTF-8. `versionCheckHeader` builds `{"version":"2.4.0","timestamp":nowMs,"userId":userId}` and returns `encryptEnvelope(...)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/share/lumiflex Common/src/test/java/tk/glucodata/share/lumiflex
git commit -m "feat(lumiflex): vendor constants and RSA envelope"
```

---

### Task 2: Models and record JSON

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudModels.kt`
- Test: `Common/src/test/java/tk/glucodata/share/lumiflex/LumiflexCloudModelsTests.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class CgmRecord(val serial: String, val sensorId: String, val sensorIndex: Int, val timeOffsetMinutes: Int, val timeMs: Long, val mgdl: Int, val trend: Int)` with `fun frontRecordId(): String` and `fun toJson(userId: String, appTime: String, appTimeZone: String, appCreateTime: String, dstOffset: String): JSONObject`.
  - `data class LoginModel(val token: String, val userId: String, val email: String, val phone: String)` with `companion object { fun fromJson(json: JSONObject?): LoginModel }`.
  - `data class FollowerInfo(...)` with `companion object { fun fromJson(json: JSONObject?): FollowerInfo }` carrying at least `userAuthorizationId`, `providerUserName`, `providerAlias`, `readerUserName`, `readerAlias`, `hideState`, `normalPushState`, `emergePushState`, `extremeHighPushState`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LumiflexCloudModelsTests {

    @Test
    fun frontRecordIdIsMd5OfSensorIdAndOffsetMinutes() {
        val record = CgmRecord("SN1", "SN1", 0, 123, 1_700_000_000_000L, 112, 2)
        assertEquals("a7eb29ff5b7727e9ee4c9426d8fd5313", record.frontRecordId())
    }

    @Test
    fun recordJsonUsesVendorFieldNames() {
        val record = CgmRecord("SN1", "SN1", 0, 12345, 1_700_000_000_000L, 112, 2)
        val json = record.toJson(
            userId = "u1",
            appTime = "2026-11-01 12:34:56",
            appTimeZone = "Europe/Moscow",
            appCreateTime = "2026-11-01 12:34:58",
            dstOffset = "10800",
        )
        assertEquals("899df6d83daf24a748677ecd2ef8d748", json.getString("frontRecordId"))
        assertEquals("u1", json.getString("userId"))
        assertEquals("SN1", json.getString("sensorId"))
        assertEquals("SN1", json.getString("deviceSn"))
        assertEquals(12345, json.getInt("timeOffset"))
        assertEquals(112, json.getInt("glucose"))
        assertEquals("2026-11-01 12:34:56", json.getString("appTime"))
        assertEquals("Europe/Moscow", json.getString("appTimeZone"))
        assertEquals("10800", json.getString("dstOffset"))
        assertFalse(json.has("mainDeviceId"))
        assertFalse(json.has("sensorStartTime"))
        assertFalse(json.has("trendValue"))
        assertFalse(json.has("cgmSn"))
    }

    @Test
    fun loginModelReadsDataEnvelope() {
        val model = LoginModel.fromJson(
            JSONObject("""{"token":"tok","userId":"u1","email":"a@b.c","phone":""}"""),
        )
        assertEquals("tok", model.token)
        assertEquals("u1", model.userId)
        assertEquals("a@b.c", model.email)
    }

    @Test
    fun followerInfoReadsFlags() {
        val info = FollowerInfo.fromJson(
            JSONObject("""{"userAuthorizationId":"a1","readerUserName":"f@b.c","readerAlias":"Ann","hideState":1,"normalPushState":0,"emergePushState":1,"extremeHighPushState":0}"""),
        )
        assertEquals("a1", info.userAuthorizationId)
        assertEquals("Ann", info.readerAlias)
        assertEquals(1, info.hideState)
        assertEquals("", FollowerInfo.fromJson(null).userAuthorizationId)
        assertTrue(FollowerInfo.fromJson(JSONObject("{}")).readerUserName.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudModelsTests"`
Expected: FAIL — unresolved `CgmRecord`, `LoginModel`, `FollowerInfo`.

- [ ] **Step 3: Implement the models**

`frontRecordId()` = `md5("$sensorId$timeOffsetMinutes")` as lowercase hex (use `java.security.MessageDigest`). `toJson` puts exactly: `frontRecordId`, `userId`, `sensorId`, `deviceSn` (= serial), `sensorIndex`, `timeOffset`, `glucose`, `glucoseIsValid` (1), `status` (0), `quality` (0), `eventWarning` (0), `deviceStatus` (0), `appTime`, `appTimeZone`, `appCreateTime`, `dstOffset`, `rawOne` (0.0), `rawTwo` (0.0), `rawVc` (0.0), `rawIsValid` (1). All `fromJson` helpers use `optString`/`optInt` with defaults so a missing field never throws.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudModelsTests"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudModels.kt Common/src/test/java/tk/glucodata/share/lumiflex/LumiflexCloudModelsTests.kt
git commit -m "feat(lumiflex): cloud models and CGM record JSON"
```

---

### Task 3: Settings and persistent queue

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudSettings.kt`
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudQueue.kt`
- Test: `Common/src/test/java/tk/glucodata/share/lumiflex/LumiflexCloudSettingsQueueTests.kt`

**Interfaces:**
- Consumes: `CgmRecord`.
- Produces:
  - `class LumiflexCloudSettings(context: Context)`: `enabled`, `account`, `token`, `userId`, `lastSyncMs`, `clear()`; companion `isEnabled(context): Boolean`, `isSetUp(context): Boolean`.
  - `class LumiflexCloudQueue(context: Context)`: `enqueue(record: CgmRecord)`, `peek(): List<CgmRecord>`, `remove(records: Collection<CgmRecord>)`, `size(): Int`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tk.glucodata.share.lumiflex

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudSettingsQueueTests {

    @Test
    fun settingsRoundTripAndStatics() {
        val context = RuntimeEnvironment.getApplication() as LumiflexCloudTestApplication
        context.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = LumiflexCloudSettings(context)
        settings.account = "a@b.c"
        settings.token = "tok"
        settings.userId = "u1"
        settings.lastSyncMs = 42L
        val again = LumiflexCloudSettings(context)
        assertEquals("a@b.c", again.account)
        assertEquals("tok", again.token)
        assertEquals("u1", again.userId)
        assertEquals(42L, again.lastSyncMs)
        assertTrue(LumiflexCloudSettings.isSetUp(context))
        assertEquals(false, LumiflexCloudSettings.isEnabled(context))
        again.clear()
        assertEquals("", LumiflexCloudSettings(context).account)
    }

    @Test
    fun queueDedupesAndPersistsAcrossInstances() {
        val context = RuntimeEnvironment.getApplication() as LumiflexCloudTestApplication
        val queue = LumiflexCloudQueue(context)
        queue.remove(queue.peek())
        val record = CgmRecord("SN1", "SN1", 0, 10, 1_700_000_000_000L, 100, 0)
        queue.enqueue(record)
        queue.enqueue(record)
        assertEquals(1, queue.size())
        assertEquals(1, LumiflexCloudQueue(context).size())
        queue.remove(listOf(record))
        assertEquals(0, LumiflexCloudQueue(context).size())
    }
}

class LumiflexCloudTestApplication : Applic() {
    override fun onCreate() {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudSettingsQueueTests"`
Expected: FAIL — unresolved `LumiflexCloudSettings`, `LumiflexCloudQueue`.

- [ ] **Step 3: Implement settings and queue**

Settings mirrors `ICanHealthCloudSettings` (prefs file `lumiflex_cloud`, keys `lumiflex_cloud_enabled/account/token/user_id/last_sync_ms`). Queue mirrors `ICanHealthCloudQueue`: JSON array file `lumiflex_cloud_queue.json` in `filesDir`, sorted by `timeMs`, dedupe on `(serial, timeOffsetMinutes)`, cap at `LumiflexCloudConstants.QUEUE_CAP` (drop oldest), atomic write via temp file + rename.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudSettingsQueueTests"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/share/lumiflex Common/src/test/java/tk/glucodata/share/lumiflex
git commit -m "feat(lumiflex): settings and persistent upload queue"
```

---

### Task 4: Client and error mapping

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudError.kt`
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudClient.kt`
- Test: `Common/src/test/java/tk/glucodata/share/lumiflex/LumiflexCloudClientTests.kt`

**Interfaces:**
- Consumes: `LumiflexCloudConstants`, `LumiflexCloudCrypto`, `CgmRecord`, `LoginModel`, `FollowerInfo`.
- Produces:
  - `class LumiflexCloudClient(private val transport: Transport = HttpTransport)` with nested `interface Transport { fun post(url: String, body: String, headers: Map<String, String>): HttpResult; fun get(url: String, headers: Map<String, String>): HttpResult }`, `data class HttpResult(val code: Int, val body: String)`.
  - `sealed class LoginResult { data class Ok(val userId: String) ; data class Failed(val kind: LumiflexCloudError.Kind, val code: Int, val message: String) }`
  - `sealed class SyncResult { object Ok ; data class Failed(...) }`
  - methods: `login(account: String, password: String, settings: LumiflexCloudSettings): LoginResult`, `uploadGlucose(token: String, userId: String, records: List<CgmRecord>, sensorStartMs: Long, nowMs: Long): SyncResult`, `followerList(token: String, isShare: Boolean): List<FollowerInfo>`, `addFollower(token: String, account: String, alias: String): FollowerInfo`, `updateFollower(token: String, id: String, displayHidden: Boolean?, normalPush: Boolean?, urgentPush: Boolean?, extremeHighPush: Boolean?): Boolean`, `removeFollower(token: String, id: String): Boolean`.
  - `sealed class LumiflexCloudError { enum class Kind { BAD_CREDENTIALS, UNAUTHORIZED, TRANSIENT, OTHER }; companion object { fun classify(code: Int, body: String?): Kind; fun message(body: String?): String } }`

- [ ] **Step 1: Write the failing test**

```kotlin
package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudClientTests {

    private class FakeTransport(var handler: (String, String) -> LumiflexCloudClient.HttpResult) :
        LumiflexCloudClient.Transport {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            urls.add(url); this.headers.add(headers); return handler(url, body)
        }
        override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            urls.add(url); this.headers.add(headers); return handler(url, "")
        }
    }

    private fun settings(): LumiflexCloudSettings =
        LumiflexCloudSettings(RuntimeEnvironment.getApplication())

    @Test
    fun loginStoresTokenAndUserIdAndSendsEnvelopeAndHeaders() {
        val transport = FakeTransport { _, body ->
            val plain = LumiflexCloudCrypto.decryptEnvelope(body)!!
            assertTrue(plain.contains("\"userName\":\"a@b.c\""))
            LumiflexCloudClient.HttpResult(
                200,
                LumiflexCloudCrypto.encryptEnvelope("""{"code":200,"data":{"token":"tok","userId":"u1","email":"a@b.c","phone":""}}"""),
            )
        }
        val client = LumiflexCloudClient(transport)
        val s = settings()
        val result = client.login("a@b.c", "pw", s)
        assertTrue(result is LumiflexCloudClient.LoginResult.Ok)
        assertEquals("tok", s.token)
        assertEquals("u1", s.userId)
        assertEquals("https://cloud.lumiflex.ru/api-x/user/loginByPassword", transport.urls.first())
        assertEquals("lumi", transport.headers.first()["brand"])
        assertEquals("ru", transport.headers.first()["version"])
        assertEquals("enabled", transport.headers.first()["encryption"])
        assertTrue(transport.headers.first().containsKey("app-version-check"))
    }

    @Test
    fun login401ClassifiesUnauthorized() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(401, "") }
        val result = LumiflexCloudClient(transport).login("a@b.c", "pw", settings())
        assertTrue(result is LumiflexCloudClient.LoginResult.Failed)
        assertEquals(LumiflexCloudError.Kind.UNAUTHORIZED, (result as LumiflexCloudClient.LoginResult.Failed).kind)
    }

    @Test
    fun uploadSendsRecordsArray() {
        var plainSeen = ""
        val transport = FakeTransport { _, body ->
            plainSeen = LumiflexCloudCrypto.decryptEnvelope(body)!!
            LumiflexCloudClient.HttpResult(200, LumiflexCloudCrypto.encryptEnvelope("""{"code":200,"data":{}}"""))
        }
        val client = LumiflexCloudClient(transport)
        val result = client.uploadGlucose(
            token = "tok", userId = "u1",
            records = listOf(CgmRecord("SN1", "SN1", 0, 5, 1_700_000_300_000L, 100, 1)),
            sensorStartMs = 1_700_000_000_000L,
            nowMs = 1_700_000_300_000L,
        )
        assertTrue(result is LumiflexCloudClient.SyncResult.Ok)
        val json = JSONObject(plainSeen)
        assertEquals(1, json.getJSONArray("records").length())
    }

    @Test
    fun upload5xxIsTransient() {
        val transport = FakeTransport { _, _ -> LumiflexCloudClient.HttpResult(503, "") }
        val result = LumiflexCloudClient(transport).uploadGlucose("tok", "u1", emptyList(), 0L, 0L)
        assertEquals(
            LumiflexCloudError.Kind.TRANSIENT,
            (result as LumiflexCloudClient.SyncResult.Failed).kind,
        )
    }

    @Test
    fun followerListParsesDataArray() {
        val transport = FakeTransport { _, _ ->
            LumiflexCloudClient.HttpResult(
                200,
                LumiflexCloudCrypto.encryptEnvelope("""{"code":200,"data":[{"userAuthorizationId":"a1","readerUserName":"f@b.c"}]}"""),
            )
        }
        val list = LumiflexCloudClient(transport).followerList("tok", isShare = true)
        assertEquals(1, list.size)
        assertEquals("a1", list.first().userAuthorizationId)
        assertTrue(transport.urls.first().endsWith("/userAuthorization/findUserAuthorizationList?type=share"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudClientTests"`
Expected: FAIL — unresolved client/error symbols.

- [ ] **Step 3: Implement the client**

Build URLs as `BASE_URL + API_PREFIX + path`. Headers: `version: ru`, `brand: lumi`, `app-info`, `encryption: enabled`, `x-token` when non-blank, `app-version-check = LumiflexCloudCrypto.versionCheckHeader(settings.userId, System.currentTimeMillis())`, `Accept-Language` from `Locale.getDefault().toLanguageTag()`, `Content-Type: application/json;charset=UTF-8`. POST bodies are `LumiflexCloudCrypto.encryptEnvelope(plain)`. Responses: if `decryptEnvelope(body) != null` use the decrypted text, else the raw body. Login sends `{"userName","password"}` and reads `data` into `LoginModel`; on `token` blank → `Failed(BAD_CREDENTIALS)`. `uploadGlucose` builds `{"records":[ ... ]}` with `appTime`/`appCreateTime` = `yyyy-MM-dd HH:mm:ss` (Locale.US, default timezone), `appTimeZone = TimeZone.getDefault().id`, `dstOffset = (TimeZone.getDefault().getOffset(nowMs) / 1000).toString()`, `timeOffset` already in the record. Follower calls use paths above; list parses `data` as array (also accept a bare array). `classify`: 401 → UNAUTHORIZED, 5xx/408/429/-1 → TRANSIENT, else OTHER; `UploadResult.Failed` on non-2xx or decrypted business `code != 200`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudClientTests"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/share/lumiflex Common/src/test/java/tk/glucodata/share/lumiflex
git commit -m "feat(lumiflex): API client and error mapping"
```

---

### Task 5: Uploader and history hook

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudUploader.kt`
- Test: `Common/src/test/java/tk/glucodata/share/lumiflex/LumiflexCloudUploaderTests.kt`

**Interfaces:**
- Consumes: `LumiflexCloudClient`, `LumiflexCloudSettings`, `LumiflexCloudQueue`, `CgmRecord`.
- Produces:
  - `class LumiflexCloudUploader(client, settings, queue, sleep: (Long) -> Unit = { Thread.sleep(it) }, sensorStartProvider: (String) -> Long = { serial -> runCatching { Natives.getSensorStartmsecFromSensorptr(Natives.str2sensorptr(serial)) }.getOrDefault(0L) })` with `data class Row(val serial: String, val timeMs: Long, val mgdl: Int, val trendIndex: Int)` and `fun flush(): FlushResult`.
  - `sealed class FlushResult { data class Success(val uploaded: Int) ; data class Unauthorized(val uploaded: Int) ; data class Failed(val uploaded: Int, val code: Int, val message: String) }`
  - companion: `@JvmStatic fun onHistoryStored(sensorSerial: String, rows: List<Row>)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tk.glucodata.share.lumiflex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudUploaderTests {

    private class FakeTransport(private val code: Int) : LumiflexCloudClient.Transport {
        var calls = 0
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            calls++
            return LumiflexCloudClient.HttpResult(code, "")
        }
        override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult =
            LumiflexCloudClient.HttpResult(code, "")
    }

    private val start = 1_700_000_000_000L

    private fun uploader(code: Int, sensorStart: Long = start): Triple<LumiflexCloudSettings, LumiflexCloudQueue, LumiflexCloudUploader> {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context)
        settings.token = "tok"; settings.userId = "u1"
        val queue = LumiflexCloudQueue(context)
        queue.remove(queue.peek())
        return Triple(
            settings,
            queue,
            LumiflexCloudUploader(
                LumiflexCloudClient(FakeTransport(code)),
                settings,
                queue,
                sleep = {},
                sensorStartProvider = { sensorStart },
            ),
        )
    }

    @Test
    fun flushUploadsAllAndClearsQueue() {
        val (_, queue, up) = uploader(200)
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 2, start + 120_000L, 101, 0))
        assertTrue(up.flush() is LumiflexCloudUploader.FlushResult.Success)
        assertEquals(0, queue.size())
    }

    @Test
    fun flush401ClearsTokenAndKeepsQueue() {
        val (settings, queue, up) = uploader(401)
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        assertTrue(up.flush() is LumiflexCloudUploader.FlushResult.Unauthorized)
        assertEquals("", settings.token)
        assertEquals(1, queue.size())
    }

    @Test
    fun flushSkipsRowsWhenSensorStartUnknown() {
        val (_, queue, up) = uploader(200, sensorStart = 0L)
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        up.flush()
        assertEquals(1, queue.size())
    }

    @Test
    fun flushDropsNonPositiveReadingsWithoutSending() {
        val (_, queue, up) = uploader(200)
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 0, 0))
        up.flush()
        assertEquals(0, queue.size())
    }

    @Test
    fun onHistoryStoredIsInertWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        LumiflexCloudSettings(context).clear()
        val queue = LumiflexCloudQueue(context); queue.remove(queue.peek())
        LumiflexCloudUploader.onHistoryStored(
            "SN1",
            listOf(LumiflexCloudUploader.Row("SN1", start + 60_000L, 100, 0)),
        )
        assertEquals(0, queue.size())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudUploaderTests"`
Expected: FAIL — unresolved `LumiflexCloudUploader`.

- [ ] **Step 3: Implement the uploader**

`flush()` requires a non-blank token. It first removes queued records with `mgdl <= 0` (never send them), then groups the rest by serial, resolves `sensorStartMs` once per serial through `sensorStartProvider`, drops (does not send) every record whose `sensorStartMs <= 0` or `timeMs <= sensorStartMs`, computes `timeOffsetMinutes = ((timeMs - sensorStartMs) / 60_000L).toInt()` **inside** `CgmRecord`, chunks by `BATCH_SIZE`, retries up to 3 times with `100ms * 2^(attempt-1)` on the injected `sleep`, removes only the uploaded subset on success, and returns `Unauthorized` (clearing `settings.token`) on 401 without dropping the queue. `onHistoryStored` mirrors iCan: reads `Applic.app`, returns when `!LumiflexCloudSettings.isEnabled`, enqueues rows with `mgdl > 0`, then schedules a single-threaded flush on a `ScheduledExecutorService` named `LumiflexCloudSend`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudUploaderTests"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/share/lumiflex Common/src/test/java/tk/glucodata/share/lumiflex
git commit -m "feat(lumiflex): uploader with batching, retry and 401 handling"
```

---

### Task 6: Follower manager

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/lumiflex/LumiflexCloudFollowerManager.kt`
- Test: `Common/src/test/java/tk/glucodata/share/lumiflex/LumiflexCloudFollowerManagerTests.kt`

**Interfaces:**
- Consumes: `LumiflexCloudClient`, `LumiflexCloudSettings`, `FollowerInfo`.
- Produces: `class LumiflexCloudFollowerManager(client, settings)` with `list(): List<FollowerInfo>`, `invite(account: String, alias: String): Boolean`, `setDisplayHidden(id: String, hidden: Boolean): Boolean`, `setAlerts(id: String, normal: Boolean, urgent: Boolean, extremeHigh: Boolean): Boolean`, `rename(id: String, alias: String): Boolean`, `remove(id: String): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudFollowerManagerTests {

    private class FakeTransport : LumiflexCloudClient.Transport {
        val bodies = mutableListOf<JSONObject>()
        val paths = mutableListOf<String>()
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            paths.add(url.substringAfter("/api-x"))
            bodies.add(JSONObject(LumiflexCloudCrypto.decryptEnvelope(body)!!))
            return LumiflexCloudClient.HttpResult(200, LumiflexCloudCrypto.encryptEnvelope("""{"code":200,"data":{}}"""))
        }
        override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult =
            LumiflexCloudClient.HttpResult(200, LumiflexCloudCrypto.encryptEnvelope("""{"code":200,"data":[]}"""))
    }

    @Test
    fun inviteSendsVendorFields() {
        val transport = FakeTransport()
        val manager = LumiflexCloudFollowerManager(
            LumiflexCloudClient(transport),
            LumiflexCloudSettings(RuntimeEnvironment.getApplication()).apply { token = "tok" },
        )
        assertTrue(manager.invite("f@b.c", "Ann"))
        assertEquals("/userAuthorization/saveOrUpdateUserAuthorization", transport.paths.first())
        val body = transport.bodies.first()
        assertEquals("f@b.c", body.getString("readerUserName"))
        assertEquals("Ann", body.getString("readerAlias"))
        assertEquals(1, body.getInt("normalPushState"))
        assertEquals(1, body.getInt("emergePushState"))
    }

    @Test
    fun alertsAndDisplayFlagsMapToVendorNames() {
        val transport = FakeTransport()
        val manager = LumiflexCloudFollowerManager(
            LumiflexCloudClient(transport),
            LumiflexCloudSettings(RuntimeEnvironment.getApplication()).apply { token = "tok" },
        )
        manager.setDisplayHidden("a1", true)
        manager.setAlerts("a1", normal = false, urgent = true, extremeHigh = true)
        assertEquals(1, transport.bodies[0].getInt("hideState"))
        assertEquals(0, transport.bodies[1].getInt("normalPushState"))
        assertEquals(1, transport.bodies[1].getInt("emergePushState"))
        assertEquals(1, transport.bodies[1].getInt("extremeHighPushState"))
        assertEquals("a1", transport.bodies[1].getString("userAuthorizationId"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudFollowerManagerTests"`
Expected: FAIL — unresolved manager.

- [ ] **Step 3: Implement the manager**

Invite sends `readerUserName`, `readerAlias`, `hideState: 0`, `normalPushState: 1`, `emergePushState: 1`. `setDisplayHidden` sends `updateAuthorizationInfo` with only `userAuthorizationId` + `hideState`. `setAlerts` sends the three flags. `rename` sends `readerAlias`. `remove` posts `{"ids":[id]}` to `deleteByIds`. All return `false` on a blank token or non-200.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.LumiflexCloudFollowerManagerTests"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/share/lumiflex Common/src/test/java/tk/glucodata/share/lumiflex
git commit -m "feat(lumiflex): follower manager"
```

---

### Task 7: Wire the uploader into history storage

**Files:**
- Modify: `Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt` (imports; `forwardBackfillToExchanges` around line 735; add `lumiflexRows` near `icnRows` around line 765)

**Interfaces:**
- Consumes: `LumiflexCloudSettings.isEnabled`, `LumiflexCloudUploader.onHistoryStored`, `LumiflexCloudUploader.Row`.
- Produces: no new public API.

- [ ] **Step 1: Add the hook**

Inside `forwardBackfillToExchanges`, after the iCan block, add:

```kotlin
if (app != null && LumiflexCloudSettings.isEnabled(app)) {
    LumiflexCloudUploader.onHistoryStored(
        serial,
        lumiflexRows(serial, backfillRows, XdripBackfill.displayedRows(serial, backfillRows)),
    )
}
```

and the builder next to `icnRows`:

```kotlin
/** The rows the Lumiflex cloud should hear about. */
private fun lumiflexRows(
    serial: String,
    rows: List<XdripBackfill.Row>,
    displayed: List<XdripBackfill.DisplayedRow>,
): List<LumiflexCloudUploader.Row> {
    val rateByTime = rows.associate { it.timeMs to it.rate }
    return displayed.map { r ->
        LumiflexCloudUploader.Row(serial, r.timeMs, r.mgdl, (rateByTime[r.timeMs] ?: 0.0).toInt())
    }
}
```

- [ ] **Step 2: Compile and run the existing history tests**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.*" --tests "tk.glucodata.data.*"`
Expected: PASS (existing tests unchanged; new hook compiles).

- [ ] **Step 3: Commit**

```bash
git add Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt
git commit -m "feat(lumiflex): feed stored readings into the Lumiflex uploader"
```

---

### Task 8: Settings and followers UI

**Files:**
- Create: `Common/src/mobile/java/tk/glucodata/ui/LumiflexCloudSettingsScreen.kt`
- Create: `Common/src/mobile/java/tk/glucodata/ui/LumiflexCloudFollowersScreen.kt`
- Modify: `Common/src/mobile/java/tk/glucodata/ui/MainNavigation.kt` (routes near line 1005)
- Modify: `Common/src/mobile/java/tk/glucodata/ui/ExpressiveSettingsScreen.kt` (entry card + `hasActiveAiDexSensor`, near lines 128 and 1647)

**Interfaces:**
- Consumes: `LumiflexCloudSettings`, `LumiflexCloudClient`, `LumiflexCloudFollowerManager`.
- Produces: `@Composable fun LumiflexCloudSettingsScreen(navController: NavController)`, `@Composable fun LumiflexCloudFollowersScreen(navController: NavController)`.

- [ ] **Step 1: Clone the iCan screens**

`LumiflexCloudSettingsScreen`: same structure as `ICanHealthCloudSettingsScreen` — `MasterSwitchCard` ("Lumiflex cloud" / "Upload glucose to Lumiflex"), account + password fields, "Sign in" button calling `client.login(account, password, settings)` in `LaunchedEffect`/coroutine, status text from `settings.lastSyncMs`, button to `navController.navigate("settings/lumiflex/followers")` when `settings.token` is non-blank, and an "Sign out" action clearing the token.

`LumiflexCloudFollowersScreen`: same structure as `ICanHealthCloudFollowersScreen` but with `LumiflexCloudFollowerManager`: list of `FollowerInfo` (alias/account), "Add follower" dialog (account + alias), per-row menu with rename, "Switch display" toggle, three alert switches, delete.

- [ ] **Step 2: Register routes and the settings entry**

`MainNavigation.kt`:
```kotlin
composable("settings/lumiflex") { LumiflexCloudSettingsScreen(navController) }
composable("settings/lumiflex/followers") { LumiflexCloudFollowersScreen(navController) }
```

`ExpressiveSettingsScreen.kt`: add
```kotlin
val showLumiflex = remember {
    runCatching { LumiflexCloudSettings.isSetUp(context) }.getOrDefault(false) ||
        hasActiveAiDexSensor()
}
```
with
```kotlin
private fun hasActiveAiDexSensor(): Boolean = runCatching {
    Natives.activeSensors()?.filterNotNull().orEmpty().any { sensorId ->
        SensorSourceResolver.resolveSensorKind(sensorId, SensorSourceResolver.SENSOR_KIND_UNKNOWN) ==
            SensorSourceResolver.SENSOR_KIND_AIDEX
    }
}.getOrDefault(false)
```
and a `SettingsRow`/card that navigates to `settings/lumiflex`, guarded by `showLumiflex`, following the iCan entry's component.

- [ ] **Step 3: Compile**

Run: `./gradlew :Common:compileMobileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add Common/src/mobile/java/tk/glucodata/ui
git commit -m "feat(lumiflex): settings and followers screens"
```

---

### Task 9: User documentation and final verification

**Files:**
- Create: `docs/lumiflex-cloud.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Write `docs/lumiflex-cloud.md`**

Short page in the style of the other feature docs: what it does, how to enable (Settings → Lumiflex cloud), what is uploaded (brief CGM records only), what a follower sees, sign-out behaviour, and the known limitations copied from the spec's Open Items (registration best-effort, batch size, no raw history, no live trend push in v1).

- [ ] **Step 2: Run the whole unit suite for the module**

Run: `./gradlew :Common:testMobileDebugUnitTest`
Expected: PASS, no new failures.

- [ ] **Step 3: Run the lint/assemble gate the repo uses**

Run: `./gradlew :Common:assembleMobileDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add docs/lumiflex-cloud.md
git commit -m "docs(lumiflex): document the cloud channel"
```

---

## Verification for the reviewer

- `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.share.lumiflex.*"` — all new tests green.
- `./gradlew :Common:testMobileDebugUnitTest` — no regressions.
- `./gradlew :Common:assembleMobileDebug` — UI compiles.
- Grep the diff for `private_key`/token logging: no `Log.*` call may include a payload body, token, or glucose value.
- Manual (needs a real account): sign in, confirm `lastSyncMs` advances, verify a reading in the Lumiflex portal, invite a follower.
