@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tk.glucodata.ui.components.StableModalBottomSheet
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.InsulinPenManager
import tk.glucodata.InsulinPenScanBus
import tk.glucodata.NovoPen.PenDose
import tk.glucodata.NovoPen.PenSheetOffer
import tk.glucodata.R
import tk.glucodata.data.journal.JournalInsulinPreset
import java.text.DateFormat
import java.util.Date

/**
 * A pen is tapped against the phone from wherever the user happens to be, so this hangs at
 * the top of the tree rather than inside the pen settings screen.
 */
@Composable
fun InsulinPenScanSheetHost() {
    val pending by InsulinPenScanBus.pending.collectAsStateWithLifecycle()
    val result = pending ?: return
    val presets by rememberInsulinPresets()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val selectableInsulins = presets.filterNot(JournalInsulinPreset::isArchived)
    val storedPreset = InsulinPenManager.pen(result.serial)?.insulinPresetId ?: 0L
    var chosenInsulinId by remember(result.serial) { mutableStateOf(storedPreset) }
    val chosenInsulin = selectableInsulins.firstOrNull { it.id == chosenInsulinId }
        ?: selectableInsulins.firstOrNull()

    // One list, one count: what the sheet may offer at all. A dose standing for an entry
    // the reader already wrote starts out ticked, since leaving it unticked would keep the
    // duplicate it was found to be.
    val offered = remember(result) { PenSheetOffer.offerable(result.doses) }
    var selected by remember(result) {
        mutableStateOf(
            PenSheetOffer.preselection(
                result.doses,
                result.merges.keys,
                result.preselectFromSeconds,
            )
        )
    }

    StableModalBottomSheet(
        onDismissRequest = { InsulinPenScanBus.clear() },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            // No icon tile on the header: everything in the sheet then shares one left edge.
            Text(
                stringResource(R.string.insulin_pen_name, result.serial),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.insulin_pen_new_doses, offered.size),
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
                selectableInsulins.forEach { preset ->
                    FilterChip(
                        selected = preset.id == chosenInsulin?.id,
                        onClick = { chosenInsulinId = preset.id },
                        label = { Text(preset.displayName) },
                    )
                }
            }

            Spacer(Modifier.size(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.insulin_pen_doses_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(
                    onClick = {
                        selected = if (selected.size == offered.size) {
                            emptySet()
                        } else {
                            offered.map(PenDose::relativeSeconds).toSet()
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (selected.size == offered.size) {
                                R.string.insulin_pen_select_none
                            } else {
                                R.string.insulin_pen_select_all
                            }
                        )
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(offered, key = PenDose::relativeSeconds) { dose ->
                    DoseRow(
                        dose = dose,
                        replacesEntryAtSeconds = result.merges[dose.relativeSeconds]?.entryTimestampSeconds,
                        checked = dose.relativeSeconds in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) {
                                selected + dose.relativeSeconds
                            } else {
                                selected - dose.relativeSeconds
                            }
                        },
                    )
                }
            }

            if (result.skippedPrimingDoses > 0) {
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.insulin_pen_priming_skipped, result.skippedPrimingDoses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(16.dp))
            Button(
                onClick = {
                    val insulin = chosenInsulin ?: return@Button
                    val confirmed = offered.filter { it.relativeSeconds in selected }
                    InsulinPenManager.importDosesAsync(
                        serial = result.serial,
                        doses = confirmed,
                        presetId = insulin.id,
                        presetName = insulin.displayName,
                        // Only what was left ticked is merged; unticking is how the reader
                        // says the pairing is wrong, and then the entry stays as it was. A
                        // different insulin than the one the pairing was worked out for
                        // drops the proposals too: they were matched on that insulin.
                        merges = if (insulin.id == storedPreset) {
                            result.merges.filterKeys { rel -> confirmed.any { it.relativeSeconds == rel } }
                        } else {
                            emptyMap()
                        },
                    )
                    InsulinPenScanBus.clear()
                },
                enabled = selected.isNotEmpty() && chosenInsulin != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.insulin_pen_add_doses, selected.size))
            }
        }
    }
}

@Composable
private fun DoseRow(
    dose: PenDose,
    replacesEntryAtSeconds: Long?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val hourFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.insulin_pen_units, formatUnits(dose.units)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    timeFormat.format(Date(dose.timestampSeconds * 1000L)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (replacesEntryAtSeconds != null) {
                val at = hourFormat.format(Date(replacesEntryAtSeconds * 1000L))
                val label = stringResource(R.string.insulin_pen_replaces, at)
                // What becomes what, said in the two icons the journal already uses for those
                // sources: a hand-written entry turns into this pen's dose. The icons need no
                // translating and no room to speak, so the words beside them only have to
                // carry which entry it is, and that is a time.
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .semantics(mergeDescendants = true) { contentDescription = label },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Icon(
                        imageVector = Icons.Default.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        at,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal fun formatUnits(units: Float): String =
    if (units % 1f == 0f) units.toInt().toString() else String.format("%.1f", units)
