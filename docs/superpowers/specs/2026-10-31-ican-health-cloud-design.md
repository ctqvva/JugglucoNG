# Design: iCan Health cloud support in JNG (master/follower channel)

Date: 2026-10-31. Status: draft for builder review.

## 1. Intent and why

JNG (JugglucoNG) already acts as a master for LibreView and Dexcom Share: it logs into the vendor cloud with the patient's account and uploads glucose, and it supports inviting/approving a follower. We add the same capability for **SinoCare "iCan Health"** (RU tenant `rus.icancgm.com`), reusing the established `DexcomShare` module pattern so the two cloud channels stay structurally identical.

Scope (user-approved, stage 1): JNG as the **patient app** — (a) login to an iCan account, (b) glucose upload to the vendor cloud, (c) invite + approve a follower. The follower-side app ("iCan Reach") is deferred to a later stage.

Sensor mapping: i3 ↔ iCGM-t3, i6 ↔ iCGM-t6 (same hardware family; the split is tenant/program, not hardware).

## 2. Goals

- G1. Patient can log in to a SinoCare iCan Health (RU) account from JNG and stay logged in across restarts.
- G2. JNG uploads glucose (and the supporting records the vendor expects) to `rus.icancgm.com` on the same cadence as the official app.
- G3. Patient can invite a follower (by email/phone) and approve/manage that follower (nickname, delete, share toggles) — the "iCan ACCESS" flow.
- G4. All network traffic is indistinguishable in shape from the official RU app (headers, URL, body envelope) so the server accepts it.
- G5. Module mirrors `DexcomShare` (Client / Queue / Uploader / Settings / Models / Errors) so maintenance cost stays flat.

## 3. Non-goals (this stage)

- NG1. Follower-side app ("iCan Reach") — deferred.
- NG2. CE tenant (`sg.icancgm.com`) — secondary; constants kept but not wired.
- NG3. Native CGM SDK integration (libdatajar / shdevicekit) — JNG keeps using its own BLE stack and uploads via the HTTP contract recovered below. We do not ship the vendor's native SDK.
- NG4. Calibration tables / `correctCode` (already handled by `ICanHealth*` driver) — unchanged.
- NG5. Push-based approve (vendor has none; ACCESS uses invite code).

## 4. Anti-goals

