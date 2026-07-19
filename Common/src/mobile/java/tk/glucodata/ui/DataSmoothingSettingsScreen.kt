package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlin.math.roundToInt
import tk.glucodata.DataSmoothing
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSmoothingSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val graphLevel by viewModel.graphSmoothingLevel.collectAsState()
    val exchangeMinutes by viewModel.exchangeSmoothingMinutes.collectAsState()
    val collapseChunks by viewModel.dataSmoothingCollapseChunks.collectAsState()
    val exchangeOptions = remember { DataSmoothing.exchangeMinutesOptions().toList() }
    var graphSlider by rememberSaveable(graphLevel) { mutableFloatStateOf(graphLevel.toFloat()) }
    var exchangeSlider by rememberSaveable(exchangeMinutes) {
        mutableFloatStateOf(exchangeOptions.indexOf(exchangeMinutes).coerceAtLeast(0).toFloat())
    }
    val selectedExchangeMinutes = exchangeOptions[exchangeSlider.roundToInt().coerceIn(0, exchangeOptions.lastIndex)]

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.graph_smoothing_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SmoothingSliderCard(
                title = stringResource(R.string.data_smoothing_graph_only_title),
                description = stringResource(R.string.data_smoothing_graph_only_desc),
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                iconTint = MaterialTheme.colorScheme.primary,
                valueLabel = if (graphSlider.roundToInt() == 0) {
                    stringResource(R.string.graph_smoothing_none)
                } else {
                    "×${graphSlider.roundToInt()}"
                },
                value = graphSlider,
                onValueChange = { graphSlider = it },
                onValueChangeFinished = {
                    viewModel.setGraphSmoothingLevel(graphSlider.roundToInt())
                },
                valueRange = 0f..3f,
                steps = 2,
                startLabel = stringResource(R.string.graph_smoothing_none),
                endLabel = "×3"
            )

            SmoothingSliderCard(
                title = stringResource(R.string.data_smoothing_exchange_only_title),
                description = stringResource(R.string.data_smoothing_exchange_only_desc),
                icon = Icons.AutoMirrored.Filled.Send,
                iconTint = MaterialTheme.colorScheme.tertiary,
                valueLabel = if (selectedExchangeMinutes == 0) {
                    stringResource(R.string.graph_smoothing_none)
                } else {
                    stringResource(R.string.minutes_short_format, selectedExchangeMinutes)
                },
                value = exchangeSlider,
                onValueChange = { exchangeSlider = it },
                onValueChangeFinished = {
                    viewModel.setExchangeSmoothingMinutes(
                        exchangeOptions[exchangeSlider.roundToInt().coerceIn(0, exchangeOptions.lastIndex)]
                    )
                },
                valueRange = 0f..exchangeOptions.lastIndex.toFloat(),
                steps = (exchangeOptions.size - 2).coerceAtLeast(0),
                startLabel = stringResource(R.string.graph_smoothing_none),
                endLabel = stringResource(R.string.minutes_short_format, exchangeOptions.last())
            )

            SettingsSwitchItem(
                title = stringResource(R.string.data_smoothing_collapse_title),
                subtitle = stringResource(R.string.data_smoothing_collapse_desc),
                checked = collapseChunks,
                onCheckedChange = viewModel::setDataSmoothingCollapseChunks,
                icon = Icons.Default.FilterAlt,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.SINGLE,
                enabled = exchangeMinutes > 0
            )
        }
    }
}

@Composable
private fun SmoothingSliderCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconTint: Color,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    startLabel: String,
    endLabel: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = iconTint.copy(alpha = 0.14f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        valueLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(startLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(endLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
