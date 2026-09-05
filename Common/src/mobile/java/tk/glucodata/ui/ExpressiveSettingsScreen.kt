@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import tk.glucodata.Natives
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorSourceResolver
import tk.glucodata.alerts.SensorHandoverRuntime
import tk.glucodata.data.calibration.CalibrationManager
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
    val mqAccountState = tk.glucodata.drivers.mq.MQRegistry.loadAccountState(context)
    val showMqAccount =
        tk.glucodata.drivers.mq.MQRegistry.persistedRecords(context).isNotEmpty() ||
            mqAccountState.hasCredentials ||
            mqAccountState.hasToken ||
            tk.glucodata.drivers.mq.MQRegistry.isCloudSyncEnabled(context)
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
    val unit by viewModel.unit.collectAsState()
    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)
    val patchedLibreEnabled by viewModel.patchedLibreBroadcastEnabled.collectAsState()
    val notificationChartEnabled by viewModel.notificationChartEnabled.collectAsState()
    val chartSmoothingMinutes by viewModel.chartSmoothingMinutes.collectAsState()
    val dataSmoothingGraphOnly by viewModel.dataSmoothingGraphOnly.collectAsState()
    val dataSmoothingCollapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsState()
    val dataSmoothingExchangeOnly by viewModel.dataSmoothingExchangeOnly.collectAsState()
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsState()
    val alertsMasterEnabled by viewModel.alertsMasterEnabled.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val sensorName by viewModel.sensorName.collectAsState()
    val isRawCalibrationMode = viewMode == 1 || viewMode == 3
    val calibrationRevision by CalibrationManager.revision.collectAsState()
    val calibrationEnabled = remember(isRawCalibrationMode, sensorName, calibrationRevision) {
        CalibrationManager.isEnabledForMode(isRawCalibrationMode, sensorName)
    }
    val calibrationModeLabel = if (isRawCalibrationMode) "Raw" else "Auto"
    
    // Auto-refresh data when screen becomes active (e.g. returning from Alerts screen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasLowAlarm by viewModel.hasLowAlarm.collectAsState()
    val lowAlarmValue by viewModel.lowAlarmThreshold.collectAsState()
    val lowAlarmSoundMode by viewModel.lowAlarmSoundMode.collectAsState()
    val hasHighAlarm by viewModel.hasHighAlarm.collectAsState()
    val highAlarmValue by viewModel.highAlarmThreshold.collectAsState()
    val highAlarmSoundMode by viewModel.highAlarmSoundMode.collectAsState()

    // Dialog states
    var showUnitDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
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
                add(stringResource(R.string.data_smoothing_graph_only_title))
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
    ) {
        // Title
        item(key = "title") {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 24.dp)
            )
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
                modifier = Modifier.padding(top = 10.dp)
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

                ManualCalibrationSettingsItem(
                    calibrationEnabled = calibrationEnabled,
                    modeLabel = calibrationModeLabel,
                    onToggleEnabled = {
                        CalibrationManager.setEnabledForMode(isRawCalibrationMode, it, sensorName)
                    },
                    onOpenCalibration = { navController.navigate("settings/calibrations") },
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                SettingsItem(
                    title = stringResource(R.string.graph_smoothing_title),
                    subtitle = graphSmoothingLabel,
                    showArrow = true,
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/data-smoothing") }
                )

                JournalSettingsItem(
                    journalEnabled = journalEnabled,
                    onToggleEnabled = { viewModel.setJournalEnabled(it) },
                    onOpenJournal = { navController.navigate("settings/journal") },
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                PredictiveSimulationSettingsItem(
                    predictiveSimulationEnabled = predictiveSimulationEnabled,
                    onToggleEnabled = { viewModel.setPredictiveSimulationEnabled(it) },
                    onOpenSettings = { navController.navigate("settings/predictive-simulation") },
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
                AlertsAndAlarmsSettingsItem(
                    alertsEnabled = alertsMasterEnabled,
                    onToggleEnabled = { viewModel.setAlertsMasterEnabled(it) },
                    onOpenSettings = { navController.navigate("settings/alerts") },
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
                SettingsItem(
                    title = stringResource(R.string.floatglucose),
                    subtitle = stringResource(R.string.enable_overlay_desc),
                    icon = Icons.Default.PictureInPicture,
                    iconTint = notifColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/floating-display") }
                )
                SettingsItem(
                    title = stringResource(R.string.lock_screen_aod),
                    subtitle = stringResource(R.string.customize_always_on_display),
                    icon = Icons.Default.Visibility,
                    iconTint = notifColor,
                    position = CardPosition.BOTTOM,
                    onClick = { navController.navigate("settings/aod-display") }
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
            val xdripEnabled by viewModel.xDripBroadcastEnabled.collectAsState()
            val glucodataBroadcastEnabled by viewModel.glucodataBroadcastEnabled.collectAsState()
            val broadcastComputedTrend by viewModel.broadcastComputedTrend.collectAsState()

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
                SettingsItem(
                    title = stringResource(R.string.outbound_api_title),
                    subtitle = stringResource(R.string.outbound_api_desc),
                    showArrow = true,
                    icon = Icons.Default.CloudUpload,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/outbound-api") }
                )
                SettingsItem(
                    title = stringResource(R.string.mirror),
                    subtitle = stringResource(R.string.mirror_desc),
                    showArrow = true,
                    icon = Icons.Default.Devices,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/mirror") }
                )
                // Edit 67b: Determine if LibreView is visible to adjust card positions
                SettingsItem(
                    title = stringResource(R.string.nightscout_config),
                    subtitle = stringResource(R.string.nightscout_desc),
                    showArrow = true,
                    icon = Icons.Default.CloudUpload,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/nightscout") }
                )
                if (showLibreView) {
                    SettingsItem(
                        title = stringResource(R.string.libreview_config),
                        subtitle = stringResource(R.string.libreview_desc),
                        showArrow = true,
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
                        showArrow = true,
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
                        showArrow = true,
                        icon = Icons.Default.Sensors,
                        iconTint = exchangeColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/ottai") }
                    )
                }
                SettingsItem(
                    title = stringResource(R.string.meterlist),
                    subtitle = stringResource(R.string.glucose_meters_desc),
                    showArrow = true,
                    icon = Icons.Default.Bloodtype,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/glucose-meters") }
                )
                SettingsItem(
                    title = stringResource(R.string.insulin_pens_title),
                    subtitle = stringResource(R.string.insulin_pens_desc),
                    showArrow = true,
                    icon = Icons.Default.Vaccines,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/insulin-pens") }
                )
                SettingsItem(
                    title = stringResource(R.string.watches),
                    subtitle = "WearOS, Watchdrip, GadgetBridge, Kerfstok",
                    showArrow = true,
                    icon = Icons.Default.Devices,
                    iconTint = exchangeColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/watch") }
                )
                SettingsItem(
                    title = stringResource(R.string.webserver),
                    subtitle = stringResource(R.string.web_server_desc),
                    showArrow = true,
                    icon = Icons.Default.Language,
                    iconTint = exchangeColor,
                    position = CardPosition.BOTTOM,
                    onClick = { navController.navigate("settings/webserver") }
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
                        showArrow = true,
                        icon = Icons.Default.DeleteSweep,
                        iconTint = advColor,
                        position = CardPosition.MIDDLE,
                        onClick = { showSensorHandoverActionDialog = true }
                    )
                }
                SettingsItem(
                    title = stringResource(R.string.cgm_readiness_title),
                    subtitle = stringResource(R.string.cgm_readiness_settings_desc),
                    showArrow = true,
                    icon = Icons.Default.Security,
                    iconTint = advColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/cgm-readiness") }
                )
                SettingsItem(
                    title = stringResource(R.string.debug_logs),
                    subtitle = stringResource(R.string.debug_logs_desc),
                    showArrow = true,
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
                            if (tk.glucodata.data.SettingsExporter.isSettingsExport(context, uri)) {
                                withContext(Dispatchers.Main) {
                                    pendingSettingsImportUri = uri
                                }
                            } else if (tk.glucodata.data.ExportPackageExporter.isExportPackage(context, uri)) {
                                withContext(Dispatchers.Main) {
                                    pendingExportPackageImportUri = uri
                                }
                            } else {
                                // Show loading? For now just toast result
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
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppUpdatesSettingsItem(
                    iconTint = dataColor,
                    position = CardPosition.TOP,
                    onOpen = { navController.navigate("settings/app-updates") }
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
                    .freezeDisplayedValues.collectAsState()
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
                Spacer(modifier = Modifier.height(6.dp))
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
        val sheetState = rememberModalBottomSheetState()
        ExportDataSettingsSheet(
            onDismiss = { showExportDialog = false },
            sheetState = sheetState
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
private fun SettingsLeadingIcon(
    icon: ImageVector,
    tint: Color
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.12f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ManualCalibrationSettingsItem(
    calibrationEnabled: Boolean,
    modeLabel: String,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenCalibration: () -> Unit,
    iconTint: Color,
    position: CardPosition
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenCalibration,
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsLeadingIcon(icon = Icons.Default.WaterDrop, tint = iconTint)
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.manual_calibration),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "$modeLabel - ${if (calibrationEnabled) "active" else "paused"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StyledSwitch(
                    checked = calibrationEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }
        }
    }
}

@Composable
private fun JournalSettingsItem(
    journalEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenJournal: () -> Unit,
    iconTint: Color,
    position: CardPosition
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenJournal,
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsLeadingIcon(icon = Icons.Default.Vaccines, tint = iconTint)
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.journal_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(if (journalEnabled) R.string.enabled_status else R.string.disabled_status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StyledSwitch(
                    checked = journalEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }
        }
    }
}

@Composable
private fun PredictiveSimulationSettingsItem(
    predictiveSimulationEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    iconTint: Color,
    position: CardPosition
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenSettings,
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsLeadingIcon(icon = Icons.AutoMirrored.Filled.ShowChart, tint = iconTint)
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.predictive_simulation_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(if (predictiveSimulationEnabled) R.string.enabled_status else R.string.disabled_status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StyledSwitch(
                    checked = predictiveSimulationEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }
        }
    }
}

@Composable
private fun AlertsAndAlarmsSettingsItem(
    alertsEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    iconTint: Color,
    position: CardPosition
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenSettings,
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsLeadingIcon(icon = Icons.Default.AddAlert, tint = iconTint)
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.glucose_alerts_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(if (alertsEnabled) R.string.global_active else R.string.global_all_alerts_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StyledSwitch(
                    checked = alertsEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }
        }
    }
}

@Composable
fun PredictiveSimulationSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsState()
    val notificationChartPredictionEnabled by viewModel.predictiveSimulationNotificationChartEnabled.collectAsState()
    val trendMomentumEnabled by viewModel.predictionTrendMomentumEnabled.collectAsState()
    val modelProfile by viewModel.predictionModelProfile.collectAsState()
    val horizonMinutes by viewModel.predictionHorizonMinutes.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.predictive_simulation_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        showArrow = true,
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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

@Composable
fun NotificationSettingsSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    context: android.content.Context,
    viewModel: DashboardViewModel
) {
    val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
    val notificationChartEnabled by viewModel.notificationChartEnabled.collectAsState()
    
    // Font Settings
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("notification_font_size", 1.0f)) }
    var fontType by remember { mutableIntStateOf(prefs.getInt("notification_font_family", 0)) } // 0=App, 1=System
    var fontWeight by remember { mutableIntStateOf(prefs.getInt("notification_font_weight", 400)) }
    
    // Arrow Settings
    var showArrow by remember { mutableStateOf(prefs.getBoolean("notification_show_arrow", true)) }
    var arrowSize by remember { mutableFloatStateOf(prefs.getFloat("notification_arrow_size", 1.0f)) }
    
    // Visibility Toggles
    var showStatus by remember { mutableStateOf(prefs.getBoolean("notification_show_status", true)) }
    var hideStatusIcon by remember { mutableStateOf(prefs.getBoolean("notification_hide_status_icon", false)) }
    var statusIconScale by remember { mutableFloatStateOf(prefs.getFloat("notification_status_icon_scale", 1.0f)) }
    var collapsedChart by remember { mutableStateOf(prefs.getBoolean("notification_chart_collapsed", false)) }
    var showTargetRange by remember { mutableStateOf(prefs.getBoolean("notification_chart_target_range", true)) }
    
    val scope = rememberCoroutineScope()
    fun save() {
        scope.launch {
            prefs.edit()
                 .putFloat("notification_font_size", fontSize)
                 .putInt("notification_font_family", fontType)
                 .putInt("notification_font_weight", fontWeight)
                 .putBoolean("notification_show_arrow", showArrow)
                 .putFloat("notification_arrow_size", arrowSize)
                 .putBoolean("notification_show_status", showStatus)
                 .putBoolean("notification_hide_status_icon", hideStatusIcon)
                 .putFloat("notification_status_icon_scale", statusIconScale)
                 .putBoolean("notification_chart_collapsed", collapsedChart)
                 .putBoolean("notification_chart_target_range", showTargetRange)
                 .apply()
        }
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState(50))
        ) {
            Text(
                "Notification Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )



            // === FONT SECTION ===
            SectionLabel("Font", topPadding = 0.dp, modifier = Modifier.padding(horizontal = 24.dp))
            
            // Font Family Toggle
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                FilterChip(
                    selected = fontType == 0,
                    onClick = { fontType = 0; save() },
                    label = { Text(stringResource(R.string.font_app_plex)) }
//                    leadingIcon = { if(fontType == 0) Icon(Icons.Filled.Check, null) }
                )
                FilterChip(
                    selected = fontType == 1,
                    onClick = { fontType = 1; save() },
                    label = { Text(stringResource(R.string.font_system_google_sans)) }
//                    leadingIcon = { if(fontType == 1) Icon(Icons.Filled.Check, null) }
                )
            }
            Spacer(Modifier.height(8.dp))

            // Font Weight - only on Android 12+ (API 31) where RemoteViews supports setFontVariationSettings
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Text(stringResource(R.string.font_weight_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp,  vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Simplified Options: Regular (400) and Medium (500)
                    FilterChip(
                        selected = fontWeight == 300,
                        onClick = { fontWeight = 300; save() },
                        label = { Text(stringResource(R.string.theme_light)) }
//                        leadingIcon = { if(fontWeight == 300) Icon(Icons.Filled.Check, null) }
                    )
                    FilterChip(
                        selected = fontWeight == 400,
                        onClick = { fontWeight = 400; save() },
                        label = { Text(stringResource(R.string.regular)) }
//                        leadingIcon = { if(fontWeight == 400) Icon(Icons.Filled.Check, null) }
                    )
                    FilterChip(
                        selected = fontWeight == 500,
                        onClick = { fontWeight = 500; save() },
                        label = { Text(stringResource(R.string.medium)) }
//                        leadingIcon = { if(fontWeight == 500) Icon(Icons.Filled.Check, null) }
                    )
                }
            }
//            Spacer(Modifier.height(16.dp))

//            SectionLabel("Size", topPadding = 0.dp, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(4.dp))

            SliderControl(
                label = "Font Size: ${(fontSize * 100).toInt()}%",
                value = fontSize,
                onValueChange = { fontSize = it; save() },
                range = 0.6f..1.5f
            )
            Spacer(Modifier.height(4.dp))

            SliderControl(
                label = "Status Bar Icon Size: ${(statusIconScale * 100).toInt()}%",
                value = statusIconScale,
                onValueChange = { statusIconScale = it; save() },
                range = 0.0f..1.25f
//                    steps = 50
            )
        }
