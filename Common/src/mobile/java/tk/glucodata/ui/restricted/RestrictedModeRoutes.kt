package tk.glucodata.ui.restricted

import androidx.compose.runtime.staticCompositionLocalOf
import tk.glucodata.RestrictedModeConfig

/**
 * Which screens a restricted phone opens without unlocking. One list, read by the
 * navigation gate in MainApp, so a route reached any way — a tab, a dashboard link, a
 * notification tap — is checked the same way.
 */
internal object RestrictedModeRoutes {
    const val ALARMS = "settings/alerts"
    const val VOICE = "settings/alerts/talker"
    const val DISPLAY_CHARTS = "settings/display-colors"
    const val DISPLAY_NOTIFICATION = "settings/notification-display"
    const val DISPLAY_FLOATING = "settings/floating-display"
    const val DISPLAY_LOCK_SCREEN = "settings/aod-display"
    const val READINESS = "settings/cgm-readiness"

    /**
     * How readings look, not what they are: smoothing and prediction change the numbers
     * on screen, so they stay out of this list.
     */
    private val displayRoutes = setOf(DISPLAY_CHARTS, DISPLAY_NOTIFICATION, DISPLAY_FLOATING, DISPLAY_LOCK_SCREEN)

    private val alwaysOpen = setOf(
        "dashboard",
        "stats",
        "sensors",
        "history",
        // The Settings tab opens to its restricted page: the way to full mode, and the few
        // settings that are safe to change.
        "settings",
        // Readiness only checks permissions and battery settings that keep a sensor working.
        READINESS,
    )

    fun isOpenWhileLocked(route: String?, config: RestrictedModeConfig): Boolean = when {
        // Before the graph has its first route there is nothing to guard.
        route == null -> true
        route in alwaysOpen -> true
        route == "journal" -> config.allowJournal
        route == ALARMS -> config.allowAlarmSettings
        route == VOICE -> config.allowVoiceSettings
        route in displayRoutes -> config.allowDisplaySettings
        // Everything else needs full mode: every other screen under settings, and the
        // calibration list, which holds the calibration settings and "clear all".
        // Allowing calibration opens the add/edit sheet, not these screens.
        else -> false
    }
}

/**
 * Asks for full mode: MainApp opens the Settings tab, where the switch to full mode lives,
 * and runs the action once unlocked. For things reached by navigating (a locked screen,
 * calibration). The default (outside MainApp, in previews) runs the action straight away.
 */
internal val LocalRequestFullMode = staticCompositionLocalOf<(onUnlocked: () -> Unit) -> Unit> {
    { onUnlocked -> onUnlocked() }
}


/**
 * The sensor families that can be added right now: null for all of them (not restricted),
 * otherwise the adult's list, or none when sensor changes are not allowed.
 */
internal fun tk.glucodata.RestrictedModeState.addableSensorTypes(): Set<tk.glucodata.ui.components.SensorType>? = when {
    !locked -> null
    !config.allowSensorChange -> emptySet()
    else -> config.allowedSensorTypes.mapNotNull { name ->
        tk.glucodata.ui.components.SensorType.entries.firstOrNull { it.name == name }
    }.toSet()
}

/**
 * For an action inside a screen that leaving would lose (an editor, a setup flow): runs it,
 * or while restricted says where full mode is instead of opening Settings over the work.
 */
@androidx.compose.runtime.Composable
internal fun rememberFullModeGuard(): (() -> Unit) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        { action ->
            if (tk.glucodata.RestrictedMode.isLocked()) {
                android.widget.Toast.makeText(
                    context,
                    tk.glucodata.R.string.restricted_needs_full_mode,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } else {
                action()
            }
        }
    }
}
