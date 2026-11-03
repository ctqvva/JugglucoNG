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
    private val calibrationRoutes = setOf(
        "calibrations",
        "calibrations/model-table",
    )

    fun isOpenWhileLocked(route: String?, config: RestrictedModeConfig): Boolean = when {
        // Before the graph has its first route there is nothing to guard.
        route == null -> true
        route in alwaysOpen -> true
        route == "journal" -> config.allowJournal
        route in calibrationRoutes -> config.allowCalibration
        // Settings and every screen under it, including the calibration list reached from
        // the settings, need full mode.
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
