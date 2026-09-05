// JugglucoNG — Ottai Setup Wizard
//
// Ottai setup keeps all protocol decisions in the driver/cloud helpers. The UI
// collects account/materials, scans QR/NFC when useful, and starts the managed
// sensor connection; first-use activation is handled during connect.

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import tk.glucodata.drivers.ottai.OttaiSmsCountry
import tk.glucodata.drivers.ottai.normalizeOttaiPhone
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.findActivity
import tk.glucodata.ui.util.rememberBleScanner
import java.util.UUID

// SENSOR is the main setup surface. ACCOUNT_SENSORS is pick-only: a row fills the
// cloud-id field instead of silently connecting.
private enum class OttaiSetupStep { SENSOR, ACCOUNT_SENSORS, REGISTER, CONNECTING, SUCCESS }

/**
 * Cloud account region. CN is the original phone+SMS app (api.ottai.com); Global (Ottai
 * com.ottai.seas, seas.ottai.com) and syai (ru.syai.com) are username/email + password and
 * share the same API. [usesSms] picks the login form.
 */
private enum class OttaiRegion(
    val labelRes: Int,
    val base: String,
    val usesSms: Boolean,
    val webBase: String?,  // website account API (email login + registration); null = no web account flow
) {
    CN(R.string.ottai_region_cn, OttaiConstants.API_BASE, true, null),
    GLOBAL(R.string.ottai_region_global, OttaiConstants.API_BASE_GLOBAL, false, OttaiConstants.WEB_BASE_OTTAI),
    SYAI(R.string.ottai_region_syai, OttaiConstants.API_BASE_SYAI, false, OttaiConstants.WEB_BASE_SYAI),
}
private const val OTTAI_SCAN_DURATION_MS = 30_000L
private const val OTTAI_OFFICIAL_RSSI_THRESHOLD = -70
/**
 * How often the panel re-reads the managed-sensor list and its live GATT state. The driver
 * reconnects an added sensor within a second of any drop, so "added" flips to "connected"
 * while the panel is open and the list has to follow.
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
    val headline = if (effective?.code.equals(OttaiCloudClient.BIZ_ALREADY_BINDING, ignoreCase = true)) {
        context.getString(R.string.ottai_cloud_already_binding)
    } else {
        context.getString(R.string.ottai_connect_saved_fail)
    }
    val detail = effective?.text.orEmpty().takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    return headline + detail + "\n" + context.getString(R.string.ottai_cloud_offline_routes)
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

/** A sensor this app already manages, with its live link state. */
private data class OttaiKnownSensor(
    val record: OttaiRegistry.SensorRecord,
    val connected: Boolean,
)

