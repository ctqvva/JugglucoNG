package tk.glucodata.ui

/** Covers every record: an interior minute can seal while both history edges stay unchanged. */
internal fun recordedDisplaySignature(points: List<GlucosePoint>): Int {
    var hash = 1
    for (point in points) {
        if (point.sealedAutoLaneValue == null && point.sealedRawLaneValue == null) continue
        hash = 31 * hash + point.timestamp.hashCode()
        hash = 31 * hash + (point.sealedAutoLaneValue?.toRawBits() ?: 0)
        hash = 31 * hash + (point.sealedRawLaneValue?.toRawBits() ?: 0)
        hash = 31 * hash + (point.sensorSerial?.hashCode() ?: 0)
    }
    return hash
}
