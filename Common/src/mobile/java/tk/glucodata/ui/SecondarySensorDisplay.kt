package tk.glucodata.ui

import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.MultiSensorSelection
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity

/** Latest displayable reading of a selected peer sensor, in the current display unit. */
data class PeerCurrentReading(
    val sensorId: String,
    val primaryStr: String,
    val secondaryStr: String?,
    val rate: Float,
    val timeMillis: Long
)

object SecondarySensorDisplay {
    /** Raw/calibration lanes take priority; otherwise use the first fresh selected peer. */
    fun fallback(
        primary: DisplayValues?,
        primarySensorId: String?,
        peers: List<PeerCurrentReading>,
        nowMillis: Long = System.currentTimeMillis(),
        freshnessWindowMillis: Long = Notify.glucosetimeout
    ): PeerCurrentReading? {
        if (primary == null || !primary.secondaryStr.isNullOrBlank() ||
            !primary.tertiaryStr.isNullOrBlank() || primarySensorId.isNullOrBlank()) {
            return null
        }
        return peers.firstOrNull { peer ->
            !SensorIdentity.matches(peer.sensorId, primarySensorId) &&
                peer.primaryStr.isNotBlank() && peer.primaryStr != "--" &&
                DisplayDataState.resolve(
                    sensorPresent = true,
                    currentTimestampMillis = peer.timeMillis,
                    latestHistoryTimestampMillis = 0L,
                    freshnessWindowMillis = freshnessWindowMillis,
                    nowMillis = nowMillis
                ).isFresh
        }
    }

    fun resolvePeers(sensorIds: List<String>): List<PeerCurrentReading> = sensorIds.mapNotNull { sensorId ->
        runCatching { CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout, sensorId) }
            .getOrNull()?.let { snapshot ->
                PeerCurrentReading(sensorId, snapshot.primaryStr, snapshot.secondaryStr, snapshot.rate, snapshot.timeMillis)
            }
    }

    /** Mirrors the dashboard's available native and managed sensors, preserving selection order. */
    fun selectedPeers(primarySensorId: String?): List<String> {
        if (primarySensorId.isNullOrBlank()) return emptyList()
        val available = buildList<String?> {
            runCatching { Natives.activeSensors() }.getOrNull()?.let { addAll(it) }
            runCatching { SensorBluetooth.mygatts() }.getOrNull()?.forEach { add(it.SerialNumber) }
            if (isEmpty()) add(primarySensorId)
        }
        return MultiSensorSelection.selectedAvailable(available, primarySensorId)
            .filterNot { SensorIdentity.matches(it, primarySensorId) }
    }
}
