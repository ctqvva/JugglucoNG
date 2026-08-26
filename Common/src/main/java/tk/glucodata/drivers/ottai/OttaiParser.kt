// JugglucoNG — Ottai driver
// OttaiParser.kt — BLE live/history payload framing + 12-byte record parser.
//
// Faithful to CgmMonitor.java (framing) + a1.a.e()/b()/c() (record parse,
// validity, AVERAGE rule) in the 1.1.0 watch decompile. See
// AGENTS/ottai-phase0-confirmed.md.
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
    /** Formula output (adjustGlucose); 0.0 means below-floor / invalid. */
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
     */
    internal fun chooseRecordSize(payload: ByteArray, deviceVersion: String): Int {
        confirmedRecordSize(deviceVersion)?.let { return it }
        val nine = vendorValidCount(payload, BLE_RECORD_SIZE_E12, curLo = 0, tempLo = 7)
        val eight = vendorValidCount(payload, BLE_RECORD_SIZE, curLo = 4, tempLo = 6)
        return if (nine > eight) BLE_RECORD_SIZE_E12 else BLE_RECORD_SIZE
    }

    /** E major.minor -> record size, only for families still unambiguous on hardware. */
    private val CONFIRMED_E_FAMILIES = mapOf(
        "1.2" to BLE_RECORD_SIZE_E12, // E1.2.3(V1.7.SH2542.1) — 9-byte
    )

    /** Leading E-number of a version string: `E1.1.4(...)`, `vE1.2.3(...)`. */
    private val E_NUMBER = Regex("""(?:^|[^0-9A-Za-z])v?e(\d+)\.(\d+)""", RegexOption.IGNORE_CASE)

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
    fun frameRecords(payload: ByteArray, deviceVersion: String = ""): List<ByteArray> {
        if (payload.size <= HEADER_SIZE) return emptyList()
        val front = frontDataNo(payload)
        val bleSize = chooseRecordSize(payload, deviceVersion)
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
     * Vendor sanity gate (a1.a.b numeric part): reject dataNo==65535, and once
     * dataNo>=60 reject if |dataNo - runtime/60| > 120 (data/time skew > ~2h).
     * The app also requires userId/mac/softVersion present — those are checked by
     * the driver, not here.
     */
    fun isRecordSane(rec: OttaiRecord): Boolean {
        if (rec.dataNo == INVALID_DATA_NO) return false
        if (rec.dataNo >= 60 && kotlin.math.abs(rec.dataNo - (rec.runtimeSec / 60)) > 120) return false
        return true
    }

    /** AVERAGE-record condition (a1.a.c): runtime>=warmup, dataNo>=5, dataNo%5==0. */
    fun isAverageTick(rec: OttaiRecord, warmupSec: Int = 3200): Boolean =
        rec.runtimeSec >= warmupSec && rec.dataNo >= 5 && rec.dataNo % 5 == 0

    /**
     * Full live parse: decrypt → frame → take last record → run formula.
     * Returns null if decryption/framing yields nothing.
     */
    fun parseLive(
        cipher: ByteArray,
        sessionKeyHex: String,
        method: String,
        coefficients: List<Double>,
        activeTimeMs: Long,
    ): OttaiReading? {
        val payload = OttaiCrypto.decryptPayload(cipher, sessionKeyHex) ?: return null
        val records = frameRecords(payload)
        if (records.isEmpty()) return null
        return toReading(records.last(), method, coefficients, activeTimeMs)
    }

    /** Full history parse: decrypt → frame → all records → run formula each. */
    fun parseHistory(
        cipher: ByteArray,
        sessionKeyHex: String,
        method: String,
        coefficients: List<Double>,
        activeTimeMs: Long,
    ): List<OttaiReading> {
        val payload = OttaiCrypto.decryptPayload(cipher, sessionKeyHex) ?: return emptyList()
        return frameRecords(payload).map { toReading(it, method, coefficients, activeTimeMs) }
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
