package tk.glucodata.share

import tk.glucodata.ExchangeTrend

/**
 * The Dexcom trend of each backfilled reading.
 *
 * A stored rate of exactly 0 is not a flat trend: it is the "no rate" placeholder the native
 * history import writes (`HistoryRepository.backfillSensor`). Reading it as a rate would send a
 * confident Flat arrow for every reading of a gap, however fast glucose was moving, which is worse
 * than sending no arrow. So 0 counts as missing, the same rule [tk.glucodata.XdripBackfill] follows,
 * and the trend is derived from the nearest earlier reading far enough back for
 * [ExchangeTrend.deriveRate] to accept (minute-spaced rows are too close to say anything).
 */
object DexcomShareBackfillTrend {

    /** A stored reading: the mg/dL a live send would carry, and the rate the row was stored with. */
    data class Source(val timeMs: Long, val mgdl: Int, val rate: Float?)

    fun rows(sources: List<Source>): List<DexcomShareUploader.Row> {
        val sorted = sources.sortedBy(Source::timeMs)
        return sorted.mapIndexed { index, current ->
            DexcomShareUploader.Row(current.timeMs, current.mgdl, trendIndex(sorted, index))
        }
    }

    private fun trendIndex(sorted: List<Source>, index: Int): Int {
        val current = sorted[index]
        val ownRate = current.rate
        if (ownRate != null && ownRate.isFinite() && ownRate != 0f) {
            return ExchangeTrend.fromRate(ownRate).index
        }
        for (earlier in index - 1 downTo 0) {
            val previous = sorted[earlier]
            if (current.timeMs - previous.timeMs > ExchangeTrend.MAX_DERIVE_GAP_MS) break
            val rate = ExchangeTrend.deriveRate(previous.mgdl, previous.timeMs, current.mgdl, current.timeMs)
            if (!rate.isNaN()) return ExchangeTrend.fromRate(rate).index
        }
        return ExchangeTrend.UNKNOWN
    }
}
