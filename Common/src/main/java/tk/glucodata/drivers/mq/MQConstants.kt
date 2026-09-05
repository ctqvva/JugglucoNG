// JugglucoNG — MQ/Glutec CGM Driver
// MQConstants.kt — Nordic UART Service UUIDs, packet header, command bytes
//
// Protocol: Nordic UART Service (NUS) frames in the form
//   {0x5A, 0xA5, CMD, LEN, ...payload..., CRC_HI, CRC_LO}
// No encryption. CRC16-Modbus (poly 0xA001).
// Separate transmitter + disposable sensor patch.
//
// Reverse-engineered from MQ/Glutec app (com.ruapp.glutec v1.0.1.6).

package tk.glucodata.drivers.mq

import java.util.Locale
import java.util.UUID

data class MQVendorEndpoints(
    val baseUrl: String,
    val loginWithPasswordUrl: String,
    val loginByCodeUrl: String,
    val registerByCodeUrl: String,
    val getVerifyCodeUrl: String,
    val resetPasswordByCodeUrl: String,
    val isAccountExistUrl: String,
    val checkTokenUrl: String,
    val logoutUrl: String,
    val qrCodeEffectiveUrl: String,
    val findByBleIdUrl: String,
    val friendUserFindUrl: String,
    val addFriendUrl: String,
    val friendListUrl: String,
    val applyListUrl: String,
    val applyFriendListUrl: String,
    val reviewFriendUrl: String,
    val permissionUrl: String,
    val historyListUrl: String,
    val queryByIdUrl: String,
    val queryNewestUrl: String,
    val viewAllSnapshotDetailUrl: String,
    val timeBucketUrl: String,
    val dataRecordStartUrl: String,
    val dataRecordGoOnUrl: String,
    val dataRecordEndUrl: String,
    val dataRecordReportUrl: String,
    val dataRecordEventUrl: String,
    val dataRecordCalibrationTimeUrl: String,
)

