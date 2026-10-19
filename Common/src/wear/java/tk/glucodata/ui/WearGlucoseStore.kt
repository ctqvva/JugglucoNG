package tk.glucodata.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CalibrationAccess
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseSmoothing
import tk.glucodata.Log
import tk.glucodata.MessageSender
import tk.glucodata.NotificationHistorySource
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import tk.glucodata.WearJournalSync
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One shared, off-main-thread copy of the history the watch screens draw from.
 *
 * Every screen used to read the store itself, on the main thread, and re-read it
 * on each refresh: the chart pulled the whole 14-day horizon on init, on every
 * range change, on every arriving sync chunk and once a minute, while the home
 * list pulled six hours of its own and each reading row asked native for the
 * calibration anchors separately. A backfill of 26 chunks therefore meant 26
 * full-horizon reads with a Room/native merge each, all blocking frames.
 *
 * Now a single loader owns it: reads are coalesced, run on a background
 * dispatcher, cover only the horizon something is actually showing, and grow
 * only when the user pans further back.
 */
object WearGlucoseStore {
    private const val TAG = "WearGlucoseStore"
    private const val HOUR_MS = 3_600_000L

    /** Enough for the default view plus a little scrollback, and cheap to read. */
    const val DEFAULT_HORIZON_MS = 24L * HOUR_MS
    const val MAX_HORIZON_MS = 14L * 24L * HOUR_MS

    /** Read on a cold open, before the full horizon, so the UI fills at once. */
    private const val FIRST_PASS_HORIZON_MS = 3L * HOUR_MS

    /** Refreshes arriving faster than this are folded into one reload. */
    private const val MIN_RELOAD_INTERVAL_MS = 4_000L
    private const val TICK_MS = 60_000L

    /** How often the journal is re-requested; it changes far slower than glucose. */
    private const val JOURNAL_REFRESH_TICKS = 5

    /**
     * One sensor drawn beside the primary, as the phone's chart draws its
     * peers: its own lane by its own view mode, in its own colour.
     */
    class PeerSeries(
        val sensorId: String,
        /** Ascending, oldest first; calibrated and smoothed like [Snapshot.points]. */
        val points: List<GlucosePoint>,
        val viewMode: Int,
        val colorArgb: Int,
    ) {
        val isRawMode: Boolean get() = viewMode == 1 || viewMode == 3
    }

    data class Snapshot(
        /** Ascending, oldest first, covering [horizonStartMs] to now. */
        val points: List<GlucosePoint> = emptyList(),
        /** The other selected sensors, in the phone's order; empty with one sensor. */
        val peers: List<PeerSeries> = emptyList(),
        /** Calibration anchors as [sensorMgdl, userMgdl, timestampMs] triples. */
        val anchors: DoubleArray = DoubleArray(0),
        val horizonStartMs: Long = 0L,
        val isMmol: Boolean = false,
        /** auto / raw / auto+raw / raw+auto, as the phone numbers them. */
        val viewMode: Int = 0,
        val sensorId: String? = null,
        val loadedAtMs: Long = 0L,
    ) {
        val isLoaded: Boolean get() = loadedAtMs > 0L

        /** True when the raw lane is the one shown first. */
        val isRawMode: Boolean get() = viewMode == 1 || viewMode == 3

        /** True when a second lane is shown beside the primary one. */
        val showsSecondary: Boolean get() = viewMode == 2 || viewMode == 3

        // Generated equals/hashCode would compare the anchor array by identity,
        // and every reload allocates a new one; Compose would then treat each
        // snapshot as changed even when nothing moved.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snapshot) return false
            return loadedAtMs == other.loadedAtMs &&
                horizonStartMs == other.horizonStartMs &&
                isMmol == other.isMmol &&
                viewMode == other.viewMode &&
                sensorId == other.sensorId &&
                points === other.points &&
                peers === other.peers
        }

        override fun hashCode(): Int = loadedAtMs.hashCode() * 31 + horizonStartMs.hashCode()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot = _snapshot.asStateFlow()

    /**
     * A load that never returns must not freeze the display. Reads go through
     * native and Room; with several sensors in play one can take far longer than
     * expected, and the in-flight flag then blocked every later refresh — the
     * screen simply stopped at whatever it last had, which is indistinguishable
     * from the app hanging.
     */
    private const val LOAD_STUCK_AFTER_MS = 45_000L

