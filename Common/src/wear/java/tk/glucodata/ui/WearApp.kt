package tk.glucodata.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import tk.glucodata.ui.screens.AlertsScreen
import tk.glucodata.ui.screens.CalibrationScreen
import tk.glucodata.ui.screens.ChartScreen
import tk.glucodata.ui.screens.MainScreen
import tk.glucodata.ui.screens.RecentReadingsScreen
import tk.glucodata.ui.screens.SensorScreen
import tk.glucodata.ui.screens.ExchangeScreen
import tk.glucodata.ui.screens.SettingsScreen

object WearRoutes {
    const val MAIN = "main"
    const val CHART = "chart"
    const val ALERTS = "alerts"
    const val SENSOR = "sensor"
    const val CALIBRATE = "calibrate"
    const val CALIBRATIONS = "calibrations"
    const val READINGS = "readings"
    const val SETTINGS = "settings"
    const val EXCHANGE = "exchange"
    const val JOURNAL = "journal"
    const val JOURNAL_ENTRY = "journalentry"
    const val READING_ACTIONS = "readingactions"
}

/**
 * Tapping a reading calibrates against it, or edits the calibration it already
 * carries — the phone behaves the same way.
 */
private fun calibrateRouteFor(point: tk.glucodata.GlucosePoint): String {
    val action = tk.glucodata.ui.screens.ReadingActions.resolve(point.timestamp)
    val sensorMgdl = if (tk.glucodata.Applic.unit == 1) point.value * 18.0182f else point.value
    return "${WearRoutes.CALIBRATE}?ts=${action.calibrationTimestamp}" +
        "&user=${action.calibrationUserValueMgdl}" +
        "&sensor=$sensorMgdl" +
        "&reading=${point.timestamp}"
}

/**
 * With the journal enabled a reading tap offers the same two choices the phone
 * does; with it off it calibrates directly, against that reading's timeframe.
 */
private fun readingTapRouteFor(point: tk.glucodata.GlucosePoint): String {
    if (!tk.glucodata.ui.screens.ReadingActions.journalAvailable()) {
        return calibrateRouteFor(point)
    }
    val action = tk.glucodata.ui.screens.ReadingActions.resolve(point.timestamp)
    val sensorMgdl = if (tk.glucodata.Applic.unit == 1) point.value * 18.0182f else point.value
    return "${WearRoutes.READING_ACTIONS}?ts=${action.calibrationTimestamp}" +
        "&user=${action.calibrationUserValueMgdl}" +
        "&sensor=$sensorMgdl" +
        "&reading=${point.timestamp}"
}

