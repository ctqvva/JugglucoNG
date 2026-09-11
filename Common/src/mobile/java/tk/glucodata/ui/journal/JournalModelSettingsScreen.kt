package tk.glucodata.ui.journal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.data.prediction.StateDoseHintCalculator
import tk.glucodata.ui.PredictiveSimulationParameterRow
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.viewmodel.DashboardViewModel
import kotlin.math.roundToInt

/**
 * The metabolic model and the features that read it. The profile (carb ratio, sensitivity,
 * absorption, dose target) is the source of parameters; the dose calculator, dose hints,
 * IOB/eIOB and food absorption all consume it. Journal setup keeps only the journal-facing
 * switches and links here for everything model-shaped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalModelSettingsScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val journalEnabled by viewModel.journalEnabled.collectAsState()
    val predictionModelProfile by viewModel.predictionModelProfile.collectAsState()
    val journalFoodMacrosEnabled by viewModel.journalFoodMacrosEnabled.collectAsState()
    val journalEiobDisplayEnabled by viewModel.journalEiobDisplayEnabled.collectAsState()
    val stateDoseHintEnabled by viewModel.stateDoseHintEnabled.collectAsState()
    val stateDoseHintCorrectInRange by viewModel.stateDoseHintCorrectInRange.collectAsState()
    val stateDoseHintHorizonMinutes by viewModel.stateDoseHintHorizonMinutes.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_model_title)) },
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
            item(key = "model") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        onClick = if (journalEnabled) {
                            { navController.navigate("settings/predictive-simulation/model-profile") }
                        } else {
                            null
                        },
                        icon = Icons.Default.Schedule,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.TOP
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.journal_food_macros_title),
                        subtitle = stringResource(R.string.journal_food_macros_desc),
                        checked = journalFoodMacrosEnabled,
                        onCheckedChange = { viewModel.setJournalFoodMacrosEnabled(it) },
                        icon = Icons.Default.Restaurant,
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
                        position = CardPosition.BOTTOM,
                        enabled = journalEnabled
                    )
                }
            }

            item(key = "suggestions") {
                Column {
                    SectionLabel(text = stringResource(R.string.journal_model_suggestions_section), topPadding = 8.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsSwitchItem(
                            title = stringResource(R.string.state_dose_hint_title),
                            subtitle = stringResource(R.string.state_dose_hint_desc),
                            checked = stateDoseHintEnabled,
                            onCheckedChange = { viewModel.setStateDoseHintEnabled(it) },
                            icon = Icons.Default.TipsAndUpdates,
                            iconTint = MaterialTheme.colorScheme.primary,
                            position = CardPosition.TOP,
                            enabled = journalEnabled
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.state_dose_hint_in_range_title),
                            subtitle = stringResource(R.string.state_dose_hint_in_range_desc),
                            checked = stateDoseHintCorrectInRange,
                            onCheckedChange = { viewModel.setStateDoseHintCorrectInRange(it) },
                            icon = Icons.Default.CenterFocusStrong,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            position = if (stateDoseHintEnabled) CardPosition.MIDDLE else CardPosition.BOTTOM,
                            enabled = journalEnabled && stateDoseHintEnabled
                        )
                        // The horizon only steers the falling case, so it hides with the hint.
                        AnimatedVisibility(
                            visible = stateDoseHintEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            DoseHintHorizonItem(
                                horizonMinutes = stateDoseHintHorizonMinutes,
                                enabled = journalEnabled,
                                onHorizonChange = { viewModel.setStateDoseHintHorizonMinutes(it.roundToInt()) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * How far ahead the carb hint looks while the value is falling. Sits as the last row of the
 * suggestions group, same shape and colour as the switches above it.
 */
@Composable
private fun DoseHintHorizonItem(
    horizonMinutes: Int,
    enabled: Boolean,
    onHorizonChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.BOTTOM),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.state_dose_hint_horizon),
                valueLabel = stringResource(R.string.predictive_horizon_value, horizonMinutes),
                value = horizonMinutes.toFloat(),
                valueRange = StateDoseHintCalculator.HORIZON_MINUTES_MIN.toFloat()..
                    StateDoseHintCalculator.HORIZON_MINUTES_MAX.toFloat(),
                enabled = enabled,
                onValueChange = onHorizonChange
            )
            Text(
                text = stringResource(R.string.state_dose_hint_horizon_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}
