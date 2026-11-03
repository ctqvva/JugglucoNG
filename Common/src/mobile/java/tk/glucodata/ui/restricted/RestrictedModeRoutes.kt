package tk.glucodata.ui.restricted

import androidx.compose.runtime.staticCompositionLocalOf
import tk.glucodata.RestrictedModeConfig

/**
 * Which screens a restricted phone opens without unlocking. One list, read by the
 * navigation gate in MainApp, so a route reached any way — a tab, a dashboard link, a
 * notification tap — is checked the same way.
 */
internal object RestrictedModeRoutes {
    private val alwaysOpen = setOf("dashboard", "stats", "sensors", "history")

    fun isOpenWhileLocked(route: String?, config: RestrictedModeConfig): Boolean = when {
        // Before the graph has its first route there is nothing to guard.
        route == null -> true
        route in alwaysOpen -> true
        route == "journal" -> config.allowJournal
        // Everything else needs full mode: settings and every screen under it, and the
        // calibration list, which holds the calibration settings and "clear all".
        // Allowing calibration opens the add/edit sheet, not these screens.
        else -> false
    }
}

/**
 * Asks for full mode, then runs the action once unlocked. MainApp provides the sheet; the
 * default (outside MainApp, in previews) runs the action straight away.
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
