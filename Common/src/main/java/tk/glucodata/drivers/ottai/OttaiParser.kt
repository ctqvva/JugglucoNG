// JugglucoNG — Ottai driver
// OttaiParser.kt — BLE live/history payload framing + 12-byte record parser.
//
// Faithful to CgmMonitor.java (framing) + a1.a.e()/b() (record parse, validity)
// in the 1.1.0 watch decompile. See AGENTS/ottai-phase0-confirmed.md.
//
// Decrypted+trimmed payload layout:
//   bytes 0..3   status/prefix (unused by the parser)
//   bytes 4..5   frontDataNo (LE16)
//   bytes 6..7   marker/count (LE16; history continuation)
//   bytes 8..N   8- or 9-byte records (the E1.1 version string is ambiguous)
// Each BLE record becomes a 12-byte parser input:
//   {0x00,0x00} || LE16(frontDataNo + recordIndex) || record8
// 12-byte record fields:
//   [2..3] dataNo LE16
//   [4]    voltage
//   [5..7] runtime = (b5<<16) | (b7<<8) | b6        (mixed-endian, confirmed)
//   [8..9] raw current LE16
//   [10..11] temperature*100 LE16  -> /100.0
//
// Live mode parses only the LAST record; history parses all records.
//
// The record size is NOT re-decided per payload once the driver has learned it. A
// minute live notify is header + one 9-byte record + padding, which is also a valid
// length for two 8-byte records, and one record is not enough evidence to tell them
// apart. See chooseRecordSize/decisiveRecordSize.

package tk.glucodata.drivers.ottai

data class OttaiRecord(
    val dataNo: Int,
    val voltage: Int,
    val runtimeSec: Int,
    val rawCurrent: Int,
    val temperatureC: Double,
    /** The 12-byte parser input (00 00 ‖ dataNoLE ‖ 8-byte record). */
    val recordBytes: ByteArray,
)

data class OttaiReading(
    val record: OttaiRecord,
    /**
     * Formula output (adjustGlucose); 0.0 means below-floor / invalid, NaN a method or coefficient
     * set that cannot be evaluated (OttaiFormula.evaluate), which OttaiOutputFilter refuses.
     */
    val adjustGlucose: Double,
    /** activeTimeMs + runtimeSec*1000. 0 if activeTime unknown. */
    val monitorTimeMs: Long,
    /** False when the record fails the vendor sanity gate (a1.a.b). */
    val valid: Boolean,
)

object OttaiParser {

    const val HEADER_SIZE = 8
    const val BLE_RECORD_SIZE = 8
    /** Some V1.7 devices pack 9-byte records with a different field layout. */
    const val BLE_RECORD_SIZE_E12 = 9
    const val PARSER_RECORD_SIZE = 12
    const val INVALID_DATA_NO = 65535

    /**
     * Choose the BLE record size for a decrypted payload.
     *
     * The version string is not a sufficient discriminator. A 2026-08-11 Syai and the
     * 2026-08-23 CN V3 sensor both report E1.1.4(V1.7.S2530.1), but the Syai puts 20
     * 8-byte records in a 168-byte frame while the CN V3 sensor advances 18 records per
     * 176-byte frame and uses the 9-byte field layout. Hard-coding E1.1 to 8 bytes therefore
     * decodes the V3 stream as temperatures over 500 °C and currents near zero.
     *
     * Only families whose layout has stayed unambiguous on hardware are trusted outright.
     * E1.1 is inferred from every payload. The inference uses the vendor's OWN validity gate
     * (raw current
     * >= 1000, temperature <= 45 — a1.a.b), not a made-up physiological band: a cold reading
     * is still valid, while the mis-aligned layout loses because it decodes an impossible
     * current/temperature (e.g. 505 °C).
     * 9-byte: current[0:2], temp[7:9]; 8-byte: current[4:6], temp[6:8].
     *
     * The inference is only as good as the payload it is given, and a minute live notify is
     * not good enough. It carries header(8) + one 9-byte record + seven pad bytes — 24 bytes,
     * which is equally a header plus two 8-byte records. One record cannot outvote two, and
     * the tie below falls to 8. A 2026-08-30 trace of an E1.1.4(V1.7.S2530.1) sensor caught
     * this happening on 112 of 702 live frames: each time, record[1] was assembled out of the
     * padding (`runtime=0 raw=0`), the live path's records.last() landed on it, it was
     * rejected, and the real sample in record[0] went unexamined. Fourteen consecutive
     * minutes were lost that way while the sensor was connected and talking.
     *
     * So [learned] wins over the guess: once [contentRecordSize] has settled the layout from
     * a payload that could actually settle it, a short frame consumes that answer rather than
     * voting again. The payload's own records outrank the version string. A confirmed family
     * only seeds a width nothing has proved yet, and only when [versionSeedApplies]: the
     * width must fit, and an E1.2 seed of 9 must not contradict the payload's own records.
     */
    internal fun chooseRecordSize(
        payload: ByteArray,
        deviceVersion: String,
        learned: Int? = null,
    ): Int {
        contentRecordSize(payload)?.let { return it }
        // A width that cannot hold even one record in this frame is not a candidate: a 16-byte
        // live notify has an 8-byte body, which no 9-byte record fits in.
        val bodyLen = payload.size - HEADER_SIZE
        learned?.takeIf { (it == BLE_RECORD_SIZE || it == BLE_RECORD_SIZE_E12) && bodyLen >= it }
            ?.let { return it }
        // A confirmed family only seeds a width the payload does not contradict. A 24-byte
        // notify fits one 9-byte record or two 8-byte ones; an E1.2 version string used to
        // force 9 before any page could show the records were 8.
        confirmedRecordSize(deviceVersion)?.takeIf { confirmed ->
            versionSeedApplies(payload, confirmed, bodyLen)
        }?.let { return it }
        val (nine, eight) = recordSizeEvidence(payload)
        return if (nine > eight) BLE_RECORD_SIZE_E12 else BLE_RECORD_SIZE
    }