    /**
     * Calibrated values keyed by reading timestamp, per sensor and lane, dropped
     * whenever that sensor's anchors change.
     *
     * The history the store reads is uncalibrated — correction happens at display
     * time — so every point has to go through the shared computation. Doing that
     * for a whole horizon on each minute tick would be thousands of fits per
     * refresh, when in practice only the newest reading is new. One cache per
     * sensor, because the peers are corrected on the same pass as the primary
     * and a single shared cache thrashed between them.
     */
    private class CalibrationCache(val key: String) {
        val auto = HashMap<Long, Float>()
        val raw = HashMap<Long, Float>()
    }
    private val calibrationCaches = HashMap<String, CalibrationCache>()
    private const val CALIBRATION_CACHE_MAX = 25_000

    /**
     * How far back a peer is drawn. The phone caps its peer history to the
     * recent dashboard window rather than the full horizon, and so does this.
     */
    private const val PEER_HORIZON_MS = 72L * HOUR_MS

    private val started = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    @Volatile private var reloadPending = false
    @Volatile private var lastLoadStartedAt = 0L
    @Volatile private var requestedHorizonMs = DEFAULT_HORIZON_MS

    /**
     * Starts the single collector that keeps the snapshot current.
     *
     * Everything here is gated on something actually collecting [snapshot].
     * The loop used to run for as long as the process lived: opening the app
     * once left the watch reading its whole history, recalibrating it and
     * re-smoothing it every minute forever, and messaging the phone every five
     * — waking it out of doze — with no screen on either device. The watch face
     * and the complications read their own source, so nothing here is needed
     * while no screen is showing it.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            launch {
                UiRefreshBus.revision.collect { if (isObserved()) refresh() }
            }
            launch {
                _snapshot.subscriptionCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .collectLatest { observed ->
                        if (!observed) return@collectLatest
                        // Whatever arrived unobserved is not in the snapshot.
                        refresh(force = true)
                        requestJournal()
                        requestPrefs()
                        var tick = 0
                        while (true) {
                            delay(TICK_MS)
                            refresh()
                            if (++tick % JOURNAL_REFRESH_TICKS == 0) requestJournal()
                        }
                    }
            }
        }
    }

    private fun isObserved(): Boolean = _snapshot.subscriptionCount.value > 0

    private fun requestJournal() {
        runCatching { WearJournalSync.requestSync() }
    }

    /**
     * Asks the phone for the display preferences and colour scheme.
     *
     * Sent when a screen appears, not on a timer: the phone also pushes these
     * with the sync the watch already asks for, so a periodic pull would only
     * add a wake-up of a sleeping phone every few minutes to learn nothing.
     */
    private fun requestPrefs() {
        runCatching { MessageSender.getMessageSender()?.requestWearPrefs() }
    }

    /**
     * Asks for at least [horizonMs] of history. Panning back past what is loaded
     * calls this; nothing re-reads the deep horizon until something needs it.
     */
    fun ensureHorizon(horizonMs: Long) {
        val wanted = horizonMs.coerceIn(DEFAULT_HORIZON_MS, MAX_HORIZON_MS)
        if (wanted <= requestedHorizonMs) return
        requestedHorizonMs = wanted
        refresh(force = true)
    }