private enum class OttaiMaterialState {
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

private fun ottaiMaterialState(
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
    var step by remember { mutableStateOf(OttaiSetupStep.SENSOR) }

    var phone by remember { mutableStateOf("") }
    var smsCountry by remember { mutableStateOf(OttaiSmsCountry.MAINLAND_CHINA) }
    var smsCountryMenuExpanded by remember { mutableStateOf(false) }
    var region by remember {
        mutableStateOf(
            if (alreadySignedIn && OttaiRegistry.loadApiBase(context) == OttaiConstants.API_BASE) {
                OttaiRegion.CN
            } else {
                OttaiRegion.GLOBAL
            },
        )
    }
    var code by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var smsStatus by remember { mutableStateOf("") }
    var smsStatusIsError by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var cloudId by remember { mutableStateOf("") }
    var selectedDeviceVersion by remember { mutableStateOf("") }
    var selectedAccountDevice by remember { mutableStateOf<OttaiCloudClient.DeviceSummary?>(null) }
    var bleAddress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var currentMaterials by remember { mutableStateOf<OttaiRegistry.DeviceMaterials?>(null) }
    var materialLoading by remember { mutableStateOf(false) }
    var credentialBootstrap by remember { mutableStateOf<OttaiBleManager?>(null) }
    var lastAutoFetchId by remember { mutableStateOf("") }
    var materialRefresh by remember { mutableStateOf(0) }
    // The account's sensors (current + past); null = not loaded yet, empty = none.
    var devices by remember { mutableStateOf<List<OttaiCloudClient.DeviceSummary>?>(null) }
    var devicesLoading by remember { mutableStateOf(false) }
    var cloudBindingCheckingId by remember { mutableStateOf("") }
    var cloudBindingCheckedId by remember { mutableStateOf("") }
    var cloudBindingFailedId by remember { mutableStateOf("") }
    var pendingCloudUnbind by remember { mutableStateOf<OttaiCloudClient.DeviceSummary?>(null) }
    // Locally-saved sensors (imported or fetched) that can connect with no network.
    var savedSensors by remember { mutableStateOf<List<OttaiRegistry.SensorRecord>>(emptyList()) }
    var savedRefresh by remember { mutableStateOf(0) }
    var nfcScanRestartKey by remember { mutableStateOf(0) }

    val refreshAccountDevices: (String) -> Unit = refreshAccountDevices@{ sensorId ->
        val canonical = OttaiConstants.canonicalSensorId(sensorId)
        if (!signedIn || !OttaiConstants.looksLikeMac(canonical)) return@refreshAccountDevices
        if (devicesLoading && cloudBindingCheckingId == canonical) return@refreshAccountDevices
        cloudBindingCheckingId = canonical
        cloudBindingCheckedId = ""
        cloudBindingFailedId = ""
        if (devicesLoading) return@refreshAccountDevices
        devicesLoading = true
        scope.launch {
            val (list, failure) = withContext(Dispatchers.IO) {
                val value = runCatching { OttaiCloudClient.listDevices(context) }
                    .onFailure { Log.w(tag, "refresh account sensors: ${it.message}") }
                    .getOrNull()
                value to OttaiCloudClient.lastFailure
            }
            if (list != null && failure == null) {
                devices = list
                cloudBindingCheckedId = canonical
                Log.i(
                    tag,
                    "cloud binding refresh sensor=$canonical rows=${list.size} " +
                        "active=${ottaiActiveCloudUnbindTarget(canonical, list) != null}",
                )
            } else {
                cloudBindingFailedId = canonical
                Log.w(tag, "cloud binding refresh failed sensor=$canonical ${failure?.text.orEmpty()}")
            }
            cloudBindingCheckingId = ""
            devicesLoading = false
        }
    }

    // When signed in and the account picker is relevant, pull the account's sensor list once.
    LaunchedEffect(signedIn, step) {
        if (signedIn && (step == OttaiSetupStep.SENSOR || step == OttaiSetupStep.ACCOUNT_SENSORS) &&
            devices == null && !devicesLoading) {
            devicesLoading = true
            val (list, failure) = withContext(Dispatchers.IO) {
                val value = runCatching { OttaiCloudClient.listDevices(context) }
                    .onFailure { Log.w(tag, "listDevices: ${it.message}") }.getOrNull()
                value to OttaiCloudClient.lastFailure
            }
            if (list != null && failure == null) {
                devices = list
                val pendingId = cloudBindingCheckingId
                if (OttaiConstants.looksLikeMac(pendingId)) {
                    cloudBindingCheckedId = pendingId
                    cloudBindingCheckingId = ""
                    Log.i(
                        tag,
                        "cloud binding refresh sensor=$pendingId rows=${list.size} " +
                            "active=${ottaiActiveCloudUnbindTarget(pendingId, list) != null}",
                    )
                }
            } else if (OttaiConstants.looksLikeMac(cloudBindingCheckingId)) {
                cloudBindingFailedId = cloudBindingCheckingId
                cloudBindingCheckingId = ""
            }
            devicesLoading = false
        }
    }
    // Refresh the locally-saved list whenever we land on the sensor step (e.g. after import).
    LaunchedEffect(step, savedRefresh) {
        if (step == OttaiSetupStep.SENSOR || step == OttaiSetupStep.ACCOUNT_SENSORS) {
            savedSensors = withContext(Dispatchers.IO) {
                OttaiRegistry.savedMaterialRecords(context)
                    .filter { OttaiRegistry.loadMaterials(context, it.sensorId).authKeys != null }
            }
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
                    status = context.getString(R.string.ottai_creds_loaded)
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
            status = ""
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
                status = when {
                    materials != null -> ""
                    fetched?.requiresV3Bootstrap == true -> ""
                    else -> ottaiMaterialFailureMessage(context, fetched?.failure)
                }
            }
        } else {
            materialLoading = false
        }
    }

    LaunchedEffect(step, region, currentMaterials) {
        if (step == OttaiSetupStep.SENSOR &&
            region == OttaiRegion.CN &&
            ottaiMaterialState(currentMaterials) == OttaiMaterialState.READY_TO_ACTIVATE
        ) {
            OttaiNfc.armForSetup()
            status = context.getString(R.string.ottai_nfc_dump_armed)
        }
    }

