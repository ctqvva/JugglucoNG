package ist.com.sdk

/**
 * Version metadata returned by `AlgorithmTools.getVersion()`.
 * The vendor JNI populates this object through reflected `setX(String)` calls.
 */
@Suppress("PropertyName")
class SDKVersion {
    @JvmField var version: String? = null
    @JvmField var buildDate: String? = null
    @JvmField var algorithm: Int = 0

    @JvmField var YUWELL: String? = null
    @JvmField var POC: String? = null
    @JvmField var ADC: String? = null
    @JvmField var POC2_55: String? = null
    @JvmField var POC2_44: String? = null
    @JvmField var POC2A_44: String? = null
    @JvmField var POC3: String? = null
    @JvmField var POC6: String? = null
    @JvmField var POC4: String? = null
    @JvmField var POC6_2: String? = null

    fun getVersion(): String? = version
    fun setVersion(v: String?) { version = v }
    fun getBuildDate(): String? = buildDate
    fun setBuildDate(v: String?) { buildDate = v }
    fun getAlgorithm(): Int = algorithm
    fun setAlgorithm(v: Int) { algorithm = v }

    fun getYUWELL(): String? = YUWELL
    fun setYUWELL(v: String?) { YUWELL = v }
    fun getPOC(): String? = POC
    fun setPOC(v: String?) { POC = v }
    fun getADC(): String? = ADC
    fun setADC(v: String?) { ADC = v }
    fun getPOC2_55(): String? = POC2_55
    fun setPOC2_55(v: String?) { POC2_55 = v }
    fun getPOC2_44(): String? = POC2_44
    fun setPOC2_44(v: String?) { POC2_44 = v }
    fun getPOC2A_44(): String? = POC2A_44
    fun setPOC2A_44(v: String?) { POC2A_44 = v }
    fun getPOC3(): String? = POC3
    fun setPOC3(v: String?) { POC3 = v }
    fun getPOC6(): String? = POC6
    fun setPOC6(v: String?) { POC6 = v }
    fun getPOC4(): String? = POC4
    fun setPOC4(v: String?) { POC4 = v }
    fun getPOC6_2(): String? = POC6_2
    fun setPOC6_2(v: String?) { POC6_2 = v }

    fun getVersionName(algorithm: Int): String = when (algorithm) {
        1 -> POC
        2 -> "2.0.0"
        3 -> POC3
        4 -> YUWELL
        5 -> POC2_44
        6 -> POC2_55
        7 -> POC2A_44
        8 -> "1.0.0_202207071544"
        9 -> POC6
        10, 11 -> POC4
        12 -> POC6_2
        else -> null
    } ?: ""
}
