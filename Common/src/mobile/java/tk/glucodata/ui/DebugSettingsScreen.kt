package tk.glucodata.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.BuildConfig
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.util.ConnectedButtonGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogType {
    TRACE, LOGCAT
}

private const val LOG_TAIL_BYTES = 50L * 1024L
private const val PREF_HOWTO_DISMISSED = "debug_howto_dismissed"

private fun LogType.fileName(): String =
    if (this == LogType.TRACE) "logs/trace.log" else "logs/logcat.txt"

private fun LogType.exportName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return if (this == LogType.TRACE) "juggluco-trace-$stamp.log" else "juggluco-logcat-$stamp.txt"
}

/** Snapshot of what is currently on disk, so the poll loop can skip untouched files. */
private data class LogSnapshot(
    val lines: List<String> = emptyList(),
    val bytes: Long = 0L,
    val stamp: Long = 0L,
    val truncated: Boolean = false
)

private fun readLog(file: File, truncationNotice: String): LogSnapshot {
    if (!file.exists()) return LogSnapshot()
    val size = file.length()
    val stamp = file.lastModified()
    return try {
        if (size > LOG_TAIL_BYTES) {
            file.inputStream().use { stream ->
                stream.skip(size - LOG_TAIL_BYTES)
                val text = stream.bufferedReader().readText()
                LogSnapshot(
                    lines = listOf(truncationNotice) + text.lineSequence().drop(1).toList(),
                    bytes = size,
                    stamp = stamp,
                    truncated = true
                )
            }
        } else {
            LogSnapshot(lines = file.readLines(), bytes = size, stamp = stamp)
        }
    } catch (e: Exception) {
        LogSnapshot(lines = listOf("${e.message}"), bytes = size, stamp = stamp)
    }
}

