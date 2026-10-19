package tk.glucodata.share

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Share has not delivered yet, plus a memory of what it has.
 *
 * Share is a five-minute stream, so most of the readings the app collects are noise to it: the
 * live path may call in every minute, and a reconnect hands over a whole gap at once. The queue
 * is what turns both into one reading per [minSpacingMs], and what stops a reading that was
 * already sent from being sent again when the same moment arrives from history.
 *
 * The delivered memory is deliberately kept for a whole [windowMs]: after a disconnect the
 * history writer re-offers everything it stored, and only the timestamps can tell which of those
 * already went out live.
 *
 * History newer than the last live reading is held back until a live reading passes it. The
 * history row of a reading is stored just before that reading goes out live; queuing it first
 * would make the live reading look already covered, and Share would get the history version: one
 * row with no neighbour to derive a trend from, and unsmoothed. Holding it lets the live reading,
 * which carries the trend, win. A reconnect gap is still filled: the reconnect's live reading is
 * newer than every row of the gap and releases them all. Held rows that no live reading passes
 * (the sensor stopped) are released after [maxHoldMs].
 */
class DexcomShareQueue(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minSpacingMs: Long = DEFAULT_MIN_SPACING_MS,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private var enabledAtMs: Long = 0L,
    private val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS
) {

    companion object {
        const val DEFAULT_WINDOW_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_MIN_SPACING_MS = 270_000L
        const val DEFAULT_MAX_PENDING = 288
        const val DEFAULT_MAX_HOLD_MS = 10L * 60 * 1000

        /** A reading may arrive slightly before its own timestamp; that is not the future. */
        private const val FUTURE_SKEW_MS = 60_000L

        private const val KEY_WINDOW_MS = "window_ms"
        private const val KEY_MIN_SPACING_MS = "min_spacing_ms"
        private const val KEY_MAX_PENDING = "max_pending"
        private const val KEY_ENABLED_AT_MS = "enabled_at_ms"
        private const val KEY_LIVE_SENSOR = "live_sensor"
        private const val KEY_LAST_LIVE_MS = "last_live_ms"
        private const val KEY_PENDING = "pending"
        private const val KEY_HELD = "held"
        private const val KEY_DELIVERED = "delivered"
        private const val KEY_TIME_MS = "t"
        private const val KEY_MGDL = "v"
        private const val KEY_TREND = "x"

        /** Never throws: a damaged or absent payload means "nothing waiting". */
        fun decode(s: String?): DexcomShareQueue {
            val root = runCatching { JSONObject(s.orEmpty()) }.getOrNull() ?: return DexcomShareQueue()
            val queue = DexcomShareQueue(
                windowMs = root.optLong(KEY_WINDOW_MS, DEFAULT_WINDOW_MS),
                minSpacingMs = root.optLong(KEY_MIN_SPACING_MS, DEFAULT_MIN_SPACING_MS),
                maxPending = root.optInt(KEY_MAX_PENDING, DEFAULT_MAX_PENDING),
                enabledAtMs = root.optLong(KEY_ENABLED_AT_MS, 0L)
            )
            queue.liveSensor = if (root.isNull(KEY_LIVE_SENSOR)) {
                null
            } else {
                root.optString(KEY_LIVE_SENSOR).takeIf { it.isNotEmpty() }
            }
            queue.lastLiveMs = root.optLong(KEY_LAST_LIVE_MS, 0L)
            readEgvs(root.optJSONArray(KEY_PENDING), queue.pending)
            readEgvs(root.optJSONArray(KEY_HELD), queue.held)
            val delivered = root.optJSONArray(KEY_DELIVERED)
            if (delivered != null) {
                for (index in 0 until delivered.length()) {
                    queue.delivered.add(delivered.optLong(index, 0L))
                }
            }
            queue.sortPending()
            queue.held.sortBy(ShareEgv::timeMs)
            return queue
        }

        private fun readEgvs(array: JSONArray?, into: MutableList<ShareEgv>) {
            if (array == null) return
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                into.add(
                    ShareEgv(
                        timeMs = item.optLong(KEY_TIME_MS, 0L),
                        mgdl = item.optInt(KEY_MGDL, 0),
                        trend = item.optInt(KEY_TREND, 0)
                    )
                )
            }
        }

        private fun writeEgvs(egvs: List<ShareEgv>): JSONArray {
            val array = JSONArray()
            egvs.forEach { egv ->
                array.put(
                    JSONObject()
                        .put(KEY_TIME_MS, egv.timeMs)
                        .put(KEY_MGDL, egv.mgdl)
                        .put(KEY_TREND, egv.trend)
                )
            }
            return array
        }
    }

    /** Oldest first, so the caller can send them in the order Dexcom should store them. */
    private val pending = ArrayList<ShareEgv>()
    /** History newer than [lastLiveMs], oldest first, waiting for a live reading to pass it. */
    private val held = ArrayList<ShareEgv>()
    private val delivered = HashSet<Long>()
    private var liveSensor: String? = null
    private var lastLiveMs = 0L

    /**
     * A live reading, which also makes [sensorId] the sensor Share follows: only that one is
     * backfilled, so a second sensor's history cannot interleave into the stream. It then
     * releases the held history it has passed.
     *
     * @return true when anything new is waiting to be sent.
     */
    @Synchronized
    fun offerLive(sensorId: String, egv: ShareEgv, nowMs: Long): Boolean {
        if (sensorId.isEmpty()) return false
        if (sensorId != liveSensor) {
            // The held rows belonged to the sensor Share followed until now.
            held.clear()
            lastLiveMs = 0L
        }
        liveSensor = sensorId
        val accepted = offer(egv, nowMs)
        if (egv.timeMs > lastLiveMs) lastLiveMs = egv.timeMs
        val released = release(nowMs)
        return accepted || released > 0
    }

    /**
     * Stored history for [sensorId]. Readings at or before the moment Share was turned on are
     * refused, so enabling the feature does not dump the readings already on the phone, and a
     * sensor other than the live one is refused entirely. Readings newer than the last live one
     * are held, not queued (see the class comment).
     *
     * @return how many readings became ready to send.
     */
    @Synchronized
    fun offerBackfill(sensorId: String, egvs: List<ShareEgv>, nowMs: Long): Int {
        if (sensorId.isEmpty() || sensorId != liveSensor) return 0
        var accepted = 0
        egvs.sortedBy(ShareEgv::timeMs).forEach { egv ->
            if (egv.timeMs <= enabledAtMs) return@forEach
            if (egv.timeMs > lastLiveMs) {
                hold(egv, nowMs)
            } else if (offer(egv, nowMs)) {
                accepted++
            }
        }
        return accepted + release(nowMs)
    }

    /** Oldest first, without anything that has left [windowMs]. */
    @Synchronized
    fun pending(nowMs: Long): List<ShareEgv> {
        release(nowMs)
        trim(nowMs)
        return pending.toList()
    }

    @Synchronized
    fun markDelivered(egvs: List<ShareEgv>) {
        val times = egvs.map(ShareEgv::timeMs).toSet()
        pending.removeAll { it.timeMs in times }
        delivered.addAll(times)
    }

    /**
     * A batch Dexcom rejected. It is remembered as delivered as well as removed: the same
     * moments would otherwise come back with the next history batch and be rejected again.
     */
    @Synchronized
    fun drop(egvs: List<ShareEgv>) = markDelivered(egvs)

    @Synchronized
    fun liveSensorId(): String? = liveSensor

    /** How many history readings are waiting for a live reading to pass them. */
    @Synchronized
    fun heldCount(): Int = held.size

    @Synchronized
    fun encode(): String {
        val deliveredJson = JSONArray()
        delivered.sorted().forEach { deliveredJson.put(it) }
        return JSONObject()
            .put(KEY_WINDOW_MS, windowMs)
            .put(KEY_MIN_SPACING_MS, minSpacingMs)
            .put(KEY_MAX_PENDING, maxPending)
            .put(KEY_ENABLED_AT_MS, enabledAtMs)
            .put(KEY_LIVE_SENSOR, liveSensor ?: JSONObject.NULL)
            .put(KEY_LAST_LIVE_MS, lastLiveMs)
            .put(KEY_PENDING, writeEgvs(pending))
            .put(KEY_HELD, writeEgvs(held))
            .put(KEY_DELIVERED, deliveredJson)
            .toString()
    }

    private fun hold(egv: ShareEgv, nowMs: Long) {
        if (egv.mgdl <= 0 || egv.timeMs <= 0L) return
        if (egv.timeMs > nowMs + FUTURE_SKEW_MS || egv.timeMs < nowMs - windowMs) return
        if (held.any { it.timeMs == egv.timeMs }) return
        held.add(egv)
        held.sortBy(ShareEgv::timeMs)
        while (held.size > maxPending) held.removeAt(0)
    }

    /**
     * Moves the held rows a live reading has passed, or that waited longer than [maxHoldMs], into
     * the queue under the usual spacing rule. The row of the live reading's own moment is covered
     * by it and dropped here.
     */
    private fun release(nowMs: Long): Int {
        if (held.isEmpty()) return 0
        val ready = held.filter { it.timeMs < lastLiveMs || it.timeMs < nowMs - maxHoldMs }
        if (ready.isEmpty()) return 0
        held.removeAll(ready.toSet())
        return ready.count { offer(it, nowMs) }
    }

    private fun offer(egv: ShareEgv, nowMs: Long): Boolean {
        if (egv.mgdl <= 0 || egv.timeMs <= 0L) return false
        if (egv.timeMs > nowMs + FUTURE_SKEW_MS) return false
        if (egv.timeMs < nowMs - windowMs) return false
        if (isCovered(egv.timeMs)) return false
        pending.add(egv)
        sortPending()
        trim(nowMs)
        return true
    }

    private fun isCovered(timeMs: Long): Boolean =
        pending.any { kotlin.math.abs(it.timeMs - timeMs) < minSpacingMs } ||
            delivered.any { kotlin.math.abs(it - timeMs) < minSpacingMs }

    private fun trim(nowMs: Long) {
        val oldest = nowMs - windowMs
        pending.removeAll { it.timeMs < oldest }
        held.removeAll { it.timeMs < oldest }
        delivered.removeAll { it < oldest }
        while (pending.size > maxPending) {
            pending.removeAt(0)
        }
    }

    private fun sortPending() {
        pending.sortBy(ShareEgv::timeMs)
    }
}
