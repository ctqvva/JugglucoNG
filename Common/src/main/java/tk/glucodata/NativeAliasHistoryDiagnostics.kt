package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tk.glucodata.drivers.sibionics.SibionicsRegistry

/** On-demand diagnostics only. No history getter, native mapping, replay or sensor-store write. */
@Keep
object NativeAliasHistoryDiagnostics {
    private const val HORIZON_SECONDS = 14L * 24L * 3600L
    private const val POLL_BYTES = 20 // persisted ScanData; tested against the native layout
    private const val MAX_SLOTS = 100_000L
    private const val MAX_SENSOR_INDEX = 46 * 24 * 60

    internal data class Sample(val second: Long, val auto: Int, val raw: Int?, val resolved: Boolean)
    internal data class Snapshot(
        val name: String, val status: String, val rawStatus: String,
        val slots: Long, val rows: Int, val minutes: Map<Long, Sample>,
    )

    /** Trace exports call this on demand; never called by a history/display/algorithm path. */
    @JvmStatic
    @JvmOverloads
    fun report(context: Context? = Applic.app): String {
        if (context == null) return ""
        return runCatching {
            val records = SibionicsRegistry.persistedRecords(context)
                .filter { it.legacyNativeName.isNotBlank() }.take(4)
            if (records.isEmpty()) return ""
            val now = System.currentTimeMillis() / 1000L
            buildString {
                append("# Native alias poll diagnostics v1; best_effort=true\n")
                append("# window_sec=").append(now - HORIZON_SECONDS).append("..").append(now)
                    .append("; auto_algorithm=UNKNOWN; historical_calibration=UNKNOWN; merge_allowed=false\n")
                val directory = File(context.filesDir, "sensors")
                records.forEach { record ->
                    val canonicalNames = Natives.getNativeHistoryRecordNames(record.sensorId)?.toList()
                    val legacyNames = Natives.getNativeHistoryRecordNames(record.legacyNativeName)?.toList()
                    append(inspect(directory, record.sensorId, record.legacyNativeName,
                        canonicalNames, legacyNames, now - HORIZON_SECONDS, now))
                }
                append("# End native alias poll diagnostics\n\n")
            }
        }.getOrElse { "# Native alias poll diagnostics unavailable: ${it.javaClass.simpleName}\n\n" }
    }

    @JvmStatic
    fun logForTraceExport() {
        if (!Log.doLog) return
        // Optional diagnostics must not prevent the existing trace export.
        runCatching {
            val report = report()
            if (report.isNotEmpty()) Log.i("NativeAliasHistory", report)
        }
    }

    internal fun inspect(
        directory: File, canonicalId: String, legacyId: String,
        canonicalNames: List<String>?, legacyNames: List<String>?, fromSec: Long, untilSec: Long,
    ): String = buildString {
        append("# sensor=").append(canonicalId).append(" registered_alias=").append(legacyId).append('\n')
        if (canonicalNames == null || legacyNames == null) {
            append("# classification=LOOKUP_UNAVAILABLE\n")
            return@buildString
        }
        val current = canonicalNames.distinct()
        val old = legacyNames.distinct()
        append("# canonical_records=").append(current.joinToString(",").ifEmpty { "NONE" })
            .append(" alias_records=").append(old.joinToString(",").ifEmpty { "NONE" }).append('\n')
        if (current.size != 1) {
            append("# classification=").append(if (current.isEmpty()) "CANONICAL_MISSING" else "CANONICAL_AMBIGUOUS").append('\n')
            return@buildString
        }
        val canonical = read(directory, current.single(), fromSec, untilSec)
        append(describe(canonical, "canonical"))
        val distinctOld = old.filter { it != canonical.name }
        if (distinctOld.isEmpty()) {
            append("# classification=").append(if (old.isEmpty()) "ALIAS_MISSING" else "SAME_NATIVE_RECORD").append('\n')
        }
        distinctOld.take(4).forEach { name ->
            val alias = read(directory, name, fromSec, untilSec)
            append(describe(alias, "alias"))
            val overlap = canonical.minutes.keys.intersect(alias.minutes.keys)
            val auto = overlap.filter { canonical.minutes.getValue(it).auto > 0 && alias.minutes.getValue(it).auto > 0 }
            val raw = overlap.filter {
                (canonical.minutes.getValue(it).raw ?: 0) > 0 && (alias.minutes.getValue(it).raw ?: 0) > 0
            }
            val classification = when {
                canonical.status != "OK" || alias.status != "OK" -> "INCOMPLETE_SNAPSHOT"
                canonical.minutes.isEmpty() || alias.minutes.isEmpty() -> "ONE_OR_BOTH_EMPTY"
                else -> "SPLIT_POLL_HISTORY"
            }
            append("# classification=").append(classification)
                .append(" overlap_minutes=").append(overlap.size)
                .append(" canonical_only_minutes=").append(canonical.minutes.size - overlap.size)
                .append(" alias_only_minutes=").append(alias.minutes.size - overlap.size)
                .append(" stored_auto_comparable=").append(auto.size)
                .append(" stored_auto_different=").append(auto.count { canonical.minutes.getValue(it).auto != alias.minutes.getValue(it).auto })
                .append(" raw_comparable=").append(raw.size)
                .append(" raw_different=").append(raw.count { canonical.minutes.getValue(it).raw != alias.minutes.getValue(it).raw })
                .append(" representation_different=").append(auto.count { canonical.minutes.getValue(it).resolved != alias.minutes.getValue(it).resolved })
                .append("; equality_is_not_algorithm_proof=true\n")
        }
    }

