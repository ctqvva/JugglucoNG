// OttaiCloudClient.kt — Ottai cloud bootstrap (login + validate + bind).
//
// Legacy sessions present as the recovered watch app. New CN SMS sessions present as the
// current phone app. Tokens must stay on the identity that issued them: the CN backend rejects
// a valid token if subsequent requests switch between these profiles.
//
// Geoblock: only CN-backend (api.ottai.com) requests carry forwarded-IP headers set to a China IP
// (that backend is geoblocked to China). GLOBAL (seas.ottai.com) / SYAI (api.syai.com) requests send
// NO forwarded headers — a forged CN IP there looks cross-region and the backend rejects the token
// (AuthFailed_TokenInvalid / accountLogin biz=Error). See headers(). CONFIRMED on-device: a RU user on
// GLOBAL failed until a VPN masked the real IP; the official global app sends no such headers and works.
//
// SECURITY: the signature SEED and all returned secrets (accessToken,
// glucoseSecretKey, keyA, method, coefficient) are credentials/IP. Never log them.
// All calls are blocking — invoke on a background thread.

package tk.glucodata.drivers.ottai

import android.content.Context
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject
import tk.glucodata.Log

object OttaiCloudClient {

    private const val TAG = OttaiConstants.TAG

    // Recovered API signing seed. The legacy endpoints and cgmAuth signer use MD5.
    private const val SEED = "dy7234hbnrnfh7q89eru8ybfn899"
    private const val WATCH_APP_NAME = "ottai-watch"
    private const val WATCH_PKG = "com.ottai.tag.watch"
    private const val PHONE_APP_NAME = "ottai"
    private const val PHONE_PKG = "com.ottai.tag"
    private const val TIMEOUT_MS = 30_000
    private const val TEMPORARY_UNBIND_DELAY_MS = 2_000L
    internal const val TEMPORARY_MATERIAL_UNBIND_METHOD = "PUT"

    private data class ApiIdentity(
        val appName: String,
        val packageName: String,
    ) {
        fun deviceId(value: String): String = "$appName:a:$value"
    }

    /**
     * The CN phone SDK stores the suffix of common_uniqueid (n:<native UUID> or g:<fallback>) and
     * formats the complete header identity as appName:a:<suffix>. The same complete identity is
     * part of the Active Auth signer input.
     */
    private fun commonHeaderDeviceId(identity: ApiIdentity, value: String): String =
        identity.deviceId(value)

    private fun sessionIdentity(profile: OttaiRegistry.SessionProfile): ApiIdentity =
        if (profile == OttaiRegistry.SessionProfile.CN_PHONE) {
            ApiIdentity(PHONE_APP_NAME, PHONE_PKG)
        } else {
            ApiIdentity(WATCH_APP_NAME, WATCH_PKG)
        }

    // Business codes a caller has to branch on rather than merely show. An account holds one
    // bound sensor at a time, so until the previous one is released every device call for a new
    // MAC is refused with AppUser_AlreadyBinding ("there are other devices that were not
    // unbound") — observed 12 times in a row while activating a replacement sensor on
    // 2026-07-29, with the UI showing only the generic "no materials for this cloud ID".
    const val BIZ_ALREADY_BINDING = "AppUser_AlreadyBinding"
    const val BIZ_OUT_OF_PRODUCE_TIME = "AppDevice_OutOfProduceTime"
    const val BIZ_UPGRADE_VERSION = "AppDevice_Upgrade_Version"
    // The server already considers the sensor finished. For [unbind] that is the state the caller
    // was asking for, so it counts as released. Not yet observed on-device.
    const val BIZ_END_USING = "AppDevice_EndUsing"
    // The stored session is dead as far as the backend is concerned (http=401). Seen when a
    // web-only JWT was persisted as the mobile session (sign-up on 2026-09-18: every
    // /deviceBind/list answered this right after "web login ok") and when a token simply expires.
    // Nothing the account does afterwards will succeed; the only fix is a fresh sign-in.
    const val BIZ_TOKEN_INVALID = "AuthFailed_TokenInvalid"

    /**
     * A non-secret cloud failure: [text] is what the UI appends, [code] is the backend's business
     * code (see the BIZ_ constants) for the callers that act on a specific one. [code] is blank
     * for the failures we raise ourselves, before any response exists.
     */
    data class CloudFailure(val text: String, val code: String = "") {
        /** The backend no longer accepts the stored session; the caller must sign in again. */
        val isTokenInvalid: Boolean get() = code.equals(BIZ_TOKEN_INVALID, ignoreCase = true)
    }

    /** Failure belongs to this unbind attempt even if another cloud request finishes meanwhile. */
    data class UnbindResult(val released: Boolean, val failure: CloudFailure? = null)

    internal data class CloudRequestResult(val body: JSONObject?, val failure: CloudFailure?)

    /** Last non-secret failure reason (HTTP + business code/message); null after a call that succeeded. */
    @Volatile
    var lastFailure: CloudFailure? = null
        private set

    /** [lastFailure] as the string the UI shows; blank when the last call succeeded. */
    val lastError: String get() = lastFailure?.text.orEmpty()

    data class LoginResult(val userId: String, val accessToken: String, val glucoseSecretKey: String) {
        val ok: Boolean get() = accessToken.isNotBlank() && glucoseSecretKey.isNotBlank()
    }

