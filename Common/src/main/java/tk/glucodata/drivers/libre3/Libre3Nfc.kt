// Libre3Nfc.kt — Libre 3 / 3 Plus NFC patch-info and activation (A0) / switch-receiver (A8).
//
// Byte layouts follow the structs the legacy native path has used on Android for years
// (Common/src/main/cpp/libre3/nfc.cpp, nfcmetadata.hpp) and the CRC in dp_activation.hpp,
// cross-checked against RoundWhiteDiscKit's protocol notes (MIT). Android's NfcV.transceive
// returns the same bytes RoundWhiteDiscKit calls the "normalized" response: a 0x00 flag byte,
// 0xA5 (sometimes repeated), 0x00, then the payload.

package tk.glucodata.drivers.libre3

import tk.glucodata.drivers.libre3.Libre3Bytes.u16le
import tk.glucodata.drivers.libre3.Libre3Bytes.u32le
import tk.glucodata.drivers.libre3.Libre3Bytes.u8

object Libre3Nfc {

    /** ISO 15693 custom command prefix: request flags 0x02, then command, then Abbott's 0x7A. */
    const val MANUFACTURER_CODE: Byte = 0x7A
    const val CMD_PATCH_INFO: Byte = 0xA1.toByte()
    const val CMD_ACTIVATE: Byte = 0xA0.toByte()
    const val CMD_SWITCH_RECEIVER: Byte = 0xA8.toByte()

    /** NFC error the sensor answers when the receiver ID differs from the one it was activated with. */
    const val ERROR_WRONG_RECEIVER = 0xB1

    const val PATCH_INFO_PAYLOAD_SIZE = 26
    const val ACTIVATION_PAYLOAD_SIZE = 16

    /** Patch states with a defined meaning on the NFC side. */
    const val STATE_NOT_ACTIVATED = 0x01
    const val STATE_TERMINATED_6 = 0x06
    const val STATE_TERMINATED_8 = 0x08

    fun patchInfoCommand(): ByteArray = byteArrayOf(0x02, CMD_PATCH_INFO, MANUFACTURER_CODE)

