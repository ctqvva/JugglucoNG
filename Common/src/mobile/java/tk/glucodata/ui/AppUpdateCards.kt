@file:OptIn(ExperimentalLayoutApi::class)

package tk.glucodata.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.update.AppUpdateController
import tk.glucodata.update.AppUpdateUiState
import tk.glucodata.update.UpdateEligibility
import tk.glucodata.update.UpdateError
import tk.glucodata.update.UpdateStage

/**
 * True when [DashboardAppUpdateBanner] would draw something.
 *
 * The dashboard's LazyColumn uses `Arrangement.spacedBy`, which reserves its gap around *every*
 * item — including one that renders nothing. Call sites use this to skip emitting the item at
 * all, rather than pushing the whole dashboard down by a phantom row.
 */
@Composable
fun rememberAppUpdateBannerVisible(): Boolean {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }
    return state.showIntroCard || state.showUpdateCard
}

/**
 * Dashboard card for the in-app updater.
 *
 * At most one card is ever shown: the one-time opt-in card until the user answers it, then the
 * status card until that particular release is dismissed. The status card carries the whole
 * download → verify → install sequence itself, so pressing the primary action never turns into
 * a trip to another screen to press something else.
 */
@Composable
fun DashboardAppUpdateBanner(
    modifier: Modifier = Modifier,
    onOpenAppUpdates: () -> Unit
) {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }

    AnimatedVisibility(
        visible = state.showIntroCard,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        AppUpdateCard(
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_intro_title),
            body = stringResource(R.string.app_updates_intro_body),
            icon = Icons.Filled.Update,
            // The X means the same thing as "Not now": answered, don't ask again. Leaving it
            // unanswered would just bring the card back on the next launch.
            onDismiss = { AppUpdateController.answerIntro(context, false) }
        ) {
            AppUpdateTextAction(
                label = stringResource(R.string.app_updates_intro_decline),
                onClick = { AppUpdateController.answerIntro(context, false) }
            )
            AppUpdateFilledAction(
                label = stringResource(R.string.app_updates_intro_enable),
                accent = MaterialTheme.colorScheme.primary,
                onClick = { AppUpdateController.answerIntro(context, true) }
            )
        }
    }

    AnimatedVisibility(
        visible = state.showUpdateCard,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        AppUpdateStatusCard(
            state = state,
            host = AppUpdateCardHost.DASHBOARD,
            // Dismissing mid-transfer would hide a running download; the X returns when idle.
            onDismiss = if (state.stage == UpdateStage.IDLE) {
                { AppUpdateController.dismissBanner(context) }
            } else {
                null
            },
            onViewDetails = onOpenAppUpdates
        )
    }
}

/**
 * Data management entry. The subtitle carries the answer the user actually came for, so the
 * common case ("nothing to do") needs no navigation at all.
 */
@Composable
fun AppUpdatesSettingsItem(
    iconTint: Color,
    position: CardPosition,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }

    val subtitle = when {
        !state.supported -> stringResource(R.string.app_updates_state_unavailable)
        state.available != null ->
            stringResource(R.string.app_updates_state_available, state.available!!.versionName)
        state.checking -> stringResource(R.string.app_updates_state_checking)
        !state.autoCheckEnabled -> stringResource(R.string.app_updates_state_off)
        else -> stringResource(R.string.app_updates_state_up_to_date, state.installedVersionName)
    }

    SettingsItem(
        title = stringResource(R.string.app_updates_title),
        subtitle = subtitle,
        icon = Icons.Filled.SystemUpdate,
        iconTint = iconTint,
        position = position,
        onClick = onOpen
    )
}

/**
 * Where the status card is being drawn. The download → verify → install machinery is identical
 * in both places; what differs is how much room there is and how much the surface is allowed to
 * commit the user to.
 */
internal enum class AppUpdateCardHost {
    /**
     * A card interrupting the glucose view. It gets one press to do the whole job — the user is
     * not there to manage an update — and says as little as possible around it.
     */
    DASHBOARD,

    /**
     * The App updates screen, where the user came deliberately. Download and install stay two
     * separate steps, so the staged file can be inspected, discarded or installed later.
     */
    SETTINGS
}

/**
 * The updater's status card, shared by the dashboard and the App updates screen.
 *
 * One component because the progress, verify, permission and failure states are the same
 * everywhere; [host] carries the two differences. [onViewDetails] adds a quiet "View" action for
 * hosts that have somewhere to go; [onDismiss] adds the X.
 */
