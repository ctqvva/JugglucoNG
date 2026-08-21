@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package tk.glucodata.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataSaverOff
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorSourceResolver
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.alerts.FullScreenIntentReadiness
import tk.glucodata.data.settings.FloatingSettingsRepository

private const val CGM_READINESS_PREFS = "cgm_readiness"
private const val DISMISS_SENSORS = "dismiss_sensors_signature"
private const val DISMISS_DASHBOARD = "dismiss_dashboard_signature"
private const val DISMISS_SETUP = "dismiss_setup_signature"

private enum class CgmReadinessStatus {
    Ready,
    Critical,
    Warning,
    Advisory,
    Manual
}

private enum class CgmReadinessAction {
    RequestBlePermissions,
    RequestNotificationPermission,
    EnableBluetooth,
    OpenBatteryOptimization,
    OpenExactAlarmSettings,
    OpenFullScreenIntentSettings,
    OpenNotificationSettings,
    OpenDndSettings,
    OpenLocationSettings,
    OpenNfcSettings,
    OpenDataSaverSettings,
    OpenOverlaySettings,
    OpenAppSettings
}

private data class CgmReadinessItem(
    val id: String,
    val titleRes: Int,
    val detailRes: Int,
    val status: CgmReadinessStatus,
    val icon: ImageVector,
    val action: CgmReadinessAction? = null,
    val actionLabelRes: Int? = null,
    val showInBanners: Boolean = true
) {
    val needsAttention: Boolean
        get() = status == CgmReadinessStatus.Critical ||
            status == CgmReadinessStatus.Warning ||
            status == CgmReadinessStatus.Advisory
}

private data class CgmReadinessSnapshot(
    val items: List<CgmReadinessItem>
) {
    val attentionItems: List<CgmReadinessItem> = items.filter { it.needsAttention && it.showInBanners }
    val criticalItems: List<CgmReadinessItem> = attentionItems.filter { it.status == CgmReadinessStatus.Critical }
    val warningItems: List<CgmReadinessItem> = attentionItems.filter { it.status == CgmReadinessStatus.Warning }
    val signature: String = attentionItems.joinToString("|") { "${it.id}:${it.status.name}" }
    val criticalSignature: String = criticalItems.joinToString("|") { "${it.id}:${it.status.name}" }
}

private fun android.content.SharedPreferences.Editor.dismissReadinessEverywhere(
    snapshot: CgmReadinessSnapshot
): android.content.SharedPreferences.Editor {
    return putString(DISMISS_SENSORS, snapshot.signature)
        .putString(DISMISS_DASHBOARD, snapshot.criticalSignature)
        .putString(DISMISS_SETUP, snapshot.signature)
}

@Composable
fun CgmReadinessScreen(navController: NavController) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val snapshot = remember(refreshTick) { buildCgmReadinessSnapshot(context) }
    val actionHandler = rememberCgmReadinessActionHandler { refreshTick++ }

    RefreshReadinessOnResume { refreshTick++ }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cgm_readiness_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh)
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "summary") {
                CgmReadinessHero(snapshot = snapshot)
            }

            items(snapshot.items, key = { it.id }) { item ->
                CgmReadinessDetailRow(
                    item = item,
                    onAction = { action -> actionHandler(action) }
                )
            }
        }
    }
}

@Composable
fun SensorsCgmReadinessBanner(
    modifier: Modifier = Modifier,
    onOpenReadiness: () -> Unit
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val snapshot = remember(refreshTick) { buildCgmReadinessSnapshot(context) }
    val items = snapshot.attentionItems
    val prefs = remember(context) { context.getSharedPreferences(CGM_READINESS_PREFS, Context.MODE_PRIVATE) }
    var dismissedSignature by remember { mutableStateOf(prefs.getString(DISMISS_SENSORS, null)) }
    val actionHandler = rememberCgmReadinessActionHandler { refreshTick++ }

    RefreshReadinessOnResume { refreshTick++ }
    ClearDismissalWhenReady(
        prefs = prefs,
        key = DISMISS_SENSORS,
        shouldClear = items.isEmpty(),
        dismissedSignature = dismissedSignature,
        onCleared = { dismissedSignature = null }
    )

    AnimatedVisibility(
        visible = items.isNotEmpty() && dismissedSignature != snapshot.signature,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        CgmReadinessSummaryCard(
            title = stringResource(R.string.cgm_readiness_title),
            snapshot = snapshot,
            items = items,
            maxVisibleItems = 3,
            onDismiss = {
                prefs.edit().dismissReadinessEverywhere(snapshot).apply()
                dismissedSignature = snapshot.signature
            },
            onOpenReadiness = onOpenReadiness,
            onAction = actionHandler
        )
    }
}

