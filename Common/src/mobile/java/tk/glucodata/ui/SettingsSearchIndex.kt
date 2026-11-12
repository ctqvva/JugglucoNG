package tk.glucodata.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.AreaChart
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import tk.glucodata.R

/** Something on the Settings tab itself that opens a dialog or sheet rather than a screen. */
internal enum class SettingsSearchAction {
    THEME, LANGUAGE, UNIT, KEEP_DISPLAYED_VALUES, BACKUPS, EXPORT, IMPORT,
    RESTART, CLEAR_HISTORY, FACTORY_RESET,
}

internal sealed interface SettingsSearchTarget {
    data class Route(val route: String) : SettingsSearchTarget
    data class Action(val action: SettingsSearchAction) : SettingsSearchTarget
}

/**
 * Rows inside a screen that a search result can point at. The screen's row wears the same
 * value as its `searchAnchor`, so opening the result scrolls to that row and marks it.
 */
internal object SettingsSearchAnchors {
    const val LOW_ALERTS = "alerts/low"
    const val HIGH_ALERTS = "alerts/high"
    const val PREDICTIVE_ALERTS = "alerts/predictive"
    const val OTHER_ALERTS = "alerts/other"
    const val QUIET_WINDOW = "alerts/quiet-window"
    const val TARGET_RANGE = "display-colors/target-range"
    const val RANGE_COLORS = "display-colors/range-colors"
    const val DASHBOARD_DELTA = "display-colors/delta"
    const val READING_AGE = "display-colors/reading-age"
    const val PREVIEW_WINDOW = "display-colors/preview-window"
    const val NOTIFICATION_ARROW = "notification/arrow"
    const val NOTIFICATION_CHART = "notification/chart"
    const val NOTIFICATION_DELTA = "notification/delta"
    const val NOTIFICATION_IOB = "notification/iob"
    const val XDRIP = "phone-apps/xdrip"
    const val XDRIP_FOLLOWER = "phone-apps/xdrip-follower"
    const val PATCHED_LIBRE = "phone-apps/patched-libre"
    const val GLUCODATA = "phone-apps/glucodata"
    const val HEALTH_CONNECT = "phone-apps/health-connect"
    const val LIBRE2_IDENTITY = "phone-apps/libre2-identity"
    const val APP_TREND = "phone-apps/app-trend"
    const val HANDOVER = "connection/handover"
    const val TURBO = "connection/turbo"
    const val AUTOCONNECT = "connection/autoconnect"
    const val VOICE_CHIME = "talker/chime"
    const val VOICE_OVERRIDE_SILENT = "talker/override-silent"
    const val VOICE_SCHEDULE = "talker/schedule"
    const val JOURNAL_TAB = "journal/tab"
    const val JOURNAL_QUICK_ADD = "journal/quick-add"
    const val JOURNAL_INSULIN_CHART = "journal/insulin-chart"
    const val JOURNAL_DOSE_CALCULATOR = "journal/dose-calculator"
    const val JOURNAL_HEALTH_ACTIVITY = "journal/health-activity"
    const val JOURNAL_AAPS_IMPORT = "journal/aaps-import"
    const val PREDICTION_MOMENTUM = "prediction/momentum"
    const val PREDICTION_NOTIFICATION_CHART = "prediction/notification-chart"
    const val PREDICTION_HORIZON = "prediction/horizon"
    const val PREDICTION_RECORD = "prediction/record"
    const val PREDICTION_ADAPTIVE = "prediction/adaptive"
    const val SMOOTHING_CAUSAL = "smoothing/causal"
    const val FLOATING_ISLAND = "floating/island"
    const val FLOATING_SECONDARY = "floating/secondary"
    const val AOD_LOCK_SCREEN = "aod/lock-screen"
    const val NIGHTSCOUT_SEND_TREATMENTS = "nightscout/send-treatments"
    const val NIGHTSCOUT_RECEIVE_TREATMENTS = "nightscout/receive-treatments"
    const val NIGHTSCOUT_UPLOAD_IOB = "nightscout/upload-iob"
    const val NIGHTSCOUT_V3 = "nightscout/v3"
    const val LIBREVIEW_OFFICIAL_END = "libreview/official-end"
}

