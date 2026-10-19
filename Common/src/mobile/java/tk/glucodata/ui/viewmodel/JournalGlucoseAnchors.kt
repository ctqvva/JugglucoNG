package tk.glucodata.ui.viewmodel

import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.rowTrendHistory
import kotlin.math.abs

/**
 * The reading a journal entry sits on, and the readings behind it for its
 * arrow, in stored units.
 *
 * The ledger used to find these by searching the whole history for every
 * entry, which is why the journal needed the whole store in memory. An entry's
 * reading is a fact about a forty-minute stretch of the timeline around it,
 * so the stretches are read for the entries that need them and kept.
 */
data class JournalGlucoseAnchor(
    val point: GlucosePoint?,
    val trendHistory: List<GlucosePoint>,
)

/**
 * Plans and applies the reads behind [JournalGlucoseAnchor]s. Pure: the
 * caller runs the queries it plans and hands the rows back.
 */
object JournalGlucoseAnchors {
    /** An entry takes the nearest reading within this of its own time; the ledger's rule since it existed. */
    const val MAX_ANCHOR_DISTANCE_MS = 20L * 60L * 1000L
    /** How much history before the anchor is read for the row's arrow — plenty for the trend engine at any cadence. */
    const val TREND_LOOKBACK_MS = 3L * 60L * 60L * 1000L
    /**
     * How many readings behind the anchor are kept for the arrow. The engine
     * regresses over at most 25 minutes and 30 readings and measures cadence
     * from a dozen gaps, so this is generous at any cadence; kept as a copy
     * rather than a view so the stretch read for the cluster can be dropped.
     */
    const val TREND_POINTS_KEPT = 60
    /** Entries closer together than this share one read. */
    const val CLUSTER_GAP_MS = 60L * 60L * 1000L
    /**
     * Entries this close to the newest reading can still gain their reading, or
     * the readings behind it, from the next minute; everything older is settled.
     */
    const val LIVE_HORIZON_MS = MAX_ANCHOR_DISTANCE_MS + TREND_LOOKBACK_MS

    /** One read: the stretch to load, and the entry timestamps it resolves. */
    data class Cluster(val startMs: Long, val endMs: Long, val entryTimestamps: List<Long>)

    /**
     * Which entry timestamps still need resolving given what is [known], and
     * the newest stored reading [latestMs]: those never resolved, and those
     * near enough to the live edge that the answer may have changed.
     */
    fun pending(entryTimestamps: Collection<Long>, known: Map<Long, JournalGlucoseAnchor>, latestMs: Long?): List<Long> {
        val liveFrom = latestMs?.minus(LIVE_HORIZON_MS) ?: Long.MAX_VALUE
        return entryTimestamps.distinct().filter { it !in known || it >= liveFrom }.sortedDescending()
    }

    /** Groups [timestamps] (any order) into reads, newest cluster first so the top of the ledger fills first. */
    fun clusters(timestamps: Collection<Long>): List<Cluster> {
        val sorted = timestamps.distinct().sorted()
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<Cluster>()
        var members = ArrayList<Long>()
        for (t in sorted) {
            if (members.isNotEmpty() && t - members.last() > CLUSTER_GAP_MS) {
                out.add(clusterOf(members))
                members = ArrayList()
            }
            members.add(t)
        }
        out.add(clusterOf(members))
        return out.asReversed()
    }

    private fun clusterOf(members: List<Long>) = Cluster(
        startMs = members.first() - MAX_ANCHOR_DISTANCE_MS - TREND_LOOKBACK_MS,
        endMs = members.last() + MAX_ANCHOR_DISTANCE_MS,
        entryTimestamps = members,
    )

    /**
     * Resolves a cluster's entries against the merged readings loaded for it
     * ([points], ascending): the nearest reading within the distance rule, and
     * the history behind it.
     */
    fun resolve(cluster: Cluster, points: List<GlucosePoint>): Map<Long, JournalGlucoseAnchor> {
        val out = HashMap<Long, JournalGlucoseAnchor>(cluster.entryTimestamps.size)
        for (timestamp in cluster.entryTimestamps) {
            val point = nearest(points, timestamp)
            out[timestamp] = JournalGlucoseAnchor(
                point = point,
                trendHistory = if (point == null) {
                    emptyList()
                } else {
                    rowTrendHistory(points, point.timestamp).take(TREND_POINTS_KEPT)
                },
            )
        }
        return out
    }

    /** The ledger's nearest-reading rule, unchanged: the closest of the neighbours around the insertion point, within [MAX_ANCHOR_DISTANCE_MS]. */
    fun nearest(points: List<GlucosePoint>, timestamp: Long): GlucosePoint? {
        if (points.isEmpty()) return null
        val insertionIndex = points.binarySearchBy(timestamp) { it.timestamp }
            .let { if (it >= 0) it else (-it - 1) }
            .coerceIn(0, points.lastIndex)
        var closest: GlucosePoint? = null
        var closestDistance = Long.MAX_VALUE
        for (index in maxOf(0, insertionIndex - 1)..minOf(points.lastIndex, insertionIndex + 1)) {
            val candidate = points[index]
            val distance = abs(candidate.timestamp - timestamp)
            if (distance < closestDistance) {
                closest = candidate
                closestDistance = distance
            }
        }
        return closest.takeIf { closestDistance <= MAX_ANCHOR_DISTANCE_MS }
    }
}
