package tk.glucodata

/**
 * Decides which exchange destinations receive a resolved reading.
 *
 * The collapse interval is a per-destination throttle. A destination that is disabled, or that
 * is not eligible on this callback because it is behind the minute gate, must not advance any
 * other destination's interval. This matters when a fast destination resolves a payload before a
 * minute-gated destination gets its next callback.
 *
 * LOOP_FEED (xDrip broadcast, xInfuus) used to be exempt from collapse entirely, because the old
 * design resolved its payload from the last point of a completed chunk — up to two intervals old.
 * ExchangeUpdateGate fixed that by always carrying the newest reading under its own timestamp, so
 * the exemption is no longer needed: LOOP_FEED collapses the same as every other destination.
 */
class ExchangeOutputPolicy {
    data class Decision(
        val sendJuggluco: Boolean,
        val sendOutboundApi: Boolean,
        val sendWearInt: Boolean,
        val sendGadgetbridge: Boolean,
        val sendLoopFeed: Boolean,
        val sendXdripFollower: Boolean
    )

    private enum class Destination {
        JUGGLUCO,
        OUTBOUND_API,
        WEAR_INT,
        GADGETBRIDGE,
        LOOP_FEED,
        XDRIP_FOLLOWER
    }

    private val gates = Destination.values().associateWith { ExchangeUpdateGate() }

    fun decide(
        sensorId: String?,
        payloadTimeMs: Long,
        intervalMinutes: Int,
        shouldBroadcastMinuteUpdate: Boolean,
        jugglucoEnabled: Boolean,
        outboundApiEnabled: Boolean,
        wearIntEnabled: Boolean,
        gadgetbridgeEnabled: Boolean,
        loopFeedEnabled: Boolean,
        xdripFollowerEnabled: Boolean = false
    ): Decision = Decision(
        sendJuggluco = shouldEmit(
            Destination.JUGGLUCO, jugglucoEnabled, true, sensorId, payloadTimeMs, intervalMinutes
        ),
        sendOutboundApi = shouldEmit(
            Destination.OUTBOUND_API, outboundApiEnabled, true, sensorId, payloadTimeMs, intervalMinutes
        ),
        sendWearInt = shouldEmit(
            Destination.WEAR_INT, wearIntEnabled, shouldBroadcastMinuteUpdate,
            sensorId, payloadTimeMs, intervalMinutes
        ),
        sendGadgetbridge = shouldEmit(
            Destination.GADGETBRIDGE, gadgetbridgeEnabled, shouldBroadcastMinuteUpdate,
            sensorId, payloadTimeMs, intervalMinutes
        ),
        sendLoopFeed = shouldEmit(
            Destination.LOOP_FEED, loopFeedEnabled, shouldBroadcastMinuteUpdate,
            sensorId, payloadTimeMs, intervalMinutes
        ),
        sendXdripFollower = shouldEmit(
            Destination.XDRIP_FOLLOWER, xdripFollowerEnabled, shouldBroadcastMinuteUpdate,
            sensorId, payloadTimeMs, intervalMinutes
        )
    )

    private fun shouldEmit(
        destination: Destination,
        enabled: Boolean,
        eligible: Boolean,
        sensorId: String?,
        payloadTimeMs: Long,
        intervalMinutes: Int
    ): Boolean {
        if (!enabled || !eligible || payloadTimeMs <= 0L) {
            return false
        }
        return gates.getValue(destination).shouldEmit(sensorId, payloadTimeMs, intervalMinutes)
    }
}
