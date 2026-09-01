package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.NightscoutTokenGrant
import tk.glucodata.R
import tk.glucodata.data.journal.JournalSyncFailure
import tk.glucodata.data.journal.JournalSyncStatus
import tk.glucodata.data.journal.JournalTreatmentUploader
import tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.ConnectedButtonGroup

private enum class NightscoutMode { UPLOAD, FOLLOW }

// Sealed result for test connection so we don't parse strings
private sealed class TestState {
    object Idle : TestState()
    object Testing : TestState()
    data class Ok(val code: Int) : TestState()
    data class Err(val message: String) : TestState()
}

/**
 * Outcome of a forced token exchange. The permissions are shown because they are the point:
 * a role fixed on the server is only in effect once a token carries it.
 */
private sealed class TokenState {
    object Idle : TokenState()
    object Refreshing : TokenState()
    data class Ok(val expiresAtMillis: Long, val permissions: List<String>) : TokenState()
    data class Err(val message: String) : TokenState()
}

private val SHA1_SECRET_REGEX = Regex("^[0-9a-fA-F]{40}$")

/** "HTTP 403" reads the same in every locale; a request that never got an answer does not. */
private fun failureDetail(context: android.content.Context, code: Int): String =
    if (code > 0) {
        "HTTP $code"
    } else {
        context.getString(R.string.nightscout_status_treatments_no_response)
    }
/**
 * The test must probe the same API family the uploader is configured for: servers that
 * keep the two auth schemes apart refuse a v3 Bearer token on a v1 endpoint, which makes
 * a correctly working v3 setup look broken.
 */
internal fun nightscoutTestEndpointPath(useV3: Boolean): String =
    if (useV3) "api/v3/status" else "api/v1/status.json"

internal fun nightscoutResponseDetail(body: String): String {
    return NightscoutTokenGrant.refusalMessage(body)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(240)
}

