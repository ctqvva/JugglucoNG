package tk.glucodata.drivers.libre3

import tk.glucodata.drivers.libre3.Libre3Bytes.s16le
import tk.glucodata.drivers.libre3.Libre3Bytes.u16le
import tk.glucodata.drivers.libre3.Libre3Bytes.u8

/**
 * Lingo security version 3, based on Juggluco 12.0.5's Lingo support (GPL-3.0-or-later).
 * The Libre 3 GATT transport, key schedule and CCM session are shared.
 */
internal object Libre3Lingo {
    const val PRODUCT_TYPE = 9
    const val SECURITY_VERSION = 3
    const val RECEIVER_ID = 1001L
    const val LIVE_WIRE_SIZE = 57
    const val LIVE_PLAINTEXT_SIZE = 51
    const val CLINICAL_STREAM = 3
    const val HISTORICAL_STREAM = 2

    // Public verifier and fingerprint of the app certificate used by upstream Lingo v3.
    val patchSigningKeys = listOf(
        Libre3Bytes.parseHex(
            "04e7886230f7dc77a2c12065e1a26cacca9482e90386ff2646953151493f6e3cf2" +
                "d0c60c437d0e488079b9e11d26bed80e7c3da22e66350d7fac54c0051f68c27d"
        )!!,
    )
    private const val APP_CERTIFICATE_SHA256 = "b1e83e2fd19e7883b1d892a4fcbeeac4f0f09d86b02a5857a43ba0ce07210829"

    fun matchesIdentity(identity: Libre3AppIdentity): Boolean =
        Libre3IdentityProvisioning.sha256Hex(identity.certificate) == APP_CERTIFICATE_SHA256

    private const val GLUCOSE_CHANNEL = 1
    private const val SECOND_CHANNEL = 0x8000
    private const val INVALID_READING = 0x8000
    private const val GLUCOSE_MASK = 0x0FFF

    fun parseRealtime(plain: ByteArray): Libre3RealtimeReading? {
        if (plain.size != LIVE_PLAINTEXT_SIZE) return null
        val typeMap = u8(plain, 34)
        val glucoseOffset = when {
            typeMap and 0x0F == GLUCOSE_CHANNEL -> 4
            typeMap ushr 4 == GLUCOSE_CHANNEL -> 19
            else -> null
        }
        return Libre3RealtimeReading(
            lifeCount = u16le(plain, 0),
            currentWord = glucoseOffset?.let { liveGlucoseWord(plain, it, it + 11) } ?: INVALID_READING,
            rateOfChangeRaw = glucoseOffset?.let { s16le(plain, it + 2) } ?: Short.MIN_VALUE.toInt(),
            esaDuration = 0,
            projectedGlucoseRaw = glucoseOffset?.let { u16le(plain, it + 6) } ?: 0,
            historicalLifeCount = u16le(plain, 2),
            historicalWord = glucoseOffset?.let { liveGlucoseWord(plain, it + 8, it + 13) } ?: INVALID_READING,
            trendByte = glucoseOffset?.let { u8(plain, it + 10) } ?: 0,
            uncappedCurrentMgdl = glucoseOffset?.let { glucoseWord(u16le(plain, it + 11)) } ?: 0,
            uncappedHistoricalMgdl = glucoseOffset?.let { glucoseWord(u16le(plain, it + 13)) } ?: 0,
            temperatureRaw = u16le(plain, 37),
        )
    }

    private fun liveGlucoseWord(plain: ByteArray, cappedOffset: Int, uncappedOffset: Int): Int {
        val capped = glucoseWord(u16le(plain, cappedOffset))
        if (capped and INVALID_READING != 0) return capped
        val uncapped = glucoseWord(u16le(plain, uncappedOffset))
        return if (uncapped != 0 && uncapped and INVALID_READING == 0) uncapped else capped
    }

    private fun glucoseWord(word: Int): Int = word and (INVALID_READING or GLUCOSE_MASK)

    // Backfill on the supported sensor has glucose on channel 1, marked in the life-count word.
    fun parseClinical(plain: ByteArray): Libre3ClinicalRecord? {
        if (plain.size != Libre3ClinicalRecord.SIZE) return null
        val life = u16le(plain, 0)
        if (life and SECOND_CHANNEL == 0) return null
        return Libre3ClinicalRecord(life and 0x7FFF, glucoseWord(u16le(plain, 10)), glucoseWord(u16le(plain, 12)))
    }

    fun parseHistory(plain: ByteArray): Libre3HistoricalPage? {
        if (plain.size !in 4..14 || plain.size % 2 != 0) return null
        val start = u16le(plain, 0)
        if (start and SECOND_CHANNEL == 0) return null
        val readings = (2 until plain.size step 2).map { u16le(plain, it) }.takeWhile { it != 0 }
        if (readings.isEmpty()) return null
        return Libre3HistoricalPage(start and 0x7FFF, readings.map(::glucoseWord))
    }
}
