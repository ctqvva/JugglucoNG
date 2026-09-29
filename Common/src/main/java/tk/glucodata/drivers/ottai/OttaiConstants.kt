// JugglucoNG — Ottai (Chinese-market CGM) driver
// OttaiConstants.kt — BLE UUIDs, cloud endpoints/headers, prefs keys, identity.
//
// See AGENTS/ottai-protocol.md + AGENTS/ottai-phase0-confirmed.md. The cloud
// "mac" is a 12-hex logical id used for server calls and BLE auth signatures; it
// is not necessarily the Android BLE address used for scanning/connecting.

package tk.glucodata.drivers.ottai

import java.util.Locale
import java.util.UUID

object OttaiConstants {

    const val TAG = "Ottai"
    const val DEFAULT_DISPLAY_NAME = "Ottai CGM"
    const val PROVISIONAL_SENSOR_PREFIX = "OTTAI-"
    const val MAX_NATIVE_SENSOR_ID_CHARS = 16

    // ---- BLE services / characteristics ----

    val SERVICE_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAR_SOFTWARE_VERSION: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val CHAR_CURRENT_TIME: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    val CHAR_MAX_ACTIVE_TIME: UUID = UUID.fromString("b8fd9848-0ccd-423f-bd34-2419aa7ea004")
    val CHAR_CGM_INFO_NOTIFY: UUID = UUID.fromString("cb627922-4e79-42e3-b107-a10e816f6caa")

    val SERVICE_CGM: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
    val CHAR_GLUCOSE_LIVE: UUID = UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")
    val CHAR_HISTORY_REQUEST: UUID = UUID.fromString("ccecb015-6750-41fd-ba78-3fb77d350574")
    val CHAR_GLUCOSE_HISTORY: UUID = UUID.fromString("69e4f45f-a180-422c-83c0-324146402112")
    val CHAR_COMMAND: UUID = UUID.fromString("d78d0706-c775-448d-8a78-01215e7c2e11")

    val SERVICE_AUTH: UUID = UUID.fromString("e06e1d43-1319-4ebf-94b0-5b0e5313b1f4")
    val CHAR_AUTH_DEVICE_PARAM: UUID = UUID.fromString("86805092-92b5-4d8c-9d73-0785ff6f9147")
    val CHAR_AUTH_APP_PARAM: UUID = UUID.fromString("1756ef6e-884b-4eb0-b646-f04ab18408f9")
    val CHAR_AUTH_SIGN: UUID = UUID.fromString("785022c6-08c0-48af-ad17-684bb889aa83")

    /** Official activation retain/destruction service. Only used during gated activation. */
    val SERVICE_DESTRUCTIVE: UUID = UUID.fromString("84c5b711-655a-460d-89ca-337dbc981857")
    val CHAR_DESTRUCTIVE: UUID = UUID.fromString("6aa799b6-b374-4148-8f36-6d440c0ec203")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Activate command base byte (gets zero-padded + AES-encrypted with session key). */
    const val ACTIVATE_CMD: Byte = 0x03

    // ---- Cloud ----

    const val API_BASE = "https://api.ottai.com"          // CN app (phone/SMS account)
    const val API_BASE_GLOBAL = "https://seas.ottai.com"  // Ottai global app com.ottai.seas (username/password); same API, different host
    const val API_BASE_SYAI = "https://api.syai.com"      // Syai global mobile API
    internal const val API_BASE_SYAI_LEGACY = "https://ru.syai.com"
    // Website account API (email login + registration). Same scheme/appName/SEED, different host.
    const val WEB_BASE_OTTAI = "https://www.ottai.com/api/cgm/web"
    const val WEB_BASE_SYAI = "https://www.syai.com/api/cgm/web"
    const val PREFIX = "/cgm/app/server"

