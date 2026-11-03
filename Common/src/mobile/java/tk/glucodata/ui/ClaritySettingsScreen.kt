// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.ClarityStatus
import tk.glucodata.Log
import tk.glucodata.share.Clarity
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.util.findActivity

private const val LOG_ID = "Clarity"
private const val EARLIEST_START_SECONDS = Clarity.EARLIEST_START_SECONDS

/**
 * Dexcom Clarity upload. Everything except the sign-in page lives in native code (net/clarity):
 * tokens, the account's client certificate and the outbox never cross JNI. This screen only
 * changes the four upload options and shows what the uploader reports.
 *
 * Amount upload (insulin/carbs) exists natively but needs label categorisation that NG has no UI
 * for yet, so it is left as stored and never switched on from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaritySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(Clarity.status()) }
    var error by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf(Locale.getDefault().country) }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf<Long?>(null) }

    // The worker reports from its own thread; poll while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { Clarity.status() }
            delay(1_000)
        }
    }

    fun configure(enabled: Boolean, history: Boolean, since: Long) {
        val current = status ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Amounts stay as stored: see the class comment.
                Clarity.configure(enabled, history, current.numbers, since)
            }
            error = if (result == null) context.getString(R.string.clarity_save_settings_error) else result.takeIf { it.isNotEmpty() }
            status = withContext(Dispatchers.IO) { Clarity.status() }
        }
    }

    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.clarity_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val s = status
            if (s == null || !s.available) {
                Text(
                    text = s?.status?.takeIf { it.isNotEmpty() }
                        ?: stringResource(if (s == null) R.string.clarity_read_settings_error else R.string.clarity_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            val signedIn = s.account.isNotEmpty()

            MasterSwitchCard(
                title = stringResource(R.string.clarity_send_enabled),
                subtitle = stringResource(R.string.clarity_active_desc),
                checked = s.enabled,
                onCheckedChange = { configure(it, s.libre3History, s.since) },
                icon = Icons.Default.CloudUpload,
                enabled = signedIn || s.enabled,
            )

            ClarityStatusCard(s, dateFormat)

            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            SectionLabel(text = stringResource(R.string.clarity_section_upload), topPadding = 0.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.clarity_change_start),
                    subtitle = stringResource(R.string.clarity_start_date, dateFormat.format(Date(s.since * 1000L))),
                    icon = Icons.Default.CalendarMonth,
                    position = CardPosition.TOP,
                    onClick = { pickingDate = true },
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.clarity_use_history),
                    subtitle = stringResource(R.string.clarity_use_history_desc),
                    checked = s.libre3History,
                    onCheckedChange = { configure(s.enabled, it, s.since) },
                    icon = Icons.Default.History,
                    position = CardPosition.MIDDLE,
                )
                SettingsItem(
                    title = stringResource(R.string.sendnow),
                    icon = Icons.AutoMirrored.Filled.Send,
                    position = CardPosition.BOTTOM,
                    onClick = {
                        scope.launch(Dispatchers.IO) { Clarity.wake() }
                    },
                )
            }

            SectionLabel(text = stringResource(R.string.clarity_section_account), topPadding = 8.dp)
            Text(
                text = if (signedIn) stringResource(R.string.clarity_signed_in_as, s.account)
                else stringResource(R.string.clarity_not_signed_in),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = country,
                onValueChange = { country = it.filter(Char::isLetter).take(2).uppercase(Locale.ROOT) },
                label = { Text(stringResource(R.string.clarity_login_country)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !s.enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Button(
                onClick = { navController.navigate("settings/clarity/login/${Uri.encode(country)}") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !s.enabled && country.length == 2,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(stringResource(R.string.clarity_sign_in))
            }
            if (s.enabled) {
                Text(
                    text = stringResource(R.string.clarity_change_account_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.clarity_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val current = status
    if (pickingDate && current != null) {
        // The picker works in UTC days; the upload start is local midnight of that day.
        val zone = ZoneId.systemDefault()
        val initialDay = Instant.ofEpochSecond(current.since).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val earliest = Instant.ofEpochSecond(EARLIEST_START_SECONDS).atZone(zone).toLocalDate()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !day.isBefore(earliest) && !day.isAfter(today)
                }
                override fun isSelectableYear(year: Int) = year in earliest.year..today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding, onClick = {
                    pickingDate = false
                    pickerState.selectedDateMillis?.let { millis ->
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        val since = maxOf(day.atStartOfDay(zone).toEpochSecond(), EARLIEST_START_SECONDS)
                        if (since != current.since) confirmStart = since
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
    confirmStart?.let { since ->
        AlertDialog(
            onDismissRequest = { confirmStart = null },
            title = { Text(stringResource(R.string.clarity_change_start_title)) },
            text = { Text(stringResource(R.string.clarity_change_start_confirm, dateFormat.format(Date(since * 1000L)))) },
            confirmButton = {
                TextButton(shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding, onClick = {
                    confirmStart = null
                    status?.let { configure(it.enabled, it.libre3History, since) }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = null }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ClarityStatusCard(s: ClarityStatus, dateFormat: DateFormat) {
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()) }
    fun at(seconds: Long) = timeFormat.format(Date(seconds * 1000L))
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(R.string.status), style = MaterialTheme.typography.titleMediumEmphasized)
            if (s.status.isNotEmpty()) {
                Text(text = s.status, style = MaterialTheme.typography.bodyMedium)
            }
            if (s.lastSuccess > 0) {
                Text(
                    text = stringResource(R.string.clarity_last_ack, at(s.lastSuccess)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.clarity_ack_count, s.acknowledgedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.acknowledgedCount > 0) {
                Text(
                    text = stringResource(R.string.clarity_ack_latest, at(s.acknowledgedLast)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.pendingCount > 0) {
                Text(
                    text = stringResource(R.string.clarity_pending_glucose, s.pendingCount, at(s.pendingFirst), at(s.pendingLast)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One sign-in may be completing natively even after its screen is gone. */
