// Libre3Records.kt — decrypted Libre 3 data-plane records and patchControl commands.
//
// Layouts match the packed structs the legacy native code decodes (cpp/libre3/bluetooth.cpp:
// oneminute, fastData, HistoryData, Patchstatus, EventLog) and RoundWhiteDiscKit's
// RealtimeGlucoseReading / PatchStatus / ClinicalReadingRecord / HistoricalReadingPage (MIT).
// Fields whose meaning neither source establishes are kept raw.

package tk.glucodata.drivers.libre3

import tk.glucodata.drivers.libre3.Libre3Bytes.s16le
import tk.glucodata.drivers.libre3.Libre3Bytes.u16le
import tk.glucodata.drivers.libre3.Libre3Bytes.u8

object Libre3Glucose {
    const val MIN_DISPLAY_MGDL = 39
    const val MAX_DISPLAY_MGDL = 501

    /**
     * A packed glucose word is usable only when bits 13-15 (sensor condition / range status and the
     * data-quality flag) are clear and the value is inside 39..501. That is exactly what the legacy
     * native check (whole word in 39..501) accepts; below/above-range codes are reported as status,
     * never stored as if they were measured values.
     */
    fun usableMgdl(word: Int): Int? {
        if (word and 0xE000 != 0) return null
        val value = word and 0x1FFF
        return if (value in MIN_DISPLAY_MGDL..MAX_DISPLAY_MGDL) value else null
    }
}

/** 29-byte glucoseData plaintext. */
data class Libre3RealtimeReading(
    val lifeCount: Int,
    val currentWord: Int,
    /** Hundredths of mg/dL per minute; -32768 means "not available". */
    val rateOfChangeRaw: Int,
    val esaDuration: Int,
    val projectedGlucoseRaw: Int,
    val historicalLifeCount: Int,
    val historicalWord: Int,
    val trendByte: Int,
    val uncappedCurrentMgdl: Int,
    val uncappedHistoricalMgdl: Int,
    val temperatureRaw: Int,
) {
    val trend: Int get() = trendByte and 0x07
    val isActionable: Boolean get() = trendByte and 0x08 != 0
    val dataQualityError: Boolean get() = currentWord and 0x8000 != 0
    val sensorCondition: Int get() = (currentWord ushr 13) and 0x03

    val currentMgdl: Int? get() = Libre3Glucose.usableMgdl(currentWord)
    val historicalMgdl: Int? get() = Libre3Glucose.usableMgdl(historicalWord)

    /** mg/dL per minute; the sensor's own rate, else the trend arrow mapped as the legacy path does. */
    val rateMgdlPerMinute: Float
        get() = when {
            rateOfChangeRaw != -32768 -> rateOfChangeRaw / 100f
            trend in 1..5 -> (trend - 3) * 1.3f
            else -> Float.NaN
        }

    companion object {
        const val SIZE = 29

        fun parse(p: ByteArray): Libre3RealtimeReading? {
            if (p.size != SIZE) return null
            return Libre3RealtimeReading(
                lifeCount = u16le(p, 0),
                currentWord = u16le(p, 2),
                rateOfChangeRaw = s16le(p, 4),
                esaDuration = u16le(p, 6),
                projectedGlucoseRaw = u16le(p, 8),
                historicalLifeCount = u16le(p, 10),
                historicalWord = u16le(p, 12),
                trendByte = u8(p, 14),
                uncappedCurrentMgdl = u16le(p, 15),
                uncappedHistoricalMgdl = u16le(p, 17),
                temperatureRaw = u16le(p, 19),
            )
        }
    }
}

