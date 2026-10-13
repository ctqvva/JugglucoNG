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
import java.util.concurrent.atomic.AtomicBoolean

/** Android storage adapter. Source history always precedes selected output. */
internal class AiDexAlgorithmRuntime(
    private val context: Context,
    private val serial: String,
    private val nativePointer: () -> Long,
    private val sensorStart: () -> Long,
    private val historyActive: () -> Boolean,
    private val publish: (AiDexAlgorithmReading, Boolean) -> Unit,
) {
    private val prefs = context.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
    private fun key(name: String) = "${name}_$serial"
    val controller = AiDexAlgorithmController(
        AiDexSampleJournal(AiDexSourceJournalBackup.file(context.filesDir, serial)),
        AiDexAlgorithmSelection.fromStorage(prefs.getInt(key("algorithmAppliedMode"), prefs.getInt(key("algorithmMode"), 0))),
    )
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "AiDexAlgorithm-$serial") }
    private val running = AtomicBoolean(false)
    private val replayRequested = AtomicBoolean(false)
    private var bootstrapped = prefs.getBoolean(key("algorithmSourceBootstrapped"), false)
    private var everAdaptive = prefs.getBoolean(key("algorithmEverAdaptive"), false)
    private var epoch = 0L
    @Volatile var rewritingHistory = false
        private set
    @Volatile var latestPublished: AiDexAlgorithmReading? = null
        private set
    @Volatile var failed = false
        private set
    private var calibrationRevision = -1L
    private var resetWaiting = false
    private var pendingLiveTime = 0L

    init {
        val requested = AiDexAlgorithmSelection.fromStorage(prefs.getInt(key("algorithmMode"), 0))
        if (requested != controller.appliedSelection) controller.request(requested)
        if (everAdaptive || requested.adaptive) {
            // Reconstruct continuation and finish an interrupted Stock restoration
            // as well as an Adaptive replay after process recreation.
            controller.invalidateReferences()
            requestReplay()
        }
    }

    fun source(time: Long, factory: Float, raw: Float, temperature: Float, origin: Int): AiDexSourceSample = synchronized(controller.lock) {
        if (epoch == 0L) epoch = controller.journal.last()?.sessionStartMs ?: 0L
        val reported = sensorStart().takeIf { it > 0L && it <= time }
        if (epoch == 0L || (reported != null && reported > epoch + 30 * 60_000L && time >= reported)) {
            epoch = reported ?: time
            resetWaiting = false
        }
        // Earlier imported sessions are kept separately from the current native
        // window. Their exact activation is unknown; use the first stored minute.
        val start = if (time < epoch) {
            controller.journal.first()?.sessionStartMs?.takeIf { it <= time } ?: time
        } else epoch
        AiDexSourceSample(time, start, factory, raw.takeIf { it.isFinite() && it >= 0f } ?: Float.NaN,
            temperature.takeIf { it.isFinite() } ?: Float.NaN, origin)
    }

    fun record(sources: Collection<AiDexSourceSample>) = controller.record(sources)
    fun resetRequested() = synchronized(controller.lock) { resetWaiting = true; controller.invalidateReferences() }
    fun isRebuilding(): Boolean = running.get()

    fun ephemeral(source: AiDexSourceSample): AiDexAlgorithmReading = synchronized(controller.lock) {
        val row = AiDexAlgorithmReading(source, source.factoryMgDl)
        calibrateFallbacks(listOf(row), controller.appliedSelection).single()
    }

    fun live(source: AiDexSourceSample, storeAndPublish: (AiDexAlgorithmReading) -> AiDexAlgorithmReading): Boolean {
        val stored = synchronized(controller.lock) {
            if (controller.isClosed()) return@synchronized false
            val reading = controller.live(source, references(controller.appliedSelection))
            if (reading == null) {
                if (source.timestampMs >= (latestPublished?.source?.timestampMs ?: 0L)) pendingLiveTime = source.timestampMs
                return@synchronized false
            }
            val output = calibrateFallbacks(listOf(reading), controller.appliedSelection).single()
            recordBaseline(output)
            latestPublished = storeAndPublish(output)
            if (pendingLiveTime <= output.source.timestampMs) pendingLiveTime = 0L
            storeUncertainty(listOf(output))
            true
        }
        if (controller.needsReplay() && (everAdaptive || controller.requestedSelection.adaptive || controller.appliedSelection.adaptive)) requestReplay()
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
            controller.invalidateReferences()
        }
        requestReplay()
    }

    fun historyComplete() {
        if (everAdaptive || controller.requestedSelection.adaptive || controller.appliedSelection.adaptive) {
            controller.invalidateReferences()
            requestReplay()
        }
    }

    private fun references(selection: AiDexAlgorithmSelection): List<AiDexReference> {
        if (!selection.calibration) return emptyList()
        // A fingerstick is a reference regardless of the lane it was entered
        // against. Preserve lane enablement; prefer Auto for a duplicate minute.
        return AiDexAlgorithmReferences.decode(CalibrationAccess.getActiveCalibrationAnchors(serial, true),
            CalibrationAccess.getActiveCalibrationAnchors(serial, false), Applic.unit == 1)
    }

    private fun bootstrap() = synchronized(controller.lock) {
        if (bootstrapped || everAdaptive || controller.isClosed()) return@synchronized
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
            bootstrapped = true
            check(prefs.edit().putBoolean(key("algorithmSourceBootstrapped"), true).commit())
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
                        val original = controller.journal.snapshot()
                        if (original.isEmpty()) return@execute
                        val displayFactor = if (Applic.unit == 1) 1f / AiDexAdaptiveV2.MGDL_PER_MMOL else 1f
                        CalibrationAccess.seedIntegratedCalibrationBaseline(
                            FloatArray(original.size) { index -> original[index].factoryMgDl * displayFactor },
                            LongArray(original.size) { index -> original[index].timestampMs }, false, serial,
                        )
                        val ticket = controller.ticket(references(controller.requestedSelection)) ?: return@execute
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
                                latestPublished = output.lastOrNull { row -> retained == null || row.source.minute in retained }
                                // A requested choice and the values already committed
                                // to Room have separate restart identities.
                                if (!prefs.edit().putInt(key("algorithmAppliedMode"), ticket.selection.storageId).commit())
                                    Log.e("AiDexAlgorithm", "Could not persist applied algorithm mode for $serial")
                                latestPublished?.let { row ->
                                    val deferredLive = pendingLiveTime == row.source.timestampMs
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
            FloatArray(fallback.size) { fallback[it].source.factoryMgDl * factor },
            LongArray(fallback.size) { fallback[it].source.timestampMs }, false, serial,
        )
        val byTime = fallback.indices.associate { index ->
            val value = calibrated.getOrNull(index)?.div(factor)?.takeIf { it.isFinite() && it > 0f } ?: fallback[index].source.factoryMgDl
            fallback[index].source.timestampMs to value
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
}