@Composable
fun DashboardCgmReadinessBanner(
    modifier: Modifier = Modifier,
    onOpenReadiness: () -> Unit
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val snapshot = remember(refreshTick) { buildCgmReadinessSnapshot(context) }
    val items = snapshot.criticalItems
    val prefs = remember(context) { context.getSharedPreferences(CGM_READINESS_PREFS, Context.MODE_PRIVATE) }
    var dismissedSignature by remember { mutableStateOf(prefs.getString(DISMISS_DASHBOARD, null)) }
    val actionHandler = rememberCgmReadinessActionHandler { refreshTick++ }

    RefreshReadinessOnResume { refreshTick++ }
    ClearDismissalWhenReady(
        prefs = prefs,
        key = DISMISS_DASHBOARD,
        shouldClear = items.isEmpty(),
        dismissedSignature = dismissedSignature,
        onCleared = { dismissedSignature = null }
    )

    AnimatedVisibility(
        visible = items.isNotEmpty() && dismissedSignature != snapshot.criticalSignature,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        CgmReadinessSummaryCard(
            title = stringResource(R.string.cgm_readiness_dashboard_title),
            snapshot = snapshot,
            items = items,
            maxVisibleItems = 3,
            onDismiss = {
                prefs.edit().dismissReadinessEverywhere(snapshot).apply()
                dismissedSignature = snapshot.criticalSignature
            },
            onOpenReadiness = onOpenReadiness,
            onAction = actionHandler,
            elevated = true
        )
    }
}

@Composable
fun CgmReadinessSetupBanner(
    modifier: Modifier = Modifier,
    includeLibreNfc: Boolean = false,
    onOpenReadiness: () -> Unit
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val snapshot = remember(refreshTick, includeLibreNfc) {
        buildCgmReadinessSnapshot(context, includeLibreNfc = includeLibreNfc)
    }
    val items = snapshot.attentionItems
    val prefs = remember(context) { context.getSharedPreferences(CGM_READINESS_PREFS, Context.MODE_PRIVATE) }
    var dismissedSignature by remember { mutableStateOf(prefs.getString(DISMISS_SETUP, null)) }
    val actionHandler = rememberCgmReadinessActionHandler { refreshTick++ }

    RefreshReadinessOnResume { refreshTick++ }
    ClearDismissalWhenReady(
        prefs = prefs,
        key = DISMISS_SETUP,
        shouldClear = items.isEmpty(),
        dismissedSignature = dismissedSignature,
        onCleared = { dismissedSignature = null }
    )

    AnimatedVisibility(
        visible = items.isNotEmpty() && dismissedSignature != snapshot.signature,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        CgmReadinessSummaryCard(
            title = stringResource(R.string.cgm_readiness_setup_title),
            summaryText = stringResource(R.string.cgm_readiness_setup_body, items.size),
            snapshot = snapshot,
            items = items,
            maxVisibleItems = 2,
            onDismiss = {
                prefs.edit().dismissReadinessEverywhere(snapshot).apply()
                dismissedSignature = snapshot.signature
            },
            onOpenReadiness = onOpenReadiness,
            onAction = actionHandler,
            elevated = true
        )
    }
}