    /**
     * Vendor-valid record counts for a payload read as 9-byte and as 8-byte, in that order.
     *
     * Exposed so the decision, the "is this decisive" test and the driver's diagnostics all
     * read one computation instead of three that could drift.
     */
    internal fun recordSizeEvidence(payload: ByteArray): Pair<Int, Int> = Pair(
        vendorValidCount(payload, BLE_RECORD_SIZE_E12, curLo = 0, tempLo = 7),
        vendorValidCount(payload, BLE_RECORD_SIZE, curLo = 4, tempLo = 6),
    )

    /**
     * Records the winning layout needs before its margin means anything. Two candidate
     * records — all a 24-byte live notify can offer — is noise; a history page or a polled
     * live read carries nine or more.
     */
    private const val MIN_DECISIVE_RECORDS = 3

    /** How far ahead the winner must be. One record of daylight can be padding luck. */
    private const val DECISIVE_MARGIN = 2

    /**
     * The layout this payload can prove, or the confirmed version string when it cannot.
     *
     * Content is checked first ([contentRecordSize]). The version string is only the
     * fallback, for a frame too short to settle the layout, and only when
     * [versionSeedApplies]. A 16-byte live notify has an 8-byte body, which no 9-byte
     * record fits in, so E1.2 does not seed 9 there. A tied 24-byte frame does not seed 9
     * either. A minute live notify can never settle the layout from content; a history page
     * or a nine-record live read always can.
     */
    internal fun decisiveRecordSize(payload: ByteArray, deviceVersion: String): Int? {
        contentRecordSize(payload)?.let { return it }
        val confirmed = confirmedRecordSize(deviceVersion) ?: return null
        val bodyLen = payload.size - HEADER_SIZE
        // Same evidence gate as chooseRecordSize. Without it, a tied 24-byte frame is
        // learned as 9 from an E1.2 version string, and the learned width then wins
        // before chooseRecordSize can refuse it.
        if (!versionSeedApplies(payload, confirmed, bodyLen)) return null
        return confirmed
    }

    /**
     * The width the driver may store, or null when this payload must not change it.
     *
     * Content always wins, including over a width already learned. The version string may
     * seed a width only while nothing has been learned, and only when [versionSeedApplies].
     * V1.5 stays 8-byte whenever the body fits. E1.2 may seed 9 only when the payload's own
     * records agree: a tied 24-byte frame must not be persisted as 9, or the learned width
     * wins on the next frame before chooseRecordSize can refuse it.
     */
    internal fun recordSizeToLearn(payload: ByteArray, deviceVersion: String, alreadyLearned: Int): Int? =
        contentRecordSize(payload)
            ?: if (alreadyLearned == 0) decisiveRecordSize(payload, deviceVersion) else null

