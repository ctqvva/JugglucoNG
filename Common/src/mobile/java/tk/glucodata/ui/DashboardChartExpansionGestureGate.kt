package tk.glucodata.ui

/**
 * Keeps chart expansion owned by a deliberate pull-down gesture that began at
 * the top of the dashboard. Fling/animation deltas and gestures that merely
 * reach the top remain part of the list scroll that produced them.
 */
internal class DashboardChartExpansionGestureGate {
    private var gestureActive = false
    private var startedAtTop = false
    private var expansionIntent = false
    private var intentResolved = false

    fun onGestureStarted(startedAtTop: Boolean, chartExpanded: Boolean) {
        gestureActive = true
        this.startedAtTop = startedAtTop
        expansionIntent = startedAtTop && chartExpanded
        intentResolved = chartExpanded || !startedAtTop
    }

    /** The first direct scroll delta decides whether a collapsed chart may expand. */
    fun onDirectScrollDelta(deltaY: Float) {
        if (!gestureActive || intentResolved || deltaY == 0f) return
        expansionIntent = startedAtTop && deltaY > 0f
        intentResolved = true
    }

    fun allowsExpansion(directUserInput: Boolean): Boolean =
        directUserInput && gestureActive && expansionIntent

    fun onGestureEnded() {
        gestureActive = false
        startedAtTop = false
        expansionIntent = false
        intentResolved = false
    }
}