/** Icon-tile role, matching the group colour the row wears on the Settings tab. */
internal enum class SettingsSearchTone { PRIMARY, SECONDARY, TERTIARY, NEUTRAL, ERROR }

/**
 * Settings that their screen only shows while they apply. Search follows the same rule, so
 * a result never opens a screen that lacks it.
 */
internal enum class SettingsSearchCondition {
    LIBREVIEW, DEXCOM_SHARE, MQ_ACCOUNT, OTTAI, HEALTH_CONNECT, LIBRE2_IDENTITY, APP_TREND,

    /** Nightscout's upload options: its Follow mode has no such rows. */
    NIGHTSCOUT_UPLOAD,
}

/**
 * One searchable setting. [path] is where it lives, read left to right (group, then the
 * screens leading to it) and shown as the result's subtitle. [keywords] are further
 * translated strings that should find it; [terms] are untranslated ones: product names and
 * the names a setting used to have. [anchor] is set for a setting that is a row inside the
 * screen [target] opens: that row is scrolled to and marked. [icon] is the row's own glyph
 * where it has one, so the result looks like the thing it opens onto.
 */
internal class SettingsSearchEntry(
    @StringRes val title: Int,
    val path: List<Int>,
    val icon: ImageVector,
    val tone: SettingsSearchTone,
    val target: SettingsSearchTarget,
    val keywords: List<Int> = emptyList(),
    val terms: List<String> = emptyList(),
    val condition: SettingsSearchCondition? = null,
    val anchor: String? = null,
)

/** An entry with its strings looked up in the current locale. */
internal class ResolvedSettingsSearchEntry(
    val entry: SettingsSearchEntry,
    val title: String,
    val location: String,
)

internal object SettingsSearchIndex {

    fun resolve(
        context: Context,
        isShown: (SettingsSearchCondition) -> Boolean,
    ): List<SettingsSearchMatcher.Candidate<ResolvedSettingsSearchEntry>> =
        entries
            .filter { entry -> entry.condition?.let(isShown) ?: true }
            .map { entry ->
                val title = context.getString(entry.title)
                val location = entry.path.joinToString(" › ") { context.getString(it) }
                SettingsSearchMatcher.Candidate(
                    item = ResolvedSettingsSearchEntry(entry, title, location),
                    title = title,
                    location = location,
                    terms = entry.keywords.map { context.getString(it) } + entry.terms,
                )
            }

    private fun route(route: String) = SettingsSearchTarget.Route(route)
    private fun action(action: SettingsSearchAction) = SettingsSearchTarget.Action(action)

    // Paths, by group. The first two groups have no label on screen, so their rows say
    // "Settings"; what lies inside them starts at the screen, as the user reaches it.
    private val TOP = listOf(R.string.settings)
    private val ALERTS = listOf(R.string.glucose_alerts_title)
    private val TALKER = ALERTS + R.string.talker
    private val JOURNAL = listOf(R.string.journal_title)
    private val JOURNAL_MAIN_SCREEN = JOURNAL + R.string.journal_section_main_screen
    private val JOURNAL_DOSING = JOURNAL + R.string.journal_section_dosing
    private val JOURNAL_IMPORT = JOURNAL + R.string.journal_section_import
    private val DISPLAY = listOf(R.string.settings_section_display)
    private val PREDICTION = DISPLAY + R.string.predictive_simulation_title
    private val SHARING = listOf(R.string.settings_section_sharing)
    private val PHONE_APPS = SHARING + R.string.settings_phone_apps_title
    private val NIGHTSCOUT = SHARING + R.string.nightscout_settings_title
    private val DEVICES = listOf(R.string.settings_section_devices)
    private val BACKUP = listOf(R.string.settings_section_backup)
    private val ADVANCED = listOf(R.string.advanced_title)
    private val CONNECTION = ADVANCED + R.string.settings_sensor_connection_title
    private val DANGER = listOf(R.string.danger_zone)

    private val P = SettingsSearchTone.PRIMARY
    private val S = SettingsSearchTone.SECONDARY
    private val T = SettingsSearchTone.TERTIARY
    private val N = SettingsSearchTone.NEUTRAL
    private val E = SettingsSearchTone.ERROR

