package tk.glucodata.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.WearJournalSync

/**
 * The journal as the watch sees it: entries the phone served, and adding insulin
 * or carbs back. The entries themselves live in Room on the phone — the watch
 * holds a cache of the last serve and relays additions.
 */
@Composable
fun JournalScreen(
    onAddInsulin: () -> Unit,
    onAddCarbs: () -> Unit,
) {
    // Collected, not polled: the old loop checked the cache ten times at 600 ms
    // and gave up, so a serve arriving a moment later never showed at all.
    val journal by WearJournalSync.journal.collectAsState()
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) { WearJournalSync.requestSync() }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            contentPadding = PaddingValues(top = 30.dp, bottom = 30.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    text = stringResource(R.string.journal_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (!journal.enabled) {
                item {
                    Text(
                        text = stringResource(R.string.wear_journal_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
                return@ScalingLazyColumn
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Button(
                        onClick = onAddInsulin,
                        label = { Text(stringResource(R.string.wear_journal_insulin)) },
                    )
                    Button(
                        onClick = onAddCarbs,
                        label = { Text(stringResource(R.string.wear_journal_carbs)) },
                    )
                }
            }
            if (journal.entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.nodata),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(journal.entries) { entry ->
                JournalEntryRow(
                    entry = entry,
                    time = timeFormat.format(Date(entry.timestampMs)),
                    onDelete = {
                        WearJournalSync.sendDelete(entry.id, entry.timestampMs)
                        WearJournalSync.removeLocally(entry.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun JournalEntryRow(
    entry: WearJournalSync.Entry,
    time: String,
    onDelete: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { confirming = !confirming }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        if (confirming) {
            Button(
                onClick = onDelete,
                label = { Text(stringResource(R.string.wear_journal_delete)) },
            )
        }
    }
}

/**
 * Amount dial for a journal entry, shaped like the calibration screen: rotary or
 * the two buttons, then one confirm.
 */
@Composable
fun JournalEntryScreen(
    isInsulin: Boolean,
    timestampMs: Long,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // A dose with no preset carries no insulin curve, so the forecast cannot
    // model it — it would predict as though the dose never happened. Default to
    // the first preset the phone sent and let the user step through them.
    val presets = remember { WearJournalSync.cached().presets }
    var presetIndex by remember { mutableStateOf(0) }
    val preset = presets.getOrNull(presetIndex)
    val focusRequester = remember { FocusRequester() }
    val step = if (isInsulin) 0.5f else 5f
    val min = if (isInsulin) 0.5f else 5f
    val max = if (isInsulin) 30f else 200f
    var value by remember { mutableFloatStateOf(if (isInsulin) 2f else 20f) }
    var rotaryAccum by remember { mutableStateOf(0f) }
    var sending by remember { mutableStateOf(false) }
    var resultOk by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun format(v: Float) =
        if (isInsulin) String.format(java.util.Locale.getDefault(), "%.1f", v)
        else v.roundToInt().toString()

    ScreenScaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .onRotaryScrollEvent { event ->
                    rotaryAccum += event.verticalScrollPixels
                    if (abs(rotaryAccum) > 20f) {
                        value = (value + if (rotaryAccum > 0) step else -step).coerceIn(min, max)
                        rotaryAccum = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(
                    if (isInsulin) R.string.wear_journal_insulin else R.string.wear_journal_carbs,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Button(
                    onClick = { value = (value - step).coerceIn(min, max) },
                    label = { Text("−") },
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = format(value) + if (isInsulin) "U" else "g",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(
                    onClick = { value = (value + step).coerceIn(min, max) },
                    label = { Text("+") },
                    modifier = Modifier.size(40.dp),
                )
            }
            if (isInsulin && presets.size > 1) {
                Button(
                    onClick = { presetIndex = (presetIndex + 1) % presets.size },
                    label = { Text(preset?.name ?: "") },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            } else if (isInsulin && preset != null) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                enabled = !sending,
                onClick = {
                    sending = true
                    resultOk = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            WearJournalSync.sendAdd(
                                timestampMs = timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                type = if (isInsulin) WearJournalSync.TYPE_INSULIN else WearJournalSync.TYPE_CARBS,
                                amount = value,
                                presetId = if (isInsulin) preset?.id ?: 0L else 0L,
                            )
                        }
                        resultOk = ok
                        sending = false
                        if (ok) {
                            // Show it at once; the next serve replaces this with
                            // the phone's own record.
                            WearJournalSync.addLocally(
                                WearJournalSync.Entry(
                                    timestampMs = timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                    id = 0L,
                                    type = if (isInsulin) WearJournalSync.TYPE_INSULIN else WearJournalSync.TYPE_CARBS,
                                    amount = value,
                                    title = format(value) + if (isInsulin) " U" else " g",
                                    presetId = if (isInsulin) preset?.id ?: 0L else 0L,
                                )
                            )
                            onDone()
                        }
                    }
                },
                label = { Text(stringResource(R.string.save)) },
            )
            resultOk?.let { ok ->
                if (!ok) {
                    Text(
                        text = stringResource(R.string.error),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
