package ist.com.sdk

/**
 * QR-decoder output. The JNI populates these fields via `setX` reflection
 * (matches the vendor SDK's POJO; field names must remain stable).
 */
@Suppress("PropertyName", "PrivatePropertyName")
class KRDecodeData {
    @JvmField var calibration: Int = 0
    @JvmField var electrodeTecNo: String? = null
    @JvmField var electrodeType: String? = null
    @JvmField var enzymeTecNo: String? = null
    @JvmField var k: Float = 0f
    @JvmField var lifeTime: Int = 0
    @JvmField var marketNo: String? = null
    @JvmField var membraneTecNo: String? = null
    @JvmField var r: Float = 0f
    @JvmField var sensorNo: String? = null
    @JvmField var serialNo: String? = null
    @JvmField var unitOrder: Int = 0
    @JvmField var year: Int = 0
    @JvmField var productMonth: Int = 0

    fun setCalibration(v: Int) { calibration = v }
    fun setElectrodeTecNo(v: String?) { electrodeTecNo = v }
    fun setElectrodeType(v: String?) { electrodeType = v }
    fun setEnzymeTecNo(v: String?) { enzymeTecNo = v }
    fun setK(v: Float) { k = v }
    fun setLifeTime(v: Int) { lifeTime = v }
    fun setMarketNo(v: String?) { marketNo = v }
    fun setMembraneTecNo(v: String?) { membraneTecNo = v }
    fun setR(v: Float) { r = v }
    fun setSensorNo(v: String?) { sensorNo = v }
    fun setSerialNo(v: String?) { serialNo = v }
    fun setUnitOrder(v: Int) { unitOrder = v }
    fun setYear(v: Int) { year = v }
    fun setProductMonth(v: Int) { productMonth = v }

    fun getCalibration(): Int = calibration
    fun getElectrodeTecNo(): String? = electrodeTecNo
    fun getElectrodeType(): String? = electrodeType
    fun getEnzymeTecNo(): String? = enzymeTecNo
    fun getK(): Float = k
    fun getLifeTime(): Int = lifeTime
    fun getMarketNo(): String? = marketNo
    fun getMembraneTecNo(): String? = membraneTecNo
    fun getR(): Float = r
    fun getSensorNo(): String? = sensorNo
    fun getSerialNo(): String? = serialNo
    fun getUnitOrder(): Int = unitOrder
    fun getYear(): Int = year
    fun getProductMonth(): Int = productMonth
}
