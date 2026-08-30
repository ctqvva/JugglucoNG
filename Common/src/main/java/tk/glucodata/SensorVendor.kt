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
    NIGHTSCOUT("", R.string.sensor_type_nightscout),
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
            ManagedSensorUiFamily.NIGHTSCOUT -> NIGHTSCOUT
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

/** Concrete sensor family shown beside the vendor badge. */
enum class SensorTypeName(
    val labelRes: Int,
) {
    LIBRE_2(R.string.sensor_type_libre_2),
    LIBRE_3(R.string.sensor_type_libre_3),
    SIBIONICS_GS1(R.string.sensor_type_sibionics_gs1),
    SIBIONICS_2(R.string.sensor_type_sibionics_2),
    SIBIONICS_GS3(R.string.sensor_type_sibionics_gs3),
    DEXCOM_G7(R.string.sensor_type_dexcom_g7),
    ACCUCHEK_SMARTGUIDE(R.string.sensor_type_accuchek_smartguide),
    AIDEX_LINX(R.string.sensor_type_aidex_linx),
    ICAN_I3(R.string.sensor_type_ican_i3),
    MQ(R.string.sensor_type_mq),
    ANYTIME(R.string.sensor_type_anytime),
    OTTAI_CGM(R.string.sensor_type_ottai),
    NIGHTSCOUT(R.string.sensor_type_nightscout),
    UNKNOWN(R.string.unknown),
    ;

    companion object {
        private const val LEGACY_AIDEX_STREAM_KIND = 0x100

        fun fromManagedFamily(
            family: ManagedSensorUiFamily,
            vendorModel: String = "",
        ): SensorTypeName = when (family) {
            ManagedSensorUiFamily.AIDEX -> AIDEX_LINX
            ManagedSensorUiFamily.ICAN -> ICAN_I3
            ManagedSensorUiFamily.MQ -> MQ
            ManagedSensorUiFamily.ANYTIME -> ANYTIME
            ManagedSensorUiFamily.OTTAI -> OTTAI_CGM
            ManagedSensorUiFamily.SIBIONICS -> when {
                vendorModel.equals("Sibionics 2", ignoreCase = true) -> SIBIONICS_2
                vendorModel.equals("Sibionics GS3", ignoreCase = true) -> SIBIONICS_GS3
                else -> SIBIONICS_GS1
            }
            ManagedSensorUiFamily.NIGHTSCOUT -> NIGHTSCOUT
            ManagedSensorUiFamily.GENERIC -> UNKNOWN
        }

        fun fromNativeKind(
            kind: Int,
            isSibionics2: Boolean = false,
        ): SensorTypeName = when (kind) {
            SensorSourceResolver.SENSOR_KIND_LIBRE2 -> LIBRE_2
            SensorSourceResolver.SENSOR_KIND_LIBRE3 -> LIBRE_3
            SensorSourceResolver.SENSOR_KIND_SIBIONICS ->
                if (isSibionics2) SIBIONICS_2 else SIBIONICS_GS1
            SensorSourceResolver.SENSOR_KIND_DEXCOM -> DEXCOM_G7
            SensorSourceResolver.SENSOR_KIND_ACCUCHEK -> ACCUCHEK_SMARTGUIDE
            SensorSourceResolver.SENSOR_KIND_AIDEX,
            LEGACY_AIDEX_STREAM_KIND -> AIDEX_LINX
            else -> UNKNOWN
        }
    }
}