    /** Re-reads on the next opportunity; bursts collapse into one pass. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLoadStartedAt < MIN_RELOAD_INTERVAL_MS) {
            reloadPending = true
            return
        }
        if (!loading.compareAndSet(false, true)) {
            val stuckFor = now - lastLoadStartedAt
            if (stuckFor < LOAD_STUCK_AFTER_MS) {
                reloadPending = true
                return
            }
            // Been "loading" far too long to be real. Take the flag back rather
            // than leave the screen frozen for good.
            Log.w(TAG, "previous history load still running after ${stuckFor / 1000}s; starting another")
        }
        lastLoadStartedAt = now
        scope.launch {
            try {
                load()
            } catch (t: Throwable) {
                Log.stack(TAG, "load", t)
            } finally {
                loading.set(false)
                if (reloadPending) {
                    reloadPending = false
                    delay(MIN_RELOAD_INTERVAL_MS)
                    refresh(force = true)
                }
            }
        }
    }

    private fun load() {
        // First pass reads a couple of hours so the screen has numbers on it
        // straight away, then the full horizon lands behind it. A cold open used
        // to draw an empty chart until the deep read finished.
        if (!_snapshot.value.isLoaded && requestedHorizonMs > FIRST_PASS_HORIZON_MS) {
            loadHorizon(FIRST_PASS_HORIZON_MS)
        }
        loadHorizon(requestedHorizonMs)
    }

    private fun loadHorizon(horizonMs: Long) {
        val now = System.currentTimeMillis()
        val isMmol = runCatching { Applic.unit == 1 }.getOrDefault(false)
        val horizonStart = now - horizonMs
        // Which of several sensors the screens follow, rather than whatever
        // native happens to call "main".
        val sensor = WearSensorSelection.resolve()
        // The mode belongs to the sensor being displayed; resolving it from
        // whatever native called "main" showed one sensor's readings under
        // another's mode as soon as the user pinned a second sensor.
        val viewMode = viewModeFor(sensor)
        if (Log.doLog) Log.i(TAG, "selection ${WearSensorSelection.selected()} primary=$sensor mode=$viewMode")
        val isRawMode = viewMode == 1 || viewMode == 3
        val rawPoints = runCatching {
            NotificationHistorySource.getDisplayHistory(horizonStart, isMmol, sensor)
        }.getOrDefault(emptyList())
            .filter { it.timestamp in horizonStart..now && it.value.isFinite() && it.value > 0f }
        val anchors = runCatching {
            CalibrationAccess.getActiveCalibrationAnchors(
                sensor ?: runCatching { SensorIdentity.resolveMainSensor() }.getOrNull(),
                isRawMode,
            )
        }.getOrDefault(DoubleArray(0))
        // Calibrate first, then smooth: smoothing a lane and correcting the
        // result is not the same as correcting each reading and smoothing those,
        // and the phone corrects at display time before its chart pipeline runs.
        val points = smooth(calibrate(rawPoints, sensor, isRawMode))
        val peers = loadPeers(maxOf(horizonStart, now - PEER_HORIZON_MS), now, isMmol)
        synchronized(calibrationCaches) {
            val live = (peers.map { it.sensorId } + sensor.orEmpty()).toSet()
            calibrationCaches.keys.retainAll { key -> live.any { key.startsWith("$it|") } }
        }

        _snapshot.value = Snapshot(
            points = points,
            peers = peers,
            anchors = anchors,
            horizonStartMs = horizonStart,
            isMmol = isMmol,
            viewMode = viewMode,
            sensorId = sensor,
            loadedAtMs = now,
        )
    }

    /**
     * The other selected sensors' series, each through the same pipeline as the
     * primary: its own view mode, its own anchors, the shared smoothing. The
     * phone draws every selected sensor on one chart; the watch drew only the
     * primary, so the second sensor was simply missing from it.
     */
    private fun loadPeers(from: Long, now: Long, isMmol: Boolean): List<PeerSeries> {
        val peers = runCatching { WearSensorSelection.peers() }.getOrDefault(emptyList())
        if (peers.isEmpty()) return emptyList()
        val colors = WearSensorSelection.colors()
        return peers.mapNotNull { peer ->
            val viewMode = viewModeFor(peer)
            val isRawMode = viewMode == 1 || viewMode == 3
            val raw = runCatching {
                NotificationHistorySource.getDisplayHistory(from, isMmol, peer)
            }.getOrDefault(emptyList())
                .filter { it.timestamp in from..now && it.value.isFinite() && it.value > 0f }
            if (Log.doLog) {
                Log.i(TAG, "peer $peer mode=$viewMode points=${raw.size} last=${raw.lastOrNull()?.let { "${it.value}/${it.rawValue}" } ?: "-"}")
            }
            if (raw.isEmpty()) return@mapNotNull null
            PeerSeries(
                sensorId = peer,
                points = smooth(calibrate(raw, peer, isRawMode)),
                viewMode = viewMode,
                colorArgb = WearSensorSelection.colorOf(peer, colors)
                    ?: runCatching { tk.glucodata.SensorVisuals.colorArgb(peer) }.getOrDefault(0xFF9E9E9E.toInt()),
            )
        }
    }

