package tk.glucodata

/**
 * Marks one native sensor finished and verifies that the native active roster dropped it.
 *
 * Live callbacks must supply their existing stream pointer: it owns the exact sensors.dat index
 * used by [Natives.finishSensor]. A callback-free record temporarily acquires the same kind of
 * stream pointer through [Natives.getdataptr] and releases it after the finish attempt.
 */
object NativeSensorTermination {
    enum class Result {
        CONFIRMED,
        STILL_ACTIVE,
        ACTIVE_STATE_UNAVAILABLE,
        FAILED,
    }

    internal interface Access {
        fun acquireDataPointer(sensorId: String): Long
        fun finish(dataPointer: Long)
        fun releaseDataPointer(dataPointer: Long)
        fun activeSensors(): Array<String>?
    }

    private object SystemAccess : Access {
        override fun acquireDataPointer(sensorId: String): Long = Natives.getdataptr(sensorId)

        override fun finish(dataPointer: Long) = Natives.finishSensor(dataPointer)

        override fun releaseDataPointer(dataPointer: Long) = Natives.freedataptr(dataPointer)

        override fun activeSensors(): Array<String>? = Natives.activeSensors()
    }

    @JvmStatic
    fun finishAndConfirm(sensorId: String, liveDataPointer: Long): Result =
        finishAndConfirm(sensorId, liveDataPointer, SystemAccess)

    internal fun finishAndConfirm(
        sensorId: String,
        liveDataPointer: Long,
        access: Access,
        matches: (String, String) -> Boolean = { candidate, expected ->
            SensorIdentity.matches(candidate, expected)
        },
    ): Result {
        var acquiredDataPointer = 0L
        return try {
            val dataPointer = if (liveDataPointer != 0L) {
                liveDataPointer
            } else {
                access.acquireDataPointer(sensorId).also { acquiredDataPointer = it }
            }
            if (dataPointer != 0L) {
                access.finish(dataPointer)
            }

            val active = access.activeSensors()
                ?: return Result.ACTIVE_STATE_UNAVAILABLE
            if (active.any { matches(it, sensorId) }) Result.STILL_ACTIVE else Result.CONFIRMED
        } catch (_: Throwable) {
            Result.FAILED
        } finally {
            if (acquiredDataPointer != 0L) {
                runCatching { access.releaseDataPointer(acquiredDataPointer) }
            }
        }
    }
}
