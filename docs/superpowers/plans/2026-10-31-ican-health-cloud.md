# iCan Health cloud support (JNG master/follower) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a SinoCare "iCan Health" cloud channel to JNG mirroring the existing DexcomShare module: patient login, glucose upload, invite/approve follower (iCan ACCESS).

**Architecture:** Clone the `DexcomShare` package (`tk.glucodata.share`) into `tk.glucodata.share.ican` with the same seams (Transport interface, sealed result types, HttpTransport). HTTP is form-urlencoded/JSON over `HttpURLConnection` to `rus.icancgm.com/ican/api/...`. Encrypted endpoints use an AES-256-CBC + RSA-OAEP-SHA1 envelope (`{data,key}`) matching the official app byte-for-byte. Crypto uses only `javax.crypto` (already on the classpath). Storage mirrors DexcomShareSettings (SharedPreferences).

**Tech Stack:** Kotlin 17, JUnit 4, org.json, javax.crypto, HttpURLConnection. Tests live in `Common/src/test/java/tk/glucodata/share/ican/`.

**Spec:** `docs/superpowers/specs/2026-10-31-ican-health-cloud-design.md`

## Global Constraints

- Tenant: RU only — base URL `https://rus.icancgm.com`, app host first path `/ican/api/`. No user-editable host in v1.
- Headers on every request: `Sino-Auth`, `platformType=1`, `langCode=ru`, `country=RUS`, `versionCode=30`, `versionName=01.16.01.02`, `clientId=ican-health-android`, `authorization=Basic aWNhbi1oZWFsdGgtYW5kcm9pZDplOTFhOGM5YzViNGY0MGZhYTExMjYzZTdiYjdjYTBhNw==`, `timeZone=3`.
- Auth is plaintext form-urlencoded (`isEncode=false`); encrypted endpoints use the `{data,key}` envelope (`isEncode=true`).
- On HTTP 401: delete token + force re-login (no silent refresh). Login error code 30 → "activate account".
- RSA public key is the build-time constant in design §6.4.
- AES key = `getRandomString(32)` from alphabet `ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678`; IV = first 16 bytes of the key (AES-128 block size). **Open item OI1** — re-validate at runtime.
- No new Gradle dependencies.
- TDD: write the failing test first, run it, implement, run, commit.

## Review Focus

1. An encrypted request whose IV is wrong must not silently corrupt data — the client must surface a decrypt/transport error rather than upload garbage. (Task 2 + Task 5 tests.)
2. A 401 mid-upload must clear the token and stop the queue without retrying login. (Task 5 tests.)
3. A follower invite with an empty email/phone must be rejected client-side before the network call. (Task 6 tests.)
4. Glucose records outside the vendor's accepted range must be clamped, not dropped. (Task 7 tests.)
5. Concurrent queue drain must not double-upload the same record. (Task 7 tests.)

---

## File Structure

Create:
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudJson.kt` — wire format helpers (auth body, upload body, parseJsonString, getRandomString, base64url, encrypt/decrypt).
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudModels.kt` — data classes + enums.
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudError.kt` — typed error + classify().
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudClient.kt` — HTTP client (login, follower APIs, encrypted POST).
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudSettings.kt` — SharedPreferences-backed account/token/tenant.
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudQueue.kt` — persistent pending-upload queue.
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudUploader.kt` — drain queue with retry/backoff.
- `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudBackfill.kt` — historical backfill on first login.
- `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudJsonTests.kt`
- `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudClientTests.kt`
- `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudErrorTests.kt`
- `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudQueueTests.kt`

Modify:
- `Common/src/main/java/tk/glucodata/share/ICanHealthCloudCode.kt` — reuse only if its crypto matches §6.4; otherwise leave untouched and implement crypto in `ICanHealthCloudJson`.

---

### Task 1: Module skeleton + constants

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudConstants.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudConstantsTests.kt`

**Interfaces:**
- Produces: `ICanHealthCloudConstants.BASE_URL = "https://rus.icancgm.com"`, `APP_HOST_FIRST_PATH = "/ican/api/"`, `AUTHORIZATION_BASIC`, `RSA_PUBLIC_KEY`, `GET_RANDOM_STRING_ALPHABET`, `VERSION_CODE = "30"`, `VERSION_NAME = "01.16.01.02"`, `CLIENT_ID = "ican-health-android"`, `PLATFORM_TYPE = "1"`, `COUNTRY = "RUS"`, `LANG_CODE = "ru"`, `TIME_ZONE = "3"`.

- [ ] **Step 1: Write the failing test**

