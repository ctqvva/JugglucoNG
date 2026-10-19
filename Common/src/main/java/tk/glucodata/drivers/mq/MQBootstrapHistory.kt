package tk.glucodata.drivers.mq

import tk.glucodata.drivers.VirtualGlucoseSensorBridge

object MQBootstrapHistory {
    fun import(sensorId: String, history: List<MQBootstrapHistoryPoint>): Int =
        VirtualGlucoseSensorBridge.importHistory(
            sensorSerial = sensorId,
            readings = history.map { VirtualGlucoseSensorBridge.Reading(it.timestampMs, it.glucoseMgdl) },
            logLabel = "MQ snapshot",
            nearDuplicateWindowMs = 90_000L,
        )
}
