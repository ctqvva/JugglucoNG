// AnytimeQr.kt — Pure-Kotlin QR-code calibration parser.
//
// The sensor packaging may carry two different printable codes:
//
// 1. A factory calibration QR. The Anytime algorithm SDK extracts it via
//    `AlgorithmTools.decodeCT(char[])` in C, which matches one of three regex
//    patterns used by the official Yuwell 1.3.2 scanner:
//
//   Format A: ^[A-Z0-9][0-9](month)(K_3d)(R_3d)[0-9]{5}[0-9A-Z]{3}$
//   Format B: ^[1-3][1-9][A-Z0-9][0-9](month)(K_3d)(R_3d)[0-9]{6}[0-9A-Z]{3}$
//   Format C: ^[A-C][1-9][A-Z0-9][0-9](month)(K_3d)(R_3d)[0-9]{5}[0-9A-HJ-NP-Z][0-9A-Z]{3}$
//   Format D: ^[1-9A-ZABDEFYTSRQ][1-9][A-Z0-9][0-9](month)(K_3d)(R_3d)[0-9]{6}[0-9A-Z]{3}$
//
// Where:
//   month  = (0[1-9]|[1-9][0-9]|[A-Z][1-9A-Z])     ← 3 chars: numeric month or alpha unit code
//   K_3d   = (00[1-9]|0[1-9][0-9]|[1-9][0-9][0-9]) ← 3 digits in [001..999], scaled to K = nnn/100
//   R_3d   = same shape, scaled to R = nnn/10  (the official app's `Tool.getDecimalOneNumber`)
//
// 2. A 7-character manual code. The official app decodes:
//      K = code[2] + "." + code[3..4], R = code[5].
//
// 3. A UDI/product label, e.g. 0116975124206236112602191728021910CQ6212.
//    That is still an Anytime/Yuwell label, but it carries product/lot/date
//    metadata, not K/R calibration coefficients. We accept it as metadata-only
//    so setup can proceed using the linear fallback, and we never feed it into
//    the vendor algorithm as if it were a decoded calibration QR.
//
// On a real device we cross-validate factory calibration parsing against the
// JNI `decodeCT()` from the vendor `.so` (see AnytimeAlgorithm). Either source
// produces the `KRDecodeData` we feed into the bundled native `algorithm(DataInput)`.

package tk.glucodata.drivers.anytime

import java.util.Locale

/**
 * Calibration data extracted from the QR sticker. Mirrors `ist.com.sdk.KRDecodeData`.
 * `electrodeType` / `*TecNo` / `marketNo` are the chemistry IDs that select the
 * algorithm sub-routine inside libalgorithm-jni.so.
 */
