@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.QrCodeScanner

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import tk.glucodata.data.meal.MealContributionLog
import tk.glucodata.data.meal.MealContributionSettings
import tk.glucodata.R
import tk.glucodata.data.journal.JournalBuiltInCurveProfile
import tk.glucodata.data.journal.JournalCurvePoint
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalInsulinPresetInput
import tk.glucodata.data.journal.LegacyJournalFoodDatabase
import tk.glucodata.data.journal.builtInJournalCurve
import tk.glucodata.data.journal.normalizeJournalCurvePoints
import tk.glucodata.data.journal.serializeJournalCurve
import tk.glucodata.ui.alerts.AddCustomAlertButton
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.ColorSwatchButton
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.ExpressiveColorPickerDialog
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class JournalPresetDraft(
    val id: Long? = null,
    val displayName: String = "",
    val accentColor: Int = DEFAULT_PRESET_COLOR,
    val curvePoints: List<JournalCurvePoint> = emptyList(),
    val sortOrder: Int = Int.MAX_VALUE,
    val isBuiltIn: Boolean = false,
    val isArchived: Boolean = false,
    val countsTowardIob: Boolean = true,
    val useForCalculation: Boolean = true
)

private data class JournalFoodDraft(
    val id: Long? = null,
    val displayName: String = "",
    val carbsText: String = "",
    val proteinText: String = "",
    val fatText: String = "",
    val absorptionText: String = "90",
    val accentColor: Int = DEFAULT_FOOD_COLOR,
    val sortOrder: Int = Int.MAX_VALUE,
    val isBuiltIn: Boolean = false,
    val isArchived: Boolean = false
)

@Composable
fun JournalSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val journalNavigationTabEnabled by viewModel.journalNavigationTabEnabled.collectAsState()
    val journalDoseCalculatorEnabled by viewModel.journalDoseCalculatorEnabled.collectAsState()
    val journalFoodMacrosEnabled by viewModel.journalFoodMacrosEnabled.collectAsState()
    val journalFoodLibraryEnabled by viewModel.journalFoodLibraryEnabled.collectAsState()
    val journalEiobDisplayEnabled by viewModel.journalEiobDisplayEnabled.collectAsState()
    val journalQuickAddAlwaysNow by viewModel.journalQuickAddAlwaysNow.collectAsState()
    val journalMealOnlineLookup by viewModel.journalMealOnlineLookup.collectAsState()
    val journalDashboardQuickAddButton by viewModel.journalDashboardQuickAddButton.collectAsState()
    val journalHealthConnectActivityEnabled by viewModel.journalHealthConnectActivityEnabled.collectAsState()
    val aapsJournalImportEnabled by viewModel.aapsJournalImportEnabled.collectAsState()
    val predictionModelProfile by viewModel.predictionModelProfile.collectAsState()
    val allPresets by viewModel.journalInsulinPresets.collectAsState()
    val allFoods by viewModel.journalFoods.collectAsState()
    val activePresets = remember(allPresets) { allPresets.filter { !it.isArchived } }
    val activeFoods = remember(allFoods) { allFoods.filter { !it.isArchived } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_manage_title)) },
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
            item(key = "gate") {
                MasterSwitchCard(
                    title = stringResource(R.string.journal_title),
                    subtitle = stringResource(R.string.journal_gate_desc),
                    checked = journalEnabled,
                    onCheckedChange = { viewModel.setJournalEnabled(it) },
                    icon = Icons.Default.Vaccines,
                    iconTint = MaterialTheme.colorScheme.tertiary
                )
            }

            item(key = "journal_access_group") {
                val showCurrentTimeOption = journalEnabled && journalDashboardQuickAddButton
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.journal_show_tab_title),
                        subtitle = stringResource(R.string.journal_show_tab_desc),
                        checked = journalNavigationTabEnabled,
                        onCheckedChange = { viewModel.setJournalNavigationTabEnabled(it) },
                        icon = Icons.Default.EditNote,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.TOP,
                        enabled = journalEnabled
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.journal_dashboard_quickadd_title),
                        subtitle = stringResource(R.string.journal_dashboard_quickadd_desc),
                        checked = journalDashboardQuickAddButton,
                        onCheckedChange = { viewModel.setJournalDashboardQuickAddButton(it) },
                        icon = Icons.Default.Add,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = if (showCurrentTimeOption) CardPosition.MIDDLE else CardPosition.BOTTOM,
                        enabled = journalEnabled
                    )
                    AnimatedVisibility(
                        visible = showCurrentTimeOption,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SettingsSwitchItem(
                            title = stringResource(R.string.journal_quickadd_always_now_title),
                            subtitle = stringResource(R.string.journal_quickadd_always_now_desc),
                            checked = journalQuickAddAlwaysNow,
                            onCheckedChange = { viewModel.setJournalQuickAddAlwaysNow(it) },
                            icon = Icons.Default.History,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            position = CardPosition.BOTTOM,
                            enabled = journalEnabled
                        )
                    }
                }
            }
            item(key = "open_journal") {
                JournalActionRow(
                    onHistoryClick = {
                        navController.navigate(
                            if (journalNavigationTabEnabled) "journal" else "settings/journal/history"
                        )
                    }
                )
            }
            item(key = "journal_intelligence") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.journal_dose_math_title),
                        subtitle = stringResource(R.string.journal_dose_calculator_desc),
                        checked = journalDoseCalculatorEnabled,
                        onCheckedChange = { viewModel.setJournalDoseCalculatorEnabled(it) },
                        icon = Icons.Default.Calculate,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.TOP,
                        enabled = journalEnabled
                    )
                    SettingsItem(
                        title = stringResource(R.string.predictive_model_tuning),
                        subtitle = if (predictionModelProfile.blocks.size == 1) {
                            stringResource(R.string.predictive_model_profile_summary_single)
                        } else {
                            stringResource(
                                R.string.predictive_model_profile_summary_count,
                                predictionModelProfile.blocks.size
                            )
                        },
                        showArrow = true,
                        onClick = if (journalEnabled) {
                            { navController.navigate("settings/predictive-simulation/model-profile") }
                        } else {
                            null
                        },
                        icon = Icons.Default.Schedule,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.MIDDLE
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.journal_food_macros_title),
                        subtitle = stringResource(R.string.journal_food_macros_desc),
                        checked = journalFoodMacrosEnabled,
                        onCheckedChange = { viewModel.setJournalFoodMacrosEnabled(it) },
                        icon = Icons.Default.LunchDining,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.MIDDLE,
                        enabled = journalEnabled
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.journal_eiob_display_title),
                        subtitle = stringResource(R.string.journal_eiob_display_desc),
                        checked = journalEiobDisplayEnabled,
                        onCheckedChange = { viewModel.setJournalEiobDisplayEnabled(it) },
                        icon = Icons.Default.Vaccines,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.MIDDLE,
                        enabled = journalEnabled
                    )
                    SettingsItem(
                        title = stringResource(R.string.journal_import_health_activity),
                        onClick = if (journalEnabled) {
                            { viewModel.setJournalHealthConnectActivityEnabled(!journalHealthConnectActivityEnabled) }
                        } else {
                            null
                        },
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        trailingContent = {
                            StyledSwitch(
                                checked = journalHealthConnectActivityEnabled,
                                onCheckedChange = { viewModel.setJournalHealthConnectActivityEnabled(it) },
                                enabled = journalEnabled
                            )
                        },
                        position = CardPosition.MIDDLE
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.journal_aaps_import_title),
                        subtitle = stringResource(R.string.journal_aaps_import_desc),
                        checked = aapsJournalImportEnabled,
                        onCheckedChange = { viewModel.setAapsJournalImportEnabled(it) },
                        icon = Icons.Default.Restore,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.BOTTOM,
                        enabled = journalEnabled
                    )
                }
            }

            item(key = "journal_libraries") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsItem(
                        title = stringResource(R.string.journal_food_library),
                        subtitle = stringResource(
                            R.string.journal_food_library_count,
                            if (journalFoodLibraryEnabled) activeFoods.size else 0,
                            allFoods.size
                        ),
                        showArrow = true,
                        onClick = { navController.navigate("settings/journal/foods") },
                        icon = Icons.Default.LunchDining,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.TOP
                    )
                    SettingsItem(
                        title = stringResource(R.string.journal_insulin_library),
                        subtitle = stringResource(R.string.journal_insulin_library_count, activePresets.size, allPresets.size),
                        showArrow = true,
                        onClick = { navController.navigate("settings/journal/insulin") },
                        icon = Icons.Default.Vaccines,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.MIDDLE
                    )
                    SettingsItem(
                        title = stringResource(R.string.meal_settings_entry),
                        subtitle = stringResource(R.string.meal_settings_entry_desc),
                        showArrow = true,
                        onClick = { navController.navigate("journal/meals") },
                        icon = Icons.Default.Restaurant,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.MIDDLE
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.meal_online_lookup_title),
                        subtitle = stringResource(R.string.meal_online_lookup_desc),
                        checked = journalMealOnlineLookup,
                        onCheckedChange = { viewModel.setJournalMealOnlineLookup(it) },
                        icon = Icons.Default.QrCodeScanner,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.MIDDLE
                    )
                    MealContributionSettingsItems()
                }
            }
        }
    }
}

