package tk.glucodata

object NotificationMultiSensorSource {
    /**
     * One selected peer (non-primary) sensor with its current snapshot.
     * [snapshot] is null when the peer has no fresh reading; such peers are
     * skipped for value items but still chart their history.
     */
    class PeerCurrent(
        @JvmField val sensorId: String,
        @JvmField val snapshot: CurrentDisplaySource.Snapshot?,
        @JvmField val colorArgb: Int
    )

    /** Every sensor that could be displayed: the primary, native's, and the connected ones. */
    @JvmStatic
    fun candidateSensorIds(primarySensorId: String?): List<String?> {
        val candidates = ArrayList<String?>()
        candidates.add(primarySensorId)
        runCatching { Natives.activeSensors()?.forEach { candidates.add(it) } }
        runCatching {
            SensorBluetooth.mygatts()?.forEach { callback ->
                candidates.add(callback.SerialNumber)
            }
        }
        return candidates
    }

    @JvmStatic
    fun selectedSensorIds(primarySensorId: String?): List<String> =
        MultiSensorSelection.selectedAvailable(candidateSensorIds(primarySensorId), primarySensorId)

    /**
     * Resolves every selected peer once per notification/AOD update. The result
     * feeds both [valueItems] and [peerSeries] so each update performs a single
     * snapshot resolution (and history query) per peer.
     */
    @JvmStatic
    fun peerCurrents(maxAgeMillis: Long, primarySensorId: String?): List<PeerCurrent> {
        val selected = selectedSensorIds(primarySensorId)
        if (selected.size <= 1) return emptyList()
        val selectedColors = SensorVisuals.distinctColorArgbMap(selected)
        return selected.drop(1).map { sensorId ->
            val snapshot = runCatching {
                CurrentDisplaySource.resolveCurrent(
                    maxAgeMillis,
                    sensorId,
                    DisplayTrendSource.TREND_WINDOW_MS
                )
            }.getOrNull()
            val color = selectedColors.entries.firstOrNull { (selectedId, _) ->
                SensorIdentity.matches(selectedId, sensorId)
            }?.value ?: SensorVisuals.colorArgb(sensorId)
            PeerCurrent(sensorId, snapshot, color)
        }
    }

    @JvmStatic
    fun valueItems(peerCurrents: List<PeerCurrent>): List<NotificationChartDrawer.ValueItem> =
        peerCurrents.mapNotNull { peer ->
            val snapshot = peer.snapshot ?: return@mapNotNull null
            NotificationChartDrawer.ValueItem(
                snapshot.fullFormatted,
                peer.colorArgb,
                snapshot.rate
            )
        }

    @JvmStatic
    fun peerSeries(
        peerCurrents: List<PeerCurrent>,
        startTimeMs: Long,
        isMmol: Boolean
    ): List<NotificationChartDrawer.PeerSeries> {
        return peerCurrents.mapNotNull { peer ->
            val history = runCatching {
                NotificationHistorySource.getDisplayHistory(startTimeMs, isMmol, peer.sensorId)
            }.getOrDefault(emptyList())
                .let { DisplayTrendSource.augmentHistory(it, peer.snapshot, peer.sensorId, startTimeMs) }
            if (history.size < 2) {
                null
            } else {
                NotificationChartDrawer.PeerSeries(
                    peer.sensorId,
                    peer.snapshot?.viewMode ?: resolveViewMode(peer.sensorId),
                    peer.colorArgb,
                    history
                )
            }
        }
    }

    @JvmStatic
    fun resolveViewMode(sensorId: String?): Int =
        CurrentDisplaySource.resolveViewModeForSensor(sensorId)
}
