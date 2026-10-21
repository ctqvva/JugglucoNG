// JugglucoNG — Ottai setup: the pieces the wizard is built from.
//
// OttaiSetupWizard.kt owns the state and every cloud/BLE call; this file only lays out the
// screens: the status card all Ottai cards share, the NFC wake card, the selected-sensor card,
// the account and registration pages, and the saved-sensors page with its per-sensor sheet.

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.setup

import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.OttaiConstants
import tk.glucodata.drivers.ottai.OttaiRegistry
import tk.glucodata.drivers.ottai.OttaiSmsCountry
import tk.glucodata.drivers.ottai.normalizeOttaiPhone
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.IconTile
import tk.glucodata.ui.components.IconTileDefaults
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.ConnectedButtonGroup

/** Status cards sit beside settings rows, so they take the 20dp radius the guide gives them. */
private val OttaiCardShape = RoundedCornerShape(20.dp)

internal fun ottaiRowPosition(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == count - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

/**
 * The one card every Ottai status uses: the icon tile is a sibling of the whole text block, so
 * title, body, details and actions all start on the 68dp line the settings rows above them use.
 * [loading] swaps the tile for the expressive loading indicator, at the tile's size, so the
 * text does not move when a wait starts or ends.
 */
@Composable
internal fun OttaiStatusCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    details: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = OttaiCardShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            if (loading) {
                Box(modifier = Modifier.size(IconTileDefaults.Size), contentAlignment = Alignment.Center) {
                    LoadingIndicator(modifier = Modifier.size(IconTileDefaults.Size), color = tint)
                }
            } else {
                IconTile(icon = icon, tint = tint)
            }
            Spacer(Modifier.width(IconTileDefaults.Gap))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                if (body != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = supportingColor)
                }
                if (details != null) {
                    Spacer(Modifier.size(4.dp))
                    details()
                }
                if (actions != null) {
                    Spacer(Modifier.size(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

/**
 * A pick-one row for the nearby list: the settings-row surface and inset, with a selected
 * state the reader can see (container + check) and a screen reader can hear (radio role).
 */
@Composable
internal fun OttaiChoiceRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color?,
    selected: Boolean,
    position: CardPosition,
    enabled: Boolean,
    onClick: () -> Unit,
    trailingText: String? = null,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(position),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
            IconTile(
                icon = icon,
                tint = tint,
                containerColor = if (iconTint != null) {
                    tint.copy(alpha = IconTileDefaults.ContainerAlpha)
                } else {
                    Color.Transparent
                },
            )
            Spacer(Modifier.width(IconTileDefaults.Gap))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailingText != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    trailingText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** A caption under a group: muted, on the group's left edge. */
@Composable
internal fun OttaiCaption(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Where the setup-time NFC wake stands. [UNCONFIRMED] is still listening. */
internal enum class OttaiNfcWakeState { IDLE, LISTENING, UNCONFIRMED, WOKE }

/**
 * The NFC wake step. Ottai China and Syai sensors do not advertise until an NFC field wakes them,
 * so on those accounts this card is the first thing the setup asks for, and it is already
 * listening when it appears. It also answers the two questions a silent phone raises: no NFC
 * at all, and NFC switched off.
 */
@Composable
internal fun OttaiNfcWakeCard(
    state: OttaiNfcWakeState,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val adapter = remember { runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() }
    var nfcEnabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { nfcEnabled = adapter?.isEnabled == true }

    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    when {
        adapter == null -> OttaiStatusCard(
            icon = Icons.Default.Nfc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = stringResource(R.string.ottai_nfc_missing_title),
            body = stringResource(R.string.ottai_nfc_missing_body),
        )
        !nfcEnabled -> OttaiStatusCard(
            icon = Icons.Default.Nfc,
            tint = StatusPalette.warning(),
            title = stringResource(R.string.ottai_nfc_off_title),
            body = stringResource(R.string.ottai_nfc_off_body),
            actions = {
                FilledTonalButton(
                    onClick = {
                        val opened = runCatching {
                            context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                        }.isSuccess
                        if (!opened) runCatching {
                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text(stringResource(R.string.ottai_nfc_open_settings)) }
            },
        )
        state == OttaiNfcWakeState.WOKE -> OttaiStatusCard(
            icon = Icons.Default.CheckCircle,
            tint = StatusPalette.success(),
            title = stringResource(R.string.ottai_nfc_woke_title),
            body = stringResource(R.string.ottai_nfc_woke_body),
            actions = {
                TextButton(
                    onClick = onStart,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.ottai_nfc_again)) }
            },
        )
        state == OttaiNfcWakeState.IDLE -> OttaiStatusCard(
            icon = Icons.Default.Nfc,
            tint = onPrimaryContainer,
            title = stringResource(R.string.ottai_nfc_idle_title),
            body = stringResource(R.string.ottai_nfc_body),
            containerColor = primaryContainer,
            contentColor = onPrimaryContainer,
            supportingColor = onPrimaryContainer.copy(alpha = 0.8f),
            actions = {
                Button(
                    onClick = onStart,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ottai_nfc_start))
                }
            },
        )
        else -> OttaiStatusCard(
            icon = Icons.Default.Nfc,
            tint = onPrimaryContainer,
            title = stringResource(R.string.ottai_nfc_listening_title),
            body = stringResource(R.string.ottai_nfc_body),
            loading = true,
            containerColor = primaryContainer,
            contentColor = onPrimaryContainer,
            supportingColor = onPrimaryContainer.copy(alpha = 0.8f),
            details = if (state == OttaiNfcWakeState.UNCONFIRMED) {
                {
                    Text(
                        stringResource(R.string.ottai_nfc_unconfirmed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else null,
        )
    }
}

/**
 * What the selected sensor ID has on this phone, and the one thing to do about it when that is
 * nothing yet. The binding line only appears while signed in, scoped to this exact ID.
 */
@Composable
internal fun OttaiSensorStateCard(
    state: OttaiMaterialState,
    loading: Boolean,
    signedIn: Boolean,
    deviceVersion: String,
    bindingState: OttaiCloudBindingUiState,
    enabled: Boolean,
    onImport: () -> Unit,
    onSignIn: () -> Unit,
) {
    val success = StatusPalette.success()
    val (icon, tint) = when (state) {
        OttaiMaterialState.MISSING -> Icons.Default.Key to MaterialTheme.colorScheme.onSurfaceVariant
        OttaiMaterialState.PARTIAL -> Icons.Default.Warning to StatusPalette.warning()
        OttaiMaterialState.READY_TO_ACTIVATE -> Icons.Default.PlayCircle to MaterialTheme.colorScheme.primary
        OttaiMaterialState.WARMING_UP -> Icons.Default.HourglassTop to MaterialTheme.colorScheme.primary
        OttaiMaterialState.ACTIVE -> Icons.Default.CheckCircle to success
        OttaiMaterialState.EXPIRED -> Icons.Default.EventBusy to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when {
        loading -> stringResource(R.string.ottai_state_fetching)
        else -> stringResource(ottaiMaterialStateTitle(state))
    }
    val body = when {
        loading -> null
        state == OttaiMaterialState.MISSING && signedIn -> stringResource(R.string.ottai_state_missing_signed_in)
        state == OttaiMaterialState.MISSING -> stringResource(R.string.ottai_state_missing_signed_out)
        state == OttaiMaterialState.PARTIAL -> stringResource(R.string.ottai_state_partial_body)
        state == OttaiMaterialState.READY_TO_ACTIVATE -> stringResource(R.string.ottai_state_not_activated_desc)
        state == OttaiMaterialState.WARMING_UP -> stringResource(R.string.ottai_state_warmup_desc)
        state == OttaiMaterialState.ACTIVE -> stringResource(R.string.ottai_state_active_body)
        else -> stringResource(R.string.ottai_state_expired_desc)
    }
    val showVersion = !loading && deviceVersion.isNotBlank()
    val showBinding = bindingState != OttaiCloudBindingUiState.HIDDEN
    OttaiStatusCard(
        icon = icon,
        tint = tint,
        title = title,
        body = body,
        loading = loading,
        details = if (showVersion || showBinding) {
            {
                if (showVersion) {
                    Text(
                        stringResource(R.string.ottai_device_version_label, deviceVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showBinding) {
                    Text(
                        stringResource(
                            when (bindingState) {
                                OttaiCloudBindingUiState.CHECKING -> R.string.ottai_cloud_binding_checking
                                OttaiCloudBindingUiState.BOUND -> R.string.ottai_cloud_binding_bound
                                OttaiCloudBindingUiState.NOT_BOUND -> R.string.ottai_cloud_binding_not_bound
                                else -> R.string.ottai_cloud_binding_error
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bindingState == OttaiCloudBindingUiState.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        } else null,
        actions = if (!loading && state == OttaiMaterialState.MISSING) {
            {
                OutlinedButton(
                    onClick = onImport,
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ottai_credentials_import))
                }
                if (!signedIn) {
                    FilledTonalButton(
                        onClick = onSignIn,
                        enabled = enabled,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) { Text(stringResource(R.string.ottai_login_button)) }
                }
            }
        } else null,
    )
}

internal fun ottaiMaterialStateTitle(state: OttaiMaterialState): Int = when (state) {
    OttaiMaterialState.MISSING -> R.string.ottai_state_missing_title
    OttaiMaterialState.PARTIAL -> R.string.ottai_state_partial_title
    OttaiMaterialState.READY_TO_ACTIVATE -> R.string.ottai_state_not_activated
    OttaiMaterialState.WARMING_UP -> R.string.ottai_state_warmup
    OttaiMaterialState.ACTIVE -> R.string.ottai_state_active
    OttaiMaterialState.EXPIRED -> R.string.ottai_state_expired
}

/** A binding the account still holds for a sensor nothing here manages. Unbinding is permanent. */
@Composable
internal fun OttaiUnusedBindingCard(
    sensorId: String,
    enabled: Boolean,
    onUnbind: () -> Unit,
) {
    OttaiStatusCard(
        icon = Icons.Default.LinkOff,
        tint = MaterialTheme.colorScheme.error,
        title = stringResource(R.string.ottai_unused_binding_title),
        body = stringResource(R.string.ottai_unused_binding_desc, sensorId),
        actions = {
            OttaiUnbindButton(enabled = enabled, onClick = onUnbind)
        },
    )
}

@Composable
private fun OttaiUnbindButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.unbind_sensor))
    }
}

// ---- Account ----

/**
 * The account page: who is signed in and to which backend, or the sign-in form. China signs in
 * by SMS; Ottai Global and Syai by email/username and password. On success the wizard returns to
 * setup, where the account row now says who is signed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OttaiAccountStep(
    ui: WizardUiMetrics,
    signedIn: Boolean,
    accountLogin: String,
    accountRegion: OttaiRegion,
    region: OttaiRegion,
    onRegionChange: (OttaiRegion) -> Unit,
    message: String,
    onSignedIn: (login: String) -> Unit,
    onSignOut: () -> Unit,
    onRegister: () -> Unit,
    // The wizard's scope, not this page's: leaving the page mid-request must not cancel the
    // coroutine after the cloud call has already stored a session, or onSignedIn never runs.
    scope: CoroutineScope,
) {
    val tag = "OttaiAccountStep"
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var smsCountry by remember { mutableStateOf(OttaiSmsCountry.MAINLAND_CHINA) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val resetSms = {
        requestId = ""
        code = ""
        status = ""
        statusIsError = false
    }

    if (showCountryPicker) {
        OttaiSmsCountryDialog(
            current = smsCountry,
            onSelect = { country ->
                showCountryPicker = false
                if (country != smsCountry) {
                    smsCountry = country
                    phone = ""
                    resetSms()
                }
            },
            onDismiss = { showCountryPicker = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.horizontalPadding)
            .padding(top = 8.dp, bottom = 32.dp),
    ) {
        if (signedIn) {
            OttaiStatusCard(
                icon = Icons.Default.AccountCircle,
                tint = MaterialTheme.colorScheme.primary,
                title = accountLogin.ifBlank { stringResource(R.string.ottai_already_signed_in) },
                body = stringResource(accountRegion.fullLabelRes),
                actions = {
                    OutlinedButton(
                        onClick = onSignOut,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) { Text(stringResource(R.string.ottai_sign_out)) }
                },
            )
            OttaiCaption(stringResource(R.string.ottai_account_signed_in_note))
            return@Column
        }

        Text(
            stringResource(R.string.ottai_account_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message.isNotBlank()) OttaiCaption(message, isError = true)

        SectionLabel(stringResource(R.string.ottai_account_app_question))
        ConnectedButtonGroup(
            options = OttaiRegion.entries.toList(),
            selectedOption = region,
            onOptionSelected = {
                onRegionChange(it)
                status = ""
                statusIsError = false
            },
            label = { r -> Text(stringResource(r.fullLabelRes)) },
            labelText = { r -> context.getString(r.fullLabelRes) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(24.dp))

        val useSms = region.usesSms
        val smsPhone = phone.takeIf { useSms }?.let { normalizeOttaiPhone(it, smsCountry) }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = phone,
                onValueChange = { value ->
                    val next = if (useSms) {
                        value.filter(Char::isDigit).take(smsCountry.subscriberDigits)
                    } else {
                        value.trim()
                    }
                    if (next != phone && useSms) resetSms()
                    phone = next
                },
                label = {
                    Text(stringResource(if (useSms) R.string.ottai_phone_hint else R.string.ottai_account_hint))
                },
                supportingText = if (!useSms) {
                    { Text(stringResource(R.string.ottai_account_note)) }
                } else null,
                isError = useSms && phone.isNotBlank() && smsPhone == null,
                leadingIcon = if (useSms) {
                    {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.ottai_sms_country_title),
                                    onClick = { showCountryPicker = true },
                                )
                                .padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(smsCountry.prefix)
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else null,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (useSms) KeyboardType.Phone else KeyboardType.Email,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (useSms) {
                OutlinedButton(
                    onClick = {
                        val normalizedPhone = smsPhone ?: return@OutlinedButton
                        busy = true
                        status = ""
                        statusIsError = false
                        scope.launch {
                            val rid = withContext(Dispatchers.IO) {
                                runCatching {
                                    OttaiCloudClient.requestSmsCode(context, normalizedPhone, smsCountry.phoneCode)
                                }.onFailure { Log.w(tag, "smsCode: ${it.message}") }.getOrNull()
                            }
                            busy = false
                            if (rid.isNullOrBlank()) {
                                statusIsError = true
                                status = context.getString(R.string.ottai_login_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                            } else {
                                requestId = rid
                                code = ""
                                status = context.getString(R.string.ottai_code_sent, rid.takeLast(6))
                            }
                        }
                    },
                    enabled = !busy && smsPhone != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text(stringResource(R.string.ottai_send_code)) }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    label = { Text(stringResource(R.string.ottai_code_hint)) },
                    singleLine = true,
                    enabled = !busy && requestId.isNotBlank(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ottai_password_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    busy = true
                    status = ""
                    statusIsError = false
                    scope.launch {
                        val login = if (useSms) smsCountry.prefix + smsPhone.orEmpty() else phone.trim()
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                if (useSms) {
                                    OttaiCloudClient.smsLogin(
                                        context,
                                        smsPhone.orEmpty(),
                                        code,
                                        requestId,
                                        smsCountry.phoneCode,
                                    )?.ok == true
                                } else {
                                    val id = phone.trim()
                                    val webBase = region.webBase
                                    val result = if (webBase != null && id.contains('@')) {
                                        OttaiCloudClient.mailLogin(context, id, password, webBase)
                                    } else {
                                        OttaiCloudClient.passwordLogin(context, id, password, region.base)
                                    }
                                    result?.ok == true
                                }
                            }.onFailure { Log.w(tag, "login: ${it.message}") }.getOrDefault(false)
                        }
                        busy = false
                        if (ok) {
                            OttaiRegistry.saveAccountLogin(context, login)
                            password = ""
                            code = ""
                            onSignedIn(login)
                        } else {
                            statusIsError = true
                            status = context.getString(R.string.ottai_login_fail) +
                                OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                                if (useSms) {
                                    context.getString(R.string.ottai_login_request_suffix, requestId.takeLast(6))
                                } else {
                                    ""
                                }
                        }
                    }
                },
                enabled = !busy && if (useSms) {
                    smsPhone != null && code.isNotBlank() && requestId.isNotBlank()
                } else {
                    phone.isNotBlank() && password.isNotBlank()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.ottai_login_button))
                }
            }
        }
        if (status.isNotBlank()) OttaiCaption(status, isError = statusIsError)

        if (region.webBase != null && !region.usesSms) {
            Spacer(Modifier.size(8.dp))
            TextButton(
                onClick = onRegister,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(stringResource(R.string.ottai_register_cta)) }
        }
    }
}

/** The app's radio picker dialog (ThemePickerDialog style) for the SMS country code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OttaiSmsCountryDialog(
    current: OttaiSmsCountry,
    onSelect: (OttaiSmsCountry) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.ottai_sms_country_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                OttaiSmsCountry.entries.forEach { country ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.RadioButton) { onSelect(country) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = country == current, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(country.labelRes()) + " (" + country.prefix + ")",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

private fun OttaiSmsCountry.labelRes(): Int = when (this) {
    OttaiSmsCountry.MAINLAND_CHINA -> R.string.ottai_sms_country_cn
    OttaiSmsCountry.HONG_KONG -> R.string.ottai_sms_country_hk
}

/** Email sign-up for Ottai Global and Syai web accounts. */
@Composable
internal fun OttaiRegisterStep(
    ui: WizardUiMetrics,
    region: OttaiRegion,
    onRegistered: (login: String) -> Unit,
    scope: CoroutineScope,
) {
    val tag = "OttaiRegisterStep"
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var regCode by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var regRequestId by remember { mutableStateOf("") }
    val webBase = region.webBase ?: OttaiConstants.WEB_BASE_OTTAI

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.horizontalPadding)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.ottai_register_intro, stringResource(region.fullLabelRes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim() },
            label = { Text(stringResource(R.string.ottai_email_hint)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                busy = true
                status = ""
                statusIsError = false
                scope.launch {
                    val rid = withContext(Dispatchers.IO) {
                        runCatching { OttaiCloudClient.sendMail(email.trim(), "SIGN_UP", webBase) }
                            .onFailure { Log.w(tag, "sendMail: ${it.message}") }.getOrNull()
                    }
                    busy = false
                    if (rid.isNullOrBlank()) {
                        statusIsError = true
                        status = context.getString(R.string.ottai_register_fail) +
                            OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                    } else {
                        regRequestId = rid
                        status = context.getString(R.string.ottai_code_sent_email, email)
                    }
                }
            },
            enabled = !busy && email.contains('@'),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
        ) { Text(stringResource(R.string.ottai_send_code)) }
        OutlinedTextField(
            value = regCode,
            onValueChange = { regCode = it.trim() },
            label = { Text(stringResource(R.string.ottai_verify_code_hint)) },
            singleLine = true,
            enabled = !busy && regRequestId.isNotBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = regPassword,
            onValueChange = { regPassword = it },
            label = { Text(stringResource(R.string.ottai_password_hint)) },
            supportingText = { Text(stringResource(R.string.ottai_password_rule)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it },
            label = { Text(stringResource(R.string.ottai_profile_hint)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            Button(
                onClick = {
                    busy = true
                    status = ""
                    statusIsError = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                OttaiCloudClient.signUp(
                                    context, email.trim(), regPassword, profileName.trim(),
                                    regRequestId, regCode.trim(), webBase,
                                )?.ok == true
                            }.onFailure { Log.w(tag, "signUp: ${it.message}") }.getOrDefault(false)
                        }
                        busy = false
                        if (ok) {
                            OttaiRegistry.saveAccountLogin(context, email.trim())
                            onRegistered(email.trim())
                        } else {
                            statusIsError = true
                            status = context.getString(R.string.ottai_register_fail) +
                                OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                        }
                    }
                },
                enabled = !busy && regRequestId.isNotBlank() && regCode.isNotBlank() &&
                    regPassword.isNotBlank() && profileName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.ottai_register_button))
                }
            }
            if (status.isNotBlank()) OttaiCaption(status, isError = statusIsError)
        }
    }
}

// ---- Saved sensors ----

/** One sensor whose credentials this phone holds: managed (added) or a saved draft. */
internal data class OttaiSavedSensor(
    val record: OttaiRegistry.SensorRecord,
    val managed: Boolean,
    val materials: OttaiRegistry.DeviceMaterials,
    val state: OttaiMaterialState,
) {
    val sensorId: String get() = OttaiConstants.canonicalSensorId(record.sensorId).ifEmpty { record.sensorId }
}

/**
 * Every sensor this phone can connect without the cloud, signed in or not, and — while signed
 * in — the account's sensors that are not saved here yet. A row opens that sensor's sheet; the
 * page itself only imports.
 */
@Composable
internal fun OttaiSavedSensorsStep(
    ui: WizardUiMetrics,
    saved: List<OttaiSavedSensor>,
    connectedAddresses: Set<String>,
    signedIn: Boolean,
    accountDevices: List<OttaiCloudClient.DeviceSummary>?,
    accountLoading: Boolean,
    accountFailure: OttaiCloudClient.CloudFailure?,
    enabled: Boolean,
    status: String,
    statusIsError: Boolean,
    onOpenSaved: (OttaiSavedSensor) -> Unit,
    onOpenAccountDevice: (OttaiCloudClient.DeviceSummary) -> Unit,
    onImport: () -> Unit,
    onRefreshAccount: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.horizontalPadding)
            .padding(top = 8.dp, bottom = 32.dp),
    ) {
        Text(
            stringResource(R.string.ottai_saved_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.isNotBlank()) OttaiCaption(status, isError = statusIsError)

        SectionLabel(stringResource(R.string.ottai_saved_section_phone))
        if (saved.isEmpty()) {
            Text(
                stringResource(R.string.ottai_saved_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                saved.forEachIndexed { index, sensor ->
                    val connected = sensor.managed &&
                        sensor.record.address.uppercase() in connectedAddresses
                    SettingsItem(
                        title = sensor.sensorId,
                        subtitle = ottaiSavedSensorSubtitle(sensor, connected),
                        icon = Icons.Default.Key,
                        iconTint = if (sensor.managed) MaterialTheme.colorScheme.primary else null,
                        position = ottaiRowPosition(index, saved.size),
                        onClick = if (enabled) ({ onOpenSaved(sensor) }) else null,
                    )
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        FilledTonalButton(
            onClick = onImport,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Icon(Icons.Default.FileDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ottai_entry_import_title))
        }

        SectionLabel(stringResource(R.string.ottai_saved_section_account))
        if (!signedIn) {
            SettingsItem(
                title = stringResource(R.string.ottai_account_title),
                subtitle = stringResource(R.string.ottai_saved_account_signed_out),
                icon = Icons.Default.AccountCircle,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onSignIn,
            )
            return@Column
        }
        val savedIds = saved.map { it.sensorId }.toSet()
        val unsaved = accountDevices.orEmpty()
            .filter { OttaiConstants.canonicalSensorId(it.mac) !in savedIds }
        when {
            accountLoading -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingIndicator(modifier = Modifier.size(IconTileDefaults.Size))
                Spacer(Modifier.width(IconTileDefaults.Gap))
                Text(
                    stringResource(R.string.ottai_sensors_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            accountFailure != null -> Text(
                stringResource(R.string.ottai_cloud_binding_error) +
                    accountFailure.text.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            unsaved.isEmpty() -> Text(
                stringResource(
                    if (accountDevices.isNullOrEmpty()) {
                        R.string.ottai_account_no_sensors
                    } else {
                        R.string.ottai_account_all_saved
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                unsaved.forEachIndexed { index, device ->
                    val id = OttaiConstants.canonicalSensorId(device.mac)
                    SettingsItem(
                        title = device.serialNo.ifBlank { id },
                        subtitle = stringResource(
                            if (device.isActive) R.string.ottai_sensor_active else R.string.ottai_sensor_past,
                        ) + " · " + id,
                        icon = Icons.Default.Cloud,
                        iconTint = if (device.isActive) MaterialTheme.colorScheme.tertiary else null,
                        position = ottaiRowPosition(index, unsaved.size),
                        onClick = if (enabled) ({ onOpenAccountDevice(device) }) else null,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onRefreshAccount,
                enabled = enabled && !accountLoading,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.refresh))
            }
        }
    }
}

@Composable
private fun ottaiSavedSensorSubtitle(sensor: OttaiSavedSensor, connected: Boolean): String {
    val presence = stringResource(
        when {
            connected -> R.string.ottai_ble_scan_connected
            sensor.managed -> R.string.ottai_ble_scan_registered
            else -> R.string.ottai_sensor_saved
        },
    )
    return presence + " · " + stringResource(ottaiMaterialStateTitle(sensor.state))
}

/** What can be done with one saved sensor. Destructive actions last, and only where allowed. */
@Composable
internal fun OttaiSavedSensorSheet(
    sensor: OttaiSavedSensor,
    connected: Boolean,
    unbindTarget: OttaiCloudClient.DeviceSummary?,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onExport: () -> Unit,
    onUnbind: () -> Unit,
    onDelete: () -> Unit,
) {
    OttaiSheet(
        title = sensor.sensorId,
        subtitle = ottaiSavedSensorSubtitle(sensor, connected),
        detail = sensor.materials.deviceVersion.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.ottai_device_version_label, it) },
        onDismiss = onDismiss,
    ) {
        Button(
            onClick = onUse,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
        ) { Text(stringResource(R.string.ottai_sheet_use)) }
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Icon(Icons.Default.FileUpload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ottai_export_file))
        }
        if (unbindTarget != null) {
            OttaiUnbindButton(
                enabled = true,
                onClick = onUnbind,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
        // A managed sensor is removed from its sensor card; its keys must outlive that card.
        if (!sensor.managed) {
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(stringResource(R.string.ottai_delete_saved)) }
        }
    }
}

@Composable
internal fun OttaiAccountDeviceSheet(
    device: OttaiCloudClient.DeviceSummary,
    unbindTarget: OttaiCloudClient.DeviceSummary?,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onUnbind: () -> Unit,
) {
    val id = OttaiConstants.canonicalSensorId(device.mac)
    OttaiSheet(
        title = device.serialNo.ifBlank { id },
        subtitle = stringResource(
            if (device.isActive) R.string.ottai_sensor_active else R.string.ottai_sensor_past,
        ) + " · " + id,
        detail = device.deviceVersion.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.ottai_device_version_label, it) },
        onDismiss = onDismiss,
    ) {
        Button(
            onClick = onUse,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
        ) { Text(stringResource(R.string.ottai_sheet_use)) }
        if (unbindTarget != null) {
            OttaiUnbindButton(
                enabled = true,
                onClick = onUnbind,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OttaiSheet(
    title: String,
    subtitle: String,
    detail: String?,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
            Spacer(Modifier.size(4.dp))
            Text(
                subtitle,
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
            Spacer(Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

/** The saved-sensors count for the setup row, or what to do when there are none. */
@Composable
internal fun ottaiSavedSensorsSummary(count: Int): String =
    if (count == 0) {
        stringResource(R.string.ottai_saved_sensors_none)
    } else {
        pluralStringResource(R.plurals.ottai_saved_sensors_count, count, count)
    }