data class AnytimeQrCalibration(
    val rawQr: String,
    val format: Format,

    /** Calibration slope (mmol/L per nA, before chemistry corrections). */
    val k: Float,
    /** Calibration intercept. */
    val r: Float,

    /** Sensor lifetime in days. Inferred from `marketNo` for known prefixes. */
    val lifeTime: Int,
    val productMonth: Int,
    val productYear: Int,

    val electrodeType: String,
    val electrodeTecNo: String,
    val enzymeTecNo: String,
    val membraneTecNo: String,
    val marketNo: String,
    val serialNo: String,
    val sensorNo: String,
    val unitOrder: Int,
    val voltageFlag: Int,
    val calibrationCount: Int,
) {
    enum class Format { A, B, C, D, MANUAL, UDI }

    val isFactoryCalibration: Boolean
        get() = format != Format.UDI && k > 0f && r > 0f

    companion object {
        // ---- Regex (verbatim from libalgorithm-jni.so .rodata) ----

        private val MONTH = "(0[1-9]|[1-9][0-9]|[A-Z][1-9A-Z])"
        private val THREE_DIGIT = "(00[1-9]|0[1-9][0-9]|[1-9][0-9][0-9])"

        @JvmField
        val PATTERN_A: Regex =
            Regex("^([A-Z0-9])([0-9])$MONTH$THREE_DIGIT$THREE_DIGIT([0-9]{5})([0-9A-Z]{3})$")
        @JvmField
        val PATTERN_B: Regex =
            Regex("^([1-3])([1-9])([A-Z0-9])([0-9])$MONTH$THREE_DIGIT$THREE_DIGIT([0-9]{6})([0-9A-Z]{3})$")
        @JvmField
        val PATTERN_C: Regex =
            Regex("^([A-C])([1-9])([A-Z0-9])([0-9])$MONTH$THREE_DIGIT$THREE_DIGIT([0-9]{5})([0-9A-HJ-NP-Z])([0-9A-Z]{3})$")
        @JvmField
        val PATTERN_D: Regex =
            Regex("^([1-9A-ZABDEFYTSRQ])([1-9])([A-Z0-9])([0-9])$MONTH$THREE_DIGIT$THREE_DIGIT([0-9]{6})([0-9A-Z]{3})$")
        @JvmField
        val PATTERN_MANUAL: Regex = Regex("^[A-Z0-9]{7}$")
        @JvmField
        val PATTERN_GS1_UDI: Regex = Regex("^01(\\d{14})11(\\d{6})17(\\d{6})10(.+)$")
    }
}

object AnytimeQr {

    /**
     * Official `PocDevice.getVoltage()` treats a numeric first QR character as
     * voltage mode 0. A non-numeric first character throws in `Integer.parseInt`
     * and selects mode 1. The parsed digit value itself is ignored.
     */
    @JvmStatic
    fun inferVoltageFlag(qr: String?): Int {
        val first = qr?.trim()?.firstOrNull() ?: return 0
        return if (first in '0'..'9') 0 else 1
    }

    /**
     * Parse a QR string into a calibration record. Factory calibration QRs
     * return decoded K/R values. GS1/UDI product labels return metadata-only
     * records with linear fallback defaults.
     */
    @JvmStatic
    fun parse(qr: String?): AnytimeQrCalibration? {
        val trimmed = qr?.trim()?.uppercase(Locale.US) ?: return null
        if (trimmed.isEmpty()) return null

        AnytimeQrCalibration.PATTERN_A.matchEntire(trimmed)?.let { return parseFormatA(trimmed, it) }
        AnytimeQrCalibration.PATTERN_B.matchEntire(trimmed)?.let { return parseFormatB(trimmed, it) }
        AnytimeQrCalibration.PATTERN_C.matchEntire(trimmed)?.let { return parseFormatC(trimmed, it) }
        AnytimeQrCalibration.PATTERN_D.matchEntire(trimmed)?.let { return parseFormatD(trimmed, it) }
        parseManual(trimmed)?.let { return it }
        parseGs1Udi(trimmed)?.let { return it }
        return null
    }