@Composable
private fun CgmReadinessHero(snapshot: CgmReadinessSnapshot) {
    val criticalCount = snapshot.criticalItems.size
    val attentionCount = snapshot.attentionItems.size
    val ready = attentionCount == 0
    val color = when {
        criticalCount > 0 -> MaterialTheme.colorScheme.error
        attentionCount > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (ready) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
        } else {
            color.copy(alpha = 0.12f)
        },
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIconSurface(
                icon = if (ready) Icons.Filled.CheckCircle else Icons.Filled.Security,
                color = color
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ready) {
                        stringResource(R.string.cgm_readiness_ready_title)
                    } else {
                        stringResource(R.string.cgm_readiness_attention_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (ready) {
                        stringResource(R.string.cgm_readiness_ready_body)
                    } else {
                        stringResource(R.string.cgm_readiness_attention_body, attentionCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CgmReadinessSummaryCard(
    title: String,
    summaryText: String? = null,
    snapshot: CgmReadinessSnapshot,
    items: List<CgmReadinessItem>,
    maxVisibleItems: Int,
    onDismiss: () -> Unit,
    onOpenReadiness: () -> Unit,
    onAction: (CgmReadinessAction) -> Unit,
    elevated: Boolean = false
) {
    val criticalCount = items.count { it.status == CgmReadinessStatus.Critical }
    val warningCount = items.count { it.status == CgmReadinessStatus.Warning }
    val attentionCount = items.size
    val primaryColor = when {
        criticalCount > 0 -> MaterialTheme.colorScheme.error
        warningCount > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val topAction = items.firstNotNullOfOrNull { item ->
        item.action?.let { action ->
            action to (item.actionLabelRes ?: R.string.cgm_readiness_fix_action)
        }
    }
    val visibleItems = items.take(maxVisibleItems)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (elevated) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.64f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.24f)),
        shadowElevation = if (elevated) 1.dp else 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StatusIconSurface(
                    icon = if (criticalCount > 0) Icons.Filled.ErrorOutline else Icons.Filled.WarningAmber,
                    color = primaryColor
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
//                        CgmReadinessCountChip(snapshot = snapshot, items = items)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = summaryText ?: stringResource(R.string.cgm_readiness_summary_body, attentionCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cgm_readiness_dismiss_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleItems.forEach { item ->
                    CgmReadinessCompactIssue(item)
                }
            }

            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                topAction?.let { (action, labelRes) ->
                    Button(
                        onClick = { onAction(action) },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
                OutlinedButton(onClick = onOpenReadiness) {
                    Text(stringResource(R.string.cgm_readiness_review_action))
                }
            }
        }
    }
}

@Composable
private fun CgmReadinessDetailRow(
    item: CgmReadinessItem,
    onAction: (CgmReadinessAction) -> Unit
) {
    val color = item.status.statusColor()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            StatusIconSurface(icon = item.icon, color = color)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    CgmStatusChip(status = item.status)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(item.detailRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.action != null && item.actionLabelRes != null) {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { onAction(item.action) }) {
                        Text(stringResource(item.actionLabelRes))
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CgmReadinessCompactIssue(item: CgmReadinessItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .padding(1.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = item.status.statusColor()
            ) {}
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(item.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        CgmStatusChip(status = item.status, compact = true)
    }
}

@Composable
private fun CgmReadinessCountChip(
    snapshot: CgmReadinessSnapshot,
    items: List<CgmReadinessItem>
) {
    val label = when {
        snapshot.criticalItems.isNotEmpty() -> {
            stringResource(R.string.cgm_readiness_critical_count, snapshot.criticalItems.size)
        }
        items.any { it.status == CgmReadinessStatus.Warning } -> {
            stringResource(R.string.cgm_readiness_warning_count, items.count { it.status == CgmReadinessStatus.Warning })
        }
        else -> stringResource(R.string.cgm_readiness_attention_count, items.size)
    }
    val color = when {
        snapshot.criticalItems.isNotEmpty() -> MaterialTheme.colorScheme.error
        items.any { it.status == CgmReadinessStatus.Warning } -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun CgmStatusChip(
    status: CgmReadinessStatus,
    compact: Boolean = false
) {
    val color = status.statusColor()
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = if (status == CgmReadinessStatus.Ready) 0.14f else 0.12f),
        contentColor = color
    ) {
        Text(
            text = stringResource(status.labelRes()),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 4.dp else 5.dp
            )
        )
    }
}

@Composable
private fun StatusIconSurface(
    icon: ImageVector,
    color: Color
) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CgmReadinessStatus.statusColor(): Color {
    return when (this) {
        CgmReadinessStatus.Ready -> MaterialTheme.colorScheme.primary
        CgmReadinessStatus.Critical -> MaterialTheme.colorScheme.error
        CgmReadinessStatus.Warning -> MaterialTheme.colorScheme.tertiary
        CgmReadinessStatus.Advisory -> MaterialTheme.colorScheme.secondary
        CgmReadinessStatus.Manual -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun CgmReadinessStatus.labelRes(): Int {
    return when (this) {
        CgmReadinessStatus.Ready -> R.string.cgm_readiness_status_ready
        CgmReadinessStatus.Critical -> R.string.cgm_readiness_status_critical
        CgmReadinessStatus.Warning -> R.string.cgm_readiness_status_warning
        CgmReadinessStatus.Advisory -> R.string.cgm_readiness_status_recommended
        CgmReadinessStatus.Manual -> R.string.cgm_readiness_status_manual
    }
}

@Composable
private fun rememberCgmReadinessActionHandler(onChanged: () -> Unit): (CgmReadinessAction) -> Unit {
    val context = LocalContext.current
    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onChanged()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onChanged()
    }
    val activityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onChanged()
    }

    return remember(context, onChanged) {
        { action ->
            when (action) {
                CgmReadinessAction.RequestBlePermissions -> {
                    blePermissionLauncher.launch(requiredBleRuntimePermissions())
                }
                CgmReadinessAction.RequestNotificationPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onChanged()
                    }
                }
                CgmReadinessAction.EnableBluetooth -> {
                    activityLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                else -> {
                    context.startReadinessSettingsActivity(action)
                    onChanged()
                }
            }
        }
    }
}

@Composable
private fun RefreshReadinessOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onResume) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun ClearDismissalWhenReady(
    prefs: android.content.SharedPreferences,
    key: String,
    shouldClear: Boolean,
    dismissedSignature: String?,
    onCleared: () -> Unit
) {
    LaunchedEffect(shouldClear, dismissedSignature) {
        if (shouldClear && dismissedSignature != null) {
            prefs.edit().remove(key).apply()
            onCleared()
        }
    }
}

private fun buildCgmReadinessSnapshot(
    context: Context,
    includeLibreNfc: Boolean = false
): CgmReadinessSnapshot {    val appContext = context.applicationContext
    val shouldCheckNfc = includeLibreNfc || hasActiveLibreSensor()
    return CgmReadinessSnapshot(
        listOf(
            blePermissionItem(appContext),
            bluetoothItem(appContext),
            legacyLocationServicesItem(appContext),
            notificationPermissionItem(appContext),
            appNotificationItem(appContext),
            exactAlarmItem(appContext),
            fullScreenIntentItem(appContext),
            batteryOptimizationItem(appContext),
            dndItem(appContext),
            overlayItem(appContext),
            dataSaverItem(appContext),
            if (shouldCheckNfc) nfcItem(appContext) else null,
            manufacturerBackgroundItem()
        ).filterNotNull()
    )
}

private fun hasActiveLibreSensor(): Boolean {
    return runCatching {
        Natives.activeSensors()
            ?.filterNotNull()
            ?.any(::isLibreSensorId) == true
    }.getOrDefault(false)
}

private fun isLibreSensorId(sensorId: String): Boolean {
    val kind = runCatching {
        SensorSourceResolver.resolveSensorKind(
            sensorId,
            SensorSourceResolver.SENSOR_KIND_UNKNOWN
        )
    }.getOrDefault(SensorSourceResolver.SENSOR_KIND_UNKNOWN)
    if (kind == SensorSourceResolver.SENSOR_KIND_LIBRE2 ||
        kind == SensorSourceResolver.SENSOR_KIND_LIBRE3
    ) {
        return true
    }
    val sensorPtr = runCatching { Natives.str2sensorptr(sensorId) }.getOrDefault(0L)
    if (sensorPtr != 0L) {
        val nativeKind = runCatching { Natives.getSensorptrLibreVersion(sensorPtr) }
            .getOrDefault(SensorSourceResolver.SENSOR_KIND_UNKNOWN)
        return nativeKind == SensorSourceResolver.SENSOR_KIND_LIBRE2 ||
            nativeKind == SensorSourceResolver.SENSOR_KIND_LIBRE3
    }
    return false
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun bluetoothItem(context: Context): CgmReadinessItem? {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    val ready = adapter?.isEnabled == true
    return CgmReadinessItem(
        id = "bluetooth",
        titleRes = R.string.cgm_readiness_bluetooth_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_bluetooth_ready_detail
        } else {
            R.string.cgm_readiness_bluetooth_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.Bluetooth,
        action = if (ready) null else CgmReadinessAction.EnableBluetooth,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_enable_bluetooth_action
    )
}

private fun blePermissionItem(context: Context): CgmReadinessItem? {
    val required = requiredBleRuntimePermissions()
    if (required.isEmpty()) return null
    val ready = required.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val titleRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        R.string.cgm_readiness_nearby_title
    } else {
        R.string.cgm_readiness_location_permission_title
    }
    val detailRes = when {
        ready && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> R.string.cgm_readiness_nearby_ready_detail
        ready -> R.string.cgm_readiness_location_permission_ready_detail
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> R.string.cgm_readiness_nearby_detail
        else -> R.string.cgm_readiness_location_permission_detail
    }
    return CgmReadinessItem(
        id = "ble_permissions",
        titleRes = titleRes,
        detailRes = detailRes,
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.Security,
        action = if (ready) null else CgmReadinessAction.RequestBlePermissions,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_allow_action
    )
}

private fun legacyLocationServicesItem(context: Context): CgmReadinessItem? {
    if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.M..Build.VERSION_CODES.R) return null
    val ready = context.isLocationEnabledCompat()
    return CgmReadinessItem(
        id = "location_services",
        titleRes = R.string.cgm_readiness_location_services_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_location_services_ready_detail
        } else {
            R.string.cgm_readiness_location_services_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.LocationOn,
        action = if (ready) null else CgmReadinessAction.OpenLocationSettings,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_location_action
    )
}

