@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.alerts

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import tk.glucodata.Applic
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.alerts.*
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.components.CardPosition as SettingsItemPosition
import tk.glucodata.ui.theme.displayLargeExpressive
import tk.glucodata.ui.theme.labelLargeExpressive
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.logic.CustomAlertManager
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * State-of-the-art Alert Settings Screen with Material 3 Expressive design.
 * Inspired by xDrip+ best practices.
 */
@Composable
fun AlertSettingsScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val isMmol = Applic.unit == 1
    
    // Load all alert configs
    val configs = remember {
        mutableStateMapOf<AlertType, AlertConfig>().apply {
            AlertType.settingsEntries.forEach { put(it, AlertRepository.loadConfig(it)) }
        }
    }

    // Custom Alerts State
    var customAlerts by remember { mutableStateOf(CustomAlertRepository.getAll()) }
    fun refreshCustomAlerts() {
        customAlerts = CustomAlertRepository.getAll()
    }
    fun saveCustomAlerts(updatedAlerts: List<CustomAlertConfig>) {
        if (updatedAlerts == customAlerts) {
            return
        }
        CustomAlertRepository.saveAll(updatedAlerts)
        customAlerts = updatedAlerts
    }
    fun upsertCustomAlert(updatedAlert: CustomAlertConfig) {
        saveCustomAlerts(
            customAlerts.map { existing ->
                if (existing.id == updatedAlert.id) updatedAlert else existing
            }
        )
    }
    fun removeCustomAlert(alertId: String) {
        saveCustomAlerts(customAlerts.filterNot { it.id == alertId })
    }

    fun persistConfigIfChanged(updated: AlertConfig) {
        if (configs[updated.type] == updated) {
            return
        }
        configs[updated.type] = updated
        AlertRepository.saveConfig(updated)
    }

    // Dialog States
    // var showAddDialogType by remember { mutableStateOf<CustomAlertType?>(null) } // Removed, using instant add
    var alertToEdit by remember { mutableStateOf<CustomAlertConfig?>(null) }
    
    fun createDefaultCustomAlert(type: CustomAlertType) {
        val existingCount = customAlerts.count { it.type == type }
        val baseName = if (type == CustomAlertType.HIGH) context.getString(R.string.custom_high) else context.getString(R.string.custom_low)
        val newName = "$baseName ${existingCount + 1}"
        
        // Default values respecting isMmol
        val threshold = if (type == CustomAlertType.HIGH) {
            if (isMmol) 10.0f else 180f
        } else {
            if (isMmol) 3.9f else 70f
        }
        
        val newAlert = CustomAlertConfig(
            id = UUID.randomUUID().toString(),
            name = newName,
            type = type,
            threshold = threshold,
            startTimeMinutes = 0,
            endTimeMinutes = 1440, // All day by default
            enabled = true
        )
        saveCustomAlerts(customAlerts + newAlert)
    }

    // Group alerts by category
    val highAlerts = remember {
        listOf(AlertType.HIGH, AlertType.VERY_HIGH)
    }

    val lowAlerts = remember {
        listOf(AlertType.LOW, AlertType.VERY_LOW)
    }

    val predictiveAlerts = remember {
        listOf(
            AlertType.PRE_LOW,
            AlertType.PRE_HIGH,
            AlertType.PERSISTENT_HIGH
        )
    }

    val otherAlerts = remember {
        listOf(
            AlertType.MISSED_READING,
            AlertType.LOSS,
            AlertType.SENSOR_EXPIRY
        )
    }
    val customHighs = remember(customAlerts) { customAlerts.filter { it.type == CustomAlertType.HIGH } }
    val customLows = remember(customAlerts) { customAlerts.filter { it.type == CustomAlertType.LOW } }
    val talkerSummary = listOf(
        stringResource(R.string.speakglucose),
//        stringResource(R.string.speakmessages),
        stringResource(R.string.speakalarms)
    ).joinToString(" • ")

    // Track expanded states
    var expandedType by remember { mutableStateOf<AlertType?>(null) }
    // Track sound picker state (Generic: Current URI + AlertTypeId + Callback)
    var soundPickerRequest by remember { mutableStateOf<Triple<String?, Int, (String?) -> Unit>?>(null) }
    var notificationDismissAction by remember {
        mutableStateOf(AlertRepository.loadNotificationDismissAction())
    }
    fun persistNotificationDismissAction(action: AlertNotificationDismissAction) {
        notificationDismissAction = action
        AlertRepository.saveNotificationDismissAction(action)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.glucose_alerts_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // === GLOBAL ALERTS SECTION ===
            item(key = "global-alerts") {
                GlobalAlertSettingsCard(
                    allConfigs = configs,
                    hasCustomAlertsEnabled = customAlerts.any { it.enabled },
                    onMasterToggle = { enabled ->
                        configs.values.toList().forEach { config ->
                            persistConfigIfChanged(config.copy(enabled = enabled))
                        }
                        saveCustomAlerts(customAlerts.map { alert -> alert.copy(enabled = enabled) })
                    },
                    onApplyToAll = { draft ->
                        configs.values.toList().forEach { config ->
                            val updated = config.copy(
                                soundEnabled = draft.soundEnabled,
                                vibrationEnabled = draft.vibrationEnabled,
                                flashEnabled = draft.flashEnabled,
                                deliveryMode = draft.deliveryMode,
                                hapticProfile = draft.hapticProfile,
                                customSoundUri = draft.customSoundUri,
                                overrideDND = draft.overrideDND,
                                alarmDurationSeconds = draft.alarmDurationSeconds,
                                timeRangeEnabled = draft.timeRangeEnabled,
                                activeStartHour = draft.activeStartHour,
                                activeStartMinute = draft.activeStartMinute,
                                activeEndHour = draft.activeEndHour,
                                activeEndMinute = draft.activeEndMinute,
                                retryEnabled = draft.retryEnabled,
                                retryIntervalMinutes = draft.retryIntervalMinutes,
                                retryCount = draft.retryCount,
                                defaultSnoozeMinutes = draft.defaultSnoozeMinutes
                            )
                            persistConfigIfChanged(updated)
                        }
                        val updatedCustomAlerts = customAlerts.map { alert ->
                            alert.copy(
                                sound = draft.soundEnabled,
                                vibrate = draft.vibrationEnabled,
                                flash = draft.flashEnabled,
                                style = when (draft.deliveryMode) {
                                    AlertDeliveryMode.NOTIFICATION_ONLY -> "notification"
                                    AlertDeliveryMode.SYSTEM_ALARM -> "alarm"
                                    AlertDeliveryMode.BOTH -> "both"
                                },
                                hapticProfile = draft.hapticProfile.name.lowercase(),
                                durationSeconds = draft.alarmDurationSeconds,
                                overrideDnd = draft.overrideDND,
                                retryEnabled = draft.retryEnabled,
                                retryIntervalMinutes = draft.retryIntervalMinutes,
                                retryCount = draft.retryCount,
                                timeRangeEnabled = draft.timeRangeEnabled,
                                startTimeMinutes = (draft.activeStartHour ?: 0) * 60 + (draft.activeStartMinute ?: 0),
                                endTimeMinutes = (draft.activeEndHour ?: 0) * 60 + (draft.activeEndMinute ?: 0),
                                soundUri = draft.customSoundUri
                            )
                        }
                        saveCustomAlerts(updatedCustomAlerts)
                    },
                    onPickSound = { draft, updateDraft ->
                        soundPickerRequest = Triple(draft.customSoundUri, draft.type.id) { uri ->
                            updateDraft(draft.copy(customSoundUri = uri))
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // === HIGH ALERTS SECTION ===
            item(key = "high-alerts-header") {
                SectionHeader(
                    title = stringResource(R.string.high_alerts_title),
                    icon = Icons.AutoMirrored.Filled.TrendingUp // Using TrendingUp for Highs
                )
            }

            // Standard High Alerts
            items(highAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                val position = getCardPosition(type, highAlerts)

                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = position,
                    onToggle = { enabled ->
                        persistConfigIfChanged(config.copy(enabled = enabled))
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        persistConfigIfChanged(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = Triple(config.customSoundUri, config.type.id) { uri ->
                            persistConfigIfChanged(config.copy(customSoundUri = uri))
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // Custom High Alerts
            if (customHighs.isNotEmpty()) {
                item(key = "custom-highs-spacer") { Spacer(Modifier.height(4.dp)) }

                items(customHighs, key = { it.id }) { alert ->
                    var isExpanded by remember { mutableStateOf(false) }
                    val position = getCardPosition(alert, customHighs)
                    
                    CustomAlertCard(
                        alert = alert,
                        isMmol = isMmol,
                        isExpanded = isExpanded,
                        position = position,
                        onToggle = { enabled ->
                            upsertCustomAlert(alert.copy(enabled = enabled))
                        },
                        onExpand = { isExpanded = !isExpanded },
                        onUpdate = { updated ->
                            upsertCustomAlert(updated)
                        },
                        onDelete = {
                            removeCustomAlert(alert.id)
                        },
                        onPickSound = { config ->
                            soundPickerRequest = Triple(config.soundUri, if (config.type == CustomAlertType.HIGH) 1 else 0) { uri ->
                                val updated = config.copy(soundUri = uri)
                                upsertCustomAlert(updated)
                                soundPickerRequest = null
                            }
                        }
                    )
                }
            }

            // Add High Alert Button
            item(key = "add-high-alert") {
                AddCustomAlertButton(
                    text = stringResource(R.string.add_high_alert),
                    onClick = { createDefaultCustomAlert(CustomAlertType.HIGH) }
                )
            }

            // === LOW ALERTS SECTION ===
            item(key = "low-alerts-header") {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    title = stringResource(R.string.low_alerts_title),
                    icon = Icons.AutoMirrored.Filled.TrendingDown // Using TrendingDown for Lows
                )
            }

            // Standard Low Alerts
            items(lowAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                val position = getCardPosition(type, lowAlerts)

                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = position,
                    onToggle = { enabled ->
                        persistConfigIfChanged(config.copy(enabled = enabled))
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        persistConfigIfChanged(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = Triple(config.customSoundUri, config.type.id) { uri ->
                            persistConfigIfChanged(config.copy(customSoundUri = uri))
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // Custom Low Alerts
            if (customLows.isNotEmpty()) {
                item(key = "custom-lows-spacer") { Spacer(Modifier.height(4.dp)) }

                items(customLows, key = { it.id }) { alert ->
                    var isExpanded by remember { mutableStateOf(false) }
                    val position = getCardPosition(alert, customLows)
                    
                    CustomAlertCard(
                        alert = alert,
                        isMmol = isMmol,
                        isExpanded = isExpanded,
                        position = position,
                        onToggle = { enabled ->
                            upsertCustomAlert(alert.copy(enabled = enabled))
                        },
                        onExpand = { isExpanded = !isExpanded },
                        onUpdate = { updated ->
                            upsertCustomAlert(updated)
                        },
                        onDelete = {
                            removeCustomAlert(alert.id)
                        },
                        onPickSound = { config ->
                            soundPickerRequest = Triple(config.soundUri, if (config.type == CustomAlertType.LOW) 0 else 1) { uri ->
                                val updated = config.copy(soundUri = uri)
                                upsertCustomAlert(updated)
                                soundPickerRequest = null
                            }
                        }
                    )
                }
            }

            // Add Low Alert Button
            item(key = "add-low-alert") {
                AddCustomAlertButton(
                    text = stringResource(R.string.add_low_alert),
                    onClick = { createDefaultCustomAlert(CustomAlertType.LOW) }
                )
            }

            // === PREDICTIVE ALERTS SECTION ===
            item(key = "predictive-alerts-header") {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    title = stringResource(R.string.predictive_alerts),
                    icon = Icons.AutoMirrored.Filled.TrendingDown
                )
            }

            items(predictiveAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = getCardPosition(type, predictiveAlerts),
                    onToggle = { enabled ->
                        persistConfigIfChanged(config.copy(enabled = enabled))
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        persistConfigIfChanged(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = Triple(config.customSoundUri, config.type.id) { uri ->
                            persistConfigIfChanged(config.copy(customSoundUri = uri))
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // === OTHER ALERTS SECTION ===
            item(key = "other-alerts-header") {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    title = stringResource(R.string.other_alerts),
                    icon = Icons.Default.NotificationsActive
                )
            }

            items(otherAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = getCardPosition(type, otherAlerts),
                    onToggle = { enabled ->
                        persistConfigIfChanged(config.copy(enabled = enabled))
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        persistConfigIfChanged(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = Triple(config.customSoundUri, config.type.id) { uri ->
                            persistConfigIfChanged(config.copy(customSoundUri = uri))
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // === SNOOZE SECTION ===
            item(key = "preemptive-snooze") {
                Spacer(Modifier.height(24.dp))
                PreemptiveSnoozeCard()
            }

            item(key = "notification-dismiss-action") {
                Spacer(Modifier.height(8.dp))
                NotificationDismissActionPreference(
                    action = notificationDismissAction,
                    onActionChange = { persistNotificationDismissAction(it) }
                )
            }

            item(key = "talker-settings") {
                Spacer(Modifier.height(24.dp))
                SettingsItem(
                    title = stringResource(R.string.talker),
                    subtitle = talkerSummary,
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    showArrow = true,
                    position = SettingsItemPosition.SINGLE,
                    onClick = { navController.navigate("settings/alerts/talker") }
                )
            }

            // Bottom padding
            item(key = "bottom-padding") { Spacer(Modifier.height(100.dp)) }
        }

        // Custom Alert Dialogs
        // Removed showAddDialogType block

        if (alertToEdit != null) {
            CustomAlertDialog(
                initialConfig = alertToEdit,
                onDismiss = { alertToEdit = null },
                onSave = { updatedAlert ->
                    upsertCustomAlert(updatedAlert)
                }
            )
        }

        // Sound Picker Dialog
        if (soundPickerRequest != null) {
            val (current, alertTypeId, callback) = soundPickerRequest!!
            SoundPicker(
                currentUri = current,
                alertTypeId = alertTypeId,
                onSoundSelected = callback,
                onDismiss = { soundPickerRequest = null }
            )
        }
    }
}

@Composable
fun AddCustomAlertButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(50), // Fully rounded
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
fun CustomAlertCard(
    alert: CustomAlertConfig,
    isMmol: Boolean,
    isExpanded: Boolean,
    position: CardPosition,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onUpdate: (CustomAlertConfig) -> Unit,
    onDelete: () -> Unit,
    onPickSound: (CustomAlertConfig) -> Unit
) {
    // Determine icon/color based on type to match AlertCard style
    val icon = if (alert.type == CustomAlertType.HIGH) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown
    val isDark = isSystemInDarkTheme()
    val accentColor = if (alert.type == CustomAlertType.HIGH) {
        Color(GlucoseRangeColors.high(isDark))
    } else {
        Color(GlucoseRangeColors.low(isDark))
    }
    var draftName by rememberSaveable(alert.id) { mutableStateOf(alert.name) }
    LaunchedEffect(alert.id, alert.name) {
        if (draftName != alert.name) {
            draftName = alert.name
        }
    }
    LaunchedEffect(alert.id, draftName) {
        if (draftName == alert.name) {
            return@LaunchedEffect
        }
        delay(250)
        if (draftName != alert.name) {
            onUpdate(alert.copy(name = draftName))
        }
    }
    
    // For Title/Subtitle
    val title = alert.name.ifEmpty { if (alert.type == CustomAlertType.HIGH) stringResource(R.string.high_alert) else stringResource(R.string.low_alert) }
    val subtitle = buildString {
        append(formatThreshold(alert.threshold, isMmol))
        
        // Add time range if enabled
        if (alert.timeRangeEnabled) {
            append(" • ")
            val startHour = alert.startTimeMinutes / 60
            val startMin = alert.startTimeMinutes % 60
            val endHour = alert.endTimeMinutes / 60
            val endMin = alert.endTimeMinutes % 60
            append(String.format("%02d:%02d-%02d:%02d", startHour, startMin, endHour, endMin))
        }
        
        if (!alert.enabled) {
            append(" • ${stringResource(R.string.disabled_status)}")
        }
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "customAlertChevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = if (alert.enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            // Main row (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Colored icon container (Identical to AlertCard)
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
                    }
                }

                // Title and subtitle
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Chevron Indicator
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(R.string.collapse_action) else stringResource(R.string.expand_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
                
                Spacer(Modifier.width(16.dp))

                // Enable/Disable switch
                StyledSwitch(
                    checked = alert.enabled,
                    onCheckedChange = onToggle
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(vertical = 16.dp)
                ) {
                    // Map to AlertConfig for shared UI component
                    val genericConfig = AlertConfig(
                        type = if (alert.type == CustomAlertType.HIGH) AlertType.HIGH else AlertType.LOW,
                        enabled = alert.enabled,
                        threshold = alert.threshold,
                        soundEnabled = alert.sound,
                        customSoundUri = alert.soundUri,
                        vibrationEnabled = alert.vibrate,
                        flashEnabled = alert.flash,
                        hapticProfile = customHapticProfile(alert.hapticProfile),
                        alarmDurationSeconds = sanitizeAlertDurationSeconds(alert.durationSeconds),
                        deliveryMode = when(alert.style.lowercase()) {
                            "alarm", "system_alarm" -> AlertDeliveryMode.SYSTEM_ALARM
                            "both" -> AlertDeliveryMode.BOTH
                            "notification" -> AlertDeliveryMode.NOTIFICATION_ONLY
                            else -> AlertDeliveryMode.SYSTEM_ALARM
                        },
                        overrideDND = alert.overrideDnd,
                        retryEnabled = alert.retryEnabled,
                        retryIntervalMinutes = alert.retryIntervalMinutes,
                        retryCount = alert.retryCount,
                        timeRangeEnabled = alert.timeRangeEnabled,
                        activeStartHour = alert.startTimeMinutes / 60,
                        activeStartMinute = alert.startTimeMinutes % 60,
                        activeEndHour = alert.endTimeMinutes / 60,
                        activeEndMinute = alert.endTimeMinutes % 60
                    )
                    // Delete Button
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_custom_alert))
                    }
                    Spacer(Modifier.height(8.dp))

                    // Render common settings
                    CommonAlertSettings(
                        config = genericConfig,
                        onConfigChange = { newConfig ->
                            val updated = alert.copy(
                                enabled = newConfig.enabled,
                                threshold = newConfig.threshold ?: alert.threshold,
                                sound = newConfig.soundEnabled,
                                soundUri = newConfig.customSoundUri,
                                vibrate = newConfig.vibrationEnabled,
                                flash = newConfig.flashEnabled,
                                style = when(newConfig.deliveryMode) {
                                    AlertDeliveryMode.SYSTEM_ALARM -> "alarm"
                                    AlertDeliveryMode.BOTH -> "both"
                                    else -> "notification"
                                },
                                hapticProfile = newConfig.hapticProfile.name.lowercase(),
                                durationSeconds = sanitizeAlertDurationSeconds(newConfig.alarmDurationSeconds),
                                overrideDnd = newConfig.overrideDND,
                                retryEnabled = newConfig.retryEnabled,
                                retryIntervalMinutes = newConfig.retryIntervalMinutes,
                                retryCount = newConfig.retryCount,
                                timeRangeEnabled = newConfig.timeRangeEnabled,
                                startTimeMinutes = (newConfig.activeStartHour ?: 0) * 60 + (newConfig.activeStartMinute ?: 0),
                                endTimeMinutes = (newConfig.activeEndHour ?: 0) * 60 + (newConfig.activeEndMinute ?: 0)
                            )
                            onUpdate(updated)
                        },
                        onPickSound = { onPickSound(alert) },
                        onTest = {
                             Notify.testCustomTrigger(
                                alert.soundUri,
                                alert.sound,
                                alert.vibrate,
                                alert.flash,
                                alert.type == CustomAlertType.HIGH,
                                alert.style,
                                alert.hapticProfile,
                                sanitizeAlertDurationSeconds(alert.durationSeconds),
                                alert.overrideDnd,
                                alert.id,
                                alert.name
                            )
                        },
                        headerContent = {
                            // Name Field
                            OutlinedTextField(
                                value = draftName,
                                onValueChange = { draftName = it },
                                label = { Text(stringResource(R.string.alert_name)) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            
                            // Threshold Slider using generic UI
                             ThresholdSlider(
                                label = stringResource(R.string.threshold_label),
                                value = alert.threshold,
                                isMmol = isMmol,
                                range = if (isMmol) {
                                     // mmol/L Ranges
                                     if (alert.type == CustomAlertType.HIGH) 4.0f..28.0f else 2.0f..6.0f
                                } else {
                                     // mg/dL Ranges
                                     if (alert.type == CustomAlertType.HIGH) 70f..500f else 40f..110f
                                },
                                onValueChange = { onUpdate(alert.copy(threshold = it)) }
                            )
                        }
                    )
                    


                }
            }
        }
    }
}






@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

enum class CardPosition { TOP, MIDDLE, BOTTOM, SINGLE }

private fun <T> getCardPosition(item: T, list: List<T>): CardPosition {
    val index = list.indexOf(item)
    return when {
        list.size == 1 -> CardPosition.SINGLE
        index == 0 -> CardPosition.TOP
        index == list.lastIndex -> CardPosition.BOTTOM
        else -> CardPosition.MIDDLE
    }
}

private fun cardShape(position: CardPosition, radius: androidx.compose.ui.unit.Dp = 12.dp): RoundedCornerShape {
    return when (position) {
        CardPosition.SINGLE -> RoundedCornerShape(radius)
        CardPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 4.dp, bottomEnd = 4.dp)
        CardPosition.MIDDLE -> RoundedCornerShape(4.dp)
        CardPosition.BOTTOM -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = radius, bottomEnd = radius)
    }
}

private fun customHapticProfile(value: String): HapticProfile {
    return runCatching { HapticProfile.valueOf(value.uppercase()) }.getOrElse {
        when (value.lowercase()) {
            "soft", "low", "silent" -> HapticProfile.SOFT
            "steady", "medium" -> HapticProfile.STEADY
            "escalating", "ascending" -> HapticProfile.ESCALATING
            else -> HapticProfile.STRONG
        }
    }
}

@Composable
private fun AlertCard(
    config: AlertConfig,
    isMmol: Boolean,
    isExpanded: Boolean,
    position: CardPosition,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onConfigChange: (AlertConfig) -> Unit,
    onPickSound: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val (icon, accentColor) = getAlertIconAndColor(config.type, isDark)
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "standardAlertChevron"
    )


    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = if (config.enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            // Main row (always visible) - minimum 72dp touch target
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Colored icon container
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 0.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = accentColor.copy(alpha = 0.12f)
                )
                {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp),

                            )
                    }
                }

                // Title and subtitle
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = stringResource(config.type.nameResId),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val subtitle = buildString {
                        config.threshold?.let { append(formatThreshold(it, isMmol)) }
                        config.durationMinutes?.let {
                            if (isNotEmpty()) append(" • ")
                            append(stringResource(R.string.minutes_short_format, it))
                        }
                        // Add time range if enabled
                        if (config.timeRangeEnabled) {
                            if (isNotEmpty()) append(" • ")
                            val startH = config.activeStartHour ?: 0
                            val startM = config.activeStartMinute ?: 0
                            val endH = config.activeEndHour ?: 0
                            val endM = config.activeEndMinute ?: 0
                            append(String.format("%02d:%02d-%02d:%02d", startH, startM, endH, endM))
                        }
                        if (!config.enabled) {
                            if (isNotEmpty()) append(" • ")
                            append(stringResource(R.string.disabled_status))
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Chevron Indicator (Before Switch)
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(R.string.collapse_action) else stringResource(R.string.expand_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )

                Spacer(Modifier.width(16.dp))

                // Enable/Disable switch
                StyledSwitch(
                    checked = config.enabled,
                    onCheckedChange = onToggle
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    AlertSettingsExpanded(
                        config = config,
                        isMmol = isMmol,
                        onConfigChange = onConfigChange,
                        onTest = {
                            // Use Notify.testTrigger to simulate real alarm flow
                            Notify.testTrigger(config.type.id)
                        },
                        onPickSound = onPickSound
                    )
                }
            }
        }
    }
}

/**
 * Test an alert by playing its sound/vibration briefly.
 */

@Composable
private fun AlertSettingsExpanded(
    config: AlertConfig,
    isMmol: Boolean,
    onConfigChange: (AlertConfig) -> Unit,
    onTest: () -> Unit,
    onPickSound: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(vertical = 16.dp)
    ) {
        CommonAlertSettings(
            config = config,
            onConfigChange = onConfigChange,
            onPickSound = { onPickSound() },
            onTest = onTest,
            headerContent = {
                 // === Threshold Section (If applicable) ===
                if (config.threshold != null) {
                    ThresholdSlider(
                        label = stringResource(R.string.threshold_label),
                        value = config.threshold,
                        isMmol = isMmol,
                        range = getThresholdRange(config.type, isMmol),
                        onValueChange = { onConfigChange(config.copy(threshold = it)) }
                    )
                }
                
                // === Durations Section (If applicable) ===
                val showGenericDuration = config.durationMinutes != null &&
                    config.type != AlertType.LOW && config.type != AlertType.VERY_LOW
                if (showGenericDuration || config.forecastMinutes != null) {
                     Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showGenericDuration) {
                            config.durationMinutes?.let {
                                Box(Modifier.weight(1f)) {
                                    DurationSlider(
                                        label = stringResource(R.string.alert_after),
                                        value = it,
                                        range = 5..120,
                                        stepSize = 5,
                                        onValueChange = { v -> onConfigChange(config.copy(durationMinutes = v)) }
                                    )
                                }
                            }
                        }
                        config.forecastMinutes?.let {
                            Box(Modifier.weight(1f)) {
                                DurationSlider(
                                    label = stringResource(R.string.look_ahead),
                                    value = it,
                                    range = 10..60,
                                    stepSize = 5,
                                    onValueChange = { v -> onConfigChange(config.copy(forecastMinutes = v)) }
                                )
                            }
                        }
                    }
                }

                // === Sustained Low Toggle (LOW / VERY_LOW only) ===
                if (config.type == AlertType.LOW || config.type == AlertType.VERY_LOW) {
                    val sustainedEnabled = config.durationMinutes != null
                    val otherDurationActive = config.forecastMinutes != null
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    stringResource(R.string.sustained_low_toggle),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.sustained_low_toggle_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StyledSwitch(
                                checked = sustainedEnabled,
                                onCheckedChange = { on ->
                                    if (on) {
                                        val seed = config.durationMinutes ?: 10
                                        onConfigChange(config.copy(durationMinutes = seed))
                                    } else if (!otherDurationActive) {
                                        onConfigChange(config.copy(durationMinutes = null))
                                    }
                                }
                            )
                        }
                        if (sustainedEnabled) {
                            DurationSlider(
                                label = stringResource(R.string.sustained_low_duration),
                                value = config.durationMinutes ?: 10,
                                range = 5..30,
                                stepSize = 5,
                                onValueChange = { v -> onConfigChange(config.copy(durationMinutes = v)) }
                            )
                        }
                    }
                }
            }
        )
    }
}
        