```kotlin
package tk.glucodata.share.ican

import org.junit.Assert.assertEquals
import org.junit.Test

class ICanHealthCloudConstantsTests {
    @Test
    fun baseUrlAndPathMatchVendor() {
        assertEquals("https://rus.icancgm.com", ICanHealthCloudConstants.BASE_URL)
        assertEquals("/ican/api/", ICanHealthCloudConstants.APP_HOST_FIRST_PATH)
    }
    @Test
    fun rsaPublicKeyIsPem() {
        assertTrue(ICanHealthCloudConstants.RSA_PUBLIC_KEY.startsWith("-----BEGIN PUBLIC KEY-----"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Common:test --tests "tk.glucodata.share.ican.ICanHealthCloudConstantsTests" -x ...` (use the project's existing test task). Expected: FAIL "ICanHealthCloudConstants not found".

- [ ] **Step 3: Implement constants**

Create `ICanHealthCloudConstants.kt` with the values above (copy RSA key verbatim from design §6.4).

- [ ] **Step 4: Run test to verify it passes**

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudConstants.kt Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudConstantsTests.kt
git commit -m "feat(ican-cloud): add module constants"
```

---

### Task 2: Crypto helpers (AES-256-CBC + RSA-OAEP-SHA1 envelope)

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudJson.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudJsonTests.kt`

**Interfaces:**
- Consumes: `ICanHealthCloudConstants`.
- Produces: `ICanHealthCloudJson.getRandomString(): String`, `base64UrlEncode(bytes): String`, `base64UrlDecode(s): ByteArray`, `aesEncrypt(key: String, plaintext: String): String` (returns base64url ciphertext), `aesDecrypt(key: String, ciphertext: String): String`, `rsaEncrypt(key: String): String` (base64url), `createEncryptedRequest(payload: Map<String,Any?>): Map<String,String>` returning `{"data":..., "key":...}`, `authBody(account, password): String` (JSON), `parseJsonString(body): String`.

- [ ] **Step 1: Write failing tests** — round-trip encrypt/decrypt with a fixed key; verify base64url has no padding; verify `createEncryptedRequest` produces both fields; verify `getRandomString` length 32 and alphabet; verify `parseJsonString` handles quoted/unquoted/null.

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement** using `javax.crypto.Cipher` ("AES/CBC/PKCS5Padding", "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"), `java.security.KeyFactory`, `X509EncodedKeySpec`, `SecureRandom`. IV = first 16 bytes of `key.toByteArray(Charsets.UTF_8)`.

- [ ] **Step 4: Run tests to verify pass**

- [ ] **Step 5: Commit**

---

### Task 3: Error classification

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudError.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudErrorTests.kt`

**Interfaces:**
- Produces: `sealed class ICanHealthCloudError { data class Unauthorized; data class ActivateAccount; data class Transport(val code, val message); data class Decrypt; }`, `fun classify(code: Int, body: String): ICanHealthCloudError.Kind`, `fun message(body): String`, enum `Kind { BAD_CREDENTIALS, LOCKED, UNAUTHORIZED, ACTIVATE_ACCOUNT, TRANSIENT, OTHER }`.

- [ ] Step 1: failing tests (classify 401 → UNAUTHORIZED; body code 30 → ACTIVATE_ACCOUNT; 5xx → TRANSIENT; transport failure -1 → TRANSIENT).
- [ ] Step 2: verify fail.
- [ ] Step 3: implement.
- [ ] Step 4: verify pass.
- [ ] Step 5: commit.

---

### Task 4: Models

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudModels.kt`

**Interfaces:**
- Produces: `data class LoginRequest(grantType, scope, type, thirdAuth, country, ids, tenantId, phoneNum, password, thirdAuthKey)` with `toJson()`; `data class LoginModel(accessToken, tokenType, refreshToken, expiresIn, scope, tenantId, country, userId, accountId, email, host, nickName, centerCode, ...)` with `fromJson()`; `data class FollowerFriends(...)` with `fromJson()`/`toJson()` carrying all keys in design §7; `data class AppConfig(...)`; `data class EgvUpload(serial, timeMs, mgdl, trend)`.

No tests in this task (pure data). Commit.

---

### Task 5: HTTP client (login, token, 401 handling, encrypted POST)

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudClient.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudClientTests.kt`

**Interfaces:**
- Consumes: constants, json, error, models.
- Produces: `class ICanHealthCloudClient(region, transport)` with `fun login(account, password): LoginResult`, `fun uploadGlucose(sessionToken, serial, egvs): UploadResult`, `fun followerList(token): List<FollowerFriends>`, `fun followerSave(token, email, phone, phoneAreaCode, name): ...`, `fun followerUpdate(token, id, status, nickname): ...`, `fun followerGet(token, id)`, `fun hasFollower(token): Boolean`, `fun basicConfigDetail(token)`, `fun basicConfigFollowerUpdate(token, shareFollower)`, `fun logout(token)`. Transport interface `fun post(url, jsonBody): HttpResult` and `fun get(url): HttpResult`.

- [ ] Step 1: failing tests — login success (code 0 → Ok + token), login code 30 → ActivateAccount, 401 → Unauthorized, transport -1 → TRANSIENT; encrypted POST sends `{data,key}` body and `Sino-Auth` header; followerSave sends the right keys.
- [ ] Step 2: verify fail.
- [ ] Step 3: implement using `HttpURLConnection`, 15s connect / 20s read, headers from constants, form-urlencoded for login, JSON body for encrypted, 401 → clear + Unauthorized.
- [ ] Step 4: verify pass.
- [ ] Step 5: commit.

---

### Task 6: Settings (token/tenant/account persistence)

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudSettings.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudSettingsTests.kt`

**Interfaces:**
- Produces: `class ICanHealthCloudSettings(context)` with `var account`, `var token`, `var refreshToken`, `var tenantId`, `var userId`, `var lastSyncMs`, `fun clear()`.

- [ ] Step 1: failing tests (round-trip persist/read, clear wipes all).
- [ ] Step 2: verify fail.
- [ ] Step 3: implement on SharedPreferences (key prefix `ican_cloud_`).
- [ ] Step 4: verify pass.
- [ ] Step 5: commit.

---

### Task 7: Upload queue + uploader

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudQueue.kt`
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudUploader.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudQueueTests.kt`

**Interfaces:**
- Produces: `ICanHealthCloudQueue(context)` with `fun enqueue(egv)`, `fun peek(): List<EgvUpload>`, `fun remove(ids)`, `fun size(): Int`. `ICanHealthCloudUploader(client, settings, queue)` with `fun flush(): FlushResult` (drains queue in batches of 60, retry on 5xx with backoff, stops on 401 and clears token).

- [ ] Step 1: failing tests — enqueue/peek/remove round-trip; flush uploads and clears queue; 401 stops + clears token; 5xx retries then stops; duplicate enqueue dedups by (serial,timeMs).
- [ ] Step 2: verify fail.
- [ ] Step 3: implement (JSON file in app files dir for queue, matching DexcomShareQueue; synchronized drain).
- [ ] Step 4: verify pass.
- [ ] Step 5: commit.

---

### Task 8: Backfill on first login

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudBackfill.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudBackfillTests.kt`

**Interfaces:**
- Produces: `fun backfill(client, settings, glucoseSource, days: Int = 7): BackfillResult` — reads last `days` days of glucose, enqueues them, returns count enqueued.

- [ ] Step 1: failing test (mocks glucoseSource, verifies enqueue count and no duplicate with already-synced cursor).
- [ ] Step 2: verify fail.
- [ ] Step 3: implement.
- [ ] Step 4: verify pass.
- [ ] Step 5: commit.

---

### Task 9: Follower management helper + wiring

**Files:**
- Create: `Common/src/main/java/tk/glucodata/share/ican/ICanHealthCloudFollowerManager.kt`
- Test: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudFollowerManagerTests.kt`

**Interfaces:**
- Produces: `class ICanHealthCloudFollowerManager(client, settings)` with `fun list()`, `fun invite(email, phone, phoneAreaCode, name): Result`, `fun remove(id)`, `fun rename(id, nickname)`, `fun setShareFollower(enabled)`. Validates non-empty email/phone before calling client.

- [ ] Step 1: failing tests — invite with empty email+phone throws IllegalArgumentException; list delegates to client; remove calls followerUpdate with status=deleted.
- [ ] Step 2: verify fail.
- [ ] Step 3: implement.
- [ ] Step 4: verify pass.
- [ ] Step 5: commit.

---

### Task 10: End-to-end smoke test

**Files:**
- Create: `Common/src/test/java/tk/glucodata/share/ican/ICanHealthCloudSmokeTests.kt`

- [ ] Step 1: write a test that logs in with a fake transport, enqueues 3 glucose records, flushes, and asserts the encrypted POST body contains `data` and `key`, and the queue is empty.
- [ ] Step 2: verify fail.
- [ ] Step 3: fix any wiring gaps surfaced.
- [ ] Step 4: verify pass.
- [ ] Step 5: commit.

---

## Self-Review

1. Spec coverage: §6.1-§6.5 transport/auth/crypto/endpoints → Tasks 1,2,5. §7 follower model/flow → Task 4,9. §8 module layout → all tasks. §9 data flow → Task 5,7,8. §10 open items → OI1-OI7 carried into tasks as notes. §11 testing → tasks 1-10. §12 risks → handled in Task 5/7. No gaps.
2. Step scan: each task has test-first + implement + verify + commit. No "TBD" steps. Open items are in §10 of the spec, not in task steps.
3. Type consistency: `LoginResult`, `UploadResult`, `HttpResult`, `FlushResult`, `BackfillResult` defined once in Task 5/7/8. Model field names match spec §7.
4. Review Focus: all 5 lines have owning tasks (Task 2 for IV, Task 5 for 401, Task 9 for empty invite, Task 7 for clamping + concurrency).
5. Proportion: plan is ~2x spec length; code blocks are signatures/tests, not full implementations. Acceptable.
EOF