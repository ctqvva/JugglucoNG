package tk.glucodata.drivers.aidex

import tk.glucodata.drivers.aidex.adaptive2.AiDexAdaptiveSample
import tk.glucodata.drivers.aidex.adaptive2.AiDexAdaptiveV2
import tk.glucodata.drivers.aidex.adaptive2.AiDexReference
import tk.glucodata.drivers.aidex.adaptive2.ProbabilisticGlucoseEstimate

internal data class AiDexAlgorithmReading(
    val source: AiDexSourceSample,
    val glucoseMgDl: Float,
    val uncertainty: ProbabilisticGlucoseEstimate? = null,
    val rateMgDlPerMinute: Float = Float.NaN,
)

internal class AiDexAlgorithmContext(val selection: AiDexAlgorithmSelection) {
    private val model = AiDexAdaptiveV2()
    private var previous: AiDexAlgorithmReading? = null
    fun process(source: AiDexSourceSample, references: List<AiDexReference>): AiDexAlgorithmReading {
        val result = if (selection.adaptive) model.process(
            AiDexAdaptiveSample(source.timestampMs, source.sessionStartMs, source.rawMgDl, source.factoryMgDl, source.temperatureC),
            if (selection.calibration) references.filter { it.timestampMs <= source.timestampMs &&
                it.timestampMs >= source.sessionStartMs && source.timestampMs - it.timestampMs <= 60_000L } else emptyList(),
        ) else null
        // Broadcasts and brief-only backfill have no raw signal. Keep a clearly
        // defined factory fallback, with no fabricated estimate or interval.
        val reading = AiDexAlgorithmReading(source,
            result?.estimate?.glucoseMmol?.times(AiDexAdaptiveV2.MGDL_PER_MMOL) ?: source.factoryMgDl,
            result?.estimate,
        )
        return reading.copy(rateMgDlPerMinute = AiDexLiveRate.between(previous, reading)).also { previous = it }
    }
}

/** A live rate is a difference between neighbouring readings of the same kind, or unknown. */
internal object AiDexLiveRate {
    /** Beyond this the difference describes a dropout, not the current trend. */
    const val MAX_GAP_MS = 5 * 60_000L

    fun between(previous: AiDexAlgorithmReading?, current: AiDexAlgorithmReading): Float {
        val earlier = previous ?: return Float.NaN
        val gap = current.source.timestampMs - earlier.source.timestampMs
        // Model and factory fallback differ by their calibration, not by glucose.
        if (gap <= 0L || gap > MAX_GAP_MS || earlier.source.sessionStartMs != current.source.sessionStartMs ||
            (earlier.uncertainty == null) != (current.uncertainty == null)) return Float.NaN
        return (current.glucoseMgDl - earlier.glucoseMgDl) / (gap / 60_000f)
    }

    /** A sensor trend byte wins in Stock; otherwise the bounded difference, else unknown. */
    fun resolve(stockTrendMgDlPerMinute: Float, previous: AiDexAlgorithmReading?, current: AiDexAlgorithmReading): Float =
        stockTrendMgDlPerMinute.takeIf { it.isFinite() }
            ?: between(previous, current).takeIf { it.isFinite() }
            ?: current.rateMgDlPerMinute.takeIf { it.isFinite() }
            ?: Float.NaN
}

