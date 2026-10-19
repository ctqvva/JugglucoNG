package tk.glucodata.ui.alerts

import java.util.Locale

/** Named resource URIs survive resource-ID reassignment between app updates. */
internal object BundledAlertSounds {
    // Collection names are proper names, identical in every locale.
    // Order is the picker order: xDrip-style sits directly above Juggluco.
    val styles = listOf("xDrip-style", "Juggluco", "Contour", "Porcelain", "Halo", "Timber", "Ember")
    private val cues = setOf("low", "high", "urgent_low", "urgent_high", "falling", "rising", "signal", "reminder", "notice")

    // Legacy low-bitrate MP3/OGG originals. Saved selections still resolve to
    // Juggluco; new selections use the remastered alert_juggluco_* WAVs below.
    private val legacyOriginals = mapOf(
        "low" to "siren", "high" to "classic", "notice" to "ghost",
        "reminder" to "nudge", "signal" to "elves", "urgent_low" to "verylow",
        "urgent_high" to "veryhigh", "falling" to "lowsoon", "rising" to "highsoon"
    )

    internal fun resourceStyle(style: String) = style.lowercase(Locale.ROOT).removeSuffix("-style")

    fun cueFor(alertTypeId: Int): String = tk.glucodata.alerts.AlertSoundDefaults.cueFor(alertTypeId)

    fun uri(packageName: String, style: String, alertTypeId: Int): String {
        require(style in styles)
        return "android.resource://$packageName/raw/alert_${resourceStyle(style)}_${cueFor(alertTypeId)}"
    }

    fun styleFor(uri: String?, packageName: String): String? {
        val rawPrefix = "android.resource://$packageName/raw/"
        if (uri != null && uri.startsWith(rawPrefix) && uri.removePrefix(rawPrefix) in legacyOriginals.values) {
            return "Juggluco"
        }
        val prefix = rawPrefix + "alert_"
        if (uri == null || !uri.startsWith(prefix)) return null
        val name = uri.removePrefix(prefix)
        return styles.firstOrNull { style ->
            val stylePrefix = resourceStyle(style) + "_"
            name.startsWith(stylePrefix) && name.removePrefix(stylePrefix) in cues
        }
    }

    /** Applying a collection globally preserves each destination's alert meaning. */
    fun forAlert(selectedUri: String?, packageName: String, alertTypeId: Int): String? {
        val style = styleFor(selectedUri, packageName) ?: return selectedUri
        return uri(packageName, style, alertTypeId)
    }
}
