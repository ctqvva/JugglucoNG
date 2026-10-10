@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import tk.glucodata.ui.components.TabScreenDefaults
import tk.glucodata.ui.components.TabScreenHeader
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.util.findActivity
import tk.glucodata.ui.util.fullRestart
import tk.glucodata.ui.util.hardRestart
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.BuildConfig
import tk.glucodata.DataSmoothing
import tk.glucodata.HealthConnection
import tk.glucodata.MainActivity
import tk.glucodata.Natives
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorSourceResolver
import tk.glucodata.alerts.SensorHandoverRuntime
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.data.ScheduledBackupSettings
import tk.glucodata.data.ScheduledBackupWorker
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.theme.labelLargeExpressive
import tk.glucodata.ui.viewmodel.DashboardViewModel
//import tk.glucodata.ui.components.ExportDataDialog
import tk.glucodata.ui.components.*
import kotlin.math.roundToInt
import java.util.Locale

/**
 * M3 Expressive Settings Screen
 *
 * Cards in the same section are connected with:
 * - First card: rounded top corners only
 * - Middle cards: no corners (squared)
 * - Last card: rounded bottom corners only
 * - 2dp gap between cards in a group
 */
@Composable
fun ExpressiveSettingsScreen(
    navController: NavController,
    themeMode: ThemeMode,
    onThemeChanged: (ThemeMode) -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sensorStatusRevision by tk.glucodata.UiRefreshBus.revision.collectAsStateWithLifecycle()
    val showMqAccount = remember(context, sensorStatusRevision) {
        tk.glucodata.drivers.mq.MQRegistry.persistedRecords(context).isNotEmpty()
    }
    // LibreView is relevant whenever the account is already set up *or* a Libre-family
    // sensor is active — the account no longer has to be configured in the setup wizard first.
    val showLibreView = remember {
        runCatching { Natives.getuselibreview() }.getOrDefault(false) ||
            runCatching { Natives.getlibreAccountIDnumber() }.getOrDefault(0L) > 0L ||
            hasActiveLibreSensorForLibreView()
    }
    val showOttaiSettings = remember {
        SensorBluetooth.mygatts().any { callback ->
            (callback as? tk.glucodata.drivers.ottai.OttaiDriver)?.isUiEnabled() == true
        }
    }

    // States
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)
    val patchedLibreEnabled by viewModel.patchedLibreBroadcastEnabled.collectAsStateWithLifecycle()
    val notificationChartEnabled by viewModel.notificationChartEnabled.collectAsStateWithLifecycle()
    val chartSmoothingMinutes by viewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val dataSmoothingGraphOnly by viewModel.dataSmoothingGraphOnly.collectAsStateWithLifecycle()
    val dataSmoothingCollapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsStateWithLifecycle()
    val dataSmoothingExchangeOnly by viewModel.dataSmoothingExchangeOnly.collectAsStateWithLifecycle()
    val journalEnabled by viewModel.journalEnabled.collectAsStateWithLifecycle()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsStateWithLifecycle()
    val alertsMasterEnabled by viewModel.alertsMasterEnabled.collectAsStateWithLifecycle()
    var healthConnectEnabled by rememberSaveable { mutableStateOf(Natives.gethealthConnect()) }
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by viewModel.sensorName.collectAsStateWithLifecycle()
    val isRawCalibrationMode = viewMode == 1 || viewMode == 3
    val calibrationRevision by CalibrationManager.revision.collectAsStateWithLifecycle()
    val calibrationEnabled = remember(isRawCalibrationMode, sensorName, calibrationRevision) {
        CalibrationManager.isEnabledForMode(isRawCalibrationMode, sensorName)
    }
    val calibrationModeLabel = if (isRawCalibrationMode) "Raw" else "Auto"

    // Rows that carry a master switch read the same state their own screen reads. The
    // system-granted ones can change while this screen is in the background, so they are
    // re-read on resume rather than only at first composition.
    val floatingOverlayEnabled by viewModel.floatingRepository.isEnabled.collectAsStateWithLifecycle(initialValue = false)
    var floatingOverlayAllowed by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var aodServiceEnabled by remember { mutableStateOf(isAodAccessibilityEnabled(context)) }
    val insulinPensEnabled by tk.glucodata.InsulinPenManager.enabled.collectAsStateWithLifecycle()
    var webServerActive by remember { mutableStateOf(Natives.getusexdripwebserver()) }
    var nightscoutActive by remember { mutableStateOf(isNightscoutActive(context)) }

    // Auto-refresh data when screen becomes active (e.g. returning from Alerts screen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshData()
                floatingOverlayAllowed = android.provider.Settings.canDrawOverlays(context)
                aodServiceEnabled = isAodAccessibilityEnabled(context)
                webServerActive = Natives.getusexdripwebserver()
                nightscoutActive = isNightscoutActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasLowAlarm by viewModel.hasLowAlarm.collectAsStateWithLifecycle()
    val lowAlarmValue by viewModel.lowAlarmThreshold.collectAsStateWithLifecycle()
    val lowAlarmSoundMode by viewModel.lowAlarmSoundMode.collectAsStateWithLifecycle()
    val hasHighAlarm by viewModel.hasHighAlarm.collectAsStateWithLifecycle()
    val highAlarmValue by viewModel.highAlarmThreshold.collectAsStateWithLifecycle()
    val highAlarmSoundMode by viewModel.highAlarmSoundMode.collectAsStateWithLifecycle()

    // Dialog states
    var showUnitDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showScheduledBackupDialog by remember { mutableStateOf(false) }
    var scheduledBackupConfig by remember {
        mutableStateOf(ScheduledBackupSettings.load(context))
    }
    LaunchedEffect(Unit) {
        ScheduledBackupWorker.initialize(context)
    }
    var pendingSettingsImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportPackageImportUri by remember { mutableStateOf<Uri?>(null) }



    // Advanced settings
    var turbo by remember { mutableStateOf(Natives.getpriority()) }
    var autoConnect by remember { mutableStateOf(Natives.getAndroid13()) }
    val handoverPrefs = remember {
        context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
    }
    var sensorHandoverEnabled by remember {
        mutableStateOf(handoverPrefs.getBoolean(SensorHandoverRuntime.PREF_ENABLED, false))
    }
    var sensorHandoverAction by remember {
        mutableStateOf(
            handoverPrefs.getInt(
                SensorHandoverRuntime.PREF_OLD_ACTION,
                SensorHandoverRuntime.OLD_ACTION_DEACTIVATE
            )
        )
    }
    var showSensorHandoverActionDialog by remember { mutableStateOf(false) }

    val currentLocale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
    val currentLangName = currentLocale.displayLanguage.replaceFirstChar { it.uppercase() }

    val themeLabel = when(themeMode) {
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
    }
    val graphSmoothingLabel = if (chartSmoothingMinutes <= 0) {
        stringResource(R.string.graph_smoothing_none)
    } else {
        val collapseIntervalMinutes = DataSmoothing.collapseIntervalMinutes(chartSmoothingMinutes)
        buildList {
            add(stringResource(R.string.minutes_short_format, chartSmoothingMinutes))
            if (dataSmoothingExchangeOnly) {
                add(stringResource(R.string.data_smoothing_exchange_only_title))
            } else if (dataSmoothingGraphOnly) {
                add(stringResource(
                    if (dataSmoothingCollapseChunks) R.string.data_smoothing_scope_graph_and_sent
                    else R.string.data_smoothing_graph_only_title
                ))
            } else {
                add(stringResource(R.string.data_smoothing_scope_all))
            }
            if (dataSmoothingCollapseChunks) {
                add(stringResource(R.string.data_smoothing_collapse_summary_format, collapseIntervalMinutes))
            }
        }.joinToString(" · ")
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(), // Add status bar padding
        contentPadding = TabScreenDefaults.contentPadding()
    ) {
        item(key = "title") {
            TabScreenHeader(title = stringResource(R.string.settings))
        }

        item(key = "general_group") {
            val generalColor = MaterialTheme.colorScheme.primary
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.theme_title),
                    subtitle = themeLabel,
                    icon = if(themeMode == ThemeMode.LIGHT) Icons.Default.LightMode else Icons.Default.DarkMode,
                    iconTint = generalColor,
                    position = CardPosition.TOP,
                    onClick = { showThemeDialog = true }
                )

                SettingsItem(
                    title = stringResource(R.string.languagename),
                    subtitle = currentLangName,
                    icon = Icons.Default.Language,
                    iconTint = generalColor,
                    position = CardPosition.BOTTOM,
                    onClick = { showLanguageDialog = true }
                )
            }
        }

        item(key = "general_glucose_group") {
            val glucoseColor = MaterialTheme.colorScheme.primary
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                SettingsItem(
                    title = stringResource(R.string.unit),
                    subtitle = unit,
                    icon = Icons.AutoMirrored.Filled.List,
                    iconTint = glucoseColor,
                    position = CardPosition.TOP,
                    onClick = { showUnitDialog = true }
                )

                SettingsItem(
                    title = stringResource(R.string.display_colors_title),
                    subtitle = stringResource(R.string.display_colors_desc),
                    icon = Icons.Default.Palette,
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/display-colors") }
                )

                SettingsNavSwitchItem(
                    title = stringResource(R.string.manual_calibration),
                    subtitle = "$calibrationModeLabel · ${
                        stringResource(
                            if (calibrationEnabled) R.string.enabled_status else R.string.disabled_status
                        )
                    }",
                    checked = calibrationEnabled,
                    onCheckedChange = {
                        CalibrationManager.setEnabledForMode(isRawCalibrationMode, it, sensorName)
                    },
                    onClick = { navController.navigate("settings/calibrations") },
                    icon = Icons.Default.WaterDrop,
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                SettingsNavSwitchItem(
                    title = stringResource(R.string.graph_smoothing_title),
                    subtitle = graphSmoothingLabel,
                    checked = chartSmoothingMinutes > 0,
                    onCheckedChange = { viewModel.setDataSmoothingEnabled(it) },
                    onClick = { navController.navigate("settings/data-smoothing") },
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                SettingsNavSwitchItem(
                    title = stringResource(R.string.journal_title),
                    subtitle = stringResource(
                        if (journalEnabled) R.string.enabled_status else R.string.disabled_status
                    ),
                    checked = journalEnabled,
                    onCheckedChange = { viewModel.setJournalEnabled(it) },
                    onClick = { navController.navigate("settings/journal") },
                    icon = Icons.Default.Vaccines,
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                SettingsNavSwitchItem(
                    title = stringResource(R.string.predictive_simulation_title),
                    subtitle = stringResource(
                        if (predictiveSimulationEnabled) R.string.enabled_status else R.string.disabled_status
                    ),
                    checked = predictiveSimulationEnabled,
                    onCheckedChange = { viewModel.setPredictiveSimulationEnabled(it) },
                    onClick = { navController.navigate("settings/predictive-simulation") },
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    iconTint = glucoseColor,
                    position = CardPosition.BOTTOM
                )
            }
        }

        // === NOTIFICATIONS ===
        item(key = "notif_label") { SectionLabel(stringResource(R.string.notifications)) }

        item(key = "notif_group") {
            // Theme: Secondary (Accent)
            val notifColor = MaterialTheme.colorScheme.secondary
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsNavSwitchItem(
                    title = stringResource(R.string.glucose_alerts_title),
                    subtitle = stringResource(
                        if (alertsMasterEnabled) R.string.global_active else R.string.global_all_alerts_disabled
                    ),
                    checked = alertsMasterEnabled,
                    onCheckedChange = { viewModel.setAlertsMasterEnabled(it) },
                    onClick = { navController.navigate("settings/alerts") },
                    icon = Icons.Default.AddAlert,
                    iconTint = MaterialTheme.colorScheme.error,
                    position = CardPosition.TOP,
                )

                SettingsItem(
                    title = stringResource(R.string.notification_settings_title),
                    subtitle = stringResource(R.string.notification_settings_subtitle),
                    icon = Icons.Default.ClearAll,
                    iconTint = notifColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/notification-display") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.floatglucose),
                    subtitle = when {
                        !floatingOverlayAllowed -> stringResource(R.string.floating_permission_required)
                        floatingOverlayEnabled -> stringResource(R.string.enabled_status)
                        else -> stringResource(R.string.disabled_status)
                    },
                    checked = floatingOverlayEnabled,
                    // The overlay cannot start without the system grant, so the switch
                    // sends you to give it rather than flipping to a state it can't hold.
                    onCheckedChange = { enabled ->
                        if (enabled && !floatingOverlayAllowed) {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } else {
                            viewModel.toggleFloatingGlucose(enabled)
                        }
                    },
                    onClick = { navController.navigate("settings/floating-display") },
                    icon = Icons.Default.PictureInPicture,
                    iconTint = notifColor,
                    position = CardPosition.MIDDLE
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.lock_screen_aod),
                    subtitle = if (aodServiceEnabled) {
                        stringResource(R.string.accessibility_service_enabled)
                    } else {
                        stringResource(R.string.open_accessibility_settings)
                    },
                    checked = aodServiceEnabled,
                    // The lock-screen overlay is an accessibility service: only the system
                    // settings screen can turn it on or off.
                    onCheckedChange = {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onClick = { navController.navigate("settings/aod-display") },
                    icon = Icons.Default.Visibility,
                    iconTint = notifColor,
                    position = CardPosition.BOTTOM
                )
            }
        }
        // === ALERTS ===

        item(key = "alerts_group") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {

            }
        }
        // === EXCHANGES ===
        item(key = "exchange_label") { SectionLabel(stringResource(R.string.exchanges)) }

        item(key = "exchange_group") {
            // Theme: Tertiary (Apps/Services)
            val exchangeColor = MaterialTheme.colorScheme.tertiary
            val xdripEnabled by viewModel.xDripBroadcastEnabled.collectAsStateWithLifecycle()
            val glucodataBroadcastEnabled by viewModel.glucodataBroadcastEnabled.collectAsStateWithLifecycle()
            val broadcastComputedTrend by viewModel.broadcastComputedTrend.collectAsStateWithLifecycle()
            val xdripReportAsLibre2 by viewModel.xdripReportAsLibre2.collectAsStateWithLifecycle()

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.xdripbroadcast),
                    subtitle = stringResource(R.string.patchedlibrebroadcast),
                    checked = patchedLibreEnabled,
                    icon = Icons.Default.Share, 
                    iconTint = exchangeColor,
                    position = CardPosition.TOP,
                    onCheckedChange = { viewModel.togglePatchedLibreBroadcast(it) }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.xdrip_compatible_title),
                    subtitle = stringResource(R.string.xdrip_compatible_desc),
                    checked = xdripEnabled,
                    icon = Icons.Default.Radio, 
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onCheckedChange = { viewModel.toggleXDripBroadcast(it) }
                )
                // Only the xDrip-style broadcast carries the source name. Stay visible while
                // enabled, so turning the broadcast off does not strand the claim out of reach.
                if (xdripEnabled || xdripReportAsLibre2) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.xdrip_report_as_libre2_title),
                        subtitle = stringResource(R.string.xdrip_report_as_libre2_desc),
                        checked = xdripReportAsLibre2,
                        icon = Icons.Default.Badge,
                        iconTint = exchangeColor,
                        position = CardPosition.MIDDLE,
                        onCheckedChange = { viewModel.setXdripReportAsLibre2(it) }
                    )
                }
                SettingsSwitchItem(
                    title = stringResource(R.string.aaps_broadcast),
                    subtitle = stringResource(R.string.glucodata_subtitle),
                    checked = glucodataBroadcastEnabled,
                    icon = Icons.AutoMirrored.Filled.Send,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onCheckedChange = { viewModel.toggleGlucodataBroadcast(it) }
                )
                // The computed trend only ever reaches an ExchangeGlucosePayload consumer, so the
                // row is noise until one of them is on. Stay visible while the setting itself is
                // enabled, otherwise turning the last consumer off would strand it out of reach.
                val anyExchangeConsumer = patchedLibreEnabled || xdripEnabled || glucodataBroadcastEnabled ||
                        OutboundApiSettings.isEnabled() ||
                        Natives.getgadgetbridge() ||
                        Natives.getwatchdrip()
                if (anyExchangeConsumer || broadcastComputedTrend) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.broadcast_computed_trend_title),
                        subtitle = stringResource(R.string.broadcast_computed_trend_desc),
                        checked = broadcastComputedTrend,
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        iconTint = exchangeColor,
                        position = CardPosition.MIDDLE,
                        onCheckedChange = { viewModel.setBroadcastComputedTrend(it) }
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.health_connect_title),
                        subtitle = stringResource(R.string.health_connect_desc),
                        checked = healthConnectEnabled,
                        icon = Icons.Default.HealthAndSafety,
                        iconTint = exchangeColor,
                        position = CardPosition.MIDDLE,
                        onCheckedChange = { enabled ->
                            Natives.sethealthConnect(enabled)
                            healthConnectEnabled = enabled
                            if (enabled) {
                                MainActivity.tryHealth = 5
                                (context.findActivity() as? MainActivity)?.let(HealthConnection::init)
                            } else {
                                MainActivity.tryHealth = 0
                                HealthConnection.stop()
                            }
                        }
                    )
                }
                SettingsItem(
                    title = stringResource(R.string.outbound_api_title),
                    subtitle = stringResource(R.string.outbound_api_desc),
                    icon = Icons.Default.CloudUpload,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/outbound-api") }
                )
                SettingsItem(
                    title = stringResource(R.string.mirror),
                    subtitle = stringResource(R.string.mirror_desc),
                    icon = Icons.Default.Devices,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/mirror") }
                )
                // Edit 67b: Determine if LibreView is visible to adjust card positions
                SettingsNavSwitchItem(
                    title = stringResource(R.string.nightscout_config),
                    subtitle = stringResource(
                        if (nightscoutActive) R.string.enabled_status else R.string.disabled_status
                    ),
                    checked = nightscoutActive,
                    onCheckedChange = {
                        setNightscoutActive(context, it)
                        nightscoutActive = it
                    },
                    onClick = { navController.navigate("settings/nightscout") },
                    icon = Icons.Default.CloudUpload,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE
                )
                if (showLibreView) {
                    SettingsItem(
                        title = stringResource(R.string.libreview_config),
                        subtitle = stringResource(R.string.libreview_desc),
                        icon = Icons.Default.Cloud,
                        iconTint = exchangeColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/libreview") }
                    )
                }
                if (showMqAccount) {
                    SettingsItem(
                        title = stringResource(R.string.mq_account_title),
                        subtitle = stringResource(R.string.mq_account_linked_desc),
                        icon = Icons.Default.Cloud,
                        iconTint = exchangeColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/mq-account") }
                    )
                }
                if (showOttaiSettings) {
                    SettingsItem(
                        title = stringResource(R.string.ottai_setup_title),
                        subtitle = stringResource(R.string.ottai_settings_desc),
                        icon = Icons.Default.Sensors,
                        iconTint = exchangeColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/ottai") }
                    )
                }
                SettingsItem(
                    title = stringResource(R.string.meterlist),
                    subtitle = stringResource(R.string.glucose_meters_desc),
                    icon = Icons.Default.Bloodtype,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/glucose-meters") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.insulin_pens_title),
                    subtitle = stringResource(
                        if (insulinPensEnabled) R.string.enabled_status else R.string.disabled_status
                    ),
                    checked = insulinPensEnabled,
                    onCheckedChange = { tk.glucodata.InsulinPenManager.setEnabled(it) },
                    onClick = { navController.navigate("settings/insulin-pens") },
                    icon = Icons.Default.Vaccines,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE
                )
                SettingsItem(
                    title = stringResource(R.string.watches),
                    subtitle = "WearOS, Watchdrip, GadgetBridge, Kerfstok",
                    icon = Icons.Default.Devices,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/watch") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.webserver),
                    subtitle = stringResource(
                        if (webServerActive) R.string.enabled_status else R.string.disabled_status
                    ),
                    checked = webServerActive,
                    onCheckedChange = {
                        Natives.setusexdripwebserver(it)
                        webServerActive = it
                    },
                    onClick = { navController.navigate("settings/webserver") },
                    icon = Icons.Default.Language,
                    iconTint = exchangeColor,
                    position = CardPosition.BOTTOM
                )
            }
        }
        // === ADVANCED ===
        item(key = "adv_label") { SectionLabel(stringResource(R.string.advanced_title)) }

        item(key = "adv_group") {
            // Theme: OnSurfaceVariant (Technical/Neutral)
            val advColor = MaterialTheme.colorScheme.onSurfaceVariant
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.turbo_title),
                    subtitle = stringResource(R.string.turbo_desc),
                    checked = turbo,
                    icon = Icons.Default.Speed,
                    iconTint = advColor,
                    position = CardPosition.TOP,
                    onCheckedChange = { Natives.setpriority(it); turbo = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.autoconnect_title),
                    subtitle = stringResource(R.string.autoconnect_desc),
                    checked = autoConnect,
                    icon = Icons.Default.Autorenew,
                    iconTint = advColor,
                    position = CardPosition.MIDDLE,
                    onCheckedChange = { SensorBluetooth.setAutoconnect(it); autoConnect = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.sensor_handover_title),
                    subtitle = stringResource(R.string.sensor_handover_desc),
                    checked = sensorHandoverEnabled,
                    icon = Icons.Default.SwapHoriz,
                    iconTint = advColor,
                    position = CardPosition.MIDDLE,
                    onCheckedChange = {
                        handoverPrefs.edit().putBoolean(SensorHandoverRuntime.PREF_ENABLED, it).apply()
                        sensorHandoverEnabled = it
                    }
                )
                if (sensorHandoverEnabled) {
                    SettingsItem(
                        title = stringResource(R.string.sensor_handover_old_action_title),
                        subtitle = if (sensorHandoverAction == SensorHandoverRuntime.OLD_ACTION_REMOVE) {
                            stringResource(R.string.sensor_handover_action_remove)
                        } else {
                            stringResource(R.string.sensor_handover_action_deactivate)
                        },
                        icon = Icons.Default.DeleteSweep,
                        iconTint = advColor,
                        position = CardPosition.MIDDLE,
                        onClick = { showSensorHandoverActionDialog = true }
                    )
                }
                SettingsItem(
                    title = stringResource(R.string.cgm_readiness_title),
                    subtitle = stringResource(R.string.cgm_readiness_settings_desc),
                    icon = Icons.Default.Security,
                    iconTint = advColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/cgm-readiness") }
                )
                SettingsItem(
                    title = stringResource(R.string.debug_logs),
                    subtitle = stringResource(R.string.debug_logs_desc),
                    icon = Icons.Default.BugReport,
                    iconTint = advColor,
                    position = CardPosition.BOTTOM,
                    onClick = { navController.navigate("settings/debug") }
                )
            }
        }

        // === DATA MANAGEMENT ===
        item(key = "data_label") { SectionLabel(stringResource(R.string.data_management)) }

        item(key = "data_group") {
            // Theme: Secondary (Files match notifications/system)
            val dataColor = MaterialTheme.colorScheme.secondary
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = { uri ->
                    if (uri != null) {
                        scope.launch {
                            when (tk.glucodata.data.ExportPackageExporter.detectImportFileType(context, uri)) {
                                tk.glucodata.data.ExportPackageExporter.ImportFileType.SETTINGS -> {
                                    withContext(Dispatchers.Main) { pendingSettingsImportUri = uri }
                                }
                                tk.glucodata.data.ExportPackageExporter.ImportFileType.EXPORT_PACKAGE -> {
                                    withContext(Dispatchers.Main) { pendingExportPackageImportUri = uri }
                                }
                                tk.glucodata.data.ExportPackageExporter.ImportFileType.OTHER -> {
                                    val result = tk.glucodata.data.HistoryExporter.importFromCsv(context, uri)
                                    withContext(Dispatchers.Main) {
                                        val msg = if (result.success)
                                            context.getString(R.string.imported_readings_count, result.successCount)
                                        else
                                            context.getString(
                                                R.string.import_failed_with_error,
                                                result.errorMessage ?: context.getString(R.string.unknown_error)
                                            )
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppUpdatesSettingsItem(
                    iconTint = dataColor,
                    position = CardPosition.TOP,
                    onOpen = { navController.navigate("settings/app-updates") }
                )

                SettingsItem(
                    title = stringResource(R.string.scheduled_backup_title),
                    subtitle = scheduledBackupSummary(scheduledBackupConfig),
                    icon = Icons.Default.Backup,
                    iconTint = dataColor,
                    position = CardPosition.MIDDLE,
                    onClick = { showScheduledBackupDialog = true }
                )

                SettingsItem(
                    title = stringResource(R.string.export_data_settings),
                    subtitle = stringResource(R.string.export_data_settings_desc),
                    icon = androidx.compose.material.icons.Icons.Default.CloudUpload,
                    iconTint = dataColor,
                    position = CardPosition.MIDDLE,
                    onClick = { showExportDialog = true }
                )

                SettingsItem(
                    title = stringResource(R.string.import_data_settings),
                    subtitle = stringResource(R.string.import_data_settings_desc),
                    icon = Icons.Default.FolderOpen,
                    iconTint = dataColor,
                    position = CardPosition.MIDDLE,
                    onClick = { 
                        importLauncher.launch(
                            arrayOf(
                                "application/json",
                                "application/gzip",
                                "application/zstd",
                                "text/*",
                                "text/csv",
                                "text/tab-separated-values",
                                "*/*"
                            )
                        )
                    }
                )

                // A reading's displayed value is a record, not a derivation —
                // see tk.glucodata.data.ReadingDisplay. Its subtitle carries the
                // current behaviour so the common question is answered in place.
                val freezeDisplayedValues by tk.glucodata.data.calibration.CalibrationManager
                    .freezeDisplayedValues.collectAsStateWithLifecycle()
                SettingsSwitchItem(
                    title = stringResource(R.string.freeze_displayed_values),
                    subtitle = stringResource(
                        if (freezeDisplayedValues) {
                            R.string.freeze_displayed_values_on
                        } else {
                            R.string.freeze_displayed_values_off
                        }
                    ),
                    checked = freezeDisplayedValues,
                    onCheckedChange = {
                        tk.glucodata.data.calibration.CalibrationManager
                            .setFreezeDisplayedValues(it)
                    },
                    icon = Icons.Default.Lock,
                    iconTint = dataColor,
                    position = CardPosition.BOTTOM
                )
            }
        }

        // === DANGER ===
        item(key = "danger_label") { SectionLabel(stringResource(R.string.danger_zone), isError = true) }

        item(key = "danger_group") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Restart App (Low Severity)
                DangerItem(
                    title = stringResource(R.string.restart_app),
                    subtitle = stringResource(R.string.restart_app_desc),
                    icon = Icons.Filled.Refresh,
                    position = CardPosition.TOP,
                    severity = DangerSeverity.LOW,
                    onClick = { context.findActivity()?.fullRestart() }
                )
                DangerItem(
                    title = stringResource(R.string.clear_history),
                    subtitle = stringResource(R.string.clear_history_desc_short),
                    icon = Icons.Filled.History,
                    position = CardPosition.MIDDLE,
                    severity = DangerSeverity.LOW,
                    onClick = { showClearHistoryDialog = true }
                )
                DangerItem(
                    title = stringResource(R.string.clear_app_data),
                    subtitle = stringResource(R.string.clear_app_data_desc),
                    icon = Icons.Filled.Delete,
                    position = CardPosition.MIDDLE,
                    severity = DangerSeverity.MEDIUM,
                    onClick = { showClearDataDialog = true }
                )
                DangerItem(
                    title = stringResource(R.string.factory_reset),
                    subtitle = stringResource(R.string.factory_reset_desc),
                    icon = Icons.Filled.Warning,
                    position = CardPosition.BOTTOM,
                    severity = DangerSeverity.HIGH,
                    onClick = { showFactoryResetDialog = true }
                )
            }
        }

        // === ABOUT ===
        item(key = "about") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                // ui-guardrails: allow text_literal - a version number, the same in every language
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Dialogs
    if (showUnitDialog) UnitPickerDialog(isMmol, { 
        viewModel.setUnit(it)
        showUnitDialog = false
        context.findActivity()?.hardRestart() 
    }, { showUnitDialog = false })
    if (showThemeDialog) ThemePickerDialog(themeMode, { onThemeChanged(it); showThemeDialog = false }, { showThemeDialog = false })
    if (showSensorHandoverActionDialog) SensorHandoverActionPickerDialog(
        currentAction = sensorHandoverAction,
        onSelect = {
            handoverPrefs.edit().putInt(SensorHandoverRuntime.PREF_OLD_ACTION, it).apply()
            sensorHandoverAction = it
            showSensorHandoverActionDialog = false
        },
        onDismiss = { showSensorHandoverActionDialog = false }
    )
    if (showLanguageDialog) LanguagePickerDialog { showLanguageDialog = false }
    if (showClearHistoryDialog) ConfirmActionDialog(stringResource(R.string.clean_history_confirm), stringResource(R.string.clear_history_desc_long), Icons.Filled.History, { scope.launch { tk.glucodata.data.DataManagement.clearHistory() }; showClearHistoryDialog = false }, { showClearHistoryDialog = false })
    if (showClearDataDialog) ConfirmActionDialog(
        stringResource(R.string.clean_data_confirm), 
        stringResource(R.string.clear_app_data_desc), 
        Icons.Filled.Delete, 
        { 
            scope.launch { 
                tk.glucodata.data.DataManagement.clearAppData()
                // Full restart to kill process and clear native memory
                context.findActivity()?.fullRestart()
            }
            showClearDataDialog = false 
        }, 
        { showClearDataDialog = false }, 
        isDestructive = true
    )
    if (showFactoryResetDialog) ConfirmActionDialog(
        stringResource(R.string.factory_reset_confirm), 
        stringResource(R.string.factory_reset_alert), 
        Icons.Filled.Warning, 
        { 
            scope.launch { 
                tk.glucodata.data.DataManagement.factoryReset()
                // Full restart to kill process and clear native memory
                context.findActivity()?.fullRestart()
            }
        }, 
        { showFactoryResetDialog = false }, 
        isDestructive = true
    )
    
    if (showExportDialog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ExportDataSettingsSheet(
            onDismiss = { showExportDialog = false },
            sheetState = sheetState
        )
    }
    if (showScheduledBackupDialog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ScheduledBackupSettingsSheet(
            onDismiss = { showScheduledBackupDialog = false },
            sheetState = sheetState,
            onConfigurationChanged = { scheduledBackupConfig = it }
        )
    }
    pendingSettingsImportUri?.let { uri ->
        ConfirmActionDialog(
            title = stringResource(R.string.settings_import_confirm_title),
            message = stringResource(R.string.settings_import_confirm_message),
            icon = Icons.Default.Settings,
            onConfirm = {
                pendingSettingsImportUri = null
                scope.launch {
                    val result = tk.glucodata.data.SettingsExporter.importFromJson(context, uri)
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_import_successful),
                                Toast.LENGTH_LONG
                            ).show()
                            context.findActivity()?.fullRestart()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.import_failed_with_error,
                                    result.exceptionOrNull()?.localizedMessage
                                        ?: context.getString(R.string.unknown_error)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            onDismiss = { pendingSettingsImportUri = null }
        )
    }
    pendingExportPackageImportUri?.let { uri ->
        ConfirmActionDialog(
            title = stringResource(R.string.import_data_settings),
            message = stringResource(R.string.settings_import_confirm_message),
            icon = Icons.Default.FolderOpen,
            onConfirm = {
                pendingExportPackageImportUri = null
                scope.launch {
                    val result = tk.glucodata.data.ExportPackageExporter.importFromJson(context, uri)
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            val summary = result.getOrThrow()
                            Toast.makeText(
                                context,
                                if (summary.restartRequired) {
                                    context.getString(R.string.settings_import_successful)
                                } else {
                                    context.getString(
                                        R.string.imported_readings_count,
                                        summary.historyReadings
                                    )
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            if (summary.historyReadings > 0) {
                                // Pin the imported serial for display (when idle) so the
                                // dashboard chart shows the imported glucose. Persisted, so
                                // it survives the restart below.
                                viewModel.onHistoryImported(summary.historyDisplaySerial)
                            } else {
                                viewModel.refreshData()
                            }
                            if (summary.restartRequired) {
                                context.findActivity()?.fullRestart()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.import_failed_with_error,
                                    result.exceptionOrNull()?.localizedMessage
                                        ?: context.getString(R.string.unknown_error)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            onDismiss = { pendingExportPackageImportUri = null }
        )
    }

    // ... Bottom of function ...

}


@Composable
fun PredictiveSimulationSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val journalEnabled by viewModel.journalEnabled.collectAsStateWithLifecycle()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsStateWithLifecycle()
    val notificationChartPredictionEnabled by viewModel.predictiveSimulationNotificationChartEnabled.collectAsStateWithLifecycle()
    val trendMomentumEnabled by viewModel.predictionTrendMomentumEnabled.collectAsStateWithLifecycle()
    val modelProfile by viewModel.predictionModelProfile.collectAsStateWithLifecycle()
    val horizonMinutes by viewModel.predictionHorizonMinutes.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.predictive_simulation_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "master") {
                MasterSwitchCard(
                    title = stringResource(R.string.predictive_simulation_title),
                    subtitle = stringResource(R.string.predictive_simulation_summary),
                    checked = predictiveSimulationEnabled,
                    onCheckedChange = { viewModel.setPredictiveSimulationEnabled(it) },
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }

            item(key = "behavior") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.predictive_trend_momentum),
                        checked = trendMomentumEnabled,
                        onCheckedChange = { viewModel.setPredictionTrendMomentumEnabled(it) },
                        position = CardPosition.TOP,
                        enabled = predictiveSimulationEnabled
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.predictive_notification_chart),
                        subtitle = stringResource(R.string.predictive_notification_chart_summary),
                        checked = notificationChartPredictionEnabled,
                        onCheckedChange = { viewModel.setPredictiveSimulationNotificationChartEnabled(it) },
                        position = CardPosition.BOTTOM,
                        enabled = predictiveSimulationEnabled
                    )
                }
            }

            item(key = "horizon") {
                PredictiveSimulationSettingsCard(enabled = predictiveSimulationEnabled) {
                    PredictiveSimulationParameterRow(
                        title = stringResource(R.string.predictive_forecast_horizon),
                        valueLabel = stringResource(R.string.predictive_horizon_value, horizonMinutes),
                        value = horizonMinutes.toFloat(),
                        valueRange = 30f..360f,
                        enabled = predictiveSimulationEnabled,
                        onValueChange = { viewModel.setPredictionHorizonMinutes(it.roundToInt()) }
                    )
                }
            }

            if (journalEnabled) {
                item(key = "model") {
                    SettingsItem(
                        title = stringResource(R.string.predictive_model_tuning),
                        subtitle = if (modelProfile.blocks.size == 1) {
                            stringResource(R.string.predictive_model_profile_summary_single)
                        } else {
                            stringResource(
                                R.string.predictive_model_profile_summary_count,
                                modelProfile.blocks.size
                            )
                        },
                        onClick = {
                            navController.navigate("settings/predictive-simulation/model-profile")
                        },
                        icon = Icons.Default.Schedule,
                        iconTint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PredictiveSimulationSettingsCard(
    enabled: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.66f),
        shape = cardShape(CardPosition.SINGLE),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            content = content
        )
    }
}

@Composable
internal fun PredictiveSimulationParameterRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled
        )
    }
}