    /** SMS endpoints receive subscriber digits separately from their phoneCode. */
    private fun normalizePhone(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.length == 13 && d.startsWith("86")) d = d.substring(2)
        if (d.length == 14 && d.startsWith("0086")) d = d.substring(4)
        return d
    }

    data class DeviceResp(
        val mac: String,
        val keyA: String,
        val method: String,
        val coefficient: String,
        val produceTime: Long,
        val methodUpdateTime: Long,
        val coeffUpdateTime: Long,
        val activeTime: Long,
        val activeExpireTime: Long,  // maxActive duration (ms) -> BLE p.D
        val preheatPeriodTime: Long,
        val retainTime: Long,        // destruction value (ms) -> BLE p.E; 0 => server default
        val deviceVersion: String,
        val deviceId: Int,
    )

    data class DeviceValidation(
        val device: DeviceResp,
        /** New CN firmware exposes metadata through V3, then supplies keyA only from bindV3. */
        val requiresV3Bind: Boolean,
    )

    // ---- signature / headers ----

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    internal fun signForProfile(
        profile: OttaiRegistry.SessionProfile,
        deviceId: String,
        ts: Long,
        vararg args: String,
    ): String {
        val identity = sessionIdentity(profile)
        return md5Hex(identity.appName + identity.deviceId(deviceId) + ts + args.joinToString("") + SEED)
    }

    /**
     * cgmAuth/verify signer — CONFIRMED from live Frida capture on com.ottai.tag v1.55.0.
     * See AGENTS/docs/protocols/ottai/cgmauth-sign-formula.md for full evidence.
     *
     * Input: appName + deviceId + mac(UPPER) + paramStr(UPPER) + shaInfo(UPPER) + timestamp_ms + SEED
     * Digest: MD5, lowercase hex output.
     */
    internal fun cgmAuthVerifySign(
        profile: OttaiRegistry.SessionProfile,
        deviceId: String,
        timestampMillis: Long,
        mac: String,
        paramStr: String,
        shaInfo: String,
    ): String {
        val identity = sessionIdentity(profile)
        return md5Hex(
            identity.appName +
                commonHeaderDeviceId(identity, deviceId) +
                mac.uppercase(Locale.ROOT) +
                paramStr.uppercase(Locale.ROOT) +
                shaInfo.uppercase(Locale.ROOT) +
                timestampMillis.toString() +
                SEED,
        )
    }

    internal fun cgmAuthVerifyRequestBody(
        mac: String,
        paramStr: String,
        shaInfo: String,
        sign: String,
        timestampMillis: Long,
    ): JSONObject = JSONObject().apply {
        put("mac", mac)
        // The official cgmAuth builder receives the BLE hex strings uppercase and places those
        // same strings in the request map. Lowercasing only the body made it differ from the
        // captured request even though the MD5 preimage had already been corrected.
        put("paramStr", paramStr.uppercase(Locale.ROOT))
        put("shaInfo", shaInfo.uppercase(Locale.ROOT))
        put("sign", sign)
        // Dart DateTime stores microseconds internally; the official caller's divide-by-1000
        // produces epoch milliseconds, then reuses that exact string in the signer and body.
        put("timestamp", timestampMillis.toString())
    }

    private fun activeProfile(ctx: Context, apiBase: String): OttaiRegistry.SessionProfile =
        if (apiBase == OttaiConstants.API_BASE) OttaiRegistry.loadSessionProfile(ctx)
        else OttaiRegistry.SessionProfile.WATCH

    private fun requestDeviceId(ctx: Context, profile: OttaiRegistry.SessionProfile): String =
        if (profile == OttaiRegistry.SessionProfile.CN_PHONE) {
            OttaiRegistry.loadCnHeaderDeviceId(ctx)
        } else {
            OttaiRegistry.loadOrCreateDeviceId(ctx)
        }

    private fun headers(
        ctx: Context,
        ts: Long,
        apiBase: String,
        authorizationOverride: String? = null,
        profileOverride: OttaiRegistry.SessionProfile? = null,
    ): MutableMap<String, String> {
        val token = authorizationOverride ?: OttaiRegistry.loadAccessToken(ctx)
        val profile = profileOverride ?: activeProfile(ctx, apiBase)
        val deviceId = requestDeviceId(ctx, profile)
        if (profile == OttaiRegistry.SessionProfile.CN_PHONE) {
            return cnPhoneHeaders(
                deviceId = deviceId,
                accessToken = token,
                timestamp = ts,
                traceId = UUID.randomUUID().toString(),
            ).toMutableMap().apply {
                if (token.isBlank()) remove("Authorization")
            }
        }
        val identity = sessionIdentity(OttaiRegistry.SessionProfile.WATCH)
        val offsetSec = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val h = mutableMapOf(
            "appName" to identity.appName,
            "versionName" to "1.1.0",
            "versionCode" to "244301",
            "packageName" to identity.packageName,
            "ua" to "Android_Watch_Ottai_Arc",
            "timezone" to offsetSec.toString(),
            "timeZoneName" to TimeZone.getDefault().id,
            "language" to Locale.getDefault().language,
            "traceId" to "trace_testtest",
            "timestamp" to ts.toString(),
            "country" to "zh_CN",
            "deviceId" to identity.deviceId(deviceId),
        )
        // Geoblock bypass — forge a CN source IP ONLY for the CN backend (api.ottai.com), which is
        // geoblocked to China and REQUIRES a CN IP. The GLOBAL (seas.ottai.com) and SYAI (api.syai.com)
        // backends serve non-China users; forging a CN IP there makes the request look cross-region and
        // the backend rejects the session (AuthFailed_TokenInvalid, accountLogin biz=Error). The official
        // global app sends no forwarded headers and works from any region, so we mirror that here.
        // (Web-API calls already omit these — see webHeaders.)
        if (apiBase == OttaiConstants.API_BASE) {
            h["X-Forwarded-For"] = OttaiConstants.CN_FORWARD_IP
            h["X-Real-IP"] = OttaiConstants.CN_FORWARD_IP
            h["CF-Connecting-IP"] = OttaiConstants.CN_FORWARD_IP
            h["True-Client-IP"] = OttaiConstants.CN_FORWARD_IP
        }
        if (token.isNotBlank()) h["Authorization"] = token
        return h
    }

    /** Client identity used by current CN phone-app sessions. */
    internal fun cnPhoneHeaders(
        deviceId: String,
        accessToken: String,
        timestamp: Long,
        traceId: String,
    ): Map<String, String> {
        val identity = sessionIdentity(OttaiRegistry.SessionProfile.CN_PHONE)
        return mapOf(
            "User-Agent" to "Dart/3.8 (dart:io)",
            "ua" to "Android",
            "deviceId" to commonHeaderDeviceId(identity, deviceId),
            "applicationType" to "ottai_main",
            "appName" to identity.appName,
            "versionCode" to "263121",
            "country" to "CN",
            "language" to "zh",
            "timezone" to "28800",
            "packageName" to identity.packageName,
            "productModel" to "MB",
            "unit" to "mmol_L",
            "timeZoneName" to "Asia/Shanghai",
            "deviceModel" to "SM-A205FN",
            "versionName" to "1.55.0",
            "X-Forwarded-For" to OttaiConstants.CN_FORWARD_IP,
            "X-Real-IP" to OttaiConstants.CN_FORWARD_IP,
            "CF-Connecting-IP" to OttaiConstants.CN_FORWARD_IP,
            "True-Client-IP" to OttaiConstants.CN_FORWARD_IP,
            "timestamp" to timestamp.toString(),
            "traceId" to traceId,
            "Authorization" to accessToken,
        )
    }

    private fun now(): Long = System.currentTimeMillis()

    // ---- API ----

    /** Backend host for the signed-in account (CN api.ottai.com vs global seas.ottai.com). */
    private fun base(ctx: Context): String = OttaiRegistry.loadApiBase(ctx)

    /**
     * The composite bind endpoint requires a deviceVersion even when Syai or global Ottai allows
     * recovering a sensor which is not present in the signed-in account. These values come from
     * exported sensors and are used only as bind request metadata; the response remains
     * authoritative for the recovered sensor's persisted version and materials.
     */
    internal const val SYAI_MATERIAL_BIND_DEVICE_VERSION = "E1.1.4(V1.7.S2530.1)"
    internal const val GLOBAL_MATERIAL_BIND_DEVICE_VERSION = "vE1.2.3(V1.7.SH2542.1)"
    internal fun materialBindDeviceVersion(
        apiBase: String,
        selectedDeviceVersion: String?,
        failureCode: String?,
    ): String? {
        selectedDeviceVersion?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        if (!failureCode.equals(BIZ_OUT_OF_PRODUCE_TIME, ignoreCase = true)) return null
        return when (apiBase) {
            OttaiConstants.API_BASE_SYAI -> SYAI_MATERIAL_BIND_DEVICE_VERSION
            OttaiConstants.API_BASE_GLOBAL -> GLOBAL_MATERIAL_BIND_DEVICE_VERSION
            else -> null
        }
    }

    fun materialBindDeviceVersion(
        ctx: Context,
        selectedDeviceVersion: String?,
        failureCode: String?,
    ): String? = materialBindDeviceVersion(base(ctx), selectedDeviceVersion, failureCode)

    /** GET /user/apiToken — sig over (ts). Returns the apiToken or null. */
    fun getApiToken(
        ctx: Context,
        apiBase: String = base(ctx),
        authorizationOverride: String? = null,
        profileOverride: OttaiRegistry.SessionProfile? = null,
    ): String? {
        val ts = now()
        val profile = profileOverride ?: activeProfile(ctx, apiBase)
        val deviceId = requestDeviceId(ctx, profile)
        val sig = signForProfile(profile, deviceId, ts)
        val resp = httpGet(
            apiBase + OttaiConstants.EP_API_TOKEN,
            mapOf("signature" to sig),
            headers(ctx, ts, apiBase, authorizationOverride, profile),
        )
        return resp?.optStringDeep("data")
    }

    /** POST /user/smsCode — needs apiToken; sig over (phone, apiToken). Returns requestId. */
    fun requestSmsCode(ctx: Context, phone: String, phoneCode: String = "86"): String? {
        val profile = OttaiRegistry.SessionProfile.CN_PHONE
        val apiToken = getApiToken(ctx, OttaiConstants.API_BASE, "", profile) ?: run { lastFailure = CloudFailure("apiToken failed"); Log.w(TAG, "apiToken failed"); return null }
        val ts = now()
        val deviceId = requestDeviceId(ctx, profile)
        val ph = normalizePhone(phone)
        val body = JSONObject().apply {
            put("phoneCode", phoneCode)
            put("phone", ph)
            put("apiToken", apiToken)
            // smsType=1 (login). REQUIRED: without it the server still sends the SMS and
            // returns a requestId, but provisions the code under the wrong type, so the
            // subsequent smsLogin always fails with Sms_CodeInvalid. Not part of the signature.
            put("smsType", 1)
            put("signature", signForProfile(profile, deviceId, ts, ph, apiToken))
        }
        val resp = httpPostJson(
            OttaiConstants.API_BASE + OttaiConstants.EP_SMS_CODE,
            body.toString(),
            headers(ctx, ts, OttaiConstants.API_BASE, "", profile),
        )
        return resp?.optStringDeep("data")
    }

    /** POST /user/smsLogin — sig over (requestId, phone, validCode). Persists creds. */
    fun smsLogin(
        ctx: Context,
        phone: String,
        code: String,
        requestId: String,
        phoneCode: String = "86",
    ): LoginResult? {
        val profile = OttaiRegistry.SessionProfile.CN_PHONE
        val ts = now()
        val deviceId = requestDeviceId(ctx, profile)
        val ph = normalizePhone(phone)
        val body = JSONObject().apply {
            put("phoneCode", phoneCode)
            put("phone", ph)
            put("validCode", code)
            put("requestId", requestId)
            put("signature", signForProfile(profile, deviceId, ts, requestId, ph, code))
        }
        val resp = httpPostJson(
            OttaiConstants.API_BASE + OttaiConstants.EP_SMS_LOGIN,
            body.toString(),
            headers(ctx, ts, OttaiConstants.API_BASE, "", profile),
        ) ?: return null
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return null
        val result = LoginResult(
            userId = data.optString("userId").orEmptyIfNull(),
            accessToken = data.optString("accessToken").orEmptyIfNull(),
            glucoseSecretKey = data.optString("glucoseSecretKey").orEmptyIfNull(),
        )
        if (result.ok) {
            OttaiRegistry.saveApiBase(ctx, OttaiConstants.API_BASE)  // this account lives on the CN backend
            OttaiRegistry.saveSessionProfile(ctx, profile)
            OttaiRegistry.saveAccessToken(ctx, result.accessToken)
            OttaiRegistry.saveGlucoseSecretKey(ctx, result.glucoseSecretKey)
            OttaiRegistry.saveUserId(ctx, result.userId)
            Log.i(TAG, "CN login session profile=cn_phone")
        }
        return result
    }

    /** GET /device/validateDeviceByMacV2 — sig over (mac). Read-only, no sensor change. */
    /**
     * Global-app login: account (email or phone) + password, no SMS. POSTs the confirmed
     * `/user/accountLogin` (it needs an apiToken in the body, same as smsCode). The exact
     * signature arg-order isn't capturable from the Flutter global app, so a few orderings
     * are tried with a fresh apiToken each (a wrong signature is rejected pre-auth, so it
     * isn't a failed-login attempt). Persists creds on success; see [lastError] otherwise.
     */
    fun passwordLogin(
        ctx: Context,
        account: String,
        password: String,
        base: String = OttaiConstants.API_BASE_GLOBAL,
        authorizationOverride: String? = null,
    ): LoginResult? {
        val acct = account.trim()
        if (acct.isBlank() || password.isBlank()) { lastFailure = CloudFailure("account/password required"); return null }
        // Username + password login on a GLOBAL backend (seas.ottai.com / api.syai.com) — same
        // API + signature scheme, different host. The server keys on the account USERNAME, which
        // is a server-assigned RANDOM string (verified: NOT derived from the email), so the email
        // cannot be used here — email sign-in goes through the web API (see mailLogin). Password
        // is PLAINTEXT; sig arg-order = sign(apiToken, account, password). SINGLE attempt only —
        // a wrong password is a real failed-login attempt, so do NOT retry variants (lockout).
        // A login must not carry whatever token is still stored from an earlier session (a stale
        // or web-only JWT): with no override, send no Authorization header at all.
        val authorization = authorizationOverride ?: ""
        val apiToken = getApiToken(ctx, base, authorization)
            ?: run { lastFailure = CloudFailure("apiToken failed"); return null }
        val ts = now()
        val deviceId = requestDeviceId(ctx, OttaiRegistry.SessionProfile.WATCH)
        val body = JSONObject().apply {
            put("account", acct)
            put("username", acct)
            put("userName", acct)
            put("password", password)
            put("apiToken", apiToken)
            put("signature", signForProfile(OttaiRegistry.SessionProfile.WATCH, deviceId, ts, apiToken, acct, password))
        }
        val resp = httpPostJson(
            base + OttaiConstants.EP_ACCOUNT_LOGIN,
            body.toString(),
            headers(ctx, ts, base, authorization),
        ) ?: return null
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return null
        val result = LoginResult(
            userId = data.optString("userId").orEmptyIfNull(),
            accessToken = data.optString("accessToken").orEmptyIfNull(),
            glucoseSecretKey = data.optString("glucoseSecretKey").orEmptyIfNull(),
        )
        if (result.ok) {
            OttaiRegistry.saveApiBase(ctx, base)  // subsequent validate/list/bind use this backend
            OttaiRegistry.saveSessionProfile(ctx, OttaiRegistry.SessionProfile.WATCH)
            OttaiRegistry.saveAccessToken(ctx, result.accessToken)
            OttaiRegistry.saveGlucoseSecretKey(ctx, result.glucoseSecretKey)
            OttaiRegistry.saveUserId(ctx, result.userId)
            Log.i(TAG, "${if (base == OttaiConstants.API_BASE_SYAI) "Syai mobile" else "password"} login ok")
            return result
        }
        return null
    }

    /** POST /user/logout (best-effort) and clear all locally-stored account credentials. */
    fun logout(ctx: Context) {
        runCatching {
            val ts = now()
            httpPostJson(base(ctx) + OttaiConstants.EP_LOGOUT, "{}", headers(ctx, ts, base(ctx)))
        }
        clearSession(ctx)
    }

    /**
     * Drop the stored account session without telling the backend. For a session the backend has
     * already rejected ([BIZ_TOKEN_INVALID]) /user/logout would only 401 again; what matters is
     * that the app stops presenting the account as signed in.
     */
    fun clearSession(ctx: Context) {
        OttaiRegistry.saveAccessToken(ctx, null)
        OttaiRegistry.saveGlucoseSecretKey(ctx, null)
        OttaiRegistry.saveUserId(ctx, null)
        OttaiRegistry.saveAccountLogin(ctx, null)
        OttaiRegistry.saveSessionProfile(ctx, null)
        OttaiRegistry.saveApiBase(ctx, OttaiConstants.API_BASE)  // reset to CN default
    }

    fun validateByMac(ctx: Context, mac: String): DeviceResp? =
        validateForSetup(ctx, mac)?.device?.takeIf { it.keyA.isNotBlank() }

    /**
     * Read-only validation. Existing sensors retain the proven V2 GET flow. Current CN firmware
     * answers V2 with AppDevice_Upgrade_Version and requires a V3 JSON POST; that response carries
     * authoritative device metadata but deliberately no keyA.
     */
    fun validateForSetup(ctx: Context, mac: String): DeviceValidation? {
        val canonical = OttaiConstants.canonicalSensorId(mac)
        if (canonical.isBlank()) return null
        val apiBase = base(ctx)
        val ts = now()
        val profile = activeProfile(ctx, apiBase)
        val deviceId = requestDeviceId(ctx, profile)
        Log.i(TAG, "validate session profile=${profile.name.lowercase(Locale.ROOT)}")
        val resp = httpGet(
            apiBase + OttaiConstants.EP_VALIDATE_BY_MAC,
            mapOf("mac" to canonical, "signature" to signForProfile(profile, deviceId, ts, canonical)),
            headers(ctx, ts, apiBase, profileOverride = profile),
        )
        if (resp != null) {
            parseDeviceResp(resp)?.let { return DeviceValidation(it, requiresV3Bind = false) }
        }
        if (apiBase != OttaiConstants.API_BASE || profile != OttaiRegistry.SessionProfile.CN_PHONE ||
            !lastFailure?.code.equals(BIZ_UPGRADE_VERSION, ignoreCase = true)
        ) return null

        val v3Ts = now()
        val body = JSONObject().apply {
            put("mac", canonical)
            put("signature", signForProfile(profile, deviceId, v3Ts, canonical))
        }
        val v3 = httpPostJson(
            apiBase + OttaiConstants.EP_VALIDATE_BY_MAC_V3,
            body.toString(),
            headers(ctx, v3Ts, apiBase, profileOverride = profile),
        ) ?: return null
        val device = parseDeviceResp(v3, requireKeyA = false) ?: return null
        Log.i(TAG, "CN V3 validation ok mac=$canonical version=${device.deviceVersion}")
        // The BLE Active Auth chain binds later, on its own connection; keep the version the
        // server validated so bindV3 can repeat it exactly.
        OttaiRegistry.saveLastValidatedDeviceVersion(ctx, canonical, device.deviceVersion)
        return DeviceValidation(device, requiresV3Bind = true)
    }

    internal enum class BindContract { LEGACY, V3 }

    /**
     * Build the composite-bind bodies. Recovered 2026-08-22 from the official builder pair
     * (0x7aec04 → branches at 0x7aee10 / 0x7aef88): BOTH endpoints send
     * {mac, deviceType:"cgm", deviceVersion, activeTime, userId, newBindType:2} — the earlier
     * sign/colorBoxTailSn/keyC/boardType model was a pool-adjacency misattribution (those are
     * fields of the server's device-record response, echoed by UI code, never sent on bind).
     * The legacy branch keeps its historically-working shape; only the V3 contract is corrected:
     * userId instead of a null patientId, and activeTime in epoch seconds (the official caller
     * runs the same floor(ms/1000) division as its request timestamps).
     */
    internal fun bindRequestBody(
        mac: String,
        deviceVersion: String,
        userId: String?,
        activeTime: Long,
        contract: BindContract,
    ): JSONObject = JSONObject().apply {
        put("mac", mac)
        put("deviceType", "cgm")
        put("deviceVersion", deviceVersion)
        put("activeTime", activeTime)
        if (!userId.isNullOrBlank()) put("userId", userId)
        when (contract) {
            BindContract.LEGACY -> Unit
            BindContract.V3 -> put("newBindType", 2)
        }
    }

    /** POST /deviceBind/composite/bind — unsigned; binds the cloud account and returns keyA. */
    fun bind(ctx: Context, mac: String, deviceVersion: String, userId: String): DeviceResp? =
        bind(ctx, mac, deviceVersion, userId, BindContract.LEGACY)

    /**
     * Current CN material bind for devices whose read-only validation selected the V3 contract.
     * Requires a signed-in session ([OttaiRegistry.loadUserId]) and an Active Auth handshake that
     * completed server-side — without it the server answers DEVICE_AUTH_ILLEGAL_ERROR.
     */
    fun bindV3(ctx: Context, mac: String, deviceVersion: String): DeviceResp? =
        bind(ctx, mac, deviceVersion, OttaiRegistry.loadUserId(ctx), BindContract.V3)

    /**
     * Result of the server-mediated V3 `Active_Auth` handshake. The server takes the sensor-read
     * authDev/authFlag and returns the values to write back — char mapping from the ottag1
     * decompile: [authHost] -> char 1756ef6e, [authFlag] -> char 785022c6. [keyB]/[cf] are extra
     * material whose write target is not yet confirmed. These are per-session handshake values, not
     * the persistent keyA secret.
     */
    data class V3AuthMaterial(
        val authHost: String,
        val authFlag: String,
        val keyB: String,
        val cf: String,
    ) {
        val ok: Boolean get() = authHost.isNotBlank() && authFlag.isNotBlank()
    }

    /**
     * POST /cgmAuth/verify — server side of the CN V3 Active_Auth. Sends the sensor-read
     * authDev/authFlag (hex) as `paramStr`; the server (which holds the sensor's keyA) returns
     * `auth`/`shaInfo`/`kb`/`cf` to write back to the sensor. Response field names are confirmed
     * from the decompile; the persisted common-header/key overrides remain runtime inputs. Wire
     * values are intentionally not logged. Returns null (leaving [lastFailure]) on any non-OK
     * response so the caller can fall back.
     */
    fun cgmAuthVerify(ctx: Context, mac: String, authDevHex: String, authFlagHex: String): V3AuthMaterial? {
        val canonical = OttaiConstants.canonicalSensorId(mac)
        if (canonical.isBlank()) { lastFailure = CloudFailure("verify requires mac"); return null }
        val apiBase = base(ctx)
        val profile = activeProfile(ctx, apiBase)
        val deviceId = requestDeviceId(ctx, profile)
        val ts = now()
        // paramStr = authDev (device param); shaInfo = authFlag, sent as its OWN field. The server
        // validates "shaInfo cannot be empty" independently of paramStr, so both must be present.
        val paramStr = authDevHex.uppercase(Locale.ROOT)
        val shaInfo = authFlagHex.uppercase(Locale.ROOT)
        Log.i(TAG, "AUTHWIRE cgmAuth/verify req macLen=${canonical.length} paramStrLen=${paramStr.length} shaInfoLen=${shaInfo.length}")
        val sign = cgmAuthVerifySign(profile, deviceId, ts, canonical, paramStr, shaInfo)
        val body = cgmAuthVerifyRequestBody(canonical, paramStr, shaInfo, sign, ts)
        val resp = httpPostJson(
            apiBase + OttaiConstants.EP_CGM_AUTH_VERIFY,
            body.toString(),
            // Gateway freshness and the captured signer both use the millisecond value.
            headers(ctx, ts, apiBase, profileOverride = profile),
            timeoutMs = 60_000,
        ) ?: return null
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result")
        val keys = data?.keys()?.asSequence()?.toList().orEmpty()
        val mat = V3AuthMaterial(
            authHost = data?.optString("auth").orEmpty(),
            authFlag = data?.optString("shaInfo").orEmpty(),
            keyB = data?.optString("kb").orEmpty(),
            cf = data?.optString("cf").orEmpty(),
        )
        Log.i(TAG, "AUTHWIRE cgmAuth/verify resp code=${lastFailure?.code ?: "OK"} dataFieldCount=${keys.size} " +
            "authLen=${mat.authHost.length} shaInfoLen=${mat.authFlag.length} kbLen=${mat.keyB.length} cfLen=${mat.cf.length}")
        return if (mat.ok) mat else null
    }

    private fun bind(
        ctx: Context,
        mac: String,
        deviceVersion: String,
        userId: String?,
        contract: BindContract,
    ): DeviceResp? {
        val canonical = OttaiConstants.canonicalSensorId(mac)
        if (canonical.isBlank() || deviceVersion.isBlank()) {
            lastFailure = CloudFailure("bind requires mac and deviceVersion")
            return null
        }
        if (contract == BindContract.V3 && userId.isNullOrBlank()) {
            lastFailure = CloudFailure("bindV3 requires a signed-in session (userId)")
            return null
        }
        val ts = now()
        // Legacy keeps its live-proven millisecond activeTime; V3 mirrors the official
        // caller's floor(ms/1000) seconds conversion.
        val body = bindRequestBody(
            canonical,
            deviceVersion.trim(),
            userId,
            if (contract == BindContract.V3) ts / 1_000L else ts,
            contract,
        )
        val endpoint = if (contract == BindContract.V3) OttaiConstants.EP_BIND_V3 else OttaiConstants.EP_BIND
        val resp = httpPostJson(base(ctx) + endpoint, body.toString(), headers(ctx, ts, base(ctx))) ?: return null
        return parseDeviceResp(resp)
    }

    /**
     * Bind only long enough to recover keyA/method/coefficient for a previously used account
     * sensor, then immediately unbind. Normal current-sensor setup should use validate/getBindDevice
     * before reaching this fallback.
     */
    fun bindForMaterials(
        ctx: Context,
        mac: String,
        deviceVersion: String,
        historicalActiveTimeMs: Long = 0L,
    ): DeviceResp? {
        val canonical = OttaiConstants.canonicalSensorId(mac)
        val userId = OttaiRegistry.loadUserId(ctx)
        val resp = bind(ctx, canonical, deviceVersion.trim(), userId, BindContract.LEGACY) ?: return null
        val bindFailure = lastFailure
        // The phone app waits before releasing the temporary binding. More importantly, never
        // return the synthetic activeTime=now from this request as the sensor's historical start.
        runCatching { Thread.sleep(TEMPORARY_UNBIND_DELAY_MS) }
        val released = runCatching { unbind(ctx, canonical, null) }
            .onFailure { Log.w(TAG, "unbind after material fetch failed: ${it.message}") }
            .getOrDefault(false)
        if (!released) Log.w(TAG, "temporary material binding cleanup was not confirmed")
        lastFailure = bindFailure
        return sanitizeTemporaryBindResponse(resp, historicalActiveTimeMs)
    }

    internal fun sanitizeTemporaryBindResponse(
        response: DeviceResp,
        historicalActiveTimeMs: Long,
    ): DeviceResp = response.copy(activeTime = historicalActiveTimeMs.takeIf { it > 0L } ?: 0L)

    /** PUT /deviceBind/unBindDevice — release a cloud binding. */
    fun unbind(ctx: Context, mac: String): Boolean = unbind(ctx, mac, null)

    fun unbindWithResult(ctx: Context, mac: String): UnbindResult = unbindWithResult(ctx, mac, null)

    private fun unbind(
        ctx: Context,
        mac: String,
        headerOverride: ((Long) -> Map<String, String>)?,
    ): Boolean = unbindWithResult(ctx, mac, headerOverride).released

    private fun unbindWithResult(
        ctx: Context,
        mac: String,
        headerOverride: ((Long) -> Map<String, String>)?,
    ): UnbindResult {
        val canonical = OttaiConstants.canonicalSensorId(mac)
        if (canonical.isBlank()) return UnbindResult(false, CloudFailure("unbind requires mac"))
        val ts = now()
        val body = JSONObject().apply {
            put("mac", canonical)
            put("deviceType", "cgm")
            put("unbindType", 0)
        }
        val requestHeaders = headerOverride?.invoke(ts) ?: headers(ctx, ts, base(ctx))
        return unbindResponseResult(
            httpPutJsonWithResult(base(ctx) + OttaiConstants.EP_UNBIND, body.toString(), requestHeaders),
        )
    }

    internal fun unbindResponseResult(result: CloudRequestResult): UnbindResult {
        val resp = result.body ?: return UnbindResult(false, result.failure)
        val bizCode = resp.opt("code")?.toString().orEmpty()
        // BIZ_END_USING says the server had already finished with this sensor, so the binding the
        // caller wanted released is gone either way — not a failure to report.
        val released = result.failure == null || bizCode.equals(BIZ_END_USING, ignoreCase = true)
        return UnbindResult(released, result.failure.takeUnless { released })
    }

    /** GET /deviceBind/getBindDevice — current account-bound sensor, no signature. */
    fun getBindDevice(ctx: Context): DeviceResp? {
        val ts = now()
        val resp = httpGet(
            base(ctx) + OttaiConstants.EP_GET_BIND_DEVICE,
            emptyMap(),
            headers(ctx, ts, base(ctx)),
        ) ?: return null
        return parseDeviceResp(resp)
    }

    /** One row of GET /deviceBind/list — a sensor the account has bound (now or before). */
    data class DeviceSummary(
        val mac: String,
        val serialNo: String,
        val deviceType: String,
        val deviceVersion: String,
        val bindTime: Long,
        val unbindTime: Long,
        /** Current-binding lookup overrides historical timestamps when available. */
        val boundToAccount: Boolean? = null,
    ) {
        /** Still bound (vs. a previously-used sensor that was unbound). */
        val isActive: Boolean get() = boundToAccount ?: (unbindTime <= 0L)
    }

    data class AccountDevicesResult(val devices: List<DeviceSummary>?, val failure: CloudFailure? = null)

    /** Read-only account snapshot: history alone does not identify the current binding. */
    fun accountDevices(ctx: Context): AccountDevicesResult {
        val apiBase = base(ctx)
        val requestHeaders = headers(ctx, now(), apiBase)
        val history = httpGetWithResult(
            apiBase + OttaiConstants.EP_DEVICE_LIST,
            mapOf("pageSize" to "80", "pageNumber" to "1"),
            requestHeaders,
        )
        if (history.failure?.isTokenInvalid == true) return AccountDevicesResult(null, history.failure)
        val binding = httpGetWithResult(
            apiBase + OttaiConstants.EP_GET_BIND_DEVICE,
            emptyMap(),
            requestHeaders,
        )
        val result = accountDevicesResult(history, binding)
        Log.i(
            TAG,
            "account binding current=${result.devices?.firstOrNull { it.isActive }?.mac ?: "none"} " +
                "rows=${result.devices?.size ?: 0} checked=${result.failure == null}",
        )
        if (result.failure != null) {
            val data = binding.body?.optJSONObject("data") ?: binding.body?.optJSONObject("result")
            val fields = data?.keys()?.asSequence()?.take(12)?.joinToString(",").orEmpty()
            Log.w(TAG, "account binding lookup failed: ${result.failure.text} dataFields=$fields")
        }
        return result
    }

    internal fun accountDevicesResult(
        history: CloudRequestResult,
        binding: CloudRequestResult,
    ): AccountDevicesResult {
        history.failure?.takeIf { it.isTokenInvalid }?.let { return AccountDevicesResult(null, it) }
        binding.failure?.let { return AccountDevicesResult(null, it) }
        val body = binding.body ?: return AccountDevicesResult(null, CloudFailure("Empty account binding response"))
        val data = body.optJSONObject("data") ?: body.optJSONObject("result")
        val vo = data?.optJSONObject("cgmDeviceRespVO") ?: data
        val mac = OttaiConstants.canonicalSensorId(vo?.optString("mac").orEmptyIfNull())
        val historyDevices = if (history.failure == null) history.body?.let(::parseDeviceSummaries).orEmpty() else emptyList()
        if (!OttaiConstants.looksLikeMac(mac)) {
            // Explicit null/empty data means no binding. An unfamiliar non-empty response must
            // not be presented as proof that a sensor is unbound.
            val explicitlyEmpty = if (data != null) data.length() == 0 else
                (body.has("data") && body.isNull("data")) || (body.has("result") && body.isNull("result"))
            if (!explicitlyEmpty) {
                return AccountDevicesResult(null, CloudFailure("Account binding response has no valid sensor MAC"))
            }
            if (history.failure != null) return AccountDevicesResult(null, history.failure)
            return AccountDevicesResult(historyDevices.map { it.copy(boundToAccount = false) })
        }
        val previous = historyDevices.firstOrNull { OttaiConstants.canonicalSensorId(it.mac) == mac }
        val current = DeviceSummary(
            mac = mac,
            serialNo = vo?.optString("serialNo").orEmptyIfNull().ifBlank { previous?.serialNo.orEmpty() },
            deviceType = vo?.optString("deviceType").orEmptyIfNull().ifBlank { "cgm" },
            deviceVersion = vo?.optString("deviceVersion").orEmptyIfNull().ifBlank { previous?.deviceVersion.orEmpty() },
            bindTime = data?.optLongLoose("bindTime")?.takeIf { it > 0L }
                ?: vo?.optLongLoose("bindTime")?.takeIf { it > 0L } ?: previous?.bindTime ?: 0L,
            unbindTime = 0L,
            boundToAccount = true,
        )
        return AccountDevicesResult(
            listOf(current) + historyDevices
                .filter { OttaiConstants.canonicalSensorId(it.mac) != mac }
                .map { it.copy(boundToAccount = false) },
        )
    }

    /**
     * GET /deviceBind/list — the account's bound + previously-bound sensors, newest
     * first. Lets the user pick a sensor instead of typing/scanning a MAC. Returns an
     * empty list on error (see [lastError]).
     */
    fun listDevices(ctx: Context, pageSize: Int = 80, pageNumber: Int = 1): List<DeviceSummary> {
        val ts = now()
        val resp = httpGet(
            base(ctx) + OttaiConstants.EP_DEVICE_LIST,
            mapOf("pageSize" to pageSize.toString(), "pageNumber" to pageNumber.toString()),
            headers(ctx, ts, base(ctx)),
        ) ?: return emptyList()
        return parseDeviceSummaries(resp)
    }

    internal fun parseDeviceSummaries(resp: JSONObject): List<DeviceSummary> {
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return emptyList()
        val items = data.optJSONArray("items")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("records")
            ?: return emptyList()
        val out = ArrayList<DeviceSummary>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val mac = o.optString("mac").orEmptyIfNull()
            if (mac.isBlank()) continue
            out.add(
                DeviceSummary(
                    mac = mac,
                    serialNo = o.optString("serialNo").orEmptyIfNull(),
                    deviceType = o.optString("deviceType").orEmptyIfNull(),
                    deviceVersion = o.optString("deviceVersion").orEmptyIfNull()
                        .ifBlank { o.optJSONObject("cgmDeviceRespVO")?.optString("deviceVersion").orEmptyIfNull() },
                    bindTime = o.optLongLoose("bindTime"),
                    unbindTime = o.optLongLoose("unbindTime"),
                ),
            )
        }
        return out
    }

    private fun parseDeviceResp(resp: JSONObject, requireKeyA: Boolean = true): DeviceResp? {
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return null
        val vo = data.optJSONObject("cgmDeviceRespVO") ?: data
        // method + coefficient (and their update-times) are authoritative in the dedicated
        // cgmDeviceMethodVO. cgmDeviceRespVO can carry an empty `method` for some firmwares
        // (e.g. V1.5) while the method VO has it — so prefer the method VO, fall back to vo.
        val mvo = data.optJSONObject("cgmDeviceMethodVO") ?: vo
        val keyA = vo.optString("keyA").orEmptyIfNull()
        if (requireKeyA && keyA.isBlank()) return null
        fun pick(key: String): String = mvo.optString(key).ifBlank { vo.optString(key) }
        fun pickTime(key: String): Long = mvo.optLongLoose(key).takeIf { it != 0L } ?: vo.optLongLoose(key)
        return DeviceResp(
            mac = vo.optString("mac").orEmptyIfNull(),
            keyA = keyA,
            method = pick("method"),
            coefficient = pick("coefficient"),
            produceTime = vo.optLongLoose("produceTime"),
            methodUpdateTime = pickTime("methodUpdateTime"),
            coeffUpdateTime = pickTime("coeffUpdateTime"),
            activeTime = vo.optLongLoose("activeTime"),
            activeExpireTime = vo.optLongLoose("activeExpireTime"),
            preheatPeriodTime = vo.optLongLoose("preheatPeriodTime"),
            retainTime = vo.optLongLoose("retainTime"),
            deviceVersion = vo.optString("deviceVersion").orEmptyIfNull(),
            deviceId = vo.optInt("id", 0),
        )
    }

    /**
     * Decrypt the cloud-returned materials with the account glucoseSecretKey.
     * Returns null if keyA fails to decrypt to the expected 192-hex auth group.
     */
    fun toMaterials(ctx: Context, mac: String, resp: DeviceResp): OttaiRegistry.DeviceMaterials? {
        val secret = OttaiRegistry.loadGlucoseSecretKey(ctx)
        if (secret.isBlank()) return null
        val canonical = OttaiConstants.canonicalSensorId(mac)
        val keyAPlain = OttaiCrypto.decryptKeyA(resp.keyA, secret, resp.produceTime.toString(), canonical)
            ?: return null
        if (OttaiCrypto.parseAuthKeys(keyAPlain) == null) return null
        val methodPlain = if (resp.method.isNotBlank())
            OttaiCrypto.decryptMethod(resp.method, secret, resp.methodUpdateTime.toString(), canonical).orEmpty() else ""
        val coeffPlain = if (resp.coefficient.isNotBlank())
            OttaiCrypto.decryptCoefficient(resp.coefficient, secret, resp.coeffUpdateTime.toString(), canonical).orEmpty() else ""
        return OttaiRegistry.DeviceMaterials(
            keyAHex = keyAPlain,
            method = OttaiMethodDefaults.resolve(methodPlain, coeffPlain),
            coefficient = coeffPlain,
            // CN V3 bind echoes its epoch-seconds request. Legacy responses are already ms.
            activeTimeMs = normalizeOttaiActiveTimeMs(resp.activeTime),
            activeExpireTimeMs = resp.activeExpireTime,
            retainTimeMs = resp.retainTime,
            preheatPeriodMs = resp.preheatPeriodTime,
            deviceVersion = resp.deviceVersion,
            deviceId = resp.deviceId,
        )
    }

    // ---- HTTP ----

    // ---- WEB API (www.ottai.com/api/cgm/web) — true email login + in-app registration ----
    // The website's account API, fully recovered from its JS. Different profile from the watch
    // API: appName "ottai-seas", signature = md5(parts + SEED) (no deviceId-prefix), and the
    // sensitive fields are AES-256-ECB-encrypted into "encryptInfo". The GuestToken header
    // signature is NOT required (verified). We use this because the account's login username is
    // a server-assigned random string (e.g. "test234123154") — the EMAIL is the only identifier
    // a user knows — and so users can register without installing the vendor app.

    private const val WEB_BASE = "https://www.ottai.com/api/cgm/web"
    private const val WEB_APP = "ottai-seas"
    private const val WEB_DEVICE_ID = "8"  // sent as the deviceId header + used in the mail/login body sig (any stable value)
    private const val WEB_AES_KEY = "miH5ngQ7z4NZU3JgZFq87Gg6v1Y7YJm9"  // web __ENV NEXT_PUBLIC_AES_KEY (AES-256)
    private const val WEB_FINGERPRINT = "0123456789abcdef0123456789abcdef01234567"  // opaque client fp (not validated)

    // Syai is a different web profile that shares only the seed and
    // AES key with Ottai. appName=cgm, US/Americas/Android/v5, no deviceId header; login uses
    // /user/mail/login (not thirdLoginByPassword). Verified against a real syai.com login capture.
    private const val WEB_APP_SYAI = "cgm"
    private const val WEB_FINGERPRINT_SYAI = "90507337afdab98e443d3ec8fcccb672"

    private fun isSyai(webBase: String): Boolean = webBase.contains("syai")
    private fun webAppFor(webBase: String): String = if (isSyai(webBase)) WEB_APP_SYAI else WEB_APP

    private fun webSign(vararg parts: String): String = md5Hex(parts.joinToString("") + SEED)

    private fun webHeaders(webBase: String, ts: Long): Map<String, String> =
        if (isSyai(webBase)) mapOf(
            "appName" to WEB_APP_SYAI,
            "timestamp" to ts.toString(),
            "deviceFingerprinting" to WEB_FINGERPRINT_SYAI,
            "country" to "US",
            "region" to "Americas",
            "ua" to "Android",
            "versionCode" to "5",
            "traceId" to "trace_$ts",
            "language" to "en",
            "timezone" to "-18000",
        ) else mapOf(
            "appName" to WEB_APP,
            "timestamp" to ts.toString(),
            "deviceId" to WEB_DEVICE_ID,
            "deviceFingerprinting" to WEB_FINGERPRINT,
            "region" to "Europe",
            "ua" to "web",
            "versionCode" to "253201",
            "traceId" to "trace_$ts",
            "language" to "en",
            "timezone" to "0",
            "X-Canary-Mode" to "OFF",
            "country" to "RU",
        )

    /** AES-256-ECB/PKCS5 of the plaintext JSON, base64 — the web "encryptInfo" field. */
    private fun webEncrypt(plainJson: String): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(WEB_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
        )
        return android.util.Base64.encodeToString(
            cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }

    private fun getWebApiToken(webBase: String): String? {
        // The apiToken endpoint throttles rapid hits (returns a null token); one retry after a
        // short pause covers a transient throttle so a single user tap doesn't fail spuriously.
        repeat(2) { attempt ->
            val ts = now()
            val resp = httpGet(
                "$webBase/user/apiToken",
                mapOf("signature" to webSign(webAppFor(webBase), ts.toString())),
                webHeaders(webBase, ts),
            )
            val tok = resp?.optStringDeep("data")
            if (!tok.isNullOrBlank()) return tok
            if (attempt == 0) runCatching { Thread.sleep(900) }
        }
        return null
    }

    /**
     * POST a web-API call, retrying the whole flow (fresh apiToken each time) on
     * User_SignatureInvalid. The www host is load-balanced and an apiToken issued by one node is
     * sometimes not yet known to the node serving the POST → a spurious SignatureInvalid; a retry
     * lands on a consistent node. Returns the response (whatever biz code) or null if never valid.
     */
    private fun webPostRetry(webBase: String, path: String, buildBody: (apiToken: String, ts: Long) -> JSONObject): JSONObject? {
        repeat(5) { attempt ->
            val apiToken = getWebApiToken(webBase) ?: run { lastFailure = CloudFailure("apiToken failed"); return null }
            val ts = now()
            val resp = httpPostJson("$webBase$path", buildBody(apiToken, ts).toString(), webHeaders(webBase, ts)) ?: return null
            if (!resp.optString("code").equals("User_SignatureInvalid", ignoreCase = true)) return resp
            if (attempt < 4) runCatching { Thread.sleep(500) }
        }
        return null
    }

    /**
     * Email + password login via the website API. The endpoint differs by region:
     *  - Ottai: `/user/login/thirdLoginByPassword` (accepts the email OR the username in the
     *    encrypted "username" field). sig = md5(appName+deviceId+ts+apiToken+identifier+password+SEED) —
     *    deviceId BEFORE ts. (A wrong order here returns the MISLEADING "User_FAILED_RETRY_TIMES".)
     *  - syai: `/user/mail/login` with a plaintext "email" field + encryptInfo={email,password};
     *    sig = md5(appName+ts+apiToken+email+password+SEED) (no deviceId). Its JWT is upgraded to
     *    a mobile session below; only that session contains the sensor-material decryption root.
     */
    fun mailLogin(ctx: Context, email: String, password: String, webBase: String = WEB_BASE): LoginResult? {
        val em = email.trim()
        if (em.isBlank() || password.isBlank()) { lastFailure = CloudFailure("email/password required"); return null }
        val resp = if (isSyai(webBase)) {
            val encInfo = webEncrypt(JSONObject().put("email", em).put("password", password).toString())
            webPostRetry(webBase, "/user/mail/login") { apiToken, ts ->
                JSONObject().apply {
                    put("encryptInfo", encInfo)
                    put("email", em)
                    put("apiToken", apiToken)
                    put("signature", webSign(WEB_APP_SYAI, ts.toString(), apiToken, em, password))
                }
            }
        } else {
            val encInfo = webEncrypt(JSONObject().put("username", em).put("password", password).toString())
            webPostRetry(webBase, "/user/login/thirdLoginByPassword") { apiToken, ts ->
                JSONObject().apply {
                    put("uuid", java.util.UUID.randomUUID().toString().replace("-", ""))
                    put("encryptInfo", encInfo)
                    put("apiToken", apiToken)
                    put("source", 5)
                    put("signature", webSign(WEB_APP, WEB_DEVICE_ID, ts.toString(), apiToken, em, password))
                }
            }
        } ?: return null
        return upgradeWebSession(ctx, resp, password, webBase)
    }

    /**
     * Turn a web-API login/sign-up response into the mobile session every CGM call needs.
     *
     * A web JWT has no device scope: the mobile API answers every /deviceBind and /device call
     * with AuthFailed_TokenInvalid (seen end-to-end on 2026-09-18 right after a sign-up). The
     * only session that works is accountLogin's, and accountLogin keys on the account's
     * server-assigned random username, which only the profile endpoint exposes. So: getUser with
     * the web JWT → username → accountLogin(username, password).
     *
     * The chain never falls back to persisting the web JWT: that produced a "signed in" account
     * on which nothing worked, and users read it as email login being broken. When a step fails
     * the account stays signed out and [lastFailure] says which step.
     *
     * The www host is load-balanced and a JWT issued by one node may not be known to the node
     * serving the next request (the same race [webPostRetry] handles for apiToken), so the
     * profile lookup and a token-rejected accountLogin are retried; a wrong password is not.
     */
    private fun upgradeWebSession(ctx: Context, resp: JSONObject, password: String, webBase: String): LoginResult? {
        val webToken = (resp.optJSONObject("data") ?: resp.optJSONObject("result"))
            ?.optString("accessToken").orEmptyIfNull()
        if (webToken.isBlank()) {
            // request() already captured the backend's business error, if any.
            if (lastError.isBlank()) lastFailure = CloudFailure("web login returned no session")
            return null
        }
        val mobileBase = webBaseToMobile(webBase)
        // Syai's web profile omits both userName and glucoseSecretKey. Its mobile profile accepts
        // the web JWT and exposes the server-assigned userName; Ottai's web profile does too.
        var userName: String? = null
        repeat(WEB_UPGRADE_ATTEMPTS) { attempt ->
            val profile = if (isSyai(webBase)) {
                mobileGetUser(ctx, mobileBase, webToken)
            } else {
                webGetUser(webBase, webToken)
            }
            userName = profile?.optString("userName").orEmptyIfNull().takeIf { it.isNotBlank() }
            if (userName != null) return@repeat
            if (profile != null) {
                lastFailure = CloudFailure("account profile has no username")
                return null
            }
            if (attempt < WEB_UPGRADE_ATTEMPTS - 1) runCatching { Thread.sleep(WEB_UPGRADE_RETRY_MS) }
        }
        val name = userName ?: run {
            if (lastError.isBlank()) lastFailure = CloudFailure("account profile lookup failed")
            return null
        }
        // First with the web JWT as Authorization (the shape verified for Syai), then, if the
        // mobile side rejects that token rather than the password, once with no Authorization at
        // all (the shape the Ottai global upgrade originally shipped with). A rejected token is
        // not a failed-login attempt, so this cannot count towards a lockout.
        passwordLogin(ctx, name, password, mobileBase, authorizationOverride = webToken)
            ?.takeIf { it.ok }?.let { return it }
        if (lastFailure?.isTokenInvalid == true) {
            passwordLogin(ctx, name, password, mobileBase, authorizationOverride = "")
                ?.takeIf { it.ok }?.let { return it }
        }
        if (lastError.isBlank()) lastFailure = CloudFailure("mobile login upgrade failed")
        return null
    }

    private const val WEB_UPGRADE_ATTEMPTS = 3
    private const val WEB_UPGRADE_RETRY_MS = 500L

    /** POST /user/mail/sendMail — emails a verification code, returns the requestId. type: SIGN_UP/LOGIN/RESET_PASSWORD. */
    fun sendMail(email: String, type: String = "SIGN_UP", webBase: String = WEB_BASE): String? {
        val em = email.trim()
        val resp = webPostRetry(webBase, "/user/mail/sendMail") { apiToken, ts ->
            JSONObject().apply {
                put("type", type)
                put("isSend", 1)
                put("email", em)
                put("apiToken", apiToken)
                put("signature", webSign(webAppFor(webBase), ts.toString(), em, apiToken))
            }
        } ?: return null
        // Success = code "OK"; the requestId for signUp is data.key. Anything else
        // (e.g. USER_REGISTERED for an existing email) -> null, with lastError already set.
        if (!resp.optString("code").equals("OK", ignoreCase = true)) return null
        return resp.optJSONObject("data")?.optString("key").orEmptyIfNull().takeIf { it.isNotBlank() }
    }

    /**
     * POST /user/mail/signUp — register (email + emailed code + password + display name), then
     * upgrade the fresh web session to a mobile one exactly like [mailLogin]. Persists creds.
     */
    fun signUp(ctx: Context, email: String, password: String, profileName: String, requestId: String, validCode: String, webBase: String = WEB_BASE): LoginResult? {
        val em = email.trim()
        if (isSyai(webBase)) return syaiSignUp(ctx, em, password, requestId, validCode, webBase)
        val encInfo = webEncrypt(
            JSONObject().put("email", em).put("password", password).put("profileName", profileName).toString(),
        )
        val resp = webPostRetry(webBase, "/user/mail/signUp") { apiToken, ts ->
            JSONObject().apply {
                put("apiToken", apiToken)
                put("encryptInfo", encInfo)
                put("requestId", requestId)
                put("validCode", validCode)
                put("recommendFlag", false)
                put("country", "RU")
                put("language", "en")
                put("signature", webSign(WEB_APP, ts.toString(), requestId, em, validCode))
            }
        } ?: return null
        return upgradeWebSession(ctx, resp, password, webBase)
    }

    /**
     * syai registration is a 3-step flow (vs Ottai's 2-step) and has no display name: sendMail (the
     * caller already did this for the requestId) -> verifyMail (plaintext {validCode} -> activates the
     * requestId) -> signUp (encryptInfo={email,password}, the password is in the sig, no validCode).
     * Shapes derived from syai's web chunks; NOT round-trip-verified (disposable mailboxes don't
     * receive syai's codes), so this is best-effort until tested on a real account.
     */
    private fun syaiSignUp(ctx: Context, em: String, password: String, requestId: String, validCode: String, webBase: String): LoginResult? {
        // 1) verify the emailed code — plaintext body, code in the signature.
        val verify = webPostRetry(webBase, "/user/mail/verifyMail") { _, ts ->
            JSONObject().apply {
                put("type", "SIGN_UP")
                put("validCode", validCode)
                put("requestId", requestId)
                put("email", em)
                put("signature", webSign(WEB_APP_SYAI, ts.toString(), requestId, em, validCode))
            }
        }
        if (verify == null || !verify.optString("code").equals("OK", ignoreCase = true)) return null
        // 2) complete registration — encryptInfo={email,password}; the sig is requestId+email ONLY
        // (the password rides inside encryptInfo, NOT the signature — verified empirically: a sig with
        // the password gives SignatureInvalid, while requestId+email gets past it).
        val encInfo = webEncrypt(JSONObject().put("email", em).put("password", password).toString())
        val resp = webPostRetry(webBase, "/user/mail/signUp") { _, ts ->
            JSONObject().apply {
                put("encryptInfo", encInfo)
                put("requestId", requestId)
                put("signature", webSign(WEB_APP_SYAI, ts.toString(), requestId, em))
            }
        } ?: return null
        return upgradeWebSession(ctx, resp, password, webBase)
    }

    /** Map a web API host to the matching mobile CGM API base for subsequent validate/list calls. */
    internal fun webBaseToMobile(webBase: String): String =
        if (webBase.contains("syai")) OttaiConstants.API_BASE_SYAI else OttaiConstants.API_BASE_GLOBAL

    /** GET /user/getUser (JWT bearer) → the user data object (glucoseSecretKey, userName, email, …). */
    private fun webGetUser(webBase: String, accessToken: String): JSONObject? {
        val ts = now()
        val headers = webGetUserHeaders(webBase, ts, accessToken)
        val resp = httpGet("$webBase/user/getUser", emptyMap(), headers) ?: return null
        return resp.optJSONObject("data") ?: resp.optJSONObject("result")
    }

    /** Mobile GET /user/getUser using a just-issued web JWT, before it is persisted. */
    private fun mobileGetUser(ctx: Context, mobileBase: String, accessToken: String): JSONObject? {
        val ts = now()
        val resp = httpGet(
            mobileBase + OttaiConstants.EP_GET_USER,
            emptyMap(),
            headers(ctx, ts, mobileBase, accessToken),
        ) ?: return null
        return resp.optJSONObject("data") ?: resp.optJSONObject("result")
    }

    internal fun webGetUserHeaders(webBase: String, ts: Long, accessToken: String): Map<String, String> =
        webHeaders(webBase, ts) +
            (if (isSyai(webBase)) mapOf("deviceId" to WEB_DEVICE_ID) else emptyMap()) +
            ("Authorization" to "Bearer $accessToken")

    private fun httpGet(base: String, query: Map<String, String>, headers: Map<String, String>): JSONObject? =
        httpGetWithResult(base, query, headers).body

    private fun httpGetWithResult(base: String, query: Map<String, String>, headers: Map<String, String>): CloudRequestResult {
        val qs = query.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val url = if (qs.isEmpty()) base else "$base?$qs"
        return requestWithResult("GET", url, null, headers)
    }

    private fun httpPostJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int = TIMEOUT_MS): JSONObject? =
        request("POST", url, body, headers + ("Content-Type" to "application/json;charset=UTF-8"), timeoutMs)

    private fun httpPutJsonWithResult(url: String, body: String, headers: Map<String, String>): CloudRequestResult =
        requestWithResult(
            TEMPORARY_MATERIAL_UNBIND_METHOD,
            url,
            body,
            headers + ("Content-Type" to "application/json;charset=UTF-8"),
        )

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Int = TIMEOUT_MS): JSONObject? =
        requestWithResult(method, url, body, headers, timeoutMs).body

    internal fun requestWithResult(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Int = TIMEOUT_MS,
        openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    ): CloudRequestResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(url).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = timeoutMs
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setRequestProperty("Content-Length", bytes.size.toString())
                    outputStream.use { it.write(bytes) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val path = url.substringBefore('?').substringAfterLast("/server")
            val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
            // Capture non-secret business error (code/message) for the UI/logs.
            val bizCode = json?.opt("code")?.toString().orEmpty()
            val bizMsg = (json?.opt("message") ?: json?.opt("msg") ?: json?.opt("detailMessage"))
                ?.toString().orEmpty().takeIf { it != "null" }.orEmpty()
            val bizOk = bizCode.isBlank() || bizCode == "200" || bizCode.equals("OK", ignoreCase = true)
            val failure = if (code !in 200..299 || !bizOk) {
                CloudFailure("http=$code biz=$bizCode ${bizMsg.take(120)}".trim(), bizCode)
            } else null
            // Keep the legacy diagnostic for older callers, but return this request's own value.
            lastFailure = failure
            if (failure != null) {
                Log.w(TAG, "$path -> ${failure.text}")
            } else {
                // Without this a successful cloud phase is invisible and reconstructable only from
                // the absence of warnings. Path and status ONLY: the body carries keyA and the
                // account glucoseSecretKey.
                Log.i(TAG, "$path -> http=$code")
            }
            CloudRequestResult(json, failure)
        } catch (t: Throwable) {
            val failure = CloudFailure("network: ${t.message}")
            lastFailure = failure
            Log.w(TAG, "request failed ${url.substringBefore('?')}: ${t.message}")
            CloudRequestResult(null, failure)
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun String?.orEmptyIfNull(): String = if (this == null || this == "null") "" else this

    private fun JSONObject.optStringDeep(key: String): String? {
        val direct = optString(key).orEmptyIfNull()
        if (direct.isNotBlank()) return direct
        return null
    }

    private fun JSONObject.optLongLoose(key: String): Long {
        if (!has(key) || isNull(key)) return 0L
        optLong(key, Long.MIN_VALUE).let { if (it != Long.MIN_VALUE) return it }
        return optString(key).orEmptyIfNull().toLongOrNull() ?: 0L
    }
}
