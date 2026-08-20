package tk.glucodata.NovoPen

/**
 * One insulin dose out of a NovoPen 6 / NovoPen Echo Plus log segment.
 *
 * The pen keeps time as an uptime counter, so absolute times only exist relative to the
 * reference time the transport anchored when the segment arrived (phone clock minus the
 * pen's relative time). [timestampSeconds] is that anchored epoch second.
 */
data class PenDose(
    /**
     * The pen's own uptime counter at the moment of the dose, straight off the wire.
     *
     * This is the dose's identity: it is what the pen stores, so it reads back the same on
     * every scan, while [timestampSeconds] moves with whatever anchor the scan happened to
     * land on.
     */
    val relativeSeconds: Long,
    val timestampSeconds: Long,
    val units: Float,
    /** Pen-reported status byte. Non-zero means the pen flagged something about the dose. */
    val flags: Int,
    /** Small dose immediately followed by a real one — an air shot, not therapy. */
    val priming: Boolean = false,
)

/**
 * Decodes the 12-byte dose records the pen ships inside a segment-data event report.
 *
 * The layout mirrors `dose_t` in `settings/javasettings.cpp`, which is the implementation
 * that has been reading real pens in Juggluco:
 *
 * ```
 * 0..3  relative time, big endian seconds
 * 4..5  0x00FF marker (little endian uint16 == 0x00FF)
 * 6..7  dose in tenths of a unit, big endian
 * 8..11 marker: low three bytes == 8, high byte carries pen status flags
 * ```
 *
 * Records that fail either marker are skipped rather than guessed at: a misread dose would
 * put insulin in the journal that was never injected.
 */
object PenDoseParser {
    const val RECORD_SIZE = 12

    /** The pen's own log holds far more than is useful, and native applied the same bound. */
    const val MAX_AGE_SECONDS = 100L * 24 * 60 * 60

    /** No NovoPen delivers this much in one dose; anything above it is a decode error. */
    private const val MAX_UNITS = 60f

    /** A dose at or below this, shortly before a real one, is an air shot. */
    private const val PRIMING_MAX_UNITS = 2.0f
    private const val PRIMING_WINDOW_SECONDS = 60L

    /**
     * @param referenceTimeSeconds epoch second the segment's relative time was anchored to
     * @param nowSeconds epoch second used to reject records outside the plausible window
     * @return decoded doses, oldest first, with priming shots flagged
     */
    fun parse(referenceTimeSeconds: Long, raw: ByteArray?, nowSeconds: Long): List<PenDose> {
        if (raw == null) return emptyList()
        val oldest = nowSeconds - MAX_AGE_SECONDS
        val decoded = ArrayList<PenDose>(raw.size / RECORD_SIZE)
        var offset = 0
        while (offset + RECORD_SIZE <= raw.size) {
            val record = decode(referenceTimeSeconds, raw, offset)
            if (record != null && record.timestampSeconds in oldest..nowSeconds) {
                decoded.add(record)
            }
            offset += RECORD_SIZE
        }
        decoded.sortBy(PenDose::timestampSeconds)
        return markPriming(decoded)
    }

    /**
     * Merges the chunks one scan produced, dropping duplicates the segments overlap on.
     *
     * Keyed on the pen's own counter rather than the anchored second, so an overlap is
     * still recognised as an overlap when the segments were anchored a moment apart.
     */
    fun merge(chunks: List<List<PenDose>>): List<PenDose> {
        val byRelative = LinkedHashMap<Long, PenDose>()
        chunks.forEach { chunk -> chunk.forEach { dose -> byRelative[dose.relativeSeconds] = dose } }
        return markPriming(byRelative.values.sortedBy(PenDose::timestampSeconds))
    }

    private fun decode(referenceTimeSeconds: Long, raw: ByteArray, offset: Int): PenDose? {
        // 0x00FF as a little endian uint16 is FF 00 on the wire.
        if (raw[offset + 4] != 0xFF.toByte() || raw[offset + 5] != 0x00.toByte()) return null
        // Low three bytes of the trailing little endian uint32 must be 8.
        if (raw[offset + 8] != 0x08.toByte() ||
            raw[offset + 9] != 0x00.toByte() ||
            raw[offset + 10] != 0x00.toByte()
        ) {
            return null
        }
        val relativeSeconds = (raw[offset].toLong() and 0xFF shl 24) or
            (raw[offset + 1].toLong() and 0xFF shl 16) or
            (raw[offset + 2].toLong() and 0xFF shl 8) or
            (raw[offset + 3].toLong() and 0xFF)
        val tenths = (raw[offset + 6].toInt() and 0xFF shl 8) or (raw[offset + 7].toInt() and 0xFF)
        val units = tenths / 10f
        if (units <= 0f || units > MAX_UNITS) return null
        return PenDose(
            relativeSeconds = relativeSeconds,
            timestampSeconds = referenceTimeSeconds + relativeSeconds,
            units = units,
            flags = raw[offset + 11].toInt() and 0xFF,
        )
    }

    /**
     * Native drops priming shots outright. Here they are only flagged, because the review
     * sheet lets the reader decide — a 2 U bolus a minute before a bigger one is rare but
     * real, and silently discarding it would be the app inventing therapy history.
     */
    private fun markPriming(sorted: List<PenDose>): List<PenDose> {
        if (sorted.size < 2) return sorted
        return sorted.mapIndexed { index, dose ->
            val next = sorted.getOrNull(index + 1) ?: return@mapIndexed dose
            val priming = dose.units <= PRIMING_MAX_UNITS &&
                next.timestampSeconds - dose.timestampSeconds in 0..PRIMING_WINDOW_SECONDS
            if (priming) dose.copy(priming = true) else dose
        }
    }
}
