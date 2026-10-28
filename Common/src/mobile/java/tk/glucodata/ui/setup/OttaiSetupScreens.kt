// JugglucoNG — Ottai setup: the pieces the wizard is built from.
//
// OttaiSetupWizard.kt owns the state and every cloud/BLE call; this file only lays out the
// screens: the card every Ottai status shares, the NFC wake hint, the chosen-sensor card, the QR
// page, the account and registration pages, and the saved-sensors page.

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.setup

import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import tk.glucodata.ui.isAppInDarkTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import tk.glucodata.ui.components.AdaptiveTrailingRow
import tk.glucodata.ui.components.AdaptiveButtonRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
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
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.IconTileDefaults
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.ConnectedButtonGroup

/** Status cards sit beside settings rows, so they take the 20dp radius the guide gives them. */
private val OttaiCardShape = RoundedCornerShape(20.dp)

/** The NFC wake card is the step a China/Syai setup starts with: a hero, so the large radius. */
private val OttaiHeroShape = RoundedCornerShape(28.dp)

internal fun ottaiRowPosition(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == count - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

/**
 * The one card every Ottai status uses. Title first, with the glyph trailing in an expressive
 * cookie, so it never reads as a settings row; while [loading] the loading indicator takes the
 * glyph's place at the same size, so nothing moves when a wait starts or ends. Details sit under
 * the text, actions at the bottom.
 */
@Composable
internal fun OttaiCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    body: String? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    iconContainerColor: Color = iconColor.copy(alpha = IconTileDefaults.ContainerAlpha),
    loading: Boolean = false,
    hero: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    details: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    // In place of the glyph: the card's one action when it acts on the title itself.
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = if (hero) OttaiHeroShape else OttaiCardShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        // The hero's wider inset keeps its text clear of the 28dp corners.
        Column(modifier = Modifier.padding(if (hero) 24.dp else 16.dp)) {
            Row(verticalAlignment = if (trailing != null) Alignment.CenterVertically else Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = if (hero) {
                            MaterialTheme.typography.headlineSmallEmphasized
                        } else {
                            MaterialTheme.typography.titleLargeEmphasized
                        },
                    )
                    if (body != null) {
                        Spacer(Modifier.size(4.dp))
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = supportingColor)
                    }
                }
                Spacer(Modifier.width(16.dp))
                if (trailing != null) {
                    trailing()
                } else {
                    OttaiCardGlyph(
                        icon = icon,
                        color = iconColor,
                        containerColor = iconContainerColor,
                        loading = loading,
                        size = if (hero) 64.dp else 48.dp,
                    )
                }
            }
            if (details != null) {
                Spacer(Modifier.size(12.dp))
                details()
            }
            if (actions != null) {
                Spacer(Modifier.size(16.dp))
                actions()
            }
        }
    }
}

@Composable
private fun OttaiCardGlyph(
    icon: ImageVector,
    color: Color,
    containerColor: Color,
    loading: Boolean,
    size: Dp,
) {
    if (loading) {
        LoadingIndicator(modifier = Modifier.size(size), color = color)
        return
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(containerColor, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size / 2))
    }
}

/** A card's trailing actions: end-aligned on the card's padding, 8dp apart. */
@Composable
internal fun OttaiCardActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
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
 * The NFC wake, said in one line under the search header. China and Syai sensors stay silent over
 * Bluetooth until an NFC field wakes them, and the phone listens the whole time the search is
 * open, so there is nothing to press. A phone without NFC shows nothing; NFC switched off gets the
 * way to turn it on. The icon and text line up with the device rows' icon and name.
 */
