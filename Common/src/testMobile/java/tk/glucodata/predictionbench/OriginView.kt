package tk.glucodata.predictionbench

import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.AdaptiveHistory
import tk.glucodata.data.prediction.PredictionModelProfile

/**
 * Which treatments a forecaster is told about.
 *
 * The distinction is the one between a prediction and a what-if simulation. A prediction
 * may only use entries that existed when it was made; a what-if is handed the treatments
 * that actually happened, including ones logged afterwards, and so measures the treatment
 * model rather than the forecast a user would have seen.
 */
enum class JournalMode {
    /** No journal at all: the CGM-only forecast. */
    NONE,

    /**
     * Entries created — and not edited since — by the forecast moment. Future-dated entries
     * qualify (a meal logged ahead is real information); a meal logged twenty minutes late
     * does not, nor does an entry whose current values came from a later edit, because only
     * the latest version is stored.
     */
    OPERATIONAL,

    /** Every entry up to the horizon, whenever it was logged: a what-if, not a prediction. */
    ORACLE,
}

/**
 * Everything a forecaster may read at one forecast moment, and nothing more.
 *
 * Readings of the lane up to and including [end]; every accessor refuses an index past it,
 * so a forecaster cannot read the future by accident. Learners that build training sets from
 * the past must also take their targets through this view, which keeps those targets at or
 * before [originTime].
 */
class OriginView internal constructor(
    private val lane: Lane,
    val end: Int,
    val journal: List<JournalEntry>,
    val presets: Map<Long, JournalInsulinPreset>,
    val unit: String,
    /** The prediction profile in force at the origin; null = the app's defaults. */
    val profile: PredictionModelProfile? = null,
    private val history: LaneJournal? = null,
    private val mode: JournalMode = JournalMode.NONE,
) {
    init {
        require(end in 0 until lane.size)
    }

    val laneId: String get() = lane.id
    val sensor: String get() = lane.sensor
    val isMmol: Boolean get() = lane.isMmol
    val originTime: Long = lane.times[end]
    val size: Int get() = end + 1
    val cadenceMinutes: Double get() = lane.cadenceMinutes

    fun time(index: Int): Long = lane.times[checked(index)]
    fun display(index: Int): Float = lane.display[checked(index)]
    fun mgdl(index: Int): Float = lane.mgdl(checked(index))

    /** Last visible index with time ≤ [t], or -1. */
    fun indexAtOrBefore(t: Long): Int {
        var lo = 0
        var hi = end
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lane.times[mid] <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /**
     * mg/dL at [t] by linear interpolation of visible readings at most [maxGapMs] apart, or the
     * reading itself when one sits on [t]. NaN when [t] is after [originTime] or in a hole.
     */
    fun mgdlAt(t: Long, maxGapMs: Long): Float {
        if (t > originTime) return Float.NaN
        val i = indexAtOrBefore(t)
        if (i < 0) return Float.NaN
        if (lane.times[i] == t) return lane.mgdl(i)
        if (i + 1 > end) return Float.NaN
        val t0 = lane.times[i]
        val t1 = lane.times[i + 1]
        if (t1 - t0 > maxGapMs) return Float.NaN
        val f = (t - t0).toFloat() / (t1 - t0).toFloat()
        return lane.mgdl(i) + (lane.mgdl(i + 1) - lane.mgdl(i)) * f
    }

    /** Mean mg/dL of visible readings in [fromMs, toMs], or NaN when there are none. */
    fun meanMgdl(fromMs: Long, toMs: Long): Float {
        val hi = indexAtOrBefore(minOf(toMs, originTime))
        if (hi < 0) return Float.NaN
        val lo = indexAtOrBefore(fromMs - 1) + 1
        if (lo > hi) return Float.NaN
        val p = lane.prefixMgdl
        return ((p[hi + 1] - p[lo]) / (hi + 1 - lo)).toFloat()
    }

    /**
     * Insulin and carbs on board at [t] ≤ [originTime], counting only entries known at the
     * origin under [mode]. What a learner may use, for its past examples and for the present.
     */
    fun onBoard(t: Long): OnBoard {
        if (t > originTime || history == null || mode == JournalMode.NONE) return OnBoard.NONE
        return history.onBoard(t, knownAt = if (mode == JournalMode.ORACLE) Long.MAX_VALUE else originTime)
    }

    /**
     * The visible past from [fromMs] to the origin as the production adaptive forecast reads
     * it, in mg/dL with sensor segments.
     */
    fun adaptiveHistory(fromMs: Long): AdaptiveHistory {
        val lo = indexAtOrBefore(fromMs - 1) + 1
        val n = size - lo
        return AdaptiveHistory(
            LongArray(n) { lane.times[lo + it] },
            FloatArray(n) { lane.mgdl(lo + it) },
            IntArray(n) { lane.segmentAt(lo + it) },
        )
    }

    private val memos = HashMap<String, Any?>()

    /**
     * Shares one computation between the forecasters at this moment (the smoothed history,
     * the arrow's velocity). Whatever [compute] reads, it reads through this view.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T> memo(key: String, compute: () -> T): T {
        if (memos.containsKey(key)) return memos[key] as T
        return compute().also { memos[key] = it }
    }

    private fun checked(index: Int): Int {
        if (index < 0 || index > end) throw IndexOutOfBoundsException("index $index is outside the visible past 0..$end")
        return index
    }

    companion object {
        /** The journal as [mode] allows it at [originTime], for a forecast reaching [horizonMs] ahead. */
        fun journalFor(
            all: List<JournalEntry>,
            mode: JournalMode,
            originTime: Long,
            horizonMs: Long,
            lookbackMs: Long = 36L * 60 * 60_000,
        ): List<JournalEntry> = when (mode) {
            JournalMode.NONE -> emptyList()
            JournalMode.OPERATIONAL -> all.filter {
                it.timestamp in (originTime - lookbackMs)..(originTime + horizonMs) &&
                    it.createdAt <= originTime && it.updatedAt <= originTime
            }
            JournalMode.ORACLE -> all.filter { it.timestamp in (originTime - lookbackMs)..(originTime + horizonMs) }
        }
    }
}
