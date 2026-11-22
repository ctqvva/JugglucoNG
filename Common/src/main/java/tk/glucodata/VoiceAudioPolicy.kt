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

    /** A user-selected Bluetooth audio device saved in preferences. */
    data class SavedDevice(
        val id: String,
        val address: String?,
        val name: String,
        val isCar: Boolean = false,
    )

    /** Metadata from a paired (bonded) Bluetooth device used to enrich audio outputs. */
    data class BondedHint(
        val address: String,
        val name: String,
        val isCar: Boolean,
    )

    /** A currently connected audio output observed from the system. */
    data class ConnectedDevice(
        val address: String?,
        val name: String,
        val isBluetoothAudio: Boolean = true,
        val isCarAudio: Boolean = false,
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

    /** Preset intervals offered in the Voice screen (0 = same as default Voice interval). */
    val INTERVAL_PRESETS_SECONDS: List<Int> = listOf(0, 60, 180, 300, 600, 900, 1800)

    /** Wait briefly after BT audio connects so car stereos finish opening A2DP before TTS starts. */
    const val CONNECT_SETTLE_DELAY_MS: Long = 4_000L

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
     * Matches a saved device against a connected audio output.
     *
     * When both sides report a valid MAC address, MAC equality is authoritative (so two cars
     * sharing a generic name like "Car Multimedia" never collide). When either side lacks a
     * valid MAC (e.g. Android 8.0–8.1 or OEM HALs that omit AudioDeviceInfo.address), falls back
     * to normalized device name.
     */
    fun devicesMatch(saved: SavedDevice, connected: ConnectedDevice): Boolean {
        val savedMac = normalizeAddress(saved.address)
        val connMac = normalizeAddress(connected.address)
        if (savedMac != null && connMac != null) {
            return savedMac == connMac
        }
        val savedName = normalizeName(saved.name)
        val connName = normalizeName(connected.name)
        return savedName.isNotEmpty() && savedName == connName
    }

    /**
     * Merges duplicate entries for the same physical Bluetooth device (for instance when a car
     * exposes both A2DP media and HFP/SCO hands-free outputs at the same time).
     */
    fun deduplicateConnectedDevices(devices: List<ConnectedDevice>): List<ConnectedDevice> {
        val merged = ArrayList<ConnectedDevice>(devices.size)
        for (device in devices) {
            if (!device.isBluetoothAudio) continue
            val mac = normalizeAddress(device.address)
            val normName = normalizeName(device.name)
            if (mac == null && normName.isEmpty()) continue

            val existingIndex = merged.indexOfFirst { existing ->
                val existingMac = normalizeAddress(existing.address)
                val existingName = normalizeName(existing.name)
                if (mac != null && existingMac != null) {
                    mac == existingMac
                } else {
                    normName.isNotEmpty() && normName == existingName
                }
            }
            if (existingIndex < 0) {
                merged.add(
                    device.copy(
                        address = mac,
                        name = device.name.trim(),
                    )
                )
            } else {
                val existing = merged[existingIndex]
                merged[existingIndex] = existing.copy(
                    address = existing.address ?: mac,
                    name = existing.name.ifBlank { device.name.trim() },
                    isCarAudio = existing.isCarAudio || device.isCarAudio,
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
    ): ActiveMatch {
        if (!config.enabled) return ActiveMatch.NONE

        val btDevices = deduplicateConnectedDevices(connectedDevices)
        when (config.scope) {
            Scope.ANY_BLUETOOTH_AUDIO -> {
                val chosen = btDevices.firstOrNull { it.isCarAudio } ?: btDevices.firstOrNull()
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
                var firstMatch: Pair<SavedDevice, ConnectedDevice>? = null
                for (connected in btDevices) {
                    val saved = config.savedDevices.firstOrNull { devicesMatch(it, connected) }
                    if (saved != null) {
                        val candidate = saved to connected
                        if (saved.isCar || connected.isCarAudio) {
                            firstMatch = candidate
                            break
                        }
                        if (firstMatch == null) {
                            firstMatch = candidate
                        }
                    }
                }
                if (firstMatch != null) {
                    val (saved, connected) = firstMatch
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
        val safeDefaultInterval = defaultIntervalSeconds.coerceAtLeast(1)
        val effectiveInterval = if (autoActive && config.intervalSeconds > 0) {
            config.intervalSeconds
        } else {
            safeDefaultInterval
        }
        return EffectiveDecision(
            autoActive = autoActive,
            shouldSpeakReading = !sessionPaused && ((manualSpeakGlucose && withinSchedule) || autoActive),
            shouldSpeakAlarm = manualSpeakAlarms || (autoActive && config.speakAlarms),
            effectiveIntervalSeconds = effectiveInterval,
            useMediaStream = autoActive && config.useMediaStream,
            bypassSchedule = autoActive,
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
    ): ConnectedDevice? {
        var mac = normalizeAddress(rawAddress)
        var name = rawName?.trim().orEmpty()
        val normName = normalizeName(name)
        var isCar = false

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
            }
        } else if (normName.isNotEmpty()) {
            val nameMatches = bondedHints.filter { normalizeName(it.name) == normName }
            if (nameMatches.size == 1) {
                val unique = nameMatches.first()
                mac = normalizeAddress(unique.address)
                if (unique.name.isNotBlank()) name = unique.name.trim()
                isCar = isCar || unique.isCar
            } else if (nameMatches.size > 1) {
                // Ambiguous paired name: never guess which MAC is connected.
                isCar = isCar || nameMatches.any { it.isCar }
            }
        }

        isCar = isCar || looksLikeCarName(name)
        if (mac == null && name.isEmpty()) return null
        return ConnectedDevice(
            address = mac,
            name = name.ifBlank { mac.orEmpty() },
            isBluetoothAudio = true,
            isCarAudio = isCar,
        )
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