@Composable
internal fun OttaiNfcHintRow(state: OttaiNfcWakeState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adapter = remember { runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() }
    var nfcEnabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { nfcEnabled = adapter?.isEnabled == true }
    if (adapter == null) return

    val icon = if (state == OttaiNfcWakeState.WOKE && nfcEnabled) Icons.Default.Check else Icons.Default.Nfc
    val tint = when {
        !nfcEnabled -> StatusPalette.warning()
        state == OttaiNfcWakeState.WOKE -> StatusPalette.success()
        state == OttaiNfcWakeState.UNCONFIRMED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val text = stringResource(
        when {
            !nfcEnabled -> R.string.ottai_nfc_off_title
            state == OttaiNfcWakeState.WOKE -> R.string.ottai_nfc_woke_title
            state == OttaiNfcWakeState.UNCONFIRMED -> R.string.ottai_nfc_unconfirmed
            else -> R.string.ottai_nfc_hint
        },
    )
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!nfcEnabled) {
            TextButton(
                onClick = {
                    val opened = runCatching {
                        context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    }.isSuccess
                    if (!opened) runCatching {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    }
                },
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(stringResource(R.string.ottai_nfc_open_settings)) }
        }
    }
}

/**
 * The chosen sensor's identity, useful status and action in a standard card.
 * With no credentials and no account, offer import or sign-in instead of Connect.
 */
@Composable
internal fun OttaiSensorCard(
    sensorId: String,
    state: OttaiMaterialState,
    loading: Boolean,
    signedIn: Boolean,
    deviceVersion: String,
    bindingState: OttaiCloudBindingUiState,
    busy: Boolean,
    canConnect: Boolean,
    connectLabel: String,
    message: String,
    messageIsError: Boolean,
    onConnect: () -> Unit,
    onImport: () -> Unit,
    onSignIn: () -> Unit,
    onExport: (() -> Unit)? = null,
    onSensorIdChange: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Keep only status text that explains what the user needs to do.
    val hasSelection = OttaiConstants.looksLikeMac(sensorId) || OttaiConstants.isBoxSerial(sensorId)
    val body = when {
        !hasSelection || loading -> null
        state == OttaiMaterialState.MISSING && signedIn -> stringResource(R.string.ottai_state_missing_signed_in)
        state == OttaiMaterialState.MISSING -> stringResource(R.string.ottai_state_missing_signed_out)
        state == OttaiMaterialState.PARTIAL -> stringResource(R.string.ottai_state_partial_body)
        state == OttaiMaterialState.EXPIRED -> stringResource(R.string.ottai_state_expired_desc)
        else -> null
    }
    val bindingText = when (bindingState) {
        OttaiCloudBindingUiState.CHECKING -> stringResource(R.string.ottai_cloud_binding_checking)
        OttaiCloudBindingUiState.BOUND -> stringResource(R.string.ottai_cloud_binding_bound)
        OttaiCloudBindingUiState.NOT_BOUND -> stringResource(R.string.ottai_cloud_binding_not_bound)
        else -> null
    }
    val meta = listOfNotNull(
        sensorId.takeIf { it.isNotBlank() && onSensorIdChange == null },
        deviceVersion.takeIf { !loading && it.isNotBlank() }
            ?.let { stringResource(R.string.ottai_device_version_label, it) },
        bindingText,
    ).joinToString(" · ")
    val bindingFailed = bindingState == OttaiCloudBindingUiState.ERROR
    val getCredentials = hasSelection && !loading && state == OttaiMaterialState.MISSING && !signedIn

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onSensorIdChange != null) {
                OttaiSensorIdEntry(value = sensorId, onValueChange = onSensorIdChange, enabled = !busy)
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.ottai_state_fetching), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (bindingFailed) {
                Text(
                    stringResource(R.string.ottai_cloud_binding_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (getCredentials) {
                Button(
                    onClick = onSignIn,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text(stringResource(R.string.ottai_login_button)) }
            } else if (!loading && canConnect) {
                Button(
                    onClick = onConnect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(connectLabel)
                    }
                }
            }
            AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
                if (!loading && onExport != null) {
                    Button(
                        onClick = onExport,
                        enabled = !busy,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export))
                    }
                    OutlinedButton(
                        onClick = onImport,
                        enabled = !busy,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ottai_credentials_replace))
                    }
                } else {
                    OttaiImportButton(
                        onClick = onImport,
                        enabled = !busy,
                        label = stringResource(R.string.ottai_credentials_import),
                    )
                }
            }
        }
    }
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
    modifier: Modifier = Modifier,
) {
    OttaiCard(
        modifier = modifier,
        title = stringResource(R.string.ottai_unused_binding_title),
        body = stringResource(R.string.ottai_unused_binding_desc, sensorId),
        icon = Icons.Default.LinkOff,
        iconColor = MaterialTheme.colorScheme.error,
        actions = {
            OttaiCardActions { OttaiUnbindButton(enabled = enabled, onClick = onUnbind) }
        },
    )
}

