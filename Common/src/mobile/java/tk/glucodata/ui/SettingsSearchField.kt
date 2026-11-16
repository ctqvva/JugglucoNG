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
import tk.glucodata.ui.components.SectionLabel
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
 * Search results as ordinary settings rows, the subtitle being the way to each from this
 * tab, one step per level ("Journal › Import").
 *
 * What fits best comes first and looks it: the results that are what was typed (by name,
 * or by another name, which then follows a "·") lead, and the ones found only through what
 * is around them (the place they live in, their description) follow in a second, labelled
 * stack. Within both, the places
 * (screens, dialogs) keep the tinted icon tile of their Settings-tab group, and the settings
 * inside a screen are quieter: a bare glyph and a muted title, so the typed words, marked
 * in primary, are what stands out. Tapping one opens the screen or dialog that holds it;
 * for a setting inside a screen, that screen scrolls to its row and marks it.
 */
@Composable
internal fun SettingsSearchResults(
    query: String,
    results: List<SettingsSearchMatcher.Match<ResolvedSettingsSearchEntry>>,
    onOpen: (ResolvedSettingsSearchEntry) -> Unit,
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
    val (direct, nearby) = results.partition { it.direct }
    Column {
        ResultStack(query, direct, onOpen)
        if (nearby.isNotEmpty()) {
            // With nothing above it, a label would only announce a lesser group of one.
            if (direct.isNotEmpty()) {
                SectionLabel(text = stringResource(R.string.settings_search_related))
            }
            ResultStack(query, nearby, onOpen)
        }
    }
}

@Composable
private fun ResultStack(
    query: String,
    results: List<SettingsSearchMatcher.Match<ResolvedSettingsSearchEntry>>,
    onOpen: (ResolvedSettingsSearchEntry) -> Unit,
) {
    // The matched words take the emphasized weight of their own line's style. In the
    // subtitle only the other name that brought a result in is marked, and by weight alone:
    // it explains the title, and must not outshine it. The path is left plain; where it is
    // the reason, the stack's label says so.
    val titleMark = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = MaterialTheme.typography.titleMediumEmphasized.fontWeight,
    )
    val viaMark = SpanStyle(fontWeight = MaterialTheme.typography.bodyMediumEmphasized.fontWeight)
    val quietTitle = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        results.forEachIndexed { index, match ->
            val result = match.item
            val inner = result.entry.inner
            SettingsItem(
                title = marked(result.title, query, titleMark, base = if (inner) quietTitle else null),
                subtitle = buildAnnotatedString {
                    append(result.location)
                    if (match.via != null) {
                        append(" · ")
                        append(marked(match.via, query, viaMark))
                    }
                },
                icon = result.entry.icon,
                // No tile for a setting inside a screen: the tinted tile is what the places wear.
                iconTint = if (inner) null else toneColor(result.entry.tone),
                position = when {
                    results.size == 1 -> CardPosition.SINGLE
                    index == 0 -> CardPosition.TOP
                    index == results.lastIndex -> CardPosition.BOTTOM
                    else -> CardPosition.MIDDLE
                },
                onClick = { onOpen(result) },
            )
        }
    }
}

private fun marked(text: String, query: String, mark: SpanStyle, base: Color? = null): AnnotatedString = buildAnnotatedString {
    append(text)
    if (base != null) addStyle(SpanStyle(color = base), 0, text.length)
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
