package tk.glucodata

/**
 * Standard title/text for glucose notifications that other apps read: the ongoing
 * notification when standard text is on, and the periodic notification. One formatter,
 * so a watch shows the same reading the same way whichever notification it forwarded.
 * The values come pre-formatted from the display snapshot; only the joining is here.
 */
object GlucoseNotificationText {
    private const val SEPARATOR = " · "

    /** "9,0 mmol/L ↗", or "5,7 · 5,5 mmol/L ↗" with peer sensors. */
    @JvmStatic
    fun title(value: CharSequence?, peers: List<CharSequence?>?, unit: String?, rateMgdlPerMinute: Float): String {
        val text = StringBuilder(value?.toString().orEmpty())
        peers?.forEach { peer ->
            if (!peer.isNullOrEmpty()) text.append(SEPARATOR).append(peer)
        }
        if (text.isEmpty()) return ""
        if (!unit.isNullOrEmpty()) text.append(' ').append(unit)
        val arrow = ExchangeTrend.fromRate(rateMgdlPerMinute).arrow
        if (arrow.isNotEmpty()) text.append(' ').append(arrow)
        return text.toString()
    }

    /** "Δ +0,2 · 12:40": the change, then when the reading was measured. */
    @JvmStatic
    fun periodicText(deltaText: String?, readingTime: String): String =
        if (deltaText.isNullOrEmpty()) readingTime else "Δ $deltaText$SEPARATOR$readingTime"
}
