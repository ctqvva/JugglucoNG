// JugglucoNG — AiDex CGM3 (G9) driver
// Cgm3Opcodes.kt — Wire opcodes, wrapper operation ids, GATT ids and frame
// constants for the MicroTech CGM3 (G9 split transmitter+probe) protocol.
//
// Sources:
// - ARM64 disassembly of libblecomm-lib.so (v1.16.1, vc11601):
//   every request builder calls the vtable send helper with w1 = raw wire
//   opcode byte (spot-verified: getStatus→0x05, reset→0x29,
//   getSensorNum→0x04, getStartTime→0x01).
// - Decompiled `com.microtechmd.blecomm.constant.Cgm3Operation` (wrapper ids).
// - UUID getter stubs (`mov w0, #imm; ret`) — identical profile to AiDEX X.
// Full analysis: docs/aidex-g9-cgm3-protocol.md (4.1, 5.3.1–5.3.3).

package tk.glucodata.drivers.aidex.cgm3

import java.util.UUID

object Cgm3Opcodes {

    // -- Wire opcodes (first byte of F002 command payloads) --

    const val GET_START_TIME: Int = 0x01
    const val GET_SENSOR_SN: Int = 0x02
    const val GET_FACTORY_CALIB: Int = 0x03
    const val GET_SENSOR_NUM: Int = 0x04
    const val GET_STATUS: Int = 0x05
    const val GET_SENSOR_CHECK: Int = 0x07
    const val GET_HISTORY_RANGE: Int = 0x08
    const val GET_HISTORIES: Int = 0x09
    const val GET_RAW_HISTORIES: Int = 0x0A
    const val GET_CALIBRATION_RANGE: Int = 0x0B
    const val GET_CALIBRATION: Int = 0x0C
    const val GET_LOG_RANGE: Int = 0x0D
    const val GET_LOG_HISTORIES: Int = 0x0E
    const val GET_ERROR_LOG: Int = 0x0F
    const val SET_NEW_SENSOR: Int = 0x20
    const val SET_FACTORY_CALIBRATION: Int = 0x21
    const val SET_RESUME_SENSOR: Int = 0x23
    const val SET_CALIBRATION: Int = 0x24
    const val KEEP_CONNECTION: Int = 0x26
    const val SET_NOTIFICATION: Int = 0x27
    const val ENABLE_BROADCAST_DATA: Int = 0x28
    /**
     * Lifecycle ops. Present in the vendor stack but effectiveness on G9
     * hardware is UNVERIFIED — newer X sensors already ignore CLEAR_STORAGE,
     * so do not rely on these until proven live on a G9 transmitter.
     */
    const val RESET: Int = 0x29
    const val DELETE_BOND: Int = 0x2A
    const val CLEAR_STORAGE: Int = 0x2B
    const val SET_TIME_OFFSET: Int = 0x2D

    /** Probe-activation long-write opcode (chunked, cf. `sendActivateChunk`). */
    const val ACTIVATE: Int = 0x2E

    // -- Wrapper operation ids (Cgm3Operation; internal dispatch only) --

