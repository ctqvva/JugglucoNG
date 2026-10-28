// JugglucoNG — Ottai Setup Wizard
//
// Ottai setup keeps all protocol decisions in the driver/cloud helpers. The UI collects the
// account or a credentials file, wakes the sensor over NFC where the sensor needs it, picks the
// sensor from a Bluetooth scan, and starts the managed sensor connection; first-use activation
// is handled during connect. The pages themselves are laid out in OttaiSetupScreens.kt.

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.setup

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.TextButton
import tk.glucodata.ui.components.AdaptiveTrailingRow
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.drivers.ottai.OttaiBleManager
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.OttaiConstants
import tk.glucodata.drivers.ottai.OttaiNfc
import tk.glucodata.drivers.ottai.OttaiRegistry
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.findActivity
import tk.glucodata.ui.util.rememberBleScanner
import java.util.UUID

/**
 * Setup opens on SEARCH: the account (sign-in form or who is signed in) on top, then the nearby
 * list. Picking a sensor anywhere (the list, QR, Saved sensors, an imported file) shows its card
 * on the search page. The separate unbind action only releases the cloud binding; nothing
 * connects until Connect.
 */
internal enum class OttaiSetupStep { SEARCH, QR, SAVED_SENSORS, REGISTER, CONNECTING, SUCCESS }

/**
 * Cloud account region. CN is the original phone+SMS app (api.ottai.com); Global (Ottai
 * com.ottai.seas, seas.ottai.com) and syai (api.syai.com) are username/email + password and
 * share the same API. [usesSms] picks the login form.
 */
internal enum class OttaiRegion(
    val fullLabelRes: Int,
    val base: String,
    val usesSms: Boolean,
    val webBase: String?,  // website account API (email login + registration); null = no web account flow
) {
    CN(R.string.ottai_region_cn_full, OttaiConstants.API_BASE, true, null),
    GLOBAL(R.string.ottai_region_global_full, OttaiConstants.API_BASE_GLOBAL, false, OttaiConstants.WEB_BASE_OTTAI),
    SYAI(R.string.ottai_region_syai, OttaiConstants.API_BASE_SYAI, false, OttaiConstants.WEB_BASE_SYAI),
}

/** The region a stored session belongs to. Syai used to be shown as Global here. */
internal fun ottaiRegionForApiBase(apiBase: String): OttaiRegion =
    OttaiRegion.entries.firstOrNull { it.base == apiBase } ?: OttaiRegion.GLOBAL

private const val OTTAI_OFFICIAL_RSSI_THRESHOLD = -70
/**
 * How often the saved-sensors page re-reads the live GATT state. The driver reconnects an added
 * sensor within a second of any drop, so "added" flips to "connected" while the page is open and
 * the list has to follow.
 */
private const val OTTAI_KNOWN_SENSOR_REFRESH_MS = 2_000L
private const val OTTAI_SCAN_LOG = "OttaiBleScanPanel"

/**
 * What one [fetchOttaiMaterials] run produced: [materials] when a route yielded a usable auth-key
 * set, and the FIRST cloud failure the chain hit.
 *
 * Only the first failure is actionable, and it cannot be read off
 * [OttaiCloudClient.lastFailure] afterwards: every route clears that field the moment its own HTTP
 * call succeeds, and getBindDevice DOES succeed for a MAC the account has not bound — it just
 * answers with a different sensor. So validate's AppUser_AlreadyBinding was erased by the very next
 * step and the user was left with the generic "no materials for this cloud ID".
 */
private data class OttaiMaterialFetch(
    val materials: OttaiRegistry.DeviceMaterials?,
    val failure: OttaiCloudClient.CloudFailure? = null,
    val validatedDeviceVersion: String = "",
    val requiresV3Bootstrap: Boolean = false,
)

/**
 * Fetch + decrypt the per-sensor materials for a MAC: prefer locally-saved ones, else
 * validate-by-mac against the cloud (requires being signed in) and persist. Returns
 * no materials if neither yields a usable auth-key set.
 */
private fun fetchOttaiMaterials(
    context: Context,
    mac: String,
    deviceVersion: String? = null,
    historicalActiveTimeMs: Long = 0L,
): OttaiMaterialFetch {
    val canonical = OttaiConstants.canonicalSensorId(mac).ifEmpty { return OttaiMaterialFetch(null) }
    OttaiRegistry.loadMaterials(context, canonical).takeIf { it.authKeys != null }
        ?.let { return OttaiMaterialFetch(it) }
    // validate-by-mac works for an unbound sensor, but one we already activated returns
    // AppDevice_AlreadyUsed there. Fall back to getBindDevice — the currently-bound sensor's
    // materials (incl. the cgmDeviceMethodVO method) — without needing to re-bind. Previously-used
    // account sensors can still be recovered by a temporary bind/unbind when listDevices supplied
    // the deviceVersion required by the bind endpoint.
    var failure: OttaiCloudClient.CloudFailure? = null
    var validation: OttaiCloudClient.DeviceValidation? = null

    fun viaValidate(): OttaiRegistry.DeviceMaterials? {
        val result = OttaiCloudClient.validateForSetup(context, canonical) ?: return null
        validation = result
        val resp = result.device
        if (resp.keyA.isBlank()) return null
        return OttaiCloudClient.toMaterials(context, canonical, resp)?.takeIf { it.authKeys != null }
    }
    fun viaBound(): OttaiRegistry.DeviceMaterials? {
        val resp = OttaiCloudClient.getBindDevice(context) ?: return null
        val boundId = OttaiConstants.canonicalSensorId(resp.mac)
        if (!OttaiConstants.matchesCanonicalOrKnownNativeAlias(boundId, canonical)) return null
        return OttaiCloudClient.toMaterials(context, boundId, resp)?.takeIf { it.authKeys != null }
    }
    fun viaTemporaryBind(): OttaiRegistry.DeviceMaterials? {
        // Account-list selections supply the real version. Syai and global Ottai additionally
        // permit recovery of an expired sensor outside the signed-in account; allow their known
        // bind metadata only after validate explicitly returned OutOfProduceTime. Other manually
        // entered IDs remain unable to reach the state-changing bind/unbind fallback.
        val version = OttaiCloudClient.materialBindDeviceVersion(
            context,
            deviceVersion,
            failure?.code,
        ) ?: return null
        val resp = OttaiCloudClient.bindForMaterials(
            context,
            canonical,
            version,
            historicalActiveTimeMs,
        ) ?: return null
        val boundId = OttaiConstants.canonicalSensorId(resp.mac).ifBlank { canonical }
        if (!OttaiConstants.matchesCanonicalOrKnownNativeAlias(boundId, canonical)) return null
        return OttaiCloudClient.toMaterials(context, canonical, resp)?.takeIf { it.authKeys != null }
    }
    fun step(route: () -> OttaiRegistry.DeviceMaterials?): OttaiRegistry.DeviceMaterials? =
        route().also { if (it == null && failure == null) failure = OttaiCloudClient.lastFailure }
    val direct = step { viaValidate() } ?: step { viaBound() }
    val m = direct ?: run {
        // A fresh V3 sensor cannot bind yet: its current authDev/authSign must first be read over
        // BLE and completed through cgmAuth. Return that route to the explicit setup action rather
        // than trying bindV3 before the sensor-mediated handshake or falling back to legacy bind.
        if (validation?.requiresV3Bind == true) {
            return OttaiMaterialFetch(
                materials = null,
                failure = failure,
                validatedDeviceVersion = validation?.device?.deviceVersion.orEmpty(),
                requiresV3Bootstrap = true,
            )
        }
        step { viaTemporaryBind() }
            ?: return OttaiMaterialFetch(null, failure, validation?.device?.deviceVersion.orEmpty())
    }
    OttaiRegistry.saveDraftRecord(
        context,
        canonical,
        OttaiConstants.macWithColons(canonical),
        OttaiConstants.DEFAULT_DISPLAY_NAME,
    )
    if (!OttaiRegistry.saveMaterials(context, canonical, m)) {
        return OttaiMaterialFetch(
            null,
            OttaiCloudClient.CloudFailure("Could not save sensor materials"),
            validation?.device?.deviceVersion.orEmpty(),
        )
    }
    return OttaiMaterialFetch(m, validatedDeviceVersion = validation?.device?.deviceVersion.orEmpty())
}

/**
 * The message for a material fetch that produced nothing. AppUser_AlreadyBinding gets its own
 * headline because the generic one hides the single thing the user can act on, and every failure
 * names the credentials-file route: the 2026-07-29 activation ended there after twelve refused
 * validate calls, with the cloud never supplying anything.
 */
private fun ottaiMaterialFailureMessage(
    context: Context,
    failure: OttaiCloudClient.CloudFailure?,
): String {
    // A fetch that THREW never returned its captured failure, so fall back to the client's last
    // one rather than showing a bare headline — that path used to append lastError and must not
    // come out of this change with less detail than it had.
    val effective = failure ?: OttaiCloudClient.lastFailure
    // A rejected session is not a sensor problem; the caller has already signed the account
    // out, so the offline-routes hint would only send the user the wrong way.
    if (effective?.isTokenInvalid == true) return context.getString(R.string.ottai_session_invalid)
    val headline = if (effective?.code.equals(OttaiCloudClient.BIZ_ALREADY_BINDING, ignoreCase = true)) {
        context.getString(R.string.ottai_already_binding_hint)
    } else {
        context.getString(R.string.ottai_fetch_failed)
    }
    val detail = effective?.text.orEmpty().takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    return headline + detail + "\n" + context.getString(R.string.ottai_offline_hint)
}