private fun notificationPermissionItem(context: Context): CgmReadinessItem? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val ready = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    return CgmReadinessItem(
        id = "notification_permission",
        titleRes = R.string.cgm_readiness_notification_permission_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_notification_permission_ready_detail
        } else {
            R.string.cgm_readiness_notification_permission_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.NotificationsActive,
        action = if (ready) null else CgmReadinessAction.RequestNotificationPermission,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_allow_action
    )
}

private fun appNotificationItem(context: Context): CgmReadinessItem? {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    val ready = NotificationManagerCompat.from(context).areNotificationsEnabled()
    return CgmReadinessItem(
        id = "notifications",
        titleRes = R.string.cgm_readiness_notifications_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_notifications_ready_detail
        } else {
            R.string.cgm_readiness_notifications_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.NotificationsActive,
        action = if (ready) null else CgmReadinessAction.OpenNotificationSettings,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_notifications_action
    )
}

private fun exactAlarmItem(context: Context): CgmReadinessItem? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    val ready = alarmManager?.canScheduleExactAlarms() != false
    return CgmReadinessItem(
        id = "exact_alarms",
        titleRes = R.string.cgm_readiness_exact_alarm_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_exact_alarm_ready_detail
        } else {
            R.string.cgm_readiness_exact_alarm_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.Alarm,
        action = if (ready) null else CgmReadinessAction.OpenExactAlarmSettings,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_alarm_action
    )
}

