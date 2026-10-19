package tk.glucodata.sms

import androidx.annotation.Keep
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration for an SMS destination.
 *
 * SMS is deliberately modelled differently from the network destinations
 * (`custom_json`, `telegram_bot`, VK). Those stream every reading; SMS costs
 * money, arrives out of order, cannot be edited in place, and wakes people at
 * night. So an SMS destination never mirrors the reading stream. It does two
 * jobs instead:
 *
 *  1. **Safety net** — escalate to other people when an alarm goes
 *     unacknowledged, when a critical value is reached, or when the sensor
 *     stops reporting altogether. ([SmsEventKind.ALARM_UNACKED],
 *     [SmsEventKind.CRITICAL], [SmsEventKind.NO_DATA])
 *  2. **Fallback relay** — keep a follower informed with periodic readings
 *     while the data path (Nightscout / Telegram / API destinations) is down.
 *     ([SmsEventKind.RELAY])
 *
 * Every path is bounded by [maxPerHour] / [maxPerDay] so a stuck sensor or a
 * flapping connection can never drain a prepaid balance.
 */
@Keep
data class SmsPolicy(
    /** Whose glucose this is; rendered as `{name}`. Empty means "no name in the text". */
    val subjectName: String = "",
    val contacts: List<SmsContact> = emptyList(),

    // --- Safety net: unacknowledged alarms ---
    val alarmEscalationEnabled: Boolean = true,
    /** How long an alarm may stay unacknowledged before the first contact is texted. */
    val unackedMinutes: Int = DEFAULT_UNACKED_MINUTES,
    /** Alert type ids that may escalate. Empty means "every alert type". */
    val alarmAlertIds: Set<Int> = DEFAULT_ALARM_ALERT_IDS,

    // --- Safety net: critical values ---
    val criticalEnabled: Boolean = true,
    val criticalLowMgdl: Int = DEFAULT_CRITICAL_LOW_MGDL,
    val criticalHighMgdl: Int = DEFAULT_CRITICAL_HIGH_MGDL,
    /** Short breathing room so a brief dip doesn't page anyone. */
    val criticalGraceMinutes: Int = DEFAULT_CRITICAL_GRACE_MINUTES,
    /**
     * Critical values normally page even if the alarm was dismissed on the phone:
     * dismissing an alarm is not the same as treating a hypo.
     */
    val criticalIgnoresAck: Boolean = true,

    // --- Safety net: sensor silence ---
    val noDataEnabled: Boolean = true,
    val noDataMinutes: Int = DEFAULT_NO_DATA_MINUTES,

    // --- Escalation ladder / repeats (shared by the three safety-net kinds) ---
    /** Delay between contact stages. 0 texts every stage at once. */
    val stageStepMinutes: Int = DEFAULT_STAGE_STEP_MINUTES,
    /** Gap before re-texting the same still-unresolved episode. 0 disables repeats. */
    val repeatMinutes: Int = DEFAULT_REPEAT_MINUTES,
    /** Total messages per episode per contact, including the first. */
    val maxSendsPerEpisode: Int = DEFAULT_MAX_SENDS_PER_EPISODE,
    /** Text everyone who was paged once the episode resolves. */
    val allClearEnabled: Boolean = true,

    // --- Fallback relay ---
    val relayMode: String = RELAY_WHEN_OFFLINE,
    val relayIntervalMinutes: Int = DEFAULT_RELAY_INTERVAL_MINUTES,
    /** The data path must have been down this long before the relay starts. */
    val relayOfflineGraceMinutes: Int = DEFAULT_RELAY_OFFLINE_GRACE_MINUTES,

    // --- Guards ---
    /**
     * When true the safety net only fires while the data path is down, i.e. SMS is
     * used strictly as a backup for followers who would otherwise see the alarm
     * over the network. When false (the default) alarms escalate regardless —
     * the network being up does not mean a human noticed.
     */
    val escalateOnlyWhenOffline: Boolean = false,
    val maxPerHour: Int = DEFAULT_MAX_PER_HOUR,
    val maxPerDay: Int = DEFAULT_MAX_PER_DAY
) {
    fun normalizedRelayMode(): String = normalizeRelayMode(relayMode)

    /**
     * A contact the runtime may actually text. A half-typed row in the editor has
     * no number yet, so it is deliberately kept in [contacts] but excluded here.
     */
    private fun sendableContacts(): List<SmsContact> =
        contacts.filter { it.enabled && it.number.isNotBlank() }

    fun contactsForStage(stage: Int): List<SmsContact> =
        sendableContacts().filter { it.normalizedStage() == stage }

    fun relayContacts(): List<SmsContact> = sendableContacts().filter { it.relay }

    /** Highest stage that has at least one sendable contact, or -1 when there are none. */
    fun lastStage(): Int = sendableContacts().maxOfOrNull { it.normalizedStage() } ?: -1

    fun numbers(): List<String> = sendableContacts().map { it.number }

    fun hasUsableContacts(): Boolean = sendableContacts().isNotEmpty()

    /** Clamps every field into a range the runtime can act on without further checks. */
    fun sanitized(): SmsPolicy = copy(
        subjectName = subjectName.trim().take(MAX_SUBJECT_NAME_LENGTH),
        // Blank rows survive: the editor adds an empty contact and the user types the
        // number into it afterwards, so dropping them here would delete the row under
        // the user's finger. Only real numbers are deduplicated.
        contacts = contacts
            .map { it.sanitized() }
            .distinctBy { if (it.number.isBlank()) it.id else it.number }
            .take(MAX_CONTACTS),
        unackedMinutes = unackedMinutes.coerceIn(1, 240),
        criticalLowMgdl = criticalLowMgdl.coerceIn(20, 140),
        criticalHighMgdl = criticalHighMgdl.coerceIn(160, 600),
        criticalGraceMinutes = criticalGraceMinutes.coerceIn(0, 60),
        noDataMinutes = noDataMinutes.coerceIn(10, 720),
        stageStepMinutes = stageStepMinutes.coerceIn(0, 120),
        repeatMinutes = repeatMinutes.coerceIn(0, 240),
        maxSendsPerEpisode = maxSendsPerEpisode.coerceIn(1, 10),
        relayMode = normalizedRelayMode(),
        relayIntervalMinutes = relayIntervalMinutes.coerceIn(5, 720),
        relayOfflineGraceMinutes = relayOfflineGraceMinutes.coerceIn(0, 240),
        maxPerHour = maxPerHour.coerceIn(1, 60),
        maxPerDay = maxPerDay.coerceIn(1, 400)
    )

    @Keep
    companion object {
        const val RELAY_OFF = "off"
        const val RELAY_WHEN_OFFLINE = "when_offline"
        const val RELAY_ALWAYS = "always"

        const val MAX_CONTACTS = 8
        const val MAX_STAGE = 2
        const val MAX_SUBJECT_NAME_LENGTH = 24

        const val DEFAULT_UNACKED_MINUTES = 10
        const val DEFAULT_CRITICAL_LOW_MGDL = 55
        const val DEFAULT_CRITICAL_HIGH_MGDL = 300
        const val DEFAULT_CRITICAL_GRACE_MINUTES = 3
        const val DEFAULT_NO_DATA_MINUTES = 45
        const val DEFAULT_STAGE_STEP_MINUTES = 5
        const val DEFAULT_REPEAT_MINUTES = 20
        const val DEFAULT_MAX_SENDS_PER_EPISODE = 3
        const val DEFAULT_RELAY_INTERVAL_MINUTES = 30
        const val DEFAULT_RELAY_OFFLINE_GRACE_MINUTES = 10
        const val DEFAULT_MAX_PER_HOUR = 4
        const val DEFAULT_MAX_PER_DAY = 20

        /**
         * Alert types that escalate out of the box: the ones that mean "someone
         * else may need to act". Sensor expiry and forecast alerts are excluded —
         * they are chores, not emergencies.
         */
        val DEFAULT_ALARM_ALERT_IDS: Set<Int> = setOf(
            0,  // LOW
            5,  // VERY_LOW
            6,  // VERY_HIGH
            12  // FALLING_FAST
        )

        fun normalizeRelayMode(mode: String): String = when (mode) {
            RELAY_ALWAYS, RELAY_WHEN_OFFLINE -> mode
            else -> RELAY_OFF
        }

        fun encode(policy: SmsPolicy): JSONObject =
            JSONObject()
                .put("subjectName", policy.subjectName)
                .put("contacts", JSONArray().also { array ->
                    policy.contacts.forEach { array.put(SmsContact.encode(it)) }
                })
                .put("alarmEscalationEnabled", policy.alarmEscalationEnabled)
                .put("unackedMinutes", policy.unackedMinutes)
                .put("alarmAlertIds", JSONArray().also { array ->
                    policy.alarmAlertIds.sorted().forEach { array.put(it) }
                })
                .put("criticalEnabled", policy.criticalEnabled)
                .put("criticalLowMgdl", policy.criticalLowMgdl)
                .put("criticalHighMgdl", policy.criticalHighMgdl)
                .put("criticalGraceMinutes", policy.criticalGraceMinutes)
                .put("criticalIgnoresAck", policy.criticalIgnoresAck)
                .put("noDataEnabled", policy.noDataEnabled)
                .put("noDataMinutes", policy.noDataMinutes)
                .put("stageStepMinutes", policy.stageStepMinutes)
                .put("repeatMinutes", policy.repeatMinutes)
                .put("maxSendsPerEpisode", policy.maxSendsPerEpisode)
                .put("allClearEnabled", policy.allClearEnabled)
                .put("relayMode", policy.normalizedRelayMode())
                .put("relayIntervalMinutes", policy.relayIntervalMinutes)
                .put("relayOfflineGraceMinutes", policy.relayOfflineGraceMinutes)
                .put("escalateOnlyWhenOffline", policy.escalateOnlyWhenOffline)
                .put("maxPerHour", policy.maxPerHour)
                .put("maxPerDay", policy.maxPerDay)

        fun decode(obj: JSONObject?): SmsPolicy {
            if (obj == null) return SmsPolicy().sanitized()
            val defaults = SmsPolicy()
            return SmsPolicy(
                subjectName = obj.optString("subjectName", defaults.subjectName),
                contacts = decodeContacts(obj.optJSONArray("contacts")),
                alarmEscalationEnabled = obj.optBoolean(
                    "alarmEscalationEnabled",
                    defaults.alarmEscalationEnabled
                ),
                unackedMinutes = obj.optInt("unackedMinutes", defaults.unackedMinutes),
                alarmAlertIds = decodeAlertIds(obj.optJSONArray("alarmAlertIds")),
                criticalEnabled = obj.optBoolean("criticalEnabled", defaults.criticalEnabled),
                criticalLowMgdl = obj.optInt("criticalLowMgdl", defaults.criticalLowMgdl),
                criticalHighMgdl = obj.optInt("criticalHighMgdl", defaults.criticalHighMgdl),
                criticalGraceMinutes = obj.optInt(
                    "criticalGraceMinutes",
                    defaults.criticalGraceMinutes
                ),
                criticalIgnoresAck = obj.optBoolean("criticalIgnoresAck", defaults.criticalIgnoresAck),
                noDataEnabled = obj.optBoolean("noDataEnabled", defaults.noDataEnabled),
                noDataMinutes = obj.optInt("noDataMinutes", defaults.noDataMinutes),
                stageStepMinutes = obj.optInt("stageStepMinutes", defaults.stageStepMinutes),
                repeatMinutes = obj.optInt("repeatMinutes", defaults.repeatMinutes),
                maxSendsPerEpisode = obj.optInt("maxSendsPerEpisode", defaults.maxSendsPerEpisode),
                allClearEnabled = obj.optBoolean("allClearEnabled", defaults.allClearEnabled),
                relayMode = obj.optString("relayMode", defaults.relayMode),
                relayIntervalMinutes = obj.optInt(
                    "relayIntervalMinutes",
                    defaults.relayIntervalMinutes
                ),
                relayOfflineGraceMinutes = obj.optInt(
                    "relayOfflineGraceMinutes",
                    defaults.relayOfflineGraceMinutes
                ),
                escalateOnlyWhenOffline = obj.optBoolean(
                    "escalateOnlyWhenOffline",
                    defaults.escalateOnlyWhenOffline
                ),
                maxPerHour = obj.optInt("maxPerHour", defaults.maxPerHour),
                maxPerDay = obj.optInt("maxPerDay", defaults.maxPerDay)
            ).sanitized()
        }

        private fun decodeContacts(array: JSONArray?): List<SmsContact> {
            if (array == null) return emptyList()
            val out = ArrayList<SmsContact>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                out += SmsContact.decode(item)
            }
            return out
        }

        private fun decodeAlertIds(array: JSONArray?): Set<Int> {
            if (array == null) return DEFAULT_ALARM_ALERT_IDS
            val out = LinkedHashSet<Int>(array.length())
            for (index in 0 until array.length()) {
                out += array.optInt(index, -1).takeIf { it >= 0 } ?: continue
            }
            return out
        }
    }
}

