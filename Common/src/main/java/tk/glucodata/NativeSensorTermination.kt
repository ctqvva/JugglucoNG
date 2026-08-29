package tk.glucodata

/**
 * Marks one exact native sensor record finished and verifies that the native active roster
 * dropped that exact name. Logical identity aliases are deliberately not used here: two physical
 * sensors must never make each other look active or be retired in each other's place.
 */
object NativeSensorTermination {
    enum class Result {
        CONFIRMED,
        STILL_ACTIVE,
        ACTIVE_STATE_UNAVAILABLE,
        FAILED,
    }

    internal interface Access {
        fun findSensorPointer(sensorId: String): Long
        fun finish(sensorPointer: Long)
        fun activeSensors(): Array<String>?
    }

    private object SystemAccess : Access {
        override fun findSensorPointer(sensorId: String): Long = Natives.str2sensorptr(sensorId)

        override fun finish(sensorPointer: Long) = Natives.finishfromSensorptr(sensorPointer)

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
            candidate.trim().equals(expected.trim(), ignoreCase = true)
        },
    ): Result {
        return try {
            // The displayed sensor name is the durable identity. A live callback's
            // stream pointer can be stale or can represent an aliased managed shell.
            @Suppress("UNUSED_VARIABLE")
            val ignoredLivePointer = liveDataPointer
            val sensorPointer = access.findSensorPointer(sensorId)
            if (sensorPointer != 0L) {
                access.finish(sensorPointer)
            }

            val active = access.activeSensors()
                ?: return Result.ACTIVE_STATE_UNAVAILABLE
            if (active.any { matches(it, sensorId) }) Result.STILL_ACTIVE else Result.CONFIRMED
        } catch (_: Throwable) {
            Result.FAILED
        }
    }
}
