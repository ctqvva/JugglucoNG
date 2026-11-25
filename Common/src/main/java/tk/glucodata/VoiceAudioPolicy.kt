package tk.glucodata

import java.util.Locale

/**
 * Pure matching and scheduling policy for automatic voice announcements when connected to
 * car or Bluetooth audio outputs. Kept free of Android framework calls so every edge case
 * can be verified in fast JVM unit tests.
 */
object VoiceAudioPolicy {

    /** Which Bluetooth audio devices can activate automatic speech. */
    enum class Scope(val prefValue: String) {
        SELECTED_DEVICES("selected"),
        ANY_BLUETOOTH_AUDIO("any");

        companion object {
            fun fromPref(value: String?): Scope =
                entries.firstOrNull { it.prefValue == value } ?: SELECTED_DEVICES
        }
    }

    /** Bluetooth transport category used to collapse dual-mode Classic + LE Audio twins. */
    enum class Transport { UNKNOWN, CLASSIC_OR_DUAL, LE_ONLY }

    /** A user-selected Bluetooth audio device saved in preferences. */
    data class SavedDevice(
        val id: String,
        val address: String?,
        val name: String,
        val isCar: Boolean = false,
        /** Sibling MACs for dual-mode (Classic + BLE/LE Audio) or CSIP earbud pairs. */
        val alternateAddresses: List<String> = emptyList(),
    )

    /** Metadata from a paired (bonded) Bluetooth device used to enrich audio outputs. */
    data class BondedHint(
        val address: String,
        val name: String,
        val isCar: Boolean,
        val transport: Transport = Transport.UNKNOWN,
    )

    /** A currently connected audio output observed from the system. */
    data class ConnectedDevice(
        val address: String?,
        val name: String,
        val isBluetoothAudio: Boolean = true,
        val isCarAudio: Boolean = false,
        /** True when [address] is unavailable and multiple paired devices share [name]. */
        val ambiguousName: Boolean = false,
        val transport: Transport = Transport.UNKNOWN,
        val alternateAddresses: List<String> = emptyList(),
    )

    /** Persisted user configuration for Bluetooth & car audio voice announcements. */
    data class Config(
        val enabled: Boolean = false,
        val scope: Scope = Scope.SELECTED_DEVICES,
        val includeCarMode: Boolean = true,
        val savedDevices: List<SavedDevice> = emptyList(),
        /** 0 = use the regular Voice interval; >0 = override interval in seconds while active. */
        val intervalSeconds: Int = 0,
        val speakAlarms: Boolean = true,
        val announceOnConnect: Boolean = true,
        /** Seconds to wait after connecting before speaking the connect announcement. */
        val connectAnnounceDelaySeconds: Int = DEFAULT_CONNECT_ANNOUNCE_DELAY_SECONDS,
        val useMediaStream: Boolean = true,
    )

    /** Result of matching current audio/car connections against [Config]. */
    data class ActiveMatch(
        val matched: Boolean,
        val triggerKey: String?,
        val displayLabel: String?,
        val viaCarMode: Boolean,
    ) {
        companion object {
            val NONE = ActiveMatch(
                matched = false,
                triggerKey = null,
                displayLabel = null,
                viaCarMode = false,
            )
        }
    }

    /** Combined effective voice behavior considering both manual settings and active audio trigger. */
    data class EffectiveDecision(
        val autoActive: Boolean,
        val shouldSpeakReading: Boolean,
        val shouldSpeakAlarm: Boolean,
        val effectiveIntervalSeconds: Int,
        val useMediaStream: Boolean,
        val bypassSchedule: Boolean,
    )

    /** Interval value meaning "use the default Voice separation setting". */
    const val INTERVAL_DEFAULT: Int = 0

    /** Interval value meaning "speak every distinct reading with no extra time separation". */
    const val INTERVAL_EVERY_READING: Int = 1

    /** Preset intervals offered in the Voice screen. */
    val INTERVAL_PRESETS_SECONDS: List<Int> = listOf(
        INTERVAL_DEFAULT,
        INTERVAL_EVERY_READING,
        180,
        300,
        600,
        900,
        1800,
    )

    /** Default seconds to wait after BT audio connects so car stereos finish opening A2DP. */
    const val DEFAULT_CONNECT_ANNOUNCE_DELAY_SECONDS: Int = 4