//        HorizontalDivider()
        


@Composable
private fun ThresholdSlider(
    label: String,
    value: Float,
    isMmol: Boolean,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    // Calculate step size based on range and unit
    // Lower ranges need finer control (0.1 mmol or 1 mg/dL)
    val stepSize = if (isMmol) {
        if (range.start < 5f) 0.1f else 0.2f  // Finer steps for low values
    } else {
        if (range.start < 100f) 1f else 5f   // Finer steps for low values
    }
    val steps = ((range.endInclusive - range.start) / stepSize).toInt() - 1

    Column {
        var sliderValue by remember { mutableStateOf(value) }
        LaunchedEffect(value) {
            sliderValue = value
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                // Use LOCAL sliderValue for real-time updates
                formatThreshold(sliderValue, isMmol),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val rounded = (kotlin.math.round(sliderValue / stepSize) * stepSize)
                onValueChange(rounded)
            },
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                activeTickColor = MaterialTheme.colorScheme.onPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        )
    }
}

/**
 * Duration slider with snapping to useful values.
 */
@Composable
internal fun DurationSlider(
    label: String,
    value: Int,
    range: IntRange,
    stepSize: Int = 5,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueText: @Composable (Int) -> String = { stringResource(R.string.minutes_short_format, it) }
) {
    val steps = if (stepSize <= 1) {
        0
    } else {
        ((range.last - range.first) / stepSize) - 1
    }

    Column(modifier = modifier) {
        var sliderValue by remember { mutableStateOf(value.toFloat()) }
        LaunchedEffect(value) {
            sliderValue = value.toFloat()
        }

        // Calculate display value based on current slider position, rounded to step
        val displayValue = (kotlin.math.round(sliderValue / stepSize) * stepSize).toInt()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText(displayValue),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val rounded = (kotlin.math.round(sliderValue / stepSize) * stepSize).toInt()
                onValueChange(rounded)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DeliveryModeSelector(
    mode: AlertDeliveryMode,
    onModeChange: (AlertDeliveryMode) -> Unit
) {
    Column {
        Text(
            stringResource(R.string.alert_style),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val deliveryModeLabels = AlertDeliveryMode.entries.associateWith { it.localizedName() }
            AlertDeliveryMode.entries.forEach { deliveryMode ->
                FilterChip(
                    selected = mode == deliveryMode,
                    onClick = { onModeChange(deliveryMode) },
                    label = { Text(deliveryModeLabels[deliveryMode] ?: deliveryMode.displayName) },
                    leadingIcon = if (mode == deliveryMode) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SnoozeDurationSelector(
    minutes: Int,
    onMinutesChange: (Int) -> Unit
) {
    DurationSlider(
        label = stringResource(R.string.default_snooze),
        value = minutes,
        range = 5..120,
        stepSize = 5,
        onValueChange = onMinutesChange
    )
}

@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PreemptiveSnoozeCard() {
    var showDialog by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier
                .clickable { showDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Snooze,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.preemptive_snooze),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    stringResource(R.string.preemptive_snooze_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
    
    if (showDialog) {
        PreemptiveSnoozeDialog(
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun NotificationDismissActionPreference(
    action: AlertNotificationDismissAction,
    onActionChange: (AlertNotificationDismissAction) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.NotificationsOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notification_dismiss_action_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.notification_dismiss_action_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val actionLabels = AlertNotificationDismissAction.entries.associateWith { it.localizedName() }
            ConnectedButtonGroup(
                options = AlertNotificationDismissAction.entries,
                selectedOption = action,
                onOptionSelected = onActionChange,
                labelText = { actionLabels[it] ?: it.name },
                label = {
                    Text(
                        text = actionLabels[it] ?: it.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                },
                icon = { option ->
                    if (option == action) Icons.Default.Check else null
                },
                modifier = Modifier.fillMaxWidth(),
                itemHeight = 40.dp,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.74f),
                selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.56f),
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreemptiveSnoozeDialog(
    onDismiss: () -> Unit
) {
    var snoozeLow by remember { mutableStateOf(false) }
    var snoozeHigh by remember { mutableStateOf(false) }
    var snoozeDuration by remember { mutableStateOf(30) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Snooze, contentDescription = null) },
        title = { Text(stringResource(R.string.preemptive_snooze)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.preemptive_snooze_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    FilterChip(
                        selected = snoozeLow,
                        onClick = { snoozeLow = !snoozeLow },
                        label = { Text(stringResource(R.string.low_alerts)) },
                        leadingIcon = if (snoozeLow) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = snoozeHigh,
                        onClick = { snoozeHigh = !snoozeHigh },
                        label = { Text(stringResource(R.string.high_alerts)) },
                        leadingIcon = if (snoozeHigh) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                }
                
                DurationSlider(
                    label = stringResource(R.string.duration_label),
                    value = snoozeDuration,
                    range = 15..120,
                    stepSize = 15,
                    onValueChange = { snoozeDuration = it }
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (snoozeLow || snoozeHigh) {
                        SnoozeManager.preemptiveSnooze(snoozeLow, snoozeHigh, snoozeDuration)
                    }
                    onDismiss()
                },
                enabled = snoozeLow || snoozeHigh
            ) {
                Text(stringResource(R.string.snooze))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// === NEW: Time Range Settings ===
// === NEW: Time Range Settings ===
@Composable
internal fun TimeRangeSettings(
    enabled: Boolean,
    startHour: Int?,
    startMinute: Int?,
    endHour: Int?,
    endMinute: Int?,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int, Int) -> Unit,  // (hour, minute)
    onEndChange: (Int, Int) -> Unit      // (hour, minute)
) {
    val startH = startHour ?: 22
    val startM = startMinute ?: 0
    val endH = endHour ?: 8
    val endM = endMinute ?: 0
    
    Column {
        ExpressiveExpandableHeader(
            icon = Icons.Default.Schedule,
            title = stringResource(R.string.active_time_range_title),
            subtitle = if (enabled) stringResource(R.string.time_range_summary, formatTime(startH, startM), formatTime(endH, endM)) else stringResource(R.string.only_alert_during_hours),
            enabled = enabled,
            onEnabledChange = onEnabledChange,
            iconTint = MaterialTheme.colorScheme.tertiary
        )
        
        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(animationSpec = tween(durationMillis = 200)) + fadeIn(),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 200)) + fadeOut()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
                // === VISUAL TIMELINE ===
                TimelineVisualization(
                    startHour = startH,
                    startMinute = startM,
                    endHour = endH,
                    endMinute = endM
                )
                
                Spacer(Modifier.height(16.dp))
                
                // === RANGE SLIDER (hours only) ===
                TimeRangeSlider(
                    startHour = startH,
                    endHour = endH,
                    onStartChange = { hour -> onStartChange(hour, startM) },
                    onEndChange = { hour -> onEndChange(hour, endM) }
                )
                
                Spacer(Modifier.height(12.dp))
                
                // === TIME CHIPS (precise time with minutes) ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeChip(
                        label = stringResource(R.string.start),
                        hour = startH,
                        minute = startM,
                        onTimeChange = onStartChange
                    )
                    
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    
                    TimeChip(
                        label = stringResource(R.string.end),
                        hour = endH,
                        minute = endM,
                        onTimeChange = onEndChange
                    )
                }
                
                // === DESCRIPTION ===
                Spacer(Modifier.height(8.dp))
                val startMins = startH * 60 + startM
                val endMins = endH * 60 + endM
                Text(
                    text = if (startMins < endMins) {
                        stringResource(R.string.alert_active_from_to, formatTime(startH, startM), formatTime(endH, endM))
                    } else {
                        stringResource(R.string.alert_active_from_to_next_day, formatTime(startH, startM), formatTime(endH, endM))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TimelineVisualization(
    startHour: Int,
    startMinute: Int = 0,
    endHour: Int,
    endMinute: Int = 0
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
    
    // Convert to fraction of day (0-1)
    val startFraction = (startHour * 60 + startMinute) / 1440f
    val endFraction = (endHour * 60 + endMinute) / 1440f
    
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        val width = size.width
        val height = size.height
        
        // Background track
        drawRoundRect(
            color = surfaceColor,
            cornerRadius = CornerRadius(height / 2, height / 2)
        )
        
        if (startFraction < endFraction) {
            // Normal range (e.g., 8:00 to 22:00)
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(width * startFraction, 0f),
                size = Size(width * (endFraction - startFraction), height),
                cornerRadius = CornerRadius(height / 2, height / 2)
            )
        } else {
            // Overnight range (e.g., 22:00 to 8:00)
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(width * startFraction, 0f),
                size = Size(width * (1f - startFraction), height),
                cornerRadius = CornerRadius(height / 2, height / 2)
            )
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(0f, 0f),
                size = Size(width * endFraction, height),
                cornerRadius = CornerRadius(height / 2, height / 2)
            )
        }
        
        // Hour markers
        for (h in listOf(0, 6, 12, 18)) {
            val x = width * (h / 24f)
            drawLine(
                color = surfaceColor.copy(alpha = 0.5f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 2f
            )
        }
    }
    
    // Hour labels
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangeSlider(
    startHour: Int,
    endHour: Int,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit
) {
    var sliderValues by remember(startHour, endHour) {
        mutableStateOf(startHour.toFloat()..endHour.toFloat())
    }

    RangeSlider(
        value = sliderValues,
        onValueChange = { range ->
            sliderValues = range
        },
        onValueChangeFinished = {
            val newStart = sliderValues.start.toInt().coerceIn(0, 23)
            val newEnd = sliderValues.endInclusive.toInt().coerceIn(0, 23)
            if (newStart != startHour) {
                onStartChange(newStart)
            }
            if (newEnd != endHour) {
                onEndChange(newEnd)
            }
        },
        valueRange = 0f..23f,
        steps = 22, // 24 hours - 1 for steps
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChip(
    label: String,
    hour: Int,
    minute: Int = 0,
    onTimeChange: (Int, Int) -> Unit
) {
    // Ensure values are in valid range to prevent crashes
    val safeHour = hour.coerceIn(0, 23)
    val safeMinute = minute.coerceIn(0, 59)
    
    var showTimePicker by remember { mutableStateOf(false) }
    val timePickerState = rememberTimePickerState(
        initialHour = safeHour,
        initialMinute = safeMinute,
        is24Hour = true
    )
    
    // Time chip button
    Surface(
        onClick = { showTimePicker = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = formatTime(hour, minute),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
    
    // Time picker dialog
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.select_time_for_label, label)) },
            text = {
                TimePicker(
                    state = timePickerState,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun formatTime(hour: Int, minute: Int): String = String.format("%02d:%02d", hour, minute)
private fun formatHour(hour: Int): String = String.format("%02d:00", hour)

// Keep for backward compatibility
internal fun formatTimeRange(start: Int, end: Int): String {
    return "${formatHour(start)}–${formatHour(end)}"
}

// === NEW: Retry Settings ===
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RetrySettings(
    enabled: Boolean,
    intervalMinutes: Int,
    retryCount: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCountChange: (Int) -> Unit
) {
    val retrySubtitle = if (enabled) {
        when {
            intervalMinutes <= 0 && retryCount == 0 -> stringResource(R.string.retry_summary_constant_forever)
            intervalMinutes <= 0 -> stringResource(R.string.retry_summary_constant, retryCount)
            retryCount == 0 -> stringResource(R.string.retry_summary_forever, intervalMinutes)
            else -> stringResource(R.string.retry_summary, intervalMinutes, retryCount)
        }
    } else {
        stringResource(R.string.realert_if_not_dismissed)
    }

    Column {
        ExpressiveExpandableHeader(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.retry_if_no_reaction),
            subtitle = retrySubtitle,
            enabled = enabled,
            onEnabledChange = onEnabledChange,
            iconTint = MaterialTheme.colorScheme.error
        )
        
        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(animationSpec = tween(durationMillis = 200)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 200))
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                Text(stringResource(R.string.retry_every), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = intervalMinutes <= 0,
                        onClick = { onIntervalChange(0) },
                        label = {
                            Text(
                                text = stringResource(R.string.retry_constantly),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    listOf(1, 2, 3, 5, 10, 15).forEach { minutes ->
                        FilterChip(
                            selected = intervalMinutes == minutes,
                            onClick = { onIntervalChange(minutes) },
                            label = { Text(stringResource(R.string.minutes_short_format, minutes)) }
                        )
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                Text(stringResource(R.string.max_retries), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0 to stringResource(R.string.retry_forever), 1 to "1", 2 to "2", 3 to "3", 5 to "5").forEach { (count, label) ->
                        FilterChip(
                            selected = retryCount == count,
                            onClick = { onCountChange(count) },
                            label = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---- Helper functions ----

private fun getAlertIconAndColor(type: AlertType, isDark: Boolean): Pair<ImageVector, Color> {
    return when (type) {
        AlertType.VERY_LOW -> Icons.Default.Warning to Color(GlucoseRangeColors.veryLow(isDark))
        AlertType.LOW -> Icons.Default.ArrowDownward to Color(GlucoseRangeColors.low(isDark))
        AlertType.HIGH -> Icons.Default.ArrowUpward to Color(GlucoseRangeColors.high(isDark))
        AlertType.VERY_HIGH -> Icons.Default.Warning to Color(GlucoseRangeColors.veryHigh(isDark))
        AlertType.PRE_LOW -> Icons.AutoMirrored.Filled.TrendingDown to Color(GlucoseRangeColors.low(isDark))
        AlertType.PRE_HIGH -> Icons.AutoMirrored.Filled.TrendingUp to Color(GlucoseRangeColors.high(isDark))
        AlertType.PERSISTENT_HIGH -> Icons.Default.Timer to Color(GlucoseRangeColors.veryHigh(isDark))
        AlertType.MISSED_READING -> Icons.Default.SignalWifiOff to Color(0xFF78909C)
        AlertType.LOSS -> Icons.Default.BluetoothDisabled to Color(0xFF90A4AE)
        AlertType.SENSOR_EXPIRY -> Icons.Default.Schedule to Color(0xFF7E57C2)
        else -> Icons.Default.Notifications to Color(0xFF42A5F5)  // Default blue
    }
}

private fun formatThreshold(value: Float, isMmol: Boolean): String {
    return if (isMmol) {
        String.format("%.1f", value)
    } else {
        String.format("%.0f", value)
    }
}

private fun getThresholdRange(type: AlertType, isMmol: Boolean): ClosedFloatingPointRange<Float> {
    return when (type) {
        AlertType.VERY_LOW -> if (isMmol) 2.0f..4.0f else 36f..70f
        AlertType.LOW -> if (isMmol) 3.0f..7f else 54f..100f
        AlertType.HIGH -> if (isMmol) 6.0f..14.0f else 126f..270f
        AlertType.VERY_HIGH -> if (isMmol) 10.0f..20.0f else 180f..360f
        AlertType.PRE_LOW -> if (isMmol) 3f..7.0f else 63f..108f
        AlertType.PRE_HIGH -> if (isMmol) 5.0f..10.0f else 126f..252f
        AlertType.PERSISTENT_HIGH -> if (isMmol) 7.0f..15.0f else 126f..270f
        else -> if (isMmol) 2.0f..20.0f else 36f..360f
    }
}


@Composable
internal fun SoundSelector(
    currentUri: String?,
    onSoundClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.soundname),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedCard(
            onClick = onSoundClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                   Text(
                        text = when {
                            currentUri.isNullOrEmpty() -> stringResource(R.string.app_default_sound)
                            currentUri == SYSTEM_DEFAULT_SOUND -> stringResource(R.string.system_default_sound)
                            else -> stringResource(R.string.custom_sound_selected)
                        },
                        style = MaterialTheme.typography.bodyLarge
                   )
                   if (!currentUri.isNullOrEmpty()) {
                       Text(
                           text = stringResource(R.string.tap_to_change),
                           style = MaterialTheme.typography.bodySmall,
                           color = MaterialTheme.colorScheme.onSurfaceVariant
                       )
                   }
                }
            }
        }
    }
}

// === M3 EXPRESSIVE TOGGLE COMPONENTS ===

/**
 * M3 Expressive styled toggle card for simple on/off settings.
 * Features: tinted icon background, title, optional subtitle, and switch.
 */
@Composable
internal fun ExpressiveToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (checked) 
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
        else 
            MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (checked) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with tinted background
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = iconTint.copy(alpha = if (checked) 0.2f else 0.1f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (checked) iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(Modifier.width(12.dp))
            
            // Switch
            StyledSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
/**
 * M3 styled header for expandable settings sections.
 * Full-width clickable row following M3 list item guidelines.
 */
@Composable
internal fun ExpressiveExpandableHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 8.dp),  // Touch reaches edges, content padded
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Simple icon with tint
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(Modifier.width(16.dp))
        
        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(Modifier.width(8.dp))
        
        // Switch
        StyledSwitch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

/**
 * Simple clickable toggle row for settings.
 * Full-width clickable row following M3 list item guidelines.
 */
@Composable
internal fun ClickableToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),  // Touch reaches edges, content padded
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Simple icon with tint
        Icon(
            icon,
            contentDescription = null,
            tint = if (checked) iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(Modifier.width(16.dp))
        
        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(Modifier.width(8.dp))
        
        // Switch
        StyledSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
