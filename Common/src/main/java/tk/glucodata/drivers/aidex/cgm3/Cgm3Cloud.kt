// JugglucoNG — AiDex CGM3 (G9) driver
// Cgm3Cloud.kt — Vendor cloud endpoints and provisioning payload shapes for
// the CGM3 (G9) transmitter/probe lifecycle.
//
// Endpoint paths recovered from decompiled
// `com.microtech.aidexx.common.net.ApiServiceKt` (v1.16.1, 136 constants).
// Same host + RSA envelope as the X-family client (`AiDexCnCloudClient`);
// only the per-endpoint request fields still need mapping from callers
// (e.g. `TransmitterManager$buildModel$historyDevice`).
// Full analysis: docs/aidex-g9-cgm3-protocol.md (5.5).

package tk.glucodata.drivers.aidex.cgm3

object Cgm3Cloud {

    /** Same base as the X-family client (`AiDexCnCloudClient.BASE_URL`). */
    const val BASE_URL: String = "https://china.pancares.com"

    /** Probe activation: issues `ActivateParam{iv, ciphertext, tag}` (hex). */
    const val ACTIVATION: String = "/backend/aidex-x/cgmDevice/activation"

    /** Transmitter registration (first pair). */
    const val DEVICE_REGISTER: String = "/backend/aidex-x/cgmDevice/userDeviceRegister"

    /** Transmitter unregistration (unpair). */
    const val DEVICE_UNREGISTER: String = "/backend/aidex-x/cgmDevice/deviceUnRegister"

    /**
     * Source of `HistoryDeviceInfo` (session `deviceKey`, `publicKey`,
     * `communicationKey`, `sensorId`, `transmitterSn`, `et`).
     */
    const val HISTORY_DEVICE: String = "/backend/aidex-x/cgmDevice/getFirstAndLastDeviceInfo"

    /** Per-user device info. */
    const val USER_DEVICE_INFO: String = "/backend/aidex-x/cgmDevice/getUserDeviceInfo"

    /** Per-sensor pairing material (same shape as X: secret + IV). */
    const val SN_CONFIG: String = "/backend/aidex-x/cgmDevice/getSnConfig"

    /** Probe-SN verification. */
    const val VERIFY_SENSOR_SN: String = "/backend/aidex-x/cgmDevice/v2/verifySensorSn"

    /** Server-driven BLE-name allowlist (`BleNameInfo` rows). */
    const val BLUETOOTH_DICT: String = "/backend/aidex-x/bluetooth/dict/v2/findAllBluetoothDictList"

    /** Device history by user + sensor ids. */
    const val DEVICE_HISTORY: String = "/backend/aidex-x/cgmDevice/getDeviceHistoryByUserIdAndSensorIds"

    const val DEVICE_HEARTBEAT: String = "/backend/aidex-x/cgmDevice/deviceHeartBeat"
    const val DEVICE_STATUS_UPDATE: String = "/backend/aidex-x/cgmDevice/deviceStatusUpdate"

    /**
     * `PairDynamicKeyInfo{publicKey, communicationKey, deviceSn}` JSON keys
     * (decompiled entity; values are hex-or-base64 16-B material).
     */
    object DynamicKeyJson {
        const val PUBLIC_KEY: String = "publicKey"
        const val COMMUNICATION_KEY: String = "communicationKey"
        const val DEVICE_SN: String = "deviceSn"
    }

    /**
     * `BleNameInfo{bluetoothName, id, isSpecial, remark, sort, status}`
     * JSON keys.
     */
    object BleNameJson {
        const val BLUETOOTH_NAME: String = "bluetoothName"
        const val IS_SPECIAL: String = "isSpecial"
        const val STATUS: String = "status"
    }

    /**
     * `ActivateParam{iv, ciphertext, tag}` JSON keys (hex strings).
     */
    object ActivateParamJson {
        const val IV: String = "iv"
        const val CIPHERTEXT: String = "ciphertext"
        const val TAG: String = "tag"
    }
}