    private fun describe(snapshot: Snapshot, role: String): String = buildString {
        append("# ").append(role).append(" name=").append(snapshot.name)
            .append(" polls=").append(snapshot.status).append(" raw=").append(snapshot.rawStatus)
            .append(" slots=").append(snapshot.slots).append(" window_rows=").append(snapshot.rows)
            .append(" window_minutes=").append(snapshot.minutes.size)
            .append(" first_sec=").append(snapshot.minutes.values.minOfOrNull { it.second } ?: 0)
            .append(" last_sec=").append(snapshot.minutes.values.maxOfOrNull { it.second } ?: 0)
            .append(" resolved_minutes=").append(snapshot.minutes.values.count { it.resolved }).append('\n')
    }

    internal fun read(directory: File, name: String, fromSec: Long, untilSec: Long): Snapshot {
        fun empty(status: String) = Snapshot(name, status, "UNKNOWN", 0, 0, emptyMap())
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name)
            return empty("INVALID_NAME")
        val record = File(directory, name)
        val polls = File(record, "polls.dat")
        if (!polls.isFile) return empty("MISSING")
        val raw = File(record, "rawpolls.dat")
        val slots = polls.length() / POLL_BYTES
        if (slots > MAX_SLOTS) return empty("OVERSIZED")
        var rawStatus = if (raw.isFile) "OK" else "MISSING"
        return try {
            val minutes = HashMap<Long, Sample>()
            var rows = 0
            DataInputStream(BufferedInputStream(polls.inputStream())).use { input ->
                val rawInput = if (raw.isFile) DataInputStream(BufferedInputStream(raw.inputStream())) else null
                rawInput.use { rawStream ->
                    val bytes = ByteArray(POLL_BYTES)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(slots.toInt()) {
                        input.readFully(bytes)
                        val rawValue = if (rawStatus != "OK") null else try {
                            java.lang.Short.reverseBytes(rawStream!!.readShort()).toInt() and 0xffff
                        } catch (_: EOFException) { rawStatus = "TRUNCATED"; null }
                        val second = buffer.getInt(0).toLong() and 0xffffffffL
                        val index = buffer.getInt(4)
                        val glucose = buffer.getInt(8)
                        val auto = (glucose and 0xffff).takeIf { it in 1..551 } ?: 0
                        if (second !in fromSec..untilSec || second !in 1_598_911_201L until 2_145_909_600L ||
                            index !in 0 until MAX_SENSOR_INDEX || (auto == 0 && (rawValue ?: 0) == 0)
                        ) return@repeat
                        rows++
                        val sample = Sample(second, auto, rawValue, glucose and 0x10000 != 0)
                        val previous = minutes[second / 60]
                        if (previous == null || previous.second < second) minutes[second / 60] = sample
                    }
                }
            }
            Snapshot(name, if (polls.length() == slots * POLL_BYTES) "OK" else "TRUNCATED",
                rawStatus, slots, rows, minutes)
        } catch (_: Exception) { empty("READ_FAILED") }
    }
}
