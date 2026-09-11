package tk.glucodata

/** Keeps a row's known origin stable when overlap syncs revisit the same timestamp. */
object HistorySourceProvenance {
    fun stableSource(existing: String?, incoming: String): String =
        existing?.takeIf { it.isNotBlank() } ?: incoming

    fun stableFirstStoredAt(existing: Long?, incoming: Long): Long = when {
        existing != null && existing > 0L -> existing
        incoming > 0L -> incoming
        else -> System.currentTimeMillis()
    }
}
