@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorSourceResolver
import tk.glucodata.share.DexcomShareSettings
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
 * The Settings tab. Rows in one group are joined into a single card stack
 * ([CardPosition] TOP / MIDDLE / BOTTOM, 2dp apart); groups are separated by a
 * [SectionLabel].
 *
 * Each row answers its own question in its subtitle: the current value where there is
 * one, otherwise what the screen behind it holds. Rows with a switch don't repeat
 * "on"/"off" there; the switch already says it.
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
    // Dexcom Share follows the same rule as LibreView: shown once it is set up, or while a
    // Dexcom sensor is active.
    val showDexcomShare = remember {
        runCatching { DexcomShareSettings.isSetUp(context) }.getOrDefault(false) ||
            hasActiveDexcomSensor()
    }
    val showOttaiSettings = remember {
        SensorBluetooth.mygatts().any { callback ->
            (callback as? tk.glucodata.drivers.ottai.OttaiDriver)?.isUiEnabled() == true
        }
    }

    // States
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)
    val chartSmoothingMinutes by viewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val dataSmoothingGraphOnly by viewModel.dataSmoothingGraphOnly.collectAsStateWithLifecycle()
    val dataSmoothingCollapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsStateWithLifecycle()
    val dataSmoothingExchangeOnly by viewModel.dataSmoothingExchangeOnly.collectAsStateWithLifecycle()
    val journalEnabled by viewModel.journalEnabled.collectAsStateWithLifecycle()
    val predictiveSimulationEnabled by viewModel.predictiveSimulationEnabled.collectAsStateWithLifecycle()
    val alertsMasterEnabled by viewModel.alertsMasterEnabled.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val sensorName by viewModel.sensorName.collectAsStateWithLifecycle()
    val isRawCalibrationMode = viewMode == 1 || viewMode == 3
    val calibrationRevision by CalibrationManager.revision.collectAsStateWithLifecycle()
    val calibrationEnabled = remember(isRawCalibrationMode, sensorName, calibrationRevision) {
        CalibrationManager.isEnabledForMode(isRawCalibrationMode, sensorName)
    }
    val freezeDisplayedValues by CalibrationManager.freezeDisplayedValues.collectAsStateWithLifecycle()
    val sealGraceHours by CalibrationManager.displaySealGraceHours.collectAsStateWithLifecycle()
    val patchedLibreEnabled by viewModel.patchedLibreBroadcastEnabled.collectAsStateWithLifecycle()
    val xdripEnabled by viewModel.xDripBroadcastEnabled.collectAsStateWithLifecycle()
    val glucodataBroadcastEnabled by viewModel.glucodataBroadcastEnabled.collectAsStateWithLifecycle()
    val xdripFollowerEnabled by viewModel.xdripFollowerEnabled.collectAsStateWithLifecycle()
    val xdripReportAsLibre2 by viewModel.xdripReportAsLibre2.collectAsStateWithLifecycle()
    val broadcastComputedTrend by viewModel.broadcastComputedTrend.collectAsStateWithLifecycle()

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
    var healthConnectEnabled by remember { mutableStateOf(PhoneAppsSettings.healthConnectEnabled()) }
    var otherTrendConsumer by remember { mutableStateOf(PhoneAppsSettings.hasOtherTrendConsumer()) }

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
                healthConnectEnabled = PhoneAppsSettings.healthConnectEnabled()
                otherTrendConsumer = PhoneAppsSettings.hasOtherTrendConsumer()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Dialog states
    var showUnitDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showScheduledBackupDialog by remember { mutableStateOf(false) }
    var showSealGraceDialog by remember { mutableStateOf(false) }
    var scheduledBackupConfig by remember {
        mutableStateOf(ScheduledBackupSettings.load(context))
    }
    LaunchedEffect(Unit) {
        ScheduledBackupWorker.initialize(context)
    }
    var pendingSettingsImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportPackageImportUri by remember { mutableStateOf<Uri?>(null) }

    // Search. The query survives a trip into a result's screen, so Back lands on the
    // same results and the next one is a tap away.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searching = searchQuery.isNotBlank()
    val focusManager = LocalFocusManager.current
    val searchLocales = LocalConfiguration.current.locales
    val showLibre2Identity = PhoneAppsSettings.showsLibre2Identity(xdripEnabled, xdripReportAsLibre2)
    val showAppTrend = PhoneAppsSettings.showsAppTrend(
        xdripEnabled, patchedLibreEnabled, glucodataBroadcastEnabled, broadcastComputedTrend, otherTrendConsumer
    )
    val searchCandidates = remember(
        context, searchLocales, showLibreView, showDexcomShare, showMqAccount, showOttaiSettings,
        showLibre2Identity, showAppTrend
    ) {
        SettingsSearchIndex.resolve(context) { condition ->
            when (condition) {
                SettingsSearchCondition.LIBREVIEW -> showLibreView
                SettingsSearchCondition.DEXCOM_SHARE -> showDexcomShare
                SettingsSearchCondition.MQ_ACCOUNT -> showMqAccount
                SettingsSearchCondition.OTTAI -> showOttaiSettings
                SettingsSearchCondition.HEALTH_CONNECT -> PhoneAppsSettings.healthConnectSupported
                SettingsSearchCondition.LIBRE2_IDENTITY -> showLibre2Identity
                SettingsSearchCondition.APP_TREND -> showAppTrend
            }
        }
    }
    val searchResults = remember(searchQuery, searchCandidates) {
        SettingsSearchMatcher.search(searchQuery, searchCandidates)
    }
    BackHandler(enabled = searching) {
        searchQuery = ""
        focusManager.clearFocus()
    }

    val currentLocale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
    val currentLangName = currentLocale.displayLanguage.replaceFirstChar { it.uppercase() }

    val themeLabel = when(themeMode) {
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
    }
    val graphSmoothingLabel = if (chartSmoothingMinutes <= 0) {
        stringResource(R.string.settings_smoothing_off_desc)
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
    // Names of the apps on this phone that currently receive readings, so the row
    // answers "who gets my glucose?" without opening it.
    val phoneAppsLabel = buildList {
        if (xdripEnabled) add(stringResource(R.string.settings_app_name_xdrip))
        if (xdripFollowerEnabled) add(stringResource(R.string.xdrip_follower_title))
        if (patchedLibreEnabled) add(stringResource(R.string.settings_app_name_patched_libre))
        if (glucodataBroadcastEnabled) add(stringResource(R.string.settings_app_name_glucodata))
        if (healthConnectEnabled) {
            add(stringResource(R.string.health_connect_title))
        }
    }.joinToString(" · ").ifEmpty { stringResource(R.string.settings_phone_apps_none) }

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

    fun openSearchResult(entry: SettingsSearchEntry) {
        focusManager.clearFocus()
        when (val target = entry.target) {
            is SettingsSearchTarget.Route -> {
                SettingsSearchReveal.request(entry.anchor)
                navController.navigate(target.route)
            }
            is SettingsSearchTarget.Action -> when (target.action) {
                SettingsSearchAction.THEME -> showThemeDialog = true
                SettingsSearchAction.LANGUAGE -> showLanguageDialog = true
                SettingsSearchAction.UNIT -> showUnitDialog = true
                SettingsSearchAction.KEEP_DISPLAYED_VALUES -> showSealGraceDialog = true
                SettingsSearchAction.BACKUPS -> showScheduledBackupDialog = true
                SettingsSearchAction.EXPORT -> showExportDialog = true
                SettingsSearchAction.IMPORT -> importLauncher.launch(importMimeTypes)
                SettingsSearchAction.RESTART -> context.findActivity()?.fullRestart()
                SettingsSearchAction.CLEAR_HISTORY -> showClearHistoryDialog = true
                SettingsSearchAction.CLEAR_APP_DATA -> showClearDataDialog = true
                SettingsSearchAction.FACTORY_RESET -> showFactoryResetDialog = true
            }
        }
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

        item(key = "search") {
            SettingsSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // While there is a query the results replace the groups below.
        if (searching) {
            item(key = "search_results") {
                SettingsSearchResults(
                    query = searchQuery,
                    results = searchResults,
                    onOpen = { openSearchResult(it) }
                )
            }
            return@LazyColumn
        }

        // === GENERAL === (no label)
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
                    position = CardPosition.MIDDLE,
                    onClick = { showLanguageDialog = true }
                )
                SettingsItem(
                    title = stringResource(R.string.settings_unit_title),
                    subtitle = unit,
                    icon = Icons.Default.Straighten,
                    iconTint = generalColor,
                    position = CardPosition.MIDDLE,
                    onClick = { showUnitDialog = true }
                )
                AppUpdatesSettingsItem(
                    iconTint = generalColor,
                    position = CardPosition.BOTTOM,
                    onOpen = { navController.navigate("settings/app-updates") }
                )
            }
        }

        // === EVERYDAY === (no label)
        item(key = "everyday_group") {
            val everydayColor = MaterialTheme.colorScheme.primary
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                SettingsNavSwitchItem(
                    title = stringResource(R.string.glucose_alerts_title),
                    subtitle = stringResource(
                        if (alertsMasterEnabled) R.string.settings_alerts_desc else R.string.settings_alerts_off_desc
                    ),
                    checked = alertsMasterEnabled,
                    onCheckedChange = { viewModel.setAlertsMasterEnabled(it) },
                    onClick = { navController.navigate("settings/alerts") },
                    icon = Icons.Default.AddAlert,
                    iconTint = MaterialTheme.colorScheme.error,
                    position = CardPosition.TOP,
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.settings_calibration_title),
                    subtitle = stringResource(
                        R.string.settings_calibration_desc,
                        stringResource(if (isRawCalibrationMode) R.string.raw else R.string.auto)
                    ),
                    checked = calibrationEnabled,
                    onCheckedChange = {
                        CalibrationManager.setEnabledForMode(isRawCalibrationMode, it, sensorName)
                    },
                    onClick = { navController.navigate("settings/calibrations") },
                    icon = Icons.Default.WaterDrop,
                    iconTint = everydayColor,
                    position = CardPosition.MIDDLE
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.journal_title),
                    subtitle = stringResource(R.string.settings_journal_desc),
                    checked = journalEnabled,
                    onCheckedChange = { viewModel.setJournalEnabled(it) },
                    onClick = { navController.navigate("settings/journal") },
                    icon = Icons.Default.EditNote,
                    iconTint = everydayColor,
                    position = CardPosition.BOTTOM
                )
            }
        }

        // === DISPLAY ===
        item(key = "display_label") { SectionLabel(stringResource(R.string.settings_section_display)) }

        item(key = "display_group") {
            val displayColor = MaterialTheme.colorScheme.secondary
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.settings_dashboard_charts_title),
                    subtitle = stringResource(R.string.settings_dashboard_charts_desc),
                    icon = Icons.Default.Palette,
                    iconTint = displayColor,
                    position = CardPosition.TOP,
                    onClick = { navController.navigate("settings/display-colors") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.graph_smoothing_title),
                    subtitle = graphSmoothingLabel,
                    checked = chartSmoothingMinutes > 0,
                    onCheckedChange = { viewModel.setDataSmoothingEnabled(it) },
                    onClick = { navController.navigate("settings/data-smoothing") },
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    iconTint = displayColor,
                    position = CardPosition.MIDDLE
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.predictive_simulation_title),
                    subtitle = stringResource(R.string.settings_prediction_desc),
                    checked = predictiveSimulationEnabled,
                    onCheckedChange = { viewModel.setPredictiveSimulationEnabled(it) },
                    onClick = { navController.navigate("settings/predictive-simulation") },
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    iconTint = displayColor,
                    position = CardPosition.MIDDLE
                )
                SettingsItem(
                    title = stringResource(R.string.notification_settings_title),
                    subtitle = stringResource(R.string.notification_settings_subtitle),
                    icon = Icons.Default.ClearAll,
                    iconTint = displayColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/notification-display") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.floatglucose),
                    subtitle = stringResource(
                        if (floatingOverlayAllowed) R.string.settings_floating_desc
                        else R.string.settings_floating_permission_desc
                    ),
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
                    iconTint = displayColor,
                    position = CardPosition.MIDDLE
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.settings_lock_screen_title),
                    subtitle = stringResource(
                        if (aodServiceEnabled) R.string.settings_lock_screen_desc
                        else R.string.settings_lock_screen_off_desc
                    ),
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
                    icon = Icons.Default.ScreenLockPortrait,
                    iconTint = displayColor,
                    position = CardPosition.BOTTOM
                )
            }
        }

        // === SHARING === apps on this phone first, then the network
        item(key = "sharing_label") { SectionLabel(stringResource(R.string.settings_section_sharing)) }

        item(key = "sharing_group") {
            val sharingColor = MaterialTheme.colorScheme.tertiary
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.settings_phone_apps_title),
                    subtitle = phoneAppsLabel,
                    icon = Icons.Default.PhoneAndroid,
                    iconTint = sharingColor,
                    position = CardPosition.TOP,
                    onClick = { navController.navigate("settings/phone-apps") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.nightscout_settings_title),
                    subtitle = stringResource(R.string.nightscout_desc),
                    checked = nightscoutActive,
                    onCheckedChange = {
                        setNightscoutActive(context, it)
                        nightscoutActive = it
                    },
                    onClick = { navController.navigate("settings/nightscout") },
                    icon = Icons.Default.CloudUpload,
                    iconTint = sharingColor,
                    position = CardPosition.MIDDLE
                )
                if (showLibreView) {
                    SettingsItem(
                        title = stringResource(R.string.libreview_config),
                        subtitle = stringResource(R.string.libreview_desc),
                        icon = Icons.Default.Cloud,
                        iconTint = sharingColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/libreview") }
                    )
                }
                if (showDexcomShare) {
                    SettingsItem(
                        title = stringResource(R.string.dexcom_share_title),
                        subtitle = stringResource(R.string.dexcom_share_desc),
                        icon = Icons.Default.CloudUpload,
                        iconTint = sharingColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/dexcom-share") }
                    )
                }
                if (showMqAccount) {
                    SettingsItem(
                        title = stringResource(R.string.mq_account_title),
                        subtitle = stringResource(R.string.settings_mq_account_desc),
                        icon = Icons.Default.AccountCircle,
                        iconTint = sharingColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/mq-account") }
                    )
                }
                if (showOttaiSettings) {
                    SettingsItem(
                        title = stringResource(R.string.ottai_setup_title),
                        subtitle = stringResource(R.string.settings_ottai_desc),
                        icon = Icons.Default.Sensors,
                        iconTint = sharingColor,
                        position = CardPosition.MIDDLE,
                        onClick = { navController.navigate("settings/ottai") }
                    )
                }
                SettingsItem(
                    title = stringResource(R.string.mirror),
                    subtitle = stringResource(R.string.settings_mirror_desc),
                    icon = Icons.Default.Devices,
                    iconTint = sharingColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/mirror") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.webserver),
                    subtitle = stringResource(R.string.settings_webserver_desc),
                    checked = webServerActive,
                    onCheckedChange = {
                        Natives.setusexdripwebserver(it)
                        webServerActive = it
                    },
                    onClick = { navController.navigate("settings/webserver") },
                    icon = Icons.Default.Dns,
                    iconTint = sharingColor,
                    position = CardPosition.MIDDLE
                )
                SettingsItem(
                    title = stringResource(R.string.outbound_api_title),
                    subtitle = stringResource(R.string.outbound_api_desc),
                    icon = Icons.Default.Webhook,
                    iconTint = sharingColor,
                    position = CardPosition.BOTTOM,
                    onClick = { navController.navigate("settings/outbound-api") }
                )
            }
        }

        // === DEVICES ===
        item(key = "devices_label") { SectionLabel(stringResource(R.string.settings_section_devices)) }

        item(key = "devices_group") {
            val devicesColor = MaterialTheme.colorScheme.primary
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.watches),
                    subtitle = stringResource(R.string.settings_watch_desc),
                    icon = Icons.Default.Watch,
                    iconTint = devicesColor,
                    position = CardPosition.TOP,
                    onClick = { navController.navigate("settings/watch") }
                )
                SettingsNavSwitchItem(
                    title = stringResource(R.string.insulin_pens_title),
                    subtitle = stringResource(R.string.insulin_pens_desc),
                    checked = insulinPensEnabled,
                    onCheckedChange = { tk.glucodata.InsulinPenManager.setEnabled(it) },
                    onClick = { navController.navigate("settings/insulin-pens") },
                    icon = Icons.Default.Vaccines,
                    iconTint = devicesColor,
                    position = CardPosition.MIDDLE
                )
                SettingsItem(
                    title = stringResource(R.string.meterlist),
                    subtitle = stringResource(R.string.glucose_meters_desc),
                    icon = Icons.Default.Bloodtype,
                    iconTint = devicesColor,
                    position = CardPosition.BOTTOM,
                    onClick = { navController.navigate("settings/glucose-meters") }
                )
            }
        }

        // === BACKUP AND RESTORE ===
        item(key = "backup_label") { SectionLabel(stringResource(R.string.settings_section_backup)) }

        item(key = "backup_group") {
            val backupColor = MaterialTheme.colorScheme.secondary
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.scheduled_backup_title),
                    subtitle = scheduledBackupSummary(scheduledBackupConfig),
                    icon = Icons.Default.Backup,
                    iconTint = backupColor,
                    position = CardPosition.TOP,
                    onClick = { showScheduledBackupDialog = true }
                )
                SettingsItem(
                    title = stringResource(R.string.settings_export_title),
                    subtitle = stringResource(R.string.export_data_settings_desc),
                    icon = Icons.Default.Upload,
                    iconTint = backupColor,
                    position = CardPosition.MIDDLE,
                    onClick = { showExportDialog = true }
                )
                SettingsItem(
                    title = stringResource(R.string.settings_import_title),
                    subtitle = stringResource(R.string.import_data_settings_desc),
                    icon = Icons.Default.FolderOpen,
                    iconTint = backupColor,
                    position = CardPosition.BOTTOM,
                    onClick = { importLauncher.launch(importMimeTypes) }
                )
            }
        }

        // === ADVANCED ===
        item(key = "advanced_label") { SectionLabel(stringResource(R.string.advanced_title)) }

        item(key = "advanced_group") {
            val advancedColor = MaterialTheme.colorScheme.onSurfaceVariant
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.debug_logs),
                    subtitle = stringResource(R.string.debug_logs_desc),
                    icon = Icons.Default.BugReport,
                    iconTint = advancedColor,
                    position = CardPosition.TOP,
                    onClick = { navController.navigate("settings/debug") }
                )
                SettingsItem(
                    title = stringResource(R.string.cgm_readiness_title),
                    subtitle = stringResource(R.string.cgm_readiness_settings_desc),
                    icon = Icons.Default.Security,
                    iconTint = advancedColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/cgm-readiness") }
                )
                SettingsItem(
                    title = stringResource(R.string.settings_sensor_connection_title),
                    subtitle = stringResource(R.string.settings_sensor_connection_desc),
                    icon = Icons.Default.Bluetooth,
                    iconTint = advancedColor,
                    position = CardPosition.MIDDLE,
                    onClick = { navController.navigate("settings/sensor-connection") }
                )
                // A reading's displayed value is a record, not a derivation —
                // see tk.glucodata.data.ReadingDisplay. Its subtitle carries the
                // current behaviour so the common question is answered in place;
                // the body picks how long a value stays revisable, the switch
                // turns keeping off.
                SettingsNavSwitchItem(
                    title = stringResource(R.string.freeze_displayed_values),
                    subtitle = if (freezeDisplayedValues) {
                        stringResource(
                            R.string.freeze_displayed_values_after,
                            pluralStringResource(R.plurals.duration_hours, sealGraceHours, sealGraceHours)
                        )
                    } else {
                        stringResource(R.string.freeze_displayed_values_off)
                    },
                    checked = freezeDisplayedValues,
                    onCheckedChange = { CalibrationManager.setFreezeDisplayedValues(it) },
                    onClick = { showSealGraceDialog = true },
                    icon = Icons.Default.Lock,
                    iconTint = advancedColor,
                    position = CardPosition.BOTTOM
                )
            }
        }

        // === DANGER ===
        item(key = "danger_label") { SectionLabel(stringResource(R.string.danger_zone), isError = true) }

        item(key = "danger_group") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    if (showSealGraceDialog) {
        SealGraceDialog(
            currentHours = sealGraceHours,
            onSave = { hours ->
                CalibrationManager.setDisplaySealGraceHours(hours)
                showSealGraceDialog = false
            },
            onDismiss = { showSealGraceDialog = false }
        )
    }
    if (showUnitDialog) UnitPickerDialog(isMmol, { 
        viewModel.setUnit(it)
        showUnitDialog = false
        context.findActivity()?.hardRestart() 
    }, { showUnitDialog = false })
    if (showThemeDialog) ThemePickerDialog(themeMode, { onThemeChanged(it); showThemeDialog = false }, { showThemeDialog = false })
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