// Components moved to tk.glucodata.ui.components.SettingsComponents.kt

// ============================================================================
// DIALOGS - M3 Expressive Style with proper layout
// ============================================================================

@Composable
private fun UnitPickerDialog(isMmol: Boolean, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.select_unit),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                listOf(stringResource(R.string.unit_mg) to 2, stringResource(R.string.unit_mmol) to 1).forEach { (label, value) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (if (isMmol) 1 else 2) == value, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun ThemePickerDialog(current: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.choose_theme),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                ThemeMode.values().forEach { mode ->
                    val label = when(mode) { ThemeMode.SYSTEM -> stringResource(R.string.theme_system); ThemeMode.LIGHT -> stringResource(R.string.theme_light); ThemeMode.DARK -> stringResource(R.string.theme_dark) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onSelect(mode) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == mode, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun SensorHandoverActionPickerDialog(
    currentAction: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        stringResource(R.string.sensor_handover_action_deactivate) to SensorHandoverRuntime.OLD_ACTION_DEACTIVATE,
        stringResource(R.string.sensor_handover_action_remove) to SensorHandoverRuntime.OLD_ACTION_REMOVE
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.sensor_handover_old_action_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Text(
                    text = stringResource(R.string.sensor_handover_old_action_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                options.forEach { (label, value) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentAction == value, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
internal fun PreviewWindowPickerDialog(
    currentMode: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        stringResource(R.string.preview_window_expanded_only) to 0,
        stringResource(R.string.preview_window_always) to 1,
        stringResource(R.string.preview_window_never) to 2
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.preview_window_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Text(
                    text = stringResource(R.string.preview_window_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                options.forEach { (label, value) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentMode == value, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val languages = listOf(
        "System" to null,
        "English" to "en",
        "Belarusian" to "be",
        "Chinese" to "zh",
        "German" to "de",
        "French" to "fr",
        "Hungarian" to "hu",
        "Italian" to "it",
        "Dutch" to "nl",
        "Polish" to "pl",
        "Portuguese" to "pt",
        "Russian" to "ru",
        "Swedish" to "sv",
        "Somali" to "so",
        "Turkish" to "tr",
        "Ukrainian" to "uk",
        "Mongolian" to "mn",
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.select_language),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    languages.forEach { (name, tag) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable {
                                    val locale = if (tag != null) LocaleListCompat.forLanguageTags(tag) else LocaleListCompat.getEmptyLocaleList()
                                    AppCompatDelegate.setApplicationLocales(locale)
                                    onDismiss()
                                }
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if ((tag == null && AppCompatDelegate.getApplicationLocales().isEmpty) ||
                                (tag != null && AppCompatDelegate.getApplicationLocales().toLanguageTags().contains(tag))) {
                                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    icon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, null, tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (isDestructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()
            ) { Text(if (isDestructive) stringResource(R.string.delete) else stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/**
 * True when a currently active sensor is genuinely a Libre 2 / Libre 3.
 *
 * Deliberately does NOT reuse the native Libre check used for NFC readiness:
 * native `isLibre2()`/`isLibre3()` are defined by *exclusion* (anything that is
 * not AccuChek/Sibionics/Dexcom is reported as Libre), so Kotlin-driver sensors
 * such as Ottai — which have no native family flag but are mirrored into the
 * native glucose stream — come back as "Libre". Here we require a positive
 * LIBRE2/LIBRE3 kind and additionally drop any sensor claimed by a managed
 * Kotlin BLE driver.
 */
private fun hasActiveLibreSensorForLibreView(): Boolean = runCatching {
    Natives.activeSensors()?.filterNotNull().orEmpty().any { sensorId ->
        // Owned by a Kotlin BLE driver (Ottai, MQ, iCan, AiDex, Anytime, Sibionics) -> not Libre.
        val managed = runCatching { ManagedSensorRuntime.resolveDriver(sensorId) }.getOrNull()
        if (managed != null) return@any false
        val kind = runCatching {
            SensorSourceResolver.resolveSensorKind(sensorId, SensorSourceResolver.SENSOR_KIND_UNKNOWN)
        }.getOrDefault(SensorSourceResolver.SENSOR_KIND_UNKNOWN)
        kind == SensorSourceResolver.SENSOR_KIND_LIBRE2 || kind == SensorSourceResolver.SENSOR_KIND_LIBRE3
    }
}.getOrDefault(false)
