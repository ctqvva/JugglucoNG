package tk.glucodata.ui.viewmodel

import tk.glucodata.Natives
import tk.glucodata.SensorIdentity

internal object NativeSensorTermination {
    enum class Result {
        CONFIRMED,
        STILL_ACTIVE,
        ACTIVE_STATE_UNAVAILABLE,
        FAILED,
    }

    internal interface Access {
        fun sensorPointer(sensorId: String): Long
        fun finish(sensorPointer: Long)
        fun activeSensors(): Array<String>?
    }

    private object SystemAccess : Access {
        override fun sensorPointer(sensorId: String): Long = Natives.str2sensorptr(sensorId)

        override fun finish(sensorPointer: Long) = Natives.finishfromSensorptr(sensorPointer)

        override fun activeSensors(): Array<String>? = Natives.activeSensors()
    }

    fun finishAndConfirm(
        sensorId: String,
        access: Access = SystemAccess,
        matches: (String, String) -> Boolean = { candidate, expected ->
            SensorIdentity.matches(candidate, expected)
        },
    ): Result {
        return try {
            val sensorPointer = access.sensorPointer(sensorId)
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