    // Keep one callback for the whole wizard so an automatically armed CN wake also
    // restarts BLE discovery. Never leave NFC routing armed after the wizard closes.
    DisposableEffect(Unit) {
        val callback: (OttaiNfc.Result) -> Unit = { result ->
            scope.launch {
                savedRefresh += 1
                if (result.wakeDetected) nfcScanRestartKey += 1
                status = if (result.wakeDetected) {
                    context.getString(R.string.ottai_nfc_read_ok)
                } else {
                    context.getString(R.string.ottai_nfc_read_failed)
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

    // Save the decrypted per-sensor materials to a file (portable to any device).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                val id = OttaiConstants.canonicalSensorId(cloudId)
                val json = OttaiRegistry.exportJson(context, id)
                    ?: return@withContext context.getString(R.string.ottai_save_nothing)
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                    .fold({ context.getString(R.string.ottai_save_ok) }, { "Save failed: ${it.message}" })
            }
            status = msg
        }
    }
    // Load materials from a file and connect — no server/login needed on this device.
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
                val imported = withContext(Dispatchers.IO) { OttaiRegistry.loadMaterials(context, id) }
                currentMaterials = imported
                selectedDeviceVersion = imported.deviceVersion
                lastAutoFetchId = ""
                savedRefresh += 1
                step = OttaiSetupStep.SENSOR
                status = ""
            } else {
                status = context.getString(R.string.ottai_import_failed)
            }
        }
    }

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
                            val released = withContext(Dispatchers.IO) {
                                runCatching {
                                    OttaiCloudClient.unbind(context.applicationContext, targetId)
                                }.onFailure {
                                    Log.w(tag, "cloud-only unbind $targetId: ${it.message}")
                                }.getOrDefault(false)
                            }
                            busy = false
                            if (released) {
                                val unboundAt = System.currentTimeMillis()
                                devices = devices?.map { device ->
                                    if (OttaiConstants.canonicalSensorId(device.mac) == targetId) {
                                        device.copy(unbindTime = unboundAt)
                                    } else {
                                        device
                                    }
                                }
                                selectedAccountDevice = selectedAccountDevice?.let { selected ->
                                    if (OttaiConstants.canonicalSensorId(selected.mac) == targetId) {
                                        selected.copy(unbindTime = unboundAt)
                                    } else {
                                        selected
                                    }
                                }
                                status = context.getString(R.string.ottai_cloud_unbind_success)
                            } else {
                                status = context.getString(R.string.ottai_cloud_unbind_failed)
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.ottai_cloud_unbind_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingCloudUnbind = null },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    BackHandler {
        when (step) {
            OttaiSetupStep.SENSOR -> onDismiss()
            OttaiSetupStep.ACCOUNT_SENSORS -> step = OttaiSetupStep.SENSOR
            OttaiSetupStep.REGISTER -> step = OttaiSetupStep.SENSOR
            else -> step = OttaiSetupStep.SENSOR
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ottai_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(targetState = step, modifier = Modifier.padding(padding), label = "OttaiWizard") { s ->
            when (s) {
                OttaiSetupStep.REGISTER -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    Text(stringResource(R.string.ottai_register_title), style = MaterialTheme.typography.titleLarge)
                    var email by remember { mutableStateOf("") }
                    var regCode by remember { mutableStateOf("") }
                    var regPassword by remember { mutableStateOf("") }
                    var profileName by remember { mutableStateOf("") }
                    var regRequestId by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim() },
                        label = { Text(stringResource(R.string.ottai_email_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val rid = withContext(Dispatchers.IO) {
                                    runCatching {
                                        OttaiCloudClient.sendMail(
                                            email.trim(), "SIGN_UP",
                                            region.webBase ?: OttaiConstants.WEB_BASE_OTTAI,
                                        )
                                    }.onFailure { Log.w(tag, "sendMail: ${it.message}") }.getOrNull()
                                }
                                busy = false
                                if (rid.isNullOrBlank()) status = context.getString(R.string.ottai_register_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                else { regRequestId = rid; status = context.getString(R.string.ottai_code_sent_email, email) }
                            }
                        },
                        enabled = !busy && email.contains('@'),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_send_code)) }
                    OutlinedTextField(
                        value = regCode, onValueChange = { regCode = it.trim() },
                        label = { Text(stringResource(R.string.ottai_verify_code_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = regPassword, onValueChange = { regPassword = it },
                        label = { Text(stringResource(R.string.ottai_password_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.ottai_password_rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = profileName, onValueChange = { profileName = it },
                        label = { Text(stringResource(R.string.ottai_profile_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        OttaiCloudClient.signUp(
                                            context, email.trim(), regPassword, profileName.trim(),
                                            regRequestId, regCode.trim(),
                                            region.webBase ?: OttaiConstants.WEB_BASE_OTTAI,
                                        )?.accessToken?.isNotBlank() == true
                                    }.onFailure { Log.w(tag, "signUp: ${it.message}") }.getOrDefault(false)
                                }
                                busy = false
                                if (ok) { signedIn = true; step = OttaiSetupStep.SENSOR }
                                else status = context.getString(R.string.ottai_register_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                            }
                        },
                        enabled = !busy && regRequestId.isNotBlank() && regCode.isNotBlank() &&
                            regPassword.isNotBlank() && profileName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_register_button)) }
                    if (busy) CircularProgressIndicator()
                    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
                }

                OttaiSetupStep.SENSOR -> {
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
                    val hasMaterials = currentMaterials?.authKeys != null
                    val canConnect = hasSensorCode && (signedIn || hasMaterials)
                    val cloudUnbindTarget = ottaiActiveCloudUnbindTarget(cloudId, devices)
                    val cloudBindingState = ottaiCloudBindingUiState(
                        signedIn = signedIn,
                        selectedSensorId = cloudId,
                        checkingSensorId = cloudBindingCheckingId,
                        checkedSensorId = cloudBindingCheckedId,
                        failedSensorId = cloudBindingFailedId,
                        devices = devices,
                    )
                    val connectTitleRes = when (materialState) {
                        OttaiMaterialState.READY_TO_ACTIVATE -> R.string.ottai_connect_activate
                        OttaiMaterialState.EXPIRED -> R.string.ottai_connect_expired
                        else -> R.string.ottai_connect_saved
                    }

                    fun startConnect(
                        mac: String,
                        selectedBleAddress: String?,
                        connectAfterCredentialFetch: Boolean,
                    ) {
                        if (busy || materialLoading) return
                        val canonical = OttaiConstants.canonicalSensorId(mac)
                        cloudId = canonical
                        busy = true; status = ""
                        materialLoading = false
                        scope.launch {
                            // Refresh account binding state for every explicit setup attempt. Local
                            // credentials remain the BLE source of truth, but must not suppress the
                            // cloud query that refreshes current binding state and the unbind UI.
                            if (signedIn) {
                                cloudBindingCheckingId = canonical
                                cloudBindingCheckedId = ""
                                cloudBindingFailedId = ""
                                val (refreshedDevices, bindingFailure) = withContext(Dispatchers.IO) {
                                    val value = runCatching { OttaiCloudClient.listDevices(context) }
                                        .onFailure { Log.w(tag, "refresh account sensors: ${it.message}") }
                                        .getOrNull()
                                    value to OttaiCloudClient.lastFailure
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
                                    busy = false
                                    status = context.getString(R.string.ottai_connect_saved_fail)
                                    return@launch
                                }
                                materialLoading = true
                                status = context.getString(R.string.ottai_materials_loading)
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
                                            // a row/QR selection always stops after saving JSON.
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
                                                    status = context.getString(R.string.ottai_creds_loaded)
                                                    step = OttaiSetupStep.CONNECTING
                                                } else {
                                                    status = context.getString(R.string.ottai_connect_saved_fail)
                                                }
                                            }
                                        } else {
                                            busy = false
                                            status = context.getString(R.string.ottai_creds_loaded)
                                        }
                                    } else {
                                        busy = false
                                        status = ottaiMaterialFailureMessage(context, failure)
                                    }
                                }
                                if (credentialBootstrap == null) {
                                    busy = false
                                    materialLoading = false
                                    status = context.getString(R.string.ottai_connect_saved_fail)
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
                                status = context.getString(R.string.ottai_creds_loaded)
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
                                    status = context.getString(R.string.ottai_connect_saved_fail)
                                }
                            } else if (materials != null) {
                                busy = false
                                // Materials in hand and the connect still refused: that is a local
                                // registry failure, so the fetch message's offline routes would
                                // not help.
                                status = context.getString(R.string.ottai_connect_saved_fail)
                            } else {
                                busy = false
                                status = ottaiMaterialFailureMessage(context, fetched?.failure)
                            }
                        }
                    }

                    val armNfcRead: () -> Unit = {
                        status = context.getString(R.string.ottai_nfc_dump_armed)
                        OttaiNfc.armForSetup()
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(ui.horizontalPadding),
                        verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                    ) {
                        if (signedIn) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.ottai_already_signed_in),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    // Prefer the login the user typed; fall back to the numeric userId
                                    // for sessions signed in before the login was persisted.
                                    val signedInLogin = remember(signedIn) {
                                        OttaiRegistry.loadAccountLogin(context)
                                            .ifBlank { OttaiRegistry.loadUserId(context) }
                                    }
                                    if (signedInLogin.isNotBlank()) Text(
                                        stringResource(R.string.ottai_account_id, signedInLogin),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { OttaiCloudClient.logout(context) }
                                        signedIn = false
                                        devices = null
                                        status = ""
                                    }
                                }) { Text(stringResource(R.string.ottai_sign_out)) }
                            }
                        }

                        OttaiBleScanPanel(
                            ui = ui,
                            selectedAddress = bleAddress,
                            restartKey = nfcScanRestartKey,
                            onAddressSelected = { address ->
                                bleAddress = address
                                val id = OttaiConstants.canonicalSensorId(address)
                                if (OttaiConstants.looksLikeMac(id)) {
                                    cloudId = id
                                    selectedDeviceVersion = ""
                                    val hasLocal = OttaiRegistry.loadMaterials(context, id).authKeys != null
                                    if (ottaiSetupSelectionFetchesCredentials(hasLocal, signedIn)) {
                                        lastAutoFetchId = id
                                        startConnect(id, address, false)
                                    } else {
                                        if (signedIn) refreshAccountDevices(id)
                                        lastAutoFetchId = ""
                                        materialRefresh += 1
                                    }
                                }
                                if (!busy) {
                                    status = context.getString(R.string.ottai_ble_scan_selected, address)
                                }
                            },
                        )

                        if (!signedIn) {
                            Text(stringResource(R.string.ottai_login_title), style = MaterialTheme.typography.titleMedium)
                            ConnectedButtonGroup(
                                options = OttaiRegion.entries.toList(),
                                selectedOption = region,
                                onOptionSelected = {
                                    region = it
                                    status = ""
                                    smsStatus = ""
                                    smsStatusIsError = false
                                },
                                label = { r -> Text(stringResource(r.labelRes)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val useSms = region.usesSms
                            val smsPhone = phone.takeIf { useSms }
                                ?.let { normalizeOttaiPhone(it, smsCountry) }
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { value ->
                                    val next = if (useSms) {
                                        value.filter(Char::isDigit).take(smsCountry.subscriberDigits)
                                    } else value.trim()
                                    if (next != phone && useSms) {
                                        requestId = ""
                                        code = ""
                                        smsStatus = ""
                                        smsStatusIsError = false
                                    }
                                    phone = next
                                },
                                label = { Text(stringResource(if (useSms) R.string.ottai_phone_hint else R.string.ottai_account_hint)) },
                                isError = useSms && phone.isNotBlank() && smsPhone == null,
                                leadingIcon = if (useSms) {
                                    {
                                        Box(
                                            modifier = Modifier
                                                .width(80.dp)
                                                .height(56.dp),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                        onClick = { smsCountryMenuExpanded = true },
                                                    )
                                                    .padding(start = 16.dp, end = 8.dp),
                                            ) {
                                                Text(smsCountry.prefix)
                                                Icon(
                                                    imageVector = Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = smsCountryMenuExpanded,
                                                onDismissRequest = { smsCountryMenuExpanded = false },
                                            ) {
                                                OttaiSmsCountry.entries.forEach { country ->
                                                    DropdownMenuItem(
                                                        text = { Text(country.prefix) },
                                                        onClick = {
                                                            smsCountryMenuExpanded = false
                                                            if (country != smsCountry) {
                                                                smsCountry = country
                                                                phone = ""
                                                                requestId = ""
                                                                code = ""
                                                                smsStatus = ""
                                                                smsStatusIsError = false
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (useSms) KeyboardType.Phone else KeyboardType.Email,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!useSms) Text(
                                stringResource(R.string.ottai_account_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (useSms) {
                                OutlinedButton(
                                    onClick = {
                                        val normalizedPhone = smsPhone ?: return@OutlinedButton
                                        busy = true
                                        smsStatus = ""
                                        smsStatusIsError = false
                                        scope.launch {
                                            val rid = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    OttaiCloudClient.requestSmsCode(
                                                        context,
                                                        normalizedPhone,
                                                        smsCountry.phoneCode,
                                                    )
                                                }
                                                    .onFailure { Log.w(tag, "smsCode: ${it.message}") }.getOrNull()
                                            }
                                            busy = false
                                            if (rid.isNullOrBlank()) {
                                                smsStatusIsError = true
                                                smsStatus = context.getString(R.string.ottai_login_fail) +
                                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }
                                                        ?.let { "\n$it" }.orEmpty()
                                            } else {
                                                requestId = rid
                                                code = ""
                                                smsStatus = context.getString(R.string.ottai_code_sent, rid.takeLast(6))
                                            }
                                        }
                                    },
                                    enabled = !busy && smsPhone != null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ottai_send_code)) }
                                OutlinedTextField(
                                    value = code,
                                    onValueChange = { code = it.trim() },
                                    label = { Text(stringResource(R.string.ottai_code_hint)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (smsStatus.isNotBlank()) {
                                    Text(
                                        text = smsStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (smsStatusIsError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Button(
                                    onClick = {
                                        val normalizedPhone = smsPhone ?: return@Button
                                        busy = true
                                        smsStatus = ""
                                        smsStatusIsError = false
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    OttaiCloudClient.smsLogin(
                                                        context,
                                                        normalizedPhone,
                                                        code,
                                                        requestId,
                                                        smsCountry.phoneCode,
                                                    )?.ok == true
                                                }
                                                    .onFailure { Log.w(tag, "smsLogin: ${it.message}") }.getOrDefault(false)
                                            }
                                            busy = false
                                            if (ok) {
                                                OttaiRegistry.saveAccountLogin(
                                                    context,
                                                    smsCountry.prefix + normalizedPhone,
                                                )
                                                smsStatus = ""
                                                signedIn = true
                                            } else {
                                                smsStatusIsError = true
                                                smsStatus = context.getString(R.string.ottai_login_fail) +
                                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }
                                                        ?.let { "\n$it" }.orEmpty() +
                                                    context.getString(
                                                        R.string.ottai_login_request_suffix,
                                                        requestId.takeLast(6),
                                                    )
                                            }
                                        }
                                    },
                                    enabled = !busy && code.isNotBlank() && requestId.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ottai_login_button)) }
                            } else {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text(stringResource(R.string.ottai_password_hint)) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = {
                                        busy = true; status = ""
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val id = phone.trim()
                                                    val wb = region.webBase
                                                    val r = if (wb != null && id.contains('@'))
                                                        OttaiCloudClient.mailLogin(context, id, password, wb)
                                                    else
                                                        OttaiCloudClient.passwordLogin(context, id, password, region.base)
                                                    r?.ok == true
                                                }.onFailure { Log.w(tag, "passwordLogin: ${it.message}") }.getOrDefault(false)
                                            }
                                            busy = false
                                            if (ok) { OttaiRegistry.saveAccountLogin(context, phone.trim()); signedIn = true }
                                            else status = context.getString(R.string.ottai_login_fail) +
                                                OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                        }
                                    },
                                    enabled = !busy && phone.isNotBlank() && password.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ottai_login_button)) }
                            }

                            if (region.webBase != null && !region.usesSms) {
                                TextButton(
                                    onClick = { status = ""; step = OttaiSetupStep.REGISTER },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ottai_register_cta)) }
                            }
                        }

                        OttaiSensorMaterialCard(
                            cloudId = cloudId,
                            onCloudIdChange = {
                                val next = OttaiConstants.extractMacFromQr(it) ?: it.trim()
                                if (next != cloudId) {
                                    cloudId = next
                                    selectedDeviceVersion = ""
                                    lastAutoFetchId = ""
                                    cloudBindingCheckingId = ""
                                    cloudBindingCheckedId = ""
                                    cloudBindingFailedId = ""
                                }
                            },
                            materials = currentMaterials,
                            state = materialState,
                            loading = materialLoading,
                            enabled = !busy && !materialLoading,
                            onImport = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            onExport = { exportLauncher.launch("ottai_${OttaiConstants.canonicalSensorId(cloudId)}.json") },
                        )



                        InlineQrScannerCard(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            onScanResult = { raw ->
                                OttaiConstants.extractMacFromQr(raw)?.let { id ->
                                    cloudId = id
                                    selectedDeviceVersion = ""
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
                                true
                            },
                        )
                        Button(
                            onClick = { startConnect(cloudId, bleAddress, true) },
                            enabled = !busy && !materialLoading && canConnect,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(connectTitleRes))
                        }
                        HorizontalDivider()
                        if (busy) CircularProgressIndicator()
                        if (status.isNotBlank()) Text(status)

                        if (signedIn) {
                            SettingsItem(
                                title = stringResource(R.string.ottai_account_sensors_title),
                                subtitle = null,
                                showArrow = true,
                                icon = Icons.Default.Cloud,
                                iconTint = MaterialTheme.colorScheme.primary,
                                onClick = if (!busy) ({ step = OttaiSetupStep.ACCOUNT_SENSORS }) else null,
                            )
                        }

                        OutlinedButton(
                            onClick = armNfcRead,
                            enabled = !busy && !materialLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Nfc, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ottai_nfc_dump))
                        }

                        if (cloudBindingState != OttaiCloudBindingUiState.HIDDEN) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (cloudBindingState == OttaiCloudBindingUiState.CHECKING) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Default.Cloud,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (cloudBindingState == OttaiCloudBindingUiState.ERROR) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                                Text(
                                    stringResource(
                                        when (cloudBindingState) {
                                            OttaiCloudBindingUiState.CHECKING -> R.string.ottai_cloud_binding_checking
                                            OttaiCloudBindingUiState.BOUND -> R.string.ottai_cloud_binding_bound
                                            OttaiCloudBindingUiState.NOT_BOUND -> R.string.ottai_cloud_binding_not_bound
                                            OttaiCloudBindingUiState.ERROR -> R.string.ottai_cloud_binding_error
                                            OttaiCloudBindingUiState.HIDDEN -> error("hidden binding state is not rendered")
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (cloudBindingState == OttaiCloudBindingUiState.BOUND && cloudUnbindTarget != null) {
                                Text(
                                    stringResource(R.string.ottai_cloud_unbind_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { pendingCloudUnbind = cloudUnbindTarget },
                                    enabled = !busy && !materialLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Default.LinkOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.unbind_sensor))
                                }
                            }
                        }
                    }
                }

                OttaiSetupStep.ACCOUNT_SENSORS -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    Text(stringResource(R.string.ottai_account_sensors_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.ottai_account_sensors_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val savedIds = savedSensors.map { it.sensorId }.toSet()
                    if (savedSensors.isNotEmpty()) {
                        Text(stringResource(R.string.ottai_saved_credentials_title), style = MaterialTheme.typography.titleMedium)
                        savedSensors.forEach { rec ->
                            OttaiSensorRow(
                                title = rec.displayName.ifBlank { OttaiConstants.macWithColons(rec.sensorId) },
                                subtitle = stringResource(R.string.ottai_sensor_saved) + " · " + rec.sensorId,
                                active = true,
                                enabled = !busy,
                                onClick = {
                                    cloudId = rec.sensorId
                                    if (signedIn) refreshAccountDevices(rec.sensorId)
                                    lastAutoFetchId = ""
                                    materialRefresh += 1
                                    bleAddress = rec.address
                                    currentMaterials = OttaiRegistry.loadMaterials(context, rec.sensorId)
                                    selectedDeviceVersion = currentMaterials?.deviceVersion.orEmpty()
                                    status = context.getString(R.string.ottai_selected_sensor, rec.sensorId)
                                    step = OttaiSetupStep.SENSOR
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (signedIn) {
                        if (devicesLoading) Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.ottai_sensors_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                        (devices ?: emptyList())
                            .filter { OttaiConstants.canonicalSensorId(it.mac) !in savedIds }
                            .forEach { d ->
                                val cid = OttaiConstants.canonicalSensorId(d.mac)
                                OttaiSensorRow(
                                    title = d.serialNo.ifBlank { OttaiConstants.macWithColons(cid) },
                                    subtitle = stringResource(
                                        if (d.isActive) R.string.ottai_sensor_active else R.string.ottai_sensor_past,
                                    ) + " · " + cid,
                                    active = d.isActive,
                                    enabled = !busy,
                                    onClick = {
                                        cloudId = cid
                                        cloudBindingCheckingId = ""
                                        cloudBindingCheckedId = cid
                                        cloudBindingFailedId = ""
                                        selectedDeviceVersion = d.deviceVersion
                                        selectedAccountDevice = d
                                        lastAutoFetchId = ""
                                        materialRefresh += 1
                                        bleAddress = ""
                                        currentMaterials = null
                                        status = context.getString(R.string.ottai_selected_sensor, cid)
                                        step = OttaiSetupStep.SENSOR
                                    },
                                )
                                HorizontalDivider()
                            }
                        if (!devicesLoading && (devices ?: emptyList()).isEmpty()) {
                            Text(
                                stringResource(R.string.ottai_no_account_sensors),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.ottai_account_sensors_need_login),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

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
                                    status = context.getString(R.string.ottai_creds_loaded)
                                    step = OttaiSetupStep.SENSOR
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

@Composable
private fun OttaiSensorRow(
    title: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun OttaiSensorMaterialCard(
    cloudId: String,
    onCloudIdChange: (String) -> Unit,
    materials: OttaiRegistry.DeviceMaterials?,
    state: OttaiMaterialState,
    loading: Boolean,
    enabled: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val titleRes = when (state) {
        OttaiMaterialState.MISSING -> R.string.ottai_materials_missing_title
        OttaiMaterialState.PARTIAL -> R.string.ottai_materials_partial_title
        OttaiMaterialState.READY_TO_ACTIVATE -> R.string.ottai_state_not_activated
        OttaiMaterialState.WARMING_UP -> R.string.ottai_state_warmup
        OttaiMaterialState.ACTIVE -> R.string.ottai_state_active
        OttaiMaterialState.EXPIRED -> R.string.ottai_state_expired
    }
    val ready = materials?.authKeys != null
    val canonical = OttaiConstants.canonicalSensorId(cloudId)
    val canExport = ready && OttaiConstants.looksLikeMac(canonical)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = cloudId,
                onValueChange = onCloudIdChange,
                label = { Text(stringResource(R.string.ottai_sensor_mac_hint)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.ottai_materials_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            if (canonical.isNotBlank() || materials != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!materials?.deviceVersion.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.ottai_device_version_label, materials?.deviceVersion.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

//
            if (ready) {
                Text(stringResource(R.string.ottai_import_export_title), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onExport,
                        enabled = enabled && canExport,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.export))
                    }
                    OutlinedButton(
                        onClick = onImport,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ottai_credentials_replace))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onImport,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ottai_credentials_import))
                }
            }
        }
    }
}

@Composable
private fun OttaiBleScanPanel(
    ui: WizardUiMetrics,
    selectedAddress: String,
    restartKey: Int,
    onAddressSelected: (String) -> Unit,
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

    DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey, restartKey) {
        if (!scanPermissionGranted || !bluetoothEnabled) {
            scanner.stopScan()
            scanStats = emptyMap()
            scanActive = false
            return@DisposableEffect onDispose { scanner.stopScan() }
        }

        scanError = null
        scanActive = true
        // The managed scan owns the same five startScan() calls per 30s that the platform allows
        // the whole app, and it reconnects an added sensor the instant it advertises. Both are
        // reasons this panel can legitimately see nothing; record them so a trace can say which.
        Log.i(
            OTTAI_SCAN_LOG,
            "scan start filter=181f duration=${OTTAI_SCAN_DURATION_MS}ms " +
                "managedScan=${SensorBluetooth.scanActiveOrPending()}",
        )
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
        onDispose { scanner.stopScan() }
    }

    LaunchedEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey, restartKey) {
        if (scanPermissionGranted && bluetoothEnabled) {
            delay(OTTAI_SCAN_DURATION_MS)
            scanActive = false
            scanner.stopScan()
            Log.i(
                OTTAI_SCAN_LOG,
                "scan done addresses=${scanStats.size} " +
                    "stable=${scanStats.values.count { it.isStableOfficialCandidate }}",
            )
        }
    }

    // Sensors this app already manages. A connected peripheral stops advertising, and the
    // driver's managed scan reconnects an added sensor within a second of every drop — so the
    // scan below is guaranteed to come up empty for exactly the sensors the user is surest are
    // there. Poll the registry and the live GATT list instead of reading them once: an address
    // added or connected while this panel is open has to appear without a wizard restart.
    var knownRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(OTTAI_KNOWN_SENSOR_REFRESH_MS)
            knownRefresh += 1
        }
    }
    val knownSensors = remember(restartKey, scanRetryKey, knownRefresh) {
        val connected = connectedBleAddresses(context)
        OttaiRegistry.persistedRecords(context)
            .filter { it.address.isNotBlank() }
            .map { OttaiKnownSensor(it, it.address.uppercase() in connected) }
    }
    val knownAddresses = knownSensors.map { it.record.address.uppercase() }.toSet()

    val scanOrder = compareByDescending<OttaiScanStats> { if (it.candidate.isLikelyOttai) 1 else 0 }
        .thenByDescending { if (it.candidate.isPossibleCgm) 1 else 0 }
        .thenByDescending { it.bestRssi }
    val unknownHits = scanStats.values.filter { it.candidate.address.uppercase() !in knownAddresses }
    val stableDevices = unknownHits
        .filter { it.isStableOfficialCandidate }
        .sortedWith(scanOrder)
        .take(8)
    // Advertisers we really did see but the official -70 dBm rule rejects. Hiding them outright
    // made a sensor under a sleeve indistinguishable from no sensor at all; demote instead.
    val weakDevices = unknownHits
        .filterNot { it.isStableOfficialCandidate }
        .sortedWith(scanOrder)
        .take(4)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (scanActive) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ottai_ble_scan_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = {
                    scanStats = emptyMap()
                    scanError = null
                    scanPermissionGranted = hasBleScanPermissions(context)
                    bluetoothEnabled = scanner.isBluetoothEnabled()
                    scanRetryKey += 1
                },
            ) {
                Text(stringResource(R.string.search_bluetooth))
            }
        }
        if (!scanPermissionGranted || !bluetoothEnabled || scanError != null) {
            val messageRes = when {
                !scanPermissionGranted && Build.VERSION.SDK_INT >= 31 -> R.string.turn_on_nearby_devices_permission
                !scanPermissionGranted -> R.string.turn_on_location_permission
                !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.bluetooth_is_turned_off
                else -> R.string.nobluetooth
            }
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                modifier = Modifier.height(ui.buttonHeight),
            ) {
                val buttonRes = when {
                    !scanPermissionGranted -> R.string.permission
                    !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.enable_bluetooth
                    else -> R.string.search_bluetooth
                }
                Text(stringResource(buttonRes))
            }
        } else {
            // Sensors the app already owns come first: they are the ones a user is looking for,
            // and the scan structurally cannot return them.
            if (knownSensors.isNotEmpty()) {
                Text(
                    stringResource(R.string.ottai_ble_scan_registered_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    knownSensors.forEach { known ->
                        val record = known.record
                        val selected = selectedAddress.equals(record.address, ignoreCase = true)
                        val stateRes = if (known.connected) {
                            R.string.ottai_ble_scan_connected
                        } else {
                            R.string.ottai_ble_scan_registered
                        }
                        ListItem(
                            headlineContent = {
                                Text(record.displayName.ifBlank { record.sensorId })
                            },
                            supportingContent = {
                                Text("${stringResource(stateRes)} · ${record.address}")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = if (selected || known.connected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            modifier = Modifier.clickable { onAddressSelected(record.address) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (stableDevices.isNotEmpty()) {
                Column {
                    stableDevices.forEach { stats ->
                        OttaiScanResultRow(
                            stats = stats,
                            weak = false,
                            selected = selectedAddress.equals(stats.candidate.address, ignoreCase = true),
                            onClick = { onAddressSelected(stats.candidate.address) },
                        )
                    }
                }
            }

            if (weakDevices.isNotEmpty()) {
                Text(
                    stringResource(R.string.ottai_ble_scan_weak_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    weakDevices.forEach { stats ->
                        OttaiScanResultRow(
                            stats = stats,
                            weak = true,
                            selected = selectedAddress.equals(stats.candidate.address, ignoreCase = true),
                            onClick = { onAddressSelected(stats.candidate.address) },
                        )
                    }
                }
            }

            if (stableDevices.isEmpty() && weakDevices.isEmpty()) {
                Text(
                    stringResource(
                        if (scanActive) R.string.looking_for_transmitters else R.string.ottai_ble_scan_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OttaiScanResultRow(
    stats: OttaiScanStats,
    weak: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val device = stats.candidate
    val labelRes = when {
        weak -> R.string.ottai_ble_scan_weak
        device.isLikelyOttai -> R.string.ottai_ble_scan_likely
        device.isPossibleCgm -> R.string.ottai_ble_scan_possible
        else -> R.string.ottai_ble_scan_unknown
    }
    ListItem(
        headlineContent = {
            Text(device.displayName.ifBlank { stringResource(R.string.unknown) })
        },
        supportingContent = {
            Text("${stringResource(labelRes)} · ${device.address}")
        },
        leadingContent = {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = {
            Text("${device.rssi} dBm", style = MaterialTheme.typography.labelMedium)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
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