@Composable
internal fun AppUpdateStatusCard(
    state: AppUpdateUiState,
    host: AppUpdateCardHost,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onViewDetails: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val update = state.available
    val requestInstallPermission = rememberInstallPermissionRequest()

    if (state.error == UpdateError.INSTALL_PERMISSION) {
        AppUpdateCard(
            modifier = modifier,
            accent = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.app_updates_permission_title),
            body = stringResource(R.string.app_updates_permission_body),
            icon = Icons.Filled.Security,
            elevated = true
        ) {
            AppUpdateFilledAction(
                label = stringResource(R.string.app_updates_permission_action),
                accent = MaterialTheme.colorScheme.error,
                onClick = requestInstallPermission
            )
        }
        return
    }

    when (state.stage) {
        UpdateStage.DOWNLOADING -> AppUpdateCard(
            modifier = modifier,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_downloading_title),
            body = stringResource(
                R.string.app_updates_downloading_body,
                Formatter.formatShortFileSize(context, state.downloadedBytes),
                Formatter.formatShortFileSize(context, state.totalBytes)
            ),
            icon = Icons.Filled.Download,
            content = {
                LinearProgressIndicator(
                    progress = { state.downloadFraction },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            actions = {
                AppUpdateTextAction(
                    label = stringResource(R.string.app_updates_action_cancel),
                    onClick = { AppUpdateController.cancelDownload(context) }
                )
            }
        )

        UpdateStage.VERIFYING -> AppUpdateCard(
            modifier = modifier,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_verifying_title),
            body = stringResource(R.string.app_updates_verifying_body),
            icon = Icons.Filled.Security,
            content = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        )

        // Reached when the system prompt was dismissed or the install failed. The normal path
        // goes from VERIFYING straight into that prompt without stopping here.
        UpdateStage.READY_TO_INSTALL, UpdateStage.INSTALLING -> AppUpdateCard(
            modifier = modifier,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_ready_title, update?.versionName.orEmpty()),
            body = state.error?.let { updateErrorText(it) }
                ?: stringResource(R.string.app_updates_ready_body),
            icon = Icons.Filled.SystemUpdate,
            elevated = true
        ) {
            AppUpdateTextAction(
                label = stringResource(R.string.app_updates_action_discard),
                onClick = { AppUpdateController.cancelDownload(context) }
            )
            AppUpdateFilledAction(
                label = stringResource(R.string.app_updates_action_install),
                accent = MaterialTheme.colorScheme.primary,
                onClick = {
                    if (UpdateEligibility.canRequestPackageInstalls(context)) {
                        AppUpdateController.install(context)
                    } else {
                        requestInstallPermission()
                    }
                }
            )
        }

        UpdateStage.IDLE -> when {
            state.error != null -> AppUpdateCard(
                modifier = modifier,
                accent = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.app_updates_error_title),
                body = updateErrorText(state.error),
                icon = Icons.Filled.ErrorOutline,
                onDismiss = onDismiss,
                elevated = true
            )

            update != null -> {
                val oneTap = host == AppUpdateCardHost.DASHBOARD
                AppUpdateCard(
                    modifier = modifier,
                    accent = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.app_updates_card_title),
                    body = if (oneTap) {
                        stringResource(
                            R.string.app_updates_latest_value,
                            update.versionName,
                            Formatter.formatShortFileSize(context, update.artifact.sizeBytes)
                        )
                    } else {
                        stringResource(
                            R.string.app_updates_card_body,
                            update.versionName,
                            Formatter.formatShortFileSize(context, update.artifact.sizeBytes)
                        )
                    },
                    icon = Icons.Filled.Download,
                    onDismiss = onDismiss,
                    elevated = true
                ) {
                    if (onViewDetails != null) {
                        AppUpdateTextAction(
                            label = stringResource(R.string.app_updates_action_view),
                            onClick = onViewDetails
                        )
                    }
                    AppUpdateFilledAction(
                        label = if (oneTap) {
                            downloadActionLabel()
                        } else {
                            stringResource(R.string.app_updates_action_download)
                        },
                        accent = MaterialTheme.colorScheme.tertiary,
                        // From the dashboard, one press does the whole job — an "Install" button
                        // in between would ask for the same decision twice. On the screen the
                        // two steps stay separate; that is where a user manages the update.
                        onClick = { AppUpdateController.startDownload(context, autoInstall = oneTap) }
                    )
                }
            }

            else -> Unit
        }
    }
}