/**
 * How long a displayed value stays revisable — see
 * `tk.glucodata.data.ReadingDisplay.isSealedAt`. The choices are not evenly
 * spaced in hours, so the slider moves over their index: one stop per choice.
 * Nothing is applied until Save.
 */
@Composable
private fun SealGraceDialog(currentHours: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    val choices = tk.glucodata.data.calibration.CalibrationManager.DISPLAY_SEAL_GRACE_HOURS_CHOICES
    var index by remember { mutableFloatStateOf(choices.indexOf(currentHours).coerceAtLeast(0).toFloat()) }
    val selectedHours = choices[index.roundToInt().coerceIn(0, choices.lastIndex)]
    val selectedDuration = pluralStringResource(R.plurals.duration_hours, selectedHours, selectedHours)
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.freeze_displayed_values),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Text(
                    text = stringResource(R.string.freeze_displayed_values_grace_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(16.dp))
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = stringResource(R.string.freeze_displayed_values_after, selectedDuration),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = index,
                        onValueChange = { index = it },
                        valueRange = 0f..choices.lastIndex.toFloat(),
                        steps = (choices.size - 2).coerceAtLeast(0),
                        modifier = Modifier.semantics { stateDescription = selectedDuration }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = pluralStringResource(R.plurals.duration_hours, choices.first(), choices.first()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = pluralStringResource(R.plurals.duration_hours, choices.last(), choices.last()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = { onSave(selectedHours) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

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
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) }
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
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
internal fun SensorHandoverActionPickerDialog(
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
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) }
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
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) }
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
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) }
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
                colors = if (isDestructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) { Text(if (isDestructive) stringResource(R.string.delete) else stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) } }
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

/** True when a currently active sensor is a Dexcom. */
private fun hasActiveDexcomSensor(): Boolean = runCatching {
    Natives.activeSensors()?.filterNotNull().orEmpty().any { sensorId ->
        SensorSourceResolver.resolveSensorKind(sensorId, SensorSourceResolver.SENSOR_KIND_UNKNOWN) ==
            SensorSourceResolver.SENSOR_KIND_DEXCOM
    }
}.getOrDefault(false)

/** What Import offers in the file picker: JugglucoNG exports and history CSV/TSV files. */
private val importMimeTypes = arrayOf(
    "application/json",
    "application/gzip",
    "application/zstd",
    "text/*",
    "text/csv",
    "text/tab-separated-values",
    "*/*"
)
