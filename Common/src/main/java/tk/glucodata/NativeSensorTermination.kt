package tk.glucodata

/** Marks every native slot for one physical sensor inactive and verifies that the active roster dropped it. */
object NativeSensorTermination {
    enum class Result {
        CONFIRMED,
        STILL_ACTIVE,
        ACTIVE_STATE_UNAVAILABLE,
        FAILED,
    }

    internal interface Access {
        fun remove(sensorId: String): Boolean
        fun activeSensors(): Array<String>?
    }

    private object SystemAccess : Access {
        override fun remove(sensorId: String): Boolean = Natives.removeSensorById(sensorId)

        override fun activeSensors(): Array<String>? = Natives.activeSensors()
    }

    @JvmStatic
    fun removeAndConfirm(sensorId: String): Result =
        removeAndConfirm(sensorId, SystemAccess)

    internal fun removeAndConfirm(
        sensorId: String,
        access: Access,
        matches: (String, String) -> Boolean = { candidate, expected ->
            candidate.equals(expected, ignoreCase = true)
        },
    ): Result = try {
        access.remove(sensorId)
        val active = access.activeSensors()
        when {
            active == null -> Result.ACTIVE_STATE_UNAVAILABLE
            active.any { matches(it, sensorId) } -> Result.STILL_ACTIVE
            else -> Result.CONFIRMED
        }
    } catch (_: Throwable) {
        Result.FAILED
    }
}