object MQConstants {
    // Precompiled once. These were built inline on every call, and because
    // SensorIdentity.matches -> managedMatches asks every driver adapter to
    // canonicalise both ids, a single identity comparison compiled a fresh
    // java.util.regex.Pattern (ICU native) per adapter per side. A stuck-main-thread
    // stack dump landed in Pattern.compile beneath canonicalSensorId, and that path
    // runs per reading, per sensor, per UI snapshot and per notification build.
    private val MAC_WITH_COLONS: Regex = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)
    private val PLAIN_HEX_12_16: Regex = Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE)
    private val PLAIN_HEX_12_16_CASE_SENSITIVE: Regex = Regex("^[0-9A-F]{12,16}$")
    private val HEX_RUN: Regex = Regex("[0-9A-F]+", RegexOption.IGNORE_CASE)
    private val PLAIN_HEX_6_11: Regex = Regex("^[0-9A-F]{6,11}$", RegexOption.IGNORE_CASE)

    const val TAG = "MQ"
    const val DEFAULT_DISPLAY_NAME = "Glutec CGM"
    const val DEFAULT_FOLLOWER_DISPLAY_NAME = "Glutec Follower"
    const val PROVISIONAL_SENSOR_PREFIX = "MQ-"
    const val FOLLOWER_SENSOR_PREFIX = "MQF-"
    const val MAX_NATIVE_SENSOR_ID_CHARS = 16

    // ---- BLE UUIDs (Nordic UART Service) ----

    /** Nordic UART Service */
    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** TX characteristic (transmitter → phone, NOTIFY). */
    val NUS_TX_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** RX characteristic (phone → transmitter, WRITE / WRITE_NO_RESPONSE). */
    val NUS_RX_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Client Characteristic Configuration Descriptor (standard). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- Packet framing ----

    const val HEADER0: Byte = 0x5A
    const val HEADER1: Byte = -0x5B // 0xA5
    val HEADER: ByteArray = byteArrayOf(HEADER0, HEADER1)

    /** Fixed overhead bytes per framed packet: 2 header + 1 cmd + 1 len + 2 CRC. */
    const val FRAME_OVERHEAD = 6

    /** Offsets within a framed packet. */
    const val OFFSET_CMD = 2
    const val OFFSET_LEN = 3
    const val OFFSET_PAYLOAD = 4

    // ---- Commands (byte[2] in every framed packet) ----

    /** TX → RX heartbeat while the transmitter has no new data. */
    const val CMD_NOTIFY_WORKING: Byte = 0x01

    /** RX → TX acknowledgement of a NOTIFY_BG_DATA frame. */
    const val CMD_WRITE_BG_DATA_CONFIRM: Byte = 0x02

    /** RX → TX continue without re-initializing the transmitter. */
    const val CMD_WRITE_CONFIRM_WITHOUT_INIT: Byte = 0x03

    /** TX → RX blood-glucose data (one or more 6-byte records). */
    const val CMD_NOTIFY_BG_DATA: Byte = 0x04

    /** TX → RX "BG data acknowledged, session continuing" marker. Observed
     *  after every successful 0x04 ack round-trip (21 occurrences in the
     *  official HCI log). Silent — no response required from us. */
    const val CMD_NOTIFY_BG_COMPLETE: Byte = 0x05

    /** TX → RX transmitter just started work (reset / fresh session). */
    const val CMD_NOTIFY_BEGIN_WORK: Byte = 0x06

    /** RX → TX acknowledge initialization phase. */
    const val CMD_WRITE_CONFIRM_WITH_INIT: Byte = 0x08

    /** RX → TX transmitter reset. */
    const val CMD_WRITE_CONFIRM_RESET: Byte = 0x11

    // ---- BG record layout (inside payload of 0x04 packets) ----
    //
    // Official service parse on W25101399:
    //   byte 0  : record marker (0x40 on observed BG records)
    //   byte 1  : packet index low byte
    //   byte 2  : packet index high byte
    //   byte 3  : sample current low byte
    //   byte 4  : sample current high byte
    //   byte 5  : battery percent
    //
    // Record count = LEN / 6. These records do NOT contain final glucose.

    const val BG_RECORD_SIZE = 6
    const val BG_RECORD_MARKER = 0x40
    const val BG_OFFSET_MARKER = 0
    const val BG_OFFSET_PACKET_LO = 1
    const val BG_OFFSET_PACKET_HI = 2
    const val BG_OFFSET_CURRENT_LO = 3
    const val BG_OFFSET_CURRENT_HI = 4
    const val BG_OFFSET_BATTERY = 5

    /** Low-battery warning threshold (percent). */
    const val BATTERY_WARN_PERCENT = 30

    // ---- Algorithm defaults / limits ----

    /** Lowest glucose (mmol/L) kept after offset subtraction; matches app. */
    const val ALGO_MMOL_FLOOR = 3.0

    /** Final output clamp (mmol/L × 10 units). App limits to [1.7, 40.0] mmol/L. */
    const val ALGO_MMOL_MIN_TIMES10 = 17.0
    const val ALGO_MMOL_MAX_TIMES10 = 400.0

    /** Unit conversion used when bridging vendor mmol data into the app's mg/dL path. */
    const val MMOL_TO_MGDL = 18.0182

    /** Warmup guard trigger: packet index >= 19. */
    const val ALGO_WARMUP_PACKET_THRESHOLD = 19.0

    /** Default multiplier from SystemInformation.GuardianFriendsID. */
    const val ALGO_DEFAULT_MULTIPLIER = 1.1

    /** Default packages-per-session from SystemInformation.AndroidVersion. */
    const val ALGO_DEFAULT_PACKAGES = 720

    /** Default init-time guard from the vendor service. */
    const val ALGO_DEFAULT_INIT_TIME_MINUTES = 24.0

    /** Default algorithm version from SystemInformation.spare03. */
    const val ALGO_DEFAULT_VERSION = 0

    /**
     * Official sensitivity / solved K values are in current-per-mmol space.
     * Sub-1 values are not physically plausible for the observed firmware and
     * are a strong indicator of bogus legacy fallback state.
     */
    const val ALGO_MIN_VALID_K = 1.0

    /** Default server-derived deviation if no server response is cached. */
    const val SERVER_DEFAULT_DEVIATION = 0

    /** Default server-derived transmitter10 flag. */
    const val SERVER_DEFAULT_TRANSMITTER10 = 0

    /** Default server-derived protocol type (1 = Protocol01, 2 = Protocol02). */
    const val SERVER_DEFAULT_PROTOCOL_TYPE = 1

    // ---- Lifetime / cadence ----

    /** Sensor rated lifetime in days. */
    const val DEFAULT_RATED_LIFETIME_DAYS = 16

    /** Reading cadence observed on W25101399 in field logs. */
    const val DEFAULT_READING_INTERVAL_MINUTES = 3

    /** Warmup window before reliable values. */
    const val DEFAULT_WARMUP_MINUTES = 60

    // ---- Vendor bootstrap endpoints ----

    const val VENDOR_BASE_URL = "https://monitor.glutec.ru/jeecg-boot/v3"
    private const val VENDOR_LOGIN_WITH_PASSWORD_PATH = "/user/monitorUser/loginWithPhoneAndPassword"
    private const val VENDOR_LOGIN_BY_CODE_PATH = "/user/monitorUser/loginByCode"
    private const val VENDOR_REGISTER_BY_CODE_PATH = "/user/monitorUser/registerByCode"
    private const val VENDOR_GET_VERIFY_CODE_PATH = "/user/monitorUser/getVerifyCode"
    private const val VENDOR_RESET_PASSWORD_BY_CODE_PATH = "/user/monitorUser/alterPasswordByCode"
    private const val VENDOR_IS_ACCOUNT_EXIST_PATH = "/user/monitorUser/isAccountExistNew"
    private const val VENDOR_CHECK_TOKEN_PATH = "/user/monitorUser/checkToken"
    private const val VENDOR_LOGOUT_PATH = "/user/monitorUser/logout"
    private const val VENDOR_QR_CODE_EFFECTIVE_PATH = "/user/monitorUser/checkQrCodeNew"
    private const val VENDOR_FIND_BY_BLE_ID_PATH = "/emitterBluetooth/findByBleId"
    private const val VENDOR_FRIEND_USER_FIND_PATH = "/user/friendUser/find"
    private const val VENDOR_ADD_FRIEND_PATH = "/user/friendUser/addFriend"
    private const val VENDOR_FRIEND_LIST_PATH = "/user/friendUser/friendList"
    private const val VENDOR_APPLY_LIST_PATH = "/user/friendUser/applyList"
    private const val VENDOR_APPLY_FRIEND_LIST_PATH = "/user/friendUser/applyFriendList"
    private const val VENDOR_REVIEW_FRIEND_PATH = "/user/friendUser/reviewFriend"
    private const val VENDOR_PERMISSION_PATH = "/user/friendUser/permission"
    private const val VENDOR_HISTORY_LIST_PATH = "/snapshot/snapshot/historyList"
    private const val VENDOR_QUERY_BY_ID_PATH = "/snapshot/snapshot/queryById"
    private const val VENDOR_QUERY_NEWEST_PATH = "/snapshot/snapshot/queryNewest"
    private const val VENDOR_VIEW_ALL_SNAPSHOT_DETAIL_PATH = "/databags/databags/viewAllAppSnapshotDetailCondense"
    private const val VENDOR_TIME_BUCKET_PATH = "/databags/databags/timeBucket"
    private const val VENDOR_DATA_RECORD_START_PATH = "/data/dataRecord/start"
    private const val VENDOR_DATA_RECORD_GO_ON_PATH = "/data/dataRecord/goOn"
    private const val VENDOR_DATA_RECORD_END_PATH = "/data/dataRecord/end"
    private const val VENDOR_DATA_RECORD_REPORT_PATH = "/data/dataRecord/report"
    private const val VENDOR_DATA_RECORD_EVENT_PATH = "/data/dataRecord/event"
    private const val VENDOR_DATA_RECORD_CALIBRATION_TIME_PATH = "/data/dataRecord/calibrationTime"
    const val VENDOR_TIMEOUT_MS = 5_000
    const val VENDOR_BOOTSTRAP_RETRY_MS = 15 * 60 * 1000L

    // ---- Device name patterns ----

    /**
     * Advertised device names we treat as MQ/Glutec.
     *
     * The app scans with the generic BLE scanner then disambiguates by NUS service
     * UUID. We keep a small list of known prefixes to support the sensor-picker
     * UI before the GATT service discovery finishes.
     */
    val KNOWN_PREFIXES = arrayOf("Glutec", "GLUTEC", "MQ", "RU-")


    @JvmStatic
    fun isMqDevice(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (KNOWN_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }) return true
        return isLikelyPersistedSensorName(trimmed)
    }

    @JvmStatic
    fun isLikelyPersistedSensorName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (isProvisionalSensorId(trimmed)) return true
        return MAC_WITH_COLONS.matches(trimmed) ||
            PLAIN_HEX_12_16.matches(trimmed)
    }

    @JvmStatic
    fun isProvisionalSensorId(name: String?): Boolean =
        name?.trim()?.startsWith(PROVISIONAL_SENSOR_PREFIX, ignoreCase = true) == true

    @JvmStatic
    fun isFollowerSensorId(name: String?): Boolean =
        name?.trim()?.startsWith(FOLLOWER_SENSOR_PREFIX, ignoreCase = true) == true

    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String {
        val trimmed = sensorId?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        // BLE MAC address — collapse separators and uppercase.
        if (MAC_WITH_COLONS.matches(trimmed)) {
            return trimmed.uppercase(Locale.US).replace(":", "")
        }
        // Already a canonical hex id.
        if (PLAIN_HEX_12_16.matches(trimmed)) {
            return trimmed.uppercase(Locale.US).take(MAX_NATIVE_SENSOR_ID_CHARS)
        }
        return trimmed
    }

    @JvmStatic
    fun deriveFollowerSensorId(account: String?): String {
        val normalized = account?.trim().orEmpty()
        val compact = normalized.uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(24)
            .ifEmpty {
                normalized.hashCode()
                    .toUInt()
                    .toString(16)
                    .uppercase(Locale.US)
            }
        return FOLLOWER_SENSOR_PREFIX + compact
    }

    @JvmStatic
    fun deriveInitialSensorId(
        deviceName: String?,
        address: String?,
        qrCodeContent: String? = null,
    ): String {
        qrCodeContent?.trim().orEmpty().takeIf { it.isNotEmpty() }?.let { qr ->
            val hex = HEX_RUN
                .findAll(qr.uppercase(Locale.US))
                .joinToString("") { it.value }
            if (hex.length in 12..MAX_NATIVE_SENSOR_ID_CHARS) return hex
        }
        val addressCanonical = canonicalSensorId(address)
        if (addressCanonical.isNotEmpty() &&
            PLAIN_HEX_12_16_CASE_SENSITIVE.matches(addressCanonical)
        ) {
            return addressCanonical
        }
        val fallback = deviceName?.trim().orEmpty()
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(MAX_NATIVE_SENSOR_ID_CHARS - PROVISIONAL_SENSOR_PREFIX.length)
            .ifEmpty { "PENDING" }
        return PROVISIONAL_SENSOR_PREFIX + fallback
    }

    @JvmStatic
    fun matchesCanonicalOrKnownNativeAlias(a: String?, b: String?): Boolean {
        val ca = canonicalSensorId(a)
        val cb = canonicalSensorId(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        if (ca.equals(cb, ignoreCase = true)) return true
        return isNativeShortAliasOf(ca, cb) || isNativeShortAliasOf(cb, ca)
    }

    /**
     * The native direct-stream shell may expose a suffix of the MQ id as the
     * current sensor name (observed: CFD8EBDDF969 -> BDDF969). Keep this
     * MQ-scoped and suffix-exact; generic app identity must not expand legacy
     * sensor names this way.
     */
    private fun isNativeShortAliasOf(canonical: String, alias: String): Boolean {
        if (!PLAIN_HEX_12_16.matches(canonical)) {
            return false
        }
        if (!PLAIN_HEX_6_11.matches(alias)) {
            return false
        }
        return canonical.endsWith(alias, ignoreCase = true)
    }

    @JvmStatic
    fun normalizeVendorBaseUrl(baseUrl: String?): String? {
        val trimmed = baseUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        var normalized = trimmed
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            normalized = "https://$normalized"
        }
        normalized = normalized.trimEnd('/')
        return when {
            normalized.endsWith("/jeecg-boot/v3", ignoreCase = true) -> normalized
            normalized.endsWith("/jeecg-boot", ignoreCase = true) -> "$normalized/v3"
            normalized.substringAfter("://", "").substringAfter('/', "").isBlank() ->
                "$normalized/jeecg-boot/v3"
            else -> normalized
        }
    }

    @JvmStatic
    fun vendorEndpoints(baseUrl: String?): MQVendorEndpoints {
        val resolvedBaseUrl = normalizeVendorBaseUrl(baseUrl) ?: VENDOR_BASE_URL
        return MQVendorEndpoints(
            baseUrl = resolvedBaseUrl,
            loginWithPasswordUrl = resolvedBaseUrl + VENDOR_LOGIN_WITH_PASSWORD_PATH,
            loginByCodeUrl = resolvedBaseUrl + VENDOR_LOGIN_BY_CODE_PATH,
            registerByCodeUrl = resolvedBaseUrl + VENDOR_REGISTER_BY_CODE_PATH,
            getVerifyCodeUrl = resolvedBaseUrl + VENDOR_GET_VERIFY_CODE_PATH,
            resetPasswordByCodeUrl = resolvedBaseUrl + VENDOR_RESET_PASSWORD_BY_CODE_PATH,
            isAccountExistUrl = resolvedBaseUrl + VENDOR_IS_ACCOUNT_EXIST_PATH,
            checkTokenUrl = resolvedBaseUrl + VENDOR_CHECK_TOKEN_PATH,
            logoutUrl = resolvedBaseUrl + VENDOR_LOGOUT_PATH,
            qrCodeEffectiveUrl = resolvedBaseUrl + VENDOR_QR_CODE_EFFECTIVE_PATH,
            findByBleIdUrl = resolvedBaseUrl + VENDOR_FIND_BY_BLE_ID_PATH,
            friendUserFindUrl = resolvedBaseUrl + VENDOR_FRIEND_USER_FIND_PATH,
            addFriendUrl = resolvedBaseUrl + VENDOR_ADD_FRIEND_PATH,
            friendListUrl = resolvedBaseUrl + VENDOR_FRIEND_LIST_PATH,
            applyListUrl = resolvedBaseUrl + VENDOR_APPLY_LIST_PATH,
            applyFriendListUrl = resolvedBaseUrl + VENDOR_APPLY_FRIEND_LIST_PATH,
            reviewFriendUrl = resolvedBaseUrl + VENDOR_REVIEW_FRIEND_PATH,
            permissionUrl = resolvedBaseUrl + VENDOR_PERMISSION_PATH,
            historyListUrl = resolvedBaseUrl + VENDOR_HISTORY_LIST_PATH,
            queryByIdUrl = resolvedBaseUrl + VENDOR_QUERY_BY_ID_PATH,
            queryNewestUrl = resolvedBaseUrl + VENDOR_QUERY_NEWEST_PATH,
            viewAllSnapshotDetailUrl = resolvedBaseUrl + VENDOR_VIEW_ALL_SNAPSHOT_DETAIL_PATH,
            timeBucketUrl = resolvedBaseUrl + VENDOR_TIME_BUCKET_PATH,
            dataRecordStartUrl = resolvedBaseUrl + VENDOR_DATA_RECORD_START_PATH,
            dataRecordGoOnUrl = resolvedBaseUrl + VENDOR_DATA_RECORD_GO_ON_PATH,
            dataRecordEndUrl = resolvedBaseUrl + VENDOR_DATA_RECORD_END_PATH,
            dataRecordReportUrl = resolvedBaseUrl + VENDOR_DATA_RECORD_REPORT_PATH,
            dataRecordEventUrl = resolvedBaseUrl + VENDOR_DATA_RECORD_EVENT_PATH,
            dataRecordCalibrationTimeUrl = resolvedBaseUrl + VENDOR_DATA_RECORD_CALIBRATION_TIME_PATH,
        )
    }

    // ---- SharedPreferences keys ----

    const val PREF_SENSORS_KEY = "mq_sensors"
    const val PREF_PROTOCOL_TYPE_PREFIX = "mq_protocol_type_"
    const val PREF_DEVIATION_PREFIX = "mq_deviation_"
    const val PREF_TRANSMITTER10_PREFIX = "mq_transmitter10_"
    const val PREF_WARMUP_STARTED_AT_PREFIX = "mq_warmup_started_at_"
    const val PREF_SENSOR_START_AT_PREFIX = "mq_sensor_start_at_"
    const val PREF_K_VALUE_PREFIX = "mq_k_value_"
    const val PREF_B_VALUE_PREFIX = "mq_b_value_"
    const val PREF_SENSITIVITY_PREFIX = "mq_sensitivity_"
    const val PREF_ALGORITHM_VERSION_PREFIX = "mq_algorithm_version_"
    const val PREF_MULTIPLIER_PREFIX = "mq_multiplier_"
    const val PREF_PACKAGES_PREFIX = "mq_packages_"
    const val PREF_LAST_PROCESSED_PREFIX = "mq_last_processed_"
    const val PREF_LAST_PACKET_INDEX_PREFIX = "mq_last_packet_"
    const val PREF_LAST_CLOUD_REPORTED_PACKET_PREFIX = "mq_last_cloud_reported_packet_"
    const val PREF_SNAPSHOT_ID_PREFIX = "mq_snapshot_id_"
    const val PREF_LOCAL_RESET_PENDING_PREFIX = "mq_local_reset_pending_"
    const val PREF_QR_CONTENT_PREFIX = "mq_qr_content_"
    const val PREF_AUTH_TOKEN_KEY = "mq_auth_token"
    const val PREF_AUTH_PHONE_KEY = "mq_auth_phone"
    const val PREF_AUTH_PASSWORD_KEY = "mq_auth_password"
    const val PREF_API_BASE_URL_KEY = "mq_api_base_url"
    const val PREF_CLOUD_SYNC_ENABLED_KEY = "mq_cloud_sync_enabled"
    const val PREF_FOLLOWER_ENABLED_KEY = "mq_follower_enabled"
    const val PREF_FOLLOWER_ACCOUNT_KEY = "mq_follower_account"
}