/**
 * One person who can be texted.
 *
 * [stage] is the rung on the escalation ladder: stage 0 is texted first, stage 1
 * only if the situation is still unresolved [SmsPolicy.stageStepMinutes] later,
 * and so on. [relay] marks the people who also want routine readings while the
 * data path is down — usually a parent, rarely the neighbour who is only there
 * for emergencies.
 */
@Keep
data class SmsContact(
    val number: String,
    val label: String = "",
    val stage: Int = 0,
    val relay: Boolean = false,
    val enabled: Boolean = true,
    /** Stable across edits so the editor can key its text state on the row, not its value. */
    val id: String = UUID.randomUUID().toString()
) {
    fun normalizedStage(): Int = stage.coerceIn(0, SmsPolicy.MAX_STAGE)

    fun displayName(): String = label.trim().ifBlank { number }

    fun sanitized(): SmsContact = copy(
        number = normalizeNumber(number),
        label = label.trim().take(SmsPolicy.MAX_SUBJECT_NAME_LENGTH),
        stage = normalizedStage()
    )

    @Keep
    companion object {
        /**
         * Keeps digits plus a single leading `+`. Formatting characters that
         * people paste from contact apps (spaces, dashes, parentheses) are dropped
         * because the telephony stack wants a dialable string.
         */
        fun normalizeNumber(raw: String): String {
            val trimmed = raw.trim()
            val hasPlus = trimmed.startsWith("+")
            val digits = trimmed.filter { it.isDigit() }
            if (digits.isEmpty()) return ""
            return if (hasPlus) "+$digits" else digits
        }

        fun isPlausibleNumber(raw: String): Boolean {
            val normalized = normalizeNumber(raw)
            val digits = normalized.count { it.isDigit() }
            return digits in 5..15
        }

        fun encode(contact: SmsContact): JSONObject =
            JSONObject()
                .put("id", contact.id)
                .put("number", contact.number)
                .put("label", contact.label)
                .put("stage", contact.normalizedStage())
                .put("relay", contact.relay)
                .put("enabled", contact.enabled)

        fun decode(obj: JSONObject): SmsContact =
            SmsContact(
                number = obj.optString("number", ""),
                label = obj.optString("label", ""),
                stage = obj.optInt("stage", 0),
                relay = obj.optBoolean("relay", false),
                enabled = obj.optBoolean("enabled", true),
                id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
            ).sanitized()
    }
}