    /**
     * The layout the payload's own records prove, or null when they cannot.
     *
     * This outranks the version string. A 2026-09-26 trace of a sensor whose version maps to
     * the 9-byte family sent 168-byte history pages that advance 20 dataNos per frame and
     * 16-byte live notifies: 8-byte records throughout. Trusting the version read them as
     * 9-byte, so every live notify framed to zero records and only every 8th history record
     * (where the two grids happen to coincide) survived, as a bogus constant glucose.
     */
    internal fun contentRecordSize(payload: ByteArray): Int? {
        val (nine, eight) = recordSizeEvidence(payload)
        return when {
            nine >= MIN_DECISIVE_RECORDS && nine - eight >= DECISIVE_MARGIN -> BLE_RECORD_SIZE_E12
            eight >= MIN_DECISIVE_RECORDS && eight - nine >= DECISIVE_MARGIN -> BLE_RECORD_SIZE
            else -> null
        }
    }

    /** E major.minor -> record size, only for families still unambiguous on hardware. */
    private val CONFIRMED_E_FAMILIES = mapOf(
        "1.2" to BLE_RECORD_SIZE_E12, // E1.2.3(V1.7.SH2542.1) — 9-byte
    )

    /** Leading E-number of a version string: `E1.1.4(...)`, `vE1.2.3(...)`. */
    private val E_NUMBER = Regex("""(?:^|[^0-9A-Za-z])v?e(\d+)\.(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * V1.5 is 8-byte on every unit, so a frame that fits 8 keeps that seed even if one record
     * also parses as 9. E1.2 is not that sure: seed 9 only when the 9-byte reading is ahead,
     * or it has a valid record and the 8-byte reading has none. A tie falls through to the vote
     * and is not learned.
     */
    private fun versionSeedApplies(payload: ByteArray, confirmed: Int, bodyLen: Int): Boolean {
        if (bodyLen < confirmed) return false
        if (confirmed != BLE_RECORD_SIZE_E12) return true
        return confirmedLayoutAgrees(payload, confirmed)
    }

    /**
     * Version seed agrees when this layout is strictly ahead, or it has a valid record and the
     * other layout has none. A tie, or evidence for the other width, falls through to the vote.
     */
    private fun confirmedLayoutAgrees(payload: ByteArray, confirmed: Int): Boolean {
        val (nine, eight) = recordSizeEvidence(payload)
        return when (confirmed) {
            BLE_RECORD_SIZE_E12 -> nine > eight || (nine > 0 && eight == 0)
            BLE_RECORD_SIZE -> eight > nine || (eight > 0 && nine == 0)
            else -> false
        }
    }

    /** Record size for firmware whose live format we've directly confirmed; null = unknown. */
    private fun confirmedRecordSize(deviceVersion: String): Int? {
        E_NUMBER.find(deviceVersion)
            ?.let { CONFIRMED_E_FAMILIES["${it.groupValues[1]}.${it.groupValues[2]}"] }
            ?.let { return it }
        // Pre-E-number strings. V1.5 is unambiguous — no 9-byte V1.5 exists. V1.7 is the
        // ambiguous one and is deliberately absent: it falls through to the structural
        // inference rather than asserting a layout the V-number cannot tell us.
        return if (deviceVersion.contains("V1.5", ignoreCase = true)) BLE_RECORD_SIZE else null
    }

    /** Records that pass the vendor temp/current validity gate (a1.a.b) under [recSize]. */
    private fun vendorValidCount(payload: ByteArray, recSize: Int, curLo: Int, tempLo: Int): Int {
        val count = (payload.size - HEADER_SIZE) / recSize
        var valid = 0
        for (i in 0 until count) {
            val src = HEADER_SIZE + i * recSize
            if (src + tempLo + 1 >= payload.size) break
            val current = le16(payload[src + curLo], payload[src + curLo + 1])
            val temperature = le16(payload[src + tempLo], payload[src + tempLo + 1]) / 100.0
            if (current >= 1_000 && temperature <= 45.0) valid++
        }
        return valid
    }

    private fun le16(lo: Byte, hi: Byte): Int =
        (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)

    /** frontDataNo from a decrypted+trimmed payload (LE16 at bytes 4..5). */
    fun frontDataNo(payload: ByteArray): Int {
        if (payload.size < 6) return 0
        return le16(payload[4], payload[5])
    }

    /**
     * Split a decrypted+trimmed payload into 12-byte parser records
     * (`{0,0} || LE16(frontDataNo+idx) || record8`). Trailing partial bytes are
     * ignored. Returns empty if there is no record region.
     */
    fun frameRecords(
        payload: ByteArray,
        deviceVersion: String = "",
        learned: Int? = null,
    ): List<ByteArray> {
        if (payload.size <= HEADER_SIZE) return emptyList()
        val front = frontDataNo(payload)
        val bleSize = chooseRecordSize(payload, deviceVersion, learned)
        val nineByte = bleSize == BLE_RECORD_SIZE_E12
        val bodyLen = payload.size - HEADER_SIZE
        val count = bodyLen / bleSize
        if (count <= 0) return emptyList()
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val src = HEADER_SIZE + i * bleSize
            // 9-byte notifies pad the frame tail with zero records; skip them so the live
            // path's records.last() lands on the real sample.
            if (nineByte && (0 until bleSize).all { payload[src + it].toInt() == 0 }) continue
            val dataNo = (front + i) and 0xFFFF
            val rec = ByteArray(PARSER_RECORD_SIZE)
            rec[2] = (dataNo and 0xFF).toByte()
            rec[3] = ((dataNo ushr 8) and 0xFF).toByte()
            if (nineByte) {
                // Transcode the 9-byte record into the 12-byte parser layout so
                // parseRecord/formula stay unchanged. The 16-bit runtime counter wraps,
                // so derive runtime from dataNo (= minutes since activation) instead.
                val runtime = dataNo * 60
                rec[4] = payload[src + 6]                       // voltage
                rec[5] = ((runtime ushr 16) and 0xFF).toByte() // runtime (parser: b5<<16)
                rec[6] = (runtime and 0xFF).toByte()           // runtime (parser: | b6)
                rec[7] = ((runtime ushr 8) and 0xFF).toByte()  // runtime (parser: | b7<<8)
                rec[8] = payload[src + 0]                       // current LE lo
                rec[9] = payload[src + 1]                       // current LE hi
                rec[10] = payload[src + 7]                      // temp*100 LE lo
                rec[11] = payload[src + 8]                      // temp*100 LE hi
            } else {
                System.arraycopy(payload, src, rec, 4, BLE_RECORD_SIZE)
            }
            out.add(rec)
        }
        return out
    }

    /** Parse a 12-byte parser record into typed fields (a1.a.e field extraction). */
    fun parseRecord(rec: ByteArray): OttaiRecord {
        require(rec.size >= PARSER_RECORD_SIZE) { "record too short" }
        val dataNo = le16(rec[2], rec[3])
        val voltage = rec[4].toInt() and 0xFF
        val b5 = rec[5].toInt() and 0xFF
        val b6 = rec[6].toInt() and 0xFF
        val b7 = rec[7].toInt() and 0xFF
        val runtime = (b5 shl 16) or (b7 shl 8) or b6
        val rawCurrent = le16(rec[8], rec[9])
        val temperature = le16(rec[10], rec[11]) / 100.0
        return OttaiRecord(dataNo, voltage, runtime, rawCurrent, temperature, rec.copyOf())
    }

    /**
     * Vendor sanity gate (a1.a.b numeric part): reject dataNo==65535, and reject
     * |dataNo - runtime/60| > 120 (data/time skew > ~2h) at every dataNo. The old
     * dataNo>=60 skip let an 8-byte record in the first hour keep a runtime hours
     * away from its index. A normal first hour stays inside 120 minutes, and 9-byte
     * frames derive runtime from dataNo so their skew is zero.
     * The app also requires userId/mac/softVersion present — those are checked by
     * the driver, not here.
     */
    fun isRecordSane(rec: OttaiRecord): Boolean {
        if (rec.dataNo == INVALID_DATA_NO) return false
        if (kotlin.math.abs(rec.dataNo - (rec.runtimeSec / 60)) > 120) return false
        return true
    }

    /** Build a reading from a 12-byte parser record (no decryption). */
    fun toReading(
        rec12: ByteArray,
        method: String,
        coefficients: List<Double>,
        activeTimeMs: Long,
    ): OttaiReading {
        val record = parseRecord(rec12)
        val adjust = if (method.isBlank()) {
            0.0
        } else {
            OttaiFormula.evaluate(
                methodText = method,
                coefficients = coefficients,
                v = OttaiFormula.buildVariables(
                    rawCurrent = record.rawCurrent,
                    temperature = record.temperatureC,
                    runtimeSec = record.runtimeSec,
                    dataNo = record.dataNo,
                    voltage = record.voltage,
                ),
                recordBytes = rec12,
            )
        }
        val monitorMs = if (activeTimeMs > 0L) activeTimeMs + record.runtimeSec * 1000L else 0L
        return OttaiReading(record, adjust, monitorMs, isRecordSane(record))
    }
}
