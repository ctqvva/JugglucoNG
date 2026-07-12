package tk.glucodata.drivers.ottai

import java.util.UUID

object OttaiConstants {

    const val TAG = "Ottai"
    const val DEFAULT_DISPLAY_NAME = "Ottai CGM"

    // ---- Cloud API ----
    const val API_BASE = "https://api.ottai.com"
    const val API_PATH_PREFIX = "/cgm/app/server"

    const val EP_API_TOKEN = "/user/apiToken"
    const val EP_SMS_CODE = "/user/smsCode"
    const val EP_SMS_LOGIN = "/user/smsLogin"
    const val EP_ACCOUNT_LOGIN = "/user/accountLogin"
    const val EP_GET_USER = "/user/getUser"
    const val EP_VALIDATE_DEVICE = "/device/validateDeviceByMacV2"
    const val EP_BIND_DEVICE = "/deviceBind/composite/bind"
    const val EP_GET_BIND_DEVICE = "/deviceBind/getBindDevice"
    const val EP_UNBIND_DEVICE = "/deviceBind/unBindDevice"
    const val EP_LIST_DEVICES = "/deviceBind/list"
    const val EP_DOWNLOAD_GLUCOSE = "/search/downloadGlucose"
    const val EP_SYNC_DATA = "/search/searchSyncData"
    const val EP_LOGOUT = "/user/logout"

    // ---- MD5 signature seed ----
    const val SIGN_SEED = "dy7234hbnrnfh7q89eru8ybfn899"
    const val SIGN_APP_PREFIX = "ottai-watch"

    // ---- BLE UUIDs ----

    val SERVICE_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAR_SOFTWARE_VERSION: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val CHAR_CURRENT_TIME: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    val CHAR_MAX_ACTIVE_TIME: UUID = UUID.fromString("b8fd9848-0ccd-423f-bd34-2419aa7ea004")
    val CHAR_CGM_INFO: UUID = UUID.fromString("cb627922-4e79-42e3-b107-a10e816f6caa")

    val SERVICE_CGM: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
    val CHAR_NEWEST_GLUCOSE: UUID = UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")
    val CHAR_HISTORY_REQUEST: UUID = UUID.fromString("ccecb015-6750-41fd-ba78-3fb77d350574")
    val CHAR_HISTORY_GLUCOSE: UUID = UUID.fromString("69e4f45f-a180-422c-83c0-324146402112")
    val CHAR_COMMAND: UUID = UUID.fromString("d78d0706-c775-448d-8a78-01215e7c2e11")

    val SERVICE_AUTH: UUID = UUID.fromString("e06e1d43-1319-4ebf-94b0-5b0e5313b1f4")
    val CHAR_DEVICE_AUTH_PARAM: UUID = UUID.fromString("86805092-92b5-4d8c-9d73-0785ff6f9147")
    val CHAR_APP_AUTH_PARAM: UUID = UUID.fromString("1756ef6e-884b-4eb0-b646-f04ab18408f9")
    val CHAR_AUTH_SIGNATURE: UUID = UUID.fromString("785022c6-08c0-48af-ad17-684bb889aa83")

    val SERVICE_DESTRUCTION: UUID = UUID.fromString("84c5b711-655a-460d-89ca-337dbc981857")
    val CHAR_DESTRUCTION_TIME: UUID = UUID.fromString("6AA799B6-B374-4148-8F36-6D440C0EC203")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEFAULT_MTU = 247

    // ---- Lifecycle defaults ----
    const val DEFAULT_ACTIVE_EXPIRE_MS: Long = 1296000000L // 15 days
    const val DEFAULT_RETAIN_TIME_MS: Long = 172800000L // 2 days
    const val DEFAULT_WARMUP_SECONDS = 3200
    const val CGM_INFO_POLL_INTERVAL_DEFAULT_SECONDS = 15

    // ---- Scan filter ----
    const val SCAN_DURATION_MS: Long = 10000L
    const val MIN_RSSI_DBM = -70

    // ---- Command values ----
    const val CMD_ACTIVATE: Byte = 0x03
    const val CMD_STATUS_RUNNING: Byte = 3
    const val CMD_STATUS_EXPIRED: Byte = 4

    // ---- Auth key count ----
    const val AUTH_KEY_COUNT = 6
    const val AUTH_KEY_HEX_LEN = 192
    const val AUTH_KEY_BYTES = 16

    // ---- ECDH ----
    const val ECDH_CURVE = "secp256r1"

    // ---- History request limits ----
    const val HISTORY_MAX_COUNT = 20160
    const val HISTORY_GAP_WINDOW = 270

    // ---- Formula ----
    const val FORMULA_MIN_CLAMP = 0.1f

    // ---- SharedPreferences keys ----
    const val PREF_SENSORS_KEY = "ottai_sensors"
    const val PREF_TOKEN_PREFIX = "ottai_token_"
    const val PREF_GSK_PREFIX = "ottai_gsk_"
    const val PREF_USER_ID_PREFIX = "ottai_user_id_"
    const val PREF_AUTH_KEYS_PREFIX = "ottai_authkeys_"
    const val PREF_METHOD_PREFIX = "ottai_method_"
    const val PREF_COEFFICIENT_PREFIX = "ottai_coeff_"
    const val PREF_DEVICE_VERSION_PREFIX = "ottai_devver_"
    const val PREF_ACTIVE_TIME_PREFIX = "ottai_activetime_"
    const val PREF_ACTIVE_EXPIRE_TIME_PREFIX = "ottai_expiretime_"
    const val PREF_RETAIN_TIME_PREFIX = "ottai_retaintime_"
    const val PREF_PRODUCE_TIME_PREFIX = "ottai_producetime_"
    const val PREF_METHOD_UPDATE_TIME_PREFIX = "ottai_methodupd_"
    const val PREF_COEFF_UPDATE_TIME_PREFIX = "ottai_coeffupd_"
    const val PREF_SESSION_KEY_PREFIX = "ottai_sessionkey_"
    const val PREF_LAST_DATA_NO_PREFIX = "ottai_lastdno_"
    const val PREF_API_BASE_PREFIX = "ottai_apibase_"

    // ---- Derived key helpers ----
    @JvmStatic
    fun deriveFieldKey(glucoseSecretKey: String, timestampMs: Long, mac: String): ByteArray {
        val ts = timestampMs.toString()
        val suffix = ts.takeLast(10) + mac.takeLast(6)
        return (glucoseSecretKey + suffix).toByteArray(Charsets.UTF_8)
    }

    @JvmStatic
    fun deriveMethodKey(secretKey: String, methodUpdateTime: Long, mac: String): ByteArray =
        deriveFieldKey(secretKey, methodUpdateTime, mac)

    @JvmStatic
    fun deriveCoefficientKey(secretKey: String, coeffUpdateTime: Long, mac: String): ByteArray =
        deriveFieldKey(secretKey, coeffUpdateTime, mac)
}