private fun fullScreenIntentItem(context: Context): CgmReadinessItem? {
    // Since Android 14 the manifest declaration alone no longer grants
    // USE_FULL_SCREEN_INTENT; it can be missing after install and is taken away
    // again by app updates, so it is re-checked on every snapshot, not cached.
    if (!FullScreenIntentReadiness.shouldCheck(Build.VERSION.SDK_INT, isFullScreenDeliveryEnabledForAnyAlert())) {
        return null
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    val ready = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        manager?.canUseFullScreenIntent() != false
    return CgmReadinessItem(
        id = "full_screen_intent",
        titleRes = R.string.cgm_readiness_full_screen_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_full_screen_ready_detail
        } else {
            R.string.cgm_readiness_full_screen_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.Fullscreen,
        action = if (ready) null else CgmReadinessAction.OpenFullScreenIntentSettings,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_full_screen_action
    )
}

private fun isFullScreenDeliveryEnabledForAnyAlert(): Boolean {
    val alerts = AlertType.settingsEntries.map { AlertRepository.loadConfig(it) }
    val customAlerts = runCatching { CustomAlertRepository.getAll() }.getOrDefault(emptyList())
    return FullScreenIntentReadiness.anyAlertUsesFullScreen(alerts, customAlerts)
}

private fun batteryOptimizationItem(context: Context): CgmReadinessItem? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ready = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    return CgmReadinessItem(
        id = "battery_optimization",
        titleRes = R.string.cgm_readiness_battery_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_battery_ready_detail
        } else {
            R.string.cgm_readiness_battery_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Critical,
        icon = Icons.Filled.BatterySaver,
        action = if (ready) null else CgmReadinessAction.OpenBatteryOptimization,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_battery_action
    )
}

private fun dndItem(context: Context): CgmReadinessItem? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    if (!isDndOverrideEnabledForAnyAlert()) return null

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    val ready = manager?.isNotificationPolicyAccessGranted == true
    return CgmReadinessItem(
        id = "dnd_access",
        titleRes = R.string.cgm_readiness_dnd_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_dnd_ready_detail
        } else {
            R.string.cgm_readiness_dnd_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Warning,
        icon = Icons.Filled.DoNotDisturbOn,
        action = if (ready) null else CgmReadinessAction.OpenDndSettings,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_dnd_action
    )
}

