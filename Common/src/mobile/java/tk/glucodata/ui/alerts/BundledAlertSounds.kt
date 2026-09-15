package tk.glucodata.ui.alerts

import java.util.Locale
import tk.glucodata.alerts.AlertType

/** Named resource URIs survive resource-ID reassignment between app updates. */
internal object BundledAlertSounds {
    // Collection names are proper names, identical in every locale.
    val styles = listOf("Contour", "Porcelain", "Halo")
    private val cues = setOf("low", "high", "urgent_low", "urgent_high", "falling", "rising", "signal", "reminder", "notice")

    fun cueFor(alertTypeId: Int): String = when (AlertType.fromId(alertTypeId)) {
        AlertType.LOW -> "low"
        AlertType.HIGH, AlertType.PERSISTENT_HIGH -> "high"
        AlertType.AVAILABLE -> "notice"
        AlertType.AMOUNT, AlertType.SENSOR_EXPIRY -> "reminder"
        AlertType.LOSS, AlertType.MISSED_READING -> "signal"
        AlertType.VERY_LOW -> "urgent_low"
        AlertType.VERY_HIGH -> "urgent_high"
        AlertType.PRE_LOW, AlertType.FALLING_FAST -> "falling"
        AlertType.PRE_HIGH, AlertType.RISING_FAST -> "rising"
        null -> "notice"
    }

    fun uri(packageName: String, style: String, alertTypeId: Int): String {
        require(style in styles)
        return "android.resource://$packageName/raw/alert_${style.lowercase(Locale.ROOT)}_${cueFor(alertTypeId)}"
    }

    fun styleFor(uri: String?, packageName: String): String? {
        val prefix = "android.resource://$packageName/raw/alert_"
        if (uri == null || !uri.startsWith(prefix)) return null
        val name = uri.removePrefix(prefix)
        return styles.firstOrNull { style ->
            val stylePrefix = style.lowercase(Locale.ROOT) + "_"
            name.startsWith(stylePrefix) && name.removePrefix(stylePrefix) in cues
        }
    }

    /** Applying a collection globally preserves each destination's alert meaning. */
    fun forAlert(selectedUri: String?, packageName: String, alertTypeId: Int): String? {
        val style = styleFor(selectedUri, packageName) ?: return selectedUri
        return uri(packageName, style, alertTypeId)
    }
}
