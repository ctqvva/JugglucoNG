@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.Keep
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.Slider
import tk.glucodata.ui.components.StyledSwitch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.text.Layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.RectangleShape
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.AdaptiveLayoutDensity
import tk.glucodata.ui.util.findActivity
import tk.glucodata.ui.util.hardRestart
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.togetherWith

import androidx.compose.ui.draw.alpha

import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally

import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import kotlinx.coroutines.launch
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.DisplayDataState
import tk.glucodata.Libre3NfcSettings
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorBluetooth
import tk.glucodata.QRmake
import tk.glucodata.R
import tk.glucodata.MainActivity
import tk.glucodata.UiRefreshBus
import android.widget.Toast
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction
import tk.glucodata.ui.journal.JournalDoseProfile
import tk.glucodata.ui.journal.JournalEntrySheet
import tk.glucodata.ui.journal.JournalInlineChip
import tk.glucodata.ui.journal.JournalSettingsScreen
import tk.glucodata.ui.journal.buildActiveInsulinSummary
import tk.glucodata.ui.journal.buildJournalChartMarkers
import tk.glucodata.ui.journal.journalTypeColor
import tk.glucodata.ui.journal.journalTypeSelectedContainerColor
import tk.glucodata.ui.journal.journalTypeSubtleContainerColor
import tk.glucodata.ui.viewmodel.DashboardViewModel
import tk.glucodata.ui.theme.displayLargeExpressive
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.res.stringResource
import java.util.Locale
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.LegendToggle
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import kotlin.math.abs
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.animation.Crossfade
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange

private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
}

