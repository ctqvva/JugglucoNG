@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package tk.glucodata.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.State
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.InsulinPen
import tk.glucodata.InsulinPenManager
import tk.glucodata.PenDuplicateEntry
import tk.glucodata.R
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.StableModalBottomSheet
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun InsulinPenSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val enabled by InsulinPenManager.enabled.collectAsStateWithLifecycle()
    val pens by InsulinPenManager.pens.collectAsStateWithLifecycle()
    val presets by rememberInsulinPresets()
    val journalEnabled = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
            .getBoolean("dashboard_journal_enabled", true)
    }

    // Held by serial, not by value: choosing an insulin replaces the record, and a captured
    // copy would keep the dialog showing the old selection.
    var editingSerial by remember { mutableStateOf<String?>(null) }
    var forgetTarget by remember { mutableStateOf<InsulinPen?>(null) }
    var cleanupTarget by remember { mutableStateOf<Pair<String, List<PenDuplicateEntry>>?>(null) }
    val scope = rememberCoroutineScope()
    val editing = pens.firstOrNull { it.serial == editingSerial }
    val selectableInsulins = presets.filterNot(JournalInsulinPreset::isArchived)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.insulin_pens_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item("pen_master") {
                MasterSwitchCard(
                    title = stringResource(R.string.insulin_pens_enable_title),
                    subtitle = stringResource(R.string.insulin_pens_enable_desc),
                    checked = enabled,
                    onCheckedChange = InsulinPenManager::setEnabled,
                    icon = Icons.Default.Vaccines,
                )
            }

            // Doses become journal entries, so with the journal switched off a scan would
            // land somewhere the reader never looks. Say so rather than silently working.
            if (enabled && !journalEnabled) {
                item("pen_journal_off") {
                    Text(
                        stringResource(R.string.insulin_pens_journal_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (enabled) {
                item("pen_how") {
                    Spacer(Modifier.size(16.dp))
                    HowToScanCard()
                }

                item("pen_list_label") {
                    Spacer(Modifier.size(16.dp))
                    SectionLabel(stringResource(R.string.insulin_pens_paired))
                }

                if (pens.isEmpty()) {
                    item("pen_list_empty") {
                        SettingsItem(
                            title = stringResource(R.string.insulin_pens_none),
                            subtitle = stringResource(R.string.insulin_pens_none_desc),
                            icon = Icons.Default.Vaccines,
                            iconTint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                } else {
                    itemsIndexed(pens, key = { _, pen -> pen.serial }) { index, pen ->
                        SettingsItem(
                            title = stringResource(R.string.insulin_pen_name, pen.serial),
                            subtitle = penRowSubtitle(pen),
                            icon = Icons.Default.Vaccines,
                            iconTint = MaterialTheme.colorScheme.primary,
                            showArrow = true,
                            position = cardPositionFor(index, pens.size),
                            onClick = { editingSerial = pen.serial },
                        )
                    }
                }
            }
        }
    }

    editing?.let { pen ->
        PenDetailSheet(
            pen = pen,
            presets = selectableInsulins,
            onDismiss = { editingSerial = null },
            onSelected = { preset ->
                InsulinPenManager.setInsulin(pen.serial, preset.id, preset.displayName)
            },
            onArmFullRead = { InsulinPenManager.armFullRead(pen.serial) },
            onCleanup = { duplicates ->
                editingSerial = null
                cleanupTarget = pen.serial to duplicates
            },
            onForget = {
                editingSerial = null
                forgetTarget = pen
            },
        )
    }

    cleanupTarget?.let { (serial, duplicates) ->
        DuplicateCleanupDialog(
            duplicates = duplicates,
            onDismiss = { cleanupTarget = null },
            onConfirm = {
                cleanupTarget = null
                scope.launch {
                    val removed = InsulinPenManager.removeDuplicates(serial)
                    Applic.Toaster(
                        Applic.app.getString(R.string.insulin_pen_duplicates_removed, removed)
                    )
                }
            },
        )
    }

    forgetTarget?.let { pen ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text(stringResource(R.string.insulin_pen_forget_confirm, pen.serial)) },
            text = { Text(stringResource(R.string.insulin_pen_forget_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    InsulinPenManager.forget(pen.serial)
                    forgetTarget = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Names every entry it would drop, because this deletes insulin somebody's journal says
 * was injected. A count alone would ask the reader to trust the matching sight unseen.
 */
@Composable
private fun DuplicateCleanupDialog(
    duplicates: List<PenDuplicateEntry>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insulin_pen_cleanup_confirm, duplicates.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.insulin_pen_cleanup_desc))
                Spacer(Modifier.size(4.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(duplicates, key = PenDuplicateEntry::entryId) { entry ->
                        Text(
                            stringResource(
                                R.string.insulin_pen_units,
                                formatUnits(entry.units)
                            ) + " \u00b7 " + timeFormat.format(Date(entry.timestampMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun HowToScanCard() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    Icons.Default.Contactless,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(28.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.insulin_pens_how_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.insulin_pens_how_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * A pen has more to it than one setting — which insulin it holds, a way to pull its whole
 * log, and a way out when it is finished — so it gets a sheet rather than a dialog.
 */
@Composable
private fun PenDetailSheet(
    pen: InsulinPen,
    presets: List<JournalInsulinPreset>,
    onDismiss: () -> Unit,
    onSelected: (JournalInsulinPreset) -> Unit,
    onArmFullRead: () -> Unit,
    onCleanup: (List<PenDuplicateEntry>) -> Unit,
    onForget: () -> Unit,
) {
    // Worked out against the pen's last read, so the sheet can say how many rather than
    // sending the reader to a dialog that then finds nothing. Null until the check is in.
    var duplicates by remember(pen.serial) { mutableStateOf<List<PenDuplicateEntry>?>(null) }
    LaunchedEffect(pen.serial) { duplicates = InsulinPenManager.findDuplicates(pen.serial) }
    val found = duplicates.orEmpty()
    StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.insulin_pen_name, pen.serial),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                penDetailSubtitle(pen),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.insulin_pen_choose_insulin),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = preset.id == pen.insulinPresetId,
                        onClick = { onSelected(preset) },
                        label = { Text(preset.displayName) },
                    )
                }
            }

            Spacer(Modifier.size(24.dp))
            OutlinedButton(
                onClick = onArmFullRead,
                enabled = !pen.fullReadArmed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    stringResource(
                        if (pen.fullReadArmed) {
                            R.string.insulin_pen_full_read_armed
                        } else {
                            R.string.insulin_pen_full_read
                        }
                    )
                )
            }
            Text(
                stringResource(R.string.insulin_pen_full_read_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.size(16.dp))
            OutlinedButton(
                onClick = { onCleanup(found) },
                enabled = found.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    if (found.isEmpty()) {
                        stringResource(R.string.insulin_pen_cleanup)
                    } else {
                        stringResource(R.string.insulin_pen_cleanup_count, found.size)
                    }
                )
            }
            // Blank until the count is in, rather than claiming there is nothing to remove.
            Text(
                when {
                    duplicates == null -> ""
                    found.isEmpty() -> stringResource(R.string.insulin_pen_cleanup_none)
                    else -> stringResource(R.string.insulin_pen_cleanup_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.insulin_pen_forget),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The row answers the two questions worth answering without opening anything: which insulin
 * is in the pen, and whether it has been read recently. The dose count lives in the sheet —
 * on a small screen at a large font it pushed this to three wrapped lines.
 */
@Composable
private fun penRowSubtitle(pen: InsulinPen): String {
    val insulin = pen.insulinName ?: stringResource(R.string.insulin_pen_insulin_unset)
    return "$insulin \u00b7 ${lastReadText(pen)}"
}

@Composable
private fun penDetailSubtitle(pen: InsulinPen): String {
    val insulin = pen.insulinName ?: stringResource(R.string.insulin_pen_insulin_unset)
    val doses = stringResource(R.string.insulin_pen_dose_count, pen.importedDoseCount)
    return "$insulin \u00b7 $doses \u00b7 ${lastReadText(pen)}"
}

@Composable
private fun lastReadText(pen: InsulinPen): String {
    if (pen.lastScanAt <= 0L) return stringResource(R.string.insulin_pen_never_read)
    val today = Calendar.getInstance()
    val read = Calendar.getInstance().apply { timeInMillis = pen.lastScanAt }
    val sameDay = today.get(Calendar.YEAR) == read.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == read.get(Calendar.DAY_OF_YEAR)
    val format = if (sameDay) {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }
    return stringResource(R.string.insulin_pen_last_read, format.format(Date(pen.lastScanAt)))
}

/** Presets are the journal's insulin library; a pen is tagged with one of them. */
@Composable
internal fun rememberInsulinPresets(): State<List<JournalInsulinPreset>> {
    val repository = remember { JournalRepository() }
    LaunchedEffect(repository) { repository.ensureDefaultInsulinPresets() }
    val flow = remember(repository) { repository.observeInsulinPresets() }
    return flow.collectAsStateWithLifecycle(initialValue = emptyList())
}

private fun cardPositionFor(index: Int, size: Int): CardPosition = when {
    size == 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == size - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}