    /**
     * Abbott's CRC-16 used in the A0/A8 body: CRC-CCITT (poly 0x1021, init 0xFFFF) over
     * bit-reversed input bytes, no output reflection, emitted little-endian.
     */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (bitReverse8(data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private fun bitReverse8(value: Int): Int {
        var v = value
        v = ((v and 0xF0) ushr 4) or ((v and 0x0F) shl 4)
        v = ((v and 0xCC) ushr 2) or ((v and 0x33) shl 2)
        v = ((v and 0xAA) ushr 1) or ((v and 0x55) shl 1)
        return v and 0xFF
    }

    /**
     * The 10-byte A0/A8 body: `LE32(time - 1) || LE32(receiverId) || LE16(crc)`.
     *
     * The legacy driver has always sent `nowSeconds - 1` (dp_activation.hpp, verified against
     * a recorded activation); RoundWhiteDiscKit serialises whatever its caller passes. Keeping
     * the legacy rule keeps the on-wire bytes identical to the path users already run.
     */
    fun activationBody(nowSeconds: Long, receiverId: Long): ByteArray {
        val out = ByteArray(10)
        Libre3Bytes.putU32le(out, 0, (nowSeconds - 1) and 0xFFFFFFFFL)
        Libre3Bytes.putU32le(out, 4, receiverId and 0xFFFFFFFFL)
        Libre3Bytes.putU16le(out, 8, crc16(out, 0, 8))
        return out
    }

    fun activationCommand(command: Byte, nowSeconds: Long, receiverId: Long): ByteArray =
        byteArrayOf(0x02, command, MANUFACTURER_CODE) + activationBody(nowSeconds, receiverId)

    /**
     * Offset of the payload behind `00 A5 [A5…] 00`, or -1 when the prefix is wrong or fewer
     * than [payloadSize] bytes follow it. Mirrors libre3nfc::nfcPayloadOffset.
     */
    fun payloadOffset(data: ByteArray?, payloadSize: Int): Int {
        if (data == null || data.size < 3) return -1
        if (data[0].toInt() != 0 || u8(data, 1) != 0xA5) return -1
        var pos = 2
        while (pos < data.size && u8(data, pos) == 0xA5) pos++
        if (pos == data.size || data[pos].toInt() != 0) return -1
        pos++
        return if (data.size - pos < payloadSize) -1 else pos
    }

    data class PatchInfo(
        val securityVersion: Int,
        val localization: Int,
        val puckGeneration: Int,
        val wearDurationMinutes: Int,
        val firmwareBytes: ByteArray,
        val productType: Int,
        val warmupMinutes: Int,
        val state: Int,
        val serial: String,
    ) {
        /** Firmware as the sensor's own apps print it (most significant byte first). */
        val firmwareVersion: String
            get() = firmwareBytes.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() }

        val isNotActivated: Boolean get() = state == STATE_NOT_ACTIVATED
        val isTerminated: Boolean get() = state == STATE_TERMINATED_6 || state == STATE_TERMINATED_8

        override fun equals(other: Any?): Boolean =
            other is PatchInfo && serial == other.serial && state == other.state &&
                wearDurationMinutes == other.wearDurationMinutes && warmupMinutes == other.warmupMinutes &&
                firmwareBytes.contentEquals(other.firmwareBytes) && securityVersion == other.securityVersion

        override fun hashCode(): Int = serial.hashCode() * 31 + state
    }

    fun parsePatchInfo(response: ByteArray?): PatchInfo? {
        val off = payloadOffset(response, PATCH_INFO_PAYLOAD_SIZE)
        if (off < 0 || response == null) return null
        // Same plausibility the legacy NfcMetadata check applies before it creates a sensor.
        val warmup = u8(response, off + 13) * 5
        val wear = u16le(response, off + 6)
        if (warmup == 0 || warmup > 255 || wear <= warmup) return null
        val serialBytes = response.copyOfRange(off + 15, off + 24)
        if (!serialBytes.all { it in 0x20..0x7E }) return null
        return PatchInfo(
            securityVersion = u16le(response, off),
            localization = u16le(response, off + 2),
            puckGeneration = u16le(response, off + 4),
            wearDurationMinutes = u16le(response, off + 6),
            firmwareBytes = response.copyOfRange(off + 8, off + 12),
            productType = u8(response, off + 12),
            warmupMinutes = u8(response, off + 13) * 5,
            state = u8(response, off + 14),
            serial = String(serialBytes, Charsets.US_ASCII),
        )
    }

    /**
     * Which command a scan should send. A sensor that was never activated takes A0; one that
     * already runs takes A8 (switch receiver). An explicit override wins, as in the legacy
     * Libre3NfcSettings.
     */
    fun chooseCommand(info: PatchInfo, override: Byte? = null): Byte =
        override ?: if (info.isNotActivated) CMD_ACTIVATE else CMD_SWITCH_RECEIVER

    sealed class ActivationResult {
        data class Success(
            /** "AA:BB:CC:DD:EE:FF", most significant byte first, as Android's BluetoothDevice prints it. */
            val bleAddress: String,
            val blePin: ByteArray,
            /** Seconds since the epoch; the sensor's own activation time when it reports one. */
            val activationTimeSeconds: Long,
            val reportedActivationTime: Boolean,
        ) : ActivationResult() {
            override fun equals(other: Any?): Boolean =
                other is Success && bleAddress == other.bleAddress && blePin.contentEquals(other.blePin) &&
                    activationTimeSeconds == other.activationTimeSeconds
            override fun hashCode(): Int = bleAddress.hashCode()
        }

        /** The sensor refused the command; [code] 0xB1 means a receiver ID it was not activated with. */
        data class SensorError(val code: Int) : ActivationResult() {
            val isWrongReceiver: Boolean get() = code == ERROR_WRONG_RECEIVER
        }

        data class Malformed(val length: Int) : ActivationResult()
    }

    fun parseActivationResponse(response: ByteArray?, nowSeconds: Long): ActivationResult {
        if (response == null) return ActivationResult.Malformed(-1)
        // Error: 00 A5 01 <code> (nfc2error: zero, recogn = LE16 0x01A5, error).
        if (response.size == 4 && response[0].toInt() == 0 && u8(response, 1) == 0xA5 && u8(response, 2) == 0x01) {
            return ActivationResult.SensorError(u8(response, 3))
        }
        val off = payloadOffset(response, ACTIVATION_PAYLOAD_SIZE)
        if (off < 0) return ActivationResult.Malformed(response.size)
        val address = (5 downTo 0).joinToString(":") { "%02X".format(u8(response, off + it)) }
        val pin = response.copyOfRange(off + 6, off + 10)
        val reported = u32le(response, off + 10)
        // Legacy falls back to "now" only for a zero time and rejects anything else implausible.
        if (reported != 0L && reported !in MIN_PLAUSIBLE_ACTIVATION_SECONDS..(nowSeconds + 60)) {
            return ActivationResult.Malformed(response.size)
        }
        val valid = reported != 0L
        return ActivationResult.Success(
            bleAddress = address,
            blePin = pin,
            activationTimeSeconds = if (valid) reported else nowSeconds,
            reportedActivationTime = valid,
        )
    }

    /** Same floor the native NfcMetadata check uses (May 2020). */
    private const val MIN_PLAUSIBLE_ACTIVATION_SECONDS = 1_590_000_000L

    /** Libre 3 advertises its NFC-reported address as an uppercase hex local name without colons. */
    fun advertisedName(bleAddress: String): String = bleAddress.replace(":", "").uppercase()
}
