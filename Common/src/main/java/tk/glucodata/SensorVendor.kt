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

/** The sensor card's badge. A blank [brand] means "draw a glyph instead". */
data class SensorBadge(val brand: String, val model: String) {
    /** Brand and model together, for the badges short enough to read on one line. */
    val inlineText: String get() = if (model.isEmpty()) brand else "$brand $model"

    /**
     * Whether the two parts have to stack. A one-character model like Sibionics' reads oddly
     * on a row of its own, so short pairs stay inline and only genuinely long ones split.
     */
    val stacked: Boolean get() = model.isNotEmpty() && inlineText.length > 7
}

/**
 * Badge lines for a sensor: the product line on top, the concrete model underneath.
 *
 * The model line is whatever the driver reported for this specific device — "Sibionics 2",
 * "iCan i6", "CT5" — because that is the name the sensor wizard and the Model row already use.
 * Only when a driver reports nothing does it fall back to the family's own name, and when even
 * that is unknown the line is left blank rather than inventing a model.
 */
fun sensorBadge(
    vendor: SensorVendor,
    type: SensorTypeName,
    vendorModel: String = "",
): SensorBadge {
    val reported = modelToken(vendorModel)
    val model = reported.ifEmpty { fallbackModelToken(type) }
    return when (vendor) {
        SensorVendor.ABBOTT -> SensorBadge("LIBRE", model)
        SensorVendor.SIBIONICS -> SensorBadge("SIBI", model)
        SensorVendor.DEXCOM -> SensorBadge("DEX", model)
        SensorVendor.ROCHE -> SensorBadge("ACCU", model)
        SensorVendor.MICROTECH -> SensorBadge("AIDEX", model)
        SensorVendor.SINOCARE -> SensorBadge("ICAN", model)
        SensorVendor.GLUTEC -> SensorBadge("GLUTEC", model)
        SensorVendor.YUWELL -> SensorBadge("ANYTIME", model)
        SensorVendor.OTTAI -> SensorBadge("OTTAI", model)
        SensorVendor.NIGHTSCOUT -> SensorBadge("NIGHT", "SCOUT")
        SensorVendor.UNKNOWN -> SensorBadge("", "")
    }
}

/** Model name for sensors whose driver reports none, e.g. the natively-decoded Libre and Dexcom. */
private fun fallbackModelToken(type: SensorTypeName): String = when (type) {
    SensorTypeName.LIBRE_2 -> "2"
    SensorTypeName.LIBRE_3 -> "3"
    SensorTypeName.SIBIONICS_GS1 -> "GS1"
    SensorTypeName.SIBIONICS_2 -> "2"
    SensorTypeName.SIBIONICS_GS3 -> "GS3"
    SensorTypeName.DEXCOM_G7 -> "G7"
    SensorTypeName.ACCUCHEK_SMARTGUIDE -> "CHEK"
    SensorTypeName.MQ -> "MQ"
    else -> ""
}

/** Words that name no particular model, so the badge is better off with one line than two. */
private val genericModelWords = setOf("CGM", "SENSOR", "GLUCOSE", "UNKNOWN")

/**
 * Squeezes a driver's model string into something that fits a badge line: the last word of
 * "Sibionics 2" or "iCan i6", the family of "CT3-Ultrasonic". Anything still too long, or that
 * names nothing ("Ottai CGM"), is dropped rather than padding the badge with a second line.
 */
private fun modelToken(vendorModel: String): String {
    val trimmed = vendorModel.trim()
    if (trimmed.isEmpty()) return ""
    val lastWord = trimmed.substringAfterLast(' ').trim()
    val shortened = if (lastWord.length > 6) lastWord.substringBefore('-') else lastWord
    if (shortened.length !in 1..6) return ""
    val upper = shortened.uppercase()
    return if (upper in genericModelWords) "" else upper
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
