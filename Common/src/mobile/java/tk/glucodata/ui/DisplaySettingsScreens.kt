@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.accessibility.AODOverlayService
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsChoice
import tk.glucodata.ui.components.SettingsControlsCard
import tk.glucodata.ui.components.SettingsSlider
import tk.glucodata.ui.viewmodel.DashboardViewModel

private val screenHorizontalPadding = 16.dp
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
    val notificationChartEnabled by viewModel.notificationChartEnabled.collectAsStateWithLifecycle()
    val deltaIntervalMinutes by viewModel.deltaIntervalMinutes.collectAsStateWithLifecycle()

    var fontSize by rememberSaveable { mutableFloatStateOf(prefs.getFloat("notification_font_size", 1.0f)) }
    var fontType by rememberSaveable { mutableIntStateOf(prefs.getInt("notification_font_family", 0)) }
    var fontWeight by rememberSaveable { mutableIntStateOf(prefs.getInt("notification_font_weight", 400)) }
    var showArrow by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_show_arrow", true)) }
    var largeArrow by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_large_trend_arrow", false)) }
    var arrowSize by rememberSaveable { mutableFloatStateOf(prefs.getFloat("notification_arrow_size", 1.0f)) }
    var collapsedChart by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_chart_collapsed", true)) }
    var pauseChartScreenOff by rememberSaveable { mutableStateOf(prefs.getBoolean("notification_chart_pause_screen_off", false)) }
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
            .putBoolean("notification_chart_pause_screen_off", pauseChartScreenOff)
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

    SettingsScreenScaffold(
        navController = navController,
        title = stringResource(R.string.notification_settings_title)
    ) {
        val showFontWeight = android.os.Build.VERSION.SDK_INT >= 31
        SectionLabel(
            stringResource(R.string.typography),
            topPadding = 0.dp,
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        SettingsControlsCard(modifier = Modifier.padding(horizontal = screenHorizontalPadding)) {
            SettingsChoice(
                title = stringResource(R.string.font_source),
                options = listOf(0, 1),
                labels = listOf(
                    stringResource(R.string.font_app_plex),
                    stringResource(R.string.font_system_google_sans)
                ),
                selectedOption = fontType,
                onOptionSelected = { fontType = it; save() }
            )
            if (showFontWeight) {
                SettingsChoice(
                    title = stringResource(R.string.font_weight_label),
                    options = listOf(300, 400, 500),
                    labels = listOf(
                        stringResource(R.string.theme_light),
                        stringResource(R.string.regular),
                        stringResource(R.string.medium)
                    ),
                    selectedOption = fontWeight,
                    onOptionSelected = { fontWeight = it; save() }
                )
            }
            SettingsSlider(
                title = stringResource(R.string.font_size_percent, (fontSize * 100).toInt()),
                value = fontSize,
                onValueChange = { fontSize = it; save() },
                range = 0.6f..1.5f
            )
            SettingsSlider(
                title = stringResource(R.string.status_bar_icon_size_percent, (statusIconScale * 100).toInt()),
                value = statusIconScale,
                onValueChange = { statusIconScale = it; save() },
                range = 0f..1.25f
            )
        }

        SectionLabel(
            stringResource(R.string.appearance),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.show_trend_arrow),
                checked = showArrow,
                onCheckedChange = { showArrow = it; save() },
                position = CardPosition.TOP,
                searchAnchor = SettingsSearchAnchors.NOTIFICATION_ARROW
            )
            SettingsSwitchItem(
                title = stringResource(R.string.notification_large_arrow_title),
                subtitle = stringResource(R.string.notification_large_arrow_desc),
                checked = largeArrow,
                onCheckedChange = { largeArrow = it; save() },
                position = if (showArrow) CardPosition.MIDDLE else CardPosition.BOTTOM,
                animatePosition = true
            )
            AnimatedVisibility(
                visible = showArrow,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SettingsControlsCard(position = CardPosition.BOTTOM) {
                    SettingsSlider(
                        title = stringResource(R.string.arrow_size_percent, (arrowSize * 100).toInt()),
                        value = arrowSize,
                        onValueChange = { arrowSize = it; save() },
                        range = 0.5f..1.5f
                    )
                }
            }
        }

        val showAnyChart = notificationChartEnabled || collapsedChart
        SectionLabel(
            stringResource(R.string.notification_charts_section),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.show_chart_expanded),
                subtitle = stringResource(R.string.show_chart_expanded_desc),
                checked = notificationChartEnabled,
                onCheckedChange = { viewModel.toggleNotificationChart(it) },
                position = CardPosition.TOP,
                searchAnchor = SettingsSearchAnchors.NOTIFICATION_CHART
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.show_target_range),
                        subtitle = stringResource(R.string.show_target_range_desc),
                        checked = showTargetRange,
                        onCheckedChange = { showTargetRange = it; save() },
                        position = CardPosition.MIDDLE
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.notification_chart_pause_screen_off),
                        subtitle = stringResource(R.string.notification_chart_pause_screen_off_summary),
                        checked = pauseChartScreenOff,
                        onCheckedChange = { pauseChartScreenOff = it; save() },
                        position = CardPosition.BOTTOM
                    )
                }
            }
        }

        val showRiskOptions = showIob || showCob
        SectionLabel(
            stringResource(R.string.notification_status_line_section),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.notification_show_delta_title),
                subtitle = stringResource(R.string.notification_show_delta_minutes_desc, deltaIntervalMinutes),
                checked = showDelta,
                onCheckedChange = { showDelta = it; save() },
                position = CardPosition.TOP,
                searchAnchor = SettingsSearchAnchors.NOTIFICATION_DELTA
            )
            SettingsSwitchItem(
                title = stringResource(R.string.notification_show_iob_title),
                subtitle = stringResource(R.string.notification_show_iob_desc),
                checked = showIob,
                onCheckedChange = { showIob = it; save() },
                position = CardPosition.MIDDLE,
                searchAnchor = SettingsSearchAnchors.NOTIFICATION_IOB
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
    val rangeColorsEnabled by viewModel.glucoseValueRangeColorsEnabled.collectAsStateWithLifecycle()
    val arrowForecastEnabled by viewModel.glucoseArrowForecastColorsEnabled.collectAsStateWithLifecycle()
    val chartRangeColorsEnabled by viewModel.glucoseChartRangeColorsEnabled.collectAsStateWithLifecycle()
    val appChartRangeColorsEnabled by viewModel.glucoseAppChartRangeColorsEnabled.collectAsStateWithLifecycle()
    val dashboardDeltaEnabled by viewModel.dashboardShowDelta.collectAsStateWithLifecycle()
    val dashboardRowsDeltaEnabled by viewModel.dashboardRowsShowDelta.collectAsStateWithLifecycle()
    val dashboardReadingAgeEnabled by viewModel.dashboardShowReadingAge.collectAsStateWithLifecycle()
    val previewWindowMode by viewModel.previewWindowMode.collectAsStateWithLifecycle()
    var showPreviewWindowDialog by rememberSaveable { mutableStateOf(false) }
    val previewWindowLabel = when (previewWindowMode) {
        1 -> stringResource(R.string.preview_window_always)
        2 -> stringResource(R.string.preview_window_never)
        else -> stringResource(R.string.preview_window_expanded_only)
    }

    SettingsScreenScaffold(
        navController = navController,
        title = stringResource(R.string.settings_dashboard_charts_title)
    ) {
        GlucoseRangeDisplaySettings(viewModel)

        SectionLabel(
            stringResource(R.string.glucose_palette_edit_title),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.glucose_range_colors_title),
                subtitle = stringResource(R.string.glucose_range_colors_desc),
                checked = rangeColorsEnabled,
                onCheckedChange = { viewModel.setGlucoseValueRangeColorsEnabled(it) },
                position = CardPosition.TOP,
                searchAnchor = SettingsSearchAnchors.RANGE_COLORS
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
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsItem(
                title = stringResource(R.string.preview_window_title),
                subtitle = previewWindowLabel,
                icon = Icons.Default.CropSquare,
                iconTint = MaterialTheme.colorScheme.primary,
                position = CardPosition.TOP,
                searchAnchor = SettingsSearchAnchors.PREVIEW_WINDOW,
                onClick = { showPreviewWindowDialog = true }
            )
            SettingsSwitchItem(
                title = stringResource(R.string.dashboard_show_delta_title),
                subtitle = stringResource(R.string.dashboard_show_delta_desc),
                checked = dashboardDeltaEnabled,
                onCheckedChange = { viewModel.setDashboardShowDelta(it) },
                position = CardPosition.MIDDLE,
                searchAnchor = SettingsSearchAnchors.DASHBOARD_DELTA
            )
            SettingsSwitchItem(
                title = stringResource(R.string.dashboard_rows_show_delta_title),
                subtitle = stringResource(R.string.dashboard_rows_show_delta_desc),
                checked = dashboardRowsDeltaEnabled,
                onCheckedChange = { viewModel.setDashboardRowsShowDelta(it) },
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.dashboard_show_reading_age_title),
                subtitle = stringResource(R.string.dashboard_show_reading_age_desc),
                checked = dashboardReadingAgeEnabled,
                onCheckedChange = { viewModel.setDashboardShowReadingAge(it) },
                position = CardPosition.BOTTOM,
                searchAnchor = SettingsSearchAnchors.READING_AGE
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

    val isEnabled by repository.isEnabled.collectAsStateWithLifecycle(initialValue = false)
    val isTransparent by repository.isTransparent.collectAsStateWithLifecycle(initialValue = false)
    val showSecondary by repository.showSecondary.collectAsStateWithLifecycle(initialValue = false)
    val fontSource by repository.fontSource.collectAsStateWithLifecycle(initialValue = "APP")
    val fontSize by repository.fontSize.collectAsStateWithLifecycle(initialValue = FloatingSettingsRepository.DEFAULT_FONT_SIZE)
    val fontWeight by repository.fontWeight.collectAsStateWithLifecycle(initialValue = "REGULAR")
    val showArrow by repository.showArrow.collectAsStateWithLifecycle(initialValue = true)
    val cornerRadius by repository.cornerRadius.collectAsStateWithLifecycle(initialValue = 28f)
    val opacity by repository.backgroundOpacity.collectAsStateWithLifecycle(initialValue = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
    val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsStateWithLifecycle(initialValue = false)
    val verticalOffset by repository.islandVerticalOffset.collectAsStateWithLifecycle(initialValue = FloatingSettingsRepository.DEFAULT_ISLAND_VERTICAL_OFFSET)
    val manualGap by repository.islandGap.collectAsStateWithLifecycle(initialValue = 0f)
    val useSubtleOutline by repository.useSubtleOutline.collectAsStateWithLifecycle(initialValue = false)
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = Settings.canDrawOverlays(context)
    }

    SettingsScreenScaffold(navController = navController, title = stringResource(R.string.floatglucose)) {
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
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )

        if (isEnabled && !hasPermission) {
            Spacer(Modifier.height(16.dp))
            WarningPanel(
                text = stringResource(R.string.floating_permission_required),
                modifier = Modifier.padding(horizontal = screenHorizontalPadding)
            )
        }

        SectionLabel(
            stringResource(R.string.appearance),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsControlsCard(position = CardPosition.TOP) {
                SettingsChoice(
                    title = stringResource(R.string.background),
                    options = listOf(false, true),
                    labels = listOf(stringResource(R.string.filled), stringResource(R.string.transparent)),
                    selectedOption = isTransparent,
                    onOptionSelected = { repository.setTransparent(it) }
                )
                if (!isTransparent) {
                    SettingsSlider(
                        title = stringResource(R.string.corner_radius_dp, cornerRadius.toInt()),
                        value = cornerRadius,
                        onValueChange = { repository.setCornerRadius(it) },
                        range = 0f..48f,
                        steps = 24
                    )
                    SettingsSlider(
                        title = stringResource(R.string.background_opacity_percent, (opacity * 100).toInt()),
                        value = opacity,
                        onValueChange = { repository.setBackgroundOpacity(it) },
                        range = 0.1f..1.0f,
                        steps = 18
                    )
                }
            }
            SettingsSwitchItem(
                title = stringResource(R.string.use_subtle_outline),
                subtitle = stringResource(R.string.use_subtle_outline_desc),
                checked = useSubtleOutline,
                onCheckedChange = { repository.setUseSubtleOutline(it) },
                position = CardPosition.BOTTOM
            )
        }

        SectionLabel(
            stringResource(R.string.position_layout),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.dynamic_island_mode),
                subtitle = stringResource(R.string.dynamic_island_desc),
                checked = isDynamicIsland,
                onCheckedChange = { repository.setDynamicIslandEnabled(it) },
                position = if (isDynamicIsland) CardPosition.TOP else CardPosition.SINGLE,
                animatePosition = true
            )
            if (isDynamicIsland) {
                SettingsControlsCard(position = CardPosition.BOTTOM) {
                    SettingsSlider(
                        title = "${stringResource(R.string.offset)}: ${stringResource(R.string.dp_value, verticalOffset.toInt())}",
                        value = verticalOffset,
                        onValueChange = { repository.setIslandVerticalOffset(it) },
                        range = 0f..50f,
                        steps = 50
                    )
                    val gapDisplay = if (manualGap == 0f) stringResource(R.string.auto) else stringResource(R.string.dp_value, manualGap.toInt())
                    SettingsSlider(
                        title = stringResource(R.string.gap_width_value, gapDisplay),
                        value = manualGap,
                        onValueChange = { repository.setIslandGap(it) },
                        range = 0f..200f,
                        steps = 40
                    )
                }
            }
        }

        if (!isDynamicIsland) {
            Column(modifier = Modifier.padding(start = screenHorizontalPadding, end = screenHorizontalPadding, top = 16.dp)) {
                OutlinedButton(
                    onClick = { repository.resetPosition() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text(stringResource(R.string.floating_reset_position))
                }
                Text(
                    stringResource(R.string.floating_reset_position_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        SectionLabel(
            stringResource(R.string.metrics),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
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

        SectionLabel(
            stringResource(R.string.typography),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        SettingsControlsCard(modifier = Modifier.padding(horizontal = screenHorizontalPadding)) {
            SettingsChoice(
                title = stringResource(R.string.font_source),
                options = listOf("APP", "SYSTEM"),
                labels = listOf(stringResource(R.string.font_app_plex), stringResource(R.string.font_system_sans)),
                selectedOption = fontSource,
                onOptionSelected = { repository.setFontSource(it) }
            )
            SettingsSlider(
                title = stringResource(R.string.size_sp, fontSize.toInt()),
                value = fontSize,
                onValueChange = { repository.setFontSize(it) },
                range = 12f..48f,
                steps = 35
            )
            SettingsChoice(
                title = stringResource(R.string.font_weight_label),
                options = listOf("LIGHT", "REGULAR", "MEDIUM"),
                labels = listOf(
                    stringResource(R.string.theme_light),
                    stringResource(R.string.regular),
                    stringResource(R.string.medium)
                ),
                selectedOption = fontWeight,
                onOptionSelected = { repository.setFontWeight(it) }
            )
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
    var showOnLockscreen by rememberSaveable {
        mutableStateOf(
            prefs.getBoolean(
                AODOverlayService.PREF_SHOW_ON_LOCKSCREEN,
                AODOverlayService.DEFAULT_SHOW_ON_LOCKSCREEN
            )
        )
    }

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
            .putBoolean(AODOverlayService.PREF_SHOW_ON_LOCKSCREEN, showOnLockscreen)
            .apply()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        serviceEnabled = isAodAccessibilityEnabled(context)
    }

    SettingsScreenScaffold(
        navController = navController,
        title = stringResource(R.string.settings_lock_screen_title)
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
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitchItem(
            title = stringResource(R.string.aod_show_on_lockscreen),
            subtitle = stringResource(R.string.aod_show_on_lockscreen_desc),
            checked = showOnLockscreen,
            onCheckedChange = {
                showOnLockscreen = it
                save()
                context.sendBroadcast(
                    Intent(AODOverlayService.ACTION_IMMEDIATE_REFRESH).setPackage(context.packageName)
                )
            },
            icon = Icons.Default.Lock,
            position = CardPosition.SINGLE,
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )

        SectionLabel(
            stringResource(R.string.typography),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        SettingsControlsCard(modifier = Modifier.padding(horizontal = screenHorizontalPadding)) {
            SettingsChoice(
                title = stringResource(R.string.font_source),
                options = listOf("APP", "SYSTEM"),
                labels = listOf(stringResource(R.string.font_app_plex), stringResource(R.string.font_system_google_sans)),
                selectedOption = fontSource,
                onOptionSelected = { fontSource = it; save() }
            )
            SettingsChoice(
                title = stringResource(R.string.font_weight_label),
                options = listOf(300, 400, 500),
                labels = listOf(
                    stringResource(R.string.theme_light),
                    stringResource(R.string.regular),
                    stringResource(R.string.medium)
                ),
                selectedOption = fontWeight,
                onOptionSelected = { fontWeight = it; save() }
            )
        }

        SectionLabel(
            stringResource(R.string.position_layout),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        SettingsControlsCard(modifier = Modifier.padding(horizontal = screenHorizontalPadding)) {
            SettingsChoice(
                title = stringResource(R.string.active_positions_randomized),
                options = aodPositionOptions.map { it.first },
                labels = aodPositionOptions.map { stringResource(it.second) },
                selectedOptions = aodPositionOptions.map { it.first }.filter { it in positions },
                multiSelect = true,
                onOptionSelected = { pos ->
                    // At least one position stays on.
                    val newSet = positions.toMutableSet()
                    if (newSet.contains(pos)) {
                        if (newSet.size > 1) newSet.remove(pos)
                    } else {
                        newSet.add(pos)
                    }
                    positions = newSet
                    save()
                }
            )
            SettingsChoice(
                title = stringResource(R.string.text_alignment),
                options = aodAlignmentOptions.map { it.first },
                labels = aodAlignmentOptions.map { stringResource(it.second) },
                selectedOption = alignment,
                onOptionSelected = { alignment = it; save() }
            )
        }

        // Switches first, then the sliders; the chart and arrow sliders only
        // while their element is shown.
        SectionLabel(
            stringResource(R.string.appearance),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = screenHorizontalPadding)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.show_chart),
                checked = showChart,
                onCheckedChange = { showChart = it; save() },
                position = CardPosition.TOP
            )
            SettingsSwitchItem(
                title = stringResource(R.string.show_trend_arrow),
                checked = showArrow,
                onCheckedChange = { showArrow = it; save() },
                position = CardPosition.MIDDLE
            )
            SettingsSwitchItem(
                title = stringResource(R.string.display_secondary_values),
                subtitle = stringResource(R.string.display_secondary_values_desc),
                checked = showSecondary,
                onCheckedChange = { showSecondary = it; save() },
                position = CardPosition.MIDDLE
            )
            SettingsControlsCard(position = CardPosition.BOTTOM) {
                SettingsSlider(
                    title = stringResource(R.string.opacity_percent, (opacity * 100).toInt()),
                    value = opacity,
                    onValueChange = { opacity = it; save() },
                    range = 0.1f..1.0f
                )
                SettingsSlider(
                    title = stringResource(R.string.text_size_percent, (textScale * 100).toInt()),
                    value = textScale,
                    onValueChange = { textScale = it; save() },
                    range = 0.5f..6.0f
                )
                if (showChart) {
                    SettingsSlider(
                        title = stringResource(R.string.chart_size_percent, (chartScale * 100).toInt()),
                        value = chartScale,
                        onValueChange = { chartScale = it; save() },
                        range = 0.5f..2.0f
                    )
                }
                if (showArrow) {
                    SettingsSlider(
                        title = stringResource(R.string.arrow_size_percent, (arrowScale * 100).toInt()),
                        value = arrowScale,
                        onValueChange = { arrowScale = it; save() },
                        range = 0.5f..2.0f
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreenScaffold(
    navController: NavController,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = title,
                onNavigateBack = { navController.popBackStack() },
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

internal fun isAodAccessibilityEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
        info.resolveInfo?.serviceInfo?.packageName == context.packageName &&
            info.resolveInfo?.serviceInfo?.name == tk.glucodata.accessibility.AODOverlayService::class.java.name
    }
}
