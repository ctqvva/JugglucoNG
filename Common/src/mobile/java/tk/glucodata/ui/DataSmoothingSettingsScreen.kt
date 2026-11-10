package tk.glucodata.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.WaterDrop
import tk.glucodata.ui.util.ConnectedButtonGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlin.math.roundToInt
import tk.glucodata.DataSmoothing
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.viewmodel.DashboardViewModel

@Composable
fun DataSmoothingSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val context = LocalContext.current
    val smoothingMinutes by viewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val targets by viewModel.dataSmoothingTargets.collectAsStateWithLifecycle()
    val causal by viewModel.dataSmoothingCausal.collectAsStateWithLifecycle()
    DataSmoothingSettingsContent(
        smoothingMinutes = smoothingMinutes,
        lastEnabledMinutes = remember(smoothingMinutes) { DataSmoothing.getLastEnabledMinutes(context) },
        targets = targets,
        causal = causal,
        onEnabledChange = viewModel::setDataSmoothingEnabled,
        onMinutesChange = viewModel::setChartSmoothingMinutes,
        onTargetsChange = viewModel::setDataSmoothingTargets,
        onCausalChange = viewModel::setDataSmoothingCausal,
        onNavigateBack = { navController.popBackStack() }
    )
}

/** The screen without its view model, so a test can draw it. */
@Composable
internal fun DataSmoothingSettingsContent(
    smoothingMinutes: Int,
    lastEnabledMinutes: Int,
    targets: DataSmoothing.Targets,
    causal: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onTargetsChange: (DataSmoothing.Targets) -> Unit,
    onCausalChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val isEnabled = smoothingMinutes > 0
    val options = remember { DataSmoothing.enabledMinutesOptions().toList() }
    val configuredMinutes = if (isEnabled) smoothingMinutes else lastEnabledMinutes
    val selectedIndex = options.indexOf(configuredMinutes).coerceAtLeast(0)
    var sliderIndex by rememberSaveable(smoothingMinutes) { mutableFloatStateOf(selectedIndex.toFloat()) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.7f,
        label = "dataSmoothingContentAlpha"
    )

    val selectedMinutes = options[sliderIndex.roundToInt().coerceIn(0, options.lastIndex)]
    val selectedLabel = stringResource(R.string.minutes_short_format, selectedMinutes)
    val collapseIntervalMinutes = DataSmoothing.collapseIntervalMinutes(configuredMinutes)
    // "Smoothed over" only where it is true: a thinned place that is also smoothed, or, before
    // any place is picked, a place that would be picked first.
    val thinsSmoothed = DataSmoothingPlace.entries
        .filter { it.fewer(targets) }
        .ifEmpty { DataSmoothingPlace.entries }
        .any { it.smoothed(targets) }
    val collapseSubtitle = when {
        thinsSmoothed && collapseIntervalMinutes in 1 until configuredMinutes ->
            stringResource(R.string.data_smoothing_collapse_desc_capped, configuredMinutes)
        else ->
            stringResource(R.string.data_smoothing_collapse_desc_match, collapseIntervalMinutes)
    }

    val smoothedAnywhere = targets.chart || targets.current || targets.sent
    val fewerScope = dataSmoothingFewerScopeLabel(targets)
    val placeLabels = DataSmoothingPlace.entries.associateWith { stringResource(it.shortLabel) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.graph_smoothing_title),
                onNavigateBack = onNavigateBack,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)
        ) {
            MasterSwitchCard(
                title = stringResource(R.string.graph_smoothing_title),
                subtitle = when {
                    isEnabled && smoothedAnywhere -> stringResource(R.string.data_smoothing_on_desc, smoothingMinutes)
                    // Only thinned: say where, rather than "shown as measured" under an on switch.
                    isEnabled && fewerScope != null -> stringResource(
                        R.string.data_smoothing_fewer_summary_format,
                        stringResource(fewerScope)
                    )
                    else -> stringResource(R.string.settings_smoothing_off_desc)
                },
                checked = isEnabled,
                onCheckedChange = { onEnabledChange(it) },
                icon = Icons.AutoMirrored.Filled.TrendingUp
            )

            Column(modifier = Modifier.alpha(contentAlpha)) {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isEnabled) {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        }
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.data_smoothing_window_title),
                                    style = MaterialTheme.typography.titleMediumEmphasized
                                )

                                Text(
                                    text = stringResource(R.string.data_smoothing_window_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isEnabled) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            ) {
                                Text(
                                    text = selectedLabel,
                                    style = MaterialTheme.typography.labelLargeEmphasized,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }

                        Slider(
                            value = sliderIndex,
                            onValueChange = { sliderIndex = it },
                            onValueChangeFinished = {
                                val nextMinutes = options[sliderIndex.roundToInt().coerceIn(0, options.lastIndex)]
                                if (nextMinutes != smoothingMinutes) {
                                    onMinutesChange(nextMinutes)
                                }
                            },
                            valueRange = 0f..options.lastIndex.toFloat(),
                            steps = (options.size - 2).coerceAtLeast(0),
                            enabled = isEnabled
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.minutes_short_format, options.first()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.minutes_short_format, options.last()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Apply to: one switch per place, as M3 lists have one control per row.
                SectionLabel(stringResource(R.string.data_smoothing_scope_title))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DataSmoothingPlace.entries.forEach { place ->
                        SettingsSwitchItem(
                            title = stringResource(place.title),
                            subtitle = stringResource(place.description),
                            checked = place.smoothed(targets),
                            onCheckedChange = { onTargetsChange(place.toggleSmoothed(targets)) },
                            icon = place.icon,
                            iconTint = MaterialTheme.colorScheme.primary,
                            position = place.position,
                            enabled = isEnabled
                        )
                    }
                }

                // Fewer readings works with or without smoothing at a place.
                Spacer(Modifier.height(24.dp))
                FewerReadingsCard(
                    description = collapseSubtitle,
                    targets = targets,
                    placeLabels = placeLabels,
                    onTargetsChange = onTargetsChange,
                    enabled = isEnabled
                )

                Spacer(Modifier.height(16.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.data_smoothing_causal_title),
                    subtitle = stringResource(R.string.data_smoothing_causal_desc),
                    checked = causal,
                    onCheckedChange = { onCausalChange(it) },
                    icon = Icons.Default.Functions,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.SINGLE,
                    enabled = isEnabled
                )
            }
        }
    }
}