    /**
     * Applies the user's calibration to a series, as the phone does at display
     * time. Before this the watch drew whatever was stored, which since the
     * lanes started travelling uncalibrated meant raw values everywhere.
     */
    private fun calibrate(
        points: List<GlucosePoint>,
        sensor: String?,
        isRawMode: Boolean,
    ): List<GlucosePoint> {
        if (points.isEmpty()) return points
        val hasCalibration = runCatching {
            CalibrationAccess.hasActiveCalibration(isRawMode, sensor)
        }.getOrDefault(false)
        if (!hasCalibration) return points

        val revision = runCatching { CalibrationAccess.getRevision() }.getOrDefault(0L)
        val cacheId = "${sensor.orEmpty()}|$isRawMode"
        val key = "$cacheId|$revision"
        val cache = synchronized(calibrationCaches) {
            val existing = calibrationCaches[cacheId]
            if (existing == null || existing.key != key || existing.auto.size > CALIBRATION_CACHE_MAX) {
                CalibrationCache(key).also { calibrationCaches[cacheId] = it }
            } else {
                existing
            }
        }

        fun corrected(value: Float, timestamp: Long, rawLane: Boolean): Float {
            if (!value.isFinite() || value <= 0f) return value
            val lane = if (rawLane) cache.raw else cache.auto
            synchronized(cache) { lane[timestamp] }?.let { return it }
            val result = runCatching {
                CalibrationAccess.getCalibratedValue(value, timestamp, rawLane, false, sensor)
            }.getOrDefault(value).takeIf { it.isFinite() && it > 0f } ?: value
            synchronized(cache) { lane[timestamp] = result }
            return result
        }

        // The phone corrects only the lane its view mode makes primary, and with
        // that lane's own anchors (ReadingRow: baseValue = raw or auto by mode);
        // the other lane is drawn as it was stored. Correcting both here made the
        // watch's secondary read 5,3 where the phone showed 5,4 — and in a raw
        // view mode it ran the auto value through the raw anchors, which are
        // fitted against a different set of numbers entirely.
        return points.map { point ->
            val value = if (isRawMode) point.value else corrected(point.value, point.timestamp, false)
            val raw = if (isRawMode) corrected(point.rawValue, point.timestamp, true) else point.rawValue
            if (value == point.value && raw == point.rawValue) point
            else GlucosePoint(point.timestamp, value, raw)
        }
    }

    /**
     * Applies the user's smoothing setting, which the watch used to ignore
     * outright: the same sensor drew a smooth curve on the phone and a noisy
     * one here. The settings are mirrored from the phone with the rest of the
     * preferences, and the pipeline is the phone's own.
     *
     * "Smooth exchange outputs only" means the displayed series stays raw, so
     * [DataSmoothing.graphSmoothingMinutes] — not the plain minutes value — is
     * what decides, exactly as the dashboard decides it.
     */
    private fun smooth(points: List<GlucosePoint>): List<GlucosePoint> {
        val context = Applic.app ?: return points
        val minutes = runCatching { DataSmoothing.graphSmoothingMinutes(context) }.getOrDefault(0)
        if (minutes <= 0) return points
        val collapse = runCatching { DataSmoothing.collapseChunks(context) }.getOrDefault(false)
        return runCatching {
            GlucoseSmoothing.smooth(
                points = points,
                smoothingMinutes = minutes,
                collapseIntoChunks = collapse,
                timestamp = { it.timestamp },
                value = { it.value },
                rawValue = { it.rawValue },
                withValues = { point, auto, raw -> GlucosePoint(point.timestamp, auto, raw) },
            )
        }.getOrElse {
            Log.stack(TAG, "smooth", it)
            points
        }
    }

    private fun viewModeFor(sensor: String?): Int = runCatching {
        val resolved = sensor ?: NotificationHistorySource.resolveSensorSerial()
        CurrentDisplaySource.resolveViewModeForSensor(resolved).coerceIn(0, 3)
    }.getOrDefault(0)

    /** The sensor the screens follow, and the mode it is displayed in. */
    fun currentSensor(): String? =
        runCatching { WearSensorSelection.resolve() }.getOrNull()
            ?: runCatching { NotificationHistorySource.resolveSensorSerial() }.getOrNull()

    /** The view mode the snapshot was loaded with, for screens that show it. */
    fun viewMode(): Int = viewModeFor(currentSensor())

    /** Newest first, for the reading lists. */
    fun recent(count: Int, withinMs: Long = 6L * HOUR_MS): List<GlucosePoint> {
        val snap = _snapshot.value
        if (snap.points.isEmpty()) return emptyList()
        val cutoff = System.currentTimeMillis() - withinMs
        val result = ArrayList<GlucosePoint>(count)
        for (index in snap.points.indices.reversed()) {
            val point = snap.points[index]
            if (point.timestamp < cutoff) break
            result.add(point)
            if (result.size >= count) break
        }
        return result
    }
}
