package ist.com.sdk

/**
 * Single-reading input to `AlgorithmTools.algorithmLatestGlucose`.
 * Mirrors the vendor SDK's POJO. Field names and `setX` setters are required
 * because the JNI calls them via reflection.
 */
@Suppress("PropertyName")
class LatestData {
    @JvmField var ib: Float = 0f
    @JvmField var iw: Float = 0f
    @JvmField var t: Float = 0f
    @JvmField var k0: Float = 0f
    @JvmField var r: Float = 0f
    @JvmField var glucoseId: Int = 0
    @JvmField var year: Int = 0
    @JvmField var month: Int = 0
    @JvmField var day: Int = 0
    @JvmField var hour: Int = 0
    @JvmField var minute: Int = 0
    @JvmField var name: String = ""
    @JvmField var sensorInfo: String = ""
    @JvmField var algorithm: Int = 0
    @JvmField var lifeTime: Int = 0
    @JvmField var productMonth: Int = 0
    @JvmField var batch: String = ""
    @JvmField var enzyme_activity: Float = 0f
    @JvmField var membrane_layers: Float = 0f
    @JvmField var len_iw: Float = 0f
    @JvmField var left: Float = 0f
    @JvmField var right: Float = 0f
    @JvmField var width: Float = 0f
    @JvmField var userType: Int = 0
    @JvmField var age: Int = 0
    @JvmField var gender: Int = 0
    @JvmField var height: Int = 0
    @JvmField var weight: Int = 0
    @JvmField var sickDuration: Float = 0f
    @JvmField var endCount: Int = 0
    @JvmField var initCount: Int = 0
    @JvmField var newBgToGlucoseId: Int = 0
    @JvmField var newBgValue: Int = 0
    @JvmField var checkErrorContinuousAbnormalCurrent: Boolean = false
    @JvmField var algorithmUpgradeGlucoseId: Int = 0
    @JvmField var algorithmUpgradeGLUMG: IntArray = IntArray(0)

    // Setters matching the vendor SDK signatures.
    fun setIb(v: Float) { ib = v }
    fun setIw(v: Float) { iw = v }
    fun setT(v: Float) { t = v }
    fun setK0(v: Float) { k0 = v }
    fun setR(v: Float) { r = v }
    fun setGlucoseId(v: Int) { glucoseId = v }
    fun setYear(v: Int) { year = v }
    fun setMonth(v: Int) { month = v }
    fun setDay(v: Int) { day = v }
    fun setHour(v: Int) { hour = v }
    fun setMinute(v: Int) { minute = v }
    fun setName(v: String) { name = v }
    fun setSensorInfo(v: String) { sensorInfo = v }
    fun setAlgorithm(v: Int) { algorithm = v }
    fun setLifeTime(v: Int) { lifeTime = v }
    fun setProductMonth(v: Int) { productMonth = v }
    fun setBatch(v: String) { batch = v }
    fun setEnzymeActivity(v: Float) { enzyme_activity = v }
    fun setEnzyme_activity(v: Float) { enzyme_activity = v }
    fun setMembraneLayers(v: Float) { membrane_layers = v }
    fun setMembrane_layers(v: Float) { membrane_layers = v }
    fun setLenIw(v: Float) { len_iw = v }
    fun setLen_iw(v: Float) { len_iw = v }
    fun setLeft(v: Float) { left = v }
    fun setRight(v: Float) { right = v }
    fun setWidth(v: Float) { width = v }
    fun setUserType(v: Int) { userType = v }
    fun setAge(v: Int) { age = v }
    fun setGender(v: Int) { gender = v }
    fun setHeight(v: Int) { height = v }
    fun setWeight(v: Int) { weight = v }
    fun setSickDuration(v: Float) { sickDuration = v }
    fun setEndCount(v: Int) { endCount = v }
    fun setInitCount(v: Int) { initCount = v }
    fun setNewBgToGlucoseId(v: Int) { newBgToGlucoseId = v }
    fun setNewBgValue(v: Int) { newBgValue = v }
    fun setCheckErrorContinuousAbnormalCurrent(v: Boolean) {
        checkErrorContinuousAbnormalCurrent = v
    }
    fun setAlgorithmUpgradeGlucoseId(v: Int) { algorithmUpgradeGlucoseId = v }
    fun setAlgorithmUpgradeGLUMG(v: IntArray?) {
        algorithmUpgradeGLUMG = v ?: IntArray(0)
    }

    // Getters
    fun getIb(): Float = ib
    fun getIw(): Float = iw
    fun getT(): Float = t
    fun getK0(): Float = k0
    fun getR(): Float = r
    fun getGlucoseId(): Int = glucoseId
    fun getYear(): Int = year
    fun getMonth(): Int = month
    fun getDay(): Int = day
    fun getHour(): Int = hour
    fun getMinute(): Int = minute
    fun getName(): String = name
    fun getSensorInfo(): String = sensorInfo
    fun getAlgorithm(): Int = algorithm
    fun getLifeTime(): Int = lifeTime
    fun getProductMonth(): Int = productMonth
    fun getBatch(): String = batch
    fun getEnzyme_activity(): Float = enzyme_activity
    fun getMembrane_layers(): Float = membrane_layers
    fun getLen_iw(): Float = len_iw
    fun getLeft(): Float = left
    fun getRight(): Float = right
    fun getWidth(): Float = width
    fun getUserType(): Int = userType
    fun getAge(): Int = age
    fun getGender(): Int = gender
    fun getHeight(): Int = height
    fun getWeight(): Int = weight
    fun getSickDuration(): Float = sickDuration
    fun getEndCount(): Int = endCount
    fun getInitCount(): Int = initCount
    fun getNewBgToGlucoseId(): Int = newBgToGlucoseId
    fun getNewBgValue(): Int = newBgValue
    fun isCheckErrorContinuousAbnormalCurrent(): Boolean = checkErrorContinuousAbnormalCurrent
    fun getAlgorithmUpgradeGlucoseId(): Int = algorithmUpgradeGlucoseId
    fun getAlgorithmUpgradeGLUMG(): IntArray = algorithmUpgradeGLUMG
}
