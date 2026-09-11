package tk.glucodata.data

/**
 * Makes the recorded owner win the main line for the minutes it was recorded
 * for.
 *
 * [HistoryDisplayMerge] decides who owns each minute from facts that are alive
 * at query time — which sensor is preferred *now*, which read most recently.
 * That is the right answer for the minutes still settling and the wrong one for
 * every minute the user has already been shown: it lets a main-sensor swap
 * today rewrite which sensor drew 02:00. The record stores the owner for
 * exactly this reason, and until now nothing read it — the merge picked a
 * sensor, and only the recorded *value* was painted over its reading, so the
 * main line's identity followed the current selection everywhere and the
 * freeze could adjust a number but never who owned the minute.
 *
 * This runs after the merge and substitutes, for each minute that has a sealed
 * record, the recorded owner's own reading in place of the merge's choice.
 * Where the owner has no reading for that minute the merge's choice stands,
 * carrying the recorded value as before — better a value on the wrong line than
 * a hole in the line.
 *
 * Pure on purpose, so the rule has one definition and a test.
 */
internal object RecordedOwnerMerge {

    fun apply(
        merged: List<HistoryReading>,
        raw: List<HistoryReading>,
        display: Map<Long, ReadingDisplay>,
        nowMs: Long,
    ): List<HistoryReading> {
        if (merged.isEmpty() || display.isEmpty()) return merged

        // Only the minutes that have a sealed record can be re-owned, and only
        // those need an index of the raw readings — which is the whole list, so
        // build it once, keyed by (minute, sensor), and only for sealed minutes.
        val sealedMinutes = HashSet<Long>(display.size)
        for ((minute, record) in display) {
            if (record.isUsable && record.isSealedAt(nowMs)) sealedMinutes.add(minute)
        }
        if (sealedMinutes.isEmpty()) return merged

        val byMinuteAndSensor = HashMap<Pair<Long, String>, HistoryReading>()
        for (reading in raw) {
            val minute = ReadingDisplay.minuteOf(reading.timestamp)
            if (minute !in sealedMinutes) continue
            val serial = reading.sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // First reading wins within a minute for a sensor; the merge's own
            // bucket collapse picks the same way, so this stays consistent.
            byMinuteAndSensor.putIfAbsent(minute to serial, reading)
        }

        return merged.map { chosen ->
            val minute = ReadingDisplay.minuteOf(chosen.timestamp)
            val record = display[minute] ?: return@map chosen
            if (!record.isUsable || !record.isSealedAt(nowMs)) return@map chosen
            val owner = record.sensorSerial.trim().takeIf { it.isNotEmpty() } ?: return@map chosen
            val chosenSerial = chosen.sensorSerial?.trim()
            if (chosenSerial != null && tk.glucodata.SensorIdentity.matches(chosenSerial, owner)) {
                return@map chosen
            }
            byMinuteAndSensor[minute to owner]
                ?: byMinuteAndSensor.entries.firstOrNull { (key, _) ->
                    key.first == minute && tk.glucodata.SensorIdentity.matches(key.second, owner)
                }?.value
                ?: chosen
        }
    }
}