    const val OP_GET_DEVICE_SPEC: Int = 256 // 0x100
    const val OP_GET_SOFTWARE_INFO: Int = 257 // 0x101
    const val OP_DELETE_BOND: Int = 258 // 0x102
    const val OP_GET_SEED: Int = 260 // 0x104
    const val OP_SET_SENSOR_SN: Int = 513 // 0x201
    const val OP_SET_FACTORY_CALIBRATION: Int = 514 // 0x202
    const val OP_SET_NEW_SENSOR: Int = 515 // 0x203
    const val OP_SET_RESUME_SENSOR: Int = 516 // 0x204
    const val OP_NEW_SENSOR_ONLINE: Int = 526 // 0x20E
    const val OP_SET_TIME_OFFSET: Int = 525 // 0x20D
    const val OP_GET_SENSOR_SN: Int = 521 // 0x209
    const val OP_GET_SENSOR_NUM: Int = 520 // 0x208
    const val OP_GET_FACTORY_CALIBRATION: Int = 522 // 0x20A
    const val OP_GET_START_TIME: Int = 523 // 0x20B
    const val OP_GET_ACTIVATED_NUMBER: Int = 524 // 0x20C
    const val OP_KEEP_CONNECTION: Int = 768 // 0x300
    const val OP_SET_NOTIFICATION: Int = 769 // 0x301
    const val OP_ENABLE_BROADCAST_DATA: Int = 770 // 0x302
    const val OP_GET_STATUS: Int = 1024 // 0x400
    const val OP_GET_HISTORY_RANGE: Int = 1025 // 0x401
    const val OP_GET_HISTORIES: Int = 1026 // 0x402
    const val OP_GET_HISTORIES_RAW: Int = 1028 // 0x404
    const val OP_GET_SENSOR_CHECK: Int = 1029 // 0x405
    const val OP_SET_CALIBRATION: Int = 1280 // 0x500
    const val OP_GET_CALIBRATION_RANGE: Int = 1281 // 0x501
    const val OP_GET_CALIBRATION: Int = 1282 // 0x502
    const val OP_GET_LOG_RANGE: Int = 1537 // 0x601
    const val OP_GET_LOG_HISTORIES: Int = 1538 // 0x602
    const val OP_GET_ERROR_LOG: Int = 1539 // 0x603
    const val OP_RESET: Int = 3840 // 0xF00
    const val OP_CLEAR_STORAGE: Int = 3842 // 0xF02
    const val OP_GET_DEFAULT_PARAM: Int = 3586 // 0xE02
    const val OP_SET_DEFAULT_PARAM: Int = 3585 // 0xE01

    // -- GATT (identical profile to AiDEX X) --

    /** Service 0x181F. */
    val SERVICE_UUID: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")

    /** Command characteristic 0xF002. */
    val CHAR_COMMAND: UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")

    /** Private/pairing characteristic 0xF001. */
    val CHAR_PRIVATE: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")

    /** Live-data characteristic 0xF003. */
    val CHAR_NOTIFY: UUID = UUID.fromString("0000f003-0000-1000-8000-00805f9b34fb")

    /** Legacy characteristic slot 0xF005 (present, unused by G9 flow). */
    val CHAR_F005: UUID = UUID.fromString("0000f005-0000-1000-8000-00805f9b34fb")

    // -- Handshake framing (native `sendPairCommand` / `getSeed` /
    // `setSessionKey` / `startEncryption`) --

    /** F001 pair write = 16-B secret + 1-B appType. */
    const val PAIR_WRITE_LENGTH: Int = 17

    /** Seed read length (`getSeed`, w1=0x14). */
    const val SEED_LENGTH: Int = 20

    /** Session key length (`setSessionKey`, AES-128 size). */
    const val SESSION_KEY_LENGTH: Int = 16

    /** Seed-response verify length (`startEncryption`, 16 B + CRC-8 byte). */
    const val SEED_RESPONSE_LENGTH: Int = 17

    /** Phone app type byte (`pairWithType(1)`). */
    const val APP_TYPE_PHONE: Int = 1

    // -- Receive framing (`handleCommand`) --

    /** Encrypted-stream marker bytes. */
    const val MARKER_FF: Int = 0xFF
    const val MARKER_FE: Int = 0xFE

    /** Short notify lengths dispatched by `handleCommand` (16/17 B frames). */
    const val SHORT_FRAME_16: Int = 0x10
    const val SHORT_FRAME_17: Int = 0x11

    // -- Value constants --

    /** 10-bit glucose mask used by broadcast/history packing. */
    const val GLUCOSE_MASK_10BIT: Int = 0x03FF

    /** Broadcast validity sentinel (Cgm3 uses 0xFFFF, not X's 1023). */
    const val SENTINEL_BROADCAST: Int = 0xFFFF

    const val MIN_VALID_GLUCOSE_MGDL: Int = 20
    const val MAX_VALID_GLUCOSE_MGDL: Int = 500

    // -- Timeouts / lifecycle (from `CGMPairUtil` / `Cgm3TransmitterModelKt`) --

    /** Pairing attempt timeout. */
    const val PAIR_TIMEOUT_MILLIS: Long = 40_000L

    /** Resume-sensor (probe swap) timeout. */
    const val RESUME_SENSOR_TIMEOUT_MILLIS: Long = 30_000L
}
