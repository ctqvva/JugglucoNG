package tk.glucodata

import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType

/**
 * Two-way mirror for the plain on/off switches the watch can operate: the
 * exchange outputs, and whether each alert is enabled.
 *
 * The display preferences ([WearPrefsSync]) only travel phone→watch, because
 * the phone owns them. These are different: the watch has a screen for each,
 * and a switch that only changes the watch's own copy is worse than no switch —
 * the alerts screen has been doing exactly that, editing a device-local
 * SharedPreferences file the phone never sees.
 *
 * So the phone stays the authority, and the watch asks it to change something
 * rather than changing it locally. The phone applies, then broadcasts the new
 * state; the watch renders what came back. A toggle that the phone refuses
 * therefore snaps back on its own instead of lying about what is on.
 *
 * The exchange outputs only ever run on the phone, so its copy is the only one
 * that matters. Alerts fire on both, so the applied state is written on
 * whichever device receives it.
 */
object WearToggleSync {
    private const val LOG_ID = "WearToggleSync"

    const val SCOPE_EXCHANGE = "x"
    const val SCOPE_ALERT = "a"

    /** Boolean display preferences the watch is allowed to flip. */
    const val SCOPE_PREF = "p"

    private const val PREFS_FILE = "tk.glucodata_preferences"

    /** id -> (preference key, the default the phone reads it with). */
    private val PREF_TOGGLES: Map<String, Pair<String, Boolean>> = mapOf(
        "prediction" to ("dashboard_predictive_simulation_enabled" to true),
        AutoSensorSwitch.TOGGLE_ID to (AutoSensorSwitch.PREF_KEY to false),
    )

    data class Toggle(val scope: String, val id: String, val enabled: Boolean)

    /** The switches this device currently holds. */
    @JvmStatic
    fun currentState(): List<Toggle> {
        val result = ArrayList<Toggle>()
        // Exchange outputs are phone-side only; on the watch this reads its own
        // (unused) natives, so the watch always renders the received state.
        if (!Applic.isWearable) {
            ExchangeToggles.all.forEach { toggle ->
                result += Toggle(SCOPE_EXCHANGE, toggle.id, toggle.isEnabled())
            }
        }
        if (!Applic.isWearable) {
            val prefs = Applic.app?.getSharedPreferences(PREFS_FILE, android.content.Context.MODE_PRIVATE)
            PREF_TOGGLES.forEach { (id, spec) ->
                val (key, default) = spec
                prefs?.let { result += Toggle(SCOPE_PREF, id, it.getBoolean(key, default)) }
            }
        }
        AlertType.settingsEntries.forEach { type ->
            runCatching { AlertRepository.loadConfig(type).enabled }
                .getOrNull()
                ?.let { result += Toggle(SCOPE_ALERT, type.id.toString(), it) }
        }
        return result
    }

    @JvmStatic
    fun encode(toggles: List<Toggle>): ByteArray = buildString {
        toggles.forEach { append(it.scope).append(':').append(it.id).append('=').append(it.enabled).append('\n') }
    }.toByteArray(Charsets.UTF_8)

