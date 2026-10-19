package tk.glucodata

object DisplayDataState {
    enum class Kind {
        NO_SENSOR,
        AWAITING_DATA,
        FRESH,
        STALE
    }

    data class Status(
        val kind: Kind,
        val sensorPresent: Boolean,
        val latestTimestampMillis: Long,
        val ageMillis: Long
    ) {
        val hasData: Boolean get() = latestTimestampMillis > 0L
        val isFresh: Boolean get() = kind == Kind.FRESH
        val isStale: Boolean get() = kind == Kind.STALE
        val isAwaitingData: Boolean get() = kind == Kind.AWAITING_DATA
    }

    @JvmStatic
    @JvmOverloads
    fun resolve(
        sensorPresent: Boolean,
        currentTimestampMillis: Long,
        latestHistoryTimestampMillis: Long,
        freshnessWindowMillis: Long = Notify.glucosetimeout,
        nowMillis: Long = System.currentTimeMillis()
    ): Status {
        val latestTimestampMillis = maxOf(currentTimestampMillis, latestHistoryTimestampMillis)
        val resolvedNowMillis = if (nowMillis > 0L) nowMillis else System.currentTimeMillis()
        val ageMillis = if (latestTimestampMillis > 0L) {
            (resolvedNowMillis - latestTimestampMillis).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }

        val kind = when {
            !sensorPresent -> Kind.NO_SENSOR
            latestTimestampMillis <= 0L -> Kind.AWAITING_DATA
            ageMillis <= freshnessWindowMillis -> Kind.FRESH
            else -> Kind.STALE
        }

        return Status(
            kind = kind,
            sensorPresent = sensorPresent,
            latestTimestampMillis = latestTimestampMillis,
            ageMillis = ageMillis
        )
    }
}
