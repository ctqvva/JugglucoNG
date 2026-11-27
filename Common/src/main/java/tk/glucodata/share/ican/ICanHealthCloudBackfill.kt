package tk.glucodata.share.ican

data class BackfillResult(
    val count: Int = 0,
)

object ICanHealthCloudBackfill {

    fun historyStart(settings: ICanHealthCloudSettings, nowMs: Long, days: Int = 7): Long =
        settings.backfillProgressMs.takeIf { it > 0L }
            ?: (nowMs - days.toLong() * 24L * 60L * 60L * 1000L)

    data class Reading(
        val timeMs: Long,
        val mgdl: Int,
        val trend: Int = 0,
    )

    interface GlucoseSource {
        fun getReadings(fromMs: Long, toMs: Long): List<Reading>
    }

    fun backfill(
        client: ICanHealthCloudClient,
        settings: ICanHealthCloudSettings,
        glucoseSource: GlucoseSource,
        days: Int = 7,
    ): BackfillResult {
        return backfill(client, settings, null, glucoseSource, days)
    }

    fun backfill(
        client: ICanHealthCloudClient,
        settings: ICanHealthCloudSettings,
        queue: ICanHealthCloudQueue?,
        glucoseSource: GlucoseSource,
        days: Int = 7,
    ): BackfillResult {
        val now = System.currentTimeMillis()
        val fromMs = historyStart(settings, now, days)
        val toMs = now
        val readings = glucoseSource.getReadings(fromMs, toMs)
        if (queue == null) {
            return BackfillResult(readings.count { it.timeMs > fromMs })
        }
        val pending = readings.filter { it.timeMs > fromMs }
        val accepted = queue.enqueueAll(pending.map {
            EgvUpload(serial = "", timeMs = it.timeMs, mgdl = it.mgdl, trend = it.trend)
        })
        if (accepted > 0 && !queue.hasUnpersistedRows()) {
            settings.backfillProgressMs = maxOf(settings.backfillProgressMs, pending.maxOf { it.timeMs })
        }
        return BackfillResult(accepted)
    }
}

typealias ICanHealthCloudBackfillResult = BackfillResult
