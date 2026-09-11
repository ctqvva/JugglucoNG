package tk.glucodata.ui

import tk.glucodata.data.HistoryRepository
import java.util.Collections

/**
 * Records the main value for minutes that were actually put in front of the user.
 *
 * **Visible, not loaded.** This is the whole reason the recorder exists rather
 * than the repository being called from wherever data arrives. The chart holds
 * far more than it draws: the merge needs the entire stored timeline to rank
 * sensors correctly, queries run ahead of the viewport, and panning keeps points
 * alive on both sides of the screen. None of that was presented to anyone.
 * Recording a minute the chart merely *had* would freeze today's opinion onto a
 * moment nobody looked at — the same backdating a background pass does, with a
 * scroll as the trigger instead of a timer.
 *
 * So the only input is the slice the chart just drew, and the only caller is the
 * draw path's own viewport. A minute enters the record when it appears on
 * screen, and not before.
 *
 * **Settling.** A pan sweeps thousands of minutes past the viewport in a second;
 * a glance at them is not a presentation, and writing each frame would be both
 * dishonest and expensive. [SETTLE_MS] is how long the viewport must hold still
 * before what it shows counts as looked at.
 *
 * **Once.** [presented] remembers what this process has already written, so
 * holding still, or returning to a stretch, costs nothing. The set is per
 * process on purpose: it is a write-amplification guard, not the guarantee. The
 * guarantee is in the database — a minute past its grace window cannot be
 * revised, whatever is submitted.
 */
internal object PresentedMinuteRecorder {

    /**
     * How long the viewport must be still before its contents count as seen.
     *
     * Long enough that flinging across three days records nothing it passed over,
     * short enough that stopping to read a stretch registers immediately.
     */
    const val SETTLE_MS = 700L

    private val presented = Collections.synchronizedSet(HashSet<Long>())

    /** Forgets the per-process guard. For tests, and for a store that was cleared. */
    fun reset() = presented.clear()

    /**
     * @param visible the minutes the chart has just drawn, in mg/dL.
     * @return how many were newly recorded.
     */
    suspend fun recordVisible(
        repository: HistoryRepository,
        visible: List<HistoryRepository.PresentedMinute>,
    ): Int {
        if (visible.isEmpty()) return 0
        // A minute still inside its grace window is resubmitted every pass, since
        // its value can still change; the database decides whether that lands.
        val unseen = visible.filter { it.minuteMs !in presented }
        if (unseen.isEmpty()) return 0
        val recorded = repository.recordPresentedMinutes(unseen)
        presented.addAll(unseen.map { it.minuteMs })
        return recorded
    }

    /**
     * Drops minutes still inside the grace window from the guard, so the next
     * pass resubmits them with whatever they now show.
     */
    fun releaseUnsealed(sealHorizonMs: Long) {
        synchronized(presented) {
            presented.removeAll { it > sealHorizonMs }
        }
    }
}
