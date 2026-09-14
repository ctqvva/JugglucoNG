package tk.glucodata.ui.viewmodel

import tk.glucodata.ui.GlucosePoint

/** A stretch of the timeline the screen holds in memory, in epoch milliseconds, inclusive. */
data class TimelineWindow(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "window ends before it starts: $startMs..$endMs" }
    }

    fun contains(startMs: Long, endMs: Long): Boolean = startMs >= this.startMs && endMs <= this.endMs
}

/**
 * How much of the store the chart holds around what it shows, and when it
 * asks for more.
 *
 * The chart is handed the viewport plus a margin on each side, so an
 * ordinary pan never reaches the edge of what is loaded, and a new window is
 * requested only when the viewport has used up half of that margin. The
 * margin is at least a day: the preview strip under the chart shows the 24
 * hours around the viewport, and it must never draw from a shorter list than
 * the chart itself. Windows snap to the hour so that two nearby requests are
 * the same request.
 *
 * The live edge is loaded separately and always — see [liveTailStart] — for
 * everything that reads the newest readings without looking at the chart:
 * the hero value, the reading rows and their deltas, the prediction, the
 * history-recovery check. It reaches back four days: every range button up
 * to 3D, with the day of margin the preview strip needs, so the ordinary use
 * of the chart never loads anything at all. A window is asked for only when
 * the viewport wants what the tail does not hold, and then a chart panned
 * back to last month holds two stretches: the month, and now.
 */
object TimelineWindowPolicy {
    const val HOUR_MS = 60L * 60L * 1000L
    const val MIN_MARGIN_MS = 24L * HOUR_MS
    /** How far back the always-loaded live tail reaches, at least: the 3D range plus the preview margin. */
    const val LIVE_TAIL_MS = 96L * HOUR_MS
    /** The tail is re-anchored when its start has drifted further than this behind now. */
    const val LIVE_TAIL_REANCHOR_MS = LIVE_TAIL_MS + 12L * HOUR_MS
    /** What the first paint loads before the whole tail lands: the default range and its margin, as the dashboard always did. */
    const val FIRST_PAINT_MS = 12L * HOUR_MS

    fun marginFor(viewportStartMs: Long, viewportEndMs: Long): Long =
        maxOf(viewportEndMs - viewportStartMs, MIN_MARGIN_MS)

    /** The window to load for a viewport: the viewport, a margin each side, snapped to the hour. */
    fun windowFor(viewportStartMs: Long, viewportEndMs: Long): TimelineWindow {
        val margin = marginFor(viewportStartMs, viewportEndMs)
        val start = Math.floorDiv(viewportStartMs - margin, HOUR_MS) * HOUR_MS
        val end = ceilToHour(viewportEndMs + margin)
        return TimelineWindow(start, end)
    }

    /**
     * Whether [current] no longer comfortably covers the viewport: the
     * viewport has eaten into the inner half of the margin on either side, or
     * has grown so that the margin it now deserves would.
     */
    fun needsNewWindow(current: TimelineWindow?, viewportStartMs: Long, viewportEndMs: Long): Boolean {
        if (current == null) return true
        val slack = marginFor(viewportStartMs, viewportEndMs) / 2
        return viewportStartMs - current.startMs < slack || current.endMs - viewportEndMs < slack
    }

    /** Where the live tail starts for a clock reading of [nowMs]: [LIVE_TAIL_MS] back, on the hour. */
    fun liveTailStart(nowMs: Long): Long = Math.floorDiv(nowMs - LIVE_TAIL_MS, HOUR_MS) * HOUR_MS

    /**
     * The window to hold for a viewport, given a live tail that starts at
     * [tailStartMs]: null when the tail already covers the viewport and its
     * margin, so no second stretch is loaded for the ordinary case; otherwise
     * [windowFor] the viewport, unless [current] still comfortably covers it.
     */
    fun windowUpdate(
        current: TimelineWindow?,
        tailStartMs: Long,
        viewportStartMs: Long,
        viewportEndMs: Long,
    ): TimelineWindow? {
        // Inside the tail only the preview strip's day of margin matters; the
        // pan margin is for stretches that have to be fetched ahead of the
        // viewport, and the tail is already there.
        if (viewportStartMs - MIN_MARGIN_MS >= tailStartMs) return null
        return if (needsNewWindow(current, viewportStartMs, viewportEndMs)) {
            windowFor(viewportStartMs, viewportEndMs)
        } else {
            current
        }
    }

    /** Whether a tail anchored at [tailStartMs] has drifted far enough behind [nowMs] to be re-anchored. */
    fun liveTailNeedsReanchor(tailStartMs: Long, nowMs: Long): Boolean =
        nowMs - tailStartMs > LIVE_TAIL_REANCHOR_MS

    private fun ceilToHour(ms: Long): Long {
        val floored = Math.floorDiv(ms, HOUR_MS) * HOUR_MS
        return if (floored == ms) ms else floored + HOUR_MS
    }
}

/**
 * The two loaded stretches as one ascending list. Where they overlap they hold
 * the same points — both are the same merge of the same store — so a point
 * present in both is taken once. Where they do not, the list simply has a
 * hole, which the chart draws as the gap it is.
 */
internal fun mergeSortedTimelines(a: List<GlucosePoint>, b: List<GlucosePoint>): List<GlucosePoint> {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    // The common case: one stretch entirely before the other.
    if (a.last().timestamp < b.first().timestamp) return a + b
    if (b.last().timestamp < a.first().timestamp) return b + a
    val out = ArrayList<GlucosePoint>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        val next = when {
            j >= b.size -> a[i++]
            i >= a.size -> b[j++]
            a[i].timestamp < b[j].timestamp -> a[i++]
            b[j].timestamp < a[i].timestamp -> b[j++]
            else -> { j++; a[i++] }
        }
        out.add(next)
    }
    return out
}