/** Short, non-identifying preamble so a shared log says which build it came from. */
private fun reportHeader(): String = buildString {
    append("# Juggluco ").append(BuildConfig.VERSION_NAME).append('\n')
    append("# Android ").append(Build.VERSION.RELEASE)
        .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
    append("# Device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
    append("# Captured ").append(
        SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).format(Date())
    ).append("\n\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val prefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }

    var logType by remember { mutableStateOf(LogType.TRACE) }
    var isRecording by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(LogSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    // Shown until the user dismisses it with the X; the title-bar help button brings it back
    // without clearing that choice, so the card never returns uninvited.
    var showHowTo by remember { mutableStateOf(!prefs.getBoolean(PREF_HOWTO_DISMISSED, false)) }

    val truncationNotice = stringResource(R.string.debug_truncated)
    val nothingToShare = stringResource(R.string.debug_nothing_to_share)
    val chooserTitle = stringResource(R.string.debug_share_chooser)
    val savingError = stringResource(R.string.error_saving_file)

    fun currentFile(): File = File(context.filesDir, logType.fileName())

    LaunchedEffect(logType) {
        isRecording = if (logType == LogType.TRACE) Natives.islogging() else Natives.islogcat()
        loading = true
        var lastStamp = -1L
        var lastBytes = -1L
        while (isActive) {
            val next = withContext(Dispatchers.IO) {
                val file = currentFile()
                if (file.exists() && file.lastModified() == lastStamp && file.length() == lastBytes) {
                    null
                } else {
                    readLog(file, truncationNotice)
                }
            }
            if (next != null) {
                lastStamp = next.stamp
                lastBytes = next.bytes
                snapshot = next
            }
            loading = false
            // Nothing appends while recording is off, so back the poll right down.
            delay(if (isRecording) 1000L else 4000L)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val source = currentFile()
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(reportHeader().toByteArray())
                        source.inputStream().use { it.copyTo(output) }
                    }
                }.exceptionOrNull()
            }
            if (error != null) snackbarHost.showSnackbar(savingError + error.message)
        }
    }

    fun shareLog() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = currentFile()
                    if (!source.exists() || source.length() == 0L) return@runCatching null
                    val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
                    // Each staged copy is as large as the log itself. Drop earlier ones so
                    // repeated shares don't quietly park tens of MB in the cache.
                    outDir.listFiles { f -> f.name.startsWith("juggluco-") }
                        ?.forEach { it.delete() }
                    val staged = File(outDir, logType.exportName())
                    staged.outputStream().use { output ->
                        output.write(reportHeader().toByteArray())
                        source.inputStream().use { it.copyTo(output) }
                    }
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        staged
                    )
                }
            }
            val uri = result.getOrNull()
            if (uri == null) {
                snackbarHost.showSnackbar(nothingToShare)
                return@launch
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        chooserTitle
                    )
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHowTo = !showHowTo }) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.debug_howto_title)
                        )
                    }
                    IconButton(onClick = {
                        if (logType == LogType.TRACE) Natives.zeroLog() else Natives.zeroLogcat()
                        snapshot = LogSnapshot()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedVisibility(visible = showHowTo) {
                HowToReportCard(
                    onDismiss = {
                        showHowTo = false
                        prefs.edit().putBoolean(PREF_HOWTO_DISMISSED, true).apply()
                    }
                )
            }

            MasterSwitchCard(
                title = stringResource(R.string.debug_record_title),
                subtitle = stringResource(
                    if (isRecording) R.string.debug_record_on else R.string.debug_record_off
                ),
                checked = isRecording,
                icon = Icons.Default.BugReport,
                onCheckedChange = { enabled ->
                    isRecording = enabled
                    if (logType == LogType.TRACE) {
                        Natives.dolog(enabled)
                        // Java-side guards mirror the native switch — refresh or they stay
                        // short-circuited and the trace comes back empty.
                        tk.glucodata.Log.refreshDoLog()
                    } else {
                        Natives.dologcat(enabled)
                    }
                }
            )

            val logTypeLabels = mapOf(
                LogType.TRACE to stringResource(R.string.trace_log),
                LogType.LOGCAT to stringResource(R.string.logcat)
            )
            ConnectedButtonGroup(
                options = LogType.entries,
                selectedOption = logType,
                onOptionSelected = { logType = it },
                labelText = { logTypeLabels[it].orEmpty() },
                label = { Text(logTypeLabels[it].orEmpty()) },
                modifier = Modifier.fillMaxWidth(),
                // The alerts screen sits on a card, so its translucent unselected colour reads as
                // grey there. This screen is on the bare background, so use the group's own
                // container tone or the unselected half vanishes.
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unselectedContentColor = MaterialTheme.colorScheme.onSurface
            )

            LogStatsRow(
                recording = isRecording,
                bytes = snapshot.bytes,
                lineCount = snapshot.lines.size,
                truncated = snapshot.truncated
            )

            LogConsole(
                lines = snapshot.lines,
                loading = loading,
                recording = isRecording,
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { shareLog() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.debug_share_log))
                }
                OutlinedButton(onClick = { saveLauncher.launch(logType.exportName()) }) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun HowToReportCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.debug_howto_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.debug_dismiss_howto),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumberedStep(1, stringResource(R.string.debug_howto_step1))
                NumberedStep(2, stringResource(R.string.debug_howto_step2))
                NumberedStep(3, stringResource(R.string.debug_howto_step3))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.debug_howto_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(20.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LogStatsRow(recording: Boolean, bytes: Long, lineCount: Int, truncated: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecordingDot(active = recording)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (lineCount == 0) {
                stringResource(R.string.debug_log_empty)
            } else {
                stringResource(
                    if (truncated) R.string.debug_log_stats_tail else R.string.debug_log_stats,
                    Formatter.formatShortFileSize(context, bytes),
                    lineCount
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "recordingPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recordingPulseAlpha"
    )
    val color by animateColorAsState(
        if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        label = "recordingDotColor"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(if (active) pulse else 1f)
            .background(color, CircleShape)
    )
}

@Composable
private fun LogConsole(
    lines: List<String>,
    loading: Boolean,
    recording: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // derivedStateOf { } is remembered without keys, so read the list through an updated
    // State holder — capturing the parameter directly would freeze the first frame's size.
    val currentLines by rememberUpdatedState(lines)
    val following by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= currentLines.size - 2
        }
    }

    LaunchedEffect(lines.size) {
        if (following && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        when {
            loading -> CenteredHint(stringResource(R.string.debug_loading))
            lines.isEmpty() -> CenteredHint(
                stringResource(
                    if (recording) R.string.log_enabled_waiting else R.string.log_unchecked
                )
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // Keep the last lines clear of the floating jump-to-latest button.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 60.dp)
            ) {
                items(lines.size) { index ->
                    Text(
                        text = lines[index],
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !following && lines.isNotEmpty(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        ) {
            val scope = rememberCoroutineScope()
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(lines.lastIndex) } },
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = stringResource(R.string.debug_jump_to_latest)
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}