    @JvmStatic
    fun decode(data: ByteArray?): List<Toggle> {
        if (data == null || data.isEmpty()) return emptyList()
        return runCatching {
            data.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
                val scopeSplit = line.indexOf(':')
                val valueSplit = line.indexOf('=')
                if (scopeSplit <= 0 || valueSplit <= scopeSplit + 1) return@mapNotNull null
                val enabled = line.substring(valueSplit + 1).toBooleanStrictOrNull()
                    ?: return@mapNotNull null
                Toggle(
                    scope = line.substring(0, scopeSplit),
                    id = line.substring(scopeSplit + 1, valueSplit),
                    enabled = enabled,
                )
            }.toList()
        }.getOrElse {
            Log.stack(LOG_ID, "decode", it)
            emptyList()
        }
    }

    /**
     * Applies received switches to this device. Unknown ids are skipped, so a
     * watch on an older build cannot be handed a switch it has no code for.
     */
    @JvmStatic
    fun apply(toggles: List<Toggle>): Int {
        var applied = 0
        toggles.forEach { toggle ->
            when (toggle.scope) {
                SCOPE_EXCHANGE -> {
                    if (Applic.isWearable) return@forEach
                    val target = ExchangeToggles.byId(toggle.id) ?: return@forEach
                    if (target.isEnabled() != toggle.enabled) target.setEnabled(toggle.enabled)
                    applied++
                }
                SCOPE_PREF -> {
                    // Written on whichever device receives it: the phone owns
                    // the setting, and the watch needs its own copy to read.
                    val spec = PREF_TOGGLES[toggle.id] ?: return@forEach
                    if (toggle.id == AutoSensorSwitch.TOGGLE_ID) {
                        // Arming the radio is part of storing this one, so it
                        // goes through the owner rather than straight to prefs —
                        // but only on a real change. This state is pushed after
                        // every handshake, and bringing Bluetooth up again on
                        // each one restarts the watch's drivers mid-connection.
                        if (AutoSensorSwitch.isEnabled() != toggle.enabled) {
                            AutoSensorSwitch.setEnabled(toggle.enabled)
                        }
                        applied++
                        return@forEach
                    }
                    runCatching {
                        Applic.app
                            ?.getSharedPreferences(PREFS_FILE, android.content.Context.MODE_PRIVATE)
                            ?.edit()?.putBoolean(spec.first, toggle.enabled)?.apply()
                    }.onFailure { Log.stack(LOG_ID, "apply pref ${toggle.id}", it) }
                    applied++
                }
                SCOPE_ALERT -> {
                    val type = AlertType.settingsEntries
                        .firstOrNull { it.id.toString() == toggle.id } ?: return@forEach
                    runCatching {
                        val config = AlertRepository.loadConfig(type)
                        if (config.enabled != toggle.enabled) {
                            AlertRepository.saveConfig(config.copy(enabled = toggle.enabled))
                        }
                    }.onFailure { Log.stack(LOG_ID, "apply alert ${toggle.id}", it) }
                    applied++
                }
            }
        }
        if (applied > 0) UiRefreshBus.requestStatusRefresh()
        return applied
    }

    // ------------------------------------------------------------- watch side

    @Volatile private var received: List<Toggle> = emptyList()

    /** The last state the phone sent, for the watch's screens to render. */
    @JvmStatic
    fun known(scope: String): List<Toggle> = received.filter { it.scope == scope }

    @JvmStatic
    fun knownEnabled(scope: String, id: String): Boolean? =
        received.firstOrNull { it.scope == scope && it.id == id }?.enabled

    /** Watch: remembers what the phone reported, and applies what is local. */
    @JvmStatic
    fun onState(data: ByteArray?) {
        val toggles = decode(data)
        if (toggles.isEmpty()) return
        received = toggles
        // Alerts fire on the watch too, and the display preferences are read
        // there, so both have to be written locally from the reply.
        apply(toggles.filter { it.scope == SCOPE_ALERT || it.scope == SCOPE_PREF })
        UiRefreshBus.requestStatusRefresh()
    }

    /**
     * Watch: asks the phone to flip one switch. The local view updates only
     * when the phone answers, so a failure shows as the switch returning.
     */
    @JvmStatic
    fun request(scope: String, id: String, enabled: Boolean) {
        runCatching {
            MessageSender.getMessageSender()
                ?.sendToggleCommand(encode(listOf(Toggle(scope, id, enabled))))
        }.onFailure { Log.stack(LOG_ID, "request", it) }
    }

    /** Watch: asks for the current state of everything. */
    @JvmStatic
    fun requestState() {
        runCatching { MessageSender.getMessageSender()?.requestToggles() }
            .onFailure { Log.stack(LOG_ID, "requestState", it) }
    }

    // ------------------------------------------------------------- phone side

    @Volatile private var lastSentHash: Int? = null

    /** Phone: applies a watch command, then reports the resulting state back. */
    @JvmStatic
    fun onCommand(data: ByteArray?, sourceNodeId: String?) {
        if (Applic.isWearable) return
        val toggles = decode(data)
        if (toggles.isEmpty()) {
            Log.w(LOG_ID, "ignoring unusable toggle command")
            return
        }
        apply(toggles)
        // Report the state actually held, not the state asked for.
        pushTo(sourceNodeId)
    }

    @JvmStatic
    fun pushTo(nodeName: String?) {
        if (Applic.isWearable) return
        val target = nodeName ?: return
        runCatching {
            val payload = encode(currentState())
            MessageSender.getMessageSender()?.sendToggleState(target, payload)
            lastSentHash = payload.contentHashCode()
        }.onFailure { Log.stack(LOG_ID, "pushTo", it) }
    }

    /** Phone: broadcasts after a local change, so the watch never lags. */
    @JvmStatic
    fun push() {
        if (Applic.isWearable) return
        runCatching {
            val payload = encode(currentState())
            MessageSender.getMessageSender()?.sendToggleState(payload)
            lastSentHash = payload.contentHashCode()
        }.onFailure { Log.stack(LOG_ID, "push", it) }
    }

    /** Phone: silent unless something moved, for the periodic ride-along. */
    @JvmStatic
    fun pushIfChanged(nodeName: String?) {
        if (Applic.isWearable) return
        val target = nodeName ?: return
        runCatching {
            val payload = encode(currentState())
            val hash = payload.contentHashCode()
            if (hash == lastSentHash) return
            MessageSender.getMessageSender()?.sendToggleState(target, payload)
            lastSentHash = hash
        }.onFailure { Log.stack(LOG_ID, "pushIfChanged", it) }
    }
}
