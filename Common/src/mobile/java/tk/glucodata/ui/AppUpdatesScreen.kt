@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.update.AppUpdateController
import tk.glucodata.update.AppUpdateUiState
import tk.glucodata.update.UpdateEligibility
import tk.glucodata.update.UpdateError
import tk.glucodata.update.UpdateSource
import tk.glucodata.update.UpdateStage

/**
 * "App updates" — the detail screen behind the Data management entry.
 *
 * Ordered by how often each part is touched, not by narrative: version facts read at the top,
 * the status card and its one action in the middle where a thumb lands, preferences at the
 * bottom. Nothing on this screen happens without a tap.
 */
@Composable
fun AppUpdatesScreen(navController: NavController) {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()
    var showSourceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_updates_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!state.supported) {
                Spacer(Modifier.height(8.dp))
                UnsupportedCard(state)
                SectionLabel(stringResource(R.string.app_updates_section_versions))
                InstalledVersionRow(CardPosition.SINGLE)
                return@Column
            }

//            // --- Versions -------------------------------------------------------------
//            SectionLabel(stringResource(R.string.app_updates_section_versions), topPadding = 12.dp)

            InstalledVersionRow(CardPosition.TOP)
            SettingsItem(
                title = stringResource(R.string.app_updates_latest_title),
                subtitle = state.available?.let { update ->
                    stringResource(
                        R.string.app_updates_latest_value,
                        update.versionName,
                        Formatter.formatShortFileSize(context, update.artifact.sizeBytes)
                    )
                } ?: stringResource(R.string.app_updates_latest_none),
                icon = Icons.Filled.NewReleases,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.BOTTOM
            )

            // --- Status and the action ------------------------------------------------
            // The card only appears when there is something to act on. "You're on the latest
            // version" does not need a container of its own — it is a caption under the button
            // that produced it, not a surface competing with the rest of the screen.
            val hasStatusCard = state.error != null ||
                state.available != null ||
                state.stage != UpdateStage.IDLE

            Spacer(Modifier.height(24.dp))

            if (hasStatusCard) {
                AppUpdateStatusCard(state = state, host = AppUpdateCardHost.SETTINGS)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(16.dp))

            CheckForUpdatesButton(
                checking = state.checking,
                // Downloading or installing is the primary action while a card offers it; the
                // check drops to the quieter treatment rather than competing with it.
                emphasised = !hasStatusCard,
                onClick = { AppUpdateController.checkNow(context) }
            )

            if (!hasStatusCard && !state.checking && state.lastCheckAtMillis > 0L) {
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.app_updates_up_to_date_title) +
                        " · " + stringResource(R.string.app_updates_last_checked, state.lastCheckedLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
//            // --- Preferences ---------------------------------------------------------
//            SectionLabel(stringResource(R.string.app_updates_section_preferences))

            SettingsSwitchItem(
                title = stringResource(R.string.app_updates_auto_title),
                subtitle = stringResource(R.string.app_updates_auto_desc),
                checked = state.autoCheckEnabled,
                icon = Icons.Filled.NotificationsActive,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.TOP,
                onCheckedChange = { AppUpdateController.setAutoCheckEnabled(context, it) }
            )
            SettingsItem(
                title = stringResource(R.string.app_updates_source_title),
                subtitle = state.updateSource,
                icon = Icons.Filled.Link,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.BOTTOM,
                onClick = { showSourceDialog = true }
            )

            val notes = state.available?.notes?.trim().orEmpty()
            if (notes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.app_updates_notes_title))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (showSourceDialog) {
        UpdateSourceDialog(
            current = state.updateSource,
            isDefault = state.isDefaultUpdateSource,
            onSave = {
                AppUpdateController.setUpdateSource(context, it)
                showSourceDialog = false
            },
            onDismiss = { showSourceDialog = false }
        )
    }
}

/**
 * The screen's action, in the same shape as every other full-width action in this app
 * (Nightscout's "Test connection" / "Send now"): 56 dp tall, icon, 10 dp, label. An action is a
 * button — rendering it as a settings row makes it look like a destination and gives it a
 * chevron's worth of affordance instead of a button's.
 */
@Composable
private fun CheckForUpdatesButton(
    checking: Boolean,
    emphasised: Boolean,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_updates_state_checking))
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_updates_action_check))
        }
    }
    if (emphasised) {
        Button(onClick = onClick, enabled = !checking, modifier = modifier, content = content)
    } else {
        OutlinedButton(onClick = onClick, enabled = !checking, modifier = modifier, content = content)
    }
}

@Composable
private fun UnsupportedCard(state: AppUpdateUiState) {
    val context = LocalContext.current
    when (state.blocker) {
        UpdateEligibility.Blocker.MANAGED_BY_STORE -> {
            val installer = remember(context) {
                UpdateEligibility.installerPackage(context).orEmpty()
            }
            AppUpdateCard(
                accent = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.app_updates_unsupported_store_title),
                body = stringResource(R.string.app_updates_unsupported_store_body, installer),
                icon = Icons.Filled.Storefront
            )
        }

        else -> AppUpdateCard(
            accent = MaterialTheme.colorScheme.secondary,
            title = stringResource(R.string.app_updates_unsupported_debug_title),
            body = stringResource(R.string.app_updates_unsupported_debug_body),
            icon = Icons.Filled.DeveloperMode
        )
    }
}

@Composable
private fun InstalledVersionRow(position: CardPosition) {
    SettingsItem(
        title = stringResource(R.string.app_updates_installed_title),
        subtitle = stringResource(
            R.string.app_updates_installed_value,
            BuildConfig.BASE_VERSION_NAME,
            BuildConfig.VERSION_CODE
        ),
        icon = Icons.Filled.Smartphone,
        iconTint = MaterialTheme.colorScheme.secondary,
        position = position
    )
}

/** Lets a fork or mirror be followed instead of the default project. */
@Composable
private fun UpdateSourceDialog(
    current: String,
    isDefault: Boolean,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(current) { mutableStateOf(current) }
    val valid = UpdateSource.isValid(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_updates_source_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotBlank() && !valid,
                    supportingText = {
                        Text(
                            if (text.isNotBlank() && !valid) {
                                stringResource(R.string.app_updates_source_invalid)
                            } else {
                                stringResource(R.string.app_updates_source_help)
                            }
                        )
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isDefault) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onSave(null) }) {
                        Text(stringResource(R.string.app_updates_source_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(text) }) {
                Text(stringResource(R.string.app_updates_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun AppUpdateUiState.lastCheckedLabel(): String =
    if (lastCheckAtMillis > 0L) {
        DateUtils.getRelativeTimeSpanString(
            lastCheckAtMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    } else {
        stringResource(R.string.app_updates_never_checked)
    }
