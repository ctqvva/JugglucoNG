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
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.data.calibration.CalibrationManager
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

    // States
    val unit by viewModel.unit.collectAsState()
    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)
    val patchedLibreEnabled by viewModel.patchedLibreBroadcastEnabled.collectAsState()
    val notificationChartEnabled by viewModel.notificationChartEnabled.collectAsState()
    val chartSmoothingMinutes by viewModel.chartSmoothingMinutes.collectAsState()
    val dataSmoothingGraphOnly by viewModel.dataSmoothingGraphOnly.collectAsState()
    val dataSmoothingCollapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsState()
    val previewWindowMode by viewModel.previewWindowMode.collectAsState()
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val journalInsulinPresets by viewModel.journalInsulinPresets.collectAsState()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsState()
    val predictionTrendMomentumEnabled by viewModel.predictionTrendMomentumEnabled.collectAsState()
    val predictionCarbRatioGramsPerUnit by viewModel.predictionCarbRatioGramsPerUnit.collectAsState()
    val predictionInsulinSensitivityMgDlPerUnit by viewModel.predictionInsulinSensitivityMgDlPerUnit.collectAsState()
    val predictionCarbAbsorptionGramsPerHour by viewModel.predictionCarbAbsorptionGramsPerHour.collectAsState()
    val predictionHorizonMinutes by viewModel.predictionHorizonMinutes.collectAsState()
    val alertsSummary by viewModel.alertsSummary.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val isRawCalibrationMode = viewMode == 1 || viewMode == 3
    val calibrationEnabledRaw by CalibrationManager.isEnabledForRaw.collectAsState()
    val calibrationEnabledAuto by CalibrationManager.isEnabledForAuto.collectAsState()
    val calibrationEnabled = if (isRawCalibrationMode) calibrationEnabledRaw else calibrationEnabledAuto
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
    val graphLowValue by viewModel.graphLow.collectAsState()
    val graphHighValue by viewModel.graphHigh.collectAsState()
    val targetLowValue by viewModel.targetLow.collectAsState()
    val targetHighValue by viewModel.targetHigh.collectAsState()

    // Dialog states
    var showUnitDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var showPreviewWindowDialog by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingSettingsImportUri by remember { mutableStateOf<Uri?>(null) }
    var glucoseRangeExpanded by rememberSaveable { mutableStateOf(false) }
    var predictiveSimulationExpanded by rememberSaveable { mutableStateOf(false) }



    // Advanced settings
    var turbo by remember { mutableStateOf(Natives.getpriority()) }
    var autoConnect by remember { mutableStateOf(Natives.getAndroid13()) }

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
            if (dataSmoothingGraphOnly) {
                add(stringResource(R.string.data_smoothing_graph_only_title))
            }
            if (dataSmoothingCollapseChunks) {
                add(stringResource(R.string.data_smoothing_collapse_summary_format, collapseIntervalMinutes))
            }
        }.joinToString(" · ")
    }
    val previewWindowLabel = when (previewWindowMode) {
        1 -> stringResource(R.string.preview_window_always)
        2 -> stringResource(R.string.preview_window_never)
        else -> stringResource(R.string.preview_window_expanded_only)
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

                GlucoseRangeExpandableSettingsItem(
                    targetLowValue = targetLowValue,
                    targetHighValue = targetHighValue,
                    chartLowValue = graphLowValue,
                    chartHighValue = graphHighValue,
                    isMmol = isMmol,
                    expanded = glucoseRangeExpanded,
                    onExpandedChange = { glucoseRangeExpanded = it },
                    onTargetRangeChange = { low, high -> viewModel.setTargetRange(low, high) },
                    onChartRangeChange = { low, high -> viewModel.setGraphRange(low, high) },
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                ManualCalibrationSettingsItem(
                    calibrationEnabled = calibrationEnabled,
                    modeLabel = calibrationModeLabel,
                    onToggleEnabled = { CalibrationManager.setEnabledForMode(isRawCalibrationMode, it) },
                    onOpenCalibration = { navController.navigate("settings/calibrations") },
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                JournalSettingsItem(
                    journalEnabled = journalEnabled,
                    activePresetCount = journalInsulinPresets.count { !it.isArchived },
                    onToggleEnabled = { viewModel.setJournalEnabled(it) },
                    onOpenJournal = { navController.navigate("settings/journal") },
                    iconTint = glucoseColor,
                    position = CardPosition.MIDDLE
                )

                PredictiveSimulationExpandableSettingsItem(
                    journalEnabled = journalEnabled,
                    predictiveSimulationEnabled = predictiveSimulationEnabled,
                    trendMomentumEnabled = predictionTrendMomentumEnabled,
                    carbRatioGramsPerUnit = predictionCarbRatioGramsPerUnit,
                    insulinSensitivityMgDlPerUnit = predictionInsulinSensitivityMgDlPerUnit,
                    carbAbsorptionGramsPerHour = predictionCarbAbsorptionGramsPerHour,
                    horizonMinutes = predictionHorizonMinutes,
                    isMmol = isMmol,
                    expanded = predictiveSimulationExpanded,
                    onExpandedChange = { predictiveSimulationExpanded = it },
                    onToggleSimulation = { viewModel.setPredictiveSimulationEnabled(it) },
                    onToggleTrendMomentum = { viewModel.setPredictionTrendMomentumEnabled(it) },
                    onCarbRatioChange = { viewModel.setPredictionCarbRatioGramsPerUnit(it) },
                    onInsulinSensitivityChange = { viewModel.setPredictionInsulinSensitivityMgDlPerUnit(it) },
                    onCarbAbsorptionChange = { viewModel.setPredictionCarbAbsorptionGramsPerHour(it) },
                    onHorizonChange = { viewModel.setPredictionHorizonMinutes(it) },
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
                SettingsItem(
                    title = stringResource(R.string.glucose_alerts_title),
                    subtitle = alertsSummary,
                    icon = Icons.Default.AddAlert,
                    iconTint = MaterialTheme.colorScheme.error,
                    showArrow = true,
                    position = CardPosition.TOP,
                    onClick = { navController.navigate("settings/alerts") }
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
                val showLibreView = Natives.getuselibreview() || Natives.getlibreAccountIDnumber() > 0L
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
                SettingsItem(
                    title = stringResource(R.string.graph_smoothing_title),
                    subtitle = graphSmoothingLabel,
                    showArrow = true,
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    iconTint = advColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/data-smoothing") }
                )
                SettingsItem(
                    title = stringResource(R.string.preview_window_title),
                    subtitle = previewWindowLabel,
                    showArrow = true,
                    icon = Icons.Default.CropSquare,
                    iconTint = advColor,
                    position = CardPosition.MIDDLE,
                    onClick = { showPreviewWindowDialog = true }
                )
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
            val settingsExportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json"),
                onResult = { uri ->
                    if (uri != null) {
                        scope.launch {
                            val result = tk.glucodata.data.SettingsExporter.exportToJson(context, uri)
                            withContext(Dispatchers.Main) {
                                val message = if (result.isSuccess) {
                                    context.getString(R.string.export_successful)
                                } else {
                                    context.getString(
                                        R.string.export_failed_with_error,
                                        result.exceptionOrNull()?.localizedMessage
                                            ?: context.getString(R.string.unknown_error)
                                    )
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            )
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = { uri ->
                    if (uri != null) {
                        scope.launch {
                            if (tk.glucodata.data.SettingsExporter.isSettingsExport(context, uri)) {
                                withContext(Dispatchers.Main) {
                                    pendingSettingsImportUri = uri
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
                SettingsItem(
                    title = stringResource(R.string.export_data),
                    subtitle = stringResource(R.string.export_data_desc),
                    icon = androidx.compose.material.icons.Icons.Default.CloudUpload,
                    iconTint = dataColor,
                    position = CardPosition.TOP,
                    onClick = { showExportDialog = true }
                )
                SettingsItem(
                    title = stringResource(R.string.export_settings),
                    subtitle = stringResource(R.string.export_settings_desc),
                    icon = Icons.Default.Settings,
                    iconTint = dataColor,
                    position = CardPosition.MIDDLE,
                    onClick = {
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(System.currentTimeMillis())
                        settingsExportLauncher.launch("Juggluco_Settings_$date.json")
                    }
                )

                SettingsItem(
                    title = stringResource(R.string.import_data_settings),
                    subtitle = stringResource(R.string.import_data_settings_desc),
                    icon = Icons.Default.FolderOpen,
                    iconTint = dataColor,
                    position = CardPosition.BOTTOM,
                    onClick = { 
                        importLauncher.launch(arrayOf("application/json", "text/*", "text/csv", "*/*"))
                    }
                )
            }
        }

        // === DANGER ===
        item(key = "danger_label") { SectionLabel(stringResource(R.string.danger_zone), isError = true) }

        item(key = "danger_group") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Restart App (Low Severity)
                DangerItem(
                    title = "Restart App",
                    subtitle = "Force restart the application",
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
    if (showPreviewWindowDialog) PreviewWindowPickerDialog(
        currentMode = previewWindowMode,
        onSelect = {
            viewModel.setPreviewWindowMode(it)
            showPreviewWindowDialog = false
        },
        onDismiss = { showPreviewWindowDialog = false }
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
        HistoryExportSheet(
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
private fun GlucoseRangeExpandableSettingsItem(
    targetLowValue: Float,
    targetHighValue: Float,
    chartLowValue: Float,
    chartHighValue: Float,
    isMmol: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTargetRangeChange: (Float, Float) -> Unit,
    onChartRangeChange: (Float, Float) -> Unit,
    iconTint: Color,
    position: CardPosition
) {
    val valueStep = if (isMmol) 0.1f else 1f
    val targetLowBounds = if (isMmol) 2.0f..8.0f else 40f..140f
    val targetHighBounds = if (isMmol) 6.0f..16.0f else 100f..350f
    val chartLowBounds = if (isMmol) 0.0f..12.0f else 0f..216f
    val chartHighBounds = if (isMmol) 4.0f..30.0f else 72f..540f
    val normalizedTargetLow = clampLowerRangeValue(
        value = targetLowValue,
        upperValue = targetHighValue,
        bounds = targetLowBounds,
        step = valueStep,
        isMmol = isMmol
    )
    val normalizedTargetHigh = clampUpperRangeValue(
        value = targetHighValue,
        lowerValue = normalizedTargetLow,
        bounds = targetHighBounds,
        step = valueStep,
        isMmol = isMmol
    )
    val normalizedChartLow = clampLowerRangeValue(
        value = chartLowValue,
        upperValue = chartHighValue,
        bounds = chartLowBounds,
        step = valueStep,
        isMmol = isMmol
    )
    val normalizedChartHigh = clampUpperRangeValue(
        value = chartHighValue,
        lowerValue = normalizedChartLow,
        bounds = chartHighBounds,
        step = valueStep,
        isMmol = isMmol
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "glucoseRangeChevron"
    )
    var targetLowSlider by remember(targetLowValue, targetHighValue, isMmol) {
        mutableFloatStateOf(normalizedTargetLow)
    }
    var targetHighSlider by remember(targetLowValue, targetHighValue, isMmol) {
        mutableFloatStateOf(normalizedTargetHigh)
    }
    var chartLowSlider by remember(chartLowValue, chartHighValue, isMmol) {
        mutableFloatStateOf(normalizedChartLow)
    }
    var chartHighSlider by remember(chartLowValue, chartHighValue, isMmol) {
        mutableFloatStateOf(normalizedChartHigh)
    }

    val targetSummary = remember(targetLowSlider, targetHighSlider, isMmol) {
        formatRangeSummary(targetLowSlider, targetHighSlider, isMmol)
    }
    val chartSummary = remember(chartLowSlider, chartHighSlider, isMmol) {
        formatRangeSummary(chartLowSlider, chartHighSlider, isMmol)
    }
    val targetShortTitle = stringResource(R.string.target_short_title)
    val chartShortTitle = stringResource(R.string.chart_short_title)
    val targetTitle = stringResource(R.string.target_range_title)
    val chartTitle = stringResource(R.string.chart_limits_title)
    val collapsedSummary = remember(targetShortTitle, chartShortTitle, targetSummary, chartSummary) {
        "$targetShortTitle $targetSummary • $chartShortTitle $chartSummary"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsLeadingIcon(icon = Icons.Default.TrackChanges, tint = iconTint)
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.glucose_range_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        text = collapsedSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        GlucoseRangeEditorSection(
                            title = targetTitle,
                            lowLabel = stringResource(R.string.low_label),
                            highLabel = stringResource(R.string.high_label),
                            lowValue = targetLowSlider,
                            highValue = targetHighSlider,
                            lowBounds = targetLowBounds,
                            highBounds = targetHighBounds,
                            isMmol = isMmol,
                            onLowValueChange = { candidate ->
                                targetLowSlider = clampLowerRangeValue(
                                    value = candidate,
                                    upperValue = targetHighSlider,
                                    bounds = targetLowBounds,
                                    step = valueStep,
                                    isMmol = isMmol
                                )
                            },
                            onHighValueChange = { candidate ->
                                targetHighSlider = clampUpperRangeValue(
                                    value = candidate,
                                    lowerValue = targetLowSlider,
                                    bounds = targetHighBounds,
                                    step = valueStep,
                                    isMmol = isMmol
                                )
                            },
                            onLowValueChangeFinished = {
                                onTargetRangeChange(targetLowSlider, targetHighSlider)
                            },
                            onHighValueChangeFinished = {
                                onTargetRangeChange(targetLowSlider, targetHighSlider)
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        GlucoseRangeEditorSection(
                            title = chartTitle,
                            lowLabel = stringResource(R.string.low_label),
                            highLabel = stringResource(R.string.high_label),
                            lowValue = chartLowSlider,
                            highValue = chartHighSlider,
                            lowBounds = chartLowBounds,
                            highBounds = chartHighBounds,
                            isMmol = isMmol,
                            onLowValueChange = { candidate ->
                                chartLowSlider = clampLowerRangeValue(
                                    value = candidate,
                                    upperValue = chartHighSlider,
                                    bounds = chartLowBounds,
                                    step = valueStep,
                                    isMmol = isMmol
                                )
                            },
                            onHighValueChange = { candidate ->
                                chartHighSlider = clampUpperRangeValue(
                                    value = candidate,
                                    lowerValue = chartLowSlider,
                                    bounds = chartHighBounds,
                                    step = valueStep,
                                    isMmol = isMmol
                                )
                            },
                            onLowValueChangeFinished = {
                                onChartRangeChange(chartLowSlider, chartHighSlider)
                            },
                            onHighValueChangeFinished = {
                                onChartRangeChange(chartLowSlider, chartHighSlider)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlucoseRangeEditorSection(
    title: String,
    lowLabel: String,
    highLabel: String,
    lowValue: Float,
    highValue: Float,
    lowBounds: ClosedFloatingPointRange<Float>,
    highBounds: ClosedFloatingPointRange<Float>,
    isMmol: Boolean,
    onLowValueChange: (Float) -> Unit,
    onHighValueChange: (Float) -> Unit,
    onLowValueChangeFinished: () -> Unit,
    onHighValueChangeFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$lowLabel: ${formatRangeValue(lowValue, isMmol)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = lowValue,
            onValueChange = onLowValueChange,
            onValueChangeFinished = onLowValueChangeFinished,
            valueRange = lowBounds
        )
        Text(
            text = "$highLabel: ${formatRangeValue(highValue, isMmol)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = highValue,
            onValueChange = onHighValueChange,
            onValueChangeFinished = onHighValueChangeFinished,
            valueRange = highBounds
        )
    }
}

private fun formatRangeSummary(lowValue: Float, highValue: Float, isMmol: Boolean): String {
    return "${formatRangeValue(lowValue, isMmol)}-${formatRangeValue(highValue, isMmol)}"
}

private fun formatRangeValue(value: Float, isMmol: Boolean): String {
    return if (isMmol) {
        String.format(Locale.getDefault(), "%.1f", value)
    } else {
        value.roundToInt().toString()
    }
}

private fun clampLowerRangeValue(
    value: Float,
    upperValue: Float,
    bounds: ClosedFloatingPointRange<Float>,
    step: Float,
    isMmol: Boolean
): Float {
    val maxValue = minOf(bounds.endInclusive, upperValue - step)
    if (maxValue < bounds.start) {
        return snapRangeValue(bounds.start, isMmol)
    }
    return snapRangeValue(value, isMmol).coerceIn(bounds.start, maxValue)
}

private fun clampUpperRangeValue(
    value: Float,
    lowerValue: Float,
    bounds: ClosedFloatingPointRange<Float>,
    step: Float,
    isMmol: Boolean
): Float {
    val minValue = maxOf(bounds.start, lowerValue + step)
    if (minValue > bounds.endInclusive) {
        return snapRangeValue(bounds.endInclusive, isMmol)
    }
    return snapRangeValue(value, isMmol).coerceIn(minValue, bounds.endInclusive)
}

private fun snapRangeValue(value: Float, isMmol: Boolean): Float {
    return if (isMmol) {
        (value * 10f).roundToInt() / 10f
    } else {
        value.roundToInt().toFloat()
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
    activePresetCount: Int,
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
                    text = stringResource(
                        R.string.journal_settings_summary,
                        stringResource(if (journalEnabled) R.string.enabled_status else R.string.disabled_status),
                        activePresetCount
                    ),
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
private fun PredictiveSimulationExpandableSettingsItem(
    journalEnabled: Boolean,
    predictiveSimulationEnabled: Boolean,
    trendMomentumEnabled: Boolean,
    carbRatioGramsPerUnit: Float,
    insulinSensitivityMgDlPerUnit: Float,
    carbAbsorptionGramsPerHour: Float,
    horizonMinutes: Int,
    isMmol: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggleSimulation: (Boolean) -> Unit,
    onToggleTrendMomentum: (Boolean) -> Unit,
    onCarbRatioChange: (Float) -> Unit,
    onInsulinSensitivityChange: (Float) -> Unit,
    onCarbAbsorptionChange: (Float) -> Unit,
    onHorizonChange: (Int) -> Unit,
    iconTint: Color,
    position: CardPosition
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "predictiveSimulationChevron"
    )
    val insulinSensitivityDisplay = remember(insulinSensitivityMgDlPerUnit, isMmol) {
        tk.glucodata.ui.util.GlucoseFormatter.displayFromMgDl(insulinSensitivityMgDlPerUnit, isMmol)
    }
    val sensitivityValue = if (isMmol) {
        stringResource(R.string.predictive_sensitivity_value_mmol, insulinSensitivityDisplay)
    } else {
        stringResource(R.string.predictive_sensitivity_value_mgdl, insulinSensitivityMgDlPerUnit)
    }
    val sensitivityRange = if (isMmol) 0.6f..10f else 10f..180f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
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
                        modifier = Modifier.padding(top = 2.dp),
                        text = stringResource(R.string.predictive_simulation_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.collapse_action else R.string.expand_action
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    VerticalDivider(
                        modifier = Modifier.height(30.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StyledSwitch(
                        checked = predictiveSimulationEnabled,
                        onCheckedChange = onToggleSimulation
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    PredictiveSimulationSubToggleRow(
                        title = stringResource(R.string.predictive_trend_momentum),
                        checked = trendMomentumEnabled,
                        enabled = predictiveSimulationEnabled,
                        onCheckedChange = onToggleTrendMomentum,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    if (journalEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
                        Text(
                            text = stringResource(R.string.predictive_model_tuning),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                        )
                        PredictiveSimulationParameterRow(
                            title = stringResource(R.string.predictive_carb_ratio),
                            valueLabel = stringResource(R.string.predictive_carb_ratio_value, carbRatioGramsPerUnit),
                            value = carbRatioGramsPerUnit,
                            valueRange = 3f..30f,
                            enabled = predictiveSimulationEnabled,
                            onValueChange = onCarbRatioChange
                        )
                        PredictiveSimulationParameterRow(
                            title = stringResource(R.string.predictive_insulin_sensitivity),
                            valueLabel = sensitivityValue,
                            value = insulinSensitivityDisplay,
                            valueRange = sensitivityRange,
                            enabled = predictiveSimulationEnabled,
                            onValueChange = { displayValue ->
                                onInsulinSensitivityChange(
                                    if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(displayValue) else displayValue
                                )
                            }
                        )
                        PredictiveSimulationParameterRow(
                            title = stringResource(R.string.predictive_carb_absorption),
                            valueLabel = stringResource(R.string.predictive_absorption_value, carbAbsorptionGramsPerHour),
                            value = carbAbsorptionGramsPerHour,
                            valueRange = 10f..90f,
                            enabled = predictiveSimulationEnabled,
                            onValueChange = onCarbAbsorptionChange
                        )
                    }
                    PredictiveSimulationParameterRow(
                        title = stringResource(R.string.predictive_forecast_horizon),
                        valueLabel = stringResource(R.string.predictive_horizon_value, horizonMinutes),
                        value = horizonMinutes.toFloat(),
                        valueRange = 30f..360f,
                        enabled = predictiveSimulationEnabled,
                        onValueChange = { onHorizonChange(it.roundToInt()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PredictiveSimulationParameterRow(
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
private fun PredictiveSimulationSubToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        StyledSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
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
                listOf(stringResource(R.string.unit_mg) to 0, stringResource(R.string.unit_mmol) to 1).forEach { (label, value) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (if (isMmol) 1 else 0) == value, onClick = null)
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
private fun PreviewWindowPickerDialog(
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
        "Turkish" to "tr",
        "Ukrainian" to "uk"
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
