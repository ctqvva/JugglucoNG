package tk.glucodata.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tk.glucodata.R

/**
 * Said once, the first time a dose hint appears while no model profile has ever been saved:
 * the amounts are being computed from the built-in sensitivity and carb ratio.
 *
 * The suggestion itself is shown regardless. Withholding it would repeat the mistake this
 * exists to avoid, a feature that reads as switched on and quietly does nothing.
 *
 * Emitted only when it is due, never as an empty item: the dashboard's lists space their
 * children with `Arrangement.spacedBy`, which reserves the gap around an item that renders
 * nothing.
 *
 * Built from [AppUpdateCard], which is the dashboard's banner shape rather than anything
 * specific to updates: accent, title, body, an X, and one action.
 */
@Composable
fun StateDoseHintProfileBanner(
    modifier: Modifier = Modifier,
    onAcknowledge: () -> Unit,
    onOpenModelProfile: () -> Unit
) {
    AppUpdateCard(
        accent = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.state_dose_hint_profile_notice_title),
        body = stringResource(R.string.state_dose_hint_profile_notice_body),
        modifier = modifier,
        icon = Icons.Filled.Tune,
        // The X means the same as opening the profile: answered, do not ask again.
        onDismiss = onAcknowledge
    ) {
        AppUpdateFilledAction(
            label = stringResource(R.string.state_dose_hint_profile_notice_open),
            accent = MaterialTheme.colorScheme.tertiary,
            onClick = {
                onAcknowledge()
                onOpenModelProfile()
            }
        )
    }
}
