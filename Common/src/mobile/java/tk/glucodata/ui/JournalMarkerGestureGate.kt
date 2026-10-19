package tk.glucodata.ui

/** Keeps a journal marker tap distinct from a chart drag that starts on the marker. */
internal class JournalMarkerGestureGate(private val touchSlop: Float) {
    private var tapCandidate = touchSlop.isFinite() && touchSlop >= 0f

    fun observe(displacementX: Float, displacementY: Float, pointerCount: Int) {
        if (!tapCandidate) return
        if (pointerCount != 1 || !displacementX.isFinite() || !displacementY.isFinite()) {
            tapCandidate = false
            return
        }

        val distanceSquared = displacementX * displacementX + displacementY * displacementY
        if (distanceSquared >= touchSlop * touchSlop) {
            tapCandidate = false
        }
    }

    fun cancel() {
        tapCandidate = false
    }

    fun shouldClick(released: Boolean): Boolean = released && tapCandidate
}