/**
 * Opt-in: send products read from a label photo back to Open Food Facts. Credentials live in
 * their own preferences file; the switch is off by default and nothing is sent before the user
 * has confirmed the values in the product form.
 */
@Composable
private fun MealContributionSettingsItems() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(MealContributionSettings.load(context)) }
    fun update(next: MealContributionSettings) {
        settings = next
        MealContributionSettings.save(context, next)
    }
    SettingsSwitchItem(
        title = stringResource(R.string.meal_contribute_title),
        subtitle = stringResource(R.string.meal_contribute_desc),
        checked = settings.enabled,
        onCheckedChange = { update(settings.copy(enabled = it)) },
        icon = Icons.Default.CloudUpload,
        iconTint = MaterialTheme.colorScheme.secondary,
        position = if (settings.enabled) CardPosition.MIDDLE else CardPosition.BOTTOM
    )
    AnimatedVisibility(visible = settings.enabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = settings.userId,
                        onValueChange = { update(settings.copy(userId = it)) },
                        label = { Text(stringResource(R.string.meal_contribute_user)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.password,
                        onValueChange = { update(settings.copy(password = it)) },
                        label = { Text(stringResource(R.string.meal_contribute_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            SettingsSwitchItem(
                title = stringResource(R.string.meal_contribute_photos_title),
                subtitle = stringResource(R.string.meal_contribute_photos_desc),
                checked = settings.uploadPhotos,
                onCheckedChange = { update(settings.copy(uploadPhotos = it)) },
                icon = Icons.Default.CloudUpload,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.MIDDLE
            )
            val log = remember(settings) { MealContributionLog.entries(context) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.meal_contribute_log_title), style = MaterialTheme.typography.titleSmall)
                    if (log.isEmpty()) {
                        Text(stringResource(R.string.meal_contribute_log_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    log.take(15).forEach { e ->
                        Text(
                            text = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(e.at)) +
                                "  " + (if (e.ok) "✓" else "✗") + "  " + e.name.ifBlank { e.barcode } + " — " + e.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalActionRow(
    onHistoryClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        JournalActionButton(
            text = stringResource(R.string.historyname),
            icon = Icons.Default.History,
            enabled = true,
            prominent = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = onHistoryClick
        )
    }
}

@Composable
private fun JournalActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    prominent: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (prominent) 48.dp else 36.dp),
        shape = RoundedCornerShape(if (prominent) 24.dp else 20.dp),
        colors = if (prominent) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        contentPadding = PaddingValues(horizontal = if (prominent) 18.dp else 14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(if (prominent) 20.dp else 16.dp)
        )
        Spacer(modifier = Modifier.width(if (prominent) 10.dp else 8.dp))
        Text(
            text = text,
            style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
@Composable
private fun JournalIntelligenceCard(
    journalEnabled: Boolean,
    doseCalculatorEnabled: Boolean,
    onDoseCalculatorChange: (Boolean) -> Unit,
    foodMacrosEnabled: Boolean,
    onFoodMacrosChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (journalEnabled) 1f else 0.58f),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(30.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            JournalIntelligenceRow(
                title = stringResource(R.string.journal_dose_math_title),
                subtitle = stringResource(R.string.journal_dose_calculator_desc),
                icon = Icons.Default.Calculate,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = doseCalculatorEnabled,
                enabled = journalEnabled,
                onCheckedChange = onDoseCalculatorChange
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
            )
            JournalIntelligenceRow(
                title = stringResource(R.string.journal_food_macros_title),
                subtitle = stringResource(R.string.journal_food_macros_desc),
                icon = Icons.Default.LunchDining,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = foodMacrosEnabled,
                enabled = journalEnabled,
                onCheckedChange = onFoodMacrosChange
            )
        }
    }
}

@Composable
private fun JournalIntelligenceRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.52f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            color = iconTint.copy(alpha = if (checked) 0.22f else 0.10f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        StyledSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun JournalLibraryHub(
    activeFoods: Int,
    totalFoods: Int,
    activeInsulin: Int,
    totalInsulin: Int,
    onFoodClick: () -> Unit,
    onInsulinClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        JournalLibraryTile(
            title = stringResource(R.string.journal_food_library),
            subtitle = stringResource(R.string.journal_food_library_count, activeFoods, totalFoods),
            icon = Icons.Default.LunchDining,
            tint = MaterialTheme.colorScheme.secondary,
            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 28.dp),
            modifier = Modifier.weight(1f),
            onClick = onFoodClick
        )
        JournalLibraryTile(
            title = stringResource(R.string.journal_insulin_library),
            subtitle = stringResource(R.string.journal_insulin_library_count, activeInsulin, totalInsulin),
            icon = Icons.Default.Vaccines,
            tint = MaterialTheme.colorScheme.tertiary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 34.dp, bottomStart = 28.dp, bottomEnd = 20.dp),
            modifier = Modifier.weight(1f),
            onClick = onInsulinClick
        )
    }
}

@Composable
private fun JournalLibraryTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        color = tint.copy(alpha = 0.13f),
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = tint.copy(alpha = 0.20f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun JournalFoodLibraryScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val allFoods by viewModel.journalFoods.collectAsState()
    val foodMacrosEnabled by viewModel.journalFoodMacrosEnabled.collectAsState()
    val foodLibraryEnabled by viewModel.journalFoodLibraryEnabled.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var legacyFoods by remember { mutableStateOf(emptyList<JournalFood>()) }
    var expandedImportFoodId by remember { mutableStateOf<Long?>(null) }
    var expandedImportFoodDetails by remember { mutableStateOf<LegacyJournalFoodDatabase.FoodDetails?>(null) }
    val activeFoods = remember(allFoods, query) {
        allFoods
            .filter { !it.isArchived }
            .filterFoodQuery(query)
    }
    val archivedFoods = remember(allFoods, query) {
        allFoods
            .filter { it.isArchived }
            .filterFoodQuery(query)
    }
    LaunchedEffect(query, foodLibraryEnabled) {
        val needle = query.trim()
        legacyFoods = if (foodLibraryEnabled && needle.length >= 2) {
            withContext(Dispatchers.Default) {
                LegacyJournalFoodDatabase.search(needle, limit = 36)
            }
        } else {
            emptyList()
        }
    }
    val importFoods = remember(allFoods, legacyFoods) {
        val savedNames = allFoods.map { it.displayName.lowercase(java.util.Locale.ROOT) }.toSet()
        legacyFoods.filter { it.displayName.lowercase(java.util.Locale.ROOT) !in savedNames }
    }
    LaunchedEffect(expandedImportFoodId, importFoods) {
        val food = importFoods.firstOrNull { it.id == expandedImportFoodId }
        expandedImportFoodDetails = null
        if (food != null) {
            expandedImportFoodDetails = withContext(Dispatchers.Default) {
                LegacyJournalFoodDatabase.details(food)
            }
        }
    }
    var editingFood by remember { mutableStateOf<JournalFood?>(null) }
    var creatingFood by remember { mutableStateOf(false) }
    fun importFood(food: JournalFood, portionGrams: Float? = null) {
        val imported = portionGrams?.let(food::scaledForPortion) ?: food
        viewModel.saveJournalFood(imported.toLibraryFoodInput(sortOrder = allFoods.size + 100))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_food_library)) },
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
            item(key = "food_library_enabled") {
                SettingsSwitchItem(
                    title = stringResource(R.string.journal_food_library),
                    subtitle = stringResource(R.string.journal_food_choose_desc),
                    checked = foodLibraryEnabled,
                    onCheckedChange = { viewModel.setJournalFoodLibraryEnabled(it) },
                    icon = Icons.Default.LunchDining,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.SINGLE
                )
            }

            item(key = "food_search") {
                JournalFoodLibrarySearchField(
                    value = query,
                    onValueChange = { query = it },
                    enabled = foodLibraryEnabled,
                    onClose = if (query.isBlank()) null else {
                        {
                            query = ""
                            expandedImportFoodId = null
                        }
                    }
                )
            }


            if (foodLibraryEnabled && activeFoods.isNotEmpty()) {
                item(key = "food_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        activeFoods.forEachIndexed { index, food ->
                            JournalFoodRow(
                                food = food,
                                position = cardPosition(index, activeFoods.size),
                                foodMacrosEnabled = foodMacrosEnabled,
                                onClick = { editingFood = food }
                            )
                        }
                    }
                }
            }

            if (foodLibraryEnabled && importFoods.isNotEmpty()) {
                item(key = "food_import_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        importFoods.forEachIndexed { index, food ->
                            val expanded = expandedImportFoodId == food.id
                            JournalFoodImportRow(
                                food = food,
                                position = cardPosition(index, importFoods.size),
                                foodMacrosEnabled = foodMacrosEnabled,
                                expanded = expanded,
                                details = if (expanded) expandedImportFoodDetails else null,
                                onToggleExpanded = {
                                    expandedImportFoodId = if (expanded) null else food.id
                                },
                                onAdd = { portionGrams -> importFood(food, portionGrams) }
                            )
                        }
                    }
                }
            }

            if (foodLibraryEnabled) {
                item(key = "add_food_button") {
                AddCustomAlertButton(
                    text = stringResource(R.string.journal_add_food),
                    onClick = { creatingFood = true }
                )
                }
            }
            if (foodLibraryEnabled && archivedFoods.isNotEmpty()) {
                item(key = "disabled_food_label") {
                    SectionLabel(
                        text = stringResource(R.string.journal_food_disabled_library),
                        topPadding = 4.dp
                    )
                }
                item(key = "disabled_food_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        archivedFoods.forEachIndexed { index, food ->
                            JournalFoodRow(
                                food = food,
                                position = cardPosition(index, archivedFoods.size),
                                foodMacrosEnabled = foodMacrosEnabled,
                                onClick = { editingFood = food }
                            )
                        }
                    }
                }
            }
        }
    }

    if (creatingFood) {
        JournalFoodSheet(
            food = null,
            foodMacrosEnabled = foodMacrosEnabled,
            onDismiss = { creatingFood = false },
            onSave = {
                viewModel.saveJournalFood(it)
                creatingFood = false
            },
            onDelete = null
        )
    }

    editingFood?.let { food ->
        JournalFoodSheet(
            food = food,
            foodMacrosEnabled = foodMacrosEnabled,
            onDismiss = { editingFood = null },
            onSave = {
                viewModel.saveJournalFood(it)
                editingFood = null
            },
            onDelete = if (food.isBuiltIn) {
                null
            } else {
                {
                    viewModel.deleteJournalFood(food.id)
                    editingFood = null
                }
            }
        )
    }
}

