package tk.glucodata

/** Keeps a row's known origin stable when overlap syncs revisit the same timestamp. */
object HistorySourceProvenance {
    fun stableSource(existing: String?, incoming: String): String = when {
        existing == null -> incoming
        existing == GlucoseReadingSource.SENSOR && incoming != GlucoseReadingSource.SENSOR -> incoming
        else -> existing
    }
}
