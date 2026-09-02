@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package tk.glucodata.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.viewmodel.DashboardViewModel

private val legacySettingsHorizontalPadding = 16.dp
private val aodPositionOptions = listOf(
    "TOP" to R.string.position_top,
    "CENTER" to R.string.position_center,
    "BOTTOM" to R.string.position_bottom
)
private val aodAlignmentOptions = listOf(
    "LEFT" to R.string.alignment_left,
    "CENTER" to R.string.alignment_center,
    "RIGHT" to R.string.alignment_right
)

@Composable
fun NotificationSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }
    val notificationChartEnabled by viewModel.notificationChartEnabled.collectAsState()
    val deltaIntervalMinutes by viewModel.deltaIntervalMinutes.collectAsState()

    var fontSize by rememberSaveable { mutableFloatStateOf(prefs.getFloat("notification_font_size", 1.0f)) }
    var fontType by rememberSaveable { mutableIntStateOf(prefs.getInt("notification_font_family", 0)) }
    var fontWeight by rememberSaveable { mutableIntStateOf(prefs.getInt("notification_font_weight", 400)) }
    var showArrow by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_show_arrow", true)) }
    var largeArrow by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_large_trend_arrow", false)) }
    var arrowSize by rememberSaveable { mutableFloatStateOf(prefs.getFloat("notification_arrow_size", 1.0f)) }
    var collapsedChart by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_chart_collapsed", false)) }
    var showTargetRange by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_chart_target_range", true)) }
    var showIob by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_show_iob", false)) }
    var showCob by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_show_cob", false)) }
    var showDelta by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_show_delta", false)) }
    var iobCobRiskColored by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_iob_cob_risk_colored", false)) }
    var iobRiskWithoutCob by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_iob_risk_without_cob", false)) }
    var statusIconScale by rememberSaveable { mutableFloatStateOf(prefs.getFloat("notification_status_icon_scale", 1.0f)) }

    fun save() {
        prefs.edit()
            .putFloat("notification_font_size", fontSize)
            .putInt("notification_font_family", fontType)
            .putInt("notification_font_weight", fontWeight)
            .putBoolean("notification_show_arrow", showArrow)
            .putBoolean("notification_large_trend_arrow", largeArrow)
            .putFloat("notification_arrow_size", arrowSize)
            .putBoolean("notification_chart_collapsed", collapsedChart)
            .putBoolean("notification_chart_target_range", showTargetRange)
            .putBoolean("notification_show_iob", showIob)
            .putBoolean("notification_show_cob", showCob)
            .putBoolean("notification_show_delta", showDelta)
            .putBoolean("notification_iob_cob_risk_colored", iobCobRiskColored)
            .putBoolean("notification_iob_risk_without_cob", iobRiskWithoutCob)
            .putFloat("notification_status_icon_scale", statusIconScale)
            .apply()
        viewModel.refreshNotificationSurfaces()
    }

    LegacySettingsScaffold(
        navController = navController,
        title = stringResource(R.string.notification_settings_title)
    ) {
        SectionLabel(
            stringResource(R.string.typography),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )

        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = fontType == 0,
                onClick = { fontType = 0; save() },
                label = { Text(stringResource(R.string.font_app_plex)) }
            )
            FilterChip(
                selected = fontType == 1,
                onClick = { fontType = 1; save() },
                label = { Text(stringResource(R.string.font_system_google_sans)) }
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.font_weight_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding, vertical = 4.dp)
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    300 to stringResource(R.string.theme_light),
                    400 to stringResource(R.string.regular),
                    500 to stringResource(R.string.medium)
                ).forEach { (weight, label) ->
                    FilterChip(
                        selected = fontWeight == weight,
                        onClick = { fontWeight = weight; save() },
                        label = { Text(label) }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        LegacySliderControl(
            label = stringResource(R.string.font_size_percent, (fontSize * 100).toInt()),
            value = fontSize,
            onValueChange = { fontSize = it; save() },
            range = 0.6f..1.5f
        )
        Spacer(Modifier.height(4.dp))
        LegacySliderControl(
            label = stringResource(R.string.status_bar_icon_size_percent, (statusIconScale * 100).toInt()),
            value = statusIconScale,
            onValueChange = { statusIconScale = it; save() },
            range = 0f..1.25f
        )

        SectionLabel(
            stringResource(R.string.appearance),
            topPadding = 16.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.show_trend_arrow),
                checked = showArrow,
                onCheckedChange = { showArrow = it; save() },
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = stringResource(R.string.notification_large_arrow_title),
                subtitle = stringResource(R.string.notification_large_arrow_desc),
                checked = largeArrow,
                onCheckedChange = { largeArrow = it; save() },
                position = CardPosition.BOTTOM
            )
        }
        AnimatedVisibility(
            visible = showArrow,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LegacySliderControl(
                label = stringResource(R.string.arrow_size_percent, (arrowSize * 100).toInt()),
                value = arrowSize,
                onValueChange = { arrowSize = it; save() },
                range = 0.5f..1.5f
            )
        }

        val showAnyChart = notificationChartEnabled || collapsedChart
        SectionLabel(
            stringResource(R.string.notification_charts_section),
            topPadding = 16.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.show_chart_expanded),
                subtitle = stringResource(R.string.show_chart_expanded_desc),
                checked = notificationChartEnabled,
                onCheckedChange = { viewModel.toggleNotificationChart(it) },
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = stringResource(R.string.show_chart_collapsed),
                subtitle = stringResource(R.string.show_chart_collapsed_desc),
                checked = collapsedChart,
                onCheckedChange = { collapsedChart = it; save() },
                position = if (showAnyChart) CardPosition.MIDDLE else CardPosition.BOTTOM
            )
            AnimatedVisibility(
                visible = showAnyChart,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.show_target_range),
                    subtitle = stringResource(R.string.show_target_range_desc),
                    checked = showTargetRange,
                    onCheckedChange = { showTargetRange = it; save() },
                    position = CardPosition.BOTTOM
                )
            }
        }

        val showRiskOptions = showIob || showCob
        SectionLabel(
            stringResource(R.string.notification_status_line_section),
            topPadding = 16.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.notification_show_delta_title),
                subtitle = stringResource(R.string.notification_show_delta_minutes_desc, deltaIntervalMinutes),
                checked = showDelta,
                onCheckedChange = { showDelta = it; save() },
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = stringResource(R.string.notification_show_iob_title),
                subtitle = stringResource(R.string.notification_show_iob_desc),
                checked = showIob,
                onCheckedChange = { showIob = it; save() },
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.notification_show_cob_title),
                subtitle = stringResource(R.string.notification_show_cob_desc),
                checked = showCob,
                onCheckedChange = { showCob = it; save() },
                position = if (showRiskOptions) CardPosition.MIDDLE else CardPosition.BOTTOM
            )
            AnimatedVisibility(
                visible = showRiskOptions,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.notification_iob_cob_risk_title),
                    subtitle = stringResource(R.string.notification_iob_cob_risk_desc),
                    checked = iobCobRiskColored,
                    onCheckedChange = { iobCobRiskColored = it; save() },
                    position = if (iobCobRiskColored) CardPosition.MIDDLE else CardPosition.BOTTOM
                )
            }
            AnimatedVisibility(
                visible = showRiskOptions && iobCobRiskColored,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.notification_iob_risk_without_cob_title),
                    subtitle = stringResource(R.string.notification_iob_risk_without_cob_desc),
                    checked = iobRiskWithoutCob,
                    onCheckedChange = { iobRiskWithoutCob = it; save() },
                    position = CardPosition.BOTTOM
                )
            }
        }
    }
}

