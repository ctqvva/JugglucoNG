package tk.glucodata.drivers.aidex

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.CalibrationAccess
import tk.glucodata.GlucoseUncertaintyAccess
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.HistorySyncAccess
import tk.glucodata.IntegratedStockBaseline
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.aidex.adaptive2.AiDexAdaptiveV2
import tk.glucodata.drivers.aidex.adaptive2.AiDexReference
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** Android storage adapter. Source history always precedes selected output. */
internal class AiDexAlgorithmRuntime(
    private val context: Context,
    private val serial: String,
    private val nativePointer: () -> Long,
    private val sensorStart: () -> Long,
    private val historyActive: () -> Boolean,
    private val storeLive: (AiDexAlgorithmReading, AiDexAlgorithmReading?) -> AiDexAlgorithmReading,
    private val publish: (AiDexAlgorithmReading, Boolean) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "AiDexAlgorithm-$serial") },
    private val requestSourceHistory: (Long?) -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
    private fun key(name: String) = "${name}_$serial"
    val controller = AiDexAlgorithmController(
        AiDexSampleJournal(AiDexSourceJournalBackup.file(context.filesDir, serial)),
        AiDexAlgorithmSelection.fromStorage(prefs.getInt(key("algorithmAppliedMode"), prefs.getInt(key("algorithmMode"), 0))),
    )
    private val running = AtomicBoolean(false)
    private val replayRequested = AtomicBoolean(false)
    private var everAdaptive = prefs.getBoolean(key("algorithmEverAdaptive"), false)
    private var epoch = 0L
    @Volatile var rewritingHistory = false
        private set
    @Volatile var latestPublished: AiDexAlgorithmReading? = null
        private set
    @Volatile var failed = false
        private set
    /** The original-input journal could not be written; readings still publish. */
    @Volatile var journalUnavailable = false
        private set
    private var calibrationRevision = -1L
    private data class CalibrationInputs(val references: List<AiDexReference>, val autoFit: Long)
    private var appliedCalibrationInputs: CalibrationInputs? = null
    @Volatile private var resetWaiting = false
    private var pendingLiveTime = 0L
    @Volatile private var missingSourceTime: Long? = null
    @Volatile private var awaitingSourceHistory = false
    @Volatile private var roomRepairNeeded = false
    @Volatile private var bootstrappedThisProcess = false

    init {
        controller.journal.quarantineListener = { moved, error ->
            // Refill from the factory values Room/native still hold before any
            // later Adaptive rebuild; everAdaptive history refetches instead.
            bootstrappedThisProcess = false
            Log.stack("AiDexAlgorithm", "unreadable source journal for $serial moved to ${moved.name}", error)
        }
        val requested = AiDexAlgorithmSelection.fromStorage(prefs.getInt(key("algorithmMode"), 0))
        if (requested != controller.appliedSelection) controller.request(requested)
        if (everAdaptive || requested.adaptive || nativePointer() != 0L || journalOrNull { controller.journal.first() } != null) {
            // Reconstruct continuation and finish an interrupted Stock restoration
            // as well as an Adaptive replay after process recreation.
            controller.invalidateReferences()
            requestReplay()
        }
    }

    fun source(time: Long, factory: Float, raw: Float, temperature: Float, origin: Int): AiDexSourceSample = synchronized(controller.lock) {
        if (epoch == 0L) epoch = journalOrNull { controller.journal.last() }?.sessionStartMs ?: 0L
        val reported = sensorStart().takeIf { it > 0L && it <= time }
        if (epoch == 0L || (reported != null && reported > epoch + 30 * 60_000L && time >= reported)) {
            epoch = reported ?: time
            resetWaiting = false
        }
        // Earlier imported sessions are kept separately from the current native
        // window. Their exact activation is unknown; use the first stored minute.
        val start = if (time < epoch) {
            journalOrNull { controller.journal.first() }?.sessionStartMs?.takeIf { it <= time } ?: time
        } else epoch
        AiDexSourceSample(time, start, factory, raw.takeIf { it.isFinite() && it >= 0f } ?: Float.NaN,
            temperature.takeIf { it.isFinite() } ?: Float.NaN, origin)
    }

    fun record(sources: Collection<AiDexSourceSample>) = controller.record(sources)

    /** Journal reads that only inform defaults must not take a reading down with them. */
    private fun <T> journalOrNull(read: () -> T): T? = runCatching(read)
        .onFailure { Log.stack("AiDexAlgorithm", "source journal read for $serial", it) }.getOrNull()

    /** A failed direct history store is repaired from the journal through the owner. */
    fun requestRoomRepair() {
        synchronized(controller.lock) {
            if (controller.isClosed()) return
            roomRepairNeeded = true
            controller.invalidateReferences()
        }
        requestReplay()
    }
    fun resetRequested() = synchronized(controller.lock) { resetWaiting = true; controller.invalidateReferences() }
    fun resetAbandoned() {
        synchronized(controller.lock) {
            if (controller.isClosed()) return
            resetWaiting = false
        }
        if (controller.needsReplay()) requestReplay()
    }
    fun isRebuilding(): Boolean = running.get() || awaitingSourceHistory

    fun sourceHistoryUnavailable(time: Long) {
        if (missingSourceTime != time || controller.isClosed()) return
        awaitingSourceHistory = false
        failed = true
        UiRefreshBus.requestStatusRefresh()
    }

    /** Only enqueue on the caller; journal, Room and publication run on the serial worker. */
    fun enqueue(work: () -> Unit): Boolean {
        if (controller.isClosed()) return false
        return runCatching {
            worker.execute {
                synchronized(controller.lock) {
                    if (controller.isClosed()) return@execute
                    runCatching(work).onFailure { Log.stack("AiDexAlgorithm", "queued sensor work", it) }
                }
            }
            true
        }.onFailure { Log.stack("AiDexAlgorithm", "cannot queue sensor work", it) }.getOrDefault(false)
    }

    fun ephemeral(source: AiDexSourceSample): AiDexAlgorithmReading = synchronized(controller.lock) {
        val row = AiDexAlgorithmReading(source, source.factoryMgDl)
        calibrateFallbacks(listOf(row), controller.appliedSelection).single()
    }

    /** The live source is already journaled; replay repairs it under current ownership. */
    fun liveRoomWriteFailed() = synchronized(controller.lock) {
        // A deferred live write during replay duplicates a batch that already
        // committed. It must not create another completion/replay loop.
        if (!controller.isClosed() && !rewritingHistory) {
            roomRepairNeeded = true
            controller.invalidateReferences()
        }
    }

    fun live(source: AiDexSourceSample,
        store: (AiDexAlgorithmReading, AiDexAlgorithmReading?) -> AiDexAlgorithmReading = storeLive): Boolean {
        val stored = synchronized(controller.lock) {
            if (controller.isClosed()) return@synchronized false
            val reading = try {
                controller.live(source, references(controller.appliedSelection), allowFactoryFallback = true)
                    .also { journalUnavailable = false }
            } catch (error: Exception) {
                // The journal is an input to later rebuilds, never a gate on the
                // reading itself: publish the factory value under the applied mode.
                if (!journalUnavailable) UiRefreshBus.requestStatusRefresh()
                journalUnavailable = true
                Log.stack("AiDexAlgorithm", "source journal write for $serial", error)
                AiDexAlgorithmReading(source, source.factoryMgDl)
                    .takeIf { source.timestampMs > (latestPublished?.source?.timestampMs ?: 0L) }
            }
            if (reading == null) {
                if (source.timestampMs >= (latestPublished?.source?.timestampMs ?: 0L)) pendingLiveTime = source.timestampMs
                return@synchronized false
            }
            recordBaseline(reading)
            val output = calibrateFallbacks(listOf(reading), controller.appliedSelection).single()
            // Storage may derive a rate from the previous reading. Install the
            // resulting snapshot BEFORE exchange resolution can consult it.
            latestPublished = store(output, latestPublished)
            if (pendingLiveTime <= output.source.timestampMs) pendingLiveTime = 0L
            storeUncertainty(listOf(output))
            publish(latestPublished!!, true)
            true
        }
        if (controller.needsReplay() && (roomRepairNeeded || everAdaptive || controller.requestedSelection.adaptive || controller.appliedSelection.adaptive)) requestReplay()
        return stored
    }

    fun select(mode: Int): Boolean {
        if (mode !in setOf(0, 8, 9)) return false
        val selection = AiDexAlgorithmSelection.fromStorage(mode)
        synchronized(controller.lock) {
            if (controller.isClosed()) return false
            if (!prefs.edit().putInt(key("algorithmMode"), mode).commit()) return false
            if (!controller.request(selection)) return false
            failed = false
        }
        requestReplay()
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    fun calibrationChanged(revision: Long) {
        synchronized(controller.lock) {
            if (calibrationRevision == revision) return
            calibrationRevision = revision
            if (!controller.requestedSelection.calibration) return
            // Completion/cache invalidations also raise the global revision.
            // Only actual reference/fit changes require another history replay.
            if (CalibrationAccess.isCalibrationStateLoaded() && calibrationInputs() == appliedCalibrationInputs) return
            controller.invalidateReferences()
        }
        requestReplay()
    }

    fun historyComplete() {
        if (everAdaptive || controller.requestedSelection.adaptive || controller.appliedSelection.adaptive || controller.needsReplay()) {
            // One completed attempt per unchanged hole. If the sensor no longer
            // has that minute, keep failing closed without a download/replay loop.
            awaitingSourceHistory = false
            controller.invalidateReferences()
            requestReplay()
        }
    }

    private fun recoverMissingSources(original: List<AiDexSourceSample>): Boolean {
        if (!everAdaptive) return false
        val held = original.mapTo(HashSet()) { it.minute }
        val missing = HistoryRepositoryAccess.getHistoryForSensor(serial, 0L, false)
            ?.filter { it.value > 0f && it.timestamp / 60_000L !in held }
            ?.minOfOrNull { it.timestamp }
        if (missing == null) {
            if (missingSourceTime != null) requestSourceHistory(null)
            missingSourceTime = null
            awaitingSourceHistory = false
            return false
        }
        if (missing != missingSourceTime) {
            missingSourceTime = missing
            awaitingSourceHistory = true
            failed = false
            Log.i("AiDexAlgorithm", "Recovering original sensor history for $serial from $missing")
            requestSourceHistory(missing)
        } else if (!awaitingSourceHistory) {
            failed = true
        }
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    private fun references(selection: AiDexAlgorithmSelection): List<AiDexReference> {
        if (!selection.calibration) return emptyList()
        // This algorithm owns Auto. Use the same eligible rows as the Auto
        // calibration UI and fallback fitter: manual Raw points stay in Raw,
        // while journal fingersticks already shared with Auto remain eligible.
        return AiDexAlgorithmReferences.decode(
            CalibrationAccess.getActiveCalibrationAnchors(serial, false), Applic.unit == 1)
    }

    private fun calibrationInputs() = CalibrationInputs(
        references(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED),
        CalibrationAccess.getIntegratedCalibrationFingerprint(serial, false),
    )

    /**
     * Stock requested and applied: write only the journal minutes Room lacks or
     * holds a different number for (a failed live/history write, a native-only
     * minute). Null when Stock does not own the history, false when the write failed.
     */
    private fun reconcileStock(): Boolean? = synchronized(controller.lock) {
        if (controller.isClosed() || controller.requestedSelection != AiDexAlgorithmSelection.STOCK ||
            controller.appliedSelection != AiDexAlgorithmSelection.STOCK) return@synchronized null
        if (HistoryRepositoryAccess.isRegistered()) {
            val stored = HistoryRepositoryAccess.getHistoryForSensor(serial, 0L, false)
                ?.associateBy { it.timestamp / 60_000L } ?: return@synchronized null
            // Both lanes: backfill can add Raw to a minute whose Auto already matches.
            val stale = controller.journal.snapshot().filter { source ->
                val held = stored[source.minute] ?: return@filter true
                kotlin.math.abs(held.value - source.factoryMgDl) > STOCK_TOLERANCE_MGDL ||
                    (source.rawMgDl.isFinite() && source.rawMgDl > 0f &&
                        kotlin.math.abs(held.rawValue - source.rawMgDl) > STOCK_TOLERANCE_MGDL)
            }
            if (stale.isNotEmpty()) {
                rewritingHistory = true
                val written = try {
                    HistorySyncAccess.storeSensorHistoryBatchBlocking(serial,
                        LongArray(stale.size) { stale[it].timestampMs },
                        FloatArray(stale.size) { stale[it].factoryMgDl },
                        FloatArray(stale.size) { stale[it].rawMgDl.takeIf { raw -> raw.isFinite() } ?: 0f })
                } finally { rewritingHistory = false }
                if (!written) {
                    // Bounded by the caller; afterwards the next live/history event retries.
                    failed = true
                    roomRepairNeeded = true
                    UiRefreshBus.requestStatusRefresh()
                    return@synchronized false
                }
                UiRefreshBus.requestDataRefresh()
            }
        }
        roomRepairNeeded = false
        failed = false
        controller.settleStock()
        true
    }

    private fun bootstrap() = synchronized(controller.lock) {
        // Until the first Adaptive write, native/Room still contain authentic
        // factory values, including holes collected by a stock-only release.
        // Afterwards every AiDex write passes through the journal first.
        if (everAdaptive || controller.isClosed() || bootstrappedThisProcess) return@synchronized
        val held = controller.journal.snapshot().mapTo(HashSet()) { it.minute }
        val room = HistoryRepositoryAccess.getHistoryForSensor(serial, 0L, false)
        val native = if (nativePointer() != 0L) {
            runCatching { Natives.getGlucoseHistoryForSensor(serial, 0L) }.getOrNull()
                ?: runCatching { Natives.getGlucoseHistoryForSensor(serial.removePrefix("X-"), 0L) }.getOrNull()
        } else null
        val values = sortedMapOf<Long, Triple<Long, Float, Float>>()
        native?.let { data ->
            for (i in data.indices step 3) if (i + 2 < data.size) {
                val time = data[i] * 1000L
                values[time / 60_000L] = Triple(time, data[i + 1] / 10f, data[i + 2] / 10f)
            }
        }
        room?.forEach { values[it.timestamp / 60_000L] = Triple(it.timestamp, it.value, it.rawValue) }
        val temperatures = AiDexTemperatureStore.loadTemperatureHistory(context, serial).associate { it.timestampMs to it.temperatureC }
        val first = values.values.firstOrNull()?.first
        if (first != null && epoch == 0L) epoch = sensorStart().takeIf { it > 0L } ?: first
        val sources = values.values.filter { it.first / 60_000L !in held && it.second.isFinite() && it.second in 1f..1022f }
            .map { (time, factory, raw) ->
                val start = if (time < epoch) first!! else epoch
                AiDexSourceSample(time, start, factory, raw.takeIf { it.isFinite() && it in 0f..6553.5f } ?: Float.NaN,
                    temperatures[time] ?: Float.NaN, AiDexSourceSample.BOOTSTRAP)
            }
        controller.record(sources)
        if (room != null || native != null) {
            check(prefs.edit().putBoolean(key("algorithmSourceBootstrapped"), true).commit())
            bootstrappedThisProcess = true
        }
    }

    fun requestReplay() {
        if (controller.isClosed()) return
        replayRequested.set(true)
        if (!running.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                try {
                    // Bounded retries; a new reading/history completion will
                    // retrigger a replay interrupted by continuously arriving data.
                    repeat(3) {
                        replayRequested.set(false)
                        if (controller.isClosed() || historyActive() || resetWaiting) return@execute
                        if (controller.requestedSelection.calibration && !CalibrationAccess.isCalibrationStateLoaded()) return@execute
                        bootstrap()
                        // Stock already owns Room/native through the direct writers.
                        // Rewriting the same factory history is work without a result.
                        when (reconcileStock()) {
                            true -> return@execute
                            false -> return@repeat // bounded retry, as for a full replay batch
                            null -> Unit
                        }
                        val original = controller.journal.snapshot()
                        val displayFactor = if (Applic.unit == 1) 1f / AiDexAdaptiveV2.MGDL_PER_MMOL else 1f
                        // Available factory anchors still own fallback fits even
                        // when a different original minute blocks full replay.
                        CalibrationAccess.seedIntegratedCalibrationBaseline(
                            FloatArray(original.size) { index -> original[index].factoryMgDl * displayFactor },
                            LongArray(original.size) { index -> original[index].timestampMs }, false, serial,
                        )
                        // A stock release advances its download cursors without
                        // writing our journal. Fetch authentic originals before
                        // computing anything; stored Auto may already be derived.
                        if (recoverMissingSources(original)) return@execute
                        if (original.isEmpty()) return@execute
                        val (ticket, inputs) = synchronized(controller.lock) {
                            val inputs = if (controller.requestedSelection.calibration) calibrationInputs() else null
                            (controller.ticket(inputs?.references ?: emptyList()) ?: return@execute) to inputs
                        }
                        val expectedPointer = nativePointer()
                        val expectedStart = sensorStart()
                        val computed = controller.compute(ticket)
                        val output = calibrateFallbacks(computed.readings, ticket.selection)
                        var mirrorFailed = false
                        val committed = controller.commit(ticket, computed) {
                            if (resetWaiting || historyActive() || nativePointer() != expectedPointer || sensorStart() != expectedStart) return@commit false
                            // Missing originals after a settings-only restore must
                            // never turn derived native values into factory inputs.
                            val history = HistoryRepositoryAccess.getHistoryForSensor(serial, 0L, false)
                            val held = ticket.sources.mapTo(HashSet()) { source -> source.minute }
                            check(!everAdaptive || history == null || history.none { point -> point.value > 0f && point.timestamp / 60_000L !in held }) {
                                "Original AiDex history is incomplete"
                            }
                            if (ticket.selection.adaptive && !everAdaptive) {
                                check(prefs.edit().putBoolean(key("algorithmEverAdaptive"), true).commit())
                                everAdaptive = true
                            }
                            ManagedSensorRuntime.clearCaches()
                            rewritingHistory = true
                            try {
                                val times = LongArray(output.size) { index -> output[index].source.timestampMs }
                                val values = FloatArray(output.size) { index -> output[index].glucoseMgDl }
                                val raws = FloatArray(output.size) { index -> output[index].source.rawMgDl.takeIf { it.isFinite() } ?: 0f }
                                if (HistoryRepositoryAccess.isRegistered() && !HistorySyncAccess.storeSensorHistoryBatchBlocking(serial, times, values, raws)) return@commit false
                                roomRepairNeeded = false
                                val retained = if (HistoryRepositoryAccess.isRegistered()) {
                                    // Replays must not put tombstoned points back in the
                                    // native stream after Room correctly filtered them.
                                    HistoryRepositoryAccess.getHistoryTimestampsForSensorOrNull(serial, 0L, Long.MAX_VALUE)
                                        ?.mapTo(HashSet()) { time -> time / 60_000L }
                                        ?: HashSet<Long>().also {
                                            // Room has committed: keep its new ownership
                                            // and retry the mirror rather than guessing
                                            // whether a missing point was deleted.
                                            mirrorFailed = true
                                            Log.e("AiDexAlgorithm", "Cannot determine retained history for $serial")
                                        }
                                } else null
                                val pointer = nativePointer()
                                if (pointer != 0L) {
                                    runCatching { output.filter { row -> row.source.timestampMs >= sensorStart() &&
                                        (retained == null || row.source.minute in retained) }.forEach { row ->
                                        Natives.aidexStoreHistoryData(pointer, row.source.timestampMs, row.glucoseMgDl, row.source.rawMgDl.takeIf { it.isFinite() } ?: 0f)
                                    } }.onFailure { error ->
                                        // Room already committed. Retain its matching calibration
                                        // ownership rather than reverting the flag around new values.
                                        Log.stack("AiDexAlgorithm", "native history mirror", error)
                                        mirrorFailed = true
                                    }
                                }
                                GlucoseUncertaintyAccess.clearForSensor(serial)
                                storeUncertainty(output)
                                output.takeLast(1500).forEach(::recordBaseline)
                                val newest = output.lastOrNull { row -> retained == null || row.source.minute in retained }
                                val deferredLive = newest != null && pendingLiveTime == newest.source.timestampMs
                                latestPublished = if (deferredLive) storeLive(newest!!, latestPublished) else newest
                                // Set before the completion revision is announced,
                                // so our own notification cannot restart this replay.
                                appliedCalibrationInputs = inputs
                                // A requested choice and the values already committed
                                // to Room have separate restart identities.
                                if (!prefs.edit().putInt(key("algorithmAppliedMode"), ticket.selection.storageId).commit())
                                    Log.e("AiDexAlgorithm", "Could not persist applied algorithm mode for $serial")
                                latestPublished?.let { row ->
                                    runCatching { publish(row, deferredLive) }.onFailure { error -> Log.stack("AiDexAlgorithm", "rebuilt current publication", error) }
                                    if (deferredLive) pendingLiveTime = 0L
                                }
                                true
                            } finally { rewritingHistory = false }
                        }
                        ManagedSensorRuntime.clearCaches()
                        if (committed) {
                            failed = mirrorFailed
                            if (mirrorFailed) controller.invalidateReferences()
                            CalibrationAccess.notifyExternalCalibrationPipelineChanged()
                            UiRefreshBus.requestDataRefresh()
                            UiRefreshBus.requestStatusRefresh()
                            return@execute
                        }
                    }
                    if (roomRepairNeeded) {
                        // No self-rescheduling on database failure. The next
                        // live/history event may retry these journaled minutes.
                        failed = true
                        UiRefreshBus.requestStatusRefresh()
                    }
                } catch (error: Throwable) {
                    failed = true
                    Log.stack("AiDexAlgorithm", "replay failed for $serial", error)
                    UiRefreshBus.requestStatusRefresh()
                } finally {
                    ManagedSensorRuntime.clearCaches()
                    running.set(false)
                    // Do not lose a mode/reference change that arrived while the
                    // previous worker was leaving its final attempt.
                    if (replayRequested.get() && !controller.isClosed()) requestReplay()
                }
            }
        }.onFailure { running.set(false); Log.stack("AiDexAlgorithm", "cannot schedule replay", it) }
    }

    private fun calibrateFallbacks(readings: List<AiDexAlgorithmReading>, selection: AiDexAlgorithmSelection): List<AiDexAlgorithmReading> {
        if (!selection.calibration) return readings
        val fallback = readings.filter { it.uncertainty == null }
        if (fallback.isEmpty()) return readings
        val factor = if (Applic.unit == 1) 1f / AiDexAdaptiveV2.MGDL_PER_MMOL else 1f
        val calibrated = CalibrationAccess.getIntegratedCalibratedSeries(
            FloatArray(readings.size) { readings[it].source.factoryMgDl * factor },
            LongArray(readings.size) { readings[it].source.timestampMs }, false, serial,
        )
        val byTime = readings.indices.filter { readings[it].uncertainty == null }.associate { index ->
            val value = calibrated.getOrNull(index)?.div(factor)?.takeIf { it.isFinite() && it > 0f } ?: readings[index].source.factoryMgDl
            readings[index].source.timestampMs to value
        }
        return readings.map { row -> byTime[row.source.timestampMs]?.let { row.copy(glucoseMgDl = it) } ?: row }
    }

    private fun recordBaseline(row: AiDexAlgorithmReading) {
        IntegratedStockBaseline.record(serial, row.source.timestampMs,
            row.source.factoryMgDl / if (Applic.unit == 1) AiDexAdaptiveV2.MGDL_PER_MMOL else 1f)
    }

    private fun storeUncertainty(rows: List<AiDexAlgorithmReading>) {
        val estimated = rows.filter { it.uncertainty != null }
        if (estimated.isEmpty()) return
        GlucoseUncertaintyAccess.storeBatch(serial,
            LongArray(estimated.size) { estimated[it].source.timestampMs },
            FloatArray(estimated.size) { estimated[it].uncertainty!!.lower90Mmol * AiDexAdaptiveV2.MGDL_PER_MMOL },
            FloatArray(estimated.size) { estimated[it].uncertainty!!.upper90Mmol * AiDexAdaptiveV2.MGDL_PER_MMOL }, 0.90f,
            FloatArray(estimated.size) { estimated[it].uncertainty!!.confidence },
            FloatArray(estimated.size) { estimated[it].uncertainty!!.artifactProbability },
        )
    }

    fun close() { controller.close(); worker.shutdownNow() }

    private companion object {
        /** Journal and Room both hold the same float; anything wider is a stale row. */
        const val STOCK_TOLERANCE_MGDL = 0.05f
    }
}
