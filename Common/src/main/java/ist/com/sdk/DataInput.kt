package ist.com.sdk

import java.util.Calendar

/**
 * Official Yuwell 1.3.x algorithm input. The native library reads this object
 * through reflected getters, so method names must stay Java-compatible.
 */
@Suppress("PropertyName")
class DataInput(
    private var glucoseId: Int,
    private var Iws: FloatArray,
    private var Ibs: FloatArray,
    private var Ts: FloatArray,
    private var eventIds: IntArray?,
    private var BGMGs: IntArray?,
    private var K0: Float,
    private var R: Float,
    startTimeMillis: Long,
    transmitterName: String,
) {
    private var len_data: Int = Iws.size
    private var len_event: Int = eventIds?.size ?: 0
    private var day: Int = 0
    private var hour: Int = 0
    private var minute: Int = 0
    private var warmup_time: Int = 20
    private var life_time: Int = 7220
    private var algorithm: Int = 10
    private var reserved_keyword1: Int = 0
    private var reserved_keyword2: IntArray? = null

    init {
        require(Iws.isNotEmpty() && Ibs.isNotEmpty() && Ts.isNotEmpty()) {
            "iws, ibs and ts must not be empty"
        }
        require(Iws.size == Ibs.size && Iws.size == Ts.size) {
            "iws, ibs and ts lengths must match"
        }
        if (eventIds != null || BGMGs != null) {
            require(eventIds != null && BGMGs != null && eventIds!!.size == BGMGs!!.size) {
                "eventIds and BGMGs lengths must match"
            }
        }
        setStartTimeMillis(startTimeMillis)
        setTransmitterName(transmitterName, 1, 0)
    }

    fun setStartTimeMillis(value: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = value }
        day = cal.get(Calendar.DAY_OF_MONTH)
        hour = cal.get(Calendar.HOUR_OF_DAY)
        minute = cal.get(Calendar.MINUTE)
    }

    fun setTransmitterName(name: String, type: Int, lifeTimeDays: Int) {
        algorithm = when {
            name.startsWith("SN8", ignoreCase = true) ||
                name.startsWith("SN7", ignoreCase = true) ||
                name.startsWith("SN9", ignoreCase = true) -> if (type == 0) 3 else 10
            name.startsWith("SN", ignoreCase = true) -> 3
            else -> 10
        }
        warmup_time = 20
        life_time = when {
            lifeTimeDays in 1..40 -> lifeTimeDays * 24 * 20 + 20
            else -> 7220
        }
    }

    fun setAlgorithm(value: Int) { algorithm = value }
    fun setWarmup_time(value: Int) { warmup_time = value }
    fun setLife_time(value: Int) { life_time = value }
    fun setReserved_keyword1(value: Int) { reserved_keyword1 = value }
    fun setReserved_keyword2(value: IntArray?) {
        if (value == null || value.size == 5) reserved_keyword2 = value
    }

    fun getGlucoseId(): Int = glucoseId
    fun getIws(): FloatArray = Iws
    fun getIbs(): FloatArray = Ibs
    fun getTs(): FloatArray = Ts
    fun getLen_data(): Int = len_data
    fun getEventIds(): IntArray? = eventIds
    fun getBGMGs(): IntArray? = BGMGs
    fun getLen_event(): Int = len_event
    fun getK0(): Float = K0
    fun getR(): Float = R
    fun getDay(): Int = day
    fun getHour(): Int = hour
    fun getMinute(): Int = minute
    fun getWarmup_time(): Int = warmup_time
    fun getLife_time(): Int = life_time
    fun getAlgorithm(): Int = algorithm
    fun getReserved_keyword1(): Int = reserved_keyword1
    fun getReserved_keyword2(): IntArray? = reserved_keyword2
}
