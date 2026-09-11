package tk.glucodata.ui

/** Covers every record: an interior minute can seal while both history edges stay unchanged. */
internal fun recordedDisplaySignature(points: List<GlucosePoint>): Int {
    var hash = 1
    for (point in points) {
        val value = point.sealedDisplayValue ?: continue
        hash = 31 * hash + point.timestamp.hashCode()
        hash = 31 * hash + value.toRawBits()
        hash = 31 * hash + (point.sealedDisplayViewMode ?: -1)
        hash = 31 * hash + (point.sensorSerial?.hashCode() ?: 0)
    }
    return hash
}