/** One ownership lock covers source changes, selection and every storage/publication commit. */
internal class AiDexAlgorithmController(
    val journal: AiDexSampleJournal,
    initial: AiDexAlgorithmSelection,
) {
    val lock = Any()
    @Volatile var requestedSelection = initial
        private set
    @Volatile var appliedSelection = initial
        private set
    private var generation = 0L
    @Volatile private var closed = false
    private var context: AiDexAlgorithmContext? = if (initial.adaptive) null else AiDexAlgorithmContext(initial)
    private var lastProcessedTime = 0L
    private var replayNeeded = initial.adaptive
    var latest: AiDexAlgorithmReading? = null
        private set

    data class Ticket(val generation: Long, val selection: AiDexAlgorithmSelection,
        val sources: List<AiDexSourceSample>, val references: List<AiDexReference>)
    data class Replay(val context: AiDexAlgorithmContext, val readings: List<AiDexAlgorithmReading>)

    fun request(selection: AiDexAlgorithmSelection): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        requestedSelection = selection
        generation++
        replayNeeded = true
        true
    }

    fun invalidateReferences() = synchronized(lock) { if (!closed) { generation++; replayNeeded = true } }

    /** Stock with Stock applied: Room and native already hold the factory values. */
    fun settleStock(): Boolean = synchronized(lock) {
        if (closed || requestedSelection != AiDexAlgorithmSelection.STOCK || appliedSelection != AiDexAlgorithmSelection.STOCK) {
            false
        } else {
            replayNeeded = false
            true
        }
    }
    fun needsReplay(): Boolean = synchronized(lock) { !closed && replayNeeded }
    // Enqueuing from a Bluetooth callback must never wait for a Room writer.
    fun isClosed(): Boolean = closed

    fun record(sources: Collection<AiDexSourceSample>): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val changed = journal.appendAll(sources) > 0
        if (changed) {
            generation++
            if (lastProcessedTime > 0L && sources.any { it.minute <= lastProcessedTime / 60_000L }) replayNeeded = true
        }
        changed
    }

    /** Must be called with storage/publication in the same lock scope by the adapter. */
    fun live(source: AiDexSourceSample, references: List<AiDexReference>, allowFactoryFallback: Boolean = false): AiDexAlgorithmReading? = synchronized(lock) {
        if (closed) return@synchronized null
        val changed = record(listOf(source))
        if (source.timestampMs <= lastProcessedTime || latest?.source?.minute == source.minute) {
            if (changed && latest?.source?.minute == source.minute) {
                if (appliedSelection.adaptive && context != null) return@synchronized null // replay a revised minute from original inputs
                val current = journal.atMinute(source.minute) ?: return@synchronized null
                return@synchronized AiDexAlgorithmReading(current, current.factoryMgDl).also {
                    latest = it; lastProcessedTime = maxOf(lastProcessedTime, current.timestampMs)
                }
            }
            return@synchronized latest?.takeIf { it.source.minute == source.minute }
        }
        val reading = context?.process(source, references) ?: if (allowFactoryFallback) {
            // A historical hole must not stop fresh factory readings. Ownership
            // remains the applied mode until a complete restoration commits.
            AiDexAlgorithmReading(source, source.factoryMgDl)
        } else return@synchronized null
        lastProcessedTime = source.timestampMs
        latest = reading
        reading
    }

    fun ticket(references: List<AiDexReference>): Ticket? = synchronized(lock) {
        if (closed) null else Ticket(generation, requestedSelection, journal.snapshot(), references.toList())
    }

    /** Computation has no side effects and can be abandoned at any point. */
    fun compute(ticket: Ticket): Replay {
        val model = AiDexAlgorithmContext(ticket.selection)
        // Use the same reference window as live. The estimator consumes accepted
        // references once; a rawless/invalid minute must not consume its anchor.
        val readings = ticket.sources.map { source -> model.process(source, ticket.references) }
        return Replay(model, readings)
    }

    /** Validate BEFORE either store is touched, while live writes/removal are excluded. */
    fun commit(ticket: Ticket, replay: Replay, write: (List<AiDexAlgorithmReading>) -> Boolean): Boolean = synchronized(lock) {
        if (closed || generation != ticket.generation || requestedSelection != ticket.selection || journal.snapshot() != ticket.sources)
            return@synchronized false
        val previous = appliedSelection
        appliedSelection = ticket.selection
        val stored = try { write(replay.readings) } catch (error: Throwable) { appliedSelection = previous; throw error }
        if (!stored) { appliedSelection = previous; return@synchronized false }
        context = replay.context
        latest = replay.readings.lastOrNull()
        lastProcessedTime = latest?.source?.timestampMs ?: 0L
        replayNeeded = false
        true
    }

    /** Drain all writers before a native shell can be finished/deleted. */
    fun close() = synchronized(lock) { closed = true; generation++; context = null }
}