    private fun parseFormatA(qr: String, m: MatchResult): AnytimeQrCalibration {
        // Groups: 1=electrodeType, 2=year, 3=month, 4=K, 5=R, 6=serial, 7=marketNo
        val electrodeType = m.groupValues[1]
        val yearChar = m.groupValues[2]
        val month = m.groupValues[3]
        val kRaw = m.groupValues[4].toInt()
        val rRaw = m.groupValues[5].toInt()
        val serial = m.groupValues[6]
        val marketNo = m.groupValues[7]
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.A,
            k = kRaw / 100f,
            r = rRaw / 10f,
            lifeTime = inferLifeTimeDays(marketNo),
            productMonth = parseMonth(month),
            productYear = 2000 + (yearChar.toIntOrNull() ?: 0) + 10,
            electrodeType = electrodeType,
            electrodeTecNo = month,
            enzymeTecNo = m.groupValues[4],
            membraneTecNo = m.groupValues[7],
            marketNo = marketNo,
            serialNo = serial,
            sensorNo = qr.take(2),
            unitOrder = (electrodeType.firstOrNull()?.digitToIntOrNull() ?: 0),
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = 0,
        )
    }

    private fun parseFormatB(qr: String, m: MatchResult): AnytimeQrCalibration {
        // Groups: 1=unitOrder, 2=year, 3=electrodeType, 4=productMonthDigit,
        //         5=month, 6=K, 7=R, 8=serial(6), 9=marketNo(3)
        val unit = m.groupValues[1]
        val year = m.groupValues[2]
        val electrodeType = m.groupValues[3]
        val productMonthDigit = m.groupValues[4]
        val month = m.groupValues[5]
        val kRaw = m.groupValues[6].toInt()
        val rRaw = m.groupValues[7].toInt()
        val serial = m.groupValues[8]
        val marketNo = m.groupValues[9]
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.B,
            k = kRaw / 100f,
            r = rRaw / 10f,
            lifeTime = inferLifeTimeDays(marketNo),
            productMonth = parseMonth(month),
            productYear = 2000 + (year.toIntOrNull() ?: 0) + 10,
            electrodeType = electrodeType,
            electrodeTecNo = month,
            enzymeTecNo = m.groupValues[6],
            membraneTecNo = m.groupValues[7],
            marketNo = marketNo,
            serialNo = serial,
            sensorNo = qr.take(2),
            unitOrder = unit.firstOrNull()?.digitToIntOrNull() ?: 0,
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = productMonthDigit.toIntOrNull() ?: 0,
        )
    }

    private fun parseFormatC(qr: String, m: MatchResult): AnytimeQrCalibration {
        // Groups: 1=unit, 2=year, 3=electrodeType, 4=productMonthDigit,
        //         5=month, 6=K, 7=R, 8=serial(5), 9=lotChar, 10=marketNo(3)
        val unit = m.groupValues[1]
        val year = m.groupValues[2]
        val electrodeType = m.groupValues[3]
        val productMonthDigit = m.groupValues[4]
        val month = m.groupValues[5]
        val kRaw = m.groupValues[6].toInt()
        val rRaw = m.groupValues[7].toInt()
        val serial = m.groupValues[8] + m.groupValues[9]
        val marketNo = m.groupValues[10]
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.C,
            k = kRaw / 100f,
            r = rRaw / 10f,
            lifeTime = inferLifeTimeDays(marketNo),
            productMonth = parseMonth(month),
            productYear = 2000 + (year.toIntOrNull() ?: 0) + 10,
            electrodeType = electrodeType,
            electrodeTecNo = month,
            enzymeTecNo = m.groupValues[4],
            membraneTecNo = m.groupValues[5],
            marketNo = marketNo,
            serialNo = serial,
            sensorNo = qr.take(2),
            unitOrder = unit.firstOrNull()?.digitToIntOrNull() ?: 0,
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = productMonthDigit.toIntOrNull() ?: 0,
        )
    }

    private fun parseFormatD(qr: String, m: MatchResult): AnytimeQrCalibration {
        val unit = m.groupValues[1]
        val year = m.groupValues[2]
        val electrodeType = m.groupValues[3]
        val productMonthDigit = m.groupValues[4]
        val month = m.groupValues[5]
        val kRaw = m.groupValues[6].toInt()
        val rRaw = m.groupValues[7].toInt()
        val serial = m.groupValues[8]
        val marketNo = m.groupValues[9]
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.D,
            k = kRaw / 100f,
            r = rRaw / 10f,
            lifeTime = inferLifeTimeDays(marketNo),
            productMonth = parseMonth(month),
            productYear = 2000 + (year.toIntOrNull() ?: 0) + 10,
            electrodeType = electrodeType,
            electrodeTecNo = month,
            enzymeTecNo = m.groupValues[6],
            membraneTecNo = m.groupValues[7],
            marketNo = marketNo,
            serialNo = serial,
            sensorNo = qr.take(2),
            unitOrder = unit.firstOrNull()?.digitToIntOrNull() ?: 0,
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = productMonthDigit.toIntOrNull() ?: 0,
        )
    }

    private fun parseManual(qr: String): AnytimeQrCalibration? {
        if (!AnytimeQrCalibration.PATTERN_MANUAL.matches(qr)) return null
        val k = runCatching { "${qr.substring(2, 3)}.${qr.substring(3, 5)}".toFloat() }.getOrNull()
            ?: return null
        val r = runCatching { qr.substring(5, 6).toFloat() }.getOrNull() ?: return null
        if (k <= 0f || r <= 0f) return null
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.MANUAL,
            k = k,
            r = r,
            lifeTime = AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS,
            productMonth = 0,
            productYear = 0,
            electrodeType = "",
            electrodeTecNo = "",
            enzymeTecNo = "",
            membraneTecNo = "",
            marketNo = "",
            serialNo = "",
            sensorNo = qr.take(2),
            unitOrder = 0,
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = 0,
        )
    }

    private fun parseGs1Udi(qr: String): AnytimeQrCalibration? {
        val normalized = normalizeGs1(qr)
        val m = AnytimeQrCalibration.PATTERN_GS1_UDI.matchEntire(normalized) ?: return null
        val gtin = m.groupValues[1]
        val manufactureDate = m.groupValues[2]
        val expiryDate = m.groupValues[3]
        val lot = m.groupValues[4].substringBefore('\u001d').trim()

        if (!isGs1DateToken(manufactureDate) || !isGs1DateToken(expiryDate) || lot.isBlank()) {
            return null
        }

        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.UDI,
            // The package UDI has no calibration coefficients. These match the
            // computeLinear fallback defaults and must not be pushed as factory K/R.
            k = 0.30f,
            r = 50f,
            lifeTime = AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS,
            productMonth = manufactureDate.substring(2, 4).toIntOrNull() ?: 0,
            productYear = 2000 + (manufactureDate.substring(0, 2).toIntOrNull() ?: 0),
            electrodeType = "",
            electrodeTecNo = "",
            enzymeTecNo = "",
            membraneTecNo = "",
            marketNo = lot,
            serialNo = gtin,
            sensorNo = gtin.takeLast(6),
            unitOrder = 0,
            voltageFlag = inferVoltageFlag(normalized),
            calibrationCount = 0,
        )
    }

    private fun normalizeGs1(qr: String): String =
        qr.uppercase(Locale.US)
            .replace("(", "")
            .replace(")", "")
            .filterNot { it.isWhitespace() }

    private fun isGs1DateToken(token: String): Boolean {
        if (token.length != 6 || token.any { it !in '0'..'9' }) return false
        val month = token.substring(2, 4).toIntOrNull() ?: return false
        val day = token.substring(4, 6).toIntOrNull() ?: return false
        return month in 1..12 && day in 1..31
    }

    private fun parseMonth(token: String): Int {
        if (token.isEmpty()) return 0
        token.toIntOrNull()?.let { return it }
        // Alpha month code: A=10, B=11, C=12. (Limited support — the SDK accepts
        // [A-Z][1-9A-Z] for OEM batches; we just take the first alpha as 10+.)
        val first = token.firstOrNull() ?: return 0
        if (first in 'A'..'Z') return 10 + (first - 'A')
        return 0
    }

    /**
     * Heuristic lifetime inference from `marketNo` codes. The SDK's `EDevice`
     * lookup pairs sensor prefix + chemistry to lifetime; we approximate from
     * the chemistry suffix alone since we may not always know the prefix.
     * Anytime CT3 sensors are 14, 15 or 16 days.
     */
    private fun inferLifeTimeDays(marketNo: String): Int = when {
        marketNo.startsWith("44") -> 16
        marketNo.startsWith("55") -> 15
        marketNo.startsWith("33") -> 14
        else -> AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS
    }

}
