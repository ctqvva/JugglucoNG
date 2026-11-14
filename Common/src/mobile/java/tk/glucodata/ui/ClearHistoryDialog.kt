package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.ClearScope

/**
 * Confirms "Clear history" and asks what it covers: readings, journal entries, or both.
 *
 * Built like the other pickers on this screen (radio rows in a [BasicAlertDialog]). Starts on
 * readings, the narrowest of the three, so a quick confirm never takes the journal with it. The
 * line under the choices spells out what the chosen scope removes and what stays, since that is
 * what the confirmation is for.
 */
@Composable
internal fun ClearHistoryDialog(
    onConfirm: (ClearScope) -> Unit,
    onDismiss: () -> Unit,
) {
    var scope by rememberSaveable { mutableStateOf(ClearScope.READINGS) }
    val options = listOf(
        ClearScope.READINGS to stringResource(R.string.readings),
        ClearScope.JOURNAL to stringResource(R.string.journal_title),
        ClearScope.BOTH to stringResource(R.string.both),
    )
    val caption = when (scope) {
        ClearScope.READINGS -> R.string.clear_history_scope_readings
        ClearScope.JOURNAL -> R.string.clear_history_scope_journal
        ClearScope.BOTH -> R.string.clear_history_scope_both
    }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.clean_history_confirm),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Column(modifier = Modifier.selectableGroup()) {
                    options.forEach { (option, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = scope == option,
                                    onClick = { scope = option },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = scope == option, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Text(
                    text = stringResource(caption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = { onConfirm(scope) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) { Text(stringResource(R.string.clear)) }
                }
            }
        }
    }
}
