// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire formats from AirGattCallback.java and the packed AirData structure in air.hpp. */
internal object CareSensAirProtocol {
    private const val APP = 0xC0
    private const val FACTORY_INFO = 0xC2
    private const val TIME = 0xC3
    private const val COUNT = 0xC4
    private const val RECORD = 0xC5
    private const val APP_ID = "csair"
    private const val APP_ID_FRAME_BYTES = 35
    private const val RECORD_BYTES = 84
    private const val ADC_SAMPLE_COUNT = 30
    private const val AUTH_PROTOCOL_KEY = "tq1Tg265o4UFD8tfPvNqUCiYyCxkhdZV"

    fun authentication(software: CareSensAirVersion, transmitterSerial: String): ByteArray? {
        if (software < CareSensAirVersion.V1_4) {
            return byteArrayOf(APP.toByte(), 1, 0x10, 0x27, 0, 0, 0, 0, 1, 0, 0)
        }
        if (transmitterSerial.length < 6 || !transmitterSerial.all(::isVisibleAscii)) return null

        val suffix = transmitterSerial.takeLast(6)
        val iv = (suffix + suffix + transmitterSerial.takeLast(4)).toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AUTH_PROTOCOL_KEY.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(iv),
        )
        val encrypted = cipher.doFinal(transmitterSerial.toByteArray(Charsets.US_ASCII))
        return command(APP, 1) + encrypted.copyOfRange(0, 16)
    }

    fun registerApp(unusedSensor: Boolean): ByteArray = ByteArray(APP_ID_FRAME_BYTES).apply {
        this[0] = APP.toByte()
        this[1] = 3
        APP_ID.toByteArray(Charsets.US_ASCII).copyInto(this, destinationOffset = 2)
        this[lastIndex] = if (unusedSensor) 1 else 0
    }

    fun requestStartInfo(): ByteArray = commandWithU32(APP, 2, 0)
    fun requestFactoryInfo(): ByteArray = command(FACTORY_INFO, 1)
    fun syncTime(unixSeconds: Long): ByteArray = commandWithU32(TIME, 2, unixSeconds)
    fun requestRecords(lastProcessedSequence: Long): ByteArray =
        commandWithU32(COUNT, 1, lastProcessedSequence)
    fun requestRecordCount(): ByteArray = command(RECORD, 1)

    fun parseRegistration(frame: ByteArray): RegistrationReply? {
        if (!frame.hasHeader(APP, 3, APP_ID_FRAME_BYTES)) return null
        val returnedId = String(frame, 2, frame.size - 3, Charsets.US_ASCII)
            .trim { it <= ' ' }
        return RegistrationReply(frame.last().toInt() and 0xFF, returnedId == APP_ID)
    }

    fun parseApplicationInfo(frame: ByteArray, software: CareSensAirVersion): ApplicationInfo? {
        if (!frame.hasHeader(APP, 1, 13)) return null
        val modern = software >= CareSensAirVersion.V1_5
        if (modern && frame.size == 31) return null // Only one byte of the QC/key-check pair.
        val hasChecks = modern && frame.size >= 32
        val bytes = frame.littleEndian()
        return ApplicationInfo(
            deviceTimeSeconds = bytes.u32(2),
            userId = bytes.u32(6),
            dataCountPerSet = bytes.u8(10),
            adcInterval = bytes.u16(11),
            qcResult = if (hasChecks) bytes.u8(30) else null,
            keyCheckResult = if (hasChecks) bytes.u8(31) else null,
        )
    }

    fun parseStartInfo(frame: ByteArray, software: CareSensAirVersion): StartInfo? {
        if (!frame.hasHeader(APP, 2, 14)) return null
        val bytes = frame.littleEndian()
        val modern = software >= CareSensAirVersion.V1_3
        val eapp = if (modern) bytes.getFloat(2) else bytes.u32(2) / 1.0e7f
        val vref = if (modern) bytes.getFloat(6) else bytes.u32(6) / 1.0e7f
        if (!eapp.isFinite() || !vref.isFinite()) return null
        return StartInfo(eapp, vref, bytes.u32(10))
    }

    fun parseRecordCount(frame: ByteArray): Int? =
        if (frame.hasHeader(COUNT, 1, 4)) frame.littleEndian().u16(2) else null

    fun parseRecord(frame: ByteArray): RawRecord? {
        if (frame.size != RECORD_BYTES || !frame.hasHeader(RECORD, 1)) return null
        val bytes = frame.littleEndian()
        return RawRecord(
            deviceError = bytes.get(2).toInt(),
            rCount = bytes.u8(3),
            aCount = bytes.u32(4),
            misc = bytes.u32(8),
            sequence = bytes.u32(12),
            sensorTimestampSeconds = bytes.u32(16),
            batteryRaw = bytes.u16(20),
            temperatureCelsius = bytes.u16(22) / 100.0,
            samples = IntArray(ADC_SAMPLE_COUNT) { bytes.u16(24 + it * 2) },
        )
    }

    private fun command(opcode: Int, subOpcode: Int): ByteArray =
        byteArrayOf(opcode.toByte(), subOpcode.toByte())

    private fun commandWithU32(opcode: Int, subOpcode: Int, value: Long): ByteArray {
        require(value in 0..0xFFFFFFFFL) { "CareSens Air command value is outside uint32" }
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(opcode.toByte()).put(subOpcode.toByte()).putInt(value.toInt()).array()
    }

    data class RegistrationReply(val statusCode: Int, val matchesAppId: Boolean) {
        val accepted: Boolean get() = statusCode == 0 && matchesAppId
    }

    data class ApplicationInfo(
        val deviceTimeSeconds: Long,
        val userId: Long,
        val dataCountPerSet: Int,
        val adcInterval: Int,
        val qcResult: Int?,
        val keyCheckResult: Int?,
    )

    data class StartInfo(val eapp: Float, val vref: Float, val elapsedSeconds: Long)

    class RawRecord(
        val deviceError: Int,
        val rCount: Int,
        val aCount: Long,
        val misc: Long,
        val sequence: Long,
        val sensorTimestampSeconds: Long,
        val batteryRaw: Int,
        val temperatureCelsius: Double,
        private val samples: IntArray,
    ) {
        fun adcSamples(): IntArray = samples.copyOf()
    }
}

/** Numeric comparison of dot-separated device version components. */
internal class CareSensAirVersion private constructor(
    private val components: List<Int>,
) : Comparable<CareSensAirVersion> {
    override fun compareTo(other: CareSensAirVersion): Int {
        for (index in 0 until maxOf(components.size, other.components.size)) {
            val difference = (components.getOrNull(index) ?: 0)
                .compareTo(other.components.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }

    companion object {
        val V1_3 = CareSensAirVersion(listOf(1, 3))
        val V1_4 = CareSensAirVersion(listOf(1, 4))
        val V1_5 = CareSensAirVersion(listOf(1, 5))

        fun parse(value: String): CareSensAirVersion? {
            if (value.any { it !in '0'..'9' && it != '.' }) return null
            val components = value.split('.').map { it.toIntOrNull() ?: return null }
            return CareSensAirVersion(components)
        }
    }
}

internal fun ByteArray.hasHeader(opcode: Int, subOpcode: Int, minimumSize: Int = 2): Boolean =
    size >= maxOf(2, minimumSize) && this[0] == opcode.toByte() && this[1] == subOpcode.toByte()

internal fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
internal fun ByteBuffer.u8(index: Int): Int = get(index).toInt() and 0xFF
internal fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF
internal fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and 0xFFFFFFFFL