/**
 * Add + connect a sensor whose materials are already saved. This is called only from
 * the explicit setup action; [activate] arms the irreversible first-use sequence.
 */
private fun connectOttaiSensor(
    context: Context,
    mac: String,
    bleAddress: String? = null,
    activate: Boolean,
    route: OttaiSetupConnectRoute,
): Boolean {
    val canonical = OttaiConstants.canonicalSensorId(mac).ifEmpty { return false }
    val ble = OttaiConstants.normalizeBleAddress(
        bleAddress, allowPlain = false,
    ) ?: OttaiConstants.normalizeBleAddress(
        OttaiRegistry.findDraftRecord(context, canonical)?.address, allowPlain = false,
    ) ?: OttaiConstants.normalizeBleAddress(
        OttaiRegistry.findRecord(context, canonical)?.address, allowPlain = false,
    ) ?: OttaiConstants.macWithColons(canonical)
    if (!ottaiSetupPublishesManagedSensor(route)) return false
    return when (route) {
        OttaiSetupConnectRoute.STORED_MATERIALS -> {
            if (OttaiRegistry.loadMaterials(context, canonical).authKeys == null) return false
            // The panel scanner shares Android's five startScan calls per 30s with the managed
            // scan. Leaving it running makes scanStarter skip, and the connect screen stays on
            // "Looking for nearby transmitters" with no advertisement reaching the new callback.
            OttaiSetupScanHold.stopPanelScans()
            OttaiRegistry.addSensorForUserConnect(
                context,
                canonical,
                ble,
                OttaiConstants.DEFAULT_DISPLAY_NAME,
                activate = activate,
                // Cloud activeTime is not authoritative. The explicit setup action may safely
                // arm activation and let the authenticated command byte decide: <3 activates,
                // 3 streams, and >=4 remains ended without a lifetime write.
                activateIfNeeded = true,
            ) != null
        }
        OttaiSetupConnectRoute.V3_CREDENTIAL_BOOTSTRAP -> false
        OttaiSetupConnectRoute.BLOCKED -> false
    }
}

private data class OttaiScanCandidate(
    val address: String,
    val displayName: String,
    val rssi: Int,
    val advertisesAuth: Boolean,
    val advertisesCgm: Boolean,
    val advertisesDeviceInfo: Boolean,
    val nameLooksOttai: Boolean,
    val nameLooksCgm: Boolean,
    val serviceSummary: String,
) {
    val isLikelyOttai: Boolean
        get() = advertisesAuth || nameLooksOttai || (advertisesCgm && advertisesDeviceInfo)

    val isPossibleCgm: Boolean
        get() = isLikelyOttai || advertisesCgm || nameLooksCgm
}

private data class OttaiScanStats(
    val candidate: OttaiScanCandidate,
    val totalHits: Int,
    val strongHits: Int,
    val bestRssi: Int,
) {
    /**
     * The official scanner's acceptance rule: RSSI at or above [OTTAI_OFFICIAL_RSSI_THRESHOLD]
     * for more than half of the address's hits. It stays the bar for the primary list, but it is
     * only a confidence rule — an Ottai transmitter worn under clothing routinely sits below it,
     * so anything that fails it is demoted, never dropped.
     */
    val isStableOfficialCandidate: Boolean
        get() = strongHits > 0 && strongHits * 2 > totalHits
}

internal enum class OttaiMaterialState {
    MISSING,
    READY_TO_ACTIVATE,
    WARMING_UP,
    ACTIVE,
    EXPIRED,
    PARTIAL,
}

internal enum class OttaiSetupConnectRoute {
    STORED_MATERIALS,
    V3_CREDENTIAL_BOOTSTRAP,
    BLOCKED,
}

internal fun ottaiSetupConnectRoute(
    hasAuthKeys: Boolean,
    requiresV3Bootstrap: Boolean,
    signedIn: Boolean,
): OttaiSetupConnectRoute = when {
    hasAuthKeys -> OttaiSetupConnectRoute.STORED_MATERIALS
    requiresV3Bootstrap && signedIn -> OttaiSetupConnectRoute.V3_CREDENTIAL_BOOTSTRAP
    else -> OttaiSetupConnectRoute.BLOCKED
}

internal fun ottaiSetupPublishesManagedSensor(route: OttaiSetupConnectRoute): Boolean =
    route == OttaiSetupConnectRoute.STORED_MATERIALS

/** Selection may fetch missing credentials, but never starts the managed sensor connection. */
internal fun ottaiSetupSelectionFetchesCredentials(hasAuthKeys: Boolean, signedIn: Boolean): Boolean =
    signedIn && !hasAuthKeys

/** Exact active cloud binding for the selected account sensor; never resolves by suffix/alias. */
internal fun ottaiActiveCloudUnbindTarget(
    selectedSensorId: String,
    devices: List<OttaiCloudClient.DeviceSummary>?,
): OttaiCloudClient.DeviceSummary? {
    val selected = OttaiConstants.canonicalSensorId(selectedSensorId)
    if (!OttaiConstants.looksLikeMac(selected)) return null
    return devices?.firstOrNull { device ->
        device.isActive && OttaiConstants.canonicalSensorId(device.mac) == selected
    }
}

/**
 * Account bindings no managed sensor accounts for: what a sensor removed without unbinding, or
 * one that simply ended, leaves behind. The account holds one binding at a time, so a stale one
 * blocks the next activation (AppUser_AlreadyBinding) until something releases it.
 */
internal fun ottaiUnusedCloudBindings(
    devices: List<OttaiCloudClient.DeviceSummary>?,
    managedSensorIds: Collection<String>,
): List<OttaiCloudClient.DeviceSummary> {
    val managed = managedSensorIds.map(OttaiConstants::canonicalSensorId).toSet()
    return devices.orEmpty().filter { device ->
        val mac = OttaiConstants.canonicalSensorId(device.mac)
        device.isActive && OttaiConstants.looksLikeMac(mac) && mac !in managed
    }.distinctBy { OttaiConstants.canonicalSensorId(it.mac) }
}

internal enum class OttaiCloudBindingUiState {
    HIDDEN,
    CHECKING,
    BOUND,
    NOT_BOUND,
    ERROR,
}

/** Binding feedback is scoped to the exact selected MAC so stale account results never leak. */
internal fun ottaiCloudBindingUiState(
    signedIn: Boolean,
    selectedSensorId: String,
    checkingSensorId: String,
    checkedSensorId: String,
    failedSensorId: String,
    devices: List<OttaiCloudClient.DeviceSummary>?,
): OttaiCloudBindingUiState {
    if (!signedIn) return OttaiCloudBindingUiState.HIDDEN
    val selected = OttaiConstants.canonicalSensorId(selectedSensorId)
    if (!OttaiConstants.looksLikeMac(selected)) return OttaiCloudBindingUiState.HIDDEN
    return when (selected) {
        OttaiConstants.canonicalSensorId(checkingSensorId) -> OttaiCloudBindingUiState.CHECKING
        OttaiConstants.canonicalSensorId(failedSensorId) -> OttaiCloudBindingUiState.ERROR
        OttaiConstants.canonicalSensorId(checkedSensorId) -> {
            if (ottaiActiveCloudUnbindTarget(selected, devices) != null) {
                OttaiCloudBindingUiState.BOUND
            } else {
                OttaiCloudBindingUiState.NOT_BOUND
            }
        }
        else -> OttaiCloudBindingUiState.HIDDEN
    }
}

internal fun ottaiMaterialState(
    materials: OttaiRegistry.DeviceMaterials?,
    recoveredStartMs: Long = 0L,
    activatedLifetimeMs: Long = 0L,
    nowMs: Long = System.currentTimeMillis(),
): OttaiMaterialState {
    if (materials?.authKeys == null) return OttaiMaterialState.MISSING
    if (materials.method.isBlank() || materials.coefficient.isBlank()) return OttaiMaterialState.PARTIAL
    // Prefer the cloud activeTime. When it's absent — a sensor activated in the vendor app, or
    // materials saved/imported before activation — fall back to a start we already recovered over
    // BLE (persisted activeTime/stream anchor) so a reconnect isn't mistaken for a fresh sensor.
    val start = materials.activeTimeMs.takeIf { it > 0L } ?: recoveredStartMs.takeIf { it > 0L }
        ?: return OttaiMaterialState.READY_TO_ACTIVATE
    val preheat = materials.preheatPeriodMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_PREHEAT_PERIOD_MS
    if (preheat > 0L && nowMs < start + preheat) return OttaiMaterialState.WARMING_UP
    // Expire on the real accepted lifetime (extended, e.g. 25d) when we know it, not the rated
    // activeExpire — so a still-streaming extended sensor isn't shown as finished.
    val lifetime = activatedLifetimeMs.takeIf { it > 0L }
        ?: materials.activeExpireTimeMs.takeIf { it > 0L }
        ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
    if (lifetime > 0L && nowMs >= start + lifetime) return OttaiMaterialState.EXPIRED
    return OttaiMaterialState.ACTIVE
}

