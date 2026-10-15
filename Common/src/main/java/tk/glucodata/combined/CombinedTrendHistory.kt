package tk.glucodata.combined

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.WearPrefsSync
import tk.glucodata.settings.KEY_COMBINED_TREND_SEGMENTS
import tk.glucodata.settings.SettingsRegistry

/** Composition segments and confirmed coverage, persisted before publishing their readings. */
object CombinedTrendHistory {
    internal data class Timeline(val throughMs: Long, val boundaries: List<Long>)
    private data class Segment(val start: Long, val end: Long)
    private data class Cache(val prefs: SharedPreferences, val encoded: String, val timelines: Map<String, Timeline>)
    @Volatile private var cache: Cache? = null
    private val fullId = Regex("COMB-[0-9A-F]{8}")

    // Native/Wear history has second precision; a millisecond boundary must also
    // cover the native copy of the same reading, which has been rounded down.
    private fun second(timeMs: Long) = timeMs / 1000L * 1000L
    private fun prefs(context: Context) = context.getSharedPreferences(SettingsRegistry.FILE, Context.MODE_PRIVATE)

    /** Upgrade the former single cutoff without losing its known boundary. */
    @Synchronized fun migrate(context: Context) {
        val target = prefs(context)
        if (target.contains(KEY_COMBINED_TREND_SEGMENTS)) return
        val legacy = context.getSharedPreferences("combined_trend_boundaries", Context.MODE_PRIVATE).all
        val timelines = legacy.mapNotNull { (id, raw) ->
            val time = (raw as? Long)?.takeIf { it > 0L } ?: return@mapNotNull null
            if (!fullId.matches(id)) return@mapNotNull null
            id to Timeline(second(time), listOf(second(time)))
        }.toMap()
        target.edit().putString(KEY_COMBINED_TREND_SEGMENTS, encode(timelines)).commit()
    }

    private fun timelines(): Map<String, Timeline> {
        val context = Applic.app ?: return emptyMap()
        migrate(context)
        val source = prefs(context)
        val encoded = SettingsRegistry.COMBINED_TREND_SEGMENTS.readString(source)
        cache?.takeIf { it.prefs === source && it.encoded == encoded }?.let { return it.timelines }
        val decoded = decode(encoded) ?: emptyMap()
        return decoded.also { cache = Cache(source, encoded, it) }
    }

    /** Every output confirms coverage; a delayed settings message must not permit a new mix's trend. */
    @Synchronized internal fun recordOutput(id: String, timeMs: Long, startsSegment: Boolean) {
        val canonical = CombinedSensorRegistry.canonicalId(id) ?: return
        if (timeMs <= 0L) return
        val next = timelines().toMutableMap()
        val time = second(timeMs)
        val old = next[canonical]
        val boundaries = (old?.boundaries.orEmpty() + if (startsSegment || old == null) listOf(time) else emptyList())
            .distinct().sorted()
        next[canonical] = Timeline(maxOf(old?.throughMs ?: 0L, time), boundaries)
        prefs(Applic.app).edit().putString(KEY_COMBINED_TREND_SEGMENTS, encode(next)).commit()
        if (!Applic.isWearable) WearPrefsSync.push()
    }

    internal fun encode(timelines: Map<String, Timeline>): String = timelines.toSortedMap().entries.joinToString("\n") { (id, timeline) ->
        "$id|${timeline.throughMs}|${timeline.boundaries.joinToString(",")}"
    }

    internal fun decode(encoded: String): Map<String, Timeline>? {
        if (encoded.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Timeline>()
        for (line in encoded.lines()) {
            val fields = line.split('|')
            if (fields.size != 3 || !fullId.matches(fields[0]) || fields[0] in result) return null
            val through = fields[1].toLongOrNull()?.takeIf { it > 0L && it % 1000L == 0L } ?: return null
            val boundaries = fields[2].split(',').map { value ->
                value.toLongOrNull()?.takeIf { it in 1..through && it % 1000L == 0L } ?: return null
            }
            if (boundaries != boundaries.distinct().sorted()) return null
            result[fields[0]] = Timeline(through, boundaries)
        }
        return result
    }

    /** Reordered snapshots only add knowledge; they never erase a newer boundary or watermark. */
    internal fun mergeEncoded(existing: String, incoming: String): String? {
        val merged = (decode(existing) ?: emptyMap()).toMutableMap()
        val received = decode(incoming) ?: return null
        received.forEach { (id, timeline) ->
            val old = merged[id]
            merged[id] = Timeline(maxOf(old?.throughMs ?: 0L, timeline.throughMs),
                (old?.boundaries.orEmpty() + timeline.boundaries).distinct().sorted())
        }
        return encode(merged)
    }

    private fun segment(id: String?, atTimeMs: Long): Segment? {
        val canonical = CombinedSensorRegistry.canonicalId(id) ?: return Segment(0L, Long.MAX_VALUE)
        val timeline = timelines()[canonical] ?: return null
        val time = if (atTimeMs == Long.MAX_VALUE) timeline.throughMs else second(atTimeMs)
        if (time > timeline.throughMs) return null
        val next = timeline.boundaries.binarySearch(time).let { if (it >= 0) it + 1 else -it - 1 }
        // Older writers retained only their latest cutoff. Nothing before the
        // earliest known boundary establishes continuity, including on upgrade.
        val start = timeline.boundaries.getOrNull(next - 1) ?: return null
        return Segment(start,
            timeline.boundaries.getOrNull(next) ?: Long.MAX_VALUE)
    }

    fun startTime(id: String?, atTimeMs: Long = Long.MAX_VALUE): Long = segment(id, atTimeMs)?.start ?: Long.MAX_VALUE

    fun sameSegment(id: String?, firstTimeMs: Long, lastTimeMs: Long): Boolean {
        val range = segment(id, lastTimeMs) ?: return false
        return firstTimeMs <= lastTimeMs && second(firstTimeMs) >= range.start && second(firstTimeMs) < range.end
    }

    fun filterUi(points: List<tk.glucodata.ui.GlucosePoint>, id: String?,
        atTimeMs: Long = points.maxOfOrNull { it.timestamp } ?: 0L): List<tk.glucodata.ui.GlucosePoint> {
        if (!CombinedSensorRegistry.isCombined(id)) return points
        val range = segment(id, atTimeMs) ?: return emptyList()
        return points.filter { second(it.timestamp) >= range.start && second(it.timestamp) < range.end && it.timestamp <= atTimeMs }
    }

    fun filter(points: List<GlucosePoint>, id: String?,
        atTimeMs: Long = points.maxOfOrNull { it.timestamp } ?: 0L): List<GlucosePoint> {
        if (!CombinedSensorRegistry.isCombined(id)) return points
        val range = segment(id, atTimeMs) ?: return emptyList()
        return points.filter { second(it.timestamp) >= range.start && second(it.timestamp) < range.end && it.timestamp <= atTimeMs }
    }
}
