package tk.glucodata

/**
 * Standard title of the ongoing notification for apps that read it (watch companions),
 * with standard text or on a periodic update. The values come pre-formatted from the
 * display snapshot; only the joining is here.
 */
object GlucoseNotificationText {
    private const val SEPARATOR = " · "

    /** "9,0 ↗", or "5,7 · 5,5 ↗" with peer sensors. */
    @JvmStatic
    fun title(value: CharSequence?, peers: List<CharSequence?>?, rateMgdlPerMinute: Float): String {
        val text = StringBuilder(value?.toString().orEmpty())
        peers?.forEach { peer ->
            if (!peer.isNullOrEmpty()) text.append(SEPARATOR).append(peer)
        }
        if (text.isEmpty()) return ""
        val arrow = ExchangeTrend.fromRate(rateMgdlPerMinute).arrow
        if (arrow.isNotEmpty()) text.append(' ').append(arrow)
        return text.toString()
    }
}
