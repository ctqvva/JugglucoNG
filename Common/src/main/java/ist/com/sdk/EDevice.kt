package ist.com.sdk

import tk.glucodata.drivers.anytime.AnytimeConstants

enum class EDevice(
    private val nameStart: String,
    private val initNumber: Int,
    private val endNumber: Int,
    private val eGattMessage: EGattMessage,
    private val algorithm: Int,
) {
    DEVICE_SN06("SN06", 60, 3420, EGattMessage.CT2, 6),
    DEVICE_SN08("SN08", 20, 6720, EGattMessage.CT2, 1),
    DEVICE_SN12("SN12", 60, 4800, EGattMessage.CT2, 1),
    DEVICE_SN16("SN16", 20, 6740, EGattMessage.CT3, 3),
    DEVICE_SN18("SN18", 20, 3380, EGattMessage.CT2, 7),
    DEVICE_SN20("SN20", 20, 6740, EGattMessage.CT2, 7),
    DEVICE_SN22("SN22", 20, 4800, EGattMessage.CT2, 1),
    DEVICE_SN26("SN26", 20, 6740, EGattMessage.CT3_YUWELL, 3),
    DEVICE_SN28("SN28", 20, 3380, EGattMessage.CT3_YUWELL, 3),
    DEVICE_SN30("SN30", 20, 6740, EGattMessage.CT2_5, 7),
    DEVICE_SN32("SN32", 20, 3380, EGattMessage.CT2_5, 7),
    DEVICE_SN36("SN36", 20, 6740, EGattMessage.CT2_5, 7),
    DEVICE_SN38("SN38", 20, 6740, EGattMessage.CT2_5, 7),
    DEVICE_SN40("SN40", 20, 6740, EGattMessage.CT2_5, 7),
    DEVICE_SN42("SN42", 20, 6740, EGattMessage.CT3_YUWELL, 3),
    DEVICE_SN46("SN46", 20, 6740, EGattMessage.CT3_YUWELL, 3),
    DEVICE_SN48("SN48", 20, 6740, EGattMessage.CT2, 7),
    DEVICE_SN50("SN50", 20, 6740, EGattMessage.CT2, 7),
    DEVICE_SN52("SN52", 20, 6740, EGattMessage.CT2, 7),
    DEVICE_SN56("SN56", 20, 6740, EGattMessage.CT3_PLUS, 3),
    DEVICE_SN58("SN58", 20, 3380, EGattMessage.CT3_PLUS, 3),
    DEVICE_SN60("SN60", 20, 6740, EGattMessage.CT3_PLUS, 3),
    DEVICE_SN62("SN62", 20, 6740, EGattMessage.CT3_PLUS, 3),
    DEVICE_SN66("SN66", 20, 6740, EGattMessage.CT3_PLUS, 3),
    DEVICE_SN68("SN68", 20, 6740, EGattMessage.CT3_PLUS, 3),
    DEVICE_SN70("SN70", 20, 6740, EGattMessage.CT3_PLUS, 10),
    DEVICE_SN17("SN17", 20, 6740, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN27("SN27", 20, 6740, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN29("SN29", 20, 3380, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN43("SN43", 20, 6740, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN47("SN47", 20, 6740, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN91("SN91", 20, 6740, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN96("SN96", 20, 6740, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN98("SN98", 20, 6740, EGattMessage.CT3_ULTRASONIC, 10),
    DEVICE_SN72("SN72", 20, 7220, EGattMessage.CT4, 10),
    DEVICE_SN76("SN76", 20, 7220, EGattMessage.CT4, 10),
    DEVICE_SN78("SN78", 20, 7220, EGattMessage.CT4, 10),
    DEVICE_SN80("SN80", 20, 7220, EGattMessage.CT4, 10),
    DEVICE_SN82("SN82", 20, 3860, EGattMessage.CT4, 10),
    DEVICE_SN86("SN86", 20, 3860, EGattMessage.CT4, 10),
    DEVICE_SN87("SN87", 20, 7220, EGattMessage.CT4, 10),
    DEVICE_SN88("SN88", 20, 7220, EGattMessage.CT4, 10),
    DEVICE_SN90("SN90", 20, 4820, EGattMessage.CT4, 10),
    DEVICE_ANYTIME("Anytime", 15, 7695, EGattMessage.CT5, 11),
    DEVICE_UNKNOWN("SN06", 60, 3420, EGattMessage.CT2, 6);

    fun getNameStart(): String = nameStart
    fun getInitNumber(): Int = initNumber
    fun getInitNumber(lifeTime: Int): Int = initNumber
    fun getEndNumber(): Int = endNumber
    fun getEndNumber(lifeTime: Int): Int {
        if (lifeTime > 0 && eGattMessage == EGattMessage.CT3_YUWELL || lifeTime > 0 && eGattMessage == EGattMessage.CT3_ULTRASONIC) {
            if (endNumber == 3380 && lifeTime == 5) return 3860
            if (endNumber == 6740 && lifeTime == 6) return 7220
        }
        if (lifeTime > 0 && eGattMessage == EGattMessage.CT5) {
            return when (lifeTime) {
                2 -> 4820
                3 -> 6740
                4 -> 7695
                5 -> 3855
                else -> endNumber
            }
        }
        return endNumber
    }
    fun getAlgorithm(): Int = algorithm
    fun geteGattMessage(): EGattMessage = eGattMessage
    fun getDeviceType(): String = if (eGattMessage == EGattMessage.CT4) "CT4" else eGattMessage.name

    companion object {
        @JvmStatic
        fun getEnumDevice(name: String?): EDevice {
            val trimmed = name?.trim().orEmpty()
            values().firstOrNull { trimmed.startsWith(it.nameStart, ignoreCase = true) }
                ?.let { return it }

            val family = AnytimeConstants.resolveFamily(trimmed)
            values().firstOrNull { family.prefix.equals(it.nameStart, ignoreCase = true) }
                ?.let { return it }
            return DEVICE_UNKNOWN
        }

        @JvmStatic
        fun getEnumDevice(name: String?, fallback: EDevice): EDevice =
            if (name.isNullOrBlank()) fallback else getEnumDevice(name)
    }
}
