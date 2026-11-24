package tk.glucodata.drivers.scgm

object ScgmConstants {
    const val CMD_HEARTBEAT = 0
    const val CMD_CURRENT_RESULT = 1
    const val CMD_QUERY_HISTORY = 2
    const val CMD_HISTORY_PAGE = 3
    const val CMD_CLEAR_HISTORY = 4
    const val CMD_ACTIVATION = 5
    const val CMD_TERMINAL_INFO = 16
    const val CMD_AUTHENTICATE = 17

    const val PREAMBLE_0 = 0x5A
    const val PREAMBLE_1 = 0xA5
    const val FRAME_TRAILER_SIZE = 4

    const val SCAN_NAME_PREFIX = "SCGM"
    const val BIOE_NAME_PREFIX = "BECGM"
    const val NOVO_NAME_PREFIX = "DN-"

    const val REQUESTED_MTU = 220
    const val WRITE_CHUNK_SIZE = 51

    const val FALLBACK_SERVICE_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val FALLBACK_NOTIFY_UUID = "0000ffe3-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val HISTORY_PAGE_SIZE = 100
    const val SAMPLE_INTERVAL_MS = 60_000L
    const val LOW_BATTERY_MV = 1900

    const val MGDL_PER_MMOL = 18.0182f
    const val SENSOR_GEN = 0

    const val SENSOR_ID_PREFIX = "SCGM-"
    const val DEFAULT_K = 0.0
    const val DEFAULT_B = 0.0
    const val DEFAULT_LOW_CURRENT_NA = 0.0
}
