package tk.glucodata.drivers.scgm

import java.util.Locale
import kotlin.math.round
import tk.glucodata.drivers.bioe.BioeCalibration

typealias ScgmCalibration = BioeCalibration

data class ScgmFrame(
    val cmd: Int,
    val payload: ByteArray,
)

data class ScgmRealtime(
    val index: Int,
    val adc: Int,
    val batteryMv: Int,
    val ntc: Int,
    val timestampMs: Long,
)

data class ScgmHistoryInquiry(
    val latestIndex: Int,
    val dataCount: Int,
)

data class ScgmHistoryPage(
    val startIndex: Int,
    val count: Int,
    val adc: List<Int>,
)

data class ScgmTerminalInfo(
    val deviceCode: String,
    val firmware: String,
    val runTimeMinutes: Int,
    val biasVoltageMv: Int,
    val packageCount: Int,
)

data class ScgmHeartbeat(
    val status: Int,
)

object ScgmParser {

    fun frame(cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(
            ScgmConstants.PREAMBLE_0.toByte(),
            ScgmConstants.PREAMBLE_1.toByte(),
            cmd.toByte(),
            payload.size.toByte(),
        ) + payload + ByteArray(ScgmConstants.FRAME_TRAILER_SIZE)

    fun heartbeat(unixSeconds: Long): ByteArray = frame(
        ScgmConstants.CMD_HEARTBEAT,
        byteArrayOf(
            unixSeconds.toByte(),
            (unixSeconds ushr 8).toByte(),
            (unixSeconds ushr 16).toByte(),
            (unixSeconds ushr 24).toByte(),
        ),
    )

    fun currentResult(): ByteArray = frame(ScgmConstants.CMD_CURRENT_RESULT)

    fun queryHistory(): ByteArray = frame(ScgmConstants.CMD_QUERY_HISTORY, byteArrayOf(0, 0))

    fun historyPage(offset: Int, count: Int): ByteArray {
        val safeOffset = offset.coerceAtLeast(0)
        val safeCount = count.coerceAtLeast(0)
        return frame(
            ScgmConstants.CMD_HISTORY_PAGE,
            byteArrayOf(
                safeOffset.toByte(),
                (safeOffset ushr 8).toByte(),
                safeCount.toByte(),
                (safeCount ushr 8).toByte(),
            ),
        )
    }

    fun clearHistory(): ByteArray = frame(ScgmConstants.CMD_CLEAR_HISTORY)

    fun activation(): ByteArray = frame(ScgmConstants.CMD_ACTIVATION, byteArrayOf(1))

    fun terminalInfo(): ByteArray = frame(ScgmConstants.CMD_TERMINAL_INFO)

    fun authenticate(random: Int, key: ByteArray): ByteArray = frame(
        ScgmConstants.CMD_AUTHENTICATE,
        byteArrayOf((random ushr 8).toByte(), random.toByte()) + key.take(13),
    )

    fun decode(data: ByteArray): ScgmFrame? {
        if (data.size < 4) return null
        if (u(data[0]) == ScgmConstants.PREAMBLE_0 && u(data[1]) == ScgmConstants.PREAMBLE_1) {
            val length = u(data[3])
            if (data.size < length + 4) return null
            return ScgmFrame(u(data[2]), data.copyOfRange(4, 4 + length))
        }
        val length = u(data[1])
        if (data.size < length + 2) return null
        return ScgmFrame(u(data[0]), data.copyOfRange(2, 2 + length))
    }

    fun command(data: ByteArray): Int? = decode(data)?.cmd

    fun parseRealtime(data: ByteArray, nowMs: Long): ScgmRealtime? {
        val parsed = decode(data) ?: return null
        if (parsed.cmd != ScgmConstants.CMD_CURRENT_RESULT || parsed.payload.size < 8) return null
        val index = le16(parsed.payload, 0)
        if (index == 0) return null
        return ScgmRealtime(
            index = index,
            adc = le16(parsed.payload, 2),
            batteryMv = le16(parsed.payload, 4),
            ntc = le16(parsed.payload, 6),
            timestampMs = nowMs,
        )
    }

    fun parseHistoryInquiry(data: ByteArray): ScgmHistoryInquiry? {
        val parsed = decode(data) ?: return null
        if (parsed.cmd != ScgmConstants.CMD_QUERY_HISTORY || parsed.payload.size < 4) return null
        return ScgmHistoryInquiry(
            latestIndex = le16(parsed.payload, 0),
            dataCount = le16(parsed.payload, 2),
        )
    }

    fun parseHistoryPage(data: ByteArray): ScgmHistoryPage? {
        val parsed = decode(data) ?: return null
        if (parsed.cmd != ScgmConstants.CMD_HISTORY_PAGE || parsed.payload.size < 4) return null
        val startIndex = le16(parsed.payload, 0)
        val count = le16(parsed.payload, 2)
        if (count <= 0 || parsed.payload.size < 4 + count * 2) return null
        val adc = (0 until count).map { le16(parsed.payload, 4 + it * 2) }
        return ScgmHistoryPage(startIndex, count, adc)
    }

    fun parseTerminalInfo(data: ByteArray): ScgmTerminalInfo? {
        val parsed = decode(data) ?: return null
        if (parsed.cmd != ScgmConstants.CMD_TERMINAL_INFO || parsed.payload.size < 26) return null
        val codeBytes = parsed.payload.copyOfRange(0, 16)
        val codeText = String(codeBytes, Charsets.ISO_8859_1).trim().trimEnd(0.toChar()).trim()
        return ScgmTerminalInfo(
            deviceCode = codeText.ifEmpty { hex(codeBytes).lowercase(Locale.ROOT) },
            firmware = hex(parsed.payload.copyOfRange(16, 20)).uppercase(Locale.ROOT),
            runTimeMinutes = le16(parsed.payload, 20),
            biasVoltageMv = le16(parsed.payload, 22),
            packageCount = le16(parsed.payload, 24),
        )
    }

    fun parseHeartbeat(data: ByteArray): ScgmHeartbeat? {
        val parsed = decode(data) ?: return null
        if (parsed.cmd != ScgmConstants.CMD_HEARTBEAT || parsed.payload.isEmpty()) return null
        return ScgmHeartbeat(u(parsed.payload[0]))
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, u(it)) }

