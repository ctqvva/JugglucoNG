package tk.glucodata.data.meal

/**
 * GS1 retail barcodes as the scanner delivers them: EAN-8, UPC-A (12), EAN-13, GTIN-14. A
 * sensor QR or any other payload fails the check digit and is rejected so the scanner keeps
 * running instead of looking up garbage.
 */
object ProductBarcode {
    /** Returns the canonical lookup key (UPC-A zero-padded to 13 digits) or null. */
    fun normalize(raw: String?): String? {
        val digits = raw?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        if (digits.length !in setOf(8, 12, 13, 14)) return null
        if (!hasValidCheckDigit(digits)) return null
        return if (digits.length == 12) "0$digits" else digits
    }

    fun hasValidCheckDigit(digits: String): Boolean {
        if (digits.length < 8 || !digits.all(Char::isDigit)) return false
        var sum = 0
        // Weights 3,1,3,1... from the right, excluding the check digit.
        for (i in digits.length - 2 downTo 0) {
            val digit = digits[i] - '0'
            val fromRight = digits.length - 2 - i
            sum += if (fromRight % 2 == 0) digit * 3 else digit
        }
        val check = (10 - sum % 10) % 10
        return check == digits.last() - '0'
    }
}
