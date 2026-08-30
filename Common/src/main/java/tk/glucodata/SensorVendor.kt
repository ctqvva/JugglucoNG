package tk.glucodata

import tk.glucodata.drivers.ManagedSensorUiFamily

/** Manufacturer identity used by sensor-list presentation. */
enum class SensorVendor(
    val badgeText: String,
    val labelRes: Int,
) {
    ABBOTT("AB", R.string.sensor_vendor_abbott),
    SIBIONICS("SI", R.string.sensor_vendor_sibionics),
    DEXCOM("DX", R.string.sensor_vendor_dexcom),
    ROCHE("RO", R.string.sensor_vendor_roche),
    MICROTECH("MT", R.string.sensor_vendor_microtech),
    SINOCARE("SC", R.string.sensor_vendor_sinocare),
    GLUTEC("MQ", R.string.sensor_vendor_glutec),
    YUWELL("YW", R.string.sensor_vendor_yuwell),
    OTTAI("OT", R.string.sensor_vendor_ottai),
    UNKNOWN("?", R.string.unknown),
    ;

    companion object {
        private const val LEGACY_AIDEX_STREAM_KIND = 0x100

        fun fromManagedFamily(family: ManagedSensorUiFamily): SensorVendor = when (family) {
            ManagedSensorUiFamily.AIDEX -> MICROTECH
            ManagedSensorUiFamily.ICAN -> SINOCARE
            ManagedSensorUiFamily.MQ -> GLUTEC
            ManagedSensorUiFamily.ANYTIME -> YUWELL
            ManagedSensorUiFamily.OTTAI -> OTTAI
            ManagedSensorUiFamily.SIBIONICS -> SIBIONICS
            ManagedSensorUiFamily.GENERIC -> UNKNOWN
        }

        fun fromNativeKind(kind: Int): SensorVendor = when (kind) {
            SensorSourceResolver.SENSOR_KIND_LIBRE2,
            SensorSourceResolver.SENSOR_KIND_LIBRE3 -> ABBOTT
            SensorSourceResolver.SENSOR_KIND_SIBIONICS -> SIBIONICS
            SensorSourceResolver.SENSOR_KIND_DEXCOM -> DEXCOM
            SensorSourceResolver.SENSOR_KIND_ACCUCHEK -> ROCHE
            SensorSourceResolver.SENSOR_KIND_AIDEX,
            LEGACY_AIDEX_STREAM_KIND -> MICROTECH
            else -> UNKNOWN
        }
    }
}
