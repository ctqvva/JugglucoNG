package tk.glucodata.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.alerts.CompressionHoldRuntime
import tk.glucodata.data.CompressionEpisodeClassifier
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.HypoEpisodeMark
import tk.glucodata.logic.CompressionLowDetector
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The hypo log: every below-range episode of the last 30 days as one row — date, time
 * range, length, nadir — with its classification and the toggle that flips it. The
 * detector and the hold only ever SUGGEST "pressure"; nothing is excluded from
 * statistics until the user confirms, and flipping a mark back restores the numbers
 * because raw readings are never touched.
 */
private data class HypoLogRow(
    val startMs: Long,
    val endMs: Long,
    val durationMinutes: Int,
    val nadirMgdl: Float,
    val confirmedPressure: Boolean,
    val suggestedPressure: Boolean,
    val suggestionSource: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HypoEpisodeLogScreen(navController: NavController) {
    val isMmol = Applic.unit == 1
    var rows by remember { mutableStateOf<List<HypoLogRow>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) { loadRows() }
    }

    fun setPressure(row: HypoLogRow, pressure: Boolean) {
        rows = rows?.map {
            if (it.startMs == row.startMs) it.copy(confirmedPressure = pressure) else it
        }
        scope.launch(Dispatchers.IO) {
            val context = Applic.app ?: return@launch
            val dao = HistoryDatabase.getInstance(context).hypoEpisodeDao()
            // Any mark overlapping this episode is replaced or removed — a boundary that
            // drifted since the mark was written must not leave an orphan behind.
            HypoEpisodeMark.findFor(dao.getAll(), row.startMs, row.endMs)?.let { dao.delete(it.episodeKeyMs) }
            if (pressure) {
                dao.upsert(
                    HypoEpisodeMark(
                        episodeKeyMs = HypoEpisodeMark.keyFor(row.startMs),
                        endMs = row.endMs,
                        nadirMgdl = row.nadirMgdl,
                        classification = HypoEpisodeMark.CLASSIFICATION_PRESSURE,
                        source = HypoEpisodeMark.SOURCE_MANUAL,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hypo_episode_log_title)) },
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
        val current = rows
        when {
            current == null -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            current.isEmpty() -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    stringResource(R.string.hypo_episode_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.hypo_episode_log_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(current, key = { it.startMs }) { row ->
                    HypoEpisodeRow(row, isMmol) { pressure -> setPressure(row, pressure) }
                }
            }
        }
    }
}

@Composable
private fun HypoEpisodeRow(row: HypoLogRow, isMmol: Boolean, onSetPressure: (Boolean) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val start = remember(row.startMs) { Instant.ofEpochMilli(row.startMs).atZone(zone) }
    val end = remember(row.endMs) { Instant.ofEpochMilli(row.endMs).atZone(zone) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(start.format(dateFormat), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.hypo_episode_log_range,
                            start.format(timeFormat),
                            end.format(timeFormat),
                            row.durationMinutes,
                            formatNadir(row.nadirMgdl, isMmol)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.hypo_episode_log_pressure_toggle),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Switch(
                        checked = row.confirmedPressure,
                        onCheckedChange = onSetPressure
                    )
                }
            }
            if (row.suggestedPressure && !row.confirmedPressure) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        stringResource(R.string.hypo_episode_log_suggested),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (row.confirmedPressure) {
                Text(
                    stringResource(R.string.hypo_episode_log_excluded_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatNadir(mgdl: Float, isMmol: Boolean): String =
    if (isMmol) "%.1f".format(GlucoseFormatter.mgToMmol(mgdl)) else "%.0f".format(mgdl)

private suspend fun loadRows(): List<HypoLogRow> {
    val context = Applic.app ?: return emptyList()
    val nowMs = System.currentTimeMillis()
    val startMs = nowMs - 30L * 24 * 60 * 60_000L

    // The same multi-sensor, display-mapped series the statistics screen computes its
    // episodes from (a 30-day window routinely spans two or three sensors); mg/dL
    // throughout — history, targets, detector.
    val uiPoints = HistoryRepository(context).getDisplayHistoryForStats(null, startMs)
        .filter { it.value > 0f }
    if (uiPoints.isEmpty()) return emptyList()
    val historyMgdl = uiPoints

    val lowMgdl = toMgdlTarget(Natives.targetlow()).takeIf { it > 0f } ?: 70f
    val veryLowMgdl = toMgdlTarget(Natives.alarmverylow()).takeIf { it > 0f } ?: 54f
    val targets = StatsTargets(lowMgDl = lowMgdl, veryLowMgDl = minOf(veryLowMgdl, lowMgdl - 1f))

    val episodes = StatsAnalytics.detectEpisodes(uiPoints, targets)
        .filter { it.kind == EpisodeKind.LOW }
        .sortedByDescending { it.startMillis }
    if (episodes.isEmpty()) return emptyList()

    val samples = historyMgdl.map { CompressionLowDetector.Sample(it.timestamp, it.value) }
    val detected = runCatching {
        CompressionEpisodeClassifier.detectPressureEpisodes(
            context, samples, startMs, nowMs, CompressionHoldRuntime.loadTuning()
        )
    }.getOrDefault(emptyList())
    val holdLog = runCatching { CompressionHoldRuntime.loadLog() }.getOrDefault(null)
    val marks = HistoryDatabase.getInstance(context).hypoEpisodeDao().getAll()

    fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long) =
        aStart <= bEnd && bStart <= aEnd

    return episodes.map { episode ->
        val mark = HypoEpisodeMark.findFor(marks, episode.startMillis, episode.endMillis)
        val detectorHit = detected.any {
            overlaps(episode.startMillis, episode.endMillis, it.onsetMillis, it.recoveryMillis)
        }
        val holdHit = holdLog?.entries?.any {
            it.outcome == tk.glucodata.alerts.CompressionHoldLog.Outcome.RESOLVED &&
                overlaps(episode.startMillis, episode.endMillis, it.startMs, it.endMs)
        } == true
        HypoLogRow(
            startMs = episode.startMillis,
            endMs = episode.endMillis,
            durationMinutes = episode.durationMinutes,
            nadirMgdl = episode.extremeMgDl,
            confirmedPressure = mark?.classification == HypoEpisodeMark.CLASSIFICATION_PRESSURE,
            suggestedPressure = detectorHit || holdHit,
            suggestionSource = when {
                holdHit -> HypoEpisodeMark.SOURCE_HOLD
                detectorHit -> HypoEpisodeMark.SOURCE_DETECTOR
                else -> null
            }
        )
    }
}

private fun toMgdlTarget(raw: Float): Float =
    if (Applic.unit == 1 && raw > 0f) GlucoseFormatter.mmolToMg(raw) else raw