@Composable
private fun OttaiUnbindButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
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
 * What the sign-in form holds. The wizard owns it, not the card: the card sits in the search list,
 * which may drop and recreate it (scrolled away, or re-laid out when the keyboard opens), and a
 * request in flight must still find its own state when it returns.
 */
internal class OttaiSignInForm {
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var statusIsError by mutableStateOf(false)
    var phone by mutableStateOf("")
    var smsCountry by mutableStateOf(OttaiSmsCountry.MAINLAND_CHINA)
    var code by mutableStateOf("")
    var requestId by mutableStateOf("")
    var password by mutableStateOf("")

    fun resetSms() {
        requestId = ""
        code = ""
        status = ""
        statusIsError = false
    }
}

/**
 * The account, at the top of setup because no new sensor works without it: who is signed in and
 * to which backend, with Sign out, or the sign-in form itself. China signs in by SMS; Ottai Global
 * and Syai by email/username and password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OttaiAccountCard(
    signedIn: Boolean,
    accountLogin: String,
    accountRegion: OttaiRegion,
    region: OttaiRegion,
    onRegionChange: (OttaiRegion) -> Unit,
    message: String,
    form: OttaiSignInForm,
    onSignedIn: (login: String) -> Unit,
    onSignOut: () -> Unit,
    onRegister: () -> Unit,
    // The wizard's scope, not this card's: leaving mid-request must not cancel the coroutine
    // after the cloud call has already stored a session, or onSignedIn never runs.
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    if (signedIn) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AdaptiveTrailingRow(
                modifier = Modifier.padding(16.dp),
                trailingContent = {
                    OutlinedButton(
                        onClick = onSignOut,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) { Text(stringResource(R.string.ottai_sign_out)) }
                },
            ) {
                Column {
                    Text(
                        accountLogin.ifBlank { stringResource(R.string.ottai_already_signed_in) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(accountRegion.fullLabelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    val tag = "OttaiAccountCard"
    val context = LocalContext.current
    var showCountryPicker by remember { mutableStateOf(false) }
    if (showCountryPicker) {
        OttaiSmsCountryDialog(
            current = form.smsCountry,
            onSelect = { country ->
                showCountryPicker = false
                if (country != form.smsCountry) {
                    form.smsCountry = country
                    form.phone = ""
                    form.resetSms()
                }
            },
            onDismiss = { showCountryPicker = false },
        )
    }

    val busy = form.busy
    val useSms = region.usesSms
    val smsPhone = form.phone.takeIf { useSms }?.let { normalizeOttaiPhone(it, form.smsCountry) }
    val signIn: () -> Unit = signIn@{
        if (busy) return@signIn
        form.busy = true
        form.status = ""
        form.statusIsError = false
        scope.launch {
            val login = if (useSms) form.smsCountry.prefix + smsPhone.orEmpty() else form.phone.trim()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (useSms) {
                        OttaiCloudClient.smsLogin(
                            context,
                            smsPhone.orEmpty(),
                            form.code,
                            form.requestId,
                            form.smsCountry.phoneCode,
                        )?.ok == true
                    } else {
                        val id = form.phone.trim()
                        val webBase = region.webBase
                        val result = if (webBase != null && id.contains('@')) {
                            OttaiCloudClient.mailLogin(context, id, form.password, webBase)
                        } else {
                            OttaiCloudClient.passwordLogin(context, id, form.password, region.base)
                        }
                        result?.ok == true
                    }
                }.onFailure { Log.w(tag, "login: ${it.message}") }.getOrDefault(false)
            }
            form.busy = false
            if (ok) {
                OttaiRegistry.saveAccountLogin(context, login)
                form.password = ""
                form.code = ""
                onSignedIn(login)
            } else {
                form.statusIsError = true
                form.status = context.getString(R.string.ottai_login_fail) +
                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                    if (useSms) {
                        context.getString(R.string.ottai_login_request_suffix, form.requestId.takeLast(6))
                    } else {
                        ""
                    }
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(12.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val options = OttaiRegion.entries.toList()
                val groups = if (maxWidth < 96.dp * LocalDensity.current.fontScale * options.size) {
                    options.map { listOf(it) }
                } else {
                    listOf(options)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { group ->
                        ConnectedButtonGroup(
                            options = group,
                            selectedOption = region,
                            onOptionSelected = {
                                if (!form.busy) {
                                    onRegionChange(it)
                                    form.resetSms()
                                }
                            },
                            label = { r -> Text(stringResource(r.fullLabelRes)) },
                            labelText = { r -> context.getString(r.fullLabelRes) },
                            modifier = Modifier.fillMaxWidth(),
                            // The default unselected container is this card's own colour; one step down
                            // keeps the unselected buttons visible, as on the sensor card.
                            unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = form.phone,
                    onValueChange = { value ->
                        val next = if (useSms) {
                            value.filter(Char::isDigit).take(form.smsCountry.subscriberDigits)
                        } else {
                            value.trim()
                        }
                        if (next != form.phone && useSms) form.resetSms()
                        form.phone = next
                    },
                    label = {
                        Text(stringResource(if (useSms) R.string.ottai_phone_hint else R.string.ottai_account_hint))
                    },
                    isError = useSms && form.phone.isNotBlank() && smsPhone == null,
                    leadingIcon = if (useSms) {
                        {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(
                                        enabled = !busy,
                                        role = Role.Button,
                                        onClickLabel = stringResource(R.string.ottai_sms_country_title),
                                        onClick = { showCountryPicker = true },
                                    )
                                    .padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(form.smsCountry.prefix)
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
                            form.busy = true
                            form.status = ""
                            form.statusIsError = false
                            scope.launch {
                                val rid = withContext(Dispatchers.IO) {
                                    runCatching {
                                        OttaiCloudClient.requestSmsCode(context, normalizedPhone, form.smsCountry.phoneCode)
                                    }.onFailure { Log.w(tag, "smsCode: ${it.message}") }.getOrNull()
                                }
                                form.busy = false
                                if (rid.isNullOrBlank()) {
                                    form.statusIsError = true
                                    form.status = context.getString(R.string.ottai_login_fail) +
                                        OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                } else {
                                    form.requestId = rid
                                    form.code = ""
                                    form.status = context.getString(R.string.ottai_code_sent, rid.takeLast(6))
                                }
                            }
                        },
                        enabled = !busy && smsPhone != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) { Text(stringResource(R.string.ottai_send_code)) }
                    OutlinedTextField(
                        value = form.code,
                        onValueChange = { form.code = it.trim() },
                        label = { Text(stringResource(R.string.ottai_code_hint)) },
                        singleLine = true,
                        enabled = !busy && form.requestId.isNotBlank(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = { form.password = it },
                        label = { Text(stringResource(R.string.ottai_password_hint)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (form.status.isNotBlank()) OttaiCaption(form.status, isError = form.statusIsError)
            Spacer(Modifier.size(16.dp))
            Button(
                onClick = signIn,
                enabled = !busy && if (useSms) {
                    smsPhone != null && form.code.isNotBlank() && form.requestId.isNotBlank()
                } else {
                    form.phone.isNotBlank() && form.password.isNotBlank()
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.ottai_login_button))
                }
            }
            if (region.webBase != null && !region.usesSms) {
                TextButton(
                    onClick = onRegister,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.ottai_register_cta)) }
            }
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
    // Shared with the sign-in card: one account request at a time, so a sign-in started after
    // Back cannot race this registration to the stored session.
    form: OttaiSignInForm,
    scope: CoroutineScope,
) {
    val tag = "OttaiRegisterStep"
    val context = LocalContext.current
    val busy = form.busy
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
                form.busy = true
                status = ""
                statusIsError = false
                scope.launch {
                    val rid = withContext(Dispatchers.IO) {
                        runCatching { OttaiCloudClient.sendMail(email.trim(), "SIGN_UP", webBase) }
                            .onFailure { Log.w(tag, "sendMail: ${it.message}") }.getOrNull()
                    }
                    form.busy = false
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
                    form.busy = true
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
                        form.busy = false
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

// ---- QR code ----

/**
 * Scan or type the sensor's code. The drawing shows which code: the one on the white applicator's
 * label. A box serial still works, but the applicator is what the user is holding.
 */
