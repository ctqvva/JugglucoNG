package ist.com.sdk

/**
 * Official Yuwell 1.3.x algorithm output. The native library populates this
 * object through reflected setters.
 */
@Suppress("PropertyName")
class DataOutput {
    @JvmField var dayCount: Int = 0
    @JvmField var hourCount: Int = 0
    @JvmField var BG_MG_ADVICE: Int = 0
    @JvmField var GLU_MG: Int = 0
    @JvmField var BGCount: Int = 0
    @JvmField var BGICount: Int = 0
    @JvmField var warnCode: Int = 0
    @JvmField var errorCode: Int = 0
    @JvmField var trend: Int = 6
    @JvmField var calibrationStatus: Int = 0
    @JvmField var data_quality: Int = 0
    @JvmField var hypoglycemiaEarlyWarnMinutes: Int = 0
    @JvmField var hyperglycemiaEarlyWarnMinutes: Int = 0

    fun setCalibration(v: Int) { calibrationStatus = v }
    fun setDayCount(v: Int) { dayCount = v }
    fun setHourCount(v: Int) { hourCount = v }
    fun setBG_MG_ADVICE(v: Int) { BG_MG_ADVICE = v }
    fun setGLU_MG(v: Int) { GLU_MG = v }
    fun setBGCount(v: Int) { BGCount = v }
    fun setBGICount(v: Int) { BGICount = v }
    fun setWarnCode(v: Int) { warnCode = v }
    fun setErrorCode(v: Int) { errorCode = v }
    fun setTrend(v: Int) { trend = v }
    fun setCalibrationStatus(v: Int) { calibrationStatus = v }
    fun setData_quality(v: Int) { data_quality = v }
    fun setHypoglycemiaEarlyWarnMinutes(v: Int) { hypoglycemiaEarlyWarnMinutes = v }
    fun setHyperglycemiaEarlyWarnMinutes(v: Int) { hyperglycemiaEarlyWarnMinutes = v }

    fun getDayCount(): Int = dayCount
    fun getHourCount(): Int = hourCount
    fun getBG_MG_ADVICE(): Int = BG_MG_ADVICE
    fun getGLU_MG(): Int = GLU_MG
    fun getBGCount(): Int = BGCount
    fun getBGICount(): Int = BGICount
    fun getWarnCode(): Int = warnCode
    fun getErrorCode(): Int = errorCode
    fun getTrend(): Int = trend
    fun getCalibrationStatus(): Int = calibrationStatus
    fun getData_quality(): Int = data_quality
    fun getHypoglycemiaEarlyWarnMinutes(): Int = hypoglycemiaEarlyWarnMinutes
    fun getHyperglycemiaEarlyWarnMinutes(): Int = hyperglycemiaEarlyWarnMinutes
}