//        Spacer(Modifier.height(8.dp))
           // === ARROW SECTION ===
//            Spacer(Modifier.height(8.dp))
//            SectionLabel("Trend Arrow", topPadding = 0.dp, modifier = Modifier.padding(horizontal = 24.dp))
            // === VISIBILITY SECTION ===
//            Spacer(Modifier.height(16.dp))
//            SectionLabel("Elements", topPadding = 0.dp, modifier = Modifier.padding(horizontal = 24.dp))
//            SettingsSwitchItem(
//                title = "Show Status Text",
//                subtitle = "Sensor connection status",
//                checked = showStatus,
//                onCheckedChange = { showStatus = it; save() },
//                icon = null,
//                position = CardPosition.TOP
//            Column(
//                    Modifier.fillMaxWidth().padding(horizontal = 24.dp,  vertical = 0.dp)
//                ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)) {
                SettingsSwitchItem(
                    title = "Show Trend Arrow",
                    checked = showArrow,
                    onCheckedChange = { showArrow = it; save() },
                    icon = null,
                    position = CardPosition.TOP
                )

                if (showArrow) {
                    Spacer(Modifier.height(4.dp))

                    SliderControl(
                        label = "Arrow Size: ${(arrowSize * 100).toInt()}%",
                        value = arrowSize,
                        onValueChange = { arrowSize = it; save() },
                        range = 0.5f..1.5f
                    )
                    Spacer(Modifier.height(4.dp))

                }
                SettingsSwitchItem(
                    title = "Show Chart (Expanded)",
                    subtitle = "Chart when notification is expanded",
                    checked = notificationChartEnabled,
                    onCheckedChange = { viewModel.toggleNotificationChart(it) },
                    icon = null,
                    position = CardPosition.MIDDLE
                )

                SettingsSwitchItem(
                    title = "Show Chart (Collapsed)",
                    subtitle = "Compact chart in collapsed view",
                    checked = collapsedChart,
                    onCheckedChange = { collapsedChart = it; save() },
                    icon = null,
                    position = CardPosition.MIDDLE
                )

                SettingsSwitchItem(
                    title = "Show Target Range",
                    subtitle = "Highlight target glucose range on chart",
                    checked = showTargetRange,
                    onCheckedChange = { showTargetRange = it; save() },
                    icon = null,
                    position = CardPosition.BOTTOM
                )
                //            SettingsSwitchItem(