private val clarityLoginFinishing = AtomicBoolean()

private fun isDexcomPage(uri: Uri): Boolean {
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
        (host == "dexcom.com" || host.endsWith(".dexcom.com") || host == "dexcom.eu" || host.endsWith(".dexcom.eu"))
}

/**
 * Dexcom's own OAuth page in a WebView. Dexcom owns the credential form; JugglucoNG only sees the
 * `dexcomg7:` callback, which goes straight to native code for the token exchange. The window is
 * FLAG_SECURE while the page is shown, and only https Dexcom pages may load.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClarityLoginScreen(navController: NavController, country: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val wasSecure = window == null || (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun complete(callback: String) {
        if (busy) return
        if (!clarityLoginFinishing.compareAndSet(false, true)) {
            error = context.getString(R.string.clarity_login_in_progress)
            return
        }
        busy = true
        attempted = true
        url = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Clarity.finishLogin(callback) ?: context.getString(R.string.clarity_login_complete_error)
                } finally {
                    clarityLoginFinishing.set(false)
                }
            }
            busy = false
            if (result.isEmpty()) navController.popBackStack()
            else error = context.getString(R.string.clarity_login_retry_error, result)
        }
    }

    LaunchedEffect(attempt) {
        error = null
        url = null
        if (clarityLoginFinishing.get()) {
            error = context.getString(R.string.clarity_login_in_progress)
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { Clarity.beginLogin(country) }
        when {
            result == null || result.size != 2 -> error = context.getString(R.string.clarity_login_start_error)
            result[1].isNotEmpty() -> error = result[1]
            !isDexcomPage(Uri.parse(result[0])) -> error = context.getString(R.string.clarity_login_start_error)
            else -> url = result[0]
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.clarity_sign_in),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val page = url
            when {
                busy -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.clarity_login_completing), style = MaterialTheme.typography.bodyMedium)
                }
                error != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = { attempt++ },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.clarity_try_again)) }
                    if (attempted) {
                        OutlinedButton(
                            onClick = { error = null; complete("") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(R.string.clarity_login_finish_pending)) }
                    }
                }
                page != null -> ClarityLoginWebView(
                    url = page,
                    onCallback = { complete(it) },
                    onError = { error = it },
                )
                else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ClarityLoginWebView(url: String, onCallback: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    // A redirect can be reported by more than one WebViewClient hook; the code is single use.
    val callbackSeen = remember(url) { AtomicBoolean() }
    fun navigate(uri: Uri): Boolean {
        if (uri.scheme == "dexcomg7") {
            if (callbackSeen.compareAndSet(false, true)) onCallback(uri.toString())
            return true
        }
        if (isDexcomPage(uri)) return false
        onError(context.getString(R.string.clarity_login_destination_error))
        return true
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && navigate(request.url)

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.scheme == "https") return null
                        Log.e(LOG_ID, "Blocked non-HTTPS sign-in resource")
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
                        handler.cancel()
                        Log.e(LOG_ID, "Sign-in TLS certificate error code=${sslError.primaryError}")
                        onError(context.getString(R.string.clarity_login_tls_error))
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                        Log.e(LOG_ID, "Sign-in resource error code=${err.errorCode} mainFrame=${request.isForMainFrame}")
                        // The callback is an unknown scheme; its load error is expected.
                        if (request.isForMainFrame && request.url.scheme != "dexcomg7") {
                            onError(context.getString(R.string.clarity_login_load_error))
                        }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        Log.e(LOG_ID, "Sign-in HTTP error=${response.statusCode} mainFrame=${request.isForMainFrame}")
                        if (request.isForMainFrame) {
                            onError(context.getString(R.string.clarity_login_http_error, response.statusCode))
                        }
                    }

                    override fun onPageStarted(view: WebView, startedUrl: String, favicon: Bitmap?) {
                        if (startedUrl.startsWith("dexcomg7:")) {
                            view.stopLoading()
                            navigate(Uri.parse(startedUrl))
                        }
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { web ->
            web.stopLoading()
            web.destroy()
        },
    )
}
