# Design: Lumiflex cloud support in JNG (master + follower invite)

Date: 2026-11-01. Status: draft for builder review.
Recon: `docs/lumiflex-cloud-recon.md`, artifacts `~/Projects/Dia/lumiflex-recon/`.
Reference module: `Common/src/main/java/tk/glucodata/share/ican/` (same shape as `share/DexcomShare*`).

## 1. Intent and why

JNG already acts as a master for Dexcom Share, LibreView and SinoCare iCan Health: it logs into the
vendor cloud with the patient's account, uploads glucose, and manages a follower. Lumiflex
(`com.microtech.aidexx.lumi.mmoll`, Microtech/pancares family, same hardware family as AiDex X /
LinX which JNG already drives over BLE) is the next channel. We add the identical capability for
the `lumi` tenant so the patient can see JNG data in the official Lumiflex app / portal and can
invite a follower there.

Scope (stage 1, same as iCan): the **patient app** — (a) login to a Lumiflex account, (b) glucose
upload to the vendor cloud, (c) invite / rename / remove a follower and toggle sharing. The
follower-side consumer (JNG reading someone else's account via `/cgm-record/list-recent`) is
deferred.

## 2. Goals

- G1. Login with Lumiflex e-mail/phone + password, stay logged in across restarts.
- G2. Upload glucose and the supporting record fields the vendor expects, on the same cadence as
  the official app, with a persistent queue and backoff.
- G3. Invite a follower by account name, set a nickname, switch the follower's display
  on/off and the three alert flags, then rename or delete.
- G4. Traffic is shape-identical to the official app: `https://cloud.lumiflex.ru` + `/api-x`,
  vendor headers, RSA envelope, vendor error codes.
- G5. Module mirrors `share/ican` (Constants / Client / Models / Error / Queue / Uploader /
  Settings / FollowerManager) plus the two Compose screens, so maintenance cost stays flat.

## 3. Non-goals (this stage)

- NG1. Follower-side read path (`/cgm-record/list-recent`, `CloudHistorySync.downloadRecentData`).
- NG2. Native CGM SDK, OTA, AGP/pdf, events (food/insulin/…), wrist-watch login, panic/welfare.
- NG3. Raw (`rawUploadState`) history upload — brief records only; raw is a later add-on.
- NG4. Push (GeTui/Edna) and vendor alarms inside JNG.
- NG5. Other brands on the same backend (`linx`, `equilg`, `smart`, `pico`, `vista`, `mediq`):
  constants carry the brand string, only `lumi` is wired.

## 4. Anti-goals

- AG1. Never store the vendor RSA private key or any secret in the repo. The public key is public
  material and is a build-time constant; the private key is required by the protocol (the official
  client ships it) but must live in a single named constant with a comment, flagged in review.
- AG2. No tokens, passwords or glucose values in production logs.
- AG3. No silent token refresh: on 401 clear token and require re-login (vendor has no refresh).

## 5. Constraints

- C1. Kotlin, `Common` module, package `tk.glucodata.share.lumiflex`.
- C2. No new dependencies: `HttpURLConnection` + `org.json` like `share/ican`; crypto via
  `java.security`/`javax.crypto` (RSA/ECB/PKCS1Padding), already used in
  `drivers/aidex/AiDexCnCloudClient`.
- C3. Unit tests for client/envelope/queue/uploader/error/settings/follower manager, mirroring
  `Common/src/test/java/tk/glucodata/share/ican/*` (mock `Transport`).
- C4. Base URL and brand are compile-time constants (single tenant `lumi`); host not user-editable.
- C5. No behavioural change when the feature is disabled (`isEnabled == false` short-circuits in
  `HistoryRepository`, exactly like iCan).

## 6. Transport contract (recovered from official v2.4.0)

### 6.1 URL
- Base `https://cloud.lumiflex.ru`
- Prefix `/api-x` (from `UrlInterceptor`, brand `lumi`)
- Effective: `https://cloud.lumiflex.ru/api-x<path>`

### 6.2 Headers (every request)
| Header | Value |
|---|---|
| `version` | `ru` |
| `brand` | `lumi` |
| `app-info` | `com.microtech.aidexx.lumi.mmoll,2.4.0` |
| `encryption` | `enabled` |
| `x-token` | session token when logged in |
| `app-version-check` | RSA(`{"version":"2.4.0","timestamp":<ms>,"userId":<id>}`) |
| `Accept-Language` | current locale tag, e.g. `ru-RU` |
| `Content-Type` | `application/json;charset=UTF-8` |

### 6.3 Envelope
- POST body before sending: RSA/ECB/PKCS1Padding, chunked by `modulus/8 - 11`, base64 →
  `{"encryptData":"..."}`; the plain form is only used for endpoints the app marks
  non-encrypted (login is sent encrypted by the official client as well — verify on first run).
- Responses with `encryption: enabled` carry `{"encryptData":"..."}` and are decrypted with the
  vendor private key.
- 1024-bit key pair (same pair as the RU pump app): public
  `MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCifgLBAr2MhgW...` (full value in
  `docs/lumiflex-cloud-recon.md` §3), private `MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJc...`.

### 6.4 Auth
- `POST /api-x/user/loginByPassword`, body `{"userName":<e-mail|phone>,"password":<plain>}`.
- Response `data` = `{token, userId, email, phone}`.
- Persist `token` and `userId`; `x-token` on subsequent calls. Also used by
  `app-version-check` payload.
- `GET /api-x/user/getUserInfo` — profile/validation probe after login.

### 6.5 Endpoints used by this stage
| Purpose | Method + path |
|---|---|
| Login | `POST /user/loginByPassword` |
| Session probe / logout | `GET /user/getUserInfo`, `GET /user/logout` |
| Device registration (sensor) | `POST /cgmDevice/userDeviceRegister` |
| Glucose upload (brief) | `POST /cgmRecord/saveCgmRecord` |
| Follower list | `GET /userAuthorization/findUserAuthorizationList?type=share|follow` |
| Add follower | `POST /userAuthorization/saveOrUpdateUserAuthorization` |
| Modify follower | `POST /userAuthorization/updateAuthorizationInfo` |
| Follower by id | `GET /userAuthorization/findAuthorizationInfoById` |
| Delete follower | `POST /userAuthorization/deleteByIds` |

### 6.6 Payloads
`saveCgmRecord` body (one batch, outer key `records`):
```json
{"records":[{
  "frontRecordId":"<md5(sensorId + timeOffset)>",
  "userId":"<userId>",
  "sensorId":"<sensor serial>",
  "deviceSn":"<sensor serial>",
  "sensorIndex":0,
  "timeOffset":12345,
  "glucose":112,
  "glucoseIsValid":1,
  "status":0,
  "quality":0,
  "eventWarning":0,
  "deviceStatus":0,
  "appTime":"2026-11-01 12:34:56",
  "appTimeZone":"Europe/Moscow",
  "appCreateTime":"2026-11-01 12:34:58",
  "dstOffset":"10800",
  "rawOne":0.0,"rawTwo":0.0,"rawVc":0.0,"rawIsValid":1
}]}
```
Facts from the lumi build (not the sibling):
- `frontRecordId` = `updateRecordUUID()` = MD5(`sensorId` + `timeOffset`) — a deterministic
  idempotency key, not a UUID.
- `timeOffset` is **minutes since sensor start** (`ExtendsKt.toHistoryDate(offset, start)` =
  `start + offset*60000`).
- `dstOffset` is a **String** in `BaseEventEntity`, so it is sent as a string.
- `mainDeviceId`, `sensorStartTime`, `trendValue`, `cgmSn` do **not** exist on the lumi entity;
  they are sibling-only fields and are not sent.
- The upload uses Gson over the entity with a no-op exclusion strategy, so extra entity fields
  (`deviceId`, `rawData1..9`, `moment`, `briefUploadState`, …) are tolerated by the server; JNG
  sends the minimal proven set above.

Follower payloads:
- `saveOrUpdateUserAuthorization`: `{readerUserName, readerAlias, hideState, normalPushState, emergePushState}`
- `updateAuthorizationInfo`: `{userAuthorizationId, providerAlias, readerAlias, hideState, normalPushState, emergePushState, extremeHighPushState}`
- `deleteByIds`: `{ids:[...]}`
- `hideState` = the per-follower "Switch Display" toggle; `normalPushState` / `emergePushState` /
  `extremeHighPushState` are the "Regular Reminders" / "Urgent Low" / "Extreme High" alert flags
  from the vendor UI strings. All four are 0/1 integers. There is no account-level
  `shareFollower` switch in this app (that concept belongs to iCan only).
- Invite is direct: the vendor UI is "Add Follower" → follower account → optional nickname →
  done; no accept step is visible (`share_add_account`, `share_instant_share/follow`).
- Response entity `ShareUserInfo`: `userAuthorizationId, providerUserName, providerAlias,
  readerUserName, readerAlias, dataProviderId, dataReaderId, hideState, normalPushState,
  emergePushState, extremeHighPushState, isLooking, autoIncrementColumn, information,
  cgmDevice, userSetting, userTrend`.

### 6.7 Throttling (vendor behaviour to mirror)
- Base sync interval 30 s; on failure multipliers `{1,4,4,10,10,30,60,…}` with reset on success.
- Uploader model in JNG stays the usual: persistent queue, per-batch retry, exponential backoff
  (like `share/ican`), plus a 401 short-circuit.

## 7. Module layout

New package `Common/src/main/java/tk/glucodata/share/lumiflex/`:

- `LumiflexCloudConstants.kt` — base URL, `/api-x`, brand, headers, RSA keys, endpoint paths,
  version strings, batch size.
- `LumiflexCloudCrypto.kt` — `encryptEnvelope(json): String`, `decryptEnvelope(body): String?`,
  `signVersionCheck(userId): String`.
- `LumiflexCloudClient.kt` — `Transport` seam (like iCan), `login`, `uploadGlucose`,
  `followerList/invite/update/remove/findById`, `registerDevice`, error mapping.
- `LumiflexCloudModels.kt` — `LoginModel`, `FollowerInfo`, `CgmRecord` (+`toJson`), `ShareUserInfo`.
- `LumiflexCloudError.kt` — `Kind` (BAD_CREDENTIALS, UNAUTHORIZED, TRANSIENT, OTHER) from HTTP
  code + business `code` in the decrypted body.
- `LumiflexCloudQueue.kt` — persistent JSON queue of pending `CgmRecord` (same storage style).
- `LumiflexCloudUploader.kt` — `onHistoryStored(serial, rows)` entry point used by
  `HistoryRepository`, single-thread drain, batches, retry/backoff, 401 → clear token.
- `LumiflexCloudSettings.kt` — prefs: enabled, account, token, userId, lastSyncMs, `clear()`,
  `isEnabled`/`isSetUp` statics.
- `LumiflexCloudFollowerManager.kt` — thin wrapper over client (list/invite/rename/remove/toggle
  display + alert flags).

Mobile UI (`Common/src/mobile/java/tk/glucodata/ui/`):
- `LumiflexCloudSettingsScreen.kt` — enable switch, account+password, login/logout, status,
  link to followers.
- `LumiflexCloudFollowersScreen.kt` — list, invite dialog, rename, delete, `shareFollower` toggle.
- `MainNavigation.kt` routes `settings/lumiflex` and `settings/lumiflex/followers`.
- `ExpressiveSettingsScreen.kt` — entry card, conditional on `isSetUp`.

Wiring: `Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt` — next to the existing
iCan hook, gated by `LumiflexCloudSettings.isEnabled(app)`; rows built the same way
(`Row(serial, timeMs, mgdl, trend)`).

## 8. Data flow

1. Settings screen → `client.login(user, pass)` → store `token`/`userId`.
2. ~~On first upload for a sensor, `registerDevice(sensor)` (see OI1)~~ — dropped: the vendor requires
   `deviceMac`/`deviceKey`/`sensorStartUp` (see `docs/lumiflex-cloud-recon.md` §12), which JNG cannot
   supply; the sensor must be paired in the official app. A serial without a server-side `sensorId`
   stays queued.
3. `HistoryRepository` append → `LumiflexCloudUploader.onHistoryStored` → queue → drain:
   group by serial, batch (default 100), `saveCgmRecord`, remove on success, retry on 5xx/network,
   stop and clear token on 401.
4. Followers screen → manager → client (list/invite/update/delete).
5. Disable → uploader no-ops, queue keeps data up to a cap.

## 9. Open items (left to builder, verify on a live account)

- OI1. `userDeviceRegister` payload keys (the caller sits in a not-yet-dumped dex). Decision for
  stage 1: treat it as best-effort with `{deviceSn, sensorId, sensorIndex}` inside a single helper,
  and let record upload be the primary path. If the portal shows the sensor as unregistered, the
  helper becomes a hard precondition and the queue persists serial→server-id (my call, verify live).
- OI2. Whether login is sent encrypted (official client encrypts every POST). Mirror: encrypt POST,
  plain GET; keep `ENCRYPT_LOGIN` as a constant.
- OI3. `appTimeZone` format: use `TimeZone.getDefault().id`; if the portal shows wrong offsets,
  switch the single helper to `GMT+hh:mm` (my call).
- OI4. Batch size: default 100 records per POST (sibling downloads 2000/page); raise if the server
  accepts more, lower on 413/400.
- OI5. `saveOrUpdateUserTrend` (live value/arrow for the follower view): not in stage 1. If a manual
  follower test shows the live value missing, add a 5-minute trend push as stage 1b — the model and
  client seam already reserve the call.
- OI6. Follower limit: vendor caches `APP_CACHE_FOLLOWER_MAX_COUNT`; JNG will not enforce a cap in
  the UI and will surface the vendor error verbatim.

Decisions taken after builder review (my calls, not the partner's):
- D1. Delivery guarantee = the iCan pattern (in-process queue + event-triggered drain + short
  retry/backoff). No WorkManager, no 30 s vendor scheduler in stage 1 — matching "как у икан".
- D2. `app-version-check` is sent on every request including login, with `userId` = `""` (the
  vendor interceptor uses `UserInfoManager.userId()` unconditionally).
- D3. `sensorId` = the BLE sensor serial JNG already stores for AiDex X/LinX; `deviceSn` = the same
  serial; `sensorIndex` = 0 unless the driver reports one.
- D4. Only sensor serials JNG itself paired are uploaded (no clone/mirror sources) — the upload
  hook is the same one used for iCan/Dexcom, gated on `isEnabled`.
- D5. Follower cap is not enforced in UI; invite is immediate (no pending state).

## 10. Testing plan

- Envelope round-trip (encrypt → decrypt) and chunking for payloads > 117 bytes.
- Login: success, 401, business-code error, network failure (mock transport).
- Upload: batching, dedupe by `frontRecordId`, retry backoff, 401 clears token.
- Queue: persist/reload, cap, remove by key.
- Follower manager: list parse, invite payload shape, delete/rename.
- Settings: `isEnabled`/`isSetUp`, clear.
- Manual: test account on a device; verify glucose in the Lumiflex portal and a follower invite
  reaching the follower's app.

## 11. Risks

- R1. `userDeviceRegister` unknown → upload rejected. Mitigation OI1, feature flag, clear error
  surfaced in settings.
- R2. Wrong `appTimeZone`/`mainDeviceId` → records stored but rendered oddly. Mitigation OI3/OI4.
- R3. Vendor changes the contract in the next app version. Mitigation: constants isolated, error
  text shown, channel can be disabled.
- R4. Account lockout while probing. Mitigation: dedicated test account only.
- R5. Shipping the vendor RSA private key in the repo. Mitigation AG1: single constant, review,
  no logging; it is already present in every official install.

## 12. References

- `docs/lumiflex-cloud-recon.md` — recon, endpoint inventory, DTO field lists.
- `Common/src/main/java/tk/glucodata/share/ican/` — module to clone (client/queue/uploader/…).
- `Common/src/mobile/java/tk/glucodata/ui/ICanHealthCloud*Screen.kt` — UI to clone.
- `Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt` — hook location.
- `docs/superpowers/specs/2026-10-31-ican-health-cloud-design.md` — sibling design.
