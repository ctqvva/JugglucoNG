package tk.glucodata.data.calibration

/**
 * Which calibration points appeared and which went, between two loads of the table.
 *
 * Compared by what a point does to the line, never by row id: a mirror sync or
 * a profile import rewrites unchanged points under new ids, and that must not
 * look like the user removing them. An edit is the old point going and a new
 * one arriving, which is what it means for anything recorded under the old one.
 *
 * The sensor is left out on purpose. A sensor replacement re-files old points
 * under an archive id, and a point that merely moved is still in force for the
 * minutes it was in force for.
 */
internal object CalibrationPointChanges {

    data class Changes(
        val gone: List<CalibrationEntity>,
        val arrived: List<CalibrationEntity>,
    ) {
        val isEmpty: Boolean get() = gone.isEmpty() && arrived.isEmpty()
    }

    private data class Effect(
        val timestamp: Long,
        val isRawMode: Boolean,
        val sensorValue: Float,
        val sensorValueRaw: Float,
        val userValue: Float,
        val isEnabled: Boolean,
    )

    private fun CalibrationEntity.effect() =
        Effect(timestamp, isRawMode, sensorValue, sensorValueRaw, userValue, isEnabled)

    fun between(before: List<CalibrationEntity>, after: List<CalibrationEntity>): Changes {
        // Two units in play is the display unit changing under the table, not a
        // calibration change, and the converted floats would not compare equal.
        if ((before + after).distinctBy { it.valueUnit }.size > 1) return Changes(emptyList(), emptyList())
        val had = before.mapTo(HashSet()) { it.effect() }
        val has = after.mapTo(HashSet()) { it.effect() }
        return Changes(
            gone = before.filter { it.effect() !in has },
            arrived = after.filter { it.effect() !in had },
        )
    }
}