@Composable
fun JournalInsulinLibraryScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val allPresets by viewModel.journalInsulinPresets.collectAsState()
    val activePresets = remember(allPresets) { allPresets.filter { !it.isArchived } }
    val archivedPresets = remember(allPresets) { allPresets.filter { it.isArchived } }
    var editingPreset by remember { mutableStateOf<JournalInsulinPreset?>(null) }
    var creatingPreset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_insulin_library)) },
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
            if (activePresets.isNotEmpty()) {
                item(key = "active_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        activePresets.forEachIndexed { index, preset ->
                            JournalPresetRow(
                                preset = preset,
                                position = cardPosition(index, activePresets.size),
                                onClick = { editingPreset = preset }
                            )
                        }
                    }
                }
            }

            item(key = "add_preset_button") {
                AddCustomAlertButton(
                    text = stringResource(R.string.journal_add_preset),
                    onClick = { creatingPreset = true }
                )
            }

            if (archivedPresets.isNotEmpty()) {
                item(key = "archived_label") {
                    SectionLabel(
                        text = stringResource(R.string.journal_archived_presets),
                        topPadding = 4.dp
                    )
                }
                item(key = "archived_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        archivedPresets.forEachIndexed { index, preset ->
                            JournalPresetRow(
                                preset = preset,
                                position = cardPosition(index, archivedPresets.size),
                                onClick = { editingPreset = preset }
                            )
                        }
                    }
                }
            }
        }
    }

    if (creatingPreset) {
        JournalInsulinPresetSheet(
            preset = null,
            onDismiss = { creatingPreset = false },
            onSave = {
                viewModel.saveJournalInsulinPreset(it)
                creatingPreset = false
            },
            onDelete = null
        )
    }

    editingPreset?.let { preset ->
        JournalInsulinPresetSheet(
            preset = preset,
            onDismiss = { editingPreset = null },
            onSave = {
                viewModel.saveJournalInsulinPreset(it)
                editingPreset = null
            },
            onDelete = if (preset.isBuiltIn) {
                null
            } else {
                {
                    viewModel.deleteJournalInsulinPreset(preset.id)
                    editingPreset = null
                }
            }
        )
    }
}

@Composable
private fun JournalPresetRow(
    preset: JournalInsulinPreset,
    position: CardPosition,
    onClick: () -> Unit
) {
    val isDisabled = preset.isArchived
    val previewColor = if (isDisabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    } else {
        Color(preset.accentColor)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (isDisabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = cardShape(position)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isDisabled) 0.6f else 1f)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDisabled) {
                        Text(
                            text = stringResource(R.string.disabled_status),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                R.string.journal_active_window,
                                stringResource(R.string.minutes_short_format, preset.onsetMinutes),
                                stringResource(R.string.minutes_short_format, preset.durationMinutes)
                            )
                        )
                        if (preset.countsTowardIob) {
                            append(" · ")
                            append(stringResource(R.string.journal_active_insulin))
                        }
                        if (preset.useForCalculation) {
                            append(" · ")
                            append(stringResource(R.string.journal_use_for_calculation))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            JournalCurvePreview(
                points = preset.curvePoints,
                color = previewColor,
                modifier = Modifier
                    .width(88.dp)
                    .height(42.dp)
            )
        }
    }
}