@Composable
fun WearApp() {
    val navController = rememberSwipeDismissableNavController()
    // System back (button/bezel gesture) pops the stack; at the root it
    // backgrounds the app like the legacy watch UI did, never finishing it.
    // Swipe-to-dismiss stays handled by SwipeDismissableNavHost.
    val activity = LocalContext.current as? Activity
    BackHandler {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            activity?.moveTaskToBack(true)
        }
    }
    AppScaffold {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = WearRoutes.MAIN,
        ) {
            composable(WearRoutes.MAIN) {
                MainScreen(
                    onOpenSettings = { navController.navigate(WearRoutes.SETTINGS) },
                    onOpenSensor = { navController.navigate(WearRoutes.SENSOR) },
                    onOpenReadings = { navController.navigate(WearRoutes.READINGS) },
                    onOpenCalibrations = { navController.navigate(WearRoutes.CALIBRATIONS) },
                    onOpenJournal = { navController.navigate(WearRoutes.JOURNAL) },
                    onCalibrateReading = { point -> navController.navigate(readingTapRouteFor(point)) },
                )
            }
            composable(WearRoutes.CHART) { ChartScreen() }
            composable(WearRoutes.READINGS) {
                RecentReadingsScreen(
                    onCalibrateReading = { point -> navController.navigate(readingTapRouteFor(point)) },
                )
            }
            composable(WearRoutes.CALIBRATIONS) {
                CalibrationScreen(
                    onCalibrate = { navController.navigate(WearRoutes.CALIBRATE) },
                    onEditCalibration = { timestamp, userValue, sensorValue ->
                        navController.navigate(
                            "${WearRoutes.CALIBRATE}?ts=$timestamp&user=$userValue&sensor=$sensorValue",
                        )
                    },
                )
            }
            composable(WearRoutes.ALERTS) { AlertsScreen() }
            composable(WearRoutes.SENSOR) {
                SensorScreen(onCalibrate = { navController.navigate(WearRoutes.CALIBRATE) })
            }
            composable(WearRoutes.CALIBRATE) {
                tk.glucodata.ui.screens.CalibrationEntryScreen(onDone = { navController.popBackStack() })
            }
            composable(
                route = "${WearRoutes.CALIBRATE}?ts={ts}&user={user}&sensor={sensor}&reading={reading}",
                arguments = listOf(
                    androidx.navigation.navArgument("ts") { defaultValue = "0" },
                    androidx.navigation.navArgument("user") { defaultValue = "NaN" },
                    androidx.navigation.navArgument("sensor") { defaultValue = "NaN" },
                    androidx.navigation.navArgument("reading") { defaultValue = "0" },
                ),
            ) { entry ->
                tk.glucodata.ui.screens.CalibrationEntryScreen(
                    onDone = { navController.popBackStack() },
                    editTimestamp = entry.arguments?.getString("ts")?.toLongOrNull() ?: 0L,
                    editUserValueMgdl = entry.arguments?.getString("user")?.toFloatOrNull() ?: Float.NaN,
                    sensorValueMgdl = entry.arguments?.getString("sensor")?.toFloatOrNull() ?: Float.NaN,
                    readingTimestamp = entry.arguments?.getString("reading")?.toLongOrNull() ?: 0L,
                )
            }
            composable(WearRoutes.JOURNAL) {
                tk.glucodata.ui.screens.JournalScreen(
                    onAddInsulin = { navController.navigate("${WearRoutes.JOURNAL_ENTRY}?insulin=1&reading=0") },
                    onAddCarbs = { navController.navigate("${WearRoutes.JOURNAL_ENTRY}?insulin=0&reading=0") },
                )
            }
            composable(
                route = "${WearRoutes.JOURNAL_ENTRY}?insulin={insulin}&reading={reading}",
                arguments = listOf(
                    androidx.navigation.navArgument("insulin") { defaultValue = "1" },
                    androidx.navigation.navArgument("reading") { defaultValue = "0" },
                ),
            ) { entry ->
                tk.glucodata.ui.screens.JournalEntryScreen(
                    isInsulin = entry.arguments?.getString("insulin") != "0",
                    timestampMs = entry.arguments?.getString("reading")?.toLongOrNull() ?: 0L,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                route = "${WearRoutes.READING_ACTIONS}?ts={ts}&user={user}&sensor={sensor}&reading={reading}",
                arguments = listOf(
                    androidx.navigation.navArgument("ts") { defaultValue = "0" },
                    androidx.navigation.navArgument("user") { defaultValue = "NaN" },
                    androidx.navigation.navArgument("sensor") { defaultValue = "NaN" },
                    androidx.navigation.navArgument("reading") { defaultValue = "0" },
                ),
            ) { entry ->
                val ts = entry.arguments?.getString("ts") ?: "0"
                val user = entry.arguments?.getString("user") ?: "NaN"
                val sensor = entry.arguments?.getString("sensor") ?: "NaN"
                val reading = entry.arguments?.getString("reading") ?: "0"
                tk.glucodata.ui.screens.ReadingActionChooser(
                    hasCalibration = (ts.toLongOrNull() ?: 0L) > 0L,
                    onAddJournal = {
                        navController.navigate("${WearRoutes.JOURNAL_ENTRY}?insulin=1&reading=$reading")
                    },
                    onCalibrate = {
                        navController.navigate(
                            "${WearRoutes.CALIBRATE}?ts=$ts&user=$user&sensor=$sensor&reading=$reading",
                        )
                    },
                )
            }
            composable(WearRoutes.SETTINGS) {
                SettingsScreen(
                    onOpenAlerts = { navController.navigate(WearRoutes.ALERTS) },
                    onOpenSensor = { navController.navigate(WearRoutes.SENSOR) },
                    onOpenExchange = { navController.navigate(WearRoutes.EXCHANGE) },
                )
            }
            composable(WearRoutes.EXCHANGE) { ExchangeScreen() }
        }
    }
}
