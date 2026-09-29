package tk.glucodata

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.settings.SettingDefinition
import tk.glucodata.settings.SettingType
import tk.glucodata.settings.SettingsRegistry

/**
 * Mirrors the phone's display preferences onto the watch.
 *
 * The watch keeps its own SharedPreferences, so every setting the user changes
 * on the phone is invisible to it unless something carries it across. Smoothing
 * was the first case to bite: the watch read [DataSmoothing] happily, found the
 * compiled-in defaults, and drew an unsmoothed curve beside a smoothed phone.
 *
 * The list of keys that travel is not kept here any more: it is
 * [SettingsRegistry.mirrored], so a default is declared once and cannot disagree
 * with itself (plan §2.4). This object only serialises it — the line format is
 * unchanged, because an older peer has to keep reading it.
 *
 * Colours travel over the same channel but with their own apply step
 * ([SensorVisuals.invalidateOverrides]), so they are encoded here and
 * re-applied on the receiving side by [MessageReceiver].
 */
object WearPrefsSync {
    private const val LOG_ID = "WearPrefsSync"
    private const val PREFS = SettingsRegistry.FILE

    /** The sensor order the phone displays, primary first. */
    const val KEY_SENSOR_SELECTION = tk.glucodata.settings.KEY_SENSOR_SELECTION

    private val KEY_SENSOR_COLORS = tk.glucodata.settings.KEY_SENSOR_COLORS
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun mirrorValue(definition: SettingDefinition, source: SharedPreferences): String? {
        definition.effectiveString?.let { return it() }
        return when (definition.type) {
            SettingType.INT -> definition.readInt(source).toString()
            SettingType.BOOL -> definition.readBool(source).toString()
            SettingType.FLOAT -> definition.readFloat(source).toString()
            // A string with a line break in it would be read back as two keys; it
            // is escaped on the way out and restored on the way in.
            SettingType.STRING -> escapeLine(definition.readString(source))
        }
    }

