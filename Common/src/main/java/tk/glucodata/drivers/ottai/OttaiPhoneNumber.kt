package tk.glucodata.drivers.ottai

internal enum class OttaiSmsCountry(
    val phoneCode: String,
    val prefix: String,
    val subscriberDigits: Int,
    private val mobileNumber: Regex,
) {
    MAINLAND_CHINA("86", "+86", 11, Regex("1[3-9]\\d{9}")),
    HONG_KONG("852", "+852", 8, Regex("[4-9]\\d{7}")),
    ;

    fun accepts(raw: String): Boolean = mobileNumber.matches(raw)
}

/** Validates subscriber digits entered after the selected SMS country prefix. */
internal fun normalizeOttaiPhone(raw: String, country: OttaiSmsCountry): String? =
    raw.takeIf(country::accepts)