private fun applyNightscoutTestAuth(
    connection: java.net.HttpURLConnection,
    baseUrl: String,
    secret: String,
    useV3: Boolean
) {
    val trimmed = secret.trim()
    if (trimmed.isEmpty()) return
    val alreadyBearer = trimmed.startsWith("Bearer ", ignoreCase = true) ||
        trimmed.startsWith("token=", ignoreCase = true)
    if (useV3 && !alreadyBearer && !SHA1_SECRET_REGEX.matches(trimmed)) {
        val encodedSecret = java.net.URLEncoder.encode(trimmed, Charsets.UTF_8.name())
        val tokenConnection = (java.net.URL("$baseUrl/api/v2/authorization/request/$encodedSecret").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (tokenConnection.responseCode in 200..299) {
                val body = tokenConnection.inputStream.bufferedReader().use { it.readText() }
                val token = org.json.JSONObject(body).optString("token")
                if (token.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    return
                }
            }
        } catch (_: Exception) {
            // Fall back to the v1/follower auth header below.
        } finally {
            tokenConnection.disconnect()
        }
    }
    NightscoutFollowerRegistry.applyAuth(connection, trimmed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightscoutSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(Natives.getnightuploadurl() ?: "") }
    var secret by rememberSaveable { mutableStateOf(Natives.getnightuploadsecret() ?: "") }
    val initialUploaderActive = remember { Natives.getuseuploader() }
    val followerConfig = remember { NightscoutFollowerRegistry.loadConfig(context) }
    var isActive by rememberSaveable { mutableStateOf(initialUploaderActive || followerConfig.enabled) }
    var sendTreatments by rememberSaveable { mutableStateOf(Natives.getpostTreatments()) }
    var sendLongInsulin by rememberSaveable { mutableStateOf(JournalTreatmentUploader.getSendLongInsulin()) }
    var receiveTreatments by rememberSaveable { mutableStateOf(JournalTreatmentUploader.getReceiveTreatments()) }
    var uploadIob by rememberSaveable { mutableStateOf(NightPost.getUploadIob()) }
    var isV3 by rememberSaveable { mutableStateOf(Natives.getnightscoutV3()) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var lastResponseCode by rememberSaveable { mutableStateOf(0) }
    var lastResponseBody by rememberSaveable { mutableStateOf("") }
    var lastAttemptTime by rememberSaveable { mutableStateOf(0L) }
    var lastSuccessTime by rememberSaveable { mutableStateOf(0L) }
    var retryMinutes by rememberSaveable { mutableStateOf(0) }
    var uploaderRunning by rememberSaveable { mutableStateOf(false) }
    var treatmentSync by remember { mutableStateOf(JournalSyncStatus.state()) }
    var deviceStatusOutcome by rememberSaveable { mutableStateOf(NightPost.DEVICE_STATUS_NONE) }
    var deviceStatusCode by rememberSaveable { mutableStateOf(0) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var tokenState by remember { mutableStateOf<TokenState>(TokenState.Idle) }

    var mode by rememberSaveable {
        mutableStateOf(
            when {
                initialUploaderActive -> NightscoutMode.UPLOAD
                followerConfig.enabled -> NightscoutMode.FOLLOW
                else -> NightscoutMode.UPLOAD
            }
        )
    }

    fun persistSettings(connectFollower: Boolean = false) {
        val uploadActive = isActive && mode == NightscoutMode.UPLOAD
        val followActive = isActive && mode == NightscoutMode.FOLLOW
        val normalizedUrl = NightscoutFollowerRegistry.normalizeUrl(url)

        Natives.setNightUploader(url.trim(), secret.trim(), uploadActive, isV3)
        // The old device-status failure describes the old settings; keep it off the screen
        // until the next attempt has run under the new ones.
        NightPost.clearDeviceStatusOutcome()
        deviceStatusOutcome = NightPost.DEVICE_STATUS_NONE
        Natives.setpostTreatments(sendTreatments)
        JournalTreatmentUploader.setSendLongInsulin(sendLongInsulin)
        JournalTreatmentUploader.setReceiveTreatments(receiveTreatments)
        if (followActive) {
            if (normalizedUrl.isBlank()) {
                NightscoutFollowerRegistry.saveConfig(context, enabled = true, url = normalizedUrl, secret = secret)
            } else if (connectFollower) {
                NightscoutFollowerRegistry.enableFollowerSensor(context, normalizedUrl, secret)
            } else {
                NightscoutFollowerRegistry.saveConfig(context, enabled = true, url = normalizedUrl, secret = secret)
            }
        } else {
            if (NightscoutFollowerRegistry.loadConfig(context).enabled) {
                NightscoutFollowerRegistry.disableFollowerSensor(context)
            }
            NightscoutFollowerRegistry.saveConfig(context, enabled = false, url = normalizedUrl, secret = secret)
        }
    }

    fun refreshStatus() {
        lastResponseCode = Natives.getnightscoutlastresponsecode()
        lastResponseBody = NightPost.getLastNativeUploadResponseBody()
        lastAttemptTime = Natives.getnightscoutlastattempttime()
        lastSuccessTime = Natives.getnightscoutlastsuccesstime()
        retryMinutes = Natives.getnightscoutretryminutes()
        uploaderRunning = Natives.getnightscoutuploaderrunning()
        treatmentSync = JournalSyncStatus.state()
        deviceStatusOutcome = NightPost.getDeviceStatusOutcome()
        deviceStatusCode = NightPost.getDeviceStatusLastCode()
    }

    fun requireUrl(): Boolean {
        if (NightscoutFollowerRegistry.normalizeUrl(url).isBlank()) {
            Toast.makeText(context, context.getString(R.string.nightscout_follow_url_required), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    fun testConnection() {
        if (!requireUrl()) return
        testState = TestState.Testing
        coroutineScope.launch {
            testState = withContext(Dispatchers.IO) {
                try {
                    val baseUrl = NightscoutFollowerRegistry.normalizeUrl(url)
                    val path = nightscoutTestEndpointPath(isV3)
                    val conn = (java.net.URL("$baseUrl/$path").openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                    }
                    applyNightscoutTestAuth(conn, baseUrl, secret, isV3)
                    val code = conn.responseCode
                    conn.disconnect()
                    // A bare status code sends people debugging the server; the endpoint says
                    // which API family answered.
                    if (code in 200..299) TestState.Ok(code) else TestState.Err("HTTP $code ($path)")
                } catch (e: Exception) {
                    TestState.Err(e.localizedMessage?.take(80) ?: context.getString(R.string.status_connection_failed))
                }
            }
        }
    }

    fun refreshToken() {
        if (!requireUrl()) return
        tokenState = TokenState.Refreshing
        // The exchange reads the stored URL and access token, so what is on screen has to be
        // stored first -- the same as "Send now" does.
        persistSettings()
        coroutineScope.launch {
            tokenState = withContext(Dispatchers.IO) {
                try {
                    val exchange = NightPost.refreshToken()
                    val grant = exchange.grant()
                    if (grant != null) {
                        TokenState.Ok(grant.expiresAtMillis, grant.permissions)
                    } else {
                        TokenState.Err(exchange.error().ifBlank { context.getString(R.string.status_connection_failed) })
                    }
                } catch (e: Exception) {
                    TokenState.Err(e.localizedMessage?.take(80) ?: context.getString(R.string.status_connection_failed))
                }
            }
        }
    }

    // Status polling stops when the screen leaves the foreground; composition alone would
    // otherwise keep it hitting the uploader status every 5s in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isActive, mode, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refreshStatus()
                delay(if (isActive && mode == NightscoutMode.UPLOAD) 5_000L else 15_000L)
            }
        }
    }

    DisposableEffect(Unit) {
        refreshStatus()
        onDispose { persistSettings(connectFollower = isActive && mode == NightscoutMode.FOLLOW) }
    }

    fun formatStatusTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return context.getString(R.string.nightscout_status_never)
        return java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
            java.util.Locale.getDefault()
        ).format(java.util.Date(epochSeconds * 1000L))
    }

    // The native uploader reports seconds; the journal sync status is in millis.
    fun formatStatusMillis(epochMillis: Long): String = formatStatusTime(epochMillis / 1000L)

    val uploaderSummary = when {
        !isActive || mode != NightscoutMode.UPLOAD -> context.getString(R.string.nightscout_status_paused)
        uploaderRunning -> context.getString(R.string.nightscout_status_running)
        retryMinutes > 0 -> context.getString(R.string.nightscout_status_retry_in, retryMinutes)
        else -> context.getString(R.string.nightscout_status_waiting)
    }
    val responseSummary = when {
        !isActive || mode != NightscoutMode.UPLOAD -> context.getString(R.string.nightscout_status_paused)
        lastResponseCode == 0 && lastAttemptTime <= 0L -> context.getString(R.string.nightscout_status_waiting)
        lastResponseCode == -2 -> context.getString(R.string.nightscout_status_response_invalid_url)
        lastResponseCode in 200..299 -> context.getString(R.string.nightscout_status_response_ok, lastResponseCode)
        lastResponseCode == 404 -> context.getString(R.string.nightscout_status_response_404)
        lastResponseCode == 413 -> context.getString(R.string.nightscout_status_response_413)
        lastResponseCode > 0 -> {
            val detail = nightscoutResponseDetail(lastResponseBody)
            if (detail.isBlank()) {
                context.getString(R.string.nightscout_status_response_error, lastResponseCode)
            } else {
                context.getString(
                    R.string.nightscout_status_response_error_detail,
                    lastResponseCode,
                    detail
                )
            }
        }
        else -> context.getString(R.string.nightscout_status_waiting)
    }

    // The lines above report the native entries uploader only, so they can read HTTP 200 while
    // the JVM-side treatment path has been rejected for days (issue #191).
    val treatmentSummary: String? = when {
        !isActive || mode != NightscoutMode.UPLOAD || !sendTreatments -> null
        treatmentSync.failure == JournalSyncFailure.UPLOAD -> context.getString(
            R.string.nightscout_status_treatments_upload_failing,
            formatStatusMillis(treatmentSync.failingSince),
            failureDetail(context, treatmentSync.failureCode),
            formatStatusMillis(treatmentSync.lastAttemptAt)
        )
        treatmentSync.failure == JournalSyncFailure.DELETE -> context.getString(
            R.string.nightscout_status_treatments_delete_failing,
            formatStatusMillis(treatmentSync.failingSince),
            failureDetail(context, treatmentSync.failureCode),
            formatStatusMillis(treatmentSync.lastAttemptAt)
        )
        treatmentSync.lastSuccessAt > 0L -> context.getString(
            R.string.nightscout_status_treatments_ok,
            formatStatusMillis(treatmentSync.lastSuccessAt)
        )
        else -> context.getString(R.string.nightscout_status_treatments_never)
    }

    // The lines above report the native entries uploader only; the devicestatus channel
    // (IOB) fails on its own, and a "no token" skip must not read like a server rejection.
    val deviceStatusSummary: String? = when {
        !isActive || mode != NightscoutMode.UPLOAD || !uploadIob -> null
        deviceStatusOutcome == NightPost.DEVICE_STATUS_NO_TOKEN ->
            context.getString(R.string.nightscout_status_iob_no_token)
        deviceStatusOutcome == NightPost.DEVICE_STATUS_REJECTED -> context.getString(
            R.string.nightscout_status_iob_rejected,
            if (deviceStatusCode > 0) "HTTP $deviceStatusCode" else context.getString(R.string.status_connection_failed)
        )
        else -> null
    }

    val masterSubtitle = when (mode) {
        NightscoutMode.UPLOAD -> if (isActive) {
            context.getString(R.string.nightscout_upload_active)
        } else {
            context.getString(R.string.nightscout_upload_paused)
        }
        NightscoutMode.FOLLOW -> when {
            !isActive -> context.getString(R.string.nightscout_follow_status_paused)
            NightscoutFollowerRegistry.normalizeUrl(url).isBlank() -> context.getString(R.string.nightscout_follow_status_config_needed)
            else -> context.getString(R.string.nightscout_follow_status_following)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nightscout_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("nightscout_master") {
                MasterSwitchCard(
                    title = stringResource(R.string.active),
                    subtitle = masterSubtitle,
                    checked = isActive,
                    onCheckedChange = { enabled ->
                        isActive = enabled
                        persistSettings(connectFollower = enabled && mode == NightscoutMode.FOLLOW)
                    },
                    icon = Icons.Filled.CloudUpload
                )
            }

            item("nightscout_mode_group") {
                ConnectedButtonGroup(
                    options = listOf(NightscoutMode.UPLOAD, NightscoutMode.FOLLOW),
                    selectedOption = mode,
                    onOptionSelected = { selectedMode ->
                        if (selectedMode == mode) return@ConnectedButtonGroup
                        mode = selectedMode
                        persistSettings(connectFollower = isActive && selectedMode == NightscoutMode.FOLLOW)
                        testState = TestState.Idle
                    },
                    label = {},
                    labelText = { selectedMode ->
                        when (selectedMode) {
                            NightscoutMode.UPLOAD -> context.getString(R.string.nightscout_mode_upload)
                            NightscoutMode.FOLLOW -> context.getString(R.string.nightscout_mode_follow)
                        }
                    },
                    icon = { selectedMode ->
                        when (selectedMode) {
                            NightscoutMode.UPLOAD -> Icons.Default.CloudUpload
                            NightscoutMode.FOLLOW -> Icons.Default.Link
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    itemHeight = 48.dp,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // URL + secret are shared by upload and follow.
            item("nightscout_connection_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = cardShape(CardPosition.SINGLE),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { newUrl ->
                                url = newUrl
                                testState = TestState.Idle
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.nightscout_url_label)) },
                            placeholder = { Text(stringResource(R.string.nightscout_url_placeholder)) },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                        )
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it; testState = TestState.Idle },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.api_secret_label)) },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { persistSettings(connectFollower = isActive && mode == NightscoutMode.FOLLOW) }),
                            trailingIcon = {
                                IconButton(onClick = { showSecret = !showSecret }) {
                                    Icon(
                                        imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Test connection and token refresh — available regardless of mode
            item("nightscout_test") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { testConnection() },
                            enabled = isActive && testState !is TestState.Testing,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                        ) {
                            if (testState is TestState.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.nightscout_test_testing))
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.nightscout_test_connection))
                            }
                        }
                        OutlinedButton(
                            onClick = { refreshToken() },
                            enabled = isActive && tokenState !is TokenState.Refreshing,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                        ) {
                            if (tokenState is TokenState.Refreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.nightscout_token_refreshing))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.nightscout_refresh_token))
                            }
                        }
                    }
                    when (val t = tokenState) {
                        is TokenState.Ok -> Text(
                            text = stringResource(
                                R.string.nightscout_token_ok,
                                formatStatusTime(t.expiresAtMillis / 1000L),
                                t.permissions.joinToString(", ").ifEmpty { "-" }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        is TokenState.Err -> Text(
                            text = stringResource(R.string.nightscout_token_error, t.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        else -> {}
                    }
                    when (val s = testState) {
                        is TestState.Ok -> Text(
                            text = stringResource(R.string.nightscout_test_ok, s.code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        is TestState.Err -> Text(
                            text = stringResource(R.string.nightscout_test_error, s.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        else -> {}
                    }
                }
            }

            // Upload-only items
            if (mode == NightscoutMode.UPLOAD) {
                item("nightscout_status_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isActive) 1f else 0.6f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        shape = cardShape(CardPosition.SINGLE),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = stringResource(R.string.status), style = MaterialTheme.typography.titleMedium)
                            Text(text = uploaderSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = responseSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.nightscout_status_last_attempt, formatStatusTime(lastAttemptTime)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.nightscout_status_last_success, formatStatusTime(lastSuccessTime)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (treatmentSummary != null) {
                                Text(
                                    text = treatmentSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (treatmentSync.isFailing) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            if (deviceStatusSummary != null) {
                                Text(
                                    text = deviceStatusSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                item("nightscout_options_group") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SettingsSwitchItem(
                            title = stringResource(R.string.sendamounts),
                            subtitle = stringResource(R.string.nightscout_send_amounts_desc),
                            checked = sendTreatments,
                            onCheckedChange = {
                                sendTreatments = it
                                Natives.setpostTreatments(it)
                            },
                            icon = Icons.Default.Medication,
                            iconTint = MaterialTheme.colorScheme.primary,
                            enabled = isActive,
                            position = CardPosition.TOP
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.nightscout_receive_amounts),
                            subtitle = stringResource(R.string.nightscout_receive_amounts_desc),
                            checked = receiveTreatments,
                            onCheckedChange = {
                                receiveTreatments = it
                                JournalTreatmentUploader.setReceiveTreatments(it)
                            },
                            icon = Icons.Default.Link,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            enabled = isActive,
                            position = CardPosition.MIDDLE
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.nightscout_send_long_insulin),
                            subtitle = stringResource(R.string.nightscout_send_long_insulin_desc),
                            checked = sendLongInsulin,
                            onCheckedChange = {
                                sendLongInsulin = it
                                JournalTreatmentUploader.setSendLongInsulin(it)
                                if (it) Natives.wakeuploader()
                            },
                            icon = Icons.Default.Medication,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            enabled = isActive && sendTreatments,
                            position = CardPosition.MIDDLE
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.nightscout_upload_iob),
                            subtitle = stringResource(R.string.nightscout_upload_iob_desc),
                            checked = uploadIob,
                            onCheckedChange = {
                                uploadIob = it
                                NightPost.setUploadIob(it)
                                if (it) Natives.wakeuploader()
                            },
                            icon = Icons.Default.CloudUpload,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            enabled = isActive,
                            position = CardPosition.MIDDLE
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.nightscout_use_v3_api),
                            subtitle = stringResource(R.string.nightscout_use_v3_api_desc),
                            checked = isV3,
                            onCheckedChange = { isV3 = it },
                            icon = Icons.Default.Api,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            enabled = isActive,
                            position = CardPosition.BOTTOM
                        )
                    }
                }

                item("nightscout_send_now") {
                    Button(
                        onClick = {
                            persistSettings()
                            Natives.wakeuploader()
                            refreshStatus()
                            Toast.makeText(context, context.getString(R.string.sending_now), Toast.LENGTH_SHORT).show()
                        },
                        enabled = isActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.sendnow))
                    }
                }

                item("nightscout_resend_reset") {
                    OutlinedButton(
                        onClick = {
                            persistSettings()
                            Natives.resetuploader()
                            refreshStatus()
                            Toast.makeText(context, context.getString(R.string.resend_triggered), Toast.LENGTH_SHORT).show()
                        },
                        enabled = isActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.resend_data_reset))
                    }
                }
            }
        }
    }
}