/** Everything this phone can connect without the cloud, with the state each sensor is in. */
private fun loadOttaiSavedSensors(context: Context): List<OttaiSavedSensor> {
    val managed = OttaiRegistry.persistedRecords(context)
    return OttaiRegistry.savedMaterialRecords(context).mapNotNull { record ->
        val id = OttaiConstants.canonicalSensorId(record.sensorId).ifEmpty { record.sensorId }
        val materials = OttaiRegistry.loadMaterials(context, id)
        if (materials.authKeys == null) return@mapNotNull null
        OttaiSavedSensor(
            record = record,
            managed = managed.any { it.matchesId(id) },
            materials = materials,
            state = ottaiMaterialState(
                materials,
                OttaiRegistry.loadProvisionalActiveTime(context, id),
                OttaiRegistry.loadAcceptedMaxActive(context, id),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OttaiSetupWizard(
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val tag = "OttaiSetupWizard"
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A Syai web JWT alone can validate IDs but cannot decrypt sensor materials. Older builds
    // persisted that partial session, so require both mobile credentials before hiding login.
    val alreadySignedIn = remember {
        OttaiRegistry.loadAccessToken(context).isNotBlank() &&
            OttaiRegistry.loadGlucoseSecretKey(context).isNotBlank()
    }
    var signedIn by remember { mutableStateOf(alreadySignedIn) }
    var step by remember { mutableStateOf(OttaiSetupStep.SEARCH) }
    // Keep the selection on the search page; scroll to it after choosing a sensor.
    var searchScrollRequest by remember { mutableStateOf(0) }
    var focusSelectedSensor by remember { mutableStateOf(false) }
    var signInReturn by remember { mutableStateOf<OttaiSetupStep?>(null) }
    val signInForm = remember { OttaiSignInForm() }

    // The backend of the stored session.
    val accountApiBase = remember(signedIn) { OttaiRegistry.loadApiBase(context) }
    val accountRegion = ottaiRegionForApiBase(accountApiBase)
    // Prefer the login the user typed; fall back to the numeric userId for sessions signed in
    // before the login was persisted.
    val accountLogin = remember(signedIn) {
        OttaiRegistry.loadAccountLogin(context).ifBlank { OttaiRegistry.loadUserId(context) }
    }
    var region by remember { mutableStateOf(if (alreadySignedIn) accountRegion else OttaiRegion.GLOBAL) }
    var accountMessage by remember { mutableStateOf("") }

    var cloudId by remember { mutableStateOf("") }
    var sensorEditPending by remember { mutableStateOf(false) }
    var selectedDeviceVersion by remember { mutableStateOf("") }
    var selectedAccountDevice by remember { mutableStateOf<OttaiCloudClient.DeviceSummary?>(null) }
    var bleAddress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var currentMaterials by remember { mutableStateOf<OttaiRegistry.DeviceMaterials?>(null) }
    var materialLoading by remember { mutableStateOf(false) }
    var credentialBootstrap by remember { mutableStateOf<OttaiBleManager?>(null) }
    var lastAutoFetchId by remember { mutableStateOf("") }
    var materialRefresh by remember { mutableStateOf(0) }
    // The account's sensors (current + past); null = not loaded yet, empty = none.
    var devices by remember { mutableStateOf<List<OttaiCloudClient.DeviceSummary>?>(null) }
    var devicesLoading by remember { mutableStateOf(false) }
    var accountDevicesFailure by remember { mutableStateOf<OttaiCloudClient.CloudFailure?>(null) }
    var cloudBindingCheckingId by remember { mutableStateOf("") }
    var cloudBindingCheckedId by remember { mutableStateOf("") }
    var cloudBindingFailedId by remember { mutableStateOf("") }
    var pendingCloudUnbind by remember { mutableStateOf<OttaiCloudClient.DeviceSummary?>(null) }
    // Locally-saved sensors (imported, fetched, or added) that can connect with no network.
    var savedSensors by remember { mutableStateOf<List<OttaiSavedSensor>>(emptyList()) }
    var savedRefresh by remember { mutableStateOf(0) }
    var connectedAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<OttaiSavedSensor?>(null) }
    var exportTargetId by remember { mutableStateOf("") }
    // Results of one-off acts (export, delete, unbind, a failed import) are said once and go.
    val snackbar = remember { SnackbarHostState() }
    fun announce(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
    var nfcScanRestartKey by remember { mutableStateOf(0) }

    // Ottai China and Syai sensors stay silent until an NFC field wakes them, so the search
    // listens for one the whole time it is open: for that account, or signed out, for the
    // backend picked on the sign-in card. Ottai Global goes without.
    val nfcWakeListening = OttaiConstants.requiresNfcActivationWake(
        if (signedIn) accountApiBase else region.base,
    )
    var nfcWake by remember {
        mutableStateOf(if (OttaiNfc.dumpMode) OttaiNfcWakeState.LISTENING else OttaiNfcWakeState.IDLE)
    }

    fun showStatus(message: String, error: Boolean = false) {
        status = message
        statusIsError = error
    }

    // The backend has rejected the stored session (AuthFailed_TokenInvalid). Every cloud call
    // will keep failing, so stop presenting the account as signed in and say why: before this the
    // wizard kept "Signed in" up while the device list silently stayed empty, and a user who had
    // just signed up read that as email sign-in being broken.
    val invalidateSession: () -> Unit = {
        OttaiCloudClient.clearSession(context)
        signedIn = false
        devices = null
        accountDevicesFailure = null
        cloudBindingCheckingId = ""
        cloudBindingCheckedId = ""
        cloudBindingFailedId = ""
        accountMessage = context.getString(R.string.ottai_session_invalid)
        showStatus(context.getString(R.string.ottai_session_invalid), error = true)
    }

    val refreshAccountDevices: (String) -> Unit = refreshAccountDevices@{ sensorId ->
        val canonical = OttaiConstants.canonicalSensorId(sensorId)
        if (!signedIn || busy || devicesLoading) return@refreshAccountDevices
        cloudBindingCheckingId = canonical
        cloudBindingCheckedId = ""
        cloudBindingFailedId = ""
        accountDevicesFailure = null
        devicesLoading = true
        scope.launch {
            val (list, failure) = withContext(Dispatchers.IO) {
                val value = runCatching { OttaiCloudClient.accountDevices(context) }
                    .onFailure { Log.w(tag, "refresh account sensors: ${it.message}") }
                    .getOrNull()
                value?.devices to value?.failure
            }
            if (list != null && failure == null) {
                devices = list
                cloudBindingCheckedId = canonical
                Log.i(
                    tag,
                    "cloud binding refresh sensor=$canonical rows=${list.size} " +
                        "active=${ottaiActiveCloudUnbindTarget(canonical, list) != null}",
                )
            } else if (failure?.isTokenInvalid == true) {
                Log.w(tag, "cloud binding refresh sensor=$canonical rejected: session invalid")
                invalidateSession()
            } else {
                devices = null
                accountDevicesFailure = failure ?: OttaiCloudClient.CloudFailure("")
                cloudBindingFailedId = canonical
                Log.w(tag, "cloud binding refresh failed sensor=$canonical ${failure?.text.orEmpty()}")
            }
            cloudBindingCheckingId = ""
            devicesLoading = false
        }
    }

    // While signed in, pull the account's sensor list once for the pages that use it.
    LaunchedEffect(signedIn, step) {
        if (signedIn && step in setOf(OttaiSetupStep.SEARCH, OttaiSetupStep.SAVED_SENSORS) &&
            devices == null && !devicesLoading) {
            // The load belongs to the wizard, so leaving a page cannot cancel it and leave
            // devicesLoading stuck while the two account queries are still in flight.
            refreshAccountDevices(cloudId)
        }
    }
    // Refresh the locally-saved list whenever we land on a page that shows it (e.g. after import).
    LaunchedEffect(step, savedRefresh) {
        if (step == OttaiSetupStep.SEARCH || step == OttaiSetupStep.SAVED_SENSORS) {
            savedSensors = withContext(Dispatchers.IO) { loadOttaiSavedSensors(context) }
        }
    }
    // A connected peripheral stops advertising, so the system's GATT list — polled, because the
    // driver reconnects an added sensor within a second of any drop — is the only truthful
    // "connected" for the saved page.
    LaunchedEffect(step) {
        if (step != OttaiSetupStep.SAVED_SENSORS) return@LaunchedEffect
        while (true) {
            connectedAddresses = withContext(Dispatchers.IO) { connectedBleAddresses(context) }
            delay(OTTAI_KNOWN_SENSOR_REFRESH_MS)
        }
    }

    LaunchedEffect(signedIn) {
        if (!signedIn) {
            lastAutoFetchId = ""
            materialLoading = false
            cloudBindingCheckingId = ""
            cloudBindingCheckedId = ""
            cloudBindingFailedId = ""
        }
    }

    LaunchedEffect(cloudId, signedIn, savedRefresh, materialRefresh) {
        val canonical = OttaiConstants.canonicalSensorId(cloudId)
        if (sensorEditPending) {
            // Wait for the complete pasted/typed code before making cloud requests.
            if (OttaiConstants.looksLikeMac(canonical) || OttaiConstants.isBoxSerial(canonical)) delay(400L)
            sensorEditPending = false
        }
        if (OttaiConstants.isBoxSerial(canonical)) {
            currentMaterials = null
            if (signedIn && lastAutoFetchId != canonical) {
                materialLoading = true
                status = ""
                val resolved = withContext(Dispatchers.IO) {
                    OttaiCloudClient.resolveSetupSensorId(context, canonical)
                }
                // This effect is cancelled when selection or sign-in changes. Never apply a
                // completed lookup to another selection, or persist materials under the serial.
                if (OttaiConstants.canonicalSensorId(cloudId) == canonical && signedIn) {
                    materialLoading = false
                    if (resolved.sensorId != null) {
                        cloudId = resolved.sensorId
                        bleAddress = ""
                        selectedAccountDevice = null
                        selectedDeviceVersion = ""
                        lastAutoFetchId = ""
                    } else {
                        lastAutoFetchId = canonical
                        status = ottaiMaterialFailureMessage(context, resolved.failure)
                        if (resolved.failure?.isTokenInvalid == true) invalidateSession()
                    }
                }
            } else {
                materialLoading = false
            }
            return@LaunchedEffect
        }
        if (!OttaiConstants.looksLikeMac(canonical)) {
            currentMaterials = null
            materialLoading = false
            return@LaunchedEffect
        }

        val local = withContext(Dispatchers.IO) {
            OttaiRegistry.loadMaterials(context, canonical).takeIf { it.authKeys != null }
        }
        if (local != null) {
            currentMaterials = local
            if (local.deviceVersion.isNotBlank()) selectedDeviceVersion = local.deviceVersion
            materialLoading = false
            return@LaunchedEffect
        }

        currentMaterials = null
        // A wizard-owned BLE bootstrap survives Activity configuration changes. Its result is
        // persisted, so a recreated composition waits for that same transaction instead of
        // launching a duplicate or showing the retry button while bindV3 is still finishing.
        if (OttaiRegistry.isV3CredentialBootstrapPending(context, canonical)) {
            materialLoading = true
            repeat(180) {
                delay(500L)
                val completed = withContext(Dispatchers.IO) {
                    OttaiRegistry.loadMaterials(context, canonical).takeIf { it.authKeys != null }
                }
                if (completed != null) {
                    currentMaterials = completed
                    if (completed.deviceVersion.isNotBlank()) selectedDeviceVersion = completed.deviceVersion
                    materialLoading = false
                    showStatus("")
                    return@LaunchedEffect
                }
                if (!OttaiRegistry.isV3CredentialBootstrapPending(context, canonical)) {
                    materialLoading = false
                    return@LaunchedEffect
                }
            }
            materialLoading = false
            return@LaunchedEffect
        }
        if (signedIn && lastAutoFetchId != canonical) {
            lastAutoFetchId = canonical
            materialLoading = true
            showStatus("")
            val selected = selectedAccountDevice?.takeIf {
                OttaiConstants.matchesCanonicalOrKnownNativeAlias(it.mac, canonical)
            }
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    fetchOttaiMaterials(
                        context,
                        canonical,
                        selected?.deviceVersion ?: selectedDeviceVersion,
                        selected?.bindTime ?: 0L,
                    )
                }
                    .onFailure { Log.w(tag, "auto-fetch materials: ${it.message}") }
                    .getOrNull()
            }
            if (OttaiConstants.canonicalSensorId(cloudId) == canonical) {
                val materials = fetched?.materials
                currentMaterials = materials
                val fetchedVersion = materials?.deviceVersion?.takeIf { it.isNotBlank() }
                    ?: fetched?.validatedDeviceVersion?.takeIf { it.isNotBlank() }
                if (fetchedVersion != null) selectedDeviceVersion = fetchedVersion
                materialLoading = false
                when {
                    materials != null -> showStatus("")
                    fetched?.requiresV3Bootstrap == true -> showStatus("")
                    else -> showStatus(ottaiMaterialFailureMessage(context, fetched?.failure), error = true)
                }
                if (fetched?.failure?.isTokenInvalid == true) invalidateSession()
                if (materials != null) savedRefresh += 1
            }
        } else {
            materialLoading = false
        }
    }

    // Listening is permanent while the search is open: every wake disarms the reader, so it is
    // re-armed after each one. Keyed on the wake counter, not the shown state: a second wake
    // leaves that at WOKE and would not re-run this.
    // Once nothing here needs a wake (signed in to Ottai Global, or Global picked), setup must
    // let go of NFC: a held capture swallows the next Libre or pen tap.
    LaunchedEffect(step, nfcWakeListening, nfcScanRestartKey) {
        if (!nfcWakeListening) {
            OttaiNfc.disarmSetup()
        } else if (step == OttaiSetupStep.SEARCH) {
            OttaiNfc.armForSetup()
        }
    }

    // Keep one callback for the whole wizard so a wake from any page also restarts BLE
    // discovery. Never leave NFC routing armed after the wizard closes.
    DisposableEffect(Unit) {
        val callback: (OttaiNfc.Result) -> Unit = { result ->
            scope.launch {
                if (result.wakeDetected) {
                    nfcWake = OttaiNfcWakeState.WOKE
                    nfcScanRestartKey += 1
                } else {
                    // A non-wake tag leaves dump mode on: still listening.
                    nfcWake = OttaiNfcWakeState.UNCONFIRMED
                }
            }
        }
        OttaiNfc.onResult = callback
        onDispose {
            // Activity recreation used to cancel a successful Active Auth while bindV3 was in
            // flight. Keep the wizard-owned transaction across configuration changes; an actual
            // navigation away still cancels it.
            if (context.findActivity()?.isChangingConfigurations != true) {
                credentialBootstrap?.cancelV3CredentialBootstrap()
            }
            OttaiNfc.dumpMode = false
            if (OttaiNfc.onResult === callback) OttaiNfc.onResult = null
        }
    }

    fun openSensor() {
        sensorEditPending = false
        focusSelectedSensor = true
        searchScrollRequest += 1
        step = OttaiSetupStep.SEARCH
    }

    fun openSignIn() {
        signInReturn = step.takeIf { it != OttaiSetupStep.SEARCH }
        focusSelectedSensor = false
        searchScrollRequest += 1
        step = OttaiSetupStep.SEARCH
    }

    // Save one sensor's decrypted materials to a file (portable to any device).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val id = exportTargetId
        if (uri != null && id.isNotBlank()) scope.launch {
            val (message, _) = withContext(Dispatchers.IO) {
                val json = OttaiRegistry.exportJson(context, id)
                    ?: return@withContext context.getString(R.string.ottai_save_nothing) to true
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("no output stream")
                }.fold(
                    { context.getString(R.string.ottai_save_ok) to false },
                    {
                        Log.w(tag, "export $id: ${it.message}")
                        context.getString(R.string.ottai_save_failed) to true
                    },
                )
            }
            announce(message)
        }
    }
    // Load materials from a file and select that sensor — no server/login needed on this device.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val id = withContext(Dispatchers.IO) {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull() ?: return@withContext null
                // Keep imported credentials as a draft. The managed record is created only
                // after the user presses Connect.
                OttaiRegistry.importJson(context, json)?.also { sid ->
                    OttaiRegistry.saveDraftRecord(
                        context,
                        sid,
                        OttaiRegistry.findDraftRecord(context, sid)?.address.orEmpty(),
                        OttaiConstants.DEFAULT_DISPLAY_NAME,
                    )
                }
            }
            if (id != null) {
                cloudId = id
                bleAddress = ""
                val imported = withContext(Dispatchers.IO) { OttaiRegistry.loadMaterials(context, id) }
                currentMaterials = imported
                selectedDeviceVersion = imported.deviceVersion
                lastAutoFetchId = ""
                savedRefresh += 1
                showStatus("")
                openSensor()
            } else {
                announce(context.getString(R.string.ottai_import_failed))
            }
        }
    }
    val launchImport = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }

    pendingCloudUnbind?.let { target ->
        val targetId = OttaiConstants.canonicalSensorId(target.mac)
        AlertDialog(
            onDismissRequest = { if (!busy) pendingCloudUnbind = null },
            icon = {
                Icon(
                    Icons.Default.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.ottai_cloud_unbind_title)) },
            text = { Text(stringResource(R.string.ottai_cloud_unbind_message, targetId)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCloudUnbind = null
                        busy = true
                        scope.launch {
                            // Deliberately cloud-only. Do not call OttaiRegistry.removeSensor,
                            // SensorBluetooth, or any BLE/native path from this action.
                            val (released, failure) = withContext(Dispatchers.IO) {
                                runCatching {
                                    OttaiCloudClient.unbindWithResult(context.applicationContext, targetId)
                                }.onFailure {
                                    Log.w(tag, "cloud-only unbind $targetId: ${it.message}")
                                }.getOrDefault(OttaiCloudClient.UnbindResult(false))
                            }
                            busy = false
                            val message = if (released) {
                                val unboundAt = System.currentTimeMillis()
                                devices = devices?.map { device ->
                                    if (OttaiConstants.canonicalSensorId(device.mac) == targetId) {
                                        device.copy(unbindTime = unboundAt, boundToAccount = false)
                                    } else {
                                        device
                                    }
                                }
                                selectedAccountDevice = selectedAccountDevice?.let { selected ->
                                    if (OttaiConstants.canonicalSensorId(selected.mac) == targetId) {
                                        selected.copy(unbindTime = unboundAt, boundToAccount = false)
                                    } else {
                                        selected
                                    }
                                }
                                context.getString(R.string.ottai_cloud_unbind_success)
                            } else if (failure?.isTokenInvalid == true) {
                                invalidateSession()
                                null
                            } else {
                                context.getString(R.string.ottai_cloud_unbind_failed) +
                                    failure?.text?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                            }
                            if (message != null) announce(message)
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.ottai_cloud_unbind_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingCloudUnbind = null },
                    enabled = !busy,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.ottai_delete_title)) },
            text = { Text(stringResource(R.string.ottai_delete_message, target.sensorId)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val id = target.sensorId
                            // forgetCredentials refuses a managed sensor; the page never offers
                            // this for one.
                            withContext(Dispatchers.IO) { OttaiRegistry.forgetCredentials(context, id) }
                            if (OttaiConstants.canonicalSensorId(cloudId) == id) {
                                currentMaterials = null
                                lastAutoFetchId = id
                                materialRefresh += 1
                            }
                            savedRefresh += 1
                            announce(context.getString(R.string.ottai_delete_done, id))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingDelete = null },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    fun startConnect(
        mac: String,
        selectedBleAddress: String?,
        connectAfterCredentialFetch: Boolean,
    ) {
        if (busy || materialLoading) return
        val canonical = OttaiConstants.canonicalSensorId(mac)
        cloudId = canonical
        busy = true
        showStatus("")
        materialLoading = false
        scope.launch {
            // Refresh account binding state for every explicit setup attempt. Local
            // credentials remain the BLE source of truth, but must not suppress the
            // cloud query that refreshes current binding state and the unbind UI.
            if (signedIn) {
                cloudBindingCheckingId = canonical
                cloudBindingCheckedId = ""
                cloudBindingFailedId = ""
                accountDevicesFailure = null
                val (refreshedDevices, bindingFailure) = withContext(Dispatchers.IO) {
                    val value = runCatching { OttaiCloudClient.accountDevices(context) }
                        .onFailure { Log.w(tag, "refresh account sensors: ${it.message}") }
                        .getOrNull()
                    value?.devices to value?.failure
                }
                if (refreshedDevices != null && bindingFailure == null) {
                    devices = refreshedDevices
                    cloudBindingCheckedId = canonical
                    Log.i(
                        tag,
                        "cloud binding refresh sensor=$canonical rows=${refreshedDevices.size} " +
                            "active=${ottaiActiveCloudUnbindTarget(canonical, refreshedDevices) != null}",
                    )
                } else {
                    devices = null
                    accountDevicesFailure = bindingFailure ?: OttaiCloudClient.CloudFailure("")
                    cloudBindingFailedId = canonical
                    Log.w(
                        tag,
                        "cloud binding refresh failed sensor=$canonical " +
                            bindingFailure?.text.orEmpty(),
                    )
                }
                cloudBindingCheckingId = ""
            }
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val selected = selectedAccountDevice?.takeIf {
                        OttaiConstants.matchesCanonicalOrKnownNativeAlias(it.mac, canonical)
                    }
                    fetchOttaiMaterials(
                        context,
                        canonical,
                        selected?.deviceVersion ?: selectedDeviceVersion,
                        selected?.bindTime ?: 0L,
                    )
                }.onFailure { Log.w(tag, "fetch credentials: ${it.message}") }.getOrNull()
            }
            val route = ottaiSetupConnectRoute(
                hasAuthKeys = fetched?.materials?.authKeys != null,
                requiresV3Bootstrap = fetched?.requiresV3Bootstrap == true,
                signedIn = signedIn,
            )
            if (route == OttaiSetupConnectRoute.V3_CREDENTIAL_BOOTSTRAP) {
                val explicitBle = OttaiConstants.normalizeBleAddress(
                    selectedBleAddress,
                    allowPlain = false,
                )
                if (explicitBle == null) {
                    // The V3 handshake reads the sensor over BLE, so it needs the address the
                    // nearby list picked; a typed ID alone cannot start it.
                    busy = false
                    showStatus(context.getString(R.string.ottai_v3_needs_nearby), error = true)
                    return@launch
                }
                materialLoading = true
                credentialBootstrap = OttaiRegistry.startV3CredentialBootstrap(
                    context,
                    canonical,
                    explicitBle,
                ) { materials, failure ->
                    credentialBootstrap = null
                    materialLoading = false
                    if (materials?.authKeys != null) {
                        currentMaterials = materials
                        if (materials.deviceVersion.isNotBlank()) {
                            selectedDeviceVersion = materials.deviceVersion
                        }
                        savedRefresh += 1
                        if (connectAfterCredentialFetch) {
                            // Only the explicit Connect button reaches this path.
                            // Continue with normal authenticated status-gated setup;
                            // a row selection always stops after saving JSON.
                            scope.launch {
                                val connected = withContext(Dispatchers.IO) {
                                    connectOttaiSensor(
                                        context,
                                        canonical,
                                        explicitBle,
                                        activate = false,
                                        route = OttaiSetupConnectRoute.STORED_MATERIALS,
                                    )
                                }
                                busy = false
                                if (connected) {
                                    showStatus("")
                                    step = OttaiSetupStep.CONNECTING
                                } else {
                                    showStatus(context.getString(R.string.ottai_connect_failed), error = true)
                                    nfcScanRestartKey += 1
                                }
                            }
                        } else {
                            busy = false
                            showStatus("")
                        }
                    } else {
                        busy = false
                        showStatus(ottaiMaterialFailureMessage(context, failure), error = true)
                        if (failure?.isTokenInvalid == true) invalidateSession()
                    }
                }
                if (credentialBootstrap == null) {
                    busy = false
                    materialLoading = false
                    showStatus(context.getString(R.string.ottai_connect_failed), error = true)
                }
                return@launch
            }

            val materials = fetched?.materials
            if (route == OttaiSetupConnectRoute.STORED_MATERIALS &&
                materials != null &&
                !connectAfterCredentialFetch
            ) {
                busy = false
                currentMaterials = materials
                if (materials.deviceVersion.isNotBlank()) {
                    selectedDeviceVersion = materials.deviceVersion
                }
                savedRefresh += 1
                showStatus("")
                return@launch
            }
            if (route == OttaiSetupConnectRoute.STORED_MATERIALS && materials != null) {
                val explicitBle = OttaiConstants.normalizeBleAddress(
                    selectedBleAddress,
                    allowPlain = false,
                )
                val fetchedState = withContext(Dispatchers.IO) {
                    ottaiMaterialState(
                        materials,
                        OttaiRegistry.loadProvisionalActiveTime(context, canonical),
                        OttaiRegistry.loadAcceptedMaxActive(context, canonical),
                    )
                }
                val connected = withContext(Dispatchers.IO) {
                    connectOttaiSensor(
                        context,
                        canonical,
                        explicitBle,
                        activate = fetchedState == OttaiMaterialState.READY_TO_ACTIVATE,
                        route = route,
                    )
                }
                busy = false
                if (connected) {
                    currentMaterials = materials
                    if (materials.deviceVersion.isNotBlank()) {
                        selectedDeviceVersion = materials.deviceVersion
                    }
                    savedRefresh += 1
                    step = OttaiSetupStep.CONNECTING
                } else {
                    showStatus(context.getString(R.string.ottai_connect_failed), error = true)
                    nfcScanRestartKey += 1
                }
            } else if (materials != null) {
                busy = false
                // Materials in hand and the connect still refused: that is a local
                // registry failure, so the fetch message's offline routes would
                // not help.
                showStatus(context.getString(R.string.ottai_connect_failed), error = true)
            } else {
                busy = false
                showStatus(ottaiMaterialFailureMessage(context, fetched?.failure), error = true)
                if (fetched?.failure?.isTokenInvalid == true) invalidateSession()
            }
        }
    }

    // Every picker updates the inline sensor card. Only Connect publishes a managed sensor.
    val selectNearby: (String) -> Unit = { address ->
        bleAddress = address
        val id = OttaiConstants.canonicalSensorId(address)
        if (OttaiConstants.looksLikeMac(id)) {
            cloudId = id
            selectedDeviceVersion = ""
            showStatus("")
            val hasLocal = OttaiRegistry.loadMaterials(context, id).authKeys != null
            if (ottaiSetupSelectionFetchesCredentials(hasLocal, signedIn)) {
                lastAutoFetchId = id
                startConnect(id, address, false)
            } else {
                if (signedIn) refreshAccountDevices(id)
                lastAutoFetchId = ""
                materialRefresh += 1
            }
            openSensor()
        }
    }
    // A scanned or typed code: the sensor ID, or a box serial the account resolves to one.
    val selectCode: (String) -> Unit = { id ->
        cloudId = id
        selectedDeviceVersion = ""
        selectedAccountDevice = null
        bleAddress = ""
        cloudBindingCheckingId = ""
        cloudBindingCheckedId = ""
        cloudBindingFailedId = ""
        showStatus("")
        if (OttaiConstants.isBoxSerial(id)) {
            lastAutoFetchId = ""
            materialRefresh += 1
        } else {
            val hasLocal = OttaiRegistry.loadMaterials(context, id).authKeys != null
            if (ottaiSetupSelectionFetchesCredentials(hasLocal, signedIn)) {
                lastAutoFetchId = id
                startConnect(id, OttaiConstants.macWithColons(id), false)
            } else {
                if (signedIn) refreshAccountDevices(id)
                lastAutoFetchId = ""
                materialRefresh += 1
            }
        }
        openSensor()
    }
    val selectSaved: (OttaiSavedSensor) -> Unit = { sensor ->
        cloudId = sensor.sensorId
        if (signedIn) refreshAccountDevices(sensor.sensorId)
        lastAutoFetchId = ""
        materialRefresh += 1
        bleAddress = sensor.record.address
        currentMaterials = sensor.materials
        selectedDeviceVersion = sensor.materials.deviceVersion
        showStatus("")
        openSensor()
    }
    val selectAccountDevice: (OttaiCloudClient.DeviceSummary) -> Unit = { device ->
        val id = OttaiConstants.canonicalSensorId(device.mac)
        cloudId = id
        cloudBindingCheckingId = ""
        cloudBindingCheckedId = id
        cloudBindingFailedId = ""
        selectedDeviceVersion = device.deviceVersion
        selectedAccountDevice = device
        lastAutoFetchId = ""
        materialRefresh += 1
        bleAddress = ""
        currentMaterials = null
        showStatus("")
        openSensor()
    }

    val atRoot = step == OttaiSetupStep.SEARCH
    val goBack: () -> Unit = {
        when (step) {
            OttaiSetupStep.SEARCH -> onDismiss()
            OttaiSetupStep.CONNECTING, OttaiSetupStep.SUCCESS -> openSensor()
            OttaiSetupStep.QR, OttaiSetupStep.SAVED_SENSORS, OttaiSetupStep.REGISTER -> step = OttaiSetupStep.SEARCH
        }
    }
    BackHandler(onBack = goBack)

    val onSignedIn: (String) -> Unit = {
        accountMessage = ""
        devices = null
        lastAutoFetchId = ""
        signedIn = true
        showStatus("")
        // The request outlives its page; only a user still waiting on it is moved on, back to
        // the page that sent them to sign in, or to the search.
        if (step == OttaiSetupStep.SEARCH || step == OttaiSetupStep.REGISTER) {
            step = signInReturn ?: OttaiSetupStep.SEARCH
        }
        signInReturn = null
    }

    val selectedSensor: @Composable () -> Unit = {
        // A start we recovered over BLE on a previous session (persisted, and carried in
        // exported JSON) lets us recognise an already-activated sensor even when the
        // cloud/imported materials carry no activeTime — so the button reads "reconnect",
        // not "start warmup".
        val canonicalSensorId = OttaiConstants.canonicalSensorId(cloudId).takeIf { it.isNotBlank() }
        val recoveredStartMs = canonicalSensorId
            ?.let { OttaiRegistry.loadProvisionalActiveTime(context, it) } ?: 0L
        val activatedLifetimeMs = canonicalSensorId
            ?.let { OttaiRegistry.loadAcceptedMaxActive(context, it) } ?: 0L
        val materialState = ottaiMaterialState(currentMaterials, recoveredStartMs, activatedLifetimeMs)
        val hasSensorCode = OttaiConstants.looksLikeMac(cloudId)
        // A box serial is looked up on the account; Connect retries the lookup.
        val needsSerialLookup = OttaiConstants.isBoxSerial(cloudId)
        val hasMaterials = currentMaterials?.authKeys != null
        val canConnect = (hasSensorCode && (signedIn || hasMaterials)) || (needsSerialLookup && signedIn)
        val cloudBindingState = ottaiCloudBindingUiState(
            signedIn = signedIn,
            selectedSensorId = cloudId,
            checkingSensorId = cloudBindingCheckingId,
            checkedSensorId = cloudBindingCheckedId,
            failedSensorId = cloudBindingFailedId,
            devices = devices,
        )
        val connectTitleRes = if (needsSerialLookup) R.string.search else R.string.ottai_connect_saved

        OttaiSensorCard(
            modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = ui.spacerMedium),
            sensorId = cloudId,
            onSensorIdChange = { entered ->
                val code = OttaiConstants.extractSetupCode(entered)
                    ?.takeIf { OttaiConstants.looksLikeMac(it) || OttaiConstants.isBoxSerial(it) } ?: entered
                if (code != cloudId) {
                    cloudId = code
                    sensorEditPending = true
                    bleAddress = if (OttaiConstants.looksLikeMac(code)) OttaiConstants.macWithColons(code) else ""
                    selectedAccountDevice = null
                    selectedDeviceVersion = ""
                    currentMaterials = null
                    lastAutoFetchId = ""
                    cloudBindingCheckingId = ""
                    cloudBindingCheckedId = ""
                    cloudBindingFailedId = ""
                    materialLoading = OttaiConstants.looksLikeMac(code) || OttaiConstants.isBoxSerial(code)
                    showStatus("")
                }
            },
            state = materialState,
            loading = materialLoading,
            signedIn = signedIn,
            deviceVersion = currentMaterials?.deviceVersion?.takeIf { it.isNotBlank() }
                ?: selectedDeviceVersion,
            bindingState = cloudBindingState,
            busy = busy,
            canConnect = canConnect,
            connectLabel = stringResource(connectTitleRes),
            message = status,
            messageIsError = statusIsError,
            onConnect = {
                if (needsSerialLookup) {
                    lastAutoFetchId = ""
                    materialRefresh += 1
                } else {
                    startConnect(cloudId, bleAddress, true)
                }
            },
            onImport = launchImport,
            onSignIn = { openSignIn() },
            onExport = if (hasMaterials && hasSensorCode) {
                {
                    val id = canonicalSensorId.orEmpty()
                    exportTargetId = id
                    exportLauncher.launch("ottai_${id}.json")
                }
            } else null,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = stringResource(
                    when {
                        step == OttaiSetupStep.SAVED_SENSORS -> R.string.ottai_saved_sensors_title
                        step == OttaiSetupStep.REGISTER -> R.string.ottai_register_title
                        else -> R.string.ottai_setup_title
                    },
                ),
                onNavigateBack = goBack,
                navigationContentDescription = stringResource(
                    if (atRoot) R.string.cancel else R.string.navigate_back,
                ),
            )
        },
    ) { padding ->
        AnimatedContent(targetState = step, modifier = Modifier.padding(padding), label = "OttaiWizard") { s ->
            when (s) {
                OttaiSetupStep.REGISTER -> OttaiRegisterStep(
                    ui = ui,
                    region = region,
                    onRegistered = { onSignedIn(it) },
                    form = signInForm,
                    scope = scope,
                )

                OttaiSetupStep.SAVED_SENSORS -> OttaiSavedSensorsStep(
                    ui = ui,
                    saved = savedSensors,
                    connectedAddresses = connectedAddresses,
                    signedIn = signedIn,
                    accountDevices = devices,
                    accountLoading = devicesLoading,
                    accountFailure = accountDevicesFailure,
                    enabled = !busy && !materialLoading,
                    unbindTarget = { id ->
                        if (signedIn && !devicesLoading) ottaiActiveCloudUnbindTarget(id, devices) else null
                    },
                    onSelectSaved = selectSaved,
                    onExport = { sensor ->
                        exportTargetId = sensor.sensorId
                        exportLauncher.launch("ottai_${sensor.sensorId}.json")
                    },
                    onDelete = { pendingDelete = it },
                    onSelectAccountDevice = selectAccountDevice,
                    onUnbind = { pendingCloudUnbind = it },
                    onImport = launchImport,
                    onRefreshAccount = { refreshAccountDevices(cloudId) },
                    onSignIn = { openSignIn() },
                )

                OttaiSetupStep.SEARCH -> {
                    // A binding nothing here accounts for blocks the next activation
                    // (AppUser_AlreadyBinding), so it is the first thing to sort out.
                    val unusedBindings = remember(devices, signedIn) {
                        if (!signedIn) {
                            emptyList()
                        } else {
                            ottaiUnusedCloudBindings(
                                devices,
                                OttaiRegistry.persistedRecords(context).map { it.sensorId },
                            )
                        }
                    }
                    OttaiSearchStep(
                        ui = ui,
                        restartKey = nfcScanRestartKey,
                        enabled = !busy && !materialLoading,
                        selectedSensor = selectedSensor,
                        scrollRequest = searchScrollRequest,
                        focusSelectedSensor = focusSelectedSensor,
                        onAddressSelected = selectNearby,
                        onScanQr = { step = OttaiSetupStep.QR },
                        header = {
                            OttaiAccountCard(
                                signedIn = signedIn,
                                accountLogin = accountLogin,
                                accountRegion = accountRegion,
                                region = region,
                                onRegionChange = { region = it },
                                message = accountMessage,
                                form = signInForm,
                                onSignedIn = { onSignedIn(it) },
                                onSignOut = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { OttaiCloudClient.logout(context) }
                                        signedIn = false
                                        devices = null
                                        accountMessage = ""
                                        showStatus("")
                                    }
                                },
                                onRegister = { step = OttaiSetupStep.REGISTER },
                                scope = scope,
                                modifier = Modifier.padding(
                                    start = ui.horizontalPadding,
                                    end = ui.horizontalPadding,
                                    top = 8.dp,
                                ),
                            )
                            unusedBindings.forEach { binding ->
                                OttaiUnusedBindingCard(
                                    sensorId = OttaiConstants.canonicalSensorId(binding.mac),
                                    enabled = !busy && !devicesLoading,
                                    onUnbind = { pendingCloudUnbind = binding },
                                    modifier = Modifier.padding(
                                        start = ui.horizontalPadding,
                                        end = ui.horizontalPadding,
                                        top = ui.spacerMedium,
                                    ),
                                )
                            }
                        },
                        nfcHint = if (nfcWakeListening) {
                            {
                                OttaiNfcHintRow(
                                    state = nfcWake,
                                    modifier = Modifier.padding(horizontal = ui.horizontalPadding),
                                )
                            }
                        } else null,
                        footer = {
                            Column(
                                modifier = Modifier.padding(
                                    start = ui.horizontalPadding,
                                    end = ui.horizontalPadding,
                                    top = ui.spacerMedium,
                                    bottom = ui.spacerMedium,
                                ),
                            ) {
                                SettingsItem(
                                    title = stringResource(R.string.ottai_saved_sensors_title),
                                    subtitle = ottaiSavedSensorsSummary(savedSensors.size),
                                    icon = Icons.Default.Key,
                                    iconTint = MaterialTheme.colorScheme.secondary,
                                    position = CardPosition.SINGLE,
                                    onClick = if (!busy) ({ step = OttaiSetupStep.SAVED_SENSORS }) else null,
                                )
                            }
                        },
                    )
                }

                OttaiSetupStep.QR -> OttaiQrStep(
                    ui = ui,
                    onScanned = { raw ->
                        val scanned = OttaiConstants.extractSetupCode(raw)
                            ?.takeIf { OttaiConstants.looksLikeMac(it) || OttaiConstants.isBoxSerial(it) }
                        scanned?.let(selectCode)
                        scanned != null
                    },
                )


                OttaiSetupStep.CONNECTING -> {
                    var connectionStatus by remember(cloudId) {
                        mutableStateOf(context.getString(R.string.connecting_to_sensor_wait))
                    }
                    LaunchedEffect(cloudId) {
                        val canonical = OttaiConstants.canonicalSensorId(cloudId)
                        while (true) {
                            if (currentMaterials?.authKeys == null) {
                                val fetched = withContext(Dispatchers.IO) {
                                    OttaiRegistry.loadMaterials(context, canonical)
                                        .takeIf { it.authKeys != null }
                                }
                                if (fetched != null) {
                                    currentMaterials = fetched
                                    if (fetched.deviceVersion.isNotBlank()) {
                                        selectedDeviceVersion = fetched.deviceVersion
                                    }
                                    savedRefresh += 1
                                    showStatus("")
                                    openSensor()
                                    break
                                }
                            }
                            val manager = SensorBluetooth.gattcallbacks
                                .filterIsInstance<OttaiBleManager>()
                                .firstOrNull { it.matchesManagedSensorId(canonical) }
                            if (manager != null) {
                                connectionStatus = manager.getDetailedBleStatus()
                                    .takeIf { it.isNotBlank() }
                                    ?: context.getString(R.string.connecting_to_sensor_wait)
                                if (manager.isSetupConnectionComplete()) {
                                    step = OttaiSetupStep.SUCCESS
                                    break
                                }
                            }
                            delay(500L)
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SensorSetupConnectingScreen(
                            ui = ui,
                            sensorLabel = cloudId.ifBlank { null },
                            supportingText = connectionStatus,
                        )
                    }
                }

                OttaiSetupStep.SUCCESS -> {
                    LaunchedScreenComplete(onComplete)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SensorSetupSuccessScreen(ui = ui, sensorLabel = cloudId.ifBlank { null })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OttaiSettingsScreen(navController: NavController) {
    OttaiSetupWizard(
        onDismiss = { navController.popBackStack() },
        onComplete = { navController.popBackStack() },
    )
}

/** Setup-panel scanners. Stopped before the driver starts the managed scan. */
private object OttaiSetupScanHold {
    private val open = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<BleDeviceScanner, Boolean>(),
    )

    fun track(scanner: BleDeviceScanner) {
        synchronized(open) { open.add(scanner) }
    }

    fun untrack(scanner: BleDeviceScanner) {
        synchronized(open) { open.remove(scanner) }
    }

    fun stopPanelScans() {
        val scanners = synchronized(open) { open.toList() }
        scanners.forEach { it.stopScan() }
    }
}

/**
 * The search, laid out like every other sensor search here (AiDex, MQ, Anytime): the wavy bar
 * while the radio listens, "Searching for sensors…" with QR scanning, then nearby devices.
 * Account, search, ID entry, the selected sensor and secondary actions share one scrolling list.
 */
@Composable
private fun OttaiSearchStep(
    ui: WizardUiMetrics,
    restartKey: Int,
    enabled: Boolean,
    selectedSensor: (@Composable () -> Unit)?,
    scrollRequest: Int,
    focusSelectedSensor: Boolean,
    onAddressSelected: (String) -> Unit,
    onScanQr: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    nfcHint: (@Composable () -> Unit)?,
    footer: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scanner = rememberBleScanner()
    var scanStats by remember { mutableStateOf<Map<String, OttaiScanStats>>(emptyMap()) }
    var scanActive by remember { mutableStateOf(false) }
    var scanPermissionGranted by remember { mutableStateOf(hasBleScanPermissions(context)) }
    var bluetoothEnabled by remember { mutableStateOf(scanner.isBluetoothEnabled()) }
    var scanRetryKey by remember { mutableStateOf(0) }
    var scanError by remember { mutableStateOf<BleDeviceScanner.ScanStartError?>(null) }
    var requestedPermissionOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        scanPermissionGranted = hasBleScanPermissions(context)
        bluetoothEnabled = scanner.isBluetoothEnabled()
        scanError = null
        scanRetryKey += 1
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        bluetoothEnabled = scanner.isBluetoothEnabled()
        scanError = null
        scanRetryKey += 1
    }

    val requestScanPermission = {
        val required = requiredBleScanPermissions()
        if (required.isEmpty()) {
            scanPermissionGranted = true
            scanRetryKey += 1
        } else {
            permissionLauncher.launch(required)
        }
    }

    LaunchedEffect(Unit) {
        if (!scanPermissionGranted && !requestedPermissionOnce) {
            requestedPermissionOnce = true
            requestScanPermission()
        }
    }

    // Leaving the panel stops the panel scanner and only then starts the managed scan. The stop
    // is done here, not left to the scan effect's own dispose: sibling dispose order is not part
    // of the DisposableEffect contract. stopScan() is idempotent. Retry and NFC do not dispose
    // this effect.
    DisposableEffect(Unit) {
        onDispose {
            scanner.stopScan()
            OttaiSetupScanHold.untrack(scanner)
            if (SensorBluetooth.gattcallbacks.isNotEmpty() && !SensorBluetooth.scanActiveOrPending()) {
                SensorBluetooth.blueone?.scanStarter(0L)
            }
        }
    }

    DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey, restartKey) {
        if (!scanPermissionGranted || !bluetoothEnabled) {
            scanner.stopScan()
            scanStats = emptyMap()
            scanActive = false
            return@DisposableEffect onDispose { scanner.stopScan() }
        }

        scanError = null
        scanActive = true
        // One scanner for the app. A second startScan while the managed one is flagged active
        // spends the platform quota and can be the start the platform drops, while scanStarter
        // keeps skipping because the flag stays set. Stop the managed scan for this window.
        if (SensorBluetooth.scanActiveOrPending()) {
            Log.i(OTTAI_SCAN_LOG, "stopping managed scan before the setup scanner")
            SensorBluetooth.blueone?.stopScan(false)
        }
        Log.i(
            OTTAI_SCAN_LOG,
            "scan start filter=181f managedScan=${SensorBluetooth.scanActiveOrPending()}",
        )
        OttaiSetupScanHold.track(scanner)
        scanner.startScan(
            serviceUuids = listOf(OttaiConstants.SERVICE_CGM),
            onResult = { result ->
                val candidate = ottaiCandidateFromScan(result, assumeCgmService = true) ?: return@startScan
                val previous = scanStats[candidate.address]
                if (previous == null) {
                    Log.i(
                        OTTAI_SCAN_LOG,
                        "scan hit ${candidate.address} rssi=${candidate.rssi} " +
                            "name=${candidate.displayName.ifBlank { "?" }} [${candidate.serviceSummary}]",
                    )
                }
                val totalHits = (previous?.totalHits ?: 0) + 1
                val strongHits = (previous?.strongHits ?: 0) +
                    if (candidate.rssi >= OTTAI_OFFICIAL_RSSI_THRESHOLD) 1 else 0
                val bestRssi = maxOf(previous?.bestRssi ?: candidate.rssi, candidate.rssi)
                val previousCandidate = previous?.candidate
                val mergedCandidate = candidate.copy(
                    displayName = candidate.displayName.ifBlank { previousCandidate?.displayName.orEmpty() },
                    rssi = bestRssi,
                    advertisesAuth = candidate.advertisesAuth || (previousCandidate?.advertisesAuth == true),
                    advertisesCgm = candidate.advertisesCgm || (previousCandidate?.advertisesCgm == true),
                    advertisesDeviceInfo = candidate.advertisesDeviceInfo ||
                        (previousCandidate?.advertisesDeviceInfo == true),
                    nameLooksOttai = candidate.nameLooksOttai || (previousCandidate?.nameLooksOttai == true),
                    nameLooksCgm = candidate.nameLooksCgm || (previousCandidate?.nameLooksCgm == true),
                    serviceSummary = mergeOttaiServiceSummary(previousCandidate?.serviceSummary, candidate.serviceSummary),
                )
                scanStats = scanStats + (
                    candidate.address to OttaiScanStats(
                        candidate = mergedCandidate,
                        totalHits = totalHits,
                        strongHits = strongHits,
                        bestRssi = bestRssi,
                    )
                )
            },
            onError = { error ->
                Log.e(OTTAI_SCAN_LOG, "scan failed $error")
                scanError = error
                scanActive = false
                when (error) {
                    BleDeviceScanner.ScanStartError.NoPermission -> scanPermissionGranted = false
                    BleDeviceScanner.ScanStartError.BluetoothDisabled -> bluetoothEnabled = false
                    else -> Unit
                }
            },
        )
        onDispose {
            scanner.stopScan()
            OttaiSetupScanHold.untrack(scanner)
        }
    }

    // Sensors this app already manages. A connected peripheral stops advertising, and the
    // driver's managed scan reconnects an added sensor within a second of every drop — so the
    // scan comes up empty for exactly the sensors the user is surest are there. They are kept
    // out of this list and live under Saved sensors, pinned below it.
    val knownAddresses = remember(restartKey, scanRetryKey) {
        OttaiRegistry.persistedRecords(context)
            .mapNotNull { it.address.takeIf(String::isNotBlank)?.uppercase() }
            .toSet()
    }
    // Every hit is listed (the scan already asks for the CGM service); likely Ottai first.
    // The official scanner's -70 dBm rule ranks, never hides: an Ottai transmitter worn under
    // clothing routinely sits below it, and hiding it made it look like no sensor at all.
    val devices = scanStats.values
        .filter { it.candidate.address.uppercase() !in knownAddresses }
        .sortedWith(
            compareByDescending<OttaiScanStats> { if (it.isStableOfficialCandidate) 1 else 0 }
                .thenByDescending { if (it.candidate.isLikelyOttai) 1 else 0 }
                .thenByDescending { it.bestRssi },
        )
    val scanBlocked = !scanPermissionGranted || !bluetoothEnabled || scanError != null

    val searchHeader: @Composable ColumnScope.() -> Unit = {
        header()
        Spacer(Modifier.height(ui.spacerMedium))
        // The wave belongs to the search, so it runs right above it, under the account card.
        if (scanActive) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        // QR is the other way to find the sensor, so it sits with the search.
        AdaptiveTrailingRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ui.horizontalPadding),
            // The text button's own padding is the gap.
            spacing = 0.dp,
            trailingContent = {
                TextButton(
                    onClick = onScanQr,
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_qr_button))
                }
            },
        ) {
            Text(stringResource(R.string.ottai_searching_sensors), style = MaterialTheme.typography.titleMedium)
        }
        nfcHint?.invoke()
        if (scanBlocked) {
            Spacer(Modifier.height(ui.spacerMedium))
            Card(
                modifier = Modifier
                    .padding(horizontal = ui.horizontalPadding)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val messageRes = when {
                        !scanPermissionGranted && Build.VERSION.SDK_INT >= 31 -> R.string.turn_on_nearby_devices_permission
                        !scanPermissionGranted -> R.string.turn_on_location_permission
                        !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.bluetooth_is_turned_off
                        else -> R.string.nobluetooth
                    }
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(ui.spacerMedium))
                    val buttonRes = when {
                        !scanPermissionGranted -> R.string.permission
                        !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.enable_bluetooth
                        else -> R.string.search_bluetooth
                    }
                    Button(
                        onClick = {
                            when {
                                !scanPermissionGranted -> requestScanPermission()
                                !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> {
                                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                }
                                else -> {
                                    scanError = null
                                    scanPermissionGranted = hasBleScanPermissions(context)
                                    bluetoothEnabled = scanner.isBluetoothEnabled()
                                    scanRetryKey += 1
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = ui.buttonHeight),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Text(stringResource(buttonRes))
                    }
                }
            }
        }
    }
    val deviceRows: LazyListScope.() -> Unit = {
        items(devices, key = { it.candidate.address }) { stats ->
            val device = stats.candidate
            val supporting = listOfNotNull(
                device.address,
                stringResource(R.string.ottai_rssi_dbm, stats.bestRssi),
                stringResource(R.string.ottai_ble_scan_weak).takeIf { !stats.isStableOfficialCandidate },
            ).joinToString(" · ")
            ListItem(
                headlineContent = { Text(device.displayName.ifBlank { stringResource(R.string.unknown) }) },
                supportingContent = { Text(supporting) },
                leadingContent = { Icon(Icons.Default.Bluetooth, null) },
                modifier = Modifier.clickable(enabled = enabled) { onAddressSelected(device.address) },
            )
            HorizontalDivider()
        }
    }

    OttaiSearchList(
        selectedSensor = selectedSensor,
        scrollRequest = scrollRequest,
        focusSelectedSensor = focusSelectedSensor,
        deviceCount = devices.size,
        header = { Column(Modifier.fillMaxWidth(), content = searchHeader) },
        devices = deviceRows,
        footer = footer,
    )
}

