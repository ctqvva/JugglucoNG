// JugglucoNG — AiDex Native Kotlin Driver
// AiDexOpcodes.kt — Wire opcodes and constants for F002 commands
//
// These are the actual bytes written to F002 (before encryption).
// Mapping confirmed by ARM64 disassembly of libblecomm-lib.so.

package tk.glucodata.drivers.aidex.native.protocol

object AiDexOpcodes {

    // -- F002 Wire Opcodes --

    /**
     * Official startup/device-info query.
     *

     */
    const val GET_STARTUP_DEVICE_INFO: Int = 0x10

    /**
     * Back-compat alias for older notes/tests that still call raw `0x10`
     * "post-bond config".
     */
    const val POST_BOND_CONFIG: Int = GET_STARTUP_DEVICE_INFO

    /** Get broadcast data (live glucose from advertisement-style query) */
    const val GET_BROADCAST_DATA: Int = 0x11

    /** Start new sensor (set activation datetime) */
    const val SET_NEW_SENSOR: Int = 0x20

    /**
     * Legacy follow-up query for sensor-local start time.
     *
     * Static RE says the official native wrapper names raw `0x21` as
     * `getStartTime()`. Older local code treated it as a combined metadata
     * query; keep only the start-time naming going forward.
     */
    const val GET_LOCAL_START_TIME: Int = 0x21

    /**
     * Official native wrapper name for raw `0x21`.
     */
    const val GET_START_TIME: Int = GET_LOCAL_START_TIME

    /** Get history range (briefStart, rawStart, newest offset) */
    const val GET_HISTORY_RANGE: Int = 0x22

    /** Get raw/calibrated history page (2-byte entries, 10-bit packed) */
    const val GET_HISTORIES_RAW: Int = 0x23

    /** Get brief history page (5-byte entries: i1, i2, vc) */
    const val GET_HISTORIES: Int = 0x24

    /** Set calibration (send blood glucose reference to sensor) */
    const val SET_CALIBRATION: Int = 0x25

    /** Get calibration range (start index, newest index) */
    const val GET_CALIBRATION_RANGE: Int = 0x26

    /** Get calibration record by index */
    const val GET_CALIBRATION: Int = 0x27

    /**
     * Set default params.
     *
     * Confirmed from official native `AidexXController.DefaultParam` long-write
     * object. Raw command byte is `0x30`, wrapper operation id is `0x300`.
     */
    const val SET_DEFAULT_PARAM: Int = 0x30

    /**
     * Get default params.
     *
     * Confirmed from official native `AidexXController.DefaultParam` query path.
     * Raw command byte is `0x31`, wrapper operation id is `0x301`.
     */
    const val GET_DEFAULT_PARAM: Int = 0x31

    /**
     * Get sensor check.
     *
     * Confirmed from official native `AidexXController.getSensorCheck(index)`.
     */
    const val GET_SENSOR_CHECK: Int = 0x32

    /**
     * Get auto-update status.
     *
     * Confirmed from official native `AidexXController.getAutoUpdateStatus()`.
     */
    const val GET_AUTO_UPDATE_STATUS: Int = 0x33

    /**
     * Enable sensor auto-update push mode.
     *
     * Confirmed from official `AidexXController.setAutoUpdateStatus()` native path:
     * controller opcode `0x34`, 1-byte payload set to `0x01`.
     */
    const val SET_AUTO_UPDATE_STATUS: Int = 0x34

    /**
     * Enable dynamic advertisement mode.
     *
     * Confirmed from official `AidexXController.setDynamicAdvMode(mode)` native path:
     * controller opcode `0x35`, 1-byte payload carrying the requested mode.
     */
    const val SET_DYNAMIC_ADV_MODE: Int = 0x35

    /** Delete vendor bond (clears sensor's internal bond state) */
    const val DELETE_BOND: Int = 0xF2

    /** Separate hardware-maintenance reset; do not chain after lifecycle clear */
    const val RESET: Int = 0xF0

    /** Put sensor into shelf/shipping mode */
    const val SHELF_MODE: Int = 0xF1

    /** Clear sensor storage and restart its lifecycle */
    const val CLEAR_STORAGE: Int = 0xF3

    // -- F003 Frame Constants --

    /** Length of a glucose data frame on F003 */
    const val DATA_FRAME_LENGTH: Int = 17

    /** Length of a status/keepalive frame on F003 */
    const val STATUS_FRAME_LENGTH: Int = 5

    /** 10-bit glucose mask for packed value extraction */
    const val GLUCOSE_MASK: Int = 0x03FF

    /** Maximum valid glucose reading in mg/dL */
    const val MAX_VALID_GLUCOSE: Int = 500

    /** Minimum valid glucose reading in mg/dL */
    const val MIN_VALID_GLUCOSE: Int = 20

    /** Sentinel value indicating invalid/no reading (10-bit max = 1023) */
    const val SENTINEL_GLUCOSE: Int = 1023

    // -- F003 Opcode Scaling --

    /** Direct mg/dL reading opcodes, scaling factor = 1.0 */
    val DIRECT_OPCODES: Set<Int> = setOf(0xA1, 0xA4, 0x5B, 0xD7)

    /** Half-scale reading opcodes, scaling factor = 0.5 */
    val HALF_SCALE_OPCODES: Set<Int> = setOf(0xD2)

    /** Returns the scaling factor for a given opcode, or null if unknown */
    fun scalingFactor(opcode: Int): Float? {
        if (opcode in DIRECT_OPCODES) return 1.0f
        if (opcode in HALF_SCALE_OPCODES) return 0.5f
        return null
    }

    // -- History Row Sizes --

    /** Row size for 0x23 calibrated history records (2 bytes per row) */
    const val HISTORY_RAW_ROW_SIZE: Int = 2

    /** Row size for 0x24 brief history records (5 bytes per row: i1, i2, vc) */
    const val HISTORY_BRIEF_ROW_SIZE: Int = 5

    /** Row size for calibration records (8 bytes per row) */
    const val CALIBRATION_ROW_SIZE: Int = 8

    // -- Advertisement --

    /** AiDex manufacturer company ID (Bluetooth SIG) */
    const val COMPANY_ID: Int = 0x0059

    /** Known AiDex device name prefixes */
    val KNOWN_NAME_PREFIXES: List<String> = listOf("AiDex", "AiDEX", "AIDEX", "Linx", "LINX", "CGM", "Smart-")

    /** Check if a device name looks like an AiDex sensor */
    fun isAiDexDevice(name: String?): Boolean {
        if (name == null) return false
        return KNOWN_NAME_PREFIXES.any { name.startsWith(it) }
    }
}