    const val EP_API_TOKEN = "$PREFIX/user/apiToken"
    const val EP_SMS_CODE = "$PREFIX/user/smsCode"
    const val EP_SMS_LOGIN = "$PREFIX/user/smsLogin"
    const val EP_ACCOUNT_LOGIN = "$PREFIX/user/accountLogin"   // global app: account/email + password (probe-confirmed endpoint)
    const val EP_LOGOUT = "$PREFIX/user/logout"
    const val EP_GET_USER = "$PREFIX/user/getUser"
    const val EP_VALIDATE_BY_MAC = "$PREFIX/device/validateDeviceByMacV2"
    const val EP_VALIDATE_BY_MAC_V3 = "$PREFIX/device/validateDeviceByMacV3"
    const val EP_BIND = "$PREFIX/deviceBind/composite/bind"
    const val EP_BIND_V3 = "$PREFIX/deviceBind/composite/bindV3"
    // Server side of the CN V3 Active_Auth handshake: the app reads authDev/authFlag off the sensor
    // over BLE, posts them here, and the server returns authHost/authFlag to write back to the
    // sensor before bindV3. Exact request/response shape pending a live capture — see
    // AGENTS/archive/context-dumps/ottai-2026-08-12-cloud-gate-findings.md.
    const val EP_CGM_AUTH_VERIFY = "$PREFIX/cgmAuth/verify"
    const val EP_UNBIND = "$PREFIX/deviceBind/unBindDevice"
    const val EP_GET_BIND_DEVICE = "$PREFIX/deviceBind/getBindDevice"
    const val EP_DEVICE_LIST = "$PREFIX/deviceBind/list"  // account's bound + past sensors (paged)
    const val EP_DOWNLOAD_GLUCOSE = "$PREFIX/search/downloadGlucose"

    /**
     * Default phone-app headers (matches the working bind helper). The four
     * forwarded-IP headers spoof a China source IP so the server's geolocation
     * returns chinaFlag=1 and the smuggling notice never triggers — no VPN.
     */
    const val HEADER_APP_NAME = "ottai"
    const val HEADER_PACKAGE = "com.ottai.tag"
    const val HEADER_APP_TYPE = "ottai_main"
    const val CN_FORWARD_IP = "114.114.114.114"

    // ---- Identity / matching ----

