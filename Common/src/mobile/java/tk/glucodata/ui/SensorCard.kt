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

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall, // Smaller label
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium, // Larger value for scannability
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun SensorCard(
    sensor: tk.glucodata.ui.viewmodel.SensorInfo,
    viewModel: tk.glucodata.ui.viewmodel.SensorViewModel,
    sensorCount: Int = 1,
    onNavigateToMqAccount: () -> Unit = {},
) {
    val context = LocalContext.current
    var showTerminateDialog by remember { mutableStateOf(false) }
    var showForgetDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    // Edit 79: showClearDialog removed — restart algorithm now in Sibionics Calibration bottom sheet
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showUnifiedResetDialog by remember { mutableStateOf(false) }
    var keepAutoCalChecked by remember { mutableStateOf(false) }
    var showReconnectDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    var wipeDataChecked by remember { mutableStateOf(false) }
    var keepDataChecked by remember { mutableStateOf(false) }

    // Sibionics Calibration Bottom Sheet
    var showSibionicsCalSheet by remember { mutableStateOf(false) }

    // AiDex Maintenance Dialogs
    var showAiDexClearDialog by remember { mutableStateOf(false) }
    var showSensorCalibrateDialog by remember { mutableStateOf(false) }
    var showAiDexUnpairDialog by remember { mutableStateOf(false) }
    var showMqRestoreSheet by remember { mutableStateOf(false) }
    var showMqCalibrationSheet by remember { mutableStateOf(false) }
    var calibrationInputText by remember { mutableStateOf("") }
    var mqCalibrationInputText by remember { mutableStateOf("") }
    var mqQrInput by remember(context, sensor.serial) {
        mutableStateOf(tk.glucodata.drivers.mq.MQRegistry.loadQrContent(context, sensor.serial).orEmpty())
    }
    var aiDexBiasChecked by remember(sensor.serial, sensor.resetCompensationActive) { mutableStateOf(sensor.resetCompensationActive) }
    // Edit 78: resetBiasChecked removed — bias toggle now lives in the bottom sheet as an independent switch

    val scope = rememberCoroutineScope() // Fix: Add missing scope
    // Edit 74: Removed LocalContext.current that was added in Edit 73 for Toasts (rejected by user).
    // Status feedback now goes through getDetailedBleStatus() via vendorActionStatus field.

    // Edit 68b: AiDex disconnect button now uses terminateSensor (destructive) instead of
    // disconnectSensor (soft). The old soft-disconnect left zombie "is finished" entries —
    // bond/keys preserved, prefs not cleaned, sensor reappeared. terminateSensor calls
    // forgetVendor() + removeAiDexFromPrefs() + finishSensor() + sensorEnded() = full cleanup.
    if (showTerminateDialog) {
        if (sensor.isAidex) {
            // AiDex: full teardown — removes bond, keys, prefs, and sensor entry
            AlertDialog(
                onDismissRequest = { showTerminateDialog = false },
                title = { Text(stringResource(R.string.disconnect_sensor_title)) },
                text = { Text(stringResource(R.string.disconnect_sensor_aidex_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.terminateSensor(sensor.serial)
                        showTerminateDialog = false
                    }) { Text(stringResource(R.string.disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = { showTerminateDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        } else {
            // Legacy sensors: destructive terminate
            AlertDialog(
                onDismissRequest = {
                    showTerminateDialog = false
                    keepDataChecked = false
                },
                title = { Text(stringResource(R.string.disconnect_sensor_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.disconnect_sensor_desc))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = keepDataChecked,
                                onCheckedChange = { keepDataChecked = it }
                            )
                            Text(stringResource(R.string.keep_data))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.terminateSensor(sensor.serial, !keepDataChecked)
                        showTerminateDialog = false
                        keepDataChecked = false
                    }) { Text(stringResource(R.string.disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showTerminateDialog = false
                        keepDataChecked = false
                    }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }

    if (showReconnectDialog) {
        AlertDialog(
            onDismissRequest = {
                showReconnectDialog = false
                wipeDataChecked = false
            },
            title = { Text(stringResource(R.string.reconnect_sensor_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.reconnect_sensor_desc))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = wipeDataChecked,
                            onCheckedChange = { wipeDataChecked = it }
                        )
                        Text(stringResource(R.string.wipe_data))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reconnectSensor(sensor.serial, wipeDataChecked)
                    showReconnectDialog = false
                    wipeDataChecked = false
                }) { Text(stringResource(R.string.reconnect)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReconnectDialog = false
                    wipeDataChecked = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Edit 62d: Forget/Disconnect dialog — for AiDex, this is the destructive "Disconnect" path
    // that wipes vendor keys, disconnects, and removes from list.
    if (showForgetDialog) {
        if (sensor.isAidex) {
            AlertDialog(
                onDismissRequest = { showForgetDialog = false },
                title = { Text(stringResource(R.string.disconnect_sensor_title)) },
                text = { Text(stringResource(R.string.remove_sensor_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.forgetSensor(sensor.serial)
                        showForgetDialog = false
                    }) { Text(stringResource(R.string.disconnect)) }
                },
                dismissButton = {
                    TextButton(onClick = { showForgetDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showForgetDialog = false },
                title = { Text(stringResource(R.string.forget_sensor_title)) },
                text = { Text(stringResource(R.string.forget_sensor_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.forgetSensor(sensor.serial)
                        showForgetDialog = false
                    }) { Text(stringResource(R.string.forget)) }
                },
                dismissButton = {
                    TextButton(onClick = { showForgetDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_sensor_title)) },
            text = {
                Text(
                    stringResource(
                        if (sensor.isSibionics2) R.string.reset_sensor_desc else R.string.unified_reset_desc
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSensor(sensor.serial)
                    showResetDialog = false
                }) { Text(stringResource(R.string.reset_sensor)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Edit 79: showClearDialog AlertDialog removed — restart algorithm now lives
    // inside the Sibionics Calibration bottom sheet as a destructive action card.

    if (showUnifiedResetDialog) {
        AlertDialog(
            onDismissRequest = { 
                showUnifiedResetDialog = false
                keepAutoCalChecked = false 
            },
            title = { Text(stringResource(R.string.reset_sensor_title)) },
            text = { 
                Column {
                    Text(stringResource(R.string.unified_reset_desc))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { keepAutoCalChecked = !keepAutoCalChecked }
                    ) {
                        Checkbox(
                            checked = keepAutoCalChecked,
                            onCheckedChange = { keepAutoCalChecked = it }
                        )
                        Text(stringResource(R.string.keep_auto_calibration))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (keepAutoCalChecked) {
                        viewModel.resetSensor(sensor.serial)  // Hardware reset only
                    } else {
                        viewModel.clearAll(sensor.serial)     // Full reset
                    }
                    showUnifiedResetDialog = false
                    keepAutoCalChecked = false
                }) { Text(stringResource(R.string.reset_sensor)) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showUnifiedResetDialog = false 
                    keepAutoCalChecked = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text(stringResource(R.string.wipe_sensor_data_title)) },
            text = { Text(stringResource(R.string.wipe_sensor_data_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.wipeSensorData(sensor.serial)
                        showWipeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.wipe_data))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Edit 78: AiDex Reset & Bias Correction bottom sheet — replaces old AlertDialog.
    // Matches the destructive-action-sheet pattern from DashboardClearOptionsBottomSheet.
    if (showAiDexClearDialog) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showAiDexClearDialog = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    stringResource(R.string.reset_correction_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reset_correction_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                // --- Bias Correction toggle ---
                Surface(
                    onClick = {
                        aiDexBiasChecked = !aiDexBiasChecked
                        if (aiDexBiasChecked) {
                            viewModel.enableAiDexBiasCompensation(sensor.serial)
                        } else {
                            viewModel.disableAiDexBiasCompensation(sensor.serial)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.bias_correction),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (sensor.resetCompensationActive && sensor.resetCompensationStatus.isNotEmpty())
                                    sensor.resetCompensationStatus
                                else if (aiDexBiasChecked && sensor.resetCompensationStatus.isNotEmpty())
                                    sensor.resetCompensationStatus
                                else
                                    stringResource(R.string.bias_correction_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (aiDexBiasChecked)
                                    MaterialTheme.colorScheme.tertiary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        StyledSwitch(
                            checked = aiDexBiasChecked,
                            onCheckedChange = {
                                aiDexBiasChecked = it
                                if (it) {
                                    viewModel.enableAiDexBiasCompensation(sensor.serial)
                                } else {
                                    viewModel.disableAiDexBiasCompensation(sensor.serial)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // --- Hardware Reset action ---
                Surface(
                    onClick = {
                        viewModel.resetAiDexSensor(sensor.serial, enableBiasCompensation = aiDexBiasChecked)
                        showAiDexClearDialog = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.hardware_reset),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                stringResource(R.string.hardware_reset_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { showAiDexClearDialog = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }

    // Auto-Calibration Settings bottom sheet — redesigned with master switch
    // Master switch guards advanced controls (slider + daily restart).
    // Restart button available in both modes (native restart in OFF, windowed in ON).
    if (showSibionicsCalSheet && sensor.isSibionics && sensor.viewMode != 1) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showSibionicsCalSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    stringResource(R.string.auto_calibration_mode),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(16.dp))

                // --- Master switch: Advanced auto-calibration (prominent card) ---
                var advancedEnabled by remember(sensor.customCalEnabled) { mutableStateOf(sensor.customCalEnabled) }
                // Track whether settings were changed but not yet applied (dirty state)
                var settingsDirty by remember { mutableStateOf(false) }
                val windowLabels = remember { listOf("12H", "1D", "2D", "3D", "5D", "7D", "10D", "14D", "18D", "MAX") }
                val maxSliderPos = windowLabels.lastIndex
                var sliderPos by remember(sensor.customCalEnabled, sensor.customCalIndex) {
                    mutableStateOf(
                        if (sensor.customCalEnabled) {
                            sensor.customCalIndex.coerceIn(0, maxSliderPos).toFloat()
                        } else {
                            maxSliderPos.toFloat()
                        }
                    )
                }
                var customAutoReset by remember(sensor.customCalEnabled, sensor.customCalAutoReset) {
                    mutableStateOf(if (sensor.customCalEnabled) sensor.customCalAutoReset else true)
                }
                fun applyAdvancedToggle(targetEnabled: Boolean) {
                    if (advancedEnabled == targetEnabled) return
                    advancedEnabled = targetEnabled
                    if (!targetEnabled) {
                        viewModel.disableCustomCalAndReplay(sensor.serial)
                        settingsDirty = false
                    } else {
                        val defaultPos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                        viewModel.updateCustomCalibration(sensor.serial, true, defaultPos, customAutoReset)
                        settingsDirty = true
                    }
                }
                fun applyDailyRestartToggle(targetEnabled: Boolean) {
                    if (customAutoReset == targetEnabled) return
                    customAutoReset = targetEnabled
                    val pos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                    viewModel.updateCustomCalibration(sensor.serial, true, pos, targetEnabled)
                    settingsDirty = true
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (advancedEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = if (advancedEnabled)
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                applyAdvancedToggle(!advancedEnabled)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = if (advancedEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Advanced auto-calibration",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (advancedEnabled) "Custom calibration window active"
                                else "Standard Juggluco algorithm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        StyledSwitch(
                            checked = advancedEnabled,
                            onCheckedChange = { checked -> applyAdvancedToggle(checked) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Advanced controls (slider + daily restart) — visible when master switch ON ---
                AnimatedVisibility(visible = advancedEnabled) {
                    Column {
                        val currentPos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                        val currentLabel = windowLabels[currentPos]

                        // Current mode label
                        Text(
                            currentLabel,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (currentLabel == "MAX") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (currentLabel == "MAX") "Use all available sensor data"
                            else "$currentLabel calibration window",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        Slider(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            value = sliderPos,
                            onValueChange = { sliderPos = it },
                            valueRange = 0f..maxSliderPos.toFloat(),
                            steps = maxSliderPos - 1,
                            onValueChangeFinished = {
                                val pos = sliderPos.toInt().coerceIn(0, maxSliderPos)
                                viewModel.updateCustomCalibration(sensor.serial, true, pos, customAutoReset)
                                settingsDirty = true
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- Restart daily toggle (full-row touch target) ---
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    applyDailyRestartToggle(!customAutoReset)
                                }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Restart daily",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "Automatically restart algorithm once per day",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            StyledSwitch(
                                checked = customAutoReset,
                                onCheckedChange = { checked -> applyDailyRestartToggle(checked) }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // --- Restart algorithm button (always visible) ---
                // Visual: RED when dirty (unapplied changes), subtle otherwise
                val restartButtonColor = if (settingsDirty)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                val restartIconColor = MaterialTheme.colorScheme.error
                val restartTextColor = MaterialTheme.colorScheme.error

                Surface(
                    onClick = {
                        if (advancedEnabled) {
                            viewModel.localReplay(sensor.serial)
                        } else {
                            viewModel.restartSibionicsNativeFresh(sensor.serial)
                        }
                        settingsDirty = false
                        showSibionicsCalSheet = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = restartButtonColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = restartIconColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (settingsDirty) stringResource(R.string.restart_algorithm_to_apply)
                                else stringResource(R.string.restart_algorithm),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = restartTextColor
                            )
                            Text(
                                if (settingsDirty) stringResource(R.string.settings_changed_press_to_apply)
                                else if (advancedEnabled) stringResource(R.string.restart_with_current_window)
                                else stringResource(R.string.restart_with_standard_algorithm),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { showSibionicsCalSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }

    if (showSensorCalibrateDialog) {
        AlertDialog(
            onDismissRequest = {
                showSensorCalibrateDialog = false
                calibrationInputText = ""
            },
            title = { Text(stringResource(R.string.calibrate_sensor_title)) },
            text = {
                val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                val unitLabel = if (isMmol) "mmol/L" else "mg/dL"
                Column {
                    Text(stringResource(R.string.calibrate_sensor_desc, unitLabel))
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = calibrationInputText,
                        onValueChange = { newVal ->
                            calibrationInputText = if (isMmol) {
                                // Allow digits and one decimal point
                                newVal.filter { c -> c.isDigit() || c == '.' }
                                    .let { s ->
                                        val dotIndex = s.indexOf('.')
                                        if (dotIndex >= 0) s.substring(0, dotIndex + 1) + s.substring(dotIndex + 1).replace(".", "")
                                        else s
                                    }
                            } else {
                                newVal.filter { c -> c.isDigit() }
                            }
                        },
                        label = { Text(stringResource(R.string.glucose_with_unit, unitLabel)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = if (isMmol)
                                androidx.compose.ui.text.input.KeyboardType.Decimal
                            else
                                androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                val inputValue = calibrationInputText.toFloatOrNull()
                val glucoseMgDl = inputValue?.let {
                    (if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it) else it).roundToInt()
                }
                val isValid = glucoseMgDl != null && glucoseMgDl in 30..500
                TextButton(
                    onClick = {
                        if (glucoseMgDl != null && isValid) {
                            if (sensor.isAidex) {
                                viewModel.calibrateAiDexSensor(sensor.serial, glucoseMgDl)
                            } else {
                                viewModel.calibrateManagedSensor(sensor.serial, glucoseMgDl)
                            }
                            showSensorCalibrateDialog = false
                            calibrationInputText = ""
                        }
                    },
                    enabled = isValid
                ) { Text(stringResource(R.string.calibrate_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSensorCalibrateDialog = false
                    calibrationInputText = ""
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showMqRestoreSheet) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showMqRestoreSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            val savedAccountState = remember(showMqRestoreSheet) {
                tk.glucodata.drivers.mq.MQRegistry.loadAccountState(context)
            }
            val accountSubtitle = when {
                savedAccountState.hasToken -> stringResource(R.string.mq_account_status_signed_in)
                savedAccountState.hasCredentials -> stringResource(R.string.mq_account_status_saved)
                else -> stringResource(R.string.mq_account_linked_desc)
            }
            val canAttemptVendorRestore = mqQrInput.isNotBlank() || savedAccountState.hasCredentials || savedAccountState.hasToken
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.mq_bootstrap_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mq_bootstrap_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = mqQrInput,
                    onValueChange = { mqQrInput = it.trim().uppercase(java.util.Locale.US) },
                    label = { Text(stringResource(R.string.scan_sensor_qr)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingsItem(
                    title = stringResource(R.string.mq_account_title),
                    subtitle = accountSubtitle,
                    showArrow = true,
                    icon = Icons.Default.Cloud,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.SINGLE,
                    onClick = {
                        showMqRestoreSheet = false
                        onNavigateToMqAccount()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.fetchMqBootstrap(
                            sensor.serial,
                            mqQrInput,
                        )
                    },
                    enabled = canAttemptVendorRestore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mq_fetch_calibration_action))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showMqRestoreSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

    if (showMqCalibrationSheet) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = {
                showMqCalibrationSheet = false
                mqCalibrationInputText = ""
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
            val unitLabel = if (isMmol) "mmol/L" else "mg/dL"
            val inputValue = mqCalibrationInputText.toFloatOrNull()
            val glucoseMgDl = inputValue?.let {
                (if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.mmolToMg(it) else it).toInt()
            }
            val canCalibrate = sensor.isVendorConnected && glucoseMgDl != null && glucoseMgDl in 30..500
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.mq_manual_calibration_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mq_manual_calibration_desc, unitLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = mqCalibrationInputText,
                    onValueChange = { newVal ->
                        mqCalibrationInputText = if (isMmol) {
                            newVal.filter { c -> c.isDigit() || c == '.' }
                                .let { s ->
                                    val dotIndex = s.indexOf('.')
                                    if (dotIndex >= 0) {
                                        s.substring(0, dotIndex + 1) + s.substring(dotIndex + 1).replace(".", "")
                                    } else {
                                        s
                                    }
                                }
                        } else {
                            newVal.filter { c -> c.isDigit() }
                        }
                    },
                    label = { Text(stringResource(R.string.glucose_with_unit, unitLabel)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (isMmol) {
                            androidx.compose.ui.text.input.KeyboardType.Decimal
                        } else {
                            androidx.compose.ui.text.input.KeyboardType.Number
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (glucoseMgDl != null && canCalibrate) {
                            viewModel.calibrateManagedSensor(sensor.serial, glucoseMgDl)
                            showMqCalibrationSheet = false
                            mqCalibrationInputText = ""
                        }
                    },
                    enabled = canCalibrate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibrate_action))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        showMqCalibrationSheet = false
                        mqCalibrationInputText = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

    if (showAiDexUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showAiDexUnpairDialog = false },
            title = { Text(stringResource(R.string.unpair_sensor_title)) },
            text = { Text(stringResource(R.string.unpair_sensor_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.unpairAiDexSensor(sensor.serial)
                        showAiDexUnpairDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.unpair)) }
            },
            dismissButton = {
                TextButton(onClick = { showAiDexUnpairDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val isStreaming = sensor.streaming
    // Visual Feedback: Darken card when disconnected/paused
    val containerColor = if (isStreaming) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentAlpha = if (isStreaming) 1f else 0.9f


    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 4.dp), 
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(28.dp) 
    ) {
        // --- Dynamic Background Logic ---
        val now = System.currentTimeMillis()
        val start = sensor.startMs
        val end = when {
            sensor.isAidex && sensor.officialEndMs > 0 -> sensor.officialEndMs
            !sensor.isAidex && sensor.expectedEndMs > 0 -> sensor.expectedEndMs
            sensor.officialEndMs > 0 -> sensor.officialEndMs
            sensor.isAidex -> start + (15L * 24 * 3600 * 1000)
            else -> start + (14L * 24 * 3600 * 1000)
        }
        
        val totalDuration = (end - start).coerceAtLeast(1) // Avoid div/0
        val usedDuration = (now - start).coerceAtLeast(0)
        val progress = (usedDuration.toFloat() / totalDuration).coerceIn(0f, 1f)
        
        // Color Shift: Safe -> Warning (80%) -> Critical (95%)
        val fillColor = when {
            progress > 0.95f -> MaterialTheme.colorScheme.error
            progress > 0.80f -> MaterialTheme.colorScheme.tertiary 
            else -> MaterialTheme.colorScheme.primary
        }
        val fillAlpha = 0.12f // Light tint

        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Dynamic Fill Layer
            // FIX: Only show fill if start date is valid (> Jan 1 2020)
            // Prevents "100% Red Fill" bug when startMs is 0 or invalid (1970).
            if (sensor.startMs > 1577836800000L) {
                 Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(fillColor.copy(alpha = fillAlpha))
                )
            }

            // 2. Content Layer with Color Indicator
            Row(modifier = Modifier.fillMaxWidth().padding(0.dp).alpha(contentAlpha)) {
                // Color indicator bar - shows sensor's assigned color
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            sensor.color.copy(alpha = if (sensor.isActive) 1f else 0.4f),
                            RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
                        )
                )

                Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                    val statusText = if (isStreaming) stringResource(R.string.enabled_status) else stringResource(R.string.disabled_status)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val serialTextStyle = when {
                                else -> MaterialTheme.typography.titleLarge
                            }
                            val enabledTextStyle = when {
                                else -> MaterialTheme.typography.titleMedium
                            }
                            // Title with optional "Active" badge
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = sensor.serial,
                                    style = serialTextStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                )
                                // Toggle Main Sensor Badge
                                Spacer(modifier = Modifier.width(8.dp))
                                val isMain = sensor.isActive

                                val badgeColor = if(isMain) sensor.color else sensor.color.copy(alpha=0.6f)
                                val badgeBg = if(isMain) sensor.color.copy(alpha = 0.15f) else Color.Transparent
                                val badgeBorder = if(isMain) null else androidx.compose.foundation.BorderStroke(1.dp, sensor.color.copy(alpha=0.3f))

                                if (sensorCount > 1) {
                                    // Multi-sensor: interactive badge with Surface background
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .then(
                                                if (!isMain) Modifier.clickable { viewModel.setMain(sensor.serial) }
                                                else Modifier
                                            )
                                            .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            color = badgeBg,
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            border = badgeBorder
                                        ) {
                                            Icon(
                                                imageVector = if (isMain) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = if (isMain) "Active" else "Set Main",
                                                tint = badgeColor,
                                                modifier = Modifier
                                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                                    .size(18.dp)
                                            )
                                        }
                                    }
                                } else {
                                    // Single sensor: slim inline checkmark, no touch target
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Active",
                                        tint = badgeColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = statusText,
                                    style = enabledTextStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            // Feature: Detailed Sensor Status
                            Spacer(modifier = Modifier.height(8.dp))

                    if (sensor.detailedStatus.isNotEmpty()) {
                        Text(
                            text = sensor.detailedStatus,
                            style = MaterialTheme.typography.titleSmall, // Bigger than labelMedium
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else if (sensor.connectionStatus.isNotEmpty()) {
                         Text(
                            text = sensor.connectionStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                        }

                        // Logic: Show Pause if running, Play if stopped (to resume)
                        IconButton(
                            onClick = {
                                if (isStreaming) {
                                    android.util.Log.d("SensorCard", "Pause button clicked for: ${sensor.serial}")
                                    viewModel.disconnectSensor(sensor.serial)
                                } else {
                                    android.util.Log.d("SensorCard", "Play button clicked for: ${sensor.serial}")
                                    viewModel.reconnectSensor(sensor.serial, false)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surfaceDim.copy(alpha=0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isStreaming) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Toggle Sensor",
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
//                } // Close Column (content)
//                } // Close Column (content)
//            } // Close Row (color indicator wrapper)
//            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer // Tonal separation
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Clean Label-Value rows
                    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val valueStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)

                    val DataRow = @Composable { label: String, value: String ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = label,
                                style = labelStyle,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.42f)
                            )
                            Text(
                                text = value,
                                style = valueStyle,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.weight(0.58f)
                            )
                        }
                    }

                    if (sensor.connectionStatus.isNotEmpty()) {
                        DataRow(stringResource(R.string.last_ble_status), sensor.connectionStatus)
                    }
                    DataRow(stringResource(R.string.sensor_address), sensor.deviceAddress)
                    
                    // FIX: Use Long timestamp directly to avoid String Parsing Locale bugs in formatSensorTime
                    // User reported "100% Fill / Red Color" bug in English Locale, likely due to startMs being 0 or parse fail.
                    // We also ensure we only show valid dates.
                    if (sensor.startMs > 1577836800000L) { // > Jan 1 2020
                        DataRow(stringResource(R.string.sensor_started), formatSensorTime(sensor.startMs.toString()))
                    }

                    if (sensor.officialEndMs > 0) {
                        DataRow(stringResource(R.string.sensor_ends_officially), formatSensorTime(sensor.officialEndMs.toString()))
                    } else if (sensor.officialEnd.isNotEmpty()) {
                        DataRow(stringResource(R.string.sensor_ends_officially), formatSensorTime(sensor.officialEnd))
                    }

                    if (sensor.expectedEndMs > 0) {
                        DataRow(stringResource(R.string.sensor_expected_end), formatSensorTime(sensor.expectedEndMs.toString()))
                    } else if (sensor.expectedEnd.isNotEmpty()) {
                       DataRow(stringResource(R.string.sensor_expected_end), formatSensorTime(sensor.expectedEnd))
                    }

                    if (sensor.isMq && sensor.batteryPercent >= 0) {
                        DataRow(stringResource(R.string.sensor_battery_voltage), "${sensor.batteryPercent}%")
                    } else if (sensor.isAnytime && sensor.batteryMillivolts > 0) {
                        // Anytime: surface both percent and voltage — voltage is the
                        // health-critical metric (low-battery cutoff is 4.05 V on CT3).
                        val voltsText = String.format(java.util.Locale.getDefault(), "%.2f V", sensor.batteryMillivolts / 1000.0)
                        val combined = if (sensor.batteryPercent >= 0) {
                            "${sensor.batteryPercent}% · $voltsText"
                        } else {
                            voltsText
                        }
                        DataRow(stringResource(R.string.sensor_battery_voltage), combined)
                    } else if (sensor.batteryMillivolts > 0) {
                        DataRow(stringResource(R.string.sensor_battery_voltage), String.format(java.util.Locale.getDefault(), "%.3f V", sensor.batteryMillivolts / 1000.0))
                    }

                    if (sensor.sensorRemainingHours >= 0) {
                        val remainText = when {
                            sensor.isSensorExpired -> stringResource(R.string.expired)
                            sensor.sensorRemainingHours <= 0 -> stringResource(R.string.expired)
                            sensor.sensorRemainingHours <= 24 -> stringResource(R.string.hours_remaining, sensor.sensorRemainingHours)
                            else -> {
                                val days = sensor.sensorRemainingHours / 24
                                val hours = sensor.sensorRemainingHours % 24
                                stringResource(R.string.days_hours_remaining, days, hours)
                            }
                        }
                        val remainColor = when {
                            sensor.isSensorExpired || sensor.sensorRemainingHours <= 0 -> MaterialTheme.colorScheme.error
                            sensor.sensorRemainingHours <= 24 -> MaterialTheme.colorScheme.error
                            sensor.sensorRemainingHours <= 48 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.sensor_life), style = labelStyle)
                            Text(
                                remainText,
                                style = valueStyle.copy(color = remainColor),
                                fontWeight = if (sensor.sensorRemainingHours <= 24) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    if (sensor.sensorAgeHours >= 0) {
                        val ageText = if (sensor.sensorAgeHours < 24) "${sensor.sensorAgeHours}h"
                                      else "${sensor.sensorAgeHours / 24}d ${sensor.sensorAgeHours % 24}h"
                        DataRow(stringResource(R.string.sensor_age), ageText)
                    }

                    if (sensor.vendorModel.isNotEmpty()) {
                        DataRow(stringResource(R.string.model), sensor.vendorModel)
                    }
                    if (sensor.vendorFirmware.isNotEmpty()) {
                        val firmwareText = if (sensor.vendorFirmware.startsWith("v", ignoreCase = true)) {
                            sensor.vendorFirmware
                        } else {
                            "v${sensor.vendorFirmware}"
                        }
                        DataRow(stringResource(R.string.firmware), firmwareText)
                    }

                    if (sensor.isSensorExpired) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.status), style = labelStyle)
                            Text(
                                stringResource(R.string.sensor_expired_text),
                                style = valueStyle.copy(color = MaterialTheme.colorScheme.error),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                }
            }
//
//            Card(
//                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), // Secondary Container
//                shape = RoundedCornerShape(12.dp),
//                modifier = Modifier.fillMaxWidth()
//            ) {
//                Column(
//                    modifier = Modifier.padding(12.dp),
//                    verticalArrangement = Arrangement.spacedBy(4.dp)
//                ) {
//                    if (sensor.connectionStatus.isNotEmpty()) {
//                        InfoRow(stringResource(R.string.last_ble_status), sensor.connectionStatus)
//                    }
//                    InfoRow(stringResource(R.string.sensor_address), sensor.deviceAddress)
//
//                    InfoRow(stringResource(R.string.sensor_started), formatSensorTime(sensor.starttime))
//                    if (sensor.officialEnd.isNotEmpty()) {
//                        InfoRow(stringResource(R.string.sensor_ends_officially), formatSensorTime(sensor.officialEnd))
//                    }
//                    if (sensor.expectedEnd.isNotEmpty()) {
//                        InfoRow(stringResource(R.string.sensor_expected_end), formatSensorTime(sensor.expectedEnd))
//                    }
//                    // InfoRow("Streaming", if (sensor.streaming) "Enabled" else "Disabled")
//                }
//            }

            Spacer(modifier = Modifier.height(16.dp)) // More breathing room (M3 Expressive)
//            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
//            Spacer(modifier = Modifier.height(16.dp))

            // Edit 79 rev: Sensor Data Mode — ConnectedButtonGroup
            if (sensor.isSibionics || sensor.supportsDisplayModes) {
                Text(
                    stringResource(R.string.data_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                val modeLabels = listOf(
                    stringResource(R.string.auto),
                    stringResource(R.string.raw),
                    stringResource(R.string.auto_raw),
                    stringResource(R.string.raw_auto)
                )
                ConnectedButtonGroup(
                    options = modeLabels.indices.toList(),
                    selectedOption = sensor.viewMode,
                    onOptionSelected = { viewModel.setCalibrationMode(sensor.serial, it) },
                    labelText = { modeLabels[it] },
                    label = {
                        Text(
                            text = modeLabels[it],
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Auto-calibration entry — Sibionics only, hidden when Raw mode selected (viewMode == 1)
                if (sensor.isSibionics && sensor.viewMode != 1) {
                    val calSubtitle = if (sensor.customCalEnabled) {
                        val calLabels = listOf("12H", "1D", "2D", "3D", "5D", "7D", "10D", "14D", "18D", "MAX")
                        val label = calLabels.getOrElse(sensor.customCalIndex) { "12H" }
                        "$label ${stringResource(R.string.window_label)}"
                    } else {
                        stringResource(R.string.juggluco_native)
                    }
                    Surface(
                        onClick = { showSibionicsCalSheet = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.auto_calibration_mode),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    calSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sensor.customCalEnabled) MaterialTheme.colorScheme.tertiary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
//                    Spacer(modifier = Modifier.height(8.dp))
                }
            }


            // Edit 79: Auto-calibration and auto-reset controls moved to Sibionics Calibration bottom sheet.

            // --- ACTION BUTTONS (Always Visible) ---
            Spacer(modifier = Modifier.height(8.dp))

            // AiDex: Calibration history list, then Calibrate button, then Reset | Pair/Unpair row
            if (sensor.isAidex) {

                // Full-width Calibrate button — disabled when vendor BLE is not connected
                val canCalibrate = sensor.isVendorConnected
                FilledTonalButton(
                    onClick = { showSensorCalibrateDialog = true },
                    enabled = canCalibrate,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Bloodtype,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (canCalibrate) stringResource(R.string.calibrate_action) else stringResource(R.string.calibrate_connect_first),
                        maxLines = 1
                    )
                }
                // Calibration history — show previous calibrations from the sensor
                if (sensor.vendorCalibrations.isNotEmpty()) {
                    val isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
                    val calDateFormat = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
                    val calCount = sensor.vendorCalibrations.size
                    val collapsible = calCount > 3
                    var calExpanded by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                    // Newest first — reverse so most recent calibrations appear at the top
//                    val allCalsReversed = sensor.vendorCalibrations()
                    val visibleCals = if (collapsible && !calExpanded) {
                        sensor.vendorCalibrations.take(3)
                    } else {
                        sensor.vendorCalibrations
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier.animateContentSize()
                        ) {
                            visibleCals.forEachIndexed { idx, cal ->
                                // Divider between rows, but NOT after the last visible row
                                if (idx > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val displayGlucose = tk.glucodata.ui.util.GlucoseFormatter.formatFromMgDl(
                                            cal.referenceGlucoseMgDl.toFloat(),
                                            isMmol
                                        )
                                        Text(
                                            text = displayGlucose,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        val timeText = if (cal.timestampMs > 0) {
                                            calDateFormat.format(java.util.Date(cal.timestampMs))
                                        } else {
                                            "${cal.timeOffsetMinutes}m"
                                        }
                                        Text(
                                            text = timeText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.padding(top = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = "CF: ${"%.2f".format(cal.cf)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Offset: ${"%.2f".format(cal.offset)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            // Expand/collapse at BOTTOM — no extra padding, rounded bottom corners
                            if (collapsible) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                        .clickable { calExpanded = !calExpanded }
                                        .heightIn(min = 48.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (calExpanded) "Show less" else "Show all $calCount",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (calExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Edit 78: Bias correction toggle moved to the Reset & Correction bottom sheet.
                // No inline toggle here — it was clipped by the card container and had
                // touch target issues. Users access it via the Reset button now.


                // Edit 74: Reset (left, smaller, no weight) | Unpair/Pair (right, larger, weight 1f)
                // Unpair/Pair is more important — it's the primary action for AiDex sensor management.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Edit 78: Reset button — opens bottom sheet. Shows tertiary tint when
                    // bias correction is active so the user knows something is going on.
                    FilledTonalButton(
                        onClick = { showAiDexClearDialog = true },
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            bottomStart = 12.dp,
                            topEnd = 4.dp,
                            bottomEnd = 4.dp
                        ),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (sensor.resetCompensationActive)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (sensor.resetCompensationActive)
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (sensor.resetCompensationActive) stringResource(R.string.correcting) else stringResource(R.string.resettitle),
                            maxLines = 1
                        )
                    }
                    // Pair / Unpair toggle — right (weight 1f = fills remaining space, prominent)
                    if (sensor.isVendorPaired) {
                        FilledTonalButton(
                            onClick = { showAiDexUnpairDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 4.dp,
                                bottomStart = 4.dp,
                                topEnd = 12.dp,
                                bottomEnd = 12.dp
                            ),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.unpair), maxLines = 1)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                viewModel.rePairAiDexSensor(sensor.serial)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 4.dp,
                                bottomStart = 4.dp,
                                topEnd = 12.dp,
                                bottomEnd = 12.dp
                            ),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.pair), maxLines = 1)
                        }
                    }
                }
            }

            // Row 1: Unified Reset Button (Sibionics only - full width, styled like "Previous calibrations")
            if (sensor.isSibionics2) {
                FilledTonalButton(
                    onClick = { showUnifiedResetDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.reset_sensor))
                }

                // Auto-reset days stepper (hardware reset scheduling, not algorithm-related)
                val isAutoResetEnabled = sensor.autoResetDays < 25
                var daysValue by remember(sensor.autoResetDays) {
                    mutableStateOf(if (isAutoResetEnabled) sensor.autoResetDays else 20)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.auto_reset_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    AnimatedVisibility(visible = isAutoResetEnabled) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (daysValue > 1) {
                                            daysValue--
                                            viewModel.setAutoResetDays(sensor.serial, daysValue)
                                        }
                                    },
                                    enabled = daysValue > 1,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        modifier = Modifier.size(16.dp),
                                        tint = if (daysValue > 1) MaterialTheme.colorScheme.onSurfaceVariant
                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.auto_reset_days, daysValue),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (daysValue < 22) {
                                            daysValue++
                                            viewModel.setAutoResetDays(sensor.serial, daysValue)
                                        }
                                    },
                                    enabled = daysValue < 22,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Increase",
                                        modifier = Modifier.size(16.dp),
                                        tint = if (daysValue < 22) MaterialTheme.colorScheme.onSurfaceVariant
                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }
                    }
                    StyledSwitch(
                        checked = isAutoResetEnabled,
                        onCheckedChange = { enabled ->
                            val newValue = if (enabled) daysValue else 300
                            viewModel.setAutoResetDays(sensor.serial, newValue)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (sensor.isMq) {
                FilledTonalButton(
                    onClick = { showMqRestoreSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.mq_fetch_calibration_action))
                }
                FilledTonalButton(
                    onClick = { showMqCalibrationSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.mq_manual_calibration_title))
                }
            }

            if (sensor.isAnytime && sensor.supportsManualCalibration) {
                val warmupComplete = sensor.sensorAgeHours >= 24
                val canCalibrate = sensor.isVendorConnected && warmupComplete
                FilledTonalButton(
                    onClick = { showSensorCalibrateDialog = true },
                    enabled = canCalibrate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Bloodtype,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            !sensor.isVendorConnected -> stringResource(R.string.calibrate_connect_first)
                            !warmupComplete -> stringResource(R.string.calibrate_after_24h)
                            else -> stringResource(R.string.calibrate_action)
                        },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            if (!sensor.isAidex && !sensor.isSibionics2 && sensor.supportsHardwareReset) {
                FilledTonalButton(
                    onClick = { showResetDialog = true },
                    enabled = sensor.isVendorConnected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.reset_sensor))
                }
            }

            // Edit 63b: All sensors get the same 2-button row: Reconnect | Disconnect.
            // AiDex-specific behavior is handled in the dialogs (terminate dialog routes
            // AiDex through disconnectSensor instead of terminateSensor).
            // Edit 65c: Keep the old full-width 50/50 row when both labels fit, then let
            // Disconnect keep priority only on genuinely tight localized layouts.
            val reconnectLabel = stringResource(R.string.reconnect)
            val disconnectLabel = stringResource(R.string.disconnect)
            val layoutDirection = LocalLayoutDirection.current
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val buttonTextStyle = MaterialTheme.typography.labelLarge
            val buttonChromeWidth = 16.dp +
                8.dp +
                ButtonDefaults.ContentPadding.calculateLeftPadding(layoutDirection) +
                ButtonDefaults.ContentPadding.calculateRightPadding(layoutDirection)
            val reconnectPreferredWidth = with(density) {
                textMeasurer.measure(
                    text = reconnectLabel,
                    style = buttonTextStyle,
                    maxLines = 1
                ).size.width.toDp() + buttonChromeWidth
            }
            val disconnectPreferredWidth = with(density) {
                textMeasurer.measure(
                    text = disconnectLabel,
                    style = buttonTextStyle,
                    maxLines = 1
                ).size.width.toDp() + buttonChromeWidth
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val buttonSpacing = 8.dp
                val equalButtonWidth = (maxWidth - buttonSpacing) / 2
                val prioritizeDisconnect =
                    reconnectPreferredWidth > equalButtonWidth ||
                    disconnectPreferredWidth > equalButtonWidth

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
                ) {
                    // Reconnect always stays flexible so it can either match the old 50/50
                    // layout or yield first when Disconnect needs more room.
                    FilledTonalButton(
                        onClick = { showReconnectDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            bottomStart = 12.dp,
                            topEnd = 4.dp,
                            bottomEnd = 4.dp
                        ),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothConnected,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            reconnectLabel,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    FilledTonalButton(
                        onClick = { showTerminateDialog = true },
                        modifier = if (prioritizeDisconnect) Modifier else Modifier.weight(1f),
                        shape = RoundedCornerShape(
                            topStart = 4.dp,
                            bottomStart = 4.dp,
                            topEnd = 12.dp,
                            bottomEnd = 12.dp
                        ),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            disconnectLabel,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
            }
        }
    }
}
}