/**
 * Addresses the system currently holds a GATT link to. A connected peripheral stops advertising,
 * so this — not the scan — is the only truthful answer to "where is my added sensor".
 */
@SuppressLint("MissingPermission")
private fun connectedBleAddresses(context: Context): Set<String> {
    if (!hasBleScanPermissions(context)) return emptySet()
    return runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.getConnectedDevices(BluetoothProfile.GATT)
            ?.mapNotNull { it.address?.uppercase() }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())
}

private fun mergeOttaiServiceSummary(previous: String?, current: String): String =
    listOf(previous.orEmpty(), current)
        .flatMap { it.split(' ') }
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .distinct()
        .joinToString(" ")

private fun ottaiCandidateFromScan(result: ScanResult, assumeCgmService: Boolean = false): OttaiScanCandidate? {
    val address = try {
        result.device.address
    } catch (_: SecurityException) {
        null
    } ?: return null

    val record = result.scanRecord
    val recordBytes = record?.bytes
    val services = record?.serviceUuids?.map { it.uuid }.orEmpty()
    val names = linkedSetOf<String>()
    listOf(
        try {
            result.device.name
        } catch (_: SecurityException) {
            null
        },
        record?.deviceName,
        scanRecordLocalName(recordBytes),
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .forEach { names.add(it) }

    val advertisesAuth = services.contains(OttaiConstants.SERVICE_AUTH) ||
        scanRecordAdvertises128BitService(recordBytes, OttaiConstants.SERVICE_AUTH)
    val advertisesCgm = assumeCgmService ||
        services.contains(OttaiConstants.SERVICE_CGM) ||
        scanRecordAdvertises16BitService(recordBytes, 0x181F)
    val advertisesDeviceInfo = services.contains(OttaiConstants.SERVICE_DEVICE_INFO) ||
        scanRecordAdvertises16BitService(recordBytes, 0x180A)
    val nameLooksOttai = names.any { it.contains("ottai", ignoreCase = true) }
    val nameLooksCgm = names.any { it.contains("cgm", ignoreCase = true) }

    val serviceTags = mutableListOf<String>()
    if (advertisesAuth) serviceTags += "AUTH"
    if (advertisesCgm) serviceTags += "CGM"
    if (advertisesDeviceInfo) serviceTags += "DIS"
    val serviceDataCount = record?.serviceData?.size ?: 0
    if (serviceDataCount > 0) serviceTags += "sd=$serviceDataCount"
    val manufacturerCount = record?.manufacturerSpecificData?.size() ?: 0
    if (manufacturerCount > 0) serviceTags += "mfg=$manufacturerCount"
    if (serviceTags.isEmpty() && services.isNotEmpty()) serviceTags += "services=${services.size}"

    return OttaiScanCandidate(
        address = address,
        displayName = names.firstOrNull().orEmpty(),
        rssi = result.rssi,
        advertisesAuth = advertisesAuth,
        advertisesCgm = advertisesCgm,
        advertisesDeviceInfo = advertisesDeviceInfo,
        nameLooksOttai = nameLooksOttai,
        nameLooksCgm = nameLooksCgm,
        serviceSummary = serviceTags.joinToString(" "),
    )
}

private fun scanRecordLocalName(scanRecord: ByteArray?): String? {
    if (scanRecord == null) return null
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x08 || type == 0x09) {
            val start = offset + 2
            if (next > start) {
                return runCatching { String(scanRecord, start, next - start, Charsets.UTF_8) }.getOrNull()
            }
        }
        offset = next
    }
    return null
}