//                title = "Hide Status Bar Icon",
//                subtitle = "Use transparent icon (minimize clutter)",
//                checked = hideStatusIcon,
//                onCheckedChange = { hideStatusIcon = it; save() },
//                icon = null,
//                position = CardPosition.MIDDLE
//            )
                //            if (!hideStatusIcon) {

//            }
//            Spacer(Modifier.height(16.dp))

            }
        }
    }


@Composable
fun AODSettingsSheet(onDismiss: () -> Unit, sheetState: SheetState, context: android.content.Context) {
    val prefs = context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
    
    var opacity by remember { mutableFloatStateOf(prefs.getFloat("aod_opacity", 1.0f)) }
    var textScale by remember { mutableFloatStateOf(prefs.getFloat("aod_text_scale", 1.5f)) }
    var chartScale by remember { mutableFloatStateOf(prefs.getFloat("aod_chart_scale", 1.5f)) }
    var showChart by remember { mutableStateOf(prefs.getBoolean("aod_show_chart", true)) }
    var showArrow by remember { mutableStateOf(prefs.getBoolean("aod_show_arrow", true)) }
    var arrowScale by remember { mutableFloatStateOf(prefs.getFloat("aod_arrow_scale", 1.0f)) }
    
    // Position: Multi-select stored as Set<String>
    var positions by remember { 
        mutableStateOf(prefs.getStringSet("aod_positions", setOf("TOP")) ?: setOf("TOP")) 
    }

    // Alignment: Single select (LEFT, CENTER, RIGHT)
    var alignment by remember { mutableStateOf(prefs.getString("aod_alignment", "CENTER") ?: "CENTER") }

    // FONT VISUALS
    var fontSource by remember { mutableStateOf(prefs.getString("aod_font_source", "APP") ?: "APP") }
    var fontWeight by remember { mutableIntStateOf(prefs.getInt("aod_font_weight", 400)) }

    // Auto-save logic scope
    val scope = rememberCoroutineScope()
    fun save() {
        scope.launch {
            prefs.edit()
                 .putFloat("aod_opacity", opacity)
                 .putFloat("aod_text_scale", textScale)
                 .putFloat("aod_chart_scale", chartScale)
                 .putBoolean("aod_show_chart", showChart)
                 .putBoolean("aod_show_arrow", showArrow)
                 .putFloat("aod_arrow_scale", arrowScale)
                 .putStringSet("aod_positions", positions)
                 .putString("aod_alignment", alignment)
                 .putString("aod_font_source", fontSource)
                 .putInt("aod_font_weight", fontWeight)
                 .apply()
        }
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "AOD Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Enable Button
            SettingsItem(
                title = "Enable Overlay service",
                subtitle = "Opens Accessibility Settings",
                icon = Icons.Default.SettingsAccessibility,
                position = CardPosition.SINGLE,
                onClick = {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // === VISUALS SECTION ===
            Text(stringResource(R.string.visuals), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

            // FONT SOURCE
            Text(stringResource(R.string.font_source), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 FilterChip(
                     selected = fontSource == "APP",
                     onClick = { fontSource = "APP"; save() },
                     label = { Text(stringResource(R.string.font_app_plex)) }
                 )
                 FilterChip(
                     selected = fontSource == "SYSTEM",
                     onClick = { fontSource = "SYSTEM"; save() },
                     label = { Text(stringResource(R.string.font_system_google_sans)) }
                 )
            }

            // FONT WEIGHT
            Text(stringResource(R.string.font_weight_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 FilterChip(
                     selected = fontWeight == 300,
                     onClick = { fontWeight = 300; save() },
                     label = { Text(stringResource(R.string.theme_light)) }
                 )
                 FilterChip(
                     selected = fontWeight == 400,
                     onClick = { fontWeight = 400; save() },
                     label = { Text(stringResource(R.string.regular)) }
                 )
                 FilterChip(
                     selected = fontWeight == 500,
                     onClick = { fontWeight = 500; save() },
                     label = { Text(stringResource(R.string.medium)) }
                 )
            }

            Spacer(Modifier.height(8.dp))

            
            Spacer(Modifier.height(16.dp))
            SectionLabel("Layout", topPadding = 0.dp, modifier = Modifier.padding(horizontal = 24.dp))

            // Multi-Position Selection
            Text(stringResource(R.string.active_positions_randomized), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 listOf("TOP", "CENTER", "BOTTOM").forEach { pos ->
                     FilterChip(
                         selected = positions.contains(pos),
                         onClick = { 
                             val newSet = positions.toMutableSet()
                             if (newSet.contains(pos)) {
                                 if (newSet.size > 1) newSet.remove(pos) // Prevent empty set
                             } else {
                                 newSet.add(pos)
                             }
                             positions = newSet
                             save()
                         },
                         label = { Text(pos) },
                         leadingIcon = if (positions.contains(pos)) {
                             { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                         } else null
                     )
                 }
            }

            Spacer(Modifier.height(12.dp))

            // Alignment Selection
            Text(stringResource(R.string.text_alignment), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 listOf("LEFT", "CENTER", "RIGHT").forEach { align ->
                     FilterChip(
                         selected = alignment == align,
                         onClick = { alignment = align; save() },
                         label = { Text(align) },
                         leadingIcon = null // Radio behavior essentially
                     )
                 }
            }
            
            Spacer(Modifier.height(24.dp))
            SectionLabel("Appearance", topPadding = 0.dp, modifier = Modifier.padding(horizontal = 24.dp))

            // Toggles Group
            SettingsSwitchItem(
                title = "Show Chart",
                checked = showChart,
                onCheckedChange = { showChart = it; save() },
                icon = null,
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = "Show Trend Arrow",
                checked = showArrow,
                onCheckedChange = { showArrow = it; save() },
                icon = null,
                position = CardPosition.BOTTOM
            )

            // Opacity
            SliderControl(
                label = "Opacity: ${(opacity * 100).toInt()}%",
                value = opacity,
                onValueChange = { opacity = it; save() },
                range = 0.1f..1.0f
            )

            // Text Scale
            SliderControl(
                label = "Text Size: ${(textScale * 100).toInt()}%",
                value = textScale,
                onValueChange = { textScale = it; save() },
                range = 0.5f..3.0f
            )

            // Chart Scale
            if (showChart) {
                SliderControl(
                    label = "Chart Size: ${(chartScale * 100).toInt()}%",
                    value = chartScale,
                    onValueChange = { chartScale = it; save() },
                    range = 0.5f..2.0f
                )
            }

            // Arrow Scale
            if (showArrow) {
                SliderControl(
                    label = "Arrow Size: ${(arrowScale * 100).toInt()}%",
                    value = arrowScale,
                    onValueChange = { arrowScale = it; save() },
                    range = 0.5f..2.0f
                )
            }
        }
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
