package tk.glucodata

import android.content.Context

/**
 * Mirrors the phone's display preferences onto the watch.
 *
 * The watch keeps its own SharedPreferences, so every setting the user changes
 * on the phone is invisible to it unless something carries it across. Smoothing
 * was the first case to bite: the watch read [DataSmoothing] happily, found the
 * compiled-in defaults, and drew an unsmoothed curve beside a smoothed phone.
 *
 * Only the keys named in [MIRRORED] travel, so a watch-local setting is never
 * stamped on by the phone, and a payload from a newer phone carrying keys this
 * build has never heard of is ignored rather than mis-applied.
 *
 * Colours have their own channel ([GlucoseColorSync]) because applying them
 * means re-running the palette's own load, not just writing prefs.
 */
object WearPrefsSync {
    private const val LOG_ID = "WearPrefsSync"
    private const val PREFS = "tk.glucodata_preferences"

    private const val TYPE_INT = "i"
    private const val TYPE_BOOL = "b"
    private const val TYPE_FLOAT = "f"

    private class Mirrored(val type: String, val default: Any)

    /**
     * The keys the phone owns, with the type each is stored as and the default
     * the phone reads it with.
     *
     * The default matters as much as the key. A setting the user has never
     * touched is absent from the phone's preferences, so sending only what is
     * stored sent nothing — and the watch fell back to its own default, which
     * for the predictive simulation was the opposite of the phone's. It showed
     * "off" on a phone where it was on. The effective value always travels now,
     * and these defaults must stay in step with the phone's readers.
     */
    private val MIRRORED: Map<String, Mirrored> = mapOf(
        // Data smoothing — the window, and the three switches that decide
        // whether it reaches the graph at all.
        "dashboard_chart_smoothing_minutes" to Mirrored(TYPE_INT, 0),
        "dashboard_data_smoothing_graph_only" to Mirrored(TYPE_BOOL, false),
        "dashboard_data_smoothing_collapse_chunks" to Mirrored(TYPE_BOOL, false),
        "dashboard_data_smoothing_exchange_outputs_only" to Mirrored(TYPE_BOOL, false),
        // Predictive simulation. Both switches default on, as the phone reads them.
        "dashboard_predictive_simulation_enabled" to Mirrored(TYPE_BOOL, true),
        "dashboard_prediction_trend_momentum_enabled" to Mirrored(TYPE_BOOL, true),
        "dashboard_prediction_carb_ratio_g_per_u" to Mirrored(TYPE_FLOAT, 10f),
        "dashboard_prediction_insulin_sensitivity_mgdl_per_u" to Mirrored(TYPE_FLOAT, 54f),
        "dashboard_prediction_carb_absorption_g_per_h" to Mirrored(TYPE_FLOAT, 35f),
        "dashboard_prediction_horizon_minutes" to Mirrored(TYPE_INT, 120),
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Serialises the mirrored keys this device currently holds. */
    @JvmStatic
    fun encode(context: Context?): ByteArray {
        if (context == null) return ByteArray(0)
        val source = prefs(context)
        val text = buildString {
            MIRRORED.forEach { (key, spec) ->
                val raw = when (spec.type) {
                    TYPE_INT -> runCatching {
                        source.getInt(key, spec.default as Int).toString()
                    }.getOrNull()
                    TYPE_BOOL -> runCatching {
                        source.getBoolean(key, spec.default as Boolean).toString()
                    }.getOrNull()
                    TYPE_FLOAT -> runCatching {
                        source.getFloat(key, spec.default as Float).toString()
                    }.getOrNull()
                    else -> null
                } ?: return@forEach
                append(spec.type).append(':').append(key).append('=').append(raw).append('\n')
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
        val lines = try {
            data.toString(Charsets.UTF_8).lines()
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "decode", t)
            return 0
        }

        val editor = prefs(context).edit()
        var written = 0
        lines.forEach { line ->
            val typeSplit = line.indexOf(':')
            val valueSplit = line.indexOf('=')
            if (typeSplit <= 0 || valueSplit <= typeSplit + 1) return@forEach
            val type = line.substring(0, typeSplit)
            val key = line.substring(typeSplit + 1, valueSplit)
            val raw = line.substring(valueSplit + 1)
            // An unknown key, or one that arrives as the wrong type, is skipped:
            // writing it would give this device a pref it cannot read back.
            if (MIRRORED[key]?.type != type) return@forEach
            when (type) {
                TYPE_INT -> raw.toIntOrNull()?.let { editor.putInt(key, it); written++ }
                TYPE_BOOL -> raw.toBooleanStrictOrNull()?.let { editor.putBoolean(key, it); written++ }
                TYPE_FLOAT -> raw.toFloatOrNull()
                    ?.takeIf { it.isFinite() }
                    ?.let { editor.putFloat(key, it); written++ }
            }
        }
        if (written == 0) return 0
        editor.apply()
        UiRefreshBus.requestDataRefresh()
        return written
    }

    // What was last sent, so the periodic re-push stays silent while nothing
    // changes. Same convergence story as the colour scheme: a watch that was off
    // when the user changed a setting must still catch up on its own.
    @Volatile private var lastSentHash: Int? = null

    @JvmStatic
    fun push() {
        runCatching {
            val payload = encode(Applic.app)
            if (payload.isEmpty()) return
            MessageSender.getMessageSender()?.sendWearPrefs(payload)
            lastSentHash = payload.contentHashCode()
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
