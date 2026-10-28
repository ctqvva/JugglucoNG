package tk.glucodata.drivers.aidex

import tk.glucodata.drivers.aidex.adaptive2.AiDexAdaptiveV2
import tk.glucodata.drivers.aidex.adaptive2.AiDexReference

internal object AiDexAlgorithmReferences {
    /** Auto-eligible anchors, packed as sensor value, reference value, timestamp in display units. */
    fun decode(auto: DoubleArray, isMmol: Boolean): List<AiDexReference> {
        val byTime = sortedMapOf<Long, AiDexReference>()
        if (auto.size % 3 != 0) return emptyList()
        for (offset in auto.indices step 3) {
            val value = auto[offset + 1].toFloat() * if (isMmol) AiDexAdaptiveV2.MGDL_PER_MMOL else 1f
            val timestamp = auto[offset + 2]
            if (!timestamp.isFinite() || timestamp <= 0 || timestamp >= 1e15) continue
            if (value.isFinite() && value in AiDexAdaptiveV2.MGDL_PER_MMOL..(35f * AiDexAdaptiveV2.MGDL_PER_MMOL))
                byTime[timestamp.toLong()] = AiDexReference(timestamp.toLong(), value)
        }
        return byTime.values.toList()
    }
}