/**
 * "Download and install" is the honest label — it says where the tap ends. It is also around
 * 150 dp wide, which a narrow device or a large accessibility font turns into a wrapped or
 * clipped row, so those get the short form rather than a broken layout.
 */
@Composable
private fun downloadActionLabel(): String {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    val roomy = screenWidthDp >= 380 && fontScale <= 1.15f
    return stringResource(
        if (roomy) R.string.app_updates_action_download_install
        else R.string.app_updates_action_download
    )
}

/** Sends the user to grant "install unknown apps", and resumes the install when they come back. */
@Composable
internal fun rememberInstallPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        AppUpdateController.clearError()
        if (UpdateEligibility.canRequestPackageInstalls(context) &&
            AppUpdateController.state.value.stage == UpdateStage.READY_TO_INSTALL
        ) {
            AppUpdateController.install(context)
        }
    }
    return remember(context, launcher) { { context.launchInstallPermission(launcher::launch) } }
}

/** Android 8+ grants "install unknown apps" per source, so the target is our own package page. */
private fun Context.launchInstallPermission(launch: (Intent) -> Unit) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:$packageName")
    )
    try {
        launch(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                getString(R.string.cgm_readiness_settings_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/**
 * The card shared by the dashboard banner and the App updates screen.
 *
 * Every metric here is copied from [tk.glucodata.ui.components.SettingsItem] on purpose —
 * 16 dp padding, a 40 dp/12 dp icon tile, a 12 dp gap — so the card's text column starts at
 * exactly the same x as the text in every settings row on the same screen. The first version
 * had the title next to the icon and the body under it, which put the two halves of one
 * sentence on two different left edges, neither of which matched the rows below.
 *
 * The only thing that deviates from a row is the corner radius: 20 dp, so it reads as a card
 * rather than another list item.
 */
@Composable
internal fun AppUpdateCard(
    accent: Color,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: (@Composable () -> Unit)? = null,
    actions: (@Composable FlowRowScope.() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (elevated) {
            accent.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            if (icon != null) {
                AppUpdateIconTile(icon = icon, color = accent)
                Spacer(Modifier.width(12.dp))
            }
            // One column: title, body, progress and buttons all hang off a single left edge.
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (onDismiss != null) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cgm_readiness_dismiss_action),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (!body.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (content != null) {
                    Spacer(Modifier.height(16.dp))
                    content()
                }

                if (actions != null) {
                    Spacer(Modifier.height(12.dp))
                    // FlowRow rather than Row: if a long label plus a large font scale still
                    // overruns the card, the buttons wrap onto a second line instead of clipping.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        content = actions
                    )
                }
            }
        }
    }
}

/** Same tile as a settings row's icon: 40 dp, 12 dp radius, 12 % tint, 24 dp glyph. */
@Composable
private fun AppUpdateIconTile(icon: ImageVector, color: Color) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun AppUpdateFilledAction(label: String, accent: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AppUpdateTextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** User-facing text for a failure. Never derived from exception messages: proguard strips those. */
@Composable
internal fun updateErrorText(error: UpdateError): String = stringResource(
    when (error) {
        UpdateError.NETWORK -> R.string.app_updates_error_network
        UpdateError.RATE_LIMITED -> R.string.app_updates_error_rate_limited
        UpdateError.PARSE -> R.string.app_updates_error_parse
        UpdateError.NO_ARTIFACT -> R.string.app_updates_error_no_artifact
        UpdateError.CHECKSUM -> R.string.app_updates_error_checksum
        UpdateError.SIGNATURE -> R.string.app_updates_error_signature
        UpdateError.PACKAGE_MISMATCH -> R.string.app_updates_error_package
        UpdateError.DOWNGRADE -> R.string.app_updates_error_downgrade
        UpdateError.STORAGE -> R.string.app_updates_error_storage
        UpdateError.INSTALL_PERMISSION -> R.string.app_updates_error_install_permission
        UpdateError.INSTALL_FAILED -> R.string.app_updates_error_install_failed
        UpdateError.CANCELLED -> R.string.app_updates_error_cancelled
    }
)