/** 12-byte patchStatus plaintext. */
data class Libre3PatchStatus(
    val lifeCount: Int,
    val errorData: Int,
    val eventDataRaw: Int,
    val eventIndex: Int,
    val patchState: Int,
    val currentLifeCount: Int,
    val stackDisconnectReason: Int,
    val appDisconnectReason: Int,
) {
    val isActive: Boolean get() = patchState == STATE_ACTIVE

    /**
     * Patch states 6 and 8, and errorData 6 and 8, are the shut-down sensor in both sources. Normal
     * end of wear (errorData 5) is not: an expired sensor can keep advertising.
     */
    val isTerminated: Boolean
        get() = patchState == 6 || patchState == 8 || errorData == 6 || errorData == 8

    val attention: Attention
        get() = when (errorData) {
            0 -> when (patchState) {
                3, 7 -> Attention.CHECK_SENSOR
                5 -> Attention.ENDED
                6, 8 -> Attention.REPLACE
                else -> Attention.NONE
            }
            3 -> Attention.INSERTION_FAILED
            5 -> Attention.ENDED
            6, 8 -> Attention.REPLACE
            // RoundWhiteDiscKit's protocol notes call 7 "replace sensor", its PatchStatus.swift calls it a
            // recoverable transmission error. Neither has a trace behind it, so this driver surfaces it
            // without ending the session; the raw code is logged and shown.
            7 -> Attention.CHECK_SENSOR
            else -> Attention.UNKNOWN_ERROR
        }

    enum class Attention { NONE, CHECK_SENSOR, INSERTION_FAILED, ENDED, REPLACE, UNKNOWN_ERROR }

    companion object {
        const val SIZE = 12
        const val STATE_ACTIVE = 4

        fun parse(p: ByteArray): Libre3PatchStatus? {
            if (p.size != SIZE) return null
            return Libre3PatchStatus(
                lifeCount = u16le(p, 0),
                errorData = u16le(p, 2),
                eventDataRaw = u16le(p, 4),
                eventIndex = u8(p, 6),
                patchState = u8(p, 7),
                currentLifeCount = u16le(p, 8),
                stackDisconnectReason = u8(p, 10),
                appDisconnectReason = u8(p, 11),
            )
        }
    }
}

/**
 * 14-byte clinicalData record: the minute stream the sensor replays after a disconnect.
 * word[0] is the record's own life count and word[5] the glucose for that minute; word[6] is the
 * latest committed 5-minute value, whose life count both sources estimate as the last 5-minute
 * boundary at lifeCount - 17 (the legacy native round((lc - 19) / 5) * 5 is the same for integers).
 */
data class Libre3ClinicalRecord(
    val lifeCount: Int,
    val currentWord: Int,
    val historicalWord: Int,
) {
    val currentMgdl: Int? get() = Libre3Glucose.usableMgdl(currentWord)
    val historicalMgdl: Int? get() = Libre3Glucose.usableMgdl(historicalWord)
    val estimatedHistoricalLifeCount: Int get() = Math.floorDiv(lifeCount - 17, 5) * 5

    companion object {
        const val SIZE = 14

        fun parse(p: ByteArray): Libre3ClinicalRecord? {
            if (p.size != SIZE) return null
            return Libre3ClinicalRecord(u16le(p, 0), u16le(p, 10), u16le(p, 12))
        }
    }
}

/** historicData page: startLifeCount_LE2 followed by 5-minute values (six in every observed page). */
data class Libre3HistoricalPage(val startLifeCount: Int, val words: List<Int>) {
    /** (lifeCount, mg/dL) for each usable value. */
    fun samples(): List<Pair<Int, Int>> = words.mapIndexedNotNull { i, word ->
        Libre3Glucose.usableMgdl(word)?.let { (startLifeCount + i * INTERVAL_MINUTES) to it }
    }

    val lastLifeCount: Int get() = startLifeCount + (words.size - 1) * INTERVAL_MINUTES

    companion object {
        const val INTERVAL_MINUTES = 5

        fun parse(p: ByteArray): Libre3HistoricalPage? {
            if (p.size < 4 || p.size % 2 != 0) return null
            val start = u16le(p, 0)
            return Libre3HistoricalPage(start, (2 until p.size step 2).map { u16le(p, it) })
        }
    }
}

object Libre3EventLog {
    const val RECORD_SIZE = 7

    /** Highest event index in a decrypted eventLog notification (0xFF marks an empty slot), or -1. */
    fun lastIndex(p: ByteArray): Int {
        for (i in p.size / RECORD_SIZE - 1 downTo 0) {
            val index = u8(p, i * RECORD_SIZE + 6)
            if (index != 0xFF) return index
        }
        return -1
    }
}

/** 7-byte patchControl plaintexts. */
object Libre3ControlCommand {
    fun historicalFrom(lifeCount: Int): ByteArray = range(0x00, lifeCount)
    fun clinicalFrom(lifeCount: Int): ByteArray = range(0x01, lifeCount)

    fun eventLogFrom(index: Int): ByteArray = byteArrayOf(0x04, index.toByte(), 0, 0, 0, 0, 0)

    private fun range(stream: Int, lifeCount: Int): ByteArray {
        val out = byteArrayOf(0x01, stream.toByte(), 0x01, 0, 0, 0, 0)
        Libre3Bytes.putU16le(out, 3, lifeCount.coerceIn(0, 0xFFFF))
        return out
    }
}