@Composable
fun SensorScreen(
    onNavigateToMqAccount: () -> Unit = {},
    onNavigateToReadiness: () -> Unit = {},
    viewModel: tk.glucodata.ui.viewmodel.SensorViewModel = viewModel(),
) {
    val context = LocalContext.current
    val sensors by viewModel.sensors.collectAsState()
    val adaptiveMetrics = rememberAdaptiveWindowMetrics()
    val compactLayout = adaptiveMetrics.isCompact
    val panelPadding = 16.dp
    val titleInset = 16.dp
    val panelTopGap = if (compactLayout) 10.dp else 16.dp
    val panelBottomPadding = if (compactLayout) 88.dp else 100.dp
    val titleStyle = if (compactLayout) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall
    val titleBottomPadding = if (compactLayout) 16.dp else 24.dp
    val fabPadding = 20.dp
    
    // Start/stop real-time polling based on screen visibility
    DisposableEffect(Unit) {
        viewModel.startPolling() // Start 2-second refresh when screen visible
        onDispose {
            viewModel.stopPolling() // Stop when leaving screen
        }
    }
    
    // State for sensor type picker and wizards
    var showSensorPicker by remember { mutableStateOf(false) }
    var showSibionicsWizard by remember { mutableStateOf(false) }
    var showLibreWizard by remember { mutableStateOf(false) }
    var showDexcomWizard by remember { mutableStateOf(false) }
    var showAccuChekWizard by remember { mutableStateOf(false) }
    var showCareSensAirWizard by remember { mutableStateOf(false) }
    var showAiDexWizard by remember { mutableStateOf(false) }
    var showICanHealthWizard by remember { mutableStateOf(false) }
    var showMQWizard by remember { mutableStateOf(false) }
    var showAnytimeWizard by remember { mutableStateOf(false) }
    var showOttaiWizard by remember { mutableStateOf(false) }

    // Sensor Type Picker Bottom Sheet
    if (showSensorPicker) {
        tk.glucodata.ui.components.SensorTypePicker(
            onDismiss = { showSensorPicker = false },
            onSensorSelected = { type ->
                showSensorPicker = false
                when (type) {
                    tk.glucodata.ui.components.SensorType.SIBIONICS -> showSibionicsWizard = true
                    tk.glucodata.ui.components.SensorType.LIBRE -> showLibreWizard = true
                    tk.glucodata.ui.components.SensorType.DEXCOM -> showDexcomWizard = true
                    tk.glucodata.ui.components.SensorType.ACCUCHEK -> showAccuChekWizard = true
                    tk.glucodata.ui.components.SensorType.CARESENS_AIR -> showCareSensAirWizard = true
                    tk.glucodata.ui.components.SensorType.AIDEX -> showAiDexWizard = true
                    tk.glucodata.ui.components.SensorType.ICANHEALTH -> showICanHealthWizard = true
                    tk.glucodata.ui.components.SensorType.MQ -> showMQWizard = true
                    tk.glucodata.ui.components.SensorType.ANYTIME -> showAnytimeWizard = true
                    tk.glucodata.ui.components.SensorType.OTTAI -> showOttaiWizard = true
                }
            }
        )
    }

    // Sibionics Setup Wizard (Full Screen)
    if (showSibionicsWizard) {
        tk.glucodata.ui.setup.SibionicsSetupWizard(
            onDismiss = { showSibionicsWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showSibionicsWizard = false
                viewModel.refreshSensors()
            }
        )
        return // Exit early to show wizard full screen
    }
    
    // Libre Setup Wizard
    if (showLibreWizard) {
        tk.glucodata.ui.setup.LibreSetupWizard(
            onDismiss = { showLibreWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanNfc = {
                showLibreWizard = false
                tk.glucodata.MainActivity.launchLibreNfcScan()
            }
        )
        return
    }
    
    // Dexcom Setup Wizard
    if (showDexcomWizard) {
        tk.glucodata.ui.setup.DexcomSetupWizard(
            onDismiss = {
                showDexcomWizard = false
            },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanResult = { raw ->
                tk.glucodata.MainActivity.handleInlineQrScan(raw, tk.glucodata.MainActivity.REQUEST_BARCODE)
                showDexcomWizard = false
            }
        )
        return
    }

    // Accu-Chek Setup Wizard
    if (showAccuChekWizard) {
        tk.glucodata.ui.setup.AccuChekSetupWizard(
            onDismiss = {
                showAccuChekWizard = false
            },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanResult = { raw ->
                tk.glucodata.MainActivity.handleInlineQrScan(raw, tk.glucodata.MainActivity.REQUEST_BARCODE)
                showAccuChekWizard = false
            }
        )
        return
    }

    // CareSens Air Setup Wizard
    if (showCareSensAirWizard) {
        tk.glucodata.ui.setup.CareSensAirSetupWizard(
            onDismiss = {
                showCareSensAirWizard = false
            },
            onNavigateToReadiness = onNavigateToReadiness,
            onScanResult = { raw ->
                tk.glucodata.MainActivity.handleInlineQrScan(raw, tk.glucodata.MainActivity.REQUEST_BARCODE)
                showCareSensAirWizard = false
            }
        )
        return
    }
    
    // AiDex Setup Wizard (Edit 48e: was missing — selecting AiDex from SensorTypePicker did nothing)
    if (showAiDexWizard) {
        tk.glucodata.ui.setup.AiDexSetupWizard(
            onDismiss = { showAiDexWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showAiDexWizard = false
                viewModel.refreshSensors()
            }
        )
        return
    }

    if (showICanHealthWizard) {
        tk.glucodata.ui.setup.ICanHealthSetupWizard(
            onDismiss = { showICanHealthWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showICanHealthWizard = false
                viewModel.refreshSensors()
            }
        )
        return
    }

    // MQ / Glutec Setup Wizard
    if (showMQWizard) {
        tk.glucodata.ui.setup.MQSetupWizard(
            onDismiss = { showMQWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showMQWizard = false
                viewModel.refreshSensors()
            },
        )
        return
    }

    // Anytime / Yuwell CT3 Setup Wizard
    if (showAnytimeWizard) {
        tk.glucodata.ui.setup.AnytimeSetupWizard(
            onDismiss = { showAnytimeWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showAnytimeWizard = false
                viewModel.refreshSensors()
            },
        )
        return
    }

    // Ottai CGM Setup Wizard
    if (showOttaiWizard) {
        tk.glucodata.ui.setup.OttaiSetupWizard(
            onDismiss = { showOttaiWizard = false },
            onNavigateToReadiness = onNavigateToReadiness,
            onComplete = {
                showOttaiWizard = false
                viewModel.refreshSensors()
            },
        )
        return
    }

    // Use Box instead of Scaffold to avoid double padding from parent nav
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (sensors.isEmpty()) {
            // Show sensor selection cards for empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = panelPadding)
                    .padding(bottom = panelBottomPadding)
            ) {
                Spacer(modifier = Modifier.height(panelTopGap))
                Text(
                    text = stringResource(R.string.sensors_title),
                    style = titleStyle,
                    modifier = Modifier.padding(start = titleInset, end = titleInset)
                )
                Spacer(modifier = Modifier.height(panelTopGap))
                SensorsCgmReadinessBanner(onOpenReadiness = onNavigateToReadiness)
                Spacer(modifier = Modifier.height(panelPadding))
                tk.glucodata.ui.components.SensorsEmptyState(
                    onSensorSelected = { type ->
                        when (type) {
                            tk.glucodata.ui.components.SensorType.SIBIONICS -> showSibionicsWizard = true
                            tk.glucodata.ui.components.SensorType.LIBRE -> showLibreWizard = true
                            tk.glucodata.ui.components.SensorType.DEXCOM -> showDexcomWizard = true
                            tk.glucodata.ui.components.SensorType.ACCUCHEK -> showAccuChekWizard = true
                            tk.glucodata.ui.components.SensorType.CARESENS_AIR -> showCareSensAirWizard = true
                            tk.glucodata.ui.components.SensorType.AIDEX -> showAiDexWizard = true
                            tk.glucodata.ui.components.SensorType.ICANHEALTH -> showICanHealthWizard = true
                            tk.glucodata.ui.components.SensorType.MQ -> showMQWizard = true
                            tk.glucodata.ui.components.SensorType.ANYTIME -> showAnytimeWizard = true
                            tk.glucodata.ui.components.SensorType.OTTAI -> showOttaiWizard = true
                        }
                    }
                )
                Spacer(modifier = Modifier.height(panelPadding))
            }
        } else {
            // LazyColumn with header that scrolls
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(
                    start = panelPadding,
                    end = panelPadding,
                    top = panelPadding,
                    bottom = panelBottomPadding
                )
            ) {
                // Scrollable header
                item {
                    Text(
                        text = stringResource(R.string.sensors_title),
                    style = titleStyle,
                    modifier = Modifier.padding(start = titleInset, bottom = titleBottomPadding)
                    )
                }
                item {
                    SensorsCgmReadinessBanner(
                        modifier = Modifier.padding(bottom = panelPadding),
                        onOpenReadiness = onNavigateToReadiness
                    )
                }
                items(sensors, key = { it.serial }) { sensor ->
                    SensorCard(
                        sensor,
                        viewModel,
                        sensorCount = sensors.size,
                        onNavigateToMqAccount = onNavigateToMqAccount,
                    )
                }
            }
        }
        
        // FAB overlay - only show when sensors exist
        if (sensors.isNotEmpty()) {
            FloatingActionButton(
                onClick = { showSensorPicker = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(fabPadding)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