    /** Preset connect-announcement delays offered when Announce on connect is enabled. */
    val CONNECT_ANNOUNCE_DELAY_PRESETS_SECONDS: List<Int> = listOf(4, 8, 10, 15, 20)

    fun normalizeConnectDelaySeconds(seconds: Int): Int =
        if (seconds <= 0) DEFAULT_CONNECT_ANNOUNCE_DELAY_SECONDS else seconds.coerceIn(1, 60)

    /** Wait briefly after BT audio connects so car stereos finish opening A2DP before TTS starts. */
    const val CONNECT_SETTLE_DELAY_MS: Long = DEFAULT_CONNECT_ANNOUNCE_DELAY_SECONDS * 1_000L

    /** Single retry delay if the TTS engine is still binding when the settle window finishes. */
    const val CONNECT_TTS_RETRY_DELAY_MS: Long = 3_000L

    /** Suppress duplicate connect announcements after brief BT glitches or a recent utterance. */
    const val CONNECT_ANNOUNCE_COOLDOWN_MS: Long = 15_000L

    private val MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val PLACEHOLDER_MACS = setOf(
        "00:00:00:00:00:00",
        "02:00:00:00:00:00",
        "FF:FF:FF:FF:FF:FF",
    )
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")

    private val CAR_NAME_TOKENS = setOf(
        "car",
        "carplay",
        "uconnect",
        "myford",
        "mazda",
        "toyota",
        "honda",
        "bmw",
        "audi",
        "mercedes",
        "mbux",
        "tesla",
        "volvo",
        "subaru",
        "hyundai",
        "kia",
        "nissan",
        "vw",
        "volkswagen",
        "skoda",
        "ford",
        "chevy",
        "chevrolet",
        "lexus",
        "porsche",
        "jeep",
        "chrysler",
        "dodge",
        "cadillac",
        "buick",
        "gmc",
        "acura",
        "infiniti",
        "mitsubishi",
        "suzuki",
        "peugeot",
        "renault",
        "citroen",
        "fiat",
        "opel",
        "cupra",
        "dacia",
        "rivian",
        "polestar",
        "entune",
    )