@Composable
fun DisplayAndColorSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val rangeColorsEnabled by viewModel.glucoseValueRangeColorsEnabled.collectAsState()
    val arrowForecastEnabled by viewModel.glucoseArrowForecastColorsEnabled.collectAsState()
    val chartRangeColorsEnabled by viewModel.glucoseChartRangeColorsEnabled.collectAsState()
    val appChartRangeColorsEnabled by viewModel.glucoseAppChartRangeColorsEnabled.collectAsState()
    val dashboardDeltaEnabled by viewModel.dashboardShowDelta.collectAsState()
    val dashboardRowsDeltaEnabled by viewModel.dashboardRowsShowDelta.collectAsState()
    val matchArrowToDisplayedDelta by viewModel.matchArrowToDisplayedDelta.collectAsState()
    val previewWindowMode by viewModel.previewWindowMode.collectAsState()
    var showPreviewWindowDialog by rememberSaveable { mutableStateOf(false) }
    val previewWindowLabel = when (previewWindowMode) {
        1 -> stringResource(R.string.preview_window_always)
        2 -> stringResource(R.string.preview_window_never)
        else -> stringResource(R.string.preview_window_expanded_only)
    }

    LegacySettingsScaffold(
        navController = navController,
        title = stringResource(R.string.display_colors_title)
    ) {
        GlucoseRangeDisplaySettings(viewModel)

        SectionLabel(
            stringResource(R.string.glucose_palette_edit_title),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.glucose_range_colors_title),
                subtitle = stringResource(R.string.glucose_range_colors_desc),
                checked = rangeColorsEnabled,
                onCheckedChange = { viewModel.setGlucoseValueRangeColorsEnabled(it) },
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = stringResource(R.string.glucose_arrow_forecast_title),
                subtitle = stringResource(R.string.glucose_arrow_forecast_desc),
                checked = arrowForecastEnabled,
                onCheckedChange = { viewModel.setGlucoseArrowForecastColorsEnabled(it) },
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.glucose_chart_range_colors_title),
                subtitle = stringResource(R.string.glucose_chart_range_colors_desc),
                checked = chartRangeColorsEnabled,
                onCheckedChange = { viewModel.setGlucoseChartRangeColorsEnabled(it) },
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.glucose_app_chart_range_colors_title),
                subtitle = stringResource(R.string.glucose_app_chart_range_colors_desc),
                checked = appChartRangeColorsEnabled,
                onCheckedChange = { viewModel.setGlucoseAppChartRangeColorsEnabled(it) },
                position = CardPosition.BOTTOM
            )
        }

        SectionLabel(
            stringResource(R.string.display_readouts_section),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        ) {
            SettingsItem(
                title = stringResource(R.string.preview_window_title),
                subtitle = previewWindowLabel,
                showArrow = true,
                icon = Icons.Default.CropSquare,
                iconTint = MaterialTheme.colorScheme.primary,
                position = CardPosition.TOP,
                onClick = { showPreviewWindowDialog = true }
            )
            SettingsSwitchItem(
                title = stringResource(R.string.dashboard_show_delta_title),
                subtitle = stringResource(R.string.dashboard_show_delta_desc),
                checked = dashboardDeltaEnabled,
                onCheckedChange = { viewModel.setDashboardShowDelta(it) },
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.match_arrow_to_displayed_delta_title),
                subtitle = stringResource(R.string.match_arrow_to_displayed_delta_desc),
                checked = matchArrowToDisplayedDelta,
                onCheckedChange = { viewModel.setMatchArrowToDisplayedDelta(it) },
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.dashboard_rows_show_delta_title),
                subtitle = stringResource(R.string.dashboard_rows_show_delta_desc),
                checked = dashboardRowsDeltaEnabled,
                onCheckedChange = { viewModel.setDashboardRowsShowDelta(it) },
                position = CardPosition.BOTTOM
            )
        }

    }

    if (showPreviewWindowDialog) {
        PreviewWindowPickerDialog(
            currentMode = previewWindowMode,
            onSelect = {
                viewModel.setPreviewWindowMode(it)
                showPreviewWindowDialog = false
            },
            onDismiss = { showPreviewWindowDialog = false }
        )
    }
}