    private val MAC_COLON = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)
    private val MAC_PLAIN = Regex("^[0-9A-F]{12}$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun isProvisionalSensorId(name: String?): Boolean =
        name?.trim()?.startsWith(PROVISIONAL_SENSOR_PREFIX, ignoreCase = true) == true

    /** Canonical cloud device id: 12 uppercase hex, no separators. */
    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String {
        val trimmed = sensorId?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (MAC_COLON.matches(trimmed)) return trimmed.uppercase(Locale.US).replace(":", "")
        if (MAC_PLAIN.matches(trimmed)) return trimmed.uppercase(Locale.US)
        return trimmed
    }

    /** Add colons to a 12-hex value only when the caller already knows it is a BLE address. */
    @JvmStatic
    fun macWithColons(canonical: String): String {
        val c = canonicalSensorId(canonical)
        if (!MAC_PLAIN.matches(c)) return c
        return c.chunked(2).joinToString(":")
    }

    /**
     * Normalize an Android BLE address to uppercase colon form. Plain 12-hex is
     * accepted only for explicit BLE-address entry; persisted records should use
     * colon form so cloud ids are never mistaken for Bluetooth addresses.
     */
    @JvmStatic
    @JvmOverloads
    fun normalizeBleAddress(address: String?, allowPlain: Boolean = false): String? {
        val t = address?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (MAC_COLON.matches(t)) return t.uppercase(Locale.US)
        if (allowPlain && MAC_PLAIN.matches(t)) return t.uppercase(Locale.US).chunked(2).joinToString(":")
        return null
    }

    @JvmStatic
    fun looksLikeBleAddress(address: String?): Boolean =
        normalizeBleAddress(address, allowPlain = true) != null

    @JvmStatic
    fun looksLikeMac(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        return MAC_COLON.matches(t) || MAC_PLAIN.matches(t)
    }

    @JvmStatic
    fun matchesCanonicalOrKnownNativeAlias(a: String?, b: String?): Boolean {
        val ca = canonicalSensorId(a)
        val cb = canonicalSensorId(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        return ca.equals(cb, ignoreCase = true)
    }

    /**
     * Extract the sensor's 12-hex MAC from arbitrary QR / barcode text.
     *
     * The Ottai sensor-box label is a GS1 DataMatrix: `(01)`GTIN `(11)`prod-date
     * `(17)`expiry `(10)`batch `(21)`serial. The MAC is the **(21) serial**, NOT the
     * leading `(01)` GTIN — naively grabbing the first 12-hex run returns the GTIN and
     * the cloud lookup fails. The GTIN and the dates are pure decimal, whereas the
     * serial usually carries hex letters (the OUI), so candidates are ranked:
     * a run right after the AI "21" (+8) / containing a hex letter (+4) / standing
     * alone, not part of a longer hex run (+2). Falls back to the first 12-hex run.
     */
    @JvmStatic
    fun extractMacFromQr(qr: String?): String? {
        if (qr.isNullOrBlank()) return null
        val raw = qr.uppercase(Locale.US)
        // Literal colon MAC wins outright.
        Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").find(raw)?.let { return canonicalSensorId(it.value) }
        fun isHex(c: Char) = c in '0'..'9' || c in 'A'..'F'
        var best: String? = null
        var bestScore = -1
        var i = 0
        while (i + 12 <= raw.length) {
            if ((i until i + 12).all { isHex(raw[it]) }) {
                val sub = raw.substring(i, i + 12)
                val afterAi21 = i >= 2 && raw[i - 1] == '1' && raw[i - 2] == '2'
                val standalone = (i == 0 || !isHex(raw[i - 1])) && (i + 12 == raw.length || !isHex(raw[i + 12]))
                val hasLetter = sub.any { it in 'A'..'F' }
                val score = (if (afterAi21) 8 else 0) + (if (hasLetter) 4 else 0) + (if (standalone) 2 else 0)
                if (score > bestScore) { bestScore = score; best = sub }
            }
            i++
        }
        return best
    }

    // ---- Lifetime / cadence ----

    /** Sensor rated lifetime reported by the cloud for the Chinese M8 tested here. */
    const val DEFAULT_RATED_LIFETIME_DAYS = 15

    /**
     * The official app writes cloud activeExpireTime to maxActive. retainTime remains
     * cloud-driven; writing an absolute epoch to destruction made the sensor terminate
     * the link.
     */
    const val DEFAULT_RETAIN_TIME_MS = 172_800_000L
    const val DEFAULT_ACTIVE_EXPIRE_MS = DEFAULT_RATED_LIFETIME_DAYS * 24L * 3600L * 1000L

    // Cloud and sensor values are durations. Reject epoch timestamps, unit mistakes, and corrupt
    // preferences before they can turn into a decades-long age/end date in the sensor UI.
    private const val MIN_ACTIVE_EXPIRE_MS = 10L * 24L * 3600L * 1000L
    private const val MAX_ACTIVE_EXPIRE_MS = 45L * 24L * 3600L * 1000L

    /** Longest managed lifetime requested during explicit activation. */
    const val EXTENDED_LIFETIME_DAYS = 30
    const val EXTENDED_LIFETIME_MS = EXTENDED_LIFETIME_DAYS * 24L * 3600L * 1000L

    @JvmStatic
    fun sanitizeActiveExpireMs(value: Long): Long =
        value.takeIf { it in MIN_ACTIVE_EXPIRE_MS..MAX_ACTIVE_EXPIRE_MS } ?: 0L

    /**
     * Poll records to create an Ottai native shell with. Geometry is fixed when the shell is first
     * built. Its start is the creation instant and only ever moves earlier, while records are dated
     * from the confirmed start (58 s later on 2026-09-22), so a record's poll index can exceed its
     * dataNo by a minute and a shell exactly one lifetime long refuses the last record. Cover the
     * longest of the lifetime this app requests, the accepted one and the cloud rating (the
     * official app writes the rating, and the first shell-creating calls on a connection run before
     * the maxActive readback), rounded up, plus a day.
     */
    @JvmStatic
    fun nativeShellRecords(acceptedMaxActiveMs: Long, cloudActiveExpireMs: Long): Int {
        val dayMs = 24L * 3600L * 1000L
        val lifetimeMs = maxOf(
            EXTENDED_LIFETIME_MS,
            sanitizeActiveExpireMs(acceptedMaxActiveMs),
            sanitizeActiveExpireMs(cloudActiveExpireMs),
        )
        return (((lifetimeMs + dayMs - 1L) / dayMs + 1L) * 24L * 60L).toInt()
    }

    /**
     * Records the firmware produces over the accepted lifetime (one a minute), or 0 while it is
     * unknown. The capacity warning measures the mapping against the lifetime itself, not against
     * [nativeShellRecords]: a shell rebuilt from wearduration2 has no spare and can still lose its
     * final record to the start offset, which is why new shells are asked for a day more.
     */
    @JvmStatic
    fun nativeLifetimeRecords(acceptedMaxActiveMs: Long): Int =
        ((sanitizeActiveExpireMs(acceptedMaxActiveMs) + 59_999L) / 60_000L).toInt()

    @JvmStatic
    fun activationMaxActiveCandidatesMs(cloudActiveExpireMs: Long): List<Long> {
        val cloudMs = sanitizeActiveExpireMs(cloudActiveExpireMs)
            .takeIf { it > 0L }
            ?: DEFAULT_ACTIVE_EXPIRE_MS
        val candidates = mutableListOf<Long>()
        if (cloudMs > EXTENDED_LIFETIME_MS) candidates += cloudMs
        (EXTENDED_LIFETIME_DAYS downTo 15).forEach { days ->
            candidates += days * 24L * 3600L * 1000L
        }
        candidates += cloudMs
        return candidates.distinct()
    }

    /**
     * Lifetime the sensor end, the countdown and the expiry warnings are measured against: the
     * maxActive the firmware accepted, then the cloud rating, then 15 days.
     *
     * The firmware enforces exactly the value it accepted. On the field a unit written with 25 days
     * ran 24.999 and two written with 28 ran 27.999 and past day 19, under a 14-day cloud rating
     * each time; the one unit seen to stop on day 15 carried the official 15 d 30 min. The cloud
     * rating is what the account plan rates a sensor at, not what it runs, so it only stands in
     * while the firmware value is unknown — and the driver reads that value back off the sensor
     * (CHAR_MAX_ACTIVE_TIME) each time a connection finds it streaming (status 3).
     */
    @JvmStatic
    fun expectedLifetimeMs(cloudActiveExpireMs: Long, acceptedMaxActiveMs: Long): Long =
        sanitizeActiveExpireMs(acceptedMaxActiveMs).takeIf { it > 0L }
            ?: sanitizeActiveExpireMs(cloudActiveExpireMs).takeIf { it > 0L }
            ?: DEFAULT_ACTIVE_EXPIRE_MS

    /** Sensor command state is authoritative; cloud/provisional timestamps are not. */
    fun commandNeedsActivation(commandStatus: Int): Boolean = commandStatus in 0..2

    /** Starting the irreversible lifetime additionally requires an explicit user action. */
    fun shouldStartActivation(commandStatus: Int, explicitlyRequested: Boolean): Boolean =
        explicitlyRequested && commandNeedsActivation(commandStatus)

    /**
     * Setup Connect may arm [activateRequestedFor] without immediately force-writing.
     * Wizard Connect always passes [activate]=false so the command byte decides.
     */
    fun setupConnectArmsActivation(activate: Boolean, activateIfNeeded: Boolean): Boolean =
        activate || activateIfNeeded

    fun setupConnectForcesImmediateActivation(activate: Boolean): Boolean = activate

    /**
     * Draft BLE address after a material fetch. A scanned colon MAC that is not the cloud id,
     * or an existing draft that already holds such a radio, wins. [macWithColons] of the cloud
     * id is only the last resort — never overwrite a non-cloud draft when the scan is absent.
     */
    @JvmStatic
    fun isNonCloudColonBle(address: String?, cloudId: String): Boolean {
        val normalized = normalizeBleAddress(address, allowPlain = false) ?: return false
        val cloud = canonicalSensorId(cloudId)
        if (cloud.isEmpty()) return true
        return !canonicalSensorId(normalized).equals(cloud, ignoreCase = true)
    }

    @JvmStatic
    fun draftBleAddressForMaterials(
        scannedBle: String?,
        existingDraftBle: String?,
        cloudId: String,
    ): String {
        normalizeBleAddress(scannedBle, allowPlain = false)
            ?.takeIf { isNonCloudColonBle(it, cloudId) }
            ?.let { return it }
        normalizeBleAddress(existingDraftBle, allowPlain = false)
            ?.takeIf { isNonCloudColonBle(it, cloudId) }
            ?.let { return it }
        return macWithColons(cloudId)
    }

    /**
     * Cache-hit fetch may persist a real radio (scan or existing non-cloud draft).
     * It must not recreate last-resort cloud-id-as-address after Connect deleted the draft.
     * Post-cloud fetch may still write that last resort when no radio exists yet.
     */
    @JvmStatic
    fun shouldPersistMaterialsDraftAddress(
        resolvedAddress: String,
        cloudId: String,
        allowCloudIdFallback: Boolean,
    ): Boolean = isNonCloudColonBle(resolvedAddress, cloudId) || allowCloudIdFallback

    /**
     * Connect prefers Compose, then a non-cloud draft radio, then the managed record,
     * then a leftover cloud-id draft, then [macWithColons]. A poisoned draft must not
     * overwrite a managed radio.
     */
    @JvmStatic
    fun connectBleAddress(
        composeBle: String?,
        draftBle: String?,
        managedBle: String?,
        cloudId: String,
    ): String {
        val compose = normalizeBleAddress(composeBle, allowPlain = false)
        val draft = normalizeBleAddress(draftBle, allowPlain = false)
        val managed = normalizeBleAddress(managedBle, allowPlain = false)
        return compose
            ?: draft?.takeIf { isNonCloudColonBle(it, cloudId) }
            ?: managed
            ?: draft
            ?: macWithColons(cloudId)
    }

    fun shouldRescanPendingSetupActivation(
        commandStatus: Int,
        explicitlyRequested: Boolean,
        commandByteSeenThisAttempt: Boolean = false,
    ): Boolean = commandStatus < 0 && explicitlyRequested && !commandByteSeenThisAttempt

    /**
     * After 0x03 was Issued, wizard Connect / automatic resume must not enter RTC/maxActive/0x03.
     * Only the Advanced-only gesture may, and only while the byte is still 0–2.
     */
    fun mayEnterActivationWrites(
        commandStatus: Int,
        activateCommandIssued: Boolean,
        advancedActivate: Boolean,
    ): Boolean {
        if (!commandNeedsActivation(commandStatus)) return false
        if (activateCommandIssued && !advancedActivate) return false
        return true
    }

    fun shouldScheduleFirstUseActivation(
        commandStatus: Int,
        explicitlyRequested: Boolean,
        activateCommandIssued: Boolean,
        advancedActivate: Boolean,
        activationInFlight: Boolean,
        activationBusy: Boolean,
    ): Boolean {
        if (activateCommandIssued && !advancedActivate) return false
        if (activationInFlight || activationBusy) return false
        return shouldStartActivation(commandStatus, explicitlyRequested || advancedActivate)
    }

    fun shouldResumeLifetimeNegotiation(
        activateCommandIssued: Boolean,
        activationNegotiationActive: Boolean,
        activationRetryPending: Boolean,
        activationBusy: Boolean,
    ): Boolean = !activateCommandIssued &&
        activationNegotiationActive &&
        activationRetryPending &&
        !activationBusy

    fun shouldReconnectToResumeActivation(
        activateCommandIssued: Boolean,
        activationNegotiationActive: Boolean,
        activationRetryPending: Boolean,
        hasCandidates: Boolean,
        activationInFlight: Boolean,
    ): Boolean = !activateCommandIssued &&
        activationNegotiationActive &&
        (activationRetryPending || hasCandidates || activationInFlight)

    fun shouldScanForActivationCandidateOn147(
        resumeActivation: Boolean,
        activationRetryPending: Boolean,
        gattStatus: Int,
    ): Boolean = resumeActivation && activationRetryPending && gattStatus == 147

    /**
     * maxActive was already GATT-accepted and 0x03 has not been Issued. A drop before 0x03
     * must resume at destruction, not rewrite the lifetime: a NACK on that replay walks the
     * 30→15 ladder down and shortens the sensor.
     */
    fun shouldSkipToPostLifetimeWrites(
        activateCommandIssued: Boolean,
        maxActiveAccepted: Boolean,
    ): Boolean = !activateCommandIssued && maxActiveAccepted

    /**
     * setSensorWearDays on an id with no shell creates one, and a shell created with no start
     * stamps "now". Native only ever moves a start earlier, so that call before 0x03 pins the
     * poll index to the maxActive accept instead of the activation. Touch native only once a
     * start exists, or once a shell was already sized from one.
     */
    fun wearUpdateMayTouchNativeShell(startMs: Long, shellAlreadySized: Boolean): Boolean =
        shellAlreadySized || startMs > 0L

    fun shouldBeginActivationNegotiation(
        activationNegotiationActive: Boolean,
        candidatesEmpty: Boolean,
    ): Boolean = !activationNegotiationActive || candidatesEmpty

    fun staleActivationStartShouldFailClosed(
        requestStarted: Boolean,
        hasLiveAuthenticatedGatt: Boolean,
        mayEnterWrites: Boolean,
    ): Boolean = !requestStarted && hasLiveAuthenticatedGatt && mayEnterWrites

    fun shouldIgnoreAlreadyStartedActivation(
        forceActivation: Boolean,
        advancedActivate: Boolean,
        hasOfficialStart: Boolean,
    ): Boolean = !forceActivation && !advancedActivate && hasOfficialStart

    fun autoFailClosedBeforeActivationAck(
        commandNeedsActivation: Boolean,
        activateCommandIssued: Boolean,
        activationCommandSentAtMs: Long,
        waitingForActivateCommandAck: Boolean,
    ): Boolean = commandNeedsActivation &&
        activateCommandIssued &&
        activationCommandSentAtMs <= 0L &&
        waitingForActivateCommandAck

    fun isActivationLinkLossWriteStatus(gattStatus: Int): Boolean =
        gattStatus == 8 || gattStatus == 19 || gattStatus == 22 ||
            gattStatus == 133 || gattStatus == 147

    fun writeErrorShouldFailActivation(
        actStepActive: Boolean,
        actStepIsActivateCommand: Boolean,
        gattStatus: Int,
    ): Boolean {
        if (!actStepActive) return false
        if (isActivationLinkLossWriteStatus(gattStatus)) return actStepIsActivateCommand
        return true
    }

    fun shouldRetryMaxActiveOnWriteError(gattStatus: Int): Boolean =
        !isActivationLinkLossWriteStatus(gattStatus)

    fun setupConnectingShouldReturnToSensor(activationFailed: Boolean): Boolean = activationFailed

    /** CN Ottai and Syai sensors need a physical NFC field wake around activation attempts. */
    fun requiresNfcActivationWake(apiBase: String): Boolean =
        apiBase == API_BASE || apiBase == API_BASE_SYAI

    /**
     * A Chinese sensor can resume advertising under a different Android BLE address
     * after NFC wake. Only admit that address while an activation recovery scan is
     * already armed; the BLE manager still requires the sensor's auth signature before
     * persisting the candidate.
     *
     * [allowNameMatch] governs the name-only fallback, which exists solely for the
     * address-changed case. It admits ANY advertisement whose name contains "ottai"
     * or "syai" — including a stranger's sensor in range — so callers must disable it
     * once this sensor's own address is known to be stable (i.e. it is already activated).
     * Otherwise the manager retargets its transport at every neighbouring Ottai in turn,
     * each one failing the auth-signature check, and never reaches its own sensor.
     */
    /**
     * How long a freshly armed candidate scan insists on the sensor's own address before the
     * name-only fallback is allowed to admit anything else.
     *
     * The fallback exists for the address-changed case, but the scanner stops on the first hit,
     * so every stranger it admits costs a whole connect/discover/auth cycle and another scan
     * restart before the real sensor can be seen. On 2026-07-29 three wrong units were probed at
     * +0 s, +12 s and +15 s and the correct one was only reached at +21 s; the sensor's own
     * advertisement is normally in the very first burst, so holding out briefly costs nothing
     * when the address is unchanged and delays only the genuine address-changed case.
     */
    const val ACTIVATION_EXACT_ONLY_WINDOW_MS = 15_000L

    @JvmStatic
    fun isActivationExactOnlyWindowOpen(discoveryStartedAtMs: Long, nowMs: Long): Boolean =
        discoveryStartedAtMs > 0L && nowMs - discoveryStartedAtMs < ACTIVATION_EXACT_ONLY_WINDOW_MS

    fun shouldProbeActivationAdvertisement(
        discoveryPending: Boolean,
        scannedAddress: String?,
        expectedAddress: String?,
        advertisedName: String?,
        rejectedAddresses: Set<String> = emptySet(),
        allowNameMatch: Boolean = true,
        exactOnlyWindowOpen: Boolean = false,
    ): Boolean {
        if (!discoveryPending) return false
        val scanned = normalizeBleAddress(scannedAddress, allowPlain = false) ?: return false
        if (rejectedAddresses.any {
                normalizeBleAddress(it, allowPlain = false)?.equals(scanned, ignoreCase = true) == true
            }
        ) {
            return false
        }
        val expected = normalizeBleAddress(expectedAddress, allowPlain = false)
        if (expected?.equals(scanned, ignoreCase = true) == true) return true
        if (!allowNameMatch || exactOnlyWindowOpen) return false
        return looksLikeOttaiAdvertisementName(advertisedName)
    }

    /** GAP names used by Ottai Global and Syai during address-changed activation scans. */
    fun looksLikeOttaiAdvertisementName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        return n.contains("ottai", ignoreCase = true) || n.contains("syai", ignoreCase = true)
    }

    /**
     * Where the current cloud id came from. BLE-copied ids follow a later user tap;
     * QR / typed / account / known-sensor ids stay put while the radio address changes.
     */
    enum class CloudIdOrigin {
        UNKNOWN,
        BLE,
        INDEPENDENT,
    }

    data class UserBleTapResult(
        val bleAddress: String,
        val cloudId: String,
        val origin: CloudIdOrigin,
        val bleAssigned: Boolean,
    )

    /**
     * A user tap always retargets the BLE radio. Cloud id follows only when it was
     * auto-copied from a previous BLE tap (or still empty).
     */
    fun applyUserBleTap(
        existingBle: String,
        existingCloudId: String,
        origin: CloudIdOrigin,
        scannedAddress: String,
    ): UserBleTapResult {
        if (scannedAddress.isBlank()) {
            return UserBleTapResult(existingBle, existingCloudId, origin, bleAssigned = false)
        }
        val nextCloud = cloudIdForBleSelection(existingCloudId, scannedAddress, origin)
        val nextOrigin = when {
            origin == CloudIdOrigin.INDEPENDENT -> CloudIdOrigin.INDEPENDENT
            looksLikeMac(nextCloud) -> CloudIdOrigin.BLE
            else -> origin
        }
        return UserBleTapResult(
            bleAddress = scannedAddress,
            cloudId = nextCloud,
            origin = nextOrigin,
            bleAssigned = true,
        )
    }

    /** Status text only when the tap actually changed the radio address. */
    fun bleTapStatusAddress(
        previousBle: String,
        assignedBle: String,
        bleAssigned: Boolean,
    ): String? {
        if (!bleAssigned) return null
        val previous = canonicalSensorId(previousBle)
        val assigned = canonicalSensorId(assignedBle)
        if (previous.isNotBlank() && previous.equals(assigned, ignoreCase = true)) return null
        return assignedBle
    }

    fun cloudIdForBleSelection(
        existingCloudId: String,
        bleAddress: String,
        origin: CloudIdOrigin,
    ): String {
        val existing = canonicalSensorId(existingCloudId)
        if (origin == CloudIdOrigin.INDEPENDENT && looksLikeMac(existing)) return existing
        val fromBle = canonicalSensorId(bleAddress)
        return if (looksLikeMac(fromBle)) fromBle else existingCloudId
    }

    fun shouldAssignScannedBleAddress(scannedAddress: String): Boolean = scannedAddress.isNotBlank()

    fun preferredWizardStatus(status: String, materialError: String): String =
        materialError.trim().ifBlank { status }

    /** Fetch errors and later action errors (sign-out, connect) must both remain visible. */
    fun wizardVisibleMessages(status: String, materialError: String): List<String> =
        listOf(materialError.trim(), status.trim()).filter { it.isNotBlank() }

    /** Past the reported end, only declare the sensor expired once samples stop this long. */
    const val EXPIRED_STALE_GRACE_MS = 6L * 3600L * 1000L

    /** Reading cadence (minutes). */
    const val DEFAULT_READING_INTERVAL_MINUTES = 1

    /** Default warmup seconds (device_manage_warmup_duration). */
    const val DEFAULT_WARMUP_SECONDS = 3200
    const val DEFAULT_PREHEAT_PERIOD_MS = 3_600_000L

    /**
     * Post-activation window during which readings are decoded and logged but never published,
     * stored or exported.
     *
     * The vendor's own preheat (DEFAULT_PREHEAT_PERIOD_MS, or the cloud's preheatPeriodTime) is
     * 60 min, but the settling ramp measured on this hardware is over well inside 10 minutes: on
     * the 2026-07-29 activation dataNo 3 read 2.80 mmol (published as 50 mg/dL) and climbed
     * monotonically — 3.00, 3.30, 3.80, 5.00, 5.70, 6.00, 6.10 — reaching plateau by dataNo 11.
     * Suppressing a full hour would throw away usable data, so this window is deliberately
     * shorter than the vendor's and is the value this fork enforces.
     */
    const val WARMUP_SUPPRESS_MS = 600_000L

    /**
     * Whether a sample falls inside the post-activation settling window and so must not be
     * published, stored or exported.
     *
     * Deliberately a property of the SAMPLE rather than of the wall clock: a history replay of
     * the same records then reaches the same verdict on every pass and across restarts, instead
     * of re-admitting during warmup whatever the live path already refused.
     *
     * [activationStartMs] must be an authoritative start — pass 0 to disable the gate rather than
     * feeding it a provisional, which for a vendor-activated sensor always reads "just now" and
     * would blank ten minutes of good readings on every fresh connect.
     */
    @JvmStatic
    fun isWithinWarmup(activationStartMs: Long, sampleMs: Long): Boolean =
        activationStartMs > 0L &&
            sampleMs > 0L &&
            sampleMs < activationStartMs + WARMUP_SUPPRESS_MS

    // ---- SharedPreferences keys ----

    const val PREF_SENSORS_KEY = "ottai_sensors"
    const val PREF_ACCESS_TOKEN = "ottai_access_token"
    const val PREF_GLUCOSE_SECRET = "ottai_glucose_secret_key"
    const val PREF_USER_ID = "ottai_user_id"
    const val PREF_ACCOUNT_LOGIN = "ottai_account_login"  // login typed at sign-in (phone/email/username), display only
    const val PREF_API_BASE = "ottai_api_base"  // which backend the signed-in account is on (CN vs global)
    const val PREF_LOGIN_API_BASE = "ottai_login_api_base"  // backend of the last SUCCESSFUL sign-in; outlives sign-out so setup reopens there
    const val PREF_SESSION_PROFILE = "ottai_session_profile"  // identity which issued the current access token
    const val PREF_KEYA_PREFIX = "ottai_keya_"            // decrypted 6x16 hex (192)
    const val PREF_METHOD_PREFIX = "ottai_method_"        // decrypted method text
    const val PREF_COEFF_PREFIX = "ottai_coeff_"          // decrypted coefficient CSV
    const val PREF_ACTIVE_TIME_PREFIX = "ottai_active_time_"
    const val PREF_PROVISIONAL_ACTIVE_TIME_PREFIX = "ottai_provisional_active_time_"
    // Spike-filter baseline (dataNo,sampleMs,mmol,raw) persisted so a restart can't let
    // the first post-restart sample bypass the continuity gate.
    const val PREF_CONTINUITY_BASELINE_PREFIX = "ottai_continuity_baseline_"
    const val PREF_ACTIVE_EXPIRE_PREFIX = "ottai_active_expire_"  // activeExpireTime ms (maxActive duration)
    // maxActive duration (ms) the firmware ACCEPTED at activation — the sensor's lifetime, which
    // the firmware enforces exactly (see expectedLifetimeMs). Persisted when the firmware ACKs the
    // maxActive write, and corrected by reading b8fd9848 back on every status 3.
    const val PREF_ACCEPTED_MAX_ACTIVE_PREFIX = "ottai_accepted_max_active_"
    const val PREF_PREHEAT_PERIOD_PREFIX = "ottai_preheat_period_"
    const val PREF_RETAIN_TIME_PREFIX = "ottai_retain_time_"      // retainTime ms (destruction value)
    const val PREF_DEVICE_VERSION_PREFIX = "ottai_device_version_"
    const val PREF_LAST_DATA_NO_PREFIX = "ottai_last_datano_"
    // BLE record layout (8 or 9) once a payload big enough to prove it has been seen. Held
    // per sensor so a short live notify, which cannot tell the two apart, never re-decides it.
    const val PREF_RECORD_SIZE_PREFIX = "ottai_record_size_"
    // Requested-but-undelivered history windows: "start:endExclusive:attempts" joined by ';'.
    const val PREF_HISTORY_HOLES_PREFIX = "ottai_history_holes_"
    const val PREF_DEVICE_ID_PREFIX = "ottai_device_id_"
    const val PREF_ACTIVATION_ATTEMPTED_PREFIX = "ottai_act_tried_"  // one-shot auto-activate guard
    // When this app wrote the activation command to a sensor that needed it: the warmup gate's
    // start until one is confirmed. removeSensor keeps it, like the start itself.
    const val PREF_ACTIVATION_COMMAND_AT_PREFIX = "ottai_activation_command_at_"
    // Durable 0x03 Issued latch for the write gate (commit). Distinct from the warmup PREF.
    const val PREF_ACTIVATE_CMD_ISSUED_PREFIX = "ottai_activate_cmd_issued_"
    // "dataNo,sampleMs,tempC*10;" per accepted reading — feeds the stats temperature card.
    const val PREF_TEMPERATURE_HISTORY_PREFIX = "ottai_temp_history_"
    const val PREF_SELF_DEVICE_ID = "ottai_self_device_id"
    // CN phone SDK common-header identity. This is separate from the legacy watch/global id.
    const val PREF_CN_COMMON_DEVICE_ID = "ottai_cn_common_device_id"
}
