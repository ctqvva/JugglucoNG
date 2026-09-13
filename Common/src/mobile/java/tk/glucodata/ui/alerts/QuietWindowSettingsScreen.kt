package tk.glucodata.ui.alerts

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import tk.glucodata.AlertDeliveryPolicy
import tk.glucodata.R
import tk.glucodata.alerts.QuietWindow
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * The quiet window's one screen, reached from the last row of the alert settings,
 * from the dashboard chip while a window runs, and from the tile's long press.
 * Top to bottom: what is running and the way to end it, the ways to start one,
 * then the preferences. Everything goes through [QuietWindow]; the safety rules
 * (a hypo always sounds again, the 24 h cap) are not the screen's to bend.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuietWindowSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val state by QuietWindow.state.collectAsState()
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    var showTimePicker by remember { mutableStateOf(false) }
    var breakthroughMinutes by remember { mutableStateOf(QuietWindow.breakthroughMinutes()) }
    var breakthroughScope by remember { mutableStateOf(QuietWindow.breakthroughScope()) }
    var defaultMinutes by remember { mutableStateOf(QuietWindow.defaultMinutes()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quiet_window_title)) },
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
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // A running window is status with one thing to do about it: a card.
            // With none running there is nothing to show, so no container.
            if (state.active) {
                RunningWindowCard(
                    untilText = timeFormat.format(Date(state.untilMs)),
                    mode = state.mode,
                    onEnd = { QuietWindow.end(context) }
                )
            }

            // Starting: every tap starts a window right away, so these are buttons.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.quiet_window_start_for),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuietWindow.PRESET_MINUTES.forEach { minutes ->
                        OutlinedButton(onClick = {
                            QuietWindow.startFor(context, TimeUnit.MINUTES.toMillis(minutes.toLong()))
                        }) {
                            Text(quietDurationLabel(minutes))
                        }
                    }
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Text(stringResource(R.string.quiet_window_preset_until))
                    }
                    OutlinedButton(onClick = { QuietWindow.startFor(context, QuietWindow.MAX_DURATION_MS) }) {
                        Text(stringResource(R.string.quiet_window_until_i_end))
                    }
                }
                Text(
                    text = stringResource(R.string.quiet_window_until_i_end_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Preferences. Each is a label, the control, and a caption saying what
            // the chosen value means - one left edge throughout.
            PreferenceBlock(title = stringResource(R.string.quiet_window_mode_title)) {
                val modeLabels = mapOf(
                    AlertDeliveryPolicy.QUIET_VIBRATE_ONLY to stringResource(R.string.quiet_window_mode_vibrate_only),
                    AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY to stringResource(R.string.quiet_window_mode_notification_only)
                )
                ConnectedButtonGroup(
                    options = listOf(AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY),
                    selectedOption = state.mode,
                    onOptionSelected = { QuietWindow.setMode(context, it) },
                    labelText = { modeLabels[it] ?: it },
                    label = { Text(modeLabels[it] ?: it, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth()
                )
                Caption(
                    stringResource(
                        if (state.mode == AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY)
                            R.string.quiet_window_mode_notification_only_desc
                        else
                            R.string.quiet_window_mode_vibrate_only_desc
                    )
                )
            }

            PreferenceBlock(title = null) {
                DurationSlider(
                    label = stringResource(R.string.quiet_window_breakthrough_title),
                    value = breakthroughMinutes,
                    range = QuietWindow.MIN_BREAKTHROUGH_MINUTES..QuietWindow.MAX_BREAKTHROUGH_MINUTES,
                    stepSize = 5,
                    onValueChange = {
                        breakthroughMinutes = it
                        QuietWindow.setBreakthroughMinutes(it)
                    }
                )
                Caption(stringResource(R.string.quiet_window_breakthrough_desc))
            }

            PreferenceBlock(title = stringResource(R.string.quiet_window_breakthrough_scope_title)) {
                val scopeLabels = mapOf(
                    AlertDeliveryPolicy.BREAKTHROUGH_ALL to stringResource(R.string.quiet_window_breakthrough_scope_all),
                    AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY to stringResource(R.string.quiet_window_breakthrough_scope_very)
                )
                ConnectedButtonGroup(
                    options = listOf(AlertDeliveryPolicy.BREAKTHROUGH_ALL, AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY),
                    selectedOption = breakthroughScope,
                    onOptionSelected = {
                        breakthroughScope = it
                        QuietWindow.setBreakthroughScope(it)
                    },
                    labelText = { scopeLabels[it] ?: it },
                    label = { Text(scopeLabels[it] ?: it, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth()
                )
                Caption(
                    stringResource(
                        if (breakthroughScope == AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY)
                            R.string.quiet_window_breakthrough_scope_very_desc
                        else
                            R.string.quiet_window_breakthrough_scope_all_desc
                    )
                )
            }

            PreferenceBlock(title = stringResource(R.string.quiet_window_tile_default_title)) {
                ConnectedButtonGroup(
                    options = QuietWindow.PRESET_MINUTES,
                    selectedOption = defaultMinutes,
                    onOptionSelected = {
                        defaultMinutes = it
                        QuietWindow.setDefaultMinutes(it)
                    },
                    labelText = { quietDurationLabelPlain(context, it) },
                    label = { Text(quietDurationLabel(it), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth()
                )
                Caption(stringResource(R.string.quiet_window_tile_default_desc))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { requestAddQuietWindowTile(context) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.quiet_window_add_tile))
                    }
                } else {
                    Caption(stringResource(R.string.quiet_window_add_tile_hint))
                }
            }

            Caption(stringResource(R.string.quiet_window_very_low_note))
        }
    }

    if (showTimePicker) {
        val now = remember { Calendar.getInstance() }
        val timePickerState = rememberTimePickerState(
            initialHour = now.get(Calendar.HOUR_OF_DAY),
            initialMinute = now.get(Calendar.MINUTE),
            is24Hour = DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.quiet_window_select_end_time)) },
            text = { TimePicker(state = timePickerState, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    val nowMs = System.currentTimeMillis()
                    QuietWindow.startUntil(
                        context,
                        QuietWindow.untilForTimeOfDay(timePickerState.hour, timePickerState.minute, nowMs),
                        nowMs = nowMs
                    )
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

/** Status card: title first, what the window does under it, the action bottom-right. */
@Composable
private fun RunningWindowCard(untilText: String, mode: String, onEnd: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DoNotDisturbOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.quiet_window_active_until, untilText),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            if (mode == AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY)
                                R.string.quiet_window_mode_notification_only_desc
                            else
                                R.string.quiet_window_mode_vibrate_only_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onEnd) {
                    Text(stringResource(R.string.quiet_window_end_now))
                }
            }
        }
    }
}

@Composable
private fun PreferenceBlock(title: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        content()
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun quietDurationLabel(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.hours_short, minutes / 60)
    else stringResource(R.string.minutes_short_format, minutes)

private fun quietDurationLabelPlain(context: android.content.Context, minutes: Int): String =
    if (minutes % 60 == 0) context.getString(R.string.hours_short, minutes / 60)
    else context.getString(R.string.minutes_short_format, minutes)

private fun requestAddQuietWindowTile(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    try {
        val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return
        statusBar.requestAddTileService(
            ComponentName(context, tk.glucodata.ui.QuietWindowTileService::class.java),
            context.getString(R.string.quiet_window_tile_label),
            Icon.createWithResource(context, R.drawable.ic_quiet_window_inactive),
            context.mainExecutor
        ) { }
    } catch (t: Throwable) {
        tk.glucodata.Log.stack("QuietWindow", "requestAddTileService", t)
    }
}
