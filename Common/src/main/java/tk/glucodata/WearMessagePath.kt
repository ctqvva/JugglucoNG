package tk.glucodata

/**
 * The phone-watch message paths, as one closed set.
 *
 * The wire strings are copied verbatim from the constants this replaces, so the protocol does
 * not move when these are adopted. Each name is the last path segment, upper-cased with
 * underscores: that is the rule the path constants did not follow and now do, so a name always
 * says where the message goes.
 *
 * The point of a closed type rather than a list of strings is the dispatch in `MessageReceiver` is
 * currently a `when` on a `String`, which the compiler checks for nothing -- an unhandled path
 * is a log line at runtime. On this type the `when` is checked for exhaustiveness, which is the
 * plan §6 Q2 requirement to "define what happens with an unknown message" rather than to log
 * and continue.
 *
 * [wire] stays the single place a path exists as text. `WearMessagePathManifestTests` compares
 * it against both manifests and against the receiver, so a path cannot drift without the
 * existing guard noticing.
 */
enum class WearMessagePath(val wire: String) {
    ALERT_ACK("/alertack"),
    ASKFORSTART("/askforstart"),
    BLUETOOTH("/bluetooth"),
    CALIBRATE("/calibrate"),
    DATA("/data"),
    DEFAULTS("/defaults"),
    DISPLAY_PREFS("/displayprefs"),
    DISPLAY_PREFS_MAINSENSOR("/displayprefs/mainsensor"),
    DISPLAY_PREFS_REQ("/displayprefs/req"),
    GLUCOSE_COLORS("/glucosecolors"),
    MESSAGES("/messages"),
    NETINFO("/netinfo"),
    PROTOCOL("/protocol"),
    SENSOR_CLAIM_STATUS("/sensorclaimstatus"),
    SENSOR_HANDOFF("/sensorhandoff"),
    START("/start"),
    SYNC2_CAL("/sync2/cal"),
    SYNC2_CALCMD("/sync2/calcmd"),
    SYNC2_CHUNK("/sync2/chunk"),
    SYNC2_JOURNAL_DATA("/sync2/journal"),
    SYNC2_JOURNAL_CMD("/sync2/journal/cmd"),
    SYNC2_JOURNAL_REQ("/sync2/journal/req"),
    SYNC2_OWN("/sync2/own"),
    SYNC2_REMOVE("/sync2/remove"),
    SYNC2_REQ("/sync2/req"),
    TOGGLES("/toggles"),
    TOGGLES_REQ("/toggles/req"),
    TOGGLES_SET("/toggles/set"),
    WAKE("/wake"),
    WAKESTREAM("/wakestream")
;

    companion object {
        /** The path as it travels on the wire, or null for a path this build does not know. */
        @JvmStatic
        fun fromWire(raw: String?): WearMessagePath? =
            entries.firstOrNull { it.wire == raw }
    }
}