- AG1. Do not store the RSA private key or any vendor secret in the repo. The RSA public key is a build-time constant (from the official app's MMKV, see §6.4); it is public material.
- AG2. Do not log tokens, passwords, or glucose values in production logs.
- AG3. Do not silently refresh tokens — on 401, delete token + force re-login (matches vendor: no silent refresh).

## 5. Constraints

- C1. Language: Kotlin (Common module), matching `DexcomShare` and `drivers/icanhealth`.
- C2. Build: Gradle, no new heavy dependencies beyond what `Common` already has (OkHttp/Retrofit or the existing HTTP client used by DexcomShare). Reuse the existing crypto primitives where possible; RSA-OAEP-SHA1 + AES-256-CBC need a crypto lib already on the classpath (check `Common/build.gradle` before adding).
- C3. Testing: unit tests for the client (mocked HTTP) + at least one integration test against a recorded fixture, mirroring DexcomShare tests.
- C4. Tenant config is a compile-time constant for RU (`https://rus.icancgm.com`); host is not user-editable in v1.
- C5. Sensor identity: i3 → productCode `100295`, i6 → `100354`/`100355`/`100373`/`100374`. Upload payload must carry `apparatusSn` (the device serial) and the detection window.

## 6. Transport contract (recovered from RU app v01.16.01.02)

### 6.1 URL scheme
- Base: `https://rus.icancgm.com`
- App host first path: `/ican/api/`
- Full URL: `${base}${appHostFirstPath}${path}` (e.g. `https://rus.icancgm.com/ican/api/sino-auth/oauth/token`).
- Dispatcher endpoints (center-user/get, language/list, country/list, phone-code/list) use the same base+path; no separate host.

### 6.2 Headers (every request)
| Header | Value |
|---|---|
| `Sino-Auth` | access token (if logged in) |
| `platformType` | `"1"` |
| `langCode` | `"ru"` |
| `country` | `"RUS"` |
| `versionCode` | `"30"` |
| `versionName` | `"01.16.01.02"` |
| `clientId` | `"ican-health-android"` |
| `authorization` | `"Basic aWNhbi1oZWFsdGgtYW5kcm9pZDplOTFhOGM5YzViNGY0MGZhYTExMjYzZTdiYjdjYTBhNw=="` |
| `timeZone` | offset hours, e.g. `"3"` |
| `client-id` (native SDK only) | `"sino-ican-android"` |
| `client-version` (native SDK only) | `"01.16.01.02"` |

Note: the Dart-side and the native CGM-SDK side use slightly different header spellings (`clientId` vs `client-id`). JGN uses the Dart-side set for the endpoints it calls directly; the native-only headers apply only to CGM-SDK endpoints JGN does not call.

### 6.3 Auth (plaintext, `isEncode = false`)
- `POST sino-auth/oauth/token`, headers `{"authorization": <Basic>}`.
- Body (form-urlencoded): `grant_type`, `scope`, `type`, `thirdAuth`, `country`, `ids`, `tenantId`, `phone_num`, `password`, `thirdAuthKey`.
  - Password login: `grant_type=password`, `phone_num=<email or phone>`, `password=<pwd>`, `scope=all`, `country=RUS`, others empty.
  - `grant_type=verify_code` for SMS/email code login.
  - `grant_type=refresh_token` exists but the server currently rejects expired sessions ("Your login session has expired. Please log in again.") — treat as re-login required.
- Response: `{code, success, msg, data: {access_token, token_type, refresh_token, expires_in, scope, tenant_id, tenant_ids, jti, country, ...userinfo}}`. On code 0, persist `access_token` (and refresh_token) to secure storage.
- Error code 30 → show "activate account" dialog.
- HTTP 401 → delete token + force re-login (no silent refresh).

### 6.4 Crypto (encrypted endpoints, `isEncode = true` by default)
Request envelope (JSON body):
```
{ "data": <base64url(AES-256-CBC(jsonEncode(payload), key, IV))>,
  "key":  <base64url(RSA-OAEP-SHA1(key))> }
```
- `key` = `getRandomString(32)` from alphabet `ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678`.
- AES key = UTF-8 of `key` (32 bytes → AES-256). IV = first 16 bytes of the key (AES block size). **Open item: confirm at runtime; the disassembly requires a 16-byte IV but the source derivation is ambiguous (see §10).**
- Padding: PKCS#7.
- RSA: RSA-OAEP-SHA1 with the public key below.
- RSA public key (from official app MMKV, build-time constant):
```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAimdiS7wHMpmFCw0j6Gc07TRchln/pp4m8kAeDuI8HvRdDB0ul/ic8fJue7Ow8YTqCEPt2IKZZtTfBGsfQgQ/pFdlQrG31lgErRBVitOZxpbxB6t2O2pH0WguI2sCYOKoOefPLjiTovMrbWQatZrTd3MeNELD7zY1WY8NLkQ5Ur+Je8q6XO+cGS1lbpfxCQvdLobhewy+9aRXsLb3Bcs0FQ4RdWweWRt0gj9akTvVlaTjgGq5/pTE3crfQXFxi8L9NPu8LKguYtLDuE/ffYSduWdowPIgJk/8w7RxAiwKARFGNSDpVCqsrFO9poQ56ZJqOxHg1gLGnJCcdj2uvbQp8QIDAQAB
-----END PUBLIC KEY-----
```
- Response: encrypted blob (base64) decrypted with `aesDecrypt` using the key delivered by the server in the response (`aesKey` field) — or, if absent, the request key. IV follows the same 16-byte rule.

### 6.5 Endpoint inventory (CGM + iCan, from memory dump)

Glucose / device data:
- `POST sino-cgm/v2/user-apparatus/save` — the primary glucose upload endpoint (native SDK).
- `POST sino-cgm/v1/user-apparatus/update` — detection window update (`detectionStartTime`, `detectionEndTime`, `timezone`, `apparatusSn`).
- `POST sino-cgm/v1/original/data/save` — glucose history upload.
- `POST sino-cgm/v1/original/bind`, `original/code`, `original/default/parameter`.
- `POST sino-health/v1/detection-data/add`, `/batch-add`, `/page`, `/list-serial-number`, `/latest-one`.
- `GET  sino-cgm/v1/user-apparatus/page`, `/detail`, `/bind/info`.
- `POST sino-health/v1/user-device/*` (bind, page, detail, update, batch-remove, batch-unbind, check-device-bind, update-connection-time).

Follower (iCan ACCESS):
- `GET  sino-ican/v1/follower/list`
- `POST sino-ican/v1/follower/save` (keys: `email`, `phone`, `phoneAreaCode`, `name`, `id`, `invitedTime`)
- `POST sino-ican/v1/follower/get-follower` (`id`)
- `POST sino-ican/v1/follower/update` (`status`, nickname)
- `GET  sino-ican/v1/follower/has-follower`
- `GET  sino-ican/v1/basic/config/detail`
- `POST sino-ican/v1/basic/config/follower/update` (`shareFollower`)
- Also mirrored under `sino-cgm/v1/follower/*`.

User / account:
- `POST sino-cgm/v1/user/register`, `/login`-equivalent (via sino-auth), `/check-pwd`, `/update`, `/get-user-info`, `/get-user-without-login`, `/active`, `/logout`, `/logoff-new`, `/rebind/email`, `/rebind/email/verify-code`, `/reset-pwd`, `/validate-code`, `/sms/send`.
- `GET  sino-cgm/v1/country/setting/detail` (and `/device`), `/country/launched-list`, `/language/launched-list`.
- `GET  sino-cgm/v1/app-version/upgrade/info`.

## 7. Follower model (iCan ACCESS)

`FollowerFriendsModel` fields (JSON keys): `id`, `userId`, `followerId`, `sharerId`, `sharerName`, `phone`, `email`, `phoneAreaCode`, `countryCode`, `followerStatus`, `followerStatusName`, `invited`, `invitedTime`, `createTime`, `updateTime`, `deleteTime`, `status`, `isDeleted`, `unfollow`, `viewPermission`, `shareFollower`, `shareHcp`, `dateType`, `timeFormat`, `unit`, `highGlucose`, `lowGlucose`, `highMessage`, `lowMessage`, `highSounds`, `lowSounds`, `hiVibration`, `loVibration`, `vibrateOnly`, `sysSound`, `deviceDisconnectSound`, `notDisturb`, `overrideDisturb`, `silenceAll`, `silenceAllBeginTime`, `silenceAllEndTime`, `urgentReminder`, `createAccount`, `createOrg`, `centerCode`, `centre`, `techSupport`, `cloudSync`, `appPermissionSwitch`, `analyzeDataChn`, `analyzeDataEur`, `improveProdEur`.

Invite flow:
1. Patient opens "share data" → `GET basic/config/detail` ( thresholds ) + `GET follower/list`.
2. Patient enters follower email/phone/name → `POST follower/save` with `{email, phone, phoneAreaCode, name, id, invitedTime}`.
3. Server sends invite code via SMS/email; follower enters it in iCan Reach (separate app — out of scope).
4. Patient manages follower: `POST follower/update` (delete via `status`, rename via nickname), `POST basic/config/follower/update` (`shareFollower`), `POST follower/batch-update`.

No push-approve. Status values are integers (exact enum TBD at runtime; store as-is).

## 8. Module layout (clone DexcomShare)

New package: `tk.glucodata.share.ican` (or extend `drivers/icanhealth` with a `cloud` subpackage — decision: separate `share/icanhealth/` to mirror `share/DexcomShare*`).

Files (mirroring DexcomShare):
- `ICanHealthCloudClient.kt` — HTTP client: base URL, headers, login, token storage, encrypted envelope (AES+RSA), response decrypt, 401 handling.
- `ICanHealthCloudModels.kt` — data classes: `LoginRequest`, `LoginModel`, `FollowerFriendsModel`, `AppConfig`, upload payloads (`UserApparatusUpdate`, `OriginalDataSave`, `DetectionDataBatch`).
- `ICanHealthCloudError.kt` — typed errors (Unauthorized, ActivateAccount(30), Network, Decrypt).
- `ICanHealthCloudQueue.kt` — persistent queue of pending uploads (reuse DexcomShareQueue storage approach).
- `ICanHealthCloudUploader.kt` — drains queue, calls client, handles retry/backoff.
- `ICanHealthCloudSettings.kt` — account, token, tenant, last-sync cursor (DataStore/SharedPreferences, matching DexcomShareSettings).
- `ICanHealthCloudBackfill.kt` — historical glucose backfill on first login (mirror DexcomShareBackfillTrend).

Reuse `ICanHealthCloudCode.kt` (existing driver crypto) only if compatible; otherwise implement crypto in the client using a crypto lib. Prefer reusing the existing `ICanHealthCrypto.kt` if it already does AES/RSA — verify during planning.

## 9. Data flow

1. User opens "iCan Health cloud" settings → enters email+password → `ICanHealthCloudClient.login()` → form-urlencoded POST → store token.
2. On login success: fetch `basic/config/detail` (thresholds) + `follower/list`; backfill last N days via `original/data/save` (or `detection-data/batch-add`).
3. Glucose pipeline: JNG's glucose stream → enqueue record → `ICanHealthCloudUploader` drains → encrypted POST to `original/data/save` (or `user-apparatus/update` for detection windows) → on 200 remove from queue; on 401 → clear token + notify; on 5xx → backoff retry.
4. Follower management UI calls client methods directly (list/save/update/basic config).

## 10. Open items (left to builder / runtime)

- OI1. **IV derivation**: disassembly proves CBC IV must be 16 bytes, and the key is 32 bytes. Candidate: first 16 bytes of the 32-char key. Runtime validation pending (needs a fresh session + decrypt of a known response). The builder should make IV configurable behind a single helper so a one-line change fixes it.
- OI2. **Response key delivery**: determine whether `aesKey` is echoed in the response body or delivered another way; the captured `check-pwd` response was a bare 128-byte blob with no envelope. May need to capture a successful encrypted request/response pair to confirm.
- OI3. **Glucose upload payload schema**: exact JSON fields for `original/data/save` and `detection-data/batch-add` (glucose value, timestamp, trend, sensor serial, detection window). Recover from memory dump of a real upload or from the CGM SDK's DTO classes (in `libdatajar.so` dex). The endpoint list is known; payload shapes are not.
- OI4. **Heartbeat**: `sino-intl/api/v1/device/heartbeat` and `sino-health/v1/user-device/update-connection-time` — cadence and payload. Likely needed for "device online" status.
- OI5. **Status code enum for follower** (`status`, `followerStatus`) — store as int; map to UI labels later.
- OI6. **CE tenant** constants only; no wiring.
- OI7. **Crypto library availability**: confirm `Common` already has an RSA/AES implementation (check `ICanHealthCrypto.kt` and `build.gradle`); avoid adding a new dependency if possible.

## 11. Testing plan

- Unit: `ICanHealthCloudClient` login (mocked HTTP) — success, code 30, 401, network error.
- Unit: encrypted envelope round-trip (encrypt then decrypt with the same key) — guards IV/padding regressions.
- Unit: queue drain + retry/backoff logic (mirror DexcomShareQueue tests).
- Fixture: record one real login + one real encrypted request/response pair (redacted) for regression.
- Manual: end-to-end with a test account on a device, verifying glucose appears in the vendor web portal.

## 12. Risks

- R1. IV wrong → server rejects all encrypted calls. Mitigation: OI1, single helper.
- R2. Upload payload mismatch → 400s. Mitigation: capture real upload from official app (memory dump of a live upload) before shipping.
- R3. Token expiry / session revocation → 401 storm. Mitigation: 401 → clear token + notify; no auto-retry of login.
- R4. Account lockout from probing. Mitigation: use a dedicated test account; never probe with the user's primary account in CI.

## 13. References

- `docs/ican/ICAN-RU-DART-RECON-2026-10-31.md` — full Dart-layer contract.
- `Common/src/main/java/tk/glucodata/share/DexcomShare*` — module to clone.
- `Common/src/main/java/tk/glucodata/drivers/icanhealth/` — existing iCan driver (BLE, crypto, calibration).
- `/Users/jetcat/Downloads/ican-ce-handoff-*.md` — CE probing history (Frida blocked on MIUI; memory-dump method).
- Memory dump: `/data/local/tmp/ru-dump/` on Redmi rosemary (pid 12150/12171) — endpoint inventory source.
EOF