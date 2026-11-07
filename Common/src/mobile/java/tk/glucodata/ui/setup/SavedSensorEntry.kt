package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.cardShape

/**
 * One sensor in a saved/account list: the ID first (it is what the user matches against the
 * sensor's label), where it stands, and its actions underneath, wrapping when a locale's labels
 * are long.
 */
@Composable
internal fun SavedSensorEntry(
    title: String,
    status: String,
    detail: String?,
    position: CardPosition,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

