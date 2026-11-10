@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import tk.glucodata.R

/** The round selection marker used by the calibration and food lists. */
@Composable
fun SelectionIndicator(visible: Boolean, selected: Boolean, gap: Dp = 12.dp) {
    AnimatedVisibility(visible = visible, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Row {
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(gap))
        }
    }
}

/** Shared selection chrome; each list supplies only its own bulk actions. */
@Composable
fun SelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    onCancel: () -> Unit,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        leadingContent = {
            IconButtonTooltip(stringResource(R.string.cancel)) {
                IconButton(onClick = onCancel, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
            Text(stringResource(R.string.selected_count, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp))
        }
    ) {
        val label = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all)
        IconButtonTooltip(label) {
            IconButton(onClick = onToggleSelectAll, shapes = IconButtonDefaults.shapes()) {
                Icon(if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll, contentDescription = label)
            }
        }
        actions()
    }
}