private fun isDndOverrideEnabledForAnyAlert(): Boolean {
    return AlertType.settingsEntries.any { type ->
        val config = AlertRepository.loadConfig(type)
        config.enabled && config.overrideDND
    }
}

private fun overlayItem(context: Context): CgmReadinessItem? {
    val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    val enabled = prefs.getBoolean(FloatingSettingsRepository.KEY_ENABLED, false)
    if (!enabled) {
        return CgmReadinessItem(
            id = "overlay",
            titleRes = R.string.cgm_readiness_overlay_title,
            detailRes = R.string.cgm_readiness_overlay_not_needed_detail,
            status = CgmReadinessStatus.Ready,
            icon = Icons.Filled.PhoneAndroid,
            showInBanners = false
        )
    }
    val ready = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    return CgmReadinessItem(
        id = "overlay",
        titleRes = R.string.cgm_readiness_overlay_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_overlay_ready_detail
        } else {
            R.string.cgm_readiness_overlay_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Warning,
        icon = Icons.Filled.PhoneAndroid,
        action = if (ready) null else CgmReadinessAction.OpenOverlaySettings,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_overlay_action
    )
}

private fun dataSaverItem(context: Context): CgmReadinessItem? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val restricted = manager?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    return CgmReadinessItem(
        id = "data_saver",
        titleRes = R.string.cgm_readiness_data_saver_title,
        detailRes = if (restricted) {
            R.string.cgm_readiness_data_saver_detail
        } else {
            R.string.cgm_readiness_data_saver_ready_detail
        },
        status = if (restricted) CgmReadinessStatus.Advisory else CgmReadinessStatus.Ready,
        icon = Icons.Filled.DataSaverOff,
        action = if (restricted) CgmReadinessAction.OpenDataSaverSettings else null,
        actionLabelRes = if (restricted) R.string.cgm_readiness_open_data_saver_action else null
    )
}

private fun nfcItem(context: Context): CgmReadinessItem? {
    val adapter = NfcAdapter.getDefaultAdapter(context) ?: return null
    val ready = adapter.isEnabled
    return CgmReadinessItem(
        id = "nfc",
        titleRes = R.string.cgm_readiness_nfc_title,
        detailRes = if (ready) {
            R.string.cgm_readiness_nfc_ready_detail
        } else {
            R.string.cgm_readiness_nfc_detail
        },
        status = if (ready) CgmReadinessStatus.Ready else CgmReadinessStatus.Advisory,
        icon = Icons.Filled.Nfc,
        action = if (ready) null else CgmReadinessAction.OpenNfcSettings,
        actionLabelRes = if (ready) null else R.string.cgm_readiness_open_nfc_action
    )
}

private fun manufacturerBackgroundItem(): CgmReadinessItem {
    return CgmReadinessItem(
        id = "manufacturer_background",
        titleRes = R.string.cgm_readiness_manufacturer_title,
        detailRes = R.string.cgm_readiness_manufacturer_detail,
        status = CgmReadinessStatus.Manual,
        icon = Icons.Filled.Settings,
        showInBanners = false
    )
}

private fun requiredBleRuntimePermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }
}

private fun Context.isLocationEnabledCompat(): Boolean {
    val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

private fun Context.startReadinessSettingsActivity(action: CgmReadinessAction) {
    val packageUri = Uri.parse("package:$packageName")
    val intent = when (action) {
        CgmReadinessAction.OpenBatteryOptimization -> {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        }
        CgmReadinessAction.OpenExactAlarmSettings -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            }
        }
        CgmReadinessAction.OpenFullScreenIntentSettings -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            }
        }
        CgmReadinessAction.OpenNotificationSettings -> {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        CgmReadinessAction.OpenDndSettings -> {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
        CgmReadinessAction.OpenLocationSettings -> {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        CgmReadinessAction.OpenNfcSettings -> {
            Intent(Settings.ACTION_NFC_SETTINGS)
        }
        CgmReadinessAction.OpenDataSaverSettings -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, packageUri)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
        }
        CgmReadinessAction.OpenOverlaySettings -> {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        }
        CgmReadinessAction.OpenAppSettings -> {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
        CgmReadinessAction.RequestBlePermissions,
        CgmReadinessAction.RequestNotificationPermission,
        CgmReadinessAction.EnableBluetooth -> return
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.cgm_readiness_settings_unavailable), Toast.LENGTH_LONG).show()
        }
    } catch (_: SecurityException) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.cgm_readiness_settings_unavailable), Toast.LENGTH_LONG).show()
        }
    }
}