/**
 * Fewer readings as one container, styled like the window card above it: the heading row is the
 * group switch, and while it is on the places it applies to sit under it as a multi-select group.
 * Switching on starts where readings are smoothed; clearing the last place switches it off.
 */
@Composable
private fun FewerReadingsCard(
    description: String,
    targets: DataSmoothing.Targets,
    placeLabels: Map<DataSmoothingPlace, String>,
    onTargetsChange: (DataSmoothing.Targets) -> Unit,
    enabled: Boolean,
) {
    val fewerPlaces = DataSmoothingPlace.entries.filter { it.fewer(targets) }
    val checked = fewerPlaces.isNotEmpty()
    val onCheckedChange: (Boolean) -> Unit = { on ->
        val places = if (on) {
            DataSmoothingPlace.entries.filter { it.smoothed(targets) }.ifEmpty { DataSmoothingPlace.entries }
        } else {
            DataSmoothingPlace.entries
        }
        onTargetsChange(places.fold(targets) { t, place -> place.withFewer(t, on) })
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            }
        ),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        // A control card, as the window card above: one column on one surface. The heading
        // spans the card so its ripple fills the card's shape; padding sits inside the ripple.
        // Collapsed it is the whole card; expanded its lower edge meets the controls below.
        val headingShape = if (checked) {
            MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
        } else {
            MaterialTheme.shapes.extraLarge
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            // The whole heading is the switch's touch target, read as one switch by TalkBack.
            // It shares its press with the switch, so a tap anywhere on it ripples and moves the switch.
            val headingInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(headingShape)
                    .toggleable(
                        value = checked,
                        interactionSource = headingInteraction,
                        indication = ripple(bounded = true),
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange
                    )
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.data_smoothing_collapse_title),
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StyledSwitch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    interactionSource = headingInteraction
                )
            }

            // 16dp on top of the heading's own 16dp, so the buttons sit clear of its press
            // highlight; it lives inside the revealed section, so the card closes to the heading.
            AnimatedVisibility(
                visible = checked,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConnectedButtonGroup(
                        options = DataSmoothingPlace.entries,
                        selectedOptions = fewerPlaces,
                        multiSelect = true,
                        onOptionSelected = { place -> onTargetsChange(place.toggleFewer(targets)) },
                        label = {},
                        labelText = { placeLabels.getValue(it) },
                        modifier = Modifier.fillMaxWidth(),
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        enabled = enabled
                    )
                    Text(
                        text = stringResource(R.string.data_smoothing_preserve_originals),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** The three places readings go. */
private enum class DataSmoothingPlace(
    val title: Int,
    val description: Int,
    /** For the Fewer readings buttons, where three full titles don't fit. */
    val shortLabel: Int,
    val icon: ImageVector,
) {
    CHART(R.string.data_smoothing_target_chart_title, R.string.data_smoothing_target_chart_desc,
        R.string.data_smoothing_target_chart_title, Icons.AutoMirrored.Filled.ShowChart),
    CURRENT(R.string.data_smoothing_target_current_title, R.string.data_smoothing_target_current_desc,
        R.string.data_smoothing_place_current_short, Icons.Default.WaterDrop),
    SENT(R.string.data_smoothing_target_sent_title, R.string.data_smoothing_target_sent_desc,
        R.string.data_smoothing_place_sent_short, Icons.AutoMirrored.Filled.Send);

    val position: CardPosition
        get() = when (this) {
            CHART -> CardPosition.TOP
            CURRENT -> CardPosition.MIDDLE
            SENT -> CardPosition.BOTTOM
        }

    fun smoothed(t: DataSmoothing.Targets) = when (this) {
        CHART -> t.chart
        CURRENT -> t.current
        SENT -> t.sent
    }

    fun fewer(t: DataSmoothing.Targets) = when (this) {
        CHART -> t.fewerChart
        CURRENT -> t.fewerCurrent
        SENT -> t.fewerSent
    }

    fun toggleSmoothed(t: DataSmoothing.Targets) = when (this) {
        CHART -> t.copy(chart = !t.chart)
        CURRENT -> t.copy(current = !t.current)
        SENT -> t.copy(sent = !t.sent)
    }

    fun toggleFewer(t: DataSmoothing.Targets) = withFewer(t, !fewer(t))

    fun withFewer(t: DataSmoothing.Targets, on: Boolean) = when (this) {
        CHART -> t.copy(fewerChart = on)
        CURRENT -> t.copy(fewerCurrent = on)
        SENT -> t.copy(fewerSent = on)
    }
}

/** Where smoothing applies, as the Settings row summarises it; null when it applies nowhere. */
internal fun dataSmoothingScopeLabel(targets: DataSmoothing.Targets): Int? =
    scopeLabel(targets.chart, targets.current, targets.sent)

/** Where readings are thinned to fewer, in the same words; null when nowhere. */
internal fun dataSmoothingFewerScopeLabel(targets: DataSmoothing.Targets): Int? =
    scopeLabel(targets.fewerChart, targets.fewerCurrent, targets.fewerSent)

private fun scopeLabel(chart: Boolean, current: Boolean, sent: Boolean): Int? = when {
    chart && current && sent -> R.string.data_smoothing_scope_all
    chart && current -> R.string.data_smoothing_scope_chart_and_current
    chart && sent -> R.string.data_smoothing_scope_graph_and_sent
    current && sent -> R.string.data_smoothing_scope_current_and_sent
    chart -> R.string.data_smoothing_graph_only_title
    current -> R.string.data_smoothing_scope_current_only
    sent -> R.string.data_smoothing_exchange_only_title
    else -> null
}
