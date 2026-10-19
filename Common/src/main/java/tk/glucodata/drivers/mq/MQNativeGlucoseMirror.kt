package tk.glucodata.drivers.mq

internal object MQNativeGlucoseMirror {
    fun write(
        sampleMs: Long,
        result: MQAlgorithm.Result,
        sensorId: String,
        store: (Long, Float, String) -> Boolean,
    ): Boolean {
        if (sampleMs < 1_000L || sensorId.isBlank() || !result.mgdl.isFinite() || result.mgdl <= 0f) {
            return false
        }
        // Native's poll value is plain mg/dL -- getmgdL() returns it as is and
        // valid() rejects anything at or above 552 -- and addGlucoseStream()
        // multiplies its float by ten on the way in. So the parameter is mg/dL
        // over ten, which is what every other managed driver feeds it. Passing
        // mg/dL here stored every reading tenfold: invalid to native, so a
        // Clone of this sensor had "no stream data" to show, and tenfold on any
        // surface that read the poll rather than Room.
        return store(sampleMs / 1_000L, result.mgdl / 10f, sensorId)
    }
}
