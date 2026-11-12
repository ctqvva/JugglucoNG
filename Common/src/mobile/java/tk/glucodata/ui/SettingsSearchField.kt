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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
 * the subtitle is the way to it from this tab, one step per level ("Journal › Import"), so a
 * setting inside a screen reads as such and never passes for the screen itself. The words
 * typed are marked where they matched; a result found by another of its names (an old name,
 * a product, a row's description) says which, after a "·". Tapping one opens the screen or
 * dialog that holds it; for a setting inside a screen, that screen scrolls to its row and
 * marks it.
 */
@Composable
internal fun SettingsSearchResults(
    query: String,
    results: List<SettingsSearchMatcher.Match<ResolvedSettingsSearchEntry>>,
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
    // The matched words take the emphasized weight of their own line's style.
    val titleMark = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = MaterialTheme.typography.titleMediumEmphasized.fontWeight,
    )
    val subtitleMark = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = MaterialTheme.typography.bodyMediumEmphasized.fontWeight,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        results.forEachIndexed { index, match ->
            val result = match.item
            SettingsItem(
                title = marked(result.title, query, titleMark),
                subtitle = buildAnnotatedString {
                    append(marked(result.location, query, subtitleMark))
                    if (match.via != null) {
                        append(" · ")
                        append(marked(match.via, query, subtitleMark))
                    }
                },
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

private fun marked(text: String, query: String, mark: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (range in SettingsSearchMatcher.highlights(text, query)) {
        addStyle(mark, range.first, range.last + 1)
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
