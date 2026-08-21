package tk.glucodata.ui.alerts

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * The quiet window's one dialog, reached from the dashboard header and from the
 * alert settings: status, presets, an end-time picker, "until I end it" (24 h),
 * and the mode. Starting closes it. Everything it does goes through [QuietWindow];
 * the safety rules (very low ignores the window, breakthrough, the 24 h cap) are
 * not the dialog's to bend.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QuietWindowDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by QuietWindow.state.collectAsState()
    val now = remember { Calendar.getInstance() }
    var showTimePicker by remember { mutableStateOf(false) }
    val timePickerState = rememberTimePickerState(
        initialHour = now.get(Calendar.HOUR_OF_DAY),
        initialMinute = now.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(context)
    )
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quiet_window_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Status
                if (state.active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                R.string.quiet_window_active_until,
                                timeFormat.format(Date(state.untilMs))
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            QuietWindow.end(context)
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.quiet_window_end_now))
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.quiet_window_inactive),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Mode
                Text(
                    stringResource(R.string.quiet_window_mode_title),
                    style = MaterialTheme.typography.labelLarge
                )
                val modeLabels = mapOf(
                    AlertDeliveryPolicy.QUIET_VIBRATE_ONLY to stringResource(R.string.quiet_window_mode_vibrate_only),
                    AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY to stringResource(R.string.quiet_window_mode_notification_only)
                )
                tk.glucodata.ui.util.ConnectedButtonGroup(
                    options = listOf(
                        AlertDeliveryPolicy.QUIET_VIBRATE_ONLY,
                        AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY
                    ),
                    selectedOption = state.mode,
                    onOptionSelected = { QuietWindow.setMode(context, it) },
                    labelText = { modeLabels[it] ?: it },
                    label = { Text(modeLabels[it] ?: it, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth(),
                    itemHeight = 36.dp
                )
                Text(
                    text = stringResource(
                        if (state.mode == AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY)
                            R.string.quiet_window_mode_notification_only_desc
                        else
                            R.string.quiet_window_mode_vibrate_only_desc
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Presets: they start the window right away.
                Text(
                    stringResource(R.string.quiet_window_start_for),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietWindow.PRESET_MINUTES.forEach { minutes ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                QuietWindow.startFor(context, TimeUnit.MINUTES.toMillis(minutes.toLong()))
                                onDismiss()
                            },
                            label = { Text(quietDurationLabel(minutes)) }
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { showTimePicker = true },
                        label = { Text(stringResource(R.string.quiet_window_preset_until)) }
                    )
                }
                Surface(
                    onClick = {
                        QuietWindow.startFor(context, QuietWindow.MAX_DURATION_MS)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.quiet_window_until_i_end),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.quiet_window_until_i_end_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    stringResource(R.string.quiet_window_very_low_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )

    if (showTimePicker) {
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
                    onDismiss()
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

@Composable
internal fun quietDurationLabel(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.hours_short, minutes / 60)
    else stringResource(R.string.minutes_short_format, minutes)

/**
 * The alert settings' entry: status and a way into the dialog, plus the two
 * settings the window has (breakthrough time, what the tile starts) and the
 * tile itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuietWindowSettingsCard() {
    val context = LocalContext.current
    val state by QuietWindow.state.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var breakthroughMinutes by remember { mutableStateOf(QuietWindow.breakthroughMinutes()) }
    var breakthroughScope by remember { mutableStateOf(QuietWindow.breakthroughScope()) }
    var defaultMinutes by remember { mutableStateOf(QuietWindow.defaultMinutes()) }
    val scopeLabels = mapOf(
        AlertDeliveryPolicy.BREAKTHROUGH_ALL to stringResource(R.string.quiet_window_breakthrough_scope_all),
        AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY to stringResource(R.string.quiet_window_breakthrough_scope_very)
    )
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column {
            Row(
                modifier = Modifier
                    .clickable { showDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.NotificationsPaused,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.quiet_window_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        if (state.active) {
                            stringResource(R.string.quiet_window_active_until, timeFormat.format(Date(state.untilMs)))
                        } else {
                            stringResource(R.string.quiet_window_desc)
                        },
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
    }

    // The window's own settings sit on the plain settings surface, not on the tinted
    // opener row: a selected FilterChip is secondaryContainer itself and vanishes there.
    Spacer(Modifier.height(8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
            Text(
                stringResource(R.string.quiet_window_breakthrough_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.quiet_window_breakthrough_scope_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            tk.glucodata.ui.util.ConnectedButtonGroup(
                options = listOf(
                    AlertDeliveryPolicy.BREAKTHROUGH_ALL,
                    AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY
                ),
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
            Text(
                stringResource(
                    if (breakthroughScope == AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY)
                        R.string.quiet_window_breakthrough_scope_very_desc
                    else
                        R.string.quiet_window_breakthrough_scope_all_desc
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.quiet_window_tile_default_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietWindow.PRESET_MINUTES.forEach { minutes ->
                    val selected = defaultMinutes == minutes
                    FilterChip(
                        selected = selected,
                        onClick = {
                            defaultMinutes = minutes
                            QuietWindow.setDefaultMinutes(minutes)
                        },
                        label = { Text(quietDurationLabel(minutes)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
            Text(
                stringResource(R.string.quiet_window_tile_default_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(onClick = { requestAddQuietWindowTile(context) }) {
                    Text(stringResource(R.string.quiet_window_add_tile))
                }
            } else {
                Text(
                    stringResource(R.string.quiet_window_add_tile_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDialog) {
        QuietWindowDialog(onDismiss = { showDialog = false })
    }
}

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