    fun adcToNa(adc: Int): Double = adc.toDouble()

    fun round2(value: Double): Double = round(value * 100.0) / 100.0

    fun glucoseMmol(adc: Int, calibration: ScgmCalibration): Double {
        if (!calibration.isUsable) return 0.0
        val na = adcToNa(adc)
        if (na <= 0.0 || na < calibration.lowCurrentThresholdNa) return 0.0
        val mmol = round2(na / 100.0 * calibration.k - calibration.b)
        return if (mmol.isFinite() && mmol > 0.0) mmol else 0.0
    }

    fun glucoseMgdl(adc: Int, calibration: ScgmCalibration): Float =
        (glucoseMmol(adc, calibration) * ScgmConstants.MGDL_PER_MMOL).toFloat()

    fun displayValue(mmol: Double, unit: Int): Float =
        if (unit == 1) mmol.toFloat() else (mmol * ScgmConstants.MGDL_PER_MMOL).toFloat()

    fun isLowBattery(batteryMv: Int): Boolean =
        batteryMv != 0 && batteryMv <= ScgmConstants.LOW_BATTERY_MV

    fun historyTimeMs(index: Int, lastPid: Int, lastTimeMs: Long): Long {
        val remainder = lastTimeMs % ScgmConstants.SAMPLE_INTERVAL_MS
        val bucket = lastTimeMs - remainder
        val minutesBack = maxOf(lastPid - index + 1, 1).toLong()
        return bucket - minutesBack * ScgmConstants.SAMPLE_INTERVAL_MS + remainder
    }

    fun shouldRead(dataCount: Int, nextBrokePosition: Int, latestIndex: Int): Int {
        val offset = maxOf(nextBrokePosition, latestIndex)
        return (dataCount - (offset - latestIndex)).coerceAtLeast(0)
    }

    fun bootAtSeconds(nowMs: Long, packageCountMinutes: Int): Long =
        nowMs / 1000L - packageCountMinutes.toLong() * 60L

    fun lastPidFromBoot(nowMs: Long, bootAtSeconds: Long): Int =
        ((nowMs / 1000L - bootAtSeconds) / 60L).toInt().coerceAtLeast(0)

    private fun u(byte: Byte): Int = byte.toInt() and 0xFF

    private fun le16(bytes: ByteArray, offset: Int): Int = u(bytes[offset]) or (u(bytes[offset + 1]) shl 8)
}
