package tk.glucodata.drivers.bioe

object BioeConstants {
    const val CMD_GET_DATA = 2
    const val CMD_INQ_LOCAL_DATA = 3
    const val CMD_GET_LOCAL_DATA = 4
    const val CMD_CLEAR_HIS = 5
    const val CMD_GET_VER = 6
    const val CMD_GET_SYSPARA = 7
    const val CMD_SET_SYSPARA = 8
    const val CMD_INQ_FLASH_DATA = 9
    const val CMD_GET_FLASH_DATA = 10
    const val CMD_CLEAR_FLASH = 11
    const val CMD_GET_CGMPARA = 12
    const val CMD_HEARTBEAT = 0xFF

    const val SCAN_NAME_PREFIX = "BECGM"
    const val FALLBACK_SERVICE_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val FALLBACK_NOTIFY_UUID = "0000ffe3-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val PREAMBLE_0 = 0x8A
    const val PREAMBLE_1 = 0xA8

    const val ADC_MAX = 4095
    const val ADC_FALLBACK = 776
    const val SAMPLE_INTERVAL_MS = 60_000L
    const val HISTORY_PAGE_SIZE = 100
    const val MAX_FLASH_REQUEST = 100

    const val MGDL_PER_MMOL = 18.0182f
    const val SENSOR_GEN = 0

    const val SENSOR_ID_PREFIX = "BIOE-"
    const val DEFAULT_K = 0.0
    const val DEFAULT_B = 0.0
    const val DEFAULT_LOW_CURRENT_NA = 0.0

    const val API_BASE_URL = "https://cgm.bioecare.com"
    const val API_DEVICE_LIST_PATH = "/api/device/using/list"
    const val SIGN_PREFIX = "mlhdCI6MTUx"
    const val SIGN_SECRET = "FmyG3nMiGM6H9FNFUR"
    const val SIGN_SUFFIX = "bioecare_app_release"
    const val API_CLIENT_TYPE = "android"
    const val API_PRODUCT_NAME = "scgm"
    const val API_APP_VERSION = "1.4.80(195)"
    const val API_CONNECT_TIMEOUT_MS = 15_000
    const val API_READ_TIMEOUT_MS = 20_000
}
