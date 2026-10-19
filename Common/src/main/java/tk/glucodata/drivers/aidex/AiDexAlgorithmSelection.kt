package tk.glucodata.drivers.aidex

/** AiDex uses the same model/calibration bits as the existing Auto selector. */
enum class AiDexAlgorithmSelection(val storageId: Int, val adaptive: Boolean, val calibration: Boolean) {
    STOCK(0, false, false),
    ADAPTIVE_V2(8, true, false),
    ADAPTIVE_V2_CALIBRATED(9, true, true);

    companion object {
        fun fromStorage(value: Int): AiDexAlgorithmSelection = entries.firstOrNull { it.storageId == value } ?: STOCK
    }
}