@Composable
private fun JournalFoodRow(
    food: JournalFood,
    position: CardPosition,
    foodMacrosEnabled: Boolean,
    onClick: () -> Unit
) {
    val isDisabled = food.isArchived
    val tint = if (isDisabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    } else {
        Color(food.accentColor)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (isDisabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = cardShape(position)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isDisabled) 0.6f else 1f)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                color = tint.copy(alpha = if (isDisabled) 0.10f else 0.18f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.LunchDining,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = food.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDisabled) {
                        Text(
                            text = stringResource(R.string.disabled_status),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    FoodMetricChip(
                        label = stringResource(R.string.carbo).trimTrailingLabel(),
                        value = "${formatFoodNumber(food.carbsGrams)} g",
                        tint = tint
                    )
                    if (foodMacrosEnabled) {
                        FoodMetricChip(
                            label = stringResource(R.string.journal_food_protein),
                            value = "${formatFoodNumber(food.proteinGrams ?: 0f)} g",
                            tint = tint
                        )
                        FoodMetricChip(
                            label = stringResource(R.string.journal_food_fat),
                            value = "${formatFoodNumber(food.fatGrams ?: 0f)} g",
                            tint = tint
                        )
                    }
                    FoodMetricChip(
                        label = "",
                        value = stringResource(R.string.minutes_short_format, food.absorptionMinutes),
                        tint = tint
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalFoodImportRow(
    food: JournalFood,
    position: CardPosition,
    foodMacrosEnabled: Boolean,
    expanded: Boolean,
    details: LegacyJournalFoodDatabase.FoodDetails?,
    onToggleExpanded: () -> Unit,
    onAdd: (Float?) -> Unit
) {
    val tint = Color(food.accentColor)
    val shape = cardShape(position)
    var portionText by rememberSaveable(food.id) { mutableStateOf("100") }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onToggleExpanded)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(tint, CircleShape)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = food.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = foodLibrarySummary(food, foodMacrosEnabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                FilledTonalIconButton(
                    onClick = {
                        onAdd(
                            if (expanded) {
                                parseFoodPortionGrams(portionText)
                            } else {
                                null
                            }
                        )
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    expandFrom = Alignment.Top
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    shrinkTowards = Alignment.Top
                ) + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    JournalFoodCompositionDetails(
                        details = details,
                        color = tint,
                        portionText = portionText,
                        onPortionTextChange = { portionText = it },
                        onSaveToLibrary = null
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalFoodLibrarySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onClose: (() -> Unit)?
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.84f),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.journal_food_search),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FoodMetricChip(
    label: String,
    value: String,
    tint: Color
) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = if (label.isBlank()) value else "$label $value",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun JournalFoodSheet(
    food: JournalFood?,
    foodMacrosEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (JournalFoodInput) -> Unit,
    onDelete: (() -> Unit)?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(food?.id) { mutableStateOf(buildFoodDraft(food)) }
    var showColorDialog by rememberSaveable(food?.id) { mutableStateOf(false) }
    val canSave = draft.displayName.trim().isNotBlank() &&
        draft.carbsText.parseFoodFloatOrNull() != null &&
        draft.absorptionText.toIntOrNull() != null

    fun saveDraft() {
        buildFoodInput(draft)?.let(onSave)
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .imePadding(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(if (food == null) R.string.journal_add_food else R.string.journal_edit_food),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (food != null) {
                        val archived = draft.isArchived
                        val containerColor = if (archived) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                        val contentColor = if (archived) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                        FilledTonalButton(
                            onClick = { draft = draft.copy(isArchived = !draft.isArchived) },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = containerColor,
                                contentColor = contentColor
                            )
                        ) {
                            Icon(
                                imageVector = if (archived) Icons.Default.CheckCircle else Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = stringResource(if (archived) R.string.enable else R.string.disable),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            item(key = "name_color") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    JournalFoodNameField(
                        value = draft.displayName,
                        onValueChange = { draft = draft.copy(displayName = it) },
                        label = stringResource(R.string.name).trimTrailingLabel(),
                        accentColor = Color(draft.accentColor),
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp)
                    )
                    ColorSwatchButton(
                        color = Color(draft.accentColor),
                        onClick = { showColorDialog = true },
                    )
                }
            }

            item(key = "macros") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    JournalFoodEditorField(
                        value = draft.carbsText,
                        onValueChange = { draft = draft.copy(carbsText = it) },
                        onStep = { delta ->
                            draft = draft.copy(carbsText = adjustFoodNumberDraft(draft.carbsText, delta, step = 5f))
                        },
                        label = stringResource(R.string.carbo).trimTrailingLabel(),
                        suffix = "g",
                        accentColor = Color(draft.accentColor)
                    )
                    if (foodMacrosEnabled) {
                        JournalFoodEditorField(
                            value = draft.proteinText,
                            onValueChange = { draft = draft.copy(proteinText = it) },
                            onStep = { delta ->
                                draft = draft.copy(proteinText = adjustFoodNumberDraft(draft.proteinText, delta, step = 5f))
                            },
                            label = stringResource(R.string.journal_food_protein),
                            suffix = "g",
                            accentColor = Color(draft.accentColor)
                        )
                        JournalFoodEditorField(
                            value = draft.fatText,
                            onValueChange = { draft = draft.copy(fatText = it) },
                            onStep = { delta ->
                                draft = draft.copy(fatText = adjustFoodNumberDraft(draft.fatText, delta, step = 5f))
                            },
                            label = stringResource(R.string.journal_food_fat),
                            suffix = "g",
                            accentColor = Color(draft.accentColor)
                        )
                    }
                    JournalFoodEditorField(
                        value = draft.absorptionText,
                        onValueChange = { updated -> draft = draft.copy(absorptionText = updated.filter(Char::isDigit)) },
                        onStep = { delta ->
                            val current = draft.absorptionText.toIntOrNull() ?: 90
                            draft = draft.copy(absorptionText = (current + delta * 15).coerceAtLeast(15).toString())
                        },
                        label = stringResource(R.string.journal_food_absorption),
                        suffix = stringResource(R.string.minutes),
                        keyboardType = KeyboardType.Number,
                        accentColor = Color(draft.accentColor)
                    )
                }
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { saveDraft() },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.save))
                    }
                    onDelete?.let {
                        OutlinedButton(
                            onClick = it,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (showColorDialog) {
        PresetColorDialog(
            initialColor = draft.accentColor,
            onDismiss = { showColorDialog = false },
            onConfirm = {
                draft = draft.copy(accentColor = it)
                showColorDialog = false
            }
        )
    }
}

@Composable
private fun JournalFoodNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                cursorBrush = SolidColor(accentColor),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun JournalFoodEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    label: String,
    suffix: String,
    accentColor: Color,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = { onStep(-1) },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        cursorBrush = SolidColor(accentColor),
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isBlank()) {
                                    Text(
                                        text = "0",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FilledTonalIconButton(
                onClick = { onStep(1) },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = accentColor.copy(alpha = 0.18f),
                    contentColor = accentColor
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun JournalInsulinPresetSheet(
    preset: JournalInsulinPreset?,
    onDismiss: () -> Unit,
    onSave: (JournalInsulinPresetInput) -> Unit,
    onDelete: (() -> Unit)?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialDraft = remember(preset?.id) { buildPresetDraft(preset) }
    var draft by remember(preset?.id) { mutableStateOf(initialDraft) }
    var selectedPointIndex by rememberSaveable(preset?.id) {
        mutableIntStateOf(defaultSelectedPointIndex(draft.curvePoints))
    }
    var showColorDialog by rememberSaveable(preset?.id) { mutableStateOf(false) }

    LaunchedEffect(draft.curvePoints.size) {
        selectedPointIndex = selectedPointIndex.coerceIn(0, draft.curvePoints.lastIndex)
    }

    val selectedPoint = draft.curvePoints.getOrNull(selectedPointIndex)
    val canSave = draft.displayName.trim().isNotBlank() && draft.curvePoints.size >= 3
    val hasChanges = draft != initialDraft
    val usesExplicitActions = draft.id == null || !draft.isBuiltIn
    val latestDraft by rememberUpdatedState(draft)
    val latestInitialDraft by rememberUpdatedState(initialDraft)
    val latestCanSave by rememberUpdatedState(canSave)
    val latestUsesExplicitActions by rememberUpdatedState(usesExplicitActions)
    val latestOnSave by rememberUpdatedState(onSave)
    var dismissalHandled by remember(preset?.id) { mutableStateOf(false) }
    fun saveBuiltInDraftIfNeeded(): Boolean {
        if (latestUsesExplicitActions || !latestCanSave || latestDraft == latestInitialDraft) {
            return false
        }
        val input = buildPresetInput(latestDraft) ?: return false
        latestOnSave(input)
        return true
    }
    fun dismissSheet() {
        dismissalHandled = true
        if (!saveBuiltInDraftIfNeeded()) {
            onDismiss()
        }
    }
    fun saveDraft() {
        val input = buildPresetInput(draft) ?: return
        dismissalHandled = true
        onSave(input)
    }
    fun toggleArchivedAndPersist() {
        val updated = draft.copy(isArchived = !draft.isArchived)
        draft = updated
        buildPresetInput(updated)?.let { input ->
            dismissalHandled = true
            onSave(input)
        }
    }
    DisposableEffect(preset?.id) {
        onDispose {
            if (!dismissalHandled) {
                saveBuiltInDraftIfNeeded()
            }
        }
    }

    StableModalBottomSheet(
        onDismissRequest = { dismissSheet() },
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .imePadding(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (preset == null) R.string.journal_add_preset else R.string.journal_edit_preset
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (preset != null) {
                        val archived = draft.isArchived
                        val containerColor = if (archived) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                        val contentColor = if (archived) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                        FilledTonalButton(
                            onClick = { toggleArchivedAndPersist() },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = containerColor,
                                contentColor = contentColor
                            )
                        ) {
                            Icon(
                                imageVector = if (archived) Icons.Default.CheckCircle else Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = stringResource(
                                    if (archived) R.string.enable else R.string.disable
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            item(key = "meta_card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (draft.isArchived) {
                                Modifier.background(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val cardSidePadding = if (draft.isArchived) 12.dp else 0.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = cardSidePadding,
                                end = cardSidePadding,
                                top = if (draft.isArchived) 12.dp else 0.dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draft.displayName,
                            onValueChange = { draft = draft.copy(displayName = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        FilledTonalIconButton(
                            onClick = { showColorDialog = true },
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Surface(
                                modifier = Modifier.size(20.dp),
                                shape = CircleShape,
                                color = Color(draft.accentColor)
                            ) {}
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = cardSidePadding)
                    )
                    CompactPresetToggleRow(
                        title = stringResource(R.string.journal_active_insulin),
                        subtitle = stringResource(R.string.journal_active_insulin_subtitle),
                        checked = draft.countsTowardIob,
                        enabled = !draft.isArchived,
                        onCheckedChange = { draft = draft.copy(countsTowardIob = it) },
                        contentPadding = PaddingValues(
                            start = cardSidePadding,
                            end = cardSidePadding
                        )
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = cardSidePadding)
                    )
                    CompactPresetToggleRow(
                        title = stringResource(R.string.journal_use_for_calculation),
                        subtitle = stringResource(R.string.journal_use_for_calculation_subtitle),
                        checked = draft.useForCalculation,
                        enabled = !draft.isArchived,
                        onCheckedChange = { draft = draft.copy(useForCalculation = it) },
                        contentPadding = PaddingValues(
                            start = cardSidePadding,
                            end = cardSidePadding,
                            bottom = if (draft.isArchived) 8.dp else 0.dp
                        )
                    )
                }
            }

            item(key = "editor") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (draft.isArchived) {
                                Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(22.dp)
                                    )
                                    .padding(12.dp)
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val resetProfile = remember(draft.isBuiltIn, draft.sortOrder) {
                        if (draft.isBuiltIn) defaultBuiltInProfile(draft.sortOrder) else null
                    }
                    val curveDiffersFromDefault = remember(draft.curvePoints, resetProfile) {
                        resetProfile != null &&
                            serializeJournalCurve(draft.curvePoints) !=
                            serializeJournalCurve(builtInJournalCurve(resetProfile))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.journal_curve_preview),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (resetProfile != null && curveDiffersFromDefault) {
                            TextButton(
                                onClick = {
                                    val defaultCurve = builtInJournalCurve(resetProfile)
                                    draft = draft.copy(curvePoints = defaultCurve)
                                    selectedPointIndex = defaultSelectedPointIndex(defaultCurve)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .widthIn(max = 132.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Restore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.journal_curve_reset),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = curveWindowSummary(draft.curvePoints),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    InteractiveJournalCurveEditor(
                        points = draft.curvePoints,
                        selectedPointIndex = selectedPointIndex,
                        color = Color(draft.accentColor),
                        onSelectedPointChange = { selectedPointIndex = it },
                        onPointChange = { index, minute, activity ->
                            draft = draft.copy(
                                curvePoints = updateCurvePoint(
                                    points = draft.curvePoints,
                                    index = index,
                                    minute = minute,
                                    activity = activity
                                )
                            )
                        }
                    )
                    selectedPoint?.let { point ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        SelectedCurvePointEditor(
                            index = selectedPointIndex,
                            point = point,
                            canEditMinute = selectedPointIndex != 0,
                            canEditActivity = selectedPointIndex in 1 until draft.curvePoints.lastIndex,
                            canDelete = canDeleteCurvePoint(draft.curvePoints, selectedPointIndex),
                            onMinuteChange = { minute ->
                                draft = draft.copy(
                                    curvePoints = updateCurvePoint(
                                        points = draft.curvePoints,
                                        index = selectedPointIndex,
                                        minute = minute,
                                        activity = point.activity
                                    )
                                )
                            },
                            onActivityChange = { activity ->
                                draft = draft.copy(
                                    curvePoints = updateCurvePoint(
                                        points = draft.curvePoints,
                                        index = selectedPointIndex,
                                        minute = point.minute,
                                        activity = activity
                                    )
                                )
                            },
                            onDelete = {
                                draft = draft.copy(
                                    curvePoints = deleteCurvePoint(draft.curvePoints, selectedPointIndex)
                                )
                                selectedPointIndex = (selectedPointIndex - 1).coerceAtLeast(1)
                            }
                        )
                    }
                }
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val updatedPoints = insertCurvePoint(draft.curvePoints, selectedPointIndex)
                            val insertedIndex = findInsertedPointIndex(draft.curvePoints, updatedPoints, selectedPointIndex)
                            draft = draft.copy(curvePoints = updatedPoints)
                            selectedPointIndex = insertedIndex
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.journal_curve_add_point))
                    }
                    Button(
                        onClick = { saveDraft() },
                        enabled = canSave && hasChanges,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.save))
                    }
                    if (usesExplicitActions) {
                        onDelete?.let {
                            OutlinedButton(
                                onClick = it,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showColorDialog) {
        PresetColorDialog(
            initialColor = draft.accentColor,
            onDismiss = { showColorDialog = false },
            onConfirm = {
                draft = draft.copy(accentColor = it)
                showColorDialog = false
            }
        )
    }
}

@Composable
private fun JournalCompactSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (checked) 0.86f else 0.42f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (checked) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StyledSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun CompactPresetToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(contentPadding)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        StyledSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SelectedCurvePointEditor(
    index: Int,
    point: JournalCurvePoint,
    canEditMinute: Boolean,
    canEditActivity: Boolean,
    canDelete: Boolean,
    onMinuteChange: (Int) -> Unit,
    onActivityChange: (Float) -> Unit,
    onDelete: () -> Unit
) {
    var minuteText by remember(index, point.minute) { mutableStateOf(point.minute.toString()) }
    var activityText by remember(index, point.activity) {
        mutableStateOf((point.activity * 100f).roundToInt().toString())
    }
    val fallbackMinuteSuffix = stringResource(R.string.minutes)
    val compactMinuteSuffix = stringResource(R.string.minutes_short_format, 1)
        .replace(Regex("\\d+"), "")
        .trim()
        .ifBlank { fallbackMinuteSuffix }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.journal_curve_point, index + 1),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = minuteText,
                onValueChange = { updated ->
                    minuteText = updated.filter(Char::isDigit)
                    minuteText.toIntOrNull()?.let(onMinuteChange)
                },
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                label = { Text(stringResource(R.string.journal_curve_minutes)) },
                singleLine = true,
                enabled = canEditMinute,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text(compactMinuteSuffix, maxLines = 1) }
            )
            OutlinedTextField(
                value = activityText,
                onValueChange = { updated ->
                    activityText = updated.filter { it.isDigit() || it == ',' || it == '.' }
                    activityText.replace(',', '.').toFloatOrNull()?.let { percent ->
                        onActivityChange((percent / 100f).coerceIn(0f, 1f))
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                label = { Text(stringResource(R.string.journal_curve_activity)) },
                singleLine = true,
                enabled = canEditActivity,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                suffix = { Text("%") }
            )
        }
    }
}

@Composable
private fun InteractiveJournalCurveEditor(
    points: List<JournalCurvePoint>,
    selectedPointIndex: Int,
    color: Color,
    onSelectedPointChange: (Int) -> Unit,
    onPointChange: (Int, Int, Float) -> Unit
) {
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
    val selectionHaloColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val chartPadding = with(density) { 18.dp.toPx() }
    val chartTopInset = with(density) { 12.dp.toPx() }
    val chartBottomInset = with(density) { 12.dp.toPx() }

    val pointsLatest = rememberUpdatedState(points)
    val chartSizeLatest = rememberUpdatedState(chartSize)
    val onSelectedPointChangeLatest = rememberUpdatedState(onSelectedPointChange)
    val onPointChangeLatest = rememberUpdatedState(onPointChange)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(164.dp)
            .onSizeChanged { chartSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    nearestCurvePointIndex(
                        points = pointsLatest.value,
                        chartSize = chartSizeLatest.value,
                        touch = offset,
                        horizontalPaddingPx = chartPadding,
                        topInsetPx = chartTopInset,
                        bottomInsetPx = chartBottomInset
                    )?.let { onSelectedPointChangeLatest.value(it) }
                }
            }
            .pointerInput(Unit) {
                var draggingPointIndex: Int? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        draggingPointIndex = nearestCurvePointIndex(
                            points = pointsLatest.value,
                            chartSize = chartSizeLatest.value,
                            touch = offset,
                            horizontalPaddingPx = chartPadding,
                            topInsetPx = chartTopInset,
                            bottomInsetPx = chartBottomInset
                        )?.also { onSelectedPointChangeLatest.value(it) }
                    },
                    onDragEnd = { draggingPointIndex = null },
                    onDragCancel = { draggingPointIndex = null },
                    onDrag = { change, _ ->
                        val index = draggingPointIndex ?: return@detectDragGestures
                        change.consume()
                        val updatedPoint = pointFromTouch(
                            points = pointsLatest.value,
                            index = index,
                            chartSize = chartSizeLatest.value,
                            touch = change.position,
                            horizontalPaddingPx = chartPadding,
                            topInsetPx = chartTopInset,
                            bottomInsetPx = chartBottomInset
                        )
                        onPointChangeLatest.value(index, updatedPoint.minute, updatedPoint.activity)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.size < 2 || chartSize.width == 0 || chartSize.height == 0) return@Canvas

            val bounds = curveBounds(
                width = size.width,
                height = size.height,
                horizontalPaddingPx = chartPadding,
                topInsetPx = chartTopInset,
                bottomInsetPx = chartBottomInset
            )
            val maxMinute = points.last().minute.coerceAtLeast(1).toFloat()

            repeat(4) { gridIndex ->
                val y = bounds.bottom - ((bounds.height / 3f) * gridIndex)
                drawLine(
                    color = guideColor,
                    start = Offset(bounds.left, y),
                    end = Offset(bounds.right, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            repeat(4) { gridIndex ->
                val x = bounds.left + ((bounds.width / 3f) * gridIndex)
                drawLine(
                    color = guideColor,
                    start = Offset(x, bounds.top),
                    end = Offset(x, bounds.bottom),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val offsets = points.map { it.toOffset(bounds, maxMinute) }
            fun Path.addSmoothedOffsets(samples: List<Offset>, moveToFirst: Boolean) {
                if (samples.isEmpty()) return
                val first = samples.first()
                if (moveToFirst) {
                    moveTo(first.x, first.y)
                } else {
                    lineTo(first.x, first.y)
                }
                if (samples.size == 1) return
                if (samples.size == 2) {
                    val last = samples.last()
                    lineTo(last.x, last.y)
                    return
                }
                for (index in 1 until samples.lastIndex) {
                    val current = samples[index]
                    val next = samples[index + 1]
                    val midX = (current.x + next.x) * 0.5f
                    val midY = (current.y + next.y) * 0.5f
                    quadraticTo(current.x, current.y, midX, midY)
                }
                val last = samples.last()
                lineTo(last.x, last.y)
            }

            val curvePath = Path().apply {
                addSmoothedOffsets(offsets, moveToFirst = true)
            }
            val fillPath = Path().apply {
                moveTo(offsets.first().x, bounds.bottom)
                addSmoothedOffsets(offsets, moveToFirst = false)
                lineTo(offsets.last().x, bounds.bottom)
                close()
            }

            drawPath(path = fillPath, color = color.copy(alpha = 0.14f))
            drawPath(
                path = curvePath,
                color = color,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            points.forEachIndexed { index, point ->
                val offset = point.toOffset(bounds, maxMinute)
                val isSelected = index == selectedPointIndex
                if (isSelected) {
                    drawCircle(
                        color = selectionHaloColor,
                        radius = 8.dp.toPx(),
                        center = offset
                    )
                }
                drawCircle(
                    color = if (index == 0 || index == points.lastIndex) {
                        color.copy(alpha = 0.7f)
                    } else {
                        color
                    },
                    radius = if (isSelected) 6.dp.toPx() else 4.5.dp.toPx(),
                    center = offset
                )
            }
        }
    }
}

@Composable
private fun PresetColorDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    ExpressiveColorPickerDialog(
        title = stringResource(R.string.colors),
        initialColor = initialColor,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun JournalCurvePreview(
    points: List<JournalCurvePoint>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val normalizedPoints = remember(points) { normalizeJournalCurvePoints(points) }
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            if (normalizedPoints.size < 2) return@Canvas
            val maxMinute = normalizedPoints.last().minute.coerceAtLeast(1).toFloat()
            val baseline = size.height - 4.dp.toPx()
            val curveHeight = (size.height - 14.dp.toPx()).coerceAtLeast(10.dp.toPx())

            repeat(3) { index ->
                val y = baseline - ((curveHeight / 2f) * index)
                drawLine(
                    color = guideColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val path = Path()
            val fillPath = Path()
            normalizedPoints.forEachIndexed { index, point ->
                val x = (point.minute / maxMinute) * size.width
                val y = baseline - (point.activity.coerceIn(0f, 1f) * curveHeight)
                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, baseline)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(size.width, baseline)
            fillPath.close()

            drawPath(path = fillPath, color = color.copy(alpha = 0.14f))
            drawPath(path = path, color = color, style = Stroke(width = 2.2.dp.toPx()))
        }
    }
}

private fun buildPresetDraft(preset: JournalInsulinPreset?): JournalPresetDraft {
    val sourcePoints = preset?.curvePoints ?: builtInJournalCurve(JournalBuiltInCurveProfile.RAPID_GENERIC)
    return JournalPresetDraft(
        id = preset?.id,
        displayName = preset?.displayName.orEmpty(),
        accentColor = preset?.accentColor ?: DEFAULT_PRESET_COLOR,
        curvePoints = normalizeJournalCurvePoints(sourcePoints),
        sortOrder = preset?.sortOrder ?: Int.MAX_VALUE,
        isBuiltIn = preset?.isBuiltIn ?: false,
        isArchived = preset?.isArchived ?: false,
        countsTowardIob = preset?.countsTowardIob ?: true,
        useForCalculation = preset?.useForCalculation ?: true
    )
}

private fun buildPresetInput(
    draft: JournalPresetDraft,
    overrideArchived: Boolean = draft.isArchived
): JournalInsulinPresetInput? {
    val normalizedCurve = normalizeJournalCurvePoints(draft.curvePoints)
    if (draft.displayName.trim().isBlank() || normalizedCurve.size < 3) return null
    val onset = normalizedCurve.firstOrNull { it.activity > 0.01f }?.minute ?: 0
    val duration = normalizedCurve.lastOrNull()?.minute ?: 0
    return JournalInsulinPresetInput(
        id = draft.id,
        displayName = draft.displayName.trim(),
        onsetMinutes = onset,
        durationMinutes = duration,
        accentColor = draft.accentColor,
        curveJson = serializeJournalCurve(normalizedCurve),
        isBuiltIn = draft.isBuiltIn,
        isArchived = overrideArchived,
        countsTowardIob = draft.countsTowardIob,
        sortOrder = draft.sortOrder,
        useForCalculation = draft.useForCalculation
    )
}

private fun updateCurvePoint(
    points: List<JournalCurvePoint>,
    index: Int,
    minute: Int,
    activity: Float
): List<JournalCurvePoint> {
    if (points.isEmpty()) return points
    val updated = points.toMutableList()
    val lastIndex = updated.lastIndex
    val previousMinute = updated.getOrNull(index - 1)?.minute?.plus(1) ?: 0
    val nextMinute = updated.getOrNull(index + 1)?.minute?.minus(1) ?: Int.MAX_VALUE

    val resolvedMinute = when (index) {
        0 -> 0
        lastIndex -> minute.coerceAtLeast(previousMinute)
        else -> minute.coerceIn(previousMinute, nextMinute.coerceAtLeast(previousMinute))
    }
    val resolvedActivity = if (index == 0 || index == lastIndex) {
        0f
    } else {
        activity.coerceIn(0f, 1f)
    }

    updated[index] = JournalCurvePoint(
        minute = resolvedMinute,
        activity = resolvedActivity
    )
    return normalizeJournalCurvePoints(updated, fallbackDurationMinutes = updated.last().minute.coerceAtLeast(120))
}

private fun insertCurvePoint(
    points: List<JournalCurvePoint>,
    selectedIndex: Int
): List<JournalCurvePoint> {
    if (points.size < 2) return points
    val insertionBaseIndex = when {
        selectedIndex <= 0 -> 0
        selectedIndex >= points.lastIndex -> points.lastIndex - 1
        else -> selectedIndex
    }
    val left = points[insertionBaseIndex]
    val right = points[insertionBaseIndex + 1]
    val minuteGap = (right.minute - left.minute).coerceAtLeast(2)
    val insertedPoint = JournalCurvePoint(
        minute = left.minute + (minuteGap / 2),
        activity = when {
            left.activity == 0f && right.activity == 0f -> 0.35f
            else -> ((left.activity + right.activity) / 2f).coerceIn(0.1f, 0.9f)
        }
    )
    val updated = points.toMutableList().also { it.add(insertionBaseIndex + 1, insertedPoint) }
    return normalizeJournalCurvePoints(updated, fallbackDurationMinutes = updated.last().minute)
}

private fun deleteCurvePoint(
    points: List<JournalCurvePoint>,
    index: Int
): List<JournalCurvePoint> {
    if (!canDeleteCurvePoint(points, index)) return points
    return normalizeJournalCurvePoints(
        points.filterIndexed { pointIndex, _ -> pointIndex != index },
        fallbackDurationMinutes = points.last().minute
    )
}

private fun canDeleteCurvePoint(points: List<JournalCurvePoint>, index: Int): Boolean {
    return points.size > 3 && index in 1 until points.lastIndex
}

private fun findInsertedPointIndex(
    before: List<JournalCurvePoint>,
    after: List<JournalCurvePoint>,
    previousSelection: Int
): Int {
    val candidate = after.firstOrNull { point -> before.none { it.minute == point.minute && it.activity == point.activity } }
    return candidate?.let { inserted ->
        after.indexOfFirst { it.minute == inserted.minute && it.activity == inserted.activity }
    }?.takeIf { it >= 0 } ?: previousSelection.coerceIn(0, after.lastIndex)
}

private fun defaultSelectedPointIndex(points: List<JournalCurvePoint>): Int {
    return points.indices.maxByOrNull { points[it].activity }?.coerceAtLeast(1) ?: 1
}

private data class CurveBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private fun JournalCurvePoint.toOffset(bounds: CurveBounds, maxMinute: Float): Offset {
    val x = bounds.left + ((minute / maxMinute) * bounds.width)
    val y = bounds.bottom - (activity.coerceIn(0f, 1f) * bounds.height)
    return Offset(x, y)
}

private fun nearestCurvePointIndex(
    points: List<JournalCurvePoint>,
    chartSize: IntSize,
    touch: Offset,
    horizontalPaddingPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    requireHitRadius: Boolean = false
): Int? {
    if (points.isEmpty() || chartSize.width == 0 || chartSize.height == 0) return null
    val bounds = curveBounds(
        width = chartSize.width.toFloat(),
        height = chartSize.height.toFloat(),
        horizontalPaddingPx = horizontalPaddingPx,
        topInsetPx = topInsetPx,
        bottomInsetPx = bottomInsetPx
    )
    val maxMinute = points.last().minute.coerceAtLeast(1).toFloat()
    val nearest = points.indices.minByOrNull { index ->
        val pointOffset = points[index].toOffset(bounds, maxMinute)
        hypot((touch.x - pointOffset.x).toDouble(), (touch.y - pointOffset.y).toDouble())
    } ?: return null
    if (!requireHitRadius) return nearest
    val offset = points[nearest].toOffset(bounds, maxMinute)
    val distance = hypot((touch.x - offset.x).toDouble(), (touch.y - offset.y).toDouble())
    return nearest.takeIf { distance <= 36.0 }
}

private fun pointFromTouch(
    points: List<JournalCurvePoint>,
    index: Int,
    chartSize: IntSize,
    touch: Offset,
    horizontalPaddingPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float
): JournalCurvePoint {
    val bounds = curveBounds(
        width = chartSize.width.toFloat(),
        height = chartSize.height.toFloat(),
        horizontalPaddingPx = horizontalPaddingPx,
        topInsetPx = topInsetPx,
        bottomInsetPx = bottomInsetPx
    )
    val maxMinute = points.last().minute.coerceAtLeast(1)
    val normalizedX = ((touch.x - bounds.left) / bounds.width).coerceIn(0f, 1f)
    val normalizedY = ((bounds.bottom - touch.y) / bounds.height).coerceIn(0f, 1f)
    val minute = (normalizedX * maxMinute).roundToInt()
    return JournalCurvePoint(
        minute = minute,
        activity = normalizedY.coerceIn(0f, 1f)
    )
}

private fun curveBounds(
    width: Float,
    height: Float,
    horizontalPaddingPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float
): CurveBounds {
    return CurveBounds(
        left = horizontalPaddingPx,
        top = topInsetPx,
        right = width - horizontalPaddingPx,
        bottom = height - bottomInsetPx
    )
}

private fun cardPosition(index: Int, size: Int): CardPosition {
    return when {
        size <= 1 -> CardPosition.SINGLE
        index == 0 -> CardPosition.TOP
        index == size - 1 -> CardPosition.BOTTOM
        else -> CardPosition.MIDDLE
    }
}

private fun curveWindowSummary(points: List<JournalCurvePoint>): String {
    val onset = points.firstOrNull { it.activity > 0.01f }?.minute ?: 0
    val duration = points.lastOrNull()?.minute ?: 0
    return "${onset}m -> ${duration}m"
}

private fun defaultBuiltInProfile(sortOrder: Int): JournalBuiltInCurveProfile? = when (sortOrder) {
    0 -> JournalBuiltInCurveProfile.RAPID_GENERIC
    1 -> JournalBuiltInCurveProfile.LONG_BASAL_GENERIC
    2 -> JournalBuiltInCurveProfile.HUMAN_REGULAR
    3 -> JournalBuiltInCurveProfile.ASPART
    4 -> JournalBuiltInCurveProfile.LISPRO
    5 -> JournalBuiltInCurveProfile.GLULISINE
    6 -> JournalBuiltInCurveProfile.FIASP
    7 -> JournalBuiltInCurveProfile.URLI
    8 -> JournalBuiltInCurveProfile.AFREZZA
    9 -> JournalBuiltInCurveProfile.NPH
    10 -> JournalBuiltInCurveProfile.ULTRA_LONG_BASAL
    else -> null
}

private fun buildFoodDraft(food: JournalFood?): JournalFoodDraft {
    return if (food == null) {
        JournalFoodDraft()
    } else {
        JournalFoodDraft(
            id = food.id,
            displayName = food.displayName,
            carbsText = formatFoodNumber(food.carbsGrams),
            proteinText = food.proteinGrams?.let(::formatFoodNumber).orEmpty(),
            fatText = food.fatGrams?.let(::formatFoodNumber).orEmpty(),
            absorptionText = food.absorptionMinutes.toString(),
            accentColor = food.accentColor,
            sortOrder = food.sortOrder,
            isBuiltIn = food.isBuiltIn,
            isArchived = food.isArchived
        )
    }
}

private fun buildFoodInput(draft: JournalFoodDraft): JournalFoodInput? {
    val name = draft.displayName.trim().takeIf { it.isNotBlank() } ?: return null
    val carbs = draft.carbsText.parseFoodFloatOrNull() ?: return null
    val absorption = draft.absorptionText.toIntOrNull()?.coerceIn(15, 480) ?: return null
    return JournalFoodInput(
        id = draft.id,
        displayName = name,
        carbsGrams = carbs,
        proteinGrams = draft.proteinText.parseFoodFloatOrNull(),
        fatGrams = draft.fatText.parseFoodFloatOrNull(),
        absorptionMinutes = absorption,
        accentColor = draft.accentColor,
        isBuiltIn = draft.isBuiltIn,
        isArchived = draft.isArchived,
        sortOrder = draft.sortOrder
    )
}

private fun JournalFood.toLibraryFoodInput(sortOrder: Int): JournalFoodInput {
    return JournalFoodInput(
        displayName = displayName,
        carbsGrams = carbsGrams,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        absorptionMinutes = absorptionMinutes,
        accentColor = accentColor,
        isBuiltIn = false,
        isArchived = false,
        sortOrder = sortOrder
    )
}

private fun JournalFood.scaledForPortion(portionGrams: Float): JournalFood {
    val scale = (portionGrams / 100f).coerceIn(0.01f, 20f)
    return copy(
        carbsGrams = carbsGrams * scale,
        proteinGrams = proteinGrams?.let { it * scale },
        fatGrams = fatGrams?.let { it * scale }
    )
}

private fun List<JournalFood>.filterFoodQuery(query: String): List<JournalFood> {
    val needle = query.trim()
    if (needle.isBlank()) return this
    val normalizedNeedle = needle.lowercase(java.util.Locale.ROOT)
    val terms = normalizedNeedle
        .split(Regex("\\s+"))
        .filter { it.length >= 2 }
        .distinct()
    return mapNotNull { food ->
        val name = food.displayName.lowercase(java.util.Locale.ROOT)
        if (!terms.all { name.contains(it) }) return@mapNotNull null
        val score = when {
            name == normalizedNeedle -> 0
            name.startsWith(normalizedNeedle) -> 1
            name.contains(normalizedNeedle) -> 2
            else -> 3
        }
        RankedFoodLibraryMatch(food, score)
    }.sortedWith(
        compareBy<RankedFoodLibraryMatch> { it.score }
            .thenBy { it.food.displayName.length }
            .thenBy { it.food.displayName.lowercase(java.util.Locale.ROOT) }
    ).map { it.food }
}

private data class RankedFoodLibraryMatch(
    val food: JournalFood,
    val score: Int
)

@Composable
private fun foodLibrarySummary(food: JournalFood, foodMacrosEnabled: Boolean): String {
    return buildString {
        append(formatFoodNumber(food.carbsGrams))
        append(" g ")
        append(stringResource(R.string.carbo).trimTrailingLabel())
        if (foodMacrosEnabled) {
            append(" · ")
            append(formatFoodNumber(food.proteinGrams ?: 0f))
            append(" g ")
            append(stringResource(R.string.journal_food_protein))
            append(" · ")
            append(formatFoodNumber(food.fatGrams ?: 0f))
            append(" g ")
            append(stringResource(R.string.journal_food_fat))
        }
        append(" · ")
        append(stringResource(R.string.minutes_short_format, food.absorptionMinutes))
    }
}

private fun formatFoodNumber(value: Float): String {
    return if (kotlin.math.abs(value - value.roundToInt()) < 0.001f) {
        value.roundToInt().toString()
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f", value).trimEnd('0').trimEnd('.', ',')
    }
}

private fun adjustFoodNumberDraft(value: String, direction: Int, step: Float): String {
    val current = value.parseFoodFloatOrNull() ?: 0f
    return formatFoodNumber((current + direction * step).coerceAtLeast(0f))
}

private fun String.parseFoodFloatOrNull(): Float? {
    return trim().replace(',', '.').toFloatOrNull()?.coerceAtLeast(0f)
}

private fun parseFoodPortionGrams(value: String): Float {
    return value
        .parseFoodFloatOrNull()
        ?.coerceIn(1f, 2000f)
        ?: 100f
}

private val DEFAULT_PRESET_COLOR = 0xFF1565C0.toInt()
private val DEFAULT_FOOD_COLOR = 0xFF5F7D4B.toInt()

private fun String.trimTrailingLabel(): String {
    return trim().trimEnd(':').trim()
}