    /**
     * Heuristic car-name detector using whole-word token boundaries only so ordinary audio names
     * like "Audio-Technica", "Bluetooth Audio", or "Oscar's AirPods" never match "audi" or "car".
     */
    fun looksLikeCarName(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        val tokens = TOKEN_SPLIT_REGEX.split(normalized).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.any { it in CAR_NAME_TOKENS }) return true
        // Multi-word phrases checked on token sequence.
        for (i in 0 until tokens.lastIndex) {
            val pair = "${tokens[i]} ${tokens[i + 1]}"
            if (pair == "android auto" || pair == "hands free" || pair == "car kit") {
                return true
            }
        }
        return false
    }

    /**
     * Normalizes a Bluetooth MAC address to uppercase `AA:BB:CC:DD:EE:FF`, or returns null for
     * missing, invalid, or Android placeholder MAC strings.
     */
    fun normalizeAddress(raw: String?): String? {
        val candidate = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (candidate.isEmpty() || candidate in PLACEHOLDER_MACS) return null
        return if (MAC_REGEX.matches(candidate)) candidate else null
    }

    /** Normalizes a device display name for case-insensitive fallback matching. */
    fun normalizeName(raw: String?): String =
        raw?.trim()?.replace(WHITESPACE_REGEX, " ")?.lowercase(Locale.ROOT).orEmpty()

    /** Stable identifier for a device: MAC address when valid, otherwise normalized name. */
    fun deviceId(address: String?, name: String): String {
        val mac = normalizeAddress(address)
        if (mac != null) return "mac:$mac"
        val normalized = normalizeName(name)
        return if (normalized.isNotEmpty()) "name:$normalized" else ""
    }

    /**
     * True when two **simultaneously active** audio outputs reported by [AudioManager] represent
     * the same physical headset (e.g. Classic A2DP + LE Audio / BLE Headset, or Left + Right LE
     * Audio CSIP earbuds). Never used across disconnected bonded devices so two separate headsets
     * with the same name can never share each other's MAC addresses.
     */
    fun areSimultaneousSameHeadset(
        addressA: String?,
        nameA: String,
        transportA: Transport,
        isCarA: Boolean,
        addressB: String?,
        nameB: String,
        transportB: Transport,
        isCarB: Boolean,
    ): Boolean {
        val macA = normalizeAddress(addressA)
        val macB = normalizeAddress(addressB)
        if (macA != null && macB != null && macA == macB) return true

        val normA = normalizeName(nameA)
        val normB = normalizeName(nameB)
        if (normA.isEmpty() || normA != normB) return false
        if (macA == null || macB == null) return true

        // Only collapse different known MACs when Android reports them simultaneously connected
        // over complementary transports (Classic + LE) or both LE Audio CSIP earbuds.
        if (!isCarA && !isCarB && (transportA == Transport.LE_ONLY || transportB == Transport.LE_ONLY)) {
            return true
        }
        return false
    }

    /**
     * Matches a saved device against a connected audio output.
     *
     * When both sides report a valid MAC address, MAC equality (including dual-mode/LE sibling
     * addresses) is authoritative so two cars sharing "Car Multimedia" never collide while dual-mode
     * earbuds match across Classic A2DP and LE Audio. When either side lacks a valid MAC, falls back
     * to normalized device name unless the paired name is ambiguous.
     */
    fun devicesMatch(saved: SavedDevice, connected: ConnectedDevice): Boolean {
        val savedMacs = buildSet {
            normalizeAddress(saved.address)?.let(::add)
            saved.alternateAddresses.mapNotNull(::normalizeAddress).forEach(::add)
        }
        val connMacs = buildSet {
            normalizeAddress(connected.address)?.let(::add)
            connected.alternateAddresses.mapNotNull(::normalizeAddress).forEach(::add)
        }
        if (savedMacs.isNotEmpty() && connMacs.isNotEmpty()) {
            return savedMacs.any { it in connMacs }
        }
        // Fail closed when the user selected a MAC-identified device and the connected output
        // has no MAC while sharing a name with multiple paired devices (e.g. two "Car Multimedia").
        if (savedMacs.isNotEmpty() && connMacs.isEmpty() && connected.ambiguousName) {
            return false
        }
        val savedName = normalizeName(saved.name)
        val connName = normalizeName(connected.name)
        return savedName.isNotEmpty() && savedName == connName
    }

    /**
     * Merges duplicate entries for the same physical Bluetooth device (e.g. A2DP + LE Audio or
     * Left/Right LE Audio CSIP earbuds connected simultaneously).
     */
    fun deduplicateConnectedDevices(devices: List<ConnectedDevice>): List<ConnectedDevice> {
        val merged = ArrayList<ConnectedDevice>(devices.size)
        for (device in devices) {
            if (!device.isBluetoothAudio) continue
            val mac = normalizeAddress(device.address)
            val normName = normalizeName(device.name)
            if (mac == null && normName.isEmpty()) continue

            val existingIndex = merged.indexOfFirst { existing ->
                val existingMacs = buildSet {
                    normalizeAddress(existing.address)?.let(::add)
                    existing.alternateAddresses.mapNotNull(::normalizeAddress).forEach(::add)
                }
                val deviceMacs = buildSet {
                    if (mac != null) add(mac)
                    device.alternateAddresses.mapNotNull(::normalizeAddress).forEach(::add)
                }
                if (existingMacs.any { it in deviceMacs }) {
                    true
                } else {
                    areSimultaneousSameHeadset(
                        addressA = existing.address,
                        nameA = existing.name,
                        transportA = existing.transport,
                        isCarA = existing.isCarAudio,
                        addressB = mac,
                        nameB = device.name,
                        transportB = device.transport,
                        isCarB = device.isCarAudio,
                    )
                }
            }
            if (existingIndex < 0) {
                merged.add(
                    device.copy(
                        address = mac,
                        name = device.name.trim(),
                        alternateAddresses = device.alternateAddresses
                            .mapNotNull(::normalizeAddress)
                            .filter { it != mac }
                            .distinct(),
                    )
                )
            } else {
                val existing = merged[existingIndex]
                // Prefer Classic/Dual MAC as primary when both Classic and LE are present.
                val preferIncomingAsPrimary = existing.address == null ||
                    (existing.transport == Transport.LE_ONLY && device.transport == Transport.CLASSIC_OR_DUAL && mac != null)
                val primaryMac = if (preferIncomingAsPrimary) mac else existing.address
                val allAlts = buildSet {
                    existing.address?.let(::add)
                    existing.alternateAddresses.mapNotNull(::normalizeAddress).forEach(::add)
                    if (mac != null) add(mac)
                    device.alternateAddresses.mapNotNull(::normalizeAddress).forEach(::add)
                    if (primaryMac != null) remove(primaryMac)
                }.toList()
                val mergedTransport = when {
                    existing.transport == Transport.CLASSIC_OR_DUAL || device.transport == Transport.CLASSIC_OR_DUAL ->
                        Transport.CLASSIC_OR_DUAL
                    existing.transport == Transport.LE_ONLY || device.transport == Transport.LE_ONLY ->
                        Transport.LE_ONLY
                    else -> Transport.UNKNOWN
                }
                merged[existingIndex] = existing.copy(
                    address = primaryMac,
                    name = existing.name.ifBlank { device.name.trim() },
                    isCarAudio = existing.isCarAudio || device.isCarAudio,
                    ambiguousName = if (primaryMac != null) false else (existing.ambiguousName || device.ambiguousName),
                    transport = mergedTransport,
                    alternateAddresses = allAlts,
                )
            }
        }
        return merged
    }

    /** Evaluates whether the current audio outputs or Android Auto / Car mode trigger auto-voice. */
    fun evaluateMatch(
        config: Config,
        connectedDevices: List<ConnectedDevice>,
        inCarMode: Boolean,
        previousMatch: ActiveMatch = ActiveMatch.NONE,
    ): ActiveMatch {
        if (!config.enabled) return ActiveMatch.NONE

        val btDevices = deduplicateConnectedDevices(connectedDevices)
        when (config.scope) {
            Scope.ANY_BLUETOOTH_AUDIO -> {
                val carCandidate = btDevices.firstOrNull { it.isCarAudio }
                val sticky = if (previousMatch.matched &&
                    previousMatch.triggerKey?.startsWith("any_bt:") == true &&
                    (previousMatch.viaCarMode || carCandidate == null)
                ) {
                    val prevId = previousMatch.triggerKey.removePrefix("any_bt:")
                    val prevName = normalizeName(previousMatch.displayLabel)
                    btDevices.firstOrNull { dev ->
                        deviceId(dev.address, dev.name) == prevId ||
                            (prevName.isNotEmpty() && normalizeName(dev.name) == prevName)
                    }
                } else {
                    null
                }
                val chosen = sticky ?: carCandidate ?: btDevices.firstOrNull()
                if (chosen != null) {
                    val key = "any_bt:" + deviceId(chosen.address, chosen.name)
                    return ActiveMatch(
                        matched = true,
                        triggerKey = key,
                        displayLabel = chosen.name.ifBlank { null },
                        viaCarMode = chosen.isCarAudio || inCarMode,
                    )
                }
            }
            Scope.SELECTED_DEVICES -> {
                val matchedPairs = ArrayList<Pair<SavedDevice, ConnectedDevice>>()
                for (connected in btDevices) {
                    val saved = config.savedDevices.firstOrNull { devicesMatch(it, connected) }
                    if (saved != null) {
                        matchedPairs.add(saved to connected)
                    }
                }
                val carCandidate = matchedPairs.firstOrNull { (saved, conn) -> saved.isCar || conn.isCarAudio }
                val sticky = if (previousMatch.matched &&
                    previousMatch.triggerKey?.startsWith("dev:") == true &&
                    (previousMatch.viaCarMode || carCandidate == null)
                ) {
                    val prevSavedId = previousMatch.triggerKey.removePrefix("dev:")
                    matchedPairs.firstOrNull { (saved, _) -> saved.id == prevSavedId }
                } else {
                    null
                }
                val chosen = sticky ?: carCandidate ?: matchedPairs.firstOrNull()
                if (chosen != null) {
                    val (saved, connected) = chosen
                    val label = connected.name.ifBlank { saved.name.trim() }.ifBlank { null }
                    return ActiveMatch(
                        matched = true,
                        triggerKey = "dev:${saved.id}",
                        displayLabel = label,
                        viaCarMode = saved.isCar || connected.isCarAudio || inCarMode,
                    )
                }
            }
        }

        if (config.includeCarMode && inCarMode) {
            return ActiveMatch(
                matched = true,
                triggerKey = "car_mode",
                displayLabel = null,
                viaCarMode = true,
            )
        }

        return ActiveMatch.NONE
    }

    /** Resolves effective speech behavior without mutating the user's manual voice toggles. */
    fun resolveEffective(
        manualSpeakGlucose: Boolean,
        withinSchedule: Boolean,
        manualSpeakAlarms: Boolean,
        defaultIntervalSeconds: Int,
        config: Config,
        activeMatch: ActiveMatch,
        sessionMuted: Boolean,
    ): EffectiveDecision {
        val sessionPaused = config.enabled && activeMatch.matched && sessionMuted
        val autoActive = config.enabled && activeMatch.matched && !sessionMuted
        // Only driving sessions (car audio or Android Auto / car mode) override Speak schedule;
        // headphones, hearing aids, and sleep earbuds continue respecting the user's quiet hours.
        val bypassSchedule = autoActive && activeMatch.viaCarMode
        val autoReadingAllowed = autoActive && (withinSchedule || bypassSchedule)
        val safeDefaultInterval = defaultIntervalSeconds.coerceAtLeast(1)
        val effectiveInterval = when {
            !autoActive || config.intervalSeconds == INTERVAL_DEFAULT -> safeDefaultInterval
            config.intervalSeconds == INTERVAL_EVERY_READING -> 0
            config.intervalSeconds > INTERVAL_EVERY_READING -> config.intervalSeconds
            else -> safeDefaultInterval
        }
        return EffectiveDecision(
            autoActive = autoActive,
            shouldSpeakReading = !sessionPaused && ((manualSpeakGlucose && withinSchedule) || autoReadingAllowed),
            shouldSpeakAlarm = manualSpeakAlarms || (autoActive && config.speakAlarms),
            effectiveIntervalSeconds = effectiveInterval,
            useMediaStream = autoActive && config.useMediaStream,
            bypassSchedule = bypassSchedule,
        )
    }

    /**
     * Enriches a single Bluetooth audio output reported by AudioManager with connected profile
     * state and paired Bluetooth metadata without inventing an arbitrary MAC for ambiguous names.
     *
     * Priority when AudioManager omits a MAC address:
     * 1. An unambiguous connected A2DP/HFP profile device matching the name.
     * 2. An unambiguous single paired device matching the name.
     * 3. If multiple paired devices share the same name (e.g. two "Car Multimedia" head units),
     *    keep [ConnectedDevice.address] as null so [devicesMatch] uses name fallback instead of
     *    treating a guessed MAC as authoritative.
     */
    fun enrichConnectedAudioOutput(
        rawAddress: String?,
        rawName: String?,
        profileConnected: List<ConnectedDevice>,
        bondedHints: List<BondedHint>,
        looksLikeCarName: (String) -> Boolean,
        transport: Transport = Transport.UNKNOWN,
    ): ConnectedDevice? {
        var mac = normalizeAddress(rawAddress)
        var name = rawName?.trim().orEmpty()
        val normName = normalizeName(name)
        var isCar = false
        var ambiguousName = false
        var resolvedTransport = transport
        val altAddresses = LinkedHashSet<String>()

        if (mac == null && normName.isNotEmpty()) {
            val matchingProfiles = profileConnected.filter {
                normalizeAddress(it.address) != null && normalizeName(it.name) == normName
            }
            val distinctMacs = matchingProfiles.mapNotNull { normalizeAddress(it.address) }.distinct()
            if (distinctMacs.size == 1) {
                mac = distinctMacs.first()
                isCar = matchingProfiles.any { it.isCarAudio }
            }
        }

        if (mac != null) {
            val exactBonded = bondedHints.firstOrNull { normalizeAddress(it.address) == mac }
            if (exactBonded != null) {
                if (exactBonded.name.isNotBlank()) name = exactBonded.name.trim()
                isCar = isCar || exactBonded.isCar
                if (resolvedTransport == Transport.UNKNOWN) resolvedTransport = exactBonded.transport
            }
        } else if (normName.isNotEmpty()) {
            val nameMatches = bondedHints.filter { normalizeName(it.name) == normName }
            if (nameMatches.size == 1) {
                val unique = nameMatches.first()
                mac = normalizeAddress(unique.address)
                if (unique.name.isNotBlank()) name = unique.name.trim()
                isCar = isCar || unique.isCar
                if (resolvedTransport == Transport.UNKNOWN) resolvedTransport = unique.transport
            } else if (nameMatches.size > 1) {
                // Ambiguous paired name with distinct MACs: never guess or merge across bonded
                // entries so an unselected headset/car can never match a selected sibling.
                isCar = isCar || nameMatches.any { it.isCar }
                ambiguousName = true
            }
        }

        isCar = isCar || looksLikeCarName(name)
        if (mac == null && name.isEmpty()) return null
        return ConnectedDevice(
            address = mac,
            name = name.ifBlank { mac.orEmpty() },
            isBluetoothAudio = true,
            isCarAudio = isCar,
            ambiguousName = ambiguousName,
            transport = resolvedTransport,
            alternateAddresses = altAddresses.toList(),
        )
    }

    /**
     * True when the active trigger moved from one matched device/session to a different one
     * (e.g. headphones A -> car B), so a per-connection pause from A does not silence B.
     *
     * Treats a handoff between Android Auto (`car_mode`) and a car Bluetooth audio device as the
     * same in-car session so pausing in the car stays paused across projection/BT handoffs.
     */
    fun isDistinctSessionSwitch(previous: ActiveMatch, current: ActiveMatch): Boolean {
        if (!previous.matched || !current.matched) return false
        if (previous.triggerKey == current.triggerKey) return false
        val sameCarProjectionHandoff = previous.viaCarMode && current.viaCarMode &&
            (previous.triggerKey == "car_mode" || current.triggerKey == "car_mode")
        if (sameCarProjectionHandoff) return false
        // In ANY_BLUETOOTH_AUDIO scope, the same physical device can refine between name-only
        // ("any_bt:name:...") and MAC-addressed ("any_bt:mac:...") once profile state arrives.
        val prevKey = previous.triggerKey
        val currKey = current.triggerKey
        val nameToMacRefinement = prevKey != null && currKey != null &&
            ((prevKey.startsWith("any_bt:name:") && currKey.startsWith("any_bt:mac:")) ||
                (prevKey.startsWith("any_bt:mac:") && currKey.startsWith("any_bt:name:")))
        if (nameToMacRefinement) {
            val prevName = normalizeName(previous.displayLabel)
            val currName = normalizeName(current.displayLabel)
            if (prevName.isNotEmpty() && prevName == currName) {
                return false
            }
        }
        return true
    }

    /**
     * Preserves the user's session pause across brief Bluetooth dropouts (within the cooldown
     * window) when the same session reconnects, while starting fresh on a real disconnect or a
     * different device.
     */
    fun shouldPreservePauseOnReconnect(
        previousPausedMatch: ActiveMatch?,
        reconnectedMatch: ActiveMatch,
        nowMs: Long,
        pausedDisconnectedAtMs: Long,
    ): Boolean {
        if (previousPausedMatch == null || !previousPausedMatch.matched || !reconnectedMatch.matched) {
            return false
        }
        if (pausedDisconnectedAtMs <= 0L || nowMs - pausedDisconnectedAtMs >= CONNECT_ANNOUNCE_COOLDOWN_MS) {
            return false
        }
        return !isDistinctSessionSwitch(previousPausedMatch, reconnectedMatch)
    }

    /**
     * Checks that a resolved reading is genuinely current before speaking it on connect.
     * `CurrentDisplaySource.resolveCurrent` can fall back to older history rows during an outage;
     * announcing a 20-minute-old reading as current while starting to drive would be misleading.
     */
    fun isReadingFreshForConnectAnnouncement(
        readingTimeMs: Long,
        nowMs: Long,
        maxAgeMs: Long,
    ): Boolean {
        if (readingTimeMs <= 0L || maxAgeMs <= 0L) return false
        val ageMs = nowMs - readingTimeMs
        return ageMs <= maxAgeMs && ageMs >= -60_000L
    }

    /**
     * Decides whether a connect transition should schedule a one-shot "current reading"
     * announcement. Suppresses chatter from brief BT profile handoffs or already-spoken readings.
     */
    fun shouldScheduleConnectAnnouncement(
        wasMatched: Boolean,
        nowMatched: Boolean,
        config: Config,
        sessionMuted: Boolean,
        nowMs: Long,
        lastDisconnectedAtMs: Long,
        lastSpokenAtMs: Long,
    ): Boolean {
        if (wasMatched || !nowMatched || sessionMuted || !config.enabled || !config.announceOnConnect) {
            return false
        }
        if (lastDisconnectedAtMs > 0L && nowMs - lastDisconnectedAtMs < CONNECT_ANNOUNCE_COOLDOWN_MS) {
            return false
        }
        if (lastSpokenAtMs > 0L && nowMs - lastSpokenAtMs < CONNECT_ANNOUNCE_COOLDOWN_MS) {
            return false
        }
        return true
    }
}
