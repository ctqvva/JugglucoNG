package tk.glucodata

import android.content.Context

/**
 * Mirrors the phone's glucose colour scheme onto the watch.
 *
 * [GlucoseRangeColors] resolves every band and traffic tone from a preset plus
 * per-band overrides held in SharedPreferences. Those prefs are per device, so
 * a watch always painted the compiled-in Muted defaults no matter what the user
 * had picked on the phone — "the same colours" was only ever true by accident.
 *
 * The payload is a short key=value text block rather than a packed struct: it
 * is sent on connect and on change (a handful of bytes, rarely), and a
 * self-describing format lets a newer phone talk to an older watch without the
 * watch mis-reading fields it does not know.
 */
object GlucoseColorSync {
    private const val LOG_ID = "GlucoseColorSync"
    private const val KEY_PALETTE = "palette"
    private const val KEY_TARGET_BACKGROUND = "target_background"
    private const val KEY_VALUE_RANGE_COLORS = "value_range_colors"
    private const val NONE = "none"

    /**
     * A complete colour scheme as it travels between devices. Every override is
     * nullable because "no override" has to survive the trip: sending 0 for an
     * absent one would paint the watch transparent black.
     */
    data class Scheme(
        val palette: String,
        /** Per-band ARGB overrides, indexed by [GlucoseRangeColors.Band.ordinal]. */
        val overrides: List<Int?>,
        val targetBackground: Int?,
        val valueRangeColors: Boolean,
    )

    private val bandCount = GlucoseRangeColors.Band.values().size

    /** Serialises a scheme. Pure, so the wire format is testable on its own. */
    @JvmStatic
    fun encodeScheme(scheme: Scheme): ByteArray = buildString {
        append(KEY_PALETTE).append('=').append(scheme.palette).append('\n')
        GlucoseRangeColors.Band.values().forEach { band ->
            append(GlucoseRangeColors.PREF_OVERRIDE_KEYS[band.ordinal])
                .append('=')
                .append(scheme.overrides.getOrNull(band.ordinal)?.toString() ?: NONE)
                .append('\n')
        }
        append(KEY_TARGET_BACKGROUND).append('=')
            .append(scheme.targetBackground?.toString() ?: NONE).append('\n')
        append(KEY_VALUE_RANGE_COLORS).append('=').append(scheme.valueRangeColors).append('\n')
    }.toByteArray(Charsets.UTF_8)

    /** Parses a payload, or null when it carries nothing usable. */
    @JvmStatic
    fun decodeScheme(data: ByteArray?): Scheme? {
        if (data == null || data.isEmpty()) return null
        val fields = try {
            data.toString(Charsets.UTF_8)
                .lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "decode", t)
            return null
        }
        // A payload without a palette is not one of ours; refuse it rather than
        // reset the watch to the defaults on a stray message.
        val palette = fields[KEY_PALETTE]?.takeIf { it.isNotEmpty() } ?: return null
        val overrides = (0 until bandCount).map { index ->
            fields[GlucoseRangeColors.PREF_OVERRIDE_KEYS[index]]
                ?.takeUnless { it == NONE }
                ?.toIntOrNull()
        }
        return Scheme(
            palette = palette,
            overrides = overrides,
            targetBackground = fields[KEY_TARGET_BACKGROUND]?.takeUnless { it == NONE }?.toIntOrNull(),
            valueRangeColors = fields[KEY_VALUE_RANGE_COLORS].toBoolean(),
        )
    }

    /** This device's current scheme, ready to send. */
    @JvmStatic
    fun encode(context: Context?): ByteArray = encodeScheme(currentScheme(context))

    private fun currentScheme(context: Context?): Scheme {
        val prefs = context?.getSharedPreferences(GlucoseRangeColors.PREF_FILE, Context.MODE_PRIVATE)
        return Scheme(
            palette = GlucoseRangeColors.getPalette().name,
            overrides = GlucoseRangeColors.Band.values().map { GlucoseRangeColors.getOverride(it) },
            targetBackground = GlucoseRangeColors.getTargetBackgroundOverride(),
            valueRangeColors = prefs?.getBoolean(GlucoseValueTone.PREF_VALUE_RANGE_COLORS, false) ?: false,
        )
    }

    /**
     * Persists a received scheme into this device's prefs and applies it live.
     * Returns false for a payload that could not be read, leaving the current
     * colours alone rather than resetting them to the defaults.
     */
    @JvmStatic
    fun apply(context: Context?, data: ByteArray?): Boolean {
        if (context == null) return false
        val scheme = decodeScheme(data) ?: return false

        val prefs = context.getSharedPreferences(GlucoseRangeColors.PREF_FILE, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(GlucoseRangeColors.PREF_PALETTE, scheme.palette)
        GlucoseRangeColors.Band.values().forEach { band ->
            val key = GlucoseRangeColors.PREF_OVERRIDE_KEYS[band.ordinal]
            val argb = scheme.overrides.getOrNull(band.ordinal)
            if (argb == null) editor.remove(key) else editor.putInt(key, argb)
        }
        if (scheme.targetBackground == null) {
            editor.remove(GlucoseRangeColors.PREF_TARGET_BACKGROUND)
        } else {
            editor.putInt(GlucoseRangeColors.PREF_TARGET_BACKGROUND, scheme.targetBackground)
        }
        editor.putBoolean(GlucoseValueTone.PREF_VALUE_RANGE_COLORS, scheme.valueRangeColors)
        editor.apply()

        // initFromPrefs is the same path app start uses, so the static getters
        // and every Compose reader of the palette pick the change up at once.
        GlucoseRangeColors.initFromPrefs(context)
        UiRefreshBus.requestDataRefresh()
        return true
    }

    /** Pushes the current scheme to every paired node. */
    @JvmStatic
    fun push() {
        runCatching {
            val payload = encode(Applic.app)
            MessageSender.getMessageSender()?.sendGlucoseColors(payload)
            lastSentHash = payload.contentHashCode()
        }.onFailure { Log.stack(LOG_ID, "push", it) }
    }

    /** Pushes the current scheme to one node, for the watch's connect handshake. */
    @JvmStatic
    fun pushTo(nodeName: String?) {
        val target = nodeName ?: return
        runCatching {
            val payload = encode(Applic.app)
            MessageSender.getMessageSender()?.sendGlucoseColors(target, payload)
            lastSentHash = payload.contentHashCode()
        }.onFailure { Log.stack(LOG_ID, "pushTo", it) }
    }

    // What was last put on the wire, so the periodic re-push stays silent while
    // nothing changes.
    @Volatile private var lastSentHash: Int? = null

    /**
     * Pushes only when the scheme differs from the last one sent.
     *
     * Change-triggered pushes alone are not enough: a watch that was off, or
     * that was installed after the user last touched the palette, would never
     * hear about a scheme and sit on the compiled-in defaults indefinitely.
     * This rides along with the sync the watch already asks for, so it converges
     * on its own without adding chatter.
     */
    @JvmStatic
    fun pushIfChanged(nodeName: String?) {
        val target = nodeName ?: return
        runCatching {
            val payload = encode(Applic.app)
            val hash = payload.contentHashCode()
            if (hash == lastSentHash) return
            MessageSender.getMessageSender()?.sendGlucoseColors(target, payload)
            lastSentHash = hash
        }.onFailure { Log.stack(LOG_ID, "pushIfChanged", it) }
    }
}
