package tk.glucodata.ui.restricted

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.RestrictedModeConfig
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem

private class AllowedRow(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit,
)

/**
 * The settings under the switch to full mode on the restricted Settings tab: what the adult
 * opened, then theme, language and readiness, which are always open. The rows read as they
 * do in the full settings, but none has a switch of its own: each opens its screen.
 */
@Composable
internal fun RestrictedAllowedSettings(
    config: RestrictedModeConfig,
    themeIcon: ImageVector,
    themeLabel: String,
    languageLabel: String,
    alertsEnabled: Boolean,
    floatingAllowed: Boolean,
    lockScreenEnabled: Boolean,
    onTheme: () -> Unit,
    onLanguage: () -> Unit,
    onOpen: (route: String) -> Unit,
) {
    val alertColor = MaterialTheme.colorScheme.error
    val displayColor = MaterialTheme.colorScheme.secondary
    val generalColor = MaterialTheme.colorScheme.primary
    val rows = buildList {
        if (config.allowAlarmSettings) {
            add(
                AllowedRow(
                    title = stringResource(R.string.glucose_alerts_title),
                    subtitle = stringResource(
                        if (alertsEnabled) R.string.settings_alerts_desc else R.string.settings_alerts_off_desc
                    ),
                    icon = Icons.Default.AddAlert,
                    tint = alertColor,
                    onClick = { onOpen(RestrictedModeRoutes.ALARMS) },
                )
            )
        }
        if (config.allowVoiceSettings) {
            add(
                AllowedRow(
                    title = stringResource(R.string.talker),
                    subtitle = stringResource(R.string.speakglucose) + " • " + stringResource(R.string.speakalarms),
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    tint = displayColor,
                    onClick = { onOpen(RestrictedModeRoutes.VOICE) },
                )
            )
        }
        if (config.allowDisplaySettings) {
            add(
                AllowedRow(
                    title = stringResource(R.string.settings_dashboard_charts_title),
                    subtitle = stringResource(R.string.settings_dashboard_charts_desc),
                    icon = Icons.Default.Palette,
                    tint = displayColor,
                    onClick = { onOpen(RestrictedModeRoutes.DISPLAY_CHARTS) },
                )
            )
            add(
                AllowedRow(
                    title = stringResource(R.string.notification_settings_title),
                    subtitle = stringResource(R.string.notification_settings_subtitle),
                    icon = Icons.Default.ClearAll,
                    tint = displayColor,
                    onClick = { onOpen(RestrictedModeRoutes.DISPLAY_NOTIFICATION) },
                )
            )
            add(
                AllowedRow(
                    title = stringResource(R.string.floatglucose),
                    subtitle = stringResource(
                        if (floatingAllowed) R.string.settings_floating_desc
                        else R.string.settings_floating_permission_desc
                    ),
                    icon = Icons.Default.PictureInPicture,
                    tint = displayColor,
                    onClick = { onOpen(RestrictedModeRoutes.DISPLAY_FLOATING) },
                )
            )
            add(
                AllowedRow(
                    title = stringResource(R.string.settings_lock_screen_title),
                    subtitle = stringResource(
                        if (lockScreenEnabled) R.string.settings_lock_screen_desc
                        else R.string.settings_lock_screen_off_desc
                    ),
                    icon = Icons.Default.ScreenLockPortrait,
                    tint = displayColor,
                    onClick = { onOpen(RestrictedModeRoutes.DISPLAY_LOCK_SCREEN) },
                )
            )
        }
        add(AllowedRow(stringResource(R.string.theme_title), themeLabel, themeIcon, generalColor, onTheme))
        add(AllowedRow(stringResource(R.string.languagename), languageLabel, Icons.Default.Language, generalColor, onLanguage))
        add(
            AllowedRow(
                title = stringResource(R.string.cgm_readiness_title),
                subtitle = stringResource(R.string.cgm_readiness_settings_desc),
                icon = Icons.Default.Security,
                tint = generalColor,
                onClick = { onOpen(RestrictedModeRoutes.READINESS) },
            )
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            SettingsItem(
                title = row.title,
                subtitle = row.subtitle,
                icon = row.icon,
                iconTint = row.tint,
                position = when (index) {
                    0 -> CardPosition.TOP
                    rows.lastIndex -> CardPosition.BOTTOM
                    else -> CardPosition.MIDDLE
                },
                onClick = row.onClick,
            )
        }
    }
}