@Composable
internal fun OttaiQrStep(
    ui: WizardUiMetrics,
    onScanned: (raw: String) -> Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.horizontalPadding)
            .padding(top = 8.dp, bottom = 32.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = OttaiHeroShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OttaiApplicatorDrawing(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(2.2f),
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    stringResource(R.string.ottai_qr_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        InlineQrScannerCard(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            onScanResult = onScanned,
        )
    }
}

/** Search, selection and Connect share one scrolling surface. */
@Composable
internal fun OttaiSearchList(
    selectedSensor: (@Composable () -> Unit)?,
    scrollRequest: Int,
    focusSelectedSensor: Boolean,
    deviceCount: Int,
    header: @Composable () -> Unit,
    devices: LazyListScope.() -> Unit,
    footer: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollRequest) {
        if (scrollRequest > 0) {
            listState.animateScrollToItem(
                if (focusSelectedSensor && selectedSensor != null) deviceCount + 1 else 0,
            )
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "header") { header() }
        devices()
        if (selectedSensor != null) {
            item(key = "selectedSensor") { selectedSensor() }
        }
        item(key = "footer") { footer() }
    }
}

/** A full-width secondary action, matching the primary button's normal minimum height. */
@Composable
internal fun OttaiImportButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.ottai_import_file),
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** The one controlled ID field shared by manual entry and every sensor picker. */
@Composable
internal fun OttaiSensorIdEntry(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val code = OttaiConstants.extractSetupCode(value)
        ?.takeIf { OttaiConstants.looksLikeMac(it) || OttaiConstants.isBoxSerial(it) }
    val invalid = value.length >= 12 && code == null
    OutlinedTextField(
        value = value,
        enabled = enabled,
        onValueChange = { onValueChange(it.trim()) },
        label = { Text(stringResource(R.string.ottai_sensor_mac_hint)) },
        supportingText = if (invalid) {
            { Text(stringResource(R.string.ottai_sensor_id_supporting)) }
        } else null,
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The Ottai applicator as the user sees it: a white capsule with a grip-dotted cap and a label,
 * the label's QR code framed in the theme colour. Drawn, not a bitmap, so it follows the theme
 * and the font scale's width. Static: nothing here changes, so nothing moves.
 */
@Composable
private fun OttaiApplicatorDrawing(modifier: Modifier = Modifier) {
    val dark = isAppInDarkTheme()
    // ui-guardrails: allow-begin hex_color - a picture of a physical object: its own white plastic, black print and Ottai blue, not theme roles
    // A white plastic object: a touch dimmer on a dark screen so it does not glare.
    val bodyTop = if (dark) Color(0xFFE9E9E6) else Color(0xFFFFFFFF)
    val bodyBottom = if (dark) Color(0xFFC8C8C4) else Color(0xFFE2E2DE)
    val edge = if (dark) Color(0xFF9E9E99) else Color(0xFFC4C4BF)
    val ink = Color(0xFF1D1D1F)
    val brand = Color(0xFF2E5BD8)
    // ui-guardrails: allow-end
    val frame = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()

        // Body and its cap seam.
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(bodyTop, bodyBottom)),
            cornerRadius = CornerRadius(h / 2),
        )
        drawRoundRect(color = edge, cornerRadius = CornerRadius(h / 2), style = Stroke(stroke))
        val seam = Path().apply {
            moveTo(w * 0.2f, h * 0.05f)
            quadraticTo(w * 0.17f, h / 2, w * 0.2f, h * 0.95f)
        }
        drawPath(seam, color = edge, style = Stroke(stroke))
        val dot = 1.6.dp.toPx()
        val pitch = 6.dp.toPx()
        for (col in 0..2) for (row in 0..col) {
            drawCircle(
                color = edge,
                radius = dot,
                center = Offset(w * 0.07f + col * pitch, h / 2 + (row - col / 2f) * pitch),
            )
        }

        // The label, its text lines, and the code.
        val labelLeft = w * 0.25f
        val labelRight = w * 0.91f
        val labelTop = h * 0.2f
        val labelBottom = h * 0.8f
        val labelH = labelBottom - labelTop
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(labelLeft, labelTop),
            size = Size(labelRight - labelLeft, labelH),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        drawRoundRect(
            color = edge.copy(alpha = 0.6f),
            topLeft = Offset(labelLeft, labelTop),
            size = Size(labelRight - labelLeft, labelH),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
        val line = labelH * 0.09f
        val textLeft = labelLeft + labelH * 0.18f
        drawRoundRect(
            color = brand,
            topLeft = Offset(textLeft, labelTop + labelH * 0.2f),
            size = Size(labelH * 0.55f, line * 1.4f),
            cornerRadius = CornerRadius(line),
        )
        listOf(0.48f to 0.9f, 0.66f to 0.75f).forEach { (y, length) ->
            drawRoundRect(
                color = edge,
                topLeft = Offset(textLeft, labelTop + labelH * y),
                size = Size(labelH * length, line),
                cornerRadius = CornerRadius(line / 2),
            )
        }

        val qr = labelH * 0.62f
        val qrLeft = labelRight - labelH * 0.2f - qr
        val qrTop = labelTop + (labelH - qr) / 2
        val module = qr / 9f
        fun cell(x: Int, y: Int, span: Int = 1) = drawRect(
            color = ink,
            topLeft = Offset(qrLeft + x * module, qrTop + y * module),
            size = Size(module * span, module * span),
        )
        // Three finder squares, then a fixed scatter of data modules.
        listOf(0 to 0, 6 to 0, 0 to 6).forEach { (x, y) ->
            drawRect(
                color = ink,
                topLeft = Offset(qrLeft + x * module + module / 4, qrTop + y * module + module / 4),
                size = Size(module * 2.5f, module * 2.5f),
                style = Stroke(module / 2),
            )
            cell(x + 1, y + 1)
        }
        listOf(
            4 to 0, 4 to 1, 3 to 2, 5 to 2, 4 to 3, 0 to 4, 2 to 4, 3 to 4, 6 to 4, 8 to 4,
            1 to 5, 4 to 5, 7 to 5, 4 to 6, 6 to 6, 8 to 6, 3 to 7, 5 to 7, 7 to 7, 4 to 8, 6 to 8, 8 to 8,
        ).forEach { (x, y) -> cell(x, y) }

        // The frame that says "this one".
        val margin = qr * 0.24f
        val left = qrLeft - margin
        val top = qrTop - margin
        val right = qrLeft + qr + margin
        val bottom = qrTop + qr + margin
        drawRoundRect(
            color = frame.copy(alpha = 0.12f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        val arm = (right - left) * 0.3f
        val frameStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        listOf(
            Triple(Offset(left, top), 1f, 1f),
            Triple(Offset(right, top), -1f, 1f),
            Triple(Offset(left, bottom), 1f, -1f),
            Triple(Offset(right, bottom), -1f, -1f),
        ).forEach { (corner, dx, dy) ->
            val bracket = Path().apply {
                moveTo(corner.x + dx * arm, corner.y)
                lineTo(corner.x, corner.y)
                lineTo(corner.x, corner.y + dy * arm)
            }
            drawPath(bracket, color = frame, style = frameStroke)
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
 * in — the account's sensors that are not saved here yet. Each sensor carries its own actions
 * in plain sight: nothing here is behind a menu.
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
    unbindTarget: (sensorId: String) -> OttaiCloudClient.DeviceSummary?,
    onSelectSaved: (OttaiSavedSensor) -> Unit,
    onExport: (OttaiSavedSensor) -> Unit,
    onDelete: (OttaiSavedSensor) -> Unit,
    onSelectAccountDevice: (OttaiCloudClient.DeviceSummary) -> Unit,
    onUnbind: (OttaiCloudClient.DeviceSummary) -> Unit,
    onImport: () -> Unit,
    onRefreshAccount: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.horizontalPadding)
            .padding(bottom = 32.dp),
    ) {
        SectionLabel(stringResource(R.string.ottai_saved_section_phone), topPadding = 8.dp)
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
                    val presence = when {
                        connected -> stringResource(R.string.ottai_ble_scan_connected)
                        sensor.managed -> stringResource(R.string.ottai_ble_scan_registered)
                        else -> null
                    }
                    val unbind = unbindTarget(sensor.sensorId)
                    OttaiSensorEntry(
                        title = sensor.sensorId,
                        status = listOfNotNull(presence, stringResource(ottaiMaterialStateTitle(sensor.state)))
                            .joinToString(" · "),
                        detail = sensor.materials.deviceVersion.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.ottai_device_version_label, it) },
                        position = ottaiRowPosition(index, saved.size),
                    ) {
                        FilledTonalButton(
                            onClick = { onSelectSaved(sensor) },
                            enabled = enabled,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) { Text(stringResource(R.string.ottai_select)) }
                        OutlinedButton(
                            onClick = { onExport(sensor) },
                            enabled = enabled,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ottai_export))
                        }
                        if (unbind != null) {
                            OttaiUnbindButton(enabled = enabled, onClick = { onUnbind(unbind) })
                        }
                        // A managed sensor is removed from its sensor card; its keys must outlive
                        // that card, so only a saved draft can be deleted here.
                        if (!sensor.managed) {
                            val deleteLabel = stringResource(R.string.ottai_delete_saved)
                            IconButtonTooltip(deleteLabel) {
                                IconButton(
                                    onClick = { onDelete(sensor) },
                                    enabled = enabled,
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = deleteLabel,
                                        tint = if (enabled) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        OttaiImportButton(
            onClick = onImport,
            enabled = enabled,
            label = stringResource(R.string.ottai_entry_import_title),
        )

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
                    val unbind = unbindTarget(id)
                    OttaiSensorEntry(
                        title = device.serialNo.ifBlank { id },
                        status = listOfNotNull(
                            stringResource(if (device.isActive) R.string.ottai_sensor_active else R.string.ottai_sensor_past),
                            id.takeIf { device.serialNo.isNotBlank() },
                        ).joinToString(" · "),
                        detail = device.deviceVersion.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.ottai_device_version_label, it) },
                        position = ottaiRowPosition(index, unsaved.size),
                    ) {
                        FilledTonalButton(
                            onClick = { onSelectAccountDevice(device) },
                            enabled = enabled,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) { Text(stringResource(R.string.ottai_select)) }
                        if (unbind != null) {
                            OttaiUnbindButton(enabled = enabled, onClick = { onUnbind(unbind) })
                        }
                    }
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

/**
 * One sensor in a saved/account list: the ID first (it is what the user matches against the
 * sensor's label), where it stands, and its actions underneath, wrapping when a locale's labels
 * are long.
 */
@Composable
private fun OttaiSensorEntry(
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

/** The saved-sensors count for the setup row, or what to do when there are none. */
@Composable
internal fun ottaiSavedSensorsSummary(count: Int): String =
    if (count == 0) {
        stringResource(R.string.ottai_saved_sensors_none)
    } else {
        pluralStringResource(R.plurals.ottai_saved_sensors_count, count, count)
    }
