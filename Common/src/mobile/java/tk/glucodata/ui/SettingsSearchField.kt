package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.SettingsItem

/**
 * The search field under the Settings title. It filters in place: the tab's groups give way
 * to the results while there is a query, so there is no second screen to dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val clearLabel = stringResource(R.string.settings_search_clear)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SearchBarDefaults.inputFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = { focusManager.clearFocus() },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButtonTooltip(clearLabel) {
                        IconButton(onClick = { onQueryChange("") }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Default.Close, contentDescription = clearLabel)
                        }
                    }
                }
            } else {
                null
            },
        )
    }
}

/**
 * Search results as one card stack of ordinary settings rows: the title is the setting,
 * the subtitle is where it lives. Tapping one opens the screen or dialog that holds it;
 * for a setting inside a screen, that screen scrolls to its row and marks it.
 */
@Composable
internal fun SettingsSearchResults(
    query: String,
    results: List<ResolvedSettingsSearchEntry>,
    onOpen: (SettingsSearchEntry) -> Unit,
) {
    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_search_no_results, query.trim()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        results.forEachIndexed { index, result ->
            SettingsItem(
                title = result.title,
                subtitle = result.location,
                icon = result.entry.icon,
                iconTint = toneColor(result.entry.tone),
                position = when {
                    results.size == 1 -> CardPosition.SINGLE
                    index == 0 -> CardPosition.TOP
                    index == results.lastIndex -> CardPosition.BOTTOM
                    else -> CardPosition.MIDDLE
                },
                onClick = { onOpen(result.entry) },
            )
        }
    }
}

@Composable
private fun toneColor(tone: SettingsSearchTone): Color = when (tone) {
    SettingsSearchTone.PRIMARY -> MaterialTheme.colorScheme.primary
    SettingsSearchTone.SECONDARY -> MaterialTheme.colorScheme.secondary
    SettingsSearchTone.TERTIARY -> MaterialTheme.colorScheme.tertiary
    SettingsSearchTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    SettingsSearchTone.ERROR -> MaterialTheme.colorScheme.error
}
