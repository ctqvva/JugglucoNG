package tk.glucodata

import tk.glucodata.drivers.ManagedSensorUiFamily

/** Manufacturer identity used by sensor-list presentation. */
enum class SensorVendor(
    val labelRes: Int,
) {
    ABBOTT(R.string.sensor_vendor_abbott),
    SIBIONICS(R.string.sensor_vendor_sibionics),
    DEXCOM(R.string.sensor_vendor_dexcom),
    ROCHE(R.string.sensor_vendor_roche),
    MICROTECH(R.string.sensor_vendor_microtech),
    SINOCARE(R.string.sensor_vendor_sinocare),
    GLUTEC(R.string.sensor_vendor_glutec),
    YUWELL(R.string.sensor_vendor_yuwell),
    OTTAI(R.string.sensor_vendor_ottai),
    NIGHTSCOUT(R.string.sensor_type_nightscout),
    UNKNOWN(R.string.unknown),
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

/** Two stacked lines of the sensor card's badge. A blank [brand] means "draw a glyph instead". */
data class SensorBadge(val brand: String, val model: String)

/**
 * Badge lines for a sensor: the product line on top, the concrete model underneath.
 *
 * The model comes from what the device reported over BLE wherever a driver reads it, so an
 * iCan i6 says "i6" rather than inheriting the family's i3. Where a driver has no model to
 * report the second line is simply left blank rather than guessing one.
 */
fun sensorBadge(
    vendor: SensorVendor,
    type: SensorTypeName,
    vendorModel: String = "",
): SensorBadge = when (vendor) {
    SensorVendor.ABBOTT -> SensorBadge(
        "LIBRE",
        when (type) {
            SensorTypeName.LIBRE_2 -> "2"
            SensorTypeName.LIBRE_3 -> "3"
            else -> ""
        },
    )
    SensorVendor.SIBIONICS -> SensorBadge(
        "SIBI",
        when (type) {
            SensorTypeName.SIBIONICS_GS1 -> "GS1"
            SensorTypeName.SIBIONICS_2 -> "GS2"
            SensorTypeName.SIBIONICS_GS3 -> "GS3"
            else -> ""
        },
    )
    SensorVendor.DEXCOM -> SensorBadge("DEXCOM", "G7")
    SensorVendor.ROCHE -> SensorBadge("ACCU", "CHEK")
    SensorVendor.MICROTECH -> SensorBadge("AIDEX", modelToken(vendorModel))
    SensorVendor.SINOCARE -> SensorBadge("ICAN", modelToken(vendorModel))
    SensorVendor.GLUTEC -> SensorBadge("GLUTEC", "MQ")
    SensorVendor.YUWELL -> SensorBadge("ANYTIME", modelToken(vendorModel))
    SensorVendor.OTTAI -> SensorBadge("OTTAI", "CGM")
    SensorVendor.NIGHTSCOUT -> SensorBadge("NIGHT", "SCOUT")
    SensorVendor.UNKNOWN -> SensorBadge("", "")
}

/**
 * Squeezes a driver's model string into something that fits a badge line: the last word of
 * "iCan i6", the family of "CT3-Ultrasonic". Anything still too long is dropped rather than
 * truncated into nonsense.
 */
private fun modelToken(vendorModel: String): String {
    val trimmed = vendorModel.trim()
    if (trimmed.isEmpty() || trimmed.equals("Unknown", ignoreCase = true)) return ""
    val lastWord = trimmed.substringAfterLast(' ').trim()
    val shortened = if (lastWord.length > 6) lastWord.substringBefore('-') else lastWord
    return if (shortened.length in 1..6) shortened.uppercase() else ""
}

/** Concrete sensor family, shown as a badge beside the sensor name. */
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
