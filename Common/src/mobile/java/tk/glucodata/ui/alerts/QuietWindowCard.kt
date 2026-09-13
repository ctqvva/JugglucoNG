package tk.glucodata.ui.alerts

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import tk.glucodata.AlertDeliveryPolicy
import tk.glucodata.R
import tk.glucodata.alerts.QuietWindow
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * The start presets, in minutes: a film or a lecture (30 min to 3 h), a night
 * (12 h). Anything else is a clock time, which is what "Until..." is for.
 */
private val START_PRESET_MINUTES = listOf(30, 60, 120, 180, 720)

/**
 * The quiet window as one card at the top of the alert settings, shaped like the
 * alert cards under it. Collapsed it is a row that says whether a window runs;
 * it opens on a tap, and opens by itself while a window runs, so the way to end
 * it is never hidden. Inside: End now, the start presets, the mode, and under
 * Advanced the breakthrough cap, its scope and the quick-settings tile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietWindowCard(
    // Whether any enabled alert makes a sound. Without one there is nothing
    // for vibrate-only to keep, so the choice is hidden and a window cuts the
    // vibration - the only thing it can cut - and that becomes the stored mode.
    anySound: Boolean,
    position: CardPosition = CardPosition.SINGLE
) {
    val context = LocalContext.current
    val state by QuietWindow.state.collectAsState()
    val startMode = if (anySound) state.mode else AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    var openedByUser by rememberSaveable { mutableStateOf(false) }
    val expanded = openedByUser || state.active
    val chevron by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "quietWindowChevron")
    var showTimePicker by remember { mutableStateOf(false) }

    val accent = if (state.active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = if (state.active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            // Header: the same geometry as an alert card's row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable { openedByUser = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = accent.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.DoNotDisturbOn, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.quiet_window_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.active) {
                            stringResource(R.string.quiet_window_active_until, timeFormat.format(Date(state.untilMs)))
                        } else {
                            stringResource(R.string.off)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.active) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevron)
                )
            }

            AnimatedVisibility(visible = expanded) {
                // Blocks pad themselves, as in CommonAlertSettings, so the Advanced
                // row can span the card and still put its text on the same edge.
                val inset = Modifier.padding(horizontal = 16.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state.active) {
                        Button(
                            onClick = { QuietWindow.end(context) },
                            modifier = inset.fillMaxWidth().heightIn(min = 56.dp)
                        ) {
                            Text(stringResource(R.string.quiet_window_end_now))
                        }
                    }

                    // Starting: every tap starts a window right away.
                    Column(modifier = inset, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.quiet_window_start_for),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // Two buttons to a row, full labels: these are buttons, not chips.
                        val starts: List<Pair<String, () -> Unit>> = START_PRESET_MINUTES.map { minutes ->
                            quietDurationLabelLong(minutes) to {
                                QuietWindow.startFor(context, TimeUnit.MINUTES.toMillis(minutes.toLong()), startMode)
                            }
                        } + (stringResource(R.string.quiet_window_preset_until) to { showTimePicker = true })
                        starts.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (label, start) ->
                                    FilledTonalButton(
                                        onClick = start,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }

                    // The mode: what a silenced alarm keeps.
                    if (anySound) Column(modifier = inset, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            modifier = Modifier.fillMaxWidth(),
                            itemHeight = 36.dp
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

                    QuietWindowAdvanced()
                }
            }
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
                        mode = startMode,
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

/** Breakthrough cap, its scope, and the quick-settings tile - set once, under Advanced. */
@Composable
private fun QuietWindowAdvanced() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var breakthroughMinutes by remember { mutableStateOf(QuietWindow.breakthroughMinutes()) }
    var breakthroughScope by remember { mutableStateOf(QuietWindow.breakthroughScope()) }
    var defaultMinutes by remember { mutableStateOf(QuietWindow.defaultMinutes()) }

    AdvancedSectionHeader(expanded = expanded, onToggle = { expanded = !expanded })
    AnimatedVisibility(visible = expanded) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.quiet_window_breakthrough_scope_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
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
                    modifier = Modifier.fillMaxWidth(),
                    itemHeight = 36.dp
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.quiet_window_tile_default_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ConnectedButtonGroup(
                    options = QuietWindow.PRESET_MINUTES,
                    selectedOption = defaultMinutes,
                    onOptionSelected = {
                        defaultMinutes = it
                        QuietWindow.setDefaultMinutes(it)
                    },
                    labelText = { quietDurationLabelPlain(context, it) },
                    label = { Text(quietDurationLabel(it), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth(),
                    itemHeight = 36.dp
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
        }
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

/** "30 min", "1 hour", "12 hours": the long form, for a button. */
@Composable
private fun quietDurationLabelLong(minutes: Int): String =
    if (minutes % 60 == 0) {
        androidx.compose.ui.res.pluralStringResource(R.plurals.duration_hours, minutes / 60, minutes / 60)
    } else {
        stringResource(R.string.minutes_short_format, minutes)
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