    /**
     * Everything the search can find, in the order the rows appear: each Settings-tab row,
     * then the settings one level inside it that people look for by name. A result for a
     * deeper setting opens the screen that holds it.
     */
    val entries: List<SettingsSearchEntry> = listOf(
        // General (first group, no label)
        SettingsSearchEntry(R.string.theme_title, TOP, Icons.Default.DarkMode, P, action(SettingsSearchAction.THEME),
            keywords = listOf(R.string.theme_light, R.string.theme_dark)),
        SettingsSearchEntry(R.string.languagename, TOP, Icons.Default.Language, P, action(SettingsSearchAction.LANGUAGE)),
        SettingsSearchEntry(R.string.settings_unit_title, TOP, Icons.Default.Straighten, P, action(SettingsSearchAction.UNIT),
            terms = listOf("mg/dL", "mmol/L")),
        SettingsSearchEntry(R.string.app_updates_title, TOP, Icons.Default.SystemUpdate, P, route("settings/app-updates")),

        // Everyday (second group, no label)
        SettingsSearchEntry(R.string.glucose_alerts_title, TOP, Icons.Default.AddAlert, E, route("settings/alerts"),
            keywords = listOf(R.string.settings_alerts_desc)),
        SettingsSearchEntry(R.string.low_alerts_title, ALERTS, Icons.AutoMirrored.Filled.TrendingDown, E, route("settings/alerts"),
            keywords = listOf(R.string.alert_very_low, R.string.alert_forecast_low),
            anchor = SettingsSearchAnchors.LOW_ALERTS),
        SettingsSearchEntry(R.string.high_alerts_title, ALERTS, Icons.AutoMirrored.Filled.TrendingUp, E, route("settings/alerts"),
            keywords = listOf(R.string.alert_very_high, R.string.alert_forecast_high, R.string.alert_persistent_high),
            anchor = SettingsSearchAnchors.HIGH_ALERTS),
        SettingsSearchEntry(R.string.predictive_alerts, ALERTS, Icons.AutoMirrored.Filled.TrendingDown, E, route("settings/alerts"),
            keywords = listOf(R.string.alert_falling_fast, R.string.alert_rising_fast),
            anchor = SettingsSearchAnchors.PREDICTIVE_ALERTS),
        SettingsSearchEntry(R.string.other_alerts, ALERTS, Icons.Default.NotificationsActive, E, route("settings/alerts"),
            keywords = listOf(R.string.alert_loss, R.string.alert_signal_restored, R.string.alert_missed_reading, R.string.alert_sensor_expiry),
            anchor = SettingsSearchAnchors.OTHER_ALERTS),
        SettingsSearchEntry(R.string.quiet_window_title, ALERTS, Icons.Default.DoNotDisturbOn, E, route("settings/alerts"),
            anchor = SettingsSearchAnchors.QUIET_WINDOW),
        SettingsSearchEntry(R.string.talker, ALERTS, Icons.AutoMirrored.Filled.VolumeUp, E, route("settings/alerts/talker"),
            keywords = listOf(R.string.speakglucose, R.string.voice_readings),
            terms = listOf("TTS")),
        SettingsSearchEntry(R.string.voice_chime_title, TALKER, Icons.Default.Doorbell, E, route("settings/alerts/talker"),
            anchor = SettingsSearchAnchors.VOICE_CHIME),
        SettingsSearchEntry(R.string.override_silent_mode, TALKER, Icons.Default.NotificationsActive, E,
            route("settings/alerts/talker"), anchor = SettingsSearchAnchors.VOICE_OVERRIDE_SILENT),
        SettingsSearchEntry(R.string.voice_schedule_title, TALKER, Icons.Default.Schedule, E, route("settings/alerts/talker"),
            anchor = SettingsSearchAnchors.VOICE_SCHEDULE),
        SettingsSearchEntry(R.string.settings_calibration_title, TOP, Icons.Default.WaterDrop, P, route("settings/calibrations"),
            keywords = listOf(R.string.calibration_from_journal_title),
            terms = listOf("fingerstick")),
        SettingsSearchEntry(R.string.journal_title, TOP, Icons.Default.EditNote, P, route("settings/journal"),
            keywords = listOf(R.string.settings_journal_desc)),
        SettingsSearchEntry(R.string.journal_food_library, JOURNAL, Icons.Default.Restaurant, P,
            route("settings/journal/foods")),
        SettingsSearchEntry(R.string.journal_insulin_library, JOURNAL, Icons.Default.Vaccines, P,
            route("settings/journal/insulin")),
        SettingsSearchEntry(R.string.journal_show_tab_title, JOURNAL_MAIN_SCREEN, Icons.Default.EditNote, P,
            route("settings/journal"), keywords = listOf(R.string.journal_show_tab_desc),
            anchor = SettingsSearchAnchors.JOURNAL_TAB),
        SettingsSearchEntry(R.string.journal_dashboard_quickadd_title, JOURNAL_MAIN_SCREEN, Icons.Default.Add, P,
            route("settings/journal"), keywords = listOf(R.string.journal_dashboard_quickadd_desc),
            anchor = SettingsSearchAnchors.JOURNAL_QUICK_ADD),
        SettingsSearchEntry(R.string.journal_entry_types_title, JOURNAL_MAIN_SCREEN, Icons.Default.Visibility, P,
            route("settings/journal/entry-types"), keywords = listOf(R.string.journal_dashboard_types_title)),
        SettingsSearchEntry(R.string.journal_insulin_chart_title, JOURNAL_MAIN_SCREEN, Icons.Default.AreaChart, P,
            route("settings/journal"), terms = listOf("IOB"), anchor = SettingsSearchAnchors.JOURNAL_INSULIN_CHART),
        SettingsSearchEntry(R.string.journal_dose_math_title, JOURNAL_DOSING, Icons.Default.Calculate, P,
            route("settings/journal"), keywords = listOf(R.string.journal_dose_calculator_desc),
            terms = listOf("bolus"), anchor = SettingsSearchAnchors.JOURNAL_DOSE_CALCULATOR),
        SettingsSearchEntry(R.string.journal_calculations_title, JOURNAL_DOSING, Icons.Default.Schedule, P,
            route("settings/journal/calculations"), keywords = listOf(R.string.journal_calculations_desc)),
        SettingsSearchEntry(R.string.journal_import_health_activity, JOURNAL_IMPORT, Icons.AutoMirrored.Filled.DirectionsRun, P,
            route("settings/journal"), keywords = listOf(R.string.journal_import_health_activity_desc),
            terms = listOf("Health Connect"), anchor = SettingsSearchAnchors.JOURNAL_HEALTH_ACTIVITY),
        SettingsSearchEntry(R.string.journal_aaps_import_title, JOURNAL_IMPORT, Icons.Default.Restore, P,
            route("settings/journal"), terms = listOf("AAPS", "AndroidAPS"),
            anchor = SettingsSearchAnchors.JOURNAL_AAPS_IMPORT),

        // Display
        SettingsSearchEntry(R.string.settings_dashboard_charts_title, DISPLAY, Icons.Default.Palette, S, route("settings/display-colors"),
            keywords = listOf(R.string.settings_dashboard_charts_desc)),
        SettingsSearchEntry(R.string.target_range_title,
            DISPLAY + R.string.settings_dashboard_charts_title + R.string.glucose_range_title,
            Icons.Default.TrackChanges, S, route("settings/display-colors"),
            keywords = listOf(R.string.chart_limits_title), anchor = SettingsSearchAnchors.TARGET_RANGE),
        SettingsSearchEntry(R.string.glucose_range_colors_title, DISPLAY + R.string.settings_dashboard_charts_title,
            Icons.Default.Palette, S, route("settings/display-colors"),
            keywords = listOf(R.string.glucose_palette_edit_title, R.string.glucose_arrow_forecast_title,
                R.string.glucose_chart_range_colors_title, R.string.glucose_app_chart_range_colors_title),
            anchor = SettingsSearchAnchors.RANGE_COLORS),
        SettingsSearchEntry(R.string.dashboard_show_delta_title, DISPLAY + R.string.settings_dashboard_charts_title,
            Icons.Default.Palette, S, route("settings/display-colors"),
            keywords = listOf(R.string.dashboard_rows_show_delta_title), anchor = SettingsSearchAnchors.DASHBOARD_DELTA),
        SettingsSearchEntry(R.string.dashboard_show_reading_age_title, DISPLAY + R.string.settings_dashboard_charts_title,
            Icons.Default.Palette, S, route("settings/display-colors"), anchor = SettingsSearchAnchors.READING_AGE),
        SettingsSearchEntry(R.string.preview_window_title, DISPLAY + R.string.settings_dashboard_charts_title,
            Icons.Default.CropSquare, S, route("settings/display-colors"), anchor = SettingsSearchAnchors.PREVIEW_WINDOW),
        SettingsSearchEntry(R.string.graph_smoothing_title, DISPLAY, Icons.AutoMirrored.Filled.TrendingUp, S, route("settings/data-smoothing"),
            keywords = listOf(R.string.data_smoothing_window_title, R.string.data_smoothing_collapse_title,
                R.string.data_smoothing_scope_title, R.string.data_smoothing_target_sent_title)),
        SettingsSearchEntry(R.string.data_smoothing_causal_title, DISPLAY + R.string.graph_smoothing_title,
            Icons.Default.Functions, S, route("settings/data-smoothing"), terms = listOf("Kalman"),
            anchor = SettingsSearchAnchors.SMOOTHING_CAUSAL),
        SettingsSearchEntry(R.string.predictive_simulation_title, DISPLAY, Icons.AutoMirrored.Filled.ShowChart, S,
            route("settings/predictive-simulation"),
            keywords = listOf(R.string.settings_prediction_desc), terms = listOf("forecast")),
        SettingsSearchEntry(R.string.predictive_trend_momentum, PREDICTION, Icons.AutoMirrored.Filled.ShowChart, S,
            route("settings/predictive-simulation"), anchor = SettingsSearchAnchors.PREDICTION_MOMENTUM),
        SettingsSearchEntry(R.string.predictive_notification_chart, PREDICTION, Icons.AutoMirrored.Filled.ShowChart, S,
            route("settings/predictive-simulation"), anchor = SettingsSearchAnchors.PREDICTION_NOTIFICATION_CHART),
        SettingsSearchEntry(R.string.predictive_forecast_horizon, PREDICTION, Icons.AutoMirrored.Filled.ShowChart, S,
            route("settings/predictive-simulation"), anchor = SettingsSearchAnchors.PREDICTION_HORIZON),
        SettingsSearchEntry(R.string.predictive_model_tuning, PREDICTION, Icons.Default.Schedule, S,
            route("settings/predictive-simulation/model-profile")),
        SettingsSearchEntry(R.string.prediction_analysis_record, PREDICTION + R.string.prediction_analysis_section,
            Icons.Default.History, S, route("settings/predictive-simulation"),
            anchor = SettingsSearchAnchors.PREDICTION_RECORD),
        SettingsSearchEntry(R.string.prediction_analysis_accuracy, PREDICTION + R.string.prediction_analysis_section,
            Icons.Default.Insights, S, route("settings/predictive-simulation/analysis"),
            keywords = listOf(R.string.prediction_analysis_past_title)),
        SettingsSearchEntry(R.string.prediction_adaptive_title, PREDICTION + R.string.prediction_analysis_section,
            Icons.Default.AutoAwesome, S, route("settings/predictive-simulation"),
            anchor = SettingsSearchAnchors.PREDICTION_ADAPTIVE),
        SettingsSearchEntry(R.string.notification_settings_title, DISPLAY, Icons.Default.ClearAll, S, route("settings/notification-display"),
            keywords = listOf(R.string.notification_settings_subtitle, R.string.typography)),
        SettingsSearchEntry(R.string.show_trend_arrow, DISPLAY + R.string.notification_settings_title,
            Icons.Default.ClearAll, S, route("settings/notification-display"),
            keywords = listOf(R.string.notification_large_arrow_title), anchor = SettingsSearchAnchors.NOTIFICATION_ARROW),
        SettingsSearchEntry(R.string.show_chart_expanded, DISPLAY + R.string.notification_settings_title,
            Icons.Default.ClearAll, S, route("settings/notification-display"),
            keywords = listOf(R.string.show_chart_collapsed, R.string.show_target_range,
                R.string.notification_chart_pause_screen_off),
            anchor = SettingsSearchAnchors.NOTIFICATION_CHART),
        SettingsSearchEntry(R.string.notification_show_delta_title, DISPLAY + R.string.notification_settings_title,
            Icons.Default.ClearAll, S, route("settings/notification-display"),
            anchor = SettingsSearchAnchors.NOTIFICATION_DELTA),
        SettingsSearchEntry(R.string.notification_show_iob_title, DISPLAY + R.string.notification_settings_title,
            Icons.Default.ClearAll, S, route("settings/notification-display"),
            keywords = listOf(R.string.notification_show_cob_title), terms = listOf("IOB", "COB"),
            anchor = SettingsSearchAnchors.NOTIFICATION_IOB),
        SettingsSearchEntry(R.string.floatglucose, DISPLAY, Icons.Default.PictureInPicture, S, route("settings/floating-display"),
            keywords = listOf(R.string.settings_floating_desc), terms = listOf("overlay")),
        SettingsSearchEntry(R.string.dynamic_island_mode, DISPLAY + R.string.floatglucose, Icons.Default.PictureInPicture, S,
            route("settings/floating-display"), keywords = listOf(R.string.dynamic_island_desc),
            anchor = SettingsSearchAnchors.FLOATING_ISLAND),
        SettingsSearchEntry(R.string.display_secondary_values, DISPLAY + R.string.floatglucose, Icons.Default.PictureInPicture, S,
            route("settings/floating-display"), anchor = SettingsSearchAnchors.FLOATING_SECONDARY),
        SettingsSearchEntry(R.string.settings_lock_screen_title, DISPLAY, Icons.Default.ScreenLockPortrait, S, route("settings/aod-display"),
            keywords = listOf(R.string.settings_lock_screen_desc), terms = listOf("AOD")),
        SettingsSearchEntry(R.string.aod_show_on_lockscreen, DISPLAY + R.string.settings_lock_screen_title, Icons.Default.Lock, S,
            route("settings/aod-display"), terms = listOf("AOD"), anchor = SettingsSearchAnchors.AOD_LOCK_SCREEN),

        // Sharing
        SettingsSearchEntry(R.string.settings_phone_apps_title, SHARING, Icons.Default.PhoneAndroid, T, route("settings/phone-apps"),
            terms = listOf("xDrip", "AAPS", "AndroidAPS", "GlucoDataHandler", "GDH", "Glucodata", "LibreLink", "broadcast")),
        SettingsSearchEntry(R.string.settings_patched_libre_title, PHONE_APPS, Icons.Default.Share, T,
            route("settings/phone-apps"), keywords = listOf(R.string.settings_patched_libre_desc),
            terms = listOf("xDrip", "patched Libre", "LibreLink", "broadcast"),
            anchor = SettingsSearchAnchors.PATCHED_LIBRE),
        SettingsSearchEntry(R.string.settings_xdrip_broadcast_title, PHONE_APPS, Icons.Default.Radio, T,
            route("settings/phone-apps"), terms = listOf("AAPS", "AndroidAPS"), anchor = SettingsSearchAnchors.XDRIP),
        SettingsSearchEntry(R.string.xdrip_follower_title, PHONE_APPS, Icons.Default.Sync, T,
            route("settings/phone-apps"), terms = listOf("xDrip", "Follower", "NSClient"),
            anchor = SettingsSearchAnchors.XDRIP_FOLLOWER),
        SettingsSearchEntry(R.string.settings_glucodata_title, PHONE_APPS, Icons.AutoMirrored.Filled.Send, T,
            route("settings/phone-apps"), terms = listOf("GlucoDataHandler", "GDH"), anchor = SettingsSearchAnchors.GLUCODATA),
        SettingsSearchEntry(R.string.health_connect_title, PHONE_APPS, Icons.Default.HealthAndSafety, T,
            route("settings/phone-apps"), condition = SettingsSearchCondition.HEALTH_CONNECT,
            anchor = SettingsSearchAnchors.HEALTH_CONNECT),
        SettingsSearchEntry(R.string.xdrip_report_as_libre2_title, PHONE_APPS, Icons.Default.Badge, T,
            route("settings/phone-apps"), terms = listOf("SMB", "AAPS"),
            condition = SettingsSearchCondition.LIBRE2_IDENTITY, anchor = SettingsSearchAnchors.LIBRE2_IDENTITY),
        SettingsSearchEntry(R.string.settings_app_trend_title, PHONE_APPS, Icons.AutoMirrored.Filled.TrendingUp, T,
            route("settings/phone-apps"), keywords = listOf(R.string.settings_app_trend_desc),
            condition = SettingsSearchCondition.APP_TREND, anchor = SettingsSearchAnchors.APP_TREND),
        SettingsSearchEntry(R.string.nightscout_settings_title, SHARING, Icons.Default.CloudUpload, T, route("settings/nightscout"),
            keywords = listOf(R.string.nightscout_desc)),
        SettingsSearchEntry(R.string.nightscout_send_treatments, NIGHTSCOUT, Icons.Default.Medication, T,
            route("settings/nightscout"), terms = listOf("treatments"), anchor = SettingsSearchAnchors.NIGHTSCOUT_SEND_TREATMENTS),
        SettingsSearchEntry(R.string.nightscout_receive_amounts, NIGHTSCOUT, Icons.Default.Link, T,
            route("settings/nightscout"), terms = listOf("treatments"), condition = SettingsSearchCondition.NIGHTSCOUT_UPLOAD,
            anchor = SettingsSearchAnchors.NIGHTSCOUT_RECEIVE_TREATMENTS),
        SettingsSearchEntry(R.string.nightscout_upload_iob, NIGHTSCOUT, Icons.Default.CloudUpload, T,
            route("settings/nightscout"), terms = listOf("IOB"), condition = SettingsSearchCondition.NIGHTSCOUT_UPLOAD,
            anchor = SettingsSearchAnchors.NIGHTSCOUT_UPLOAD_IOB),
        SettingsSearchEntry(R.string.nightscout_use_v3_api, NIGHTSCOUT, Icons.Default.Api, T,
            route("settings/nightscout"), terms = listOf("API", "JWT"), condition = SettingsSearchCondition.NIGHTSCOUT_UPLOAD,
            anchor = SettingsSearchAnchors.NIGHTSCOUT_V3),
        SettingsSearchEntry(R.string.libreview_config, SHARING, Icons.Default.Cloud, T, route("settings/libreview"),
            keywords = listOf(R.string.libreview_desc), terms = listOf("Abbott"),
            condition = SettingsSearchCondition.LIBREVIEW),
        SettingsSearchEntry(R.string.libreview_official_end, SHARING + R.string.libreview_config, Icons.Default.EventBusy, T,
            route("settings/libreview"), condition = SettingsSearchCondition.LIBREVIEW,
            anchor = SettingsSearchAnchors.LIBREVIEW_OFFICIAL_END),
        SettingsSearchEntry(R.string.dexcom_share_title, SHARING, Icons.Default.CloudUpload, T, route("settings/dexcom-share"),
            keywords = listOf(R.string.dexcom_share_desc), condition = SettingsSearchCondition.DEXCOM_SHARE),
        SettingsSearchEntry(R.string.mq_account_title, SHARING, Icons.Default.AccountCircle, T, route("settings/mq-account"),
            keywords = listOf(R.string.settings_mq_account_desc), terms = listOf("Glutec"),
            condition = SettingsSearchCondition.MQ_ACCOUNT),
        SettingsSearchEntry(R.string.ottai_setup_title, SHARING, Icons.Default.Sensors, T, route("settings/ottai"),
            keywords = listOf(R.string.settings_ottai_desc), condition = SettingsSearchCondition.OTTAI),
        SettingsSearchEntry(R.string.mirror, SHARING, Icons.Default.Devices, T, route("settings/mirror"),
            keywords = listOf(R.string.settings_mirror_desc, R.string.mirror_send_data, R.string.mirror_receive_data),
            terms = listOf("clone", "follower", "QR")),
        SettingsSearchEntry(R.string.webserver, SHARING, Icons.Default.Dns, T, route("settings/webserver"),
            keywords = listOf(R.string.settings_webserver_desc), terms = listOf("HTTP", "HTTPS", "17580")),
        SettingsSearchEntry(R.string.outbound_api_title, SHARING, Icons.Default.Webhook, T, route("settings/outbound-api"),
            keywords = listOf(R.string.outbound_api_desc), terms = listOf("Telegram", "Slack", "webhook", "SMS")),

        // Devices
        SettingsSearchEntry(R.string.watches, DEVICES, Icons.Default.Watch, P, route("settings/watch"),
            terms = listOf("Wear OS", "WearOS", "Garmin", "Kerfstok", "Watchdrip", "Gadgetbridge")),
        SettingsSearchEntry(R.string.wearos_config_title, DEVICES + R.string.watches, Icons.Default.Watch, P,
            route("settings/watch/wearos-config"), terms = listOf("Wear OS")),
        SettingsSearchEntry(R.string.garmin_status_title, DEVICES + R.string.watches, Icons.Default.Watch, P,
            route("settings/watch/garmin-status"), terms = listOf("Kerfstok", "Connect IQ")),
        SettingsSearchEntry(R.string.insulin_pens_title, DEVICES, Icons.Default.Vaccines, P, route("settings/insulin-pens"),
            keywords = listOf(R.string.insulin_pens_desc), terms = listOf("NovoPen", "NFC")),
        SettingsSearchEntry(R.string.meterlist, DEVICES, Icons.Default.Bloodtype, P, route("settings/glucose-meters"),
            keywords = listOf(R.string.glucose_meters_desc)),

        // Backup and restore
        SettingsSearchEntry(R.string.scheduled_backup_title, BACKUP, Icons.Default.Backup, S, action(SettingsSearchAction.BACKUPS)),
        SettingsSearchEntry(R.string.settings_export_title, BACKUP, Icons.Default.Upload, S, action(SettingsSearchAction.EXPORT),
            keywords = listOf(R.string.export_data_settings_desc), terms = listOf("CSV")),
        SettingsSearchEntry(R.string.settings_import_title, BACKUP, Icons.Default.FolderOpen, S, action(SettingsSearchAction.IMPORT),
            keywords = listOf(R.string.import_data_settings_desc), terms = listOf("CSV", "restore")),

        // Advanced
        SettingsSearchEntry(R.string.debug_logs, ADVANCED, Icons.Default.BugReport, N, route("settings/debug"),
            keywords = listOf(R.string.debug_logs_desc), terms = listOf("logcat", "trace")),
        SettingsSearchEntry(R.string.cgm_readiness_title, ADVANCED, Icons.Default.Security, N, route("settings/cgm-readiness"),
            keywords = listOf(R.string.cgm_readiness_settings_desc), terms = listOf("CGM readiness")),
        SettingsSearchEntry(R.string.settings_sensor_connection_title, ADVANCED, Icons.Default.Bluetooth, N,
            route("settings/sensor-connection"), keywords = listOf(R.string.settings_sensor_connection_desc)),
        SettingsSearchEntry(R.string.sensor_handover_title, CONNECTION, Icons.Default.SwapHoriz, N,
            route("settings/sensor-connection"), anchor = SettingsSearchAnchors.HANDOVER),
        SettingsSearchEntry(R.string.settings_turbo_title, CONNECTION, Icons.Default.Speed, N,
            route("settings/sensor-connection"), terms = listOf("Turbo"), anchor = SettingsSearchAnchors.TURBO),
        SettingsSearchEntry(R.string.settings_autoconnect_title, CONNECTION, Icons.Default.Autorenew, N,
            route("settings/sensor-connection"), terms = listOf("AutoConnect"), anchor = SettingsSearchAnchors.AUTOCONNECT),
        SettingsSearchEntry(R.string.freeze_displayed_values, ADVANCED, Icons.Default.Lock, N,
            action(SettingsSearchAction.KEEP_DISPLAYED_VALUES)),
        SettingsSearchEntry(R.string.restricted_mode_title, ADVANCED, Icons.Default.ChildCare, N,
            route("settings/restricted-mode"), keywords = listOf(R.string.restricted_mode_desc),
            terms = listOf("PIN", "child", "kid", "parental", "elderly", "simple")),

        // Danger zone: restart acts at once, as its row does; the rest ask for confirmation.
        SettingsSearchEntry(R.string.restart_app, DANGER, Icons.Default.Refresh, E, action(SettingsSearchAction.RESTART),
            keywords = listOf(R.string.restart_app_desc)),
        SettingsSearchEntry(R.string.clear_history, DANGER, Icons.Default.History, E, action(SettingsSearchAction.CLEAR_HISTORY)),
        SettingsSearchEntry(R.string.factory_reset, DANGER, Icons.Default.Warning, E, action(SettingsSearchAction.FACTORY_RESET)),
    )
}