    /** Serialises the mirrored keys this device currently holds. */
    @JvmStatic
    fun encode(context: Context?): ByteArray {
        if (context == null) return ByteArray(0)
        val source = prefs(context)
        val text = buildString {
            // The protocol version, first. An old receiver has no `=` on this line and skips it, so
            // the settings themselves still arrive; a newer receiver checks it before applying.
            append(WearProtocol.versionLine()).append('\n')
            SettingsRegistry.mirrored.forEach { definition ->
                val raw = mirrorValue(definition, source) ?: return@forEach
                if (raw.contains('\n')) return@forEach
                append(definition.type.wire).append(':').append(definition.key).append('=').append(raw).append('\n')
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * Applies a received payload. Returns the number of keys written, so an
     * empty or unreadable payload leaves this device's settings alone rather
     * than resetting them.
     */
    @JvmStatic
    fun apply(context: Context?, data: ByteArray?): Int {
        if (context == null || data == null || data.isEmpty()) return 0
        val text = try {
            data.toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "decode", t)
            return 0
        }
        val declaredVersion = WearProtocol.declaredVersion(text)
        if (!WearProtocol.accepts(declaredVersion)) {
            // A newer peer's payload: applying a shape this build does not know is worse than
            // leaving the settings alone. A legacy payload (no version line) is version 1.
            Log.w(
                LOG_ID,
                "ignoring display prefs from a newer protocol: v$declaredVersion > v${WearProtocol.VERSION}",
            )
            return 0
        }
        val lines = text.lines()

        val editor = prefs(context).edit()
        var written = 0
        var sensorSelectionChanged = false
        var sensorColorsChanged = false
        lines.forEach { line ->
            val typeSplit = line.indexOf(':')
            val valueSplit = line.indexOf('=')
            if (typeSplit <= 0 || valueSplit <= typeSplit + 1) return@forEach
            val type = line.substring(0, typeSplit)
            val key = line.substring(typeSplit + 1, valueSplit)
            val raw = line.substring(valueSplit + 1)
            // An unknown key, or one that arrives as the wrong type, is skipped:
            // writing it would give this device a pref it cannot read back.
            val definition = SettingsRegistry.find(key) ?: return@forEach
            if (definition.type.wire != type) return@forEach
            when (definition.type) {
                SettingType.INT -> raw.toIntOrNull()?.let { editor.putInt(key, it); written++ }
                SettingType.BOOL -> raw.toBooleanStrictOrNull()?.let { editor.putBoolean(key, it); written++ }
                SettingType.FLOAT -> raw.toFloatOrNull()
                    ?.takeIf { it.isFinite() }
                    ?.let { editor.putFloat(key, it); written++ }
                SettingType.STRING -> {
                    val value = when (key) {
                        // Stored in the form MultiSensorSelection reads.
                        KEY_SENSOR_SELECTION -> SettingsRegistry.selectionFromWire(raw)
                            .joinToString(MultiSensorSelection.SEPARATOR)
                        else -> unescapeLine(raw)
                    }
                    if (key == KEY_SENSOR_SELECTION && value != prefs(context).getString(key, "")) {
                        sensorSelectionChanged = true
                    }
                    if (key == KEY_SENSOR_COLORS && value != prefs(context).getString(key, "")) {
                        sensorColorsChanged = true
                    }
                    editor.putString(key, value)
                    written++
                }
            }
        }
        if (written == 0) return 0
        editor.apply()
        // This watch now holds the phone's preferences: a resync it owed is done.
        if (Applic.isWearable) WearSensorSelectionSync.onPhonePrefsApplied()
        if (sensorColorsChanged) SensorVisuals.invalidateOverrides()
        if (sensorSelectionChanged) {
            MultiSensorSelection.notifyStoredChanged()
            // The native "current sensor" slot is what the complications and
            // the watch face resolve through; keep it on the phone's primary.
            if (Applic.isWearable) WearSensorSelectionSync.alignCurrentSensor()
        }
        UiRefreshBus.requestDataRefresh()
        return written
    }

    @JvmStatic
    fun selectionToWire(sensorIds: List<String>): String = SettingsRegistry.selectionToWire(sensorIds)

    @JvmStatic
    fun selectionFromWire(raw: String): List<String> = SettingsRegistry.selectionFromWire(raw)

    /** A string value on one payload line: line breaks and backslashes escaped. */
    @JvmStatic
    fun escapeLine(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n")

    @JvmStatic
    fun unescapeLine(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> { out.append('\n'); index += 2; continue }
                    '\\' -> { out.append('\\'); index += 2; continue }
                }
            }
            out.append(c)
            index++
        }
        return out.toString()
    }

    // What was last sent to a watch known to be listening (pushTo, pushIfChanged),
    // so the periodic re-push stays silent while nothing changes. A broadcast does
    // not record it (see push()): a watch that was off or out of reach when the
    // user changed a setting must still catch up on its own.
    @Volatile private var lastSentHash: Int? = null

    @JvmStatic
    fun push() {
        runCatching {
            val payload = encode(Applic.app)
            if (payload.isEmpty()) return
            MessageSender.getMessageSender()?.sendWearPrefs(payload)
            // A broadcast is not known to have arrived: with no watch in reach it is dropped.
            // Forget what was sent, so the next SYNC2_REQ, which proves a watch is there,
            // sends it once more through pushIfChanged.
            lastSentHash = null
        }.onFailure { Log.stack(LOG_ID, "push", it) }
    }

    @JvmStatic
    fun pushTo(nodeName: String?) {
        val target = nodeName ?: return
        runCatching {
            val payload = encode(Applic.app)
            if (payload.isEmpty()) return
            MessageSender.getMessageSender()?.sendWearPrefs(target, payload)
            lastSentHash = payload.contentHashCode()
        }.onFailure { Log.stack(LOG_ID, "pushTo", it) }
    }

    /** Pushes only when something changed since the last send. */
    @JvmStatic
    fun pushIfChanged(nodeName: String?) {
        val target = nodeName ?: return
        runCatching {
            val payload = encode(Applic.app)
            if (payload.isEmpty()) return
            val hash = payload.contentHashCode()
            if (hash == lastSentHash) return
            MessageSender.getMessageSender()?.sendWearPrefs(target, payload)
            lastSentHash = hash
        }.onFailure { Log.stack(LOG_ID, "pushIfChanged", it) }
    }
}