private fun scanRecordAdvertises16BitService(scanRecord: ByteArray?, serviceShortUuid: Int): Boolean {
    if (scanRecord == null) return false
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x02 || type == 0x03) {
            var uuidOffset = offset + 2
            while (uuidOffset + 1 < next) {
                val uuid = (scanRecord[uuidOffset].toInt() and 0xFF) or
                    ((scanRecord[uuidOffset + 1].toInt() and 0xFF) shl 8)
                if (uuid == serviceShortUuid) return true
                uuidOffset += 2
            }
        }
        offset = next
    }
    return false
}

private fun scanRecordAdvertises128BitService(scanRecord: ByteArray?, serviceUuid: UUID): Boolean {
    if (scanRecord == null) return false
    val littleEndian = serviceUuid.toLittleEndianBytes()
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x06 || type == 0x07) {
            var uuidOffset = offset + 2
            while (uuidOffset + 15 < next) {
                var same = true
                for (i in littleEndian.indices) {
                    if (scanRecord[uuidOffset + i] != littleEndian[i]) {
                        same = false
                        break
                    }
                }
                if (same) return true
                uuidOffset += 16
            }
        }
        offset = next
    }
    return false
}

private fun UUID.toLittleEndianBytes(): ByteArray {
    val text = toString().replace("-", "")
    val bigEndian = text.chunked(2).map { it.toInt(16).toByte() }
    return bigEndian.asReversed().toByteArray()
}

@Composable
private fun LaunchedScreenComplete(onComplete: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
        onComplete()
    }
}
