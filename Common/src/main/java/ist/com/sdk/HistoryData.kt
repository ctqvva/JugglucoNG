package ist.com.sdk

/**
 * Batch input to `AlgorithmTools.algorithmGlucose` (history backfill).
 * Mirrors the vendor SDK's POJO. Field names and `setX` setters are required
 * because the JNI calls them via reflection.
 */
@Suppress("PropertyName")
class HistoryData {
    @JvmField var Ibs: FloatArray = FloatArray(0)
    @JvmField var Iws: FloatArray = FloatArray(0)
    @JvmField var Ts: FloatArray = FloatArray(0)
    @JvmField var k0: Float = 0f
    @JvmField var r: Float = 0f
    @JvmField var age: Int = 0
    @JvmField var algorithm: Int = 0
    @JvmField var batch: String = ""
    @JvmField var checkErrorContinuousAbnormalCurrentToGlucoseIds: IntArray = IntArray(0)
    @JvmField var endCount: Int = 0
    @JvmField var enzyme_activity: Float = 0f
    @JvmField var gender: Int = 0
    @JvmField var glucoseId: Int = 0
    @JvmField var height: Int = 0
    @JvmField var initCount: Int = 0
    @JvmField var left: Float = 0f
    @JvmField var len_iw: Float = 0f
    @JvmField var lifeTime: Int = 0
    @JvmField var membrane_layers: Float = 0f
    @JvmField var name: String = ""
    @JvmField var newBgToGlucoseIds: IntArray = IntArray(0)
    @JvmField var newBgValues: IntArray = IntArray(0)
    @JvmField var productMonth: Int = 0
    @JvmField var right: Float = 0f
    @JvmField var sensorInfo: String = ""
    @JvmField var sickDuration: Float = 0f
    @JvmField var weight: Int = 0
    @JvmField var width: Float = 0f
    @JvmField var startDay: Int = 0
    @JvmField var startHour: Int = 0
    @JvmField var startMinute: Int = 0
    @JvmField var startMonth: Int = 0
    @JvmField var startYear: Int = 0
    @JvmField var userType: Int = 0
    @JvmField var algorithmUpgradeGlucoseId: Int = 0
    @JvmField var algorithmUpgradeGLUMG: IntArray = IntArray(0)

    fun setIbs(v: FloatArray) { Ibs = v }
    fun setIws(v: FloatArray) { Iws = v }
    fun setTs(v: FloatArray) { Ts = v }
    fun setK0(v: Float) { k0 = v }
    fun setR(v: Float) { r = v }
    fun setAge(v: Int) { age = v }
    fun setAlgorithm(v: Int) { algorithm = v }
    fun setBatch(v: String) { batch = v }
    fun setCheckErrorContinuousAbnormalCurrentToGlucoseIds(v: IntArray) {
        checkErrorContinuousAbnormalCurrentToGlucoseIds = v
    }
    fun setEndCount(v: Int) { endCount = v }
    fun setEnzymeActivity(v: Float) { enzyme_activity = v }
    fun setGender(v: Int) { gender = v }
    fun setGlucoseId(v: Int) { glucoseId = v }
    fun setHeight(v: Int) { height = v }
    fun setInitCount(v: Int) { initCount = v }
    fun setLeft(v: Float) { left = v }
    fun setLenIw(v: Float) { len_iw = v }
    fun setLen_iw(v: Float) { len_iw = v }
    fun setLifeTime(v: Int) { lifeTime = v }
    fun setMembraneLayers(v: Float) { membrane_layers = v }
    fun setMembrane_layers(v: Float) { membrane_layers = v }
    fun setName(v: String) { name = v }
    fun setNewBgToGlucoseIds(v: IntArray) { newBgToGlucoseIds = v }
    fun setNewBgValues(v: IntArray) { newBgValues = v }
    fun setProductMonth(v: Int) { productMonth = v }
    fun setRight(v: Float) { right = v }
    fun setSensorInfo(v: String) { sensorInfo = v }
    fun setSickDuration(v: Float) { sickDuration = v }
    fun setWeight(v: Int) { weight = v }
    fun setWidth(v: Float) { width = v }
    fun setStartDay(v: Int) { startDay = v }
    fun setStartHour(v: Int) { startHour = v }
    fun setStartMinute(v: Int) { startMinute = v }
    fun setStartMonth(v: Int) { startMonth = v }
    fun setStartYear(v: Int) { startYear = v }
    fun setUserType(v: Int) { userType = v }
    fun setAlgorithmUpgradeGlucoseId(v: Int) { algorithmUpgradeGlucoseId = v }
    fun setAlgorithmUpgradeGLUMG(v: IntArray?) {
        algorithmUpgradeGLUMG = v ?: IntArray(0)
    }

    fun getIbs(): FloatArray = Ibs
    fun getIws(): FloatArray = Iws
    fun getTs(): FloatArray = Ts
    fun getK0(): Float = k0
    fun getR(): Float = r
    fun getAge(): Int = age
    fun getAlgorithm(): Int = algorithm
    fun getBatch(): String = batch
    fun getCheckErrorContinuousAbnormalCurrentToGlucoseIds(): IntArray =
        checkErrorContinuousAbnormalCurrentToGlucoseIds
    fun getEndCount(): Int = endCount
    fun getEnzyme_activity(): Float = enzyme_activity
    fun getGender(): Int = gender
    fun getGlucoseId(): Int = glucoseId
    fun getHeight(): Int = height
    fun getInitCount(): Int = initCount
    fun getLeft(): Float = left
    fun getLen_iw(): Float = len_iw
    fun getLifeTime(): Int = lifeTime
    fun getMembrane_layers(): Float = membrane_layers
    fun getName(): String = name
    fun getNewBgToGlucoseIds(): IntArray = newBgToGlucoseIds
    fun getNewBgValues(): IntArray = newBgValues
    fun getProductMonth(): Int = productMonth
    fun getRight(): Float = right
    fun getSensorInfo(): String = sensorInfo
    fun getSickDuration(): Float = sickDuration
    fun getWeight(): Int = weight
    fun getWidth(): Float = width
    fun getStartDay(): Int = startDay
    fun getStartHour(): Int = startHour
    fun getStartMinute(): Int = startMinute
    fun getStartMonth(): Int = startMonth
    fun getStartYear(): Int = startYear
    fun getUserType(): Int = userType
    fun getAlgorithmUpgradeGlucoseId(): Int = algorithmUpgradeGlucoseId
    fun getAlgorithmUpgradeGLUMG(): IntArray = algorithmUpgradeGLUMG
}
