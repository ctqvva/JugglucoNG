// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

internal class CareSensAirSession(
    private val journal: CareSensAirJournal,
    val config: CareSensAirJournal.Config,
    private val engine: CareSensAirEngine,
) {
    data class Computed(val output: CareSensAirOutput, val hasSensorTime: Boolean, val receivedSeconds: Long)

    sealed interface Arrival {
        data object Duplicate : Arrival
        data object Gap : Arrival
        data object Invalid : Arrival
        data class Accepted(val computed: Computed?) : Arrival
    }

    var cursor = 0
        private set

    /** Return results only after every journal entry has passed continuity and integrity checks. */
    fun recover(): List<Computed> = buildList {
        check(cursor == 0)
        cursor = journal.replay(config) { entry -> compute(entry)?.let { add(it) } }
    }

    fun receive(frame: ByteArray, now: Long): Arrival {
        val record = CareSensAirProtocol.parseRecord(frame) ?: return Arrival.Invalid
        if (record.sequence !in 1..CareSensAirFactoryConfig.MAX_SEQUENCE) return Arrival.Invalid
        if (record.sequence <= cursor) return Arrival.Duplicate
        if (record.sequence != cursor + 1L) return Arrival.Gap

        val rawTime = record.sensorTimestampSeconds
        val timestamp = if (plausible(rawTime, now)) rawTime else {
            val estimated = config.startSeconds + record.sequence * CareSensAirFactoryConfig.RECORD_INTERVAL_SECONDS
            val oldEnough = estimated <= now - HISTORY_ONLY_AGE_SECONDS
            if (record.deviceError == 0 && (!oldEnough || !plausible(estimated, now))) return Arrival.Invalid
            estimated
        }
        val entry = CareSensAirJournal.Entry(frame, now, timestamp)
        journal.append(config, cursor, entry)
        val computed = compute(entry)
        cursor++
        return Arrival.Accepted(computed)
    }

    private fun compute(entry: CareSensAirJournal.Entry): Computed? {
        val record = entry.record
        // Native Air never feeds a transmitter error packet into the algorithm.
        if (record.deviceError != 0) return null
        val output = engine.process(
            record.sequence.toInt(), entry.timestampSeconds, record.adcSamples(), record.temperatureCelsius,
        )
        return output.takeIf {
            it.usable && it.sequence.toLong() in 1..CareSensAirFactoryConfig.MAX_SEQUENCE &&
                plausible(it.timestampSeconds, entry.receivedSeconds)
        }?.let { Computed(it, entry.hasSensorTimestamp, entry.receivedSeconds) }
    }

    private fun plausible(timestamp: Long, received: Long): Boolean =
        timestamp in config.startSeconds..(received + 60) &&
            timestamp <= config.startSeconds + CareSensAirFactoryConfig.MAX_SEQUENCE *
                CareSensAirFactoryConfig.RECORD_INTERVAL_SECONDS

    private companion object {
        const val HISTORY_ONLY_AGE_SECONDS = 10 * 60L
    }
}
