package tk.glucodata.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlin.math.roundToInt
import tk.glucodata.DataSmoothing
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSmoothingSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val context = LocalContext.current
    val smoothingMinutes by viewModel.chartSmoothingMinutes.collectAsStateWithLifecycle()
    val graphOnly by viewModel.dataSmoothingGraphOnly.collectAsStateWithLifecycle()
    val collapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsStateWithLifecycle()
    val exchangeOnly by viewModel.dataSmoothingExchangeOnly.collectAsStateWithLifecycle()

    val isEnabled = smoothingMinutes > 0
    val options = remember { DataSmoothing.enabledMinutesOptions().toList() }
    val configuredMinutes = if (isEnabled) smoothingMinutes else DataSmoothing.getLastEnabledMinutes(context)
    val selectedIndex = options.indexOf(configuredMinutes).coerceAtLeast(0)
    var sliderIndex by rememberSaveable(smoothingMinutes) { mutableFloatStateOf(selectedIndex.toFloat()) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.7f,
        label = "dataSmoothingContentAlpha"
    )

    val selectedMinutes = options[sliderIndex.roundToInt().coerceIn(0, options.lastIndex)]
    val selectedLabel = stringResource(R.string.minutes_short_format, selectedMinutes)
    val collapseIntervalMinutes = DataSmoothing.collapseIntervalMinutes(configuredMinutes)
    val enabledSummary = buildList {
        add(stringResource(R.string.minutes_short_format, smoothingMinutes.coerceAtLeast(options.first())))
        if (exchangeOnly) {
            add(stringResource(R.string.data_smoothing_exchange_only_title))
        } else if (graphOnly) {
            add(stringResource(R.string.data_smoothing_graph_only_title))
        } else {
            add(stringResource(R.string.data_smoothing_scope_all))
        }
        if (collapseChunks) {
            add(stringResource(R.string.data_smoothing_collapse_summary_format, collapseIntervalMinutes))
        }
    }.joinToString(" · ")
    val collapseSubtitle = when {
        !collapseChunks -> stringResource(R.string.data_smoothing_collapse_desc)
        collapseIntervalMinutes in 1 until configuredMinutes ->
            stringResource(R.string.data_smoothing_collapse_desc_capped, configuredMinutes)
        else ->
            stringResource(R.string.data_smoothing_collapse_desc_match, collapseIntervalMinutes)
    }
    val exchangeOnlySubtitle = stringResource(R.string.data_smoothing_exchange_only_desc)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.graph_smoothing_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MasterSwitchCard(
                title = stringResource(R.string.graph_smoothing_title),
                subtitle = if (isEnabled) enabledSummary else stringResource(R.string.graph_smoothing_none),
                checked = isEnabled,
                onCheckedChange = { viewModel.setDataSmoothingEnabled(it) },
                icon = Icons.AutoMirrored.Filled.TrendingUp
            )

            Card(
                modifier = Modifier.alpha(contentAlpha),
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
                                style = MaterialTheme.typography.titleMediumEmphasized,)

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
                                viewModel.setChartSmoothingMinutes(nextMinutes)
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

            Column(
                modifier = Modifier.alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.data_smoothing_graph_only_title),
                    subtitle = stringResource(
                        if (collapseChunks) R.string.data_smoothing_graph_only_collapse_desc
                        else R.string.data_smoothing_graph_only_desc
                    ),
                    checked = graphOnly,
                    onCheckedChange = { viewModel.setDataSmoothingGraphOnly(it) },
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.TOP,
                    enabled = isEnabled
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.data_smoothing_exchange_only_title),
                    subtitle = exchangeOnlySubtitle,
                    checked = exchangeOnly,
                    onCheckedChange = { viewModel.setDataSmoothingExchangeOnly(it) },
                    icon = Icons.AutoMirrored.Filled.Send,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.MIDDLE,
                    enabled = isEnabled
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.data_smoothing_collapse_title),
                    subtitle = collapseSubtitle,
                    checked = collapseChunks,
                    onCheckedChange = { viewModel.setDataSmoothingCollapseChunks(it) },
                    icon = Icons.Default.FilterAlt,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.BOTTOM,
                    enabled = isEnabled
                )
            }
        }
    }
}