@Composable
fun FloatingGlucoseSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val context = LocalContext.current
    val repository = remember { viewModel.floatingRepository }

    val isEnabled by repository.isEnabled.collectAsState(initial = false)
    val isTransparent by repository.isTransparent.collectAsState(initial = false)
    val showSecondary by repository.showSecondary.collectAsState(initial = false)
    val fontSource by repository.fontSource.collectAsState(initial = "APP")
    val fontSize by repository.fontSize.collectAsState(initial = FloatingSettingsRepository.DEFAULT_FONT_SIZE)
    val fontWeight by repository.fontWeight.collectAsState(initial = "REGULAR")
    val showArrow by repository.showArrow.collectAsState(initial = true)
    val cornerRadius by repository.cornerRadius.collectAsState(initial = 28f)
    val opacity by repository.backgroundOpacity.collectAsState(initial = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
    val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsState(initial = false)
    val verticalOffset by repository.islandVerticalOffset.collectAsState(initial = FloatingSettingsRepository.DEFAULT_ISLAND_VERTICAL_OFFSET)
    val manualGap by repository.islandGap.collectAsState(initial = 0f)
    val useSubtleOutline by repository.useSubtleOutline.collectAsState(initial = false)
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = Settings.canDrawOverlays(context)
    }

    LegacySettingsScaffold(navController = navController, title = stringResource(R.string.floatglucose)) {
        MasterSwitchCard(
            title = stringResource(R.string.enable_overlay),
            subtitle = if (hasPermission) {
                stringResource(R.string.enable_overlay_desc)
            } else {
                stringResource(R.string.floating_permission_required)
            },
            checked = isEnabled,
            onCheckedChange = { enabled ->
                if (enabled && !hasPermission) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    viewModel.toggleFloatingGlucose(enabled)
                }
            },
            icon = Icons.Default.Layers,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )

        if (isEnabled && !hasPermission) {
            Spacer(Modifier.height(16.dp))
            WarningPanel(
                text = stringResource(R.string.floating_permission_required),
                modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel(
            stringResource(R.string.appearance),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            stringResource(R.string.background),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                FilterChip(
                    selected = !isTransparent,
                    onClick = { repository.setTransparent(false) },
                    label = { Text(stringResource(R.string.filled)) }
                )
                FilterChip(
                    selected = isTransparent,
                    onClick = { repository.setTransparent(true) },
                    label = { Text(stringResource(R.string.transparent)) }
                )
        }

        if (!isTransparent) {
            Spacer(modifier = Modifier.height(8.dp))
            LegacySliderControl(
                label = stringResource(R.string.corner_radius_dp, cornerRadius.toInt()),
                value = cornerRadius,
                onValueChange = { repository.setCornerRadius(it) },
                range = 0f..48f,
                steps = 24
            )
            Spacer(modifier = Modifier.height(8.dp))
            LegacySliderControl(
                label = stringResource(R.string.background_opacity_percent, (opacity * 100).toInt()),
                value = opacity,
                onValueChange = { repository.setBackgroundOpacity(it) },
                range = 0.1f..1.0f,
                steps = 18
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        SettingsSwitchItem(
            title = stringResource(R.string.use_subtle_outline),
            subtitle = stringResource(R.string.use_subtle_outline_desc),
            checked = useSubtleOutline,
            onCheckedChange = { repository.setUseSubtleOutline(it) },
            position = CardPosition.SINGLE,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )

        Spacer(modifier = Modifier.height(8.dp))
        SectionLabel(
            stringResource(R.string.position_layout),
            topPadding = 16.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSwitchItem(
            title = stringResource(R.string.dynamic_island_mode),
            subtitle = stringResource(R.string.dynamic_island_desc),
            checked = isDynamicIsland,
            onCheckedChange = { repository.setDynamicIslandEnabled(it) },
            position = CardPosition.SINGLE,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )

        if (isDynamicIsland) {
            Spacer(modifier = Modifier.height(8.dp))
            val offsetLabel = "${stringResource(R.string.offset)}: ${stringResource(R.string.dp_value, verticalOffset.toInt())}"
            LegacySliderControl(
                label = offsetLabel,
                value = verticalOffset,
                onValueChange = { repository.setIslandVerticalOffset(it) },
                range = 0f..50f,
                steps = 50
            )

            Spacer(modifier = Modifier.height(4.dp))
            val gapDisplay = if (manualGap == 0f) stringResource(R.string.auto) else stringResource(R.string.dp_value, manualGap.toInt())
            LegacySliderControl(
                label = stringResource(R.string.gap_width_value, gapDisplay),
                value = manualGap,
                onValueChange = { repository.setIslandGap(it) },
                range = 0f..200f,
                steps = 40
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel(
            stringResource(R.string.metrics),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.display_secondary_values),
                subtitle = stringResource(R.string.display_secondary_values_desc),
                checked = showSecondary,
                onCheckedChange = { repository.setShowSecondary(it) },
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = stringResource(R.string.show_trend_arrow),
                checked = showArrow,
                onCheckedChange = { repository.setShowArrow(it) },
                position = CardPosition.BOTTOM
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel(
            stringResource(R.string.typography),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            stringResource(R.string.font_source),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                FilterChip(
                    selected = fontSource == "APP",
                    onClick = { repository.setFontSource("APP") },
                    label = { Text(stringResource(R.string.font_app_plex)) }
                )
                FilterChip(
                    selected = fontSource == "SYSTEM",
                    onClick = { repository.setFontSource("SYSTEM") },
                    label = { Text(stringResource(R.string.font_system_sans)) }
                )
        }

        Spacer(modifier = Modifier.height(24.dp))
        LegacySliderControl(
            label = stringResource(R.string.size_sp, fontSize.toInt()),
            value = fontSize,
            onValueChange = { repository.setFontSize(it) },
            range = 12f..48f,
            steps = 35
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.font_weight_label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("LIGHT", "REGULAR", "MEDIUM").forEach { weight ->
                val labelRes = when (weight) {
                    "LIGHT" -> R.string.theme_light
                    "REGULAR" -> R.string.regular
                    else -> R.string.medium
                }
                FilterChip(
                    selected = fontWeight == weight,
                    onClick = { repository.setFontWeight(weight) },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
    }
}

@Composable
fun AodSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }
    var serviceEnabled by remember { mutableStateOf(isAodAccessibilityEnabled(context)) }

    var opacity by rememberSaveable { mutableFloatStateOf(prefs.getFloat("aod_opacity", 1.0f)) }
    var textScale by rememberSaveable { mutableFloatStateOf(prefs.getFloat("aod_text_scale", 1.5f)) }
    var chartScale by rememberSaveable { mutableFloatStateOf(prefs.getFloat("aod_chart_scale", 1.5f)) }
    var showChart by rememberSaveable { mutableStateOf(prefs.getBoolean("aod_show_chart", true)) }
    var showArrow by rememberSaveable { mutableStateOf(prefs.getBoolean("aod_show_arrow", true)) }
    var showSecondary by rememberSaveable { mutableStateOf(prefs.getBoolean("aod_show_secondary", false)) }
    var arrowScale by rememberSaveable { mutableFloatStateOf(prefs.getFloat("aod_arrow_scale", 1.0f)) }
    var positions by rememberSaveable { mutableStateOf(prefs.getStringSet("aod_positions", setOf("TOP")) ?: setOf("TOP")) }
    var alignment by rememberSaveable { mutableStateOf(prefs.getString("aod_alignment", "CENTER") ?: "CENTER") }
    var fontSource by rememberSaveable { mutableStateOf(prefs.getString("aod_font_source", "APP") ?: "APP") }
    var fontWeight by rememberSaveable { mutableIntStateOf(prefs.getInt("aod_font_weight", 400)) }

    fun save() {
        prefs.edit()
            .putFloat("aod_opacity", opacity)
            .putFloat("aod_text_scale", textScale)
            .putFloat("aod_chart_scale", chartScale)
            .putBoolean("aod_show_chart", showChart)
            .putBoolean("aod_show_arrow", showArrow)
            .putBoolean("aod_show_secondary", showSecondary)
            .putFloat("aod_arrow_scale", arrowScale)
            .putStringSet("aod_positions", positions)
            .putString("aod_alignment", alignment)
            .putString("aod_font_source", fontSource)
            .putInt("aod_font_weight", fontWeight)
            .apply()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        serviceEnabled = isAodAccessibilityEnabled(context)
    }

    LegacySettingsScaffold(
        navController = navController,
        title = stringResource(R.string.aod_settings_title)
    ) {
        MasterSwitchCard(
            title = stringResource(R.string.enable_overlay_service),
            subtitle = if (serviceEnabled) {
                stringResource(R.string.accessibility_service_enabled)
            } else {
                stringResource(R.string.open_accessibility_settings)
            },
            checked = serviceEnabled,
            onCheckedChange = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            icon = Icons.Default.SettingsAccessibility,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.visuals),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding, vertical = 8.dp)
        )

        Text(
            stringResource(R.string.font_source),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding, vertical = 4.dp)
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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

        Text(
            stringResource(R.string.font_weight_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding, vertical = 4.dp)
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                300 to stringResource(R.string.theme_light),
                400 to stringResource(R.string.regular),
                500 to stringResource(R.string.medium)
            ).forEach { (weight, label) ->
                FilterChip(
                    selected = fontWeight == weight,
                    onClick = { fontWeight = weight; save() },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel(
            stringResource(R.string.position_layout),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Text(
            stringResource(R.string.active_positions_randomized),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding, vertical = 4.dp)
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            aodPositionOptions.forEach { (pos, labelRes) ->
                FilterChip(
                    selected = positions.contains(pos),
                    onClick = {
                        val newSet = positions.toMutableSet()
                        if (newSet.contains(pos)) {
                            if (newSet.size > 1) newSet.remove(pos)
                        } else {
                            newSet.add(pos)
                        }
                        positions = newSet
                        save()
                    },
                    label = { Text(stringResource(labelRes)) },
                    leadingIcon = if (positions.contains(pos)) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.text_alignment),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding, vertical = 4.dp)
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            aodAlignmentOptions.forEach { (align, labelRes) ->
                FilterChip(
                    selected = alignment == align,
                    onClick = { alignment = align; save() },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(
            stringResource(R.string.appearance),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        )
        Spacer(Modifier.height(8.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = legacySettingsHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.show_chart),
                checked = showChart,
                onCheckedChange = { showChart = it; save() },
                icon = null,
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = stringResource(R.string.show_trend_arrow),
                checked = showArrow,
                onCheckedChange = { showArrow = it; save() },
                icon = null,
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.display_secondary_values),
                subtitle = stringResource(R.string.display_secondary_values_desc),
                checked = showSecondary,
                onCheckedChange = { showSecondary = it; save() },
                icon = null,
                position = CardPosition.BOTTOM
            )
        }

        LegacySliderControl(
            label = stringResource(R.string.opacity_percent, (opacity * 100).toInt()),
            value = opacity,
            onValueChange = { opacity = it; save() },
            range = 0.1f..1.0f
        )
        LegacySliderControl(
            label = stringResource(R.string.text_size_percent, (textScale * 100).toInt()),
            value = textScale,
            onValueChange = { textScale = it; save() },
            range = 0.5f..6.0f
        )
        if (showChart) {
            LegacySliderControl(
                label = stringResource(R.string.chart_size_percent, (chartScale * 100).toInt()),
                value = chartScale,
                onValueChange = { chartScale = it; save() },
                range = 0.5f..2.0f
            )
        }
        if (showArrow) {
            LegacySliderControl(
                label = stringResource(R.string.arrow_size_percent, (arrowScale * 100).toInt()),
                value = arrowScale,
                onValueChange = { arrowScale = it; save() },
                range = 0.5f..2.0f
            )
        }
    }
}

@Composable
private fun LegacySettingsScaffold(
    navController: NavController,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
    Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.Top,
            content = content
        )
    }
}

@Composable
private fun LegacySliderControl(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column(Modifier.padding(horizontal = legacySettingsHorizontalPadding, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun WarningPanel(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun isAodAccessibilityEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
        info.resolveInfo?.serviceInfo?.packageName == context.packageName &&
            info.resolveInfo?.serviceInfo?.name == tk.glucodata.accessibility.AODOverlayService::class.java.name
    }
}
