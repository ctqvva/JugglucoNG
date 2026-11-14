package tk.glucodata.share.ican

data class BackfillResult(
    val count: Int = 0,
)

object ICanHealthCloudBackfill {

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
        val fromMs = if (settings.lastSyncMs > 0L) {
            settings.lastSyncMs
        } else {
            now - days.toLong() * 24L * 60L * 60L * 1000L
        }
        val toMs = now
        val readings = glucoseSource.getReadings(fromMs, toMs)
        if (queue == null) {
            var count = 0
            for (reading in readings) {
                if (settings.lastSyncMs > 0L && reading.timeMs <= settings.lastSyncMs) {
                    continue
                }
                count++
            }
            return BackfillResult(count)
        }
        var count = 0
        for (reading in readings) {
            if (settings.lastSyncMs > 0L && reading.timeMs <= settings.lastSyncMs) {
                continue
            }
            val clamped = reading.mgdl.coerceIn(40, 400)
            queue.enqueue(EgvUpload(serial = "", timeMs = reading.timeMs, mgdl = clamped, trend = reading.trend))
            count++
        }
        return BackfillResult(count)
    }
}

typealias ICanHealthCloudBackfillResult = BackfillResult
