package tk.glucodata.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R

/** The expandable editor used by API destinations and cloud followers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandableEditorCard(
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDelete: () -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    expandedAlpha: Float = 1f,
    enabled: Boolean = true,
    deleting: Boolean = false,
    deleteLabel: String = stringResource(R.string.delete),
    headerControls: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = cardShape(CardPosition.SINGLE), color = containerColor) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggleExpanded)
                    .heightIn(min = 76.dp).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                leadingContent()
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                headerControls()
                IconButtonTooltip(deleteLabel) {
                    IconButton(onClick = onDelete, enabled = enabled, shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.semantics { contentDescription = deleteLabel }) {
                        if (deleting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Delete, null)
                    }
                }
                val expandLabel = stringResource(if (expanded) R.string.show_less else R.string.show_more)
                IconButtonTooltip(expandLabel) {
                    IconButton(onClick = onToggleExpanded, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, expandLabel)
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).alpha(expandedAlpha),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
                    content()
                }
            }
        }
    }
}
