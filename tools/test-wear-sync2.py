#!/usr/bin/env python3
"""Execute production WearSync2 chunk ingestion and backlog requests with deterministic fakes.

JNI, Room scheduling and device transport are outside this test. The fake native
writer models the minute-derived cursor that caused issue #593. The request
scenarios run the production request encoding and the phone's choice between a
full serve and the tail, including a watch that must have the full horizon (#862).
The managed handover replay runs the production history reader, chunk encoder
and ingestion in both directions, switching deterministic phone/watch fakes.
It is not a BLE/Data Layer test or two live Android runtimes.
Requires kotlinc and java on PATH.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'Common/src/main/java/tk/glucodata/WearSync2.kt').read_text()


def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end]


kotlin = r'''
package tk.glucodata
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.TreeMap

object Applic { var isWearable = false }
object SensorIdentity {
    var managed = false
    fun usesNativeDirectStreamShell(serial: String) = managed
    fun canonicalSensorId(serial: String) = serial
    fun resolveAppSensorId(serial: String?) = serial
    fun resolveMainSensor(): String? = null
    fun shouldUseNativeHistorySync(serial: String) = true
    fun resolveNativeHistorySensorNames(serial: String) = listOf(serial)
}
object SensorOwnershipRuntime {
    var local = false
    fun readsLocally(serial: String) = local
}
object Natives {
    val start = 1_780_000_000L
    var snapshot: LongArray? = longArrayOf(2, 0, start * 1000, 0, 0, 0)
    var snapshotReads = 0
    var shellWrites = 0
    var batches = 0
    var pollcount = 19
    var lastId = 2903
    var nativeTimes = longArrayOf()
    var nativeValues = floatArrayOf()
    var nativeRaws = floatArrayOf()
    var nativeSerial = ""
    var backfillBatches = 0
    val watchHistory = sortedMapOf<Long, Pair<Float, Float>>()
    fun activeSensors() = arrayOf("SIBI:0111801JGS9")
    fun getGlucoseHistoryForSensor(serial: String, start: Long): LongArray = watchHistory
        .filterKeys { it > start }.flatMap { (t, v) -> listOf(t, (v.first * 10).toLong(), (v.second * 10).toLong()) }
        .toLongArray()
    fun getSensorUiSnapshot(serial: String): LongArray? {
        snapshotReads++
        check(serial == "3038HVEAD0D")
        return snapshot
    }
    fun lastglucosetime() = 0L
    fun ensureSensorShell(serial: String, start: Long) { shellWrites++ }
    fun addGlucoseStreamBatchWithRawTemp(
        times: LongArray, values: FloatArray, raws: FloatArray,
        temps: FloatArray, serial: String,
    ): Int {
        batches++
        nativeTimes = times
        nativeValues = values
        nativeRaws = raws
        nativeSerial = serial
        for (time in times) {
            val id = ((time - start) / 60).toInt()
            if (id >= pollcount) pollcount = id + 1
            lastId = maxOf(lastId, id)
        }
        if (Applic.isWearable) for (i in times.indices) watchHistory[times[i]] = values[i] * 10f to raws[i]
        return times.size
    }
    fun backfillGlucoseStreamBatchWithRawTemp(
        times: LongArray, values: FloatArray, raws: FloatArray,
        temps: FloatArray, serial: String,
    ): Int {
        backfillBatches++
        var stored = 0
        for (i in times.indices) {
            // Slot atomicity and origin handling run against the production C++
            // writer in test-resolved-glucose.py; this models its gap-only result.
            if (watchHistory.putIfAbsent(times[i], values[i] * 10f to raws[i]) == null) stored++
        }
        return stored
    }
    fun acceptBle(id: Int): Boolean {
        if (id <= lastId) return false
        lastId = id
        pollcount++
        return true
    }
}
object HistorySyncAccess {
    var historyTimes = longArrayOf()
    var historyValues = floatArrayOf()
    var historyRaws = floatArrayOf()
    var historySerial = ""
    var currentTime = 0L
    var currentValue = 0f
    var currentRaw = 0f
    fun storeSensorHistoryBatchAsync(
        serial: String, times: LongArray, values: FloatArray, raws: FloatArray,
    ) {
        historySerial = serial
        historyTimes = times
        historyValues = values
        historyRaws = raws
        HistoryRepositoryAccess.phoneHistory = times.indices.map {
            GlucosePoint(times[it], values[it], raws[it])
        }
    }
    fun storeCurrentReadingAsync(time: Long, value: Float, raw: Float, rate: Float, serial: String) {
        check(serial == historySerial)
        currentTime = time
        currentValue = value
        currentRaw = raw
    }
}
object WearSensorSelectionSync {
    var selected = ""
    fun alignCurrentSensor(fallback: String) { selected = fallback }
}
object UiRefreshBus {
    var refreshes = 0
    fun requestDataRefresh() { refreshes++ }
}
object Log {
    const val doLog = false
    fun i(tag: String, message: String) {}
    fun w(tag: String, message: String, error: Throwable? = null) {}
    fun stack(tag: String, message: String, error: Throwable): Nothing = throw AssertionError(message, error)
}
object WearMessagePath { const val SYNC2_REQ = "/sync2/req"; const val SYNC2_CHUNK = "/sync2/chunk" }
object GlucoseValuePlausibility { fun isPlausibleMgdl(value: Float) = value in 20f..900f }
data class GlucosePoint(val timestamp: Long, val value: Float, val rawValue: Float)
object HistoryRepositoryAccess {
    var phoneHistory = emptyList<GlucosePoint>()
    fun getHistoryForSensor(serial: String, start: Long, mmol: Boolean): List<GlucosePoint>? =
        if (Applic.isWearable) null else phoneHistory.filter { it.timestamp > start }
}
object MessageSender {
    var sent: ByteArray? = null
    val chunks = ArrayList<ByteArray>()
    fun sendSyncMessage(path: String, data: ByteArray): Boolean { check(path == WearMessagePath.SYNC2_REQ); sent = data; return true }
    fun sendSyncMessageAwait(path: String, data: ByteArray, timeoutSeconds: Long): Boolean {
        check(path == WearMessagePath.SYNC2_CHUNK)
        chunks += data
        return true
    }
}
object WearSync2 {
    private const val LOG_ID = "WearSync2"
    private const val VERSION = 1
    private const val doLog = false
    private const val BACKFILL_HORIZON_SEC = 14L * 24L * 3600L
    private const val INCREMENTAL_OVERLAP_SEC = 15L * 60L
    private const val DEEP_SERVE_MIN_INTERVAL_MS = 30L * 60L * 1000L
    private const val MAX_TRIPLES_PER_CHUNK = 360
    const val TAIL = -1L
    private val lastDeepServeMs = AtomicLong(0L)
    private val lastServedChunkCount = AtomicLong(0L)
    private val lastServedMs = AtomicLong(0L)
    private fun sendCalibration(serial: String) {}
    val served = ArrayList<Long>()
    private fun serveSince(fromSec: Long) { served.add(fromSec) }
    private fun tailStartSec(): Long = TAIL
    private object executor { fun execute(action: () -> Unit) = action() }
    var enabled = true
    var removed = false
    var exchangedAt = 0L
    private fun wearCompanionEnabled() = enabled
    private fun existingSensorNameFor(serial: String) =
        if (serial == "full-alias") "3038HVEAD0D" else serial
    private fun shouldIgnoreRemovedSensor(serial: String) = removed
    private fun emitExchangeOutputsForSyncedReading(serial: String, time: Long, value: Float) {
        exchangedAt = time
    }
'''
kotlin += method('private fun isLibre2NativeSensor(') + '\n'
kotlin += method('fun onChunk(') + '\n'
kotlin += method('fun onRequest(') + '\n'
kotlin += method('private fun sanitizeFrom(') + '\n'
kotlin += method('private fun allowDeepServe(') + '\n'
kotlin += method('fun requestSync(') + '\n'
kotlin += method('private fun serveSensorSince(').replace('private fun', 'fun', 1) + '\n'
kotlin += method('private fun sendChunk(') + '\n}\n'
kotlin += (root / 'Common/src/main/java/tk/glucodata/NotificationHistorySource.kt').read_text().replace(
    'package tk.glucodata', '').replace('import android.util.Log', '').replace('import java.util.TreeMap', '')
kotlin += r'''
fun chunk(serial: String = "3038HVEAD0D"): ByteArray {
    val name = serial.toByteArray()
    val buf = ByteBuffer.allocate(5 + name.size + 8 * 16)
    buf.put(1).put(1).putShort(8).put(name.size.toByte()).put(name)
    for (i in 0 until 8) {
        buf.putLong(Natives.start + (2918 + i) * 60L)
        buf.putInt(1280 + i * 10).putInt(1200 + i * 10)
    }
    return buf.array()
}
fun assertPhoneImport() {
    check(HistorySyncAccess.historySerial == "3038HVEAD0D")
    check(HistorySyncAccess.historyTimes.size == 8)
    check(HistorySyncAccess.historyTimes.first() == (Natives.start + 2918 * 60L) * 1000)
    check(HistorySyncAccess.historyValues.contentEquals(FloatArray(8) { 128f + it }))
    check(HistorySyncAccess.historyRaws.contentEquals(FloatArray(8) { 120f + it }))
    val newest = (Natives.start + 2925 * 60L) * 1000
    check(HistorySyncAccess.currentTime == newest)
    check(HistorySyncAccess.currentValue == 135f && HistorySyncAccess.currentRaw == 127f)
    check(WearSync2.exchangedAt == newest)
    check(WearSensorSelectionSync.selected == "3038HVEAD0D" && UiRefreshBus.refreshes == 1)
}
fun main(args: Array<String>) {
    val scenario = args.single()
    when (scenario) {
        "phone-libre2", "phone-alias" -> {
            WearSync2.onChunk(chunk(if (scenario == "phone-alias") "full-alias" else "3038HVEAD0D"))
            check(Natives.shellWrites == 0 && Natives.batches == 0)
            check(Natives.pollcount == 19 && Natives.lastId == 2903)
            assertPhoneImport()
            // Returning from the watch must not suppress valid BLE IDs 2913..2925.
            for (id in 2913..2926) check(Natives.acceptBle(id))
        }
        "watch-libre2", "phone-libre3", "phone-sibionics", "phone-managed",
        "phone-managed-family", "phone-unknown", "phone-short-snapshot" -> {
            when (scenario) {
                "watch-libre2" -> Applic.isWearable = true
                "phone-libre3" -> Natives.snapshot!![0] = 3
                "phone-sibionics" -> Natives.snapshot!![0] = 0x10
                "phone-managed" -> SensorIdentity.managed = true
                "phone-managed-family" -> Natives.snapshot!![5] = 1
                "phone-unknown" -> Natives.snapshot = null
                "phone-short-snapshot" -> Natives.snapshot = longArrayOf(2, 0)
            }
            WearSync2.onChunk(chunk())
            check(Natives.shellWrites == 1 && Natives.batches == 1)
            check(Natives.nativeSerial == "3038HVEAD0D")
            check(Natives.nativeTimes.size == 8)
            check(Natives.nativeValues.contentEquals(FloatArray(8) { (1280 + it * 10) / 100f }))
            check(Natives.nativeRaws.contentEquals(FloatArray(8) { 120f + it }))
            check(Natives.pollcount == 2926 && Natives.lastId == 2925)
            if (Applic.isWearable) {
                check(Natives.snapshotReads == 0 && HistorySyncAccess.historyTimes.isEmpty())
            } else assertPhoneImport()
        }
        "local-owner", "phone-managed-owner", "watch-native-owner", "removed", "companion-disabled" -> {
            when (scenario) {
                "local-owner" -> SensorOwnershipRuntime.local = true
                "phone-managed-owner" -> { SensorOwnershipRuntime.local = true; SensorIdentity.managed = true }
                "watch-native-owner" -> { SensorOwnershipRuntime.local = true; Applic.isWearable = true }
                "removed" -> WearSync2.removed = true
                "companion-disabled" -> WearSync2.enabled = false
            }
            WearSync2.onChunk(chunk())
            check(Natives.snapshotReads == 0 && Natives.shellWrites == 0 && Natives.batches == 0)
            check(HistorySyncAccess.historyTimes.isEmpty() && UiRefreshBus.refreshes == 0)
            check(Natives.backfillBatches == 0)
        }
        "managed-watch-handover" -> {
            val serial = "SIBI:0111801JGS9"
            SensorIdentity.managed = true
            // Phone owns the sensor and serves the real source-lane wire format.
            HistoryRepositoryAccess.phoneHistory = (0 until 8).map { i ->
                GlucosePoint((Natives.start + (2918 + i) * 60L) * 1000L, 128f + i, 120f + i)
            }
            WearSync2.serveSensorSince(serial, 0L)
            val phoneChunk = MessageSender.chunks.single()
            MessageSender.chunks.clear()
            // It takes over before the phone chunk lands and receives a local
            // reading. The old guard discarded all seven missing older rows.
            Applic.isWearable = true
            SensorOwnershipRuntime.local = true
            val localTime = Natives.start + 2925 * 60L
            Natives.watchHistory[localTime] = 171f to 161f
            WearSensorSelectionSync.selected = "another-selected-sensor"
            WearSync2.onChunk(phoneChunk)
            check(Natives.backfillBatches == 1 && Natives.batches == 0 && Natives.shellWrites == 0)
            val chart = NotificationHistorySource.getDisplayHistory(0L, false, serial)
            check(chart.size == 8 && chart.first().value == 128f)
            check(chart.last().value == 171f && chart.last().rawValue == 161f)
            check(WearSensorSelectionSync.selected == "another-selected-sensor")
            check(HistorySyncAccess.currentTime == 0L && WearSync2.exchangedAt == 0L)
            // Replayed backfill preserves the local value as well.
            WearSync2.onChunk(phoneChunk)
            check(Natives.watchHistory.size == 8 && Natives.watchHistory[localTime]!!.first == 171f)
            // Another BLE reading is stored by the watch, served through the
            // same production history reader and encoder, then displayed and
            // forwarded by the phone after it has stood down.
            Natives.watchHistory[localTime + 60L] = 172f to 162f
            WearSync2.serveSensorSince(serial, 0L)
            val watchChunk = MessageSender.chunks.single()
            Applic.isWearable = false
            SensorOwnershipRuntime.local = false
            WearSync2.onChunk(watchChunk)
            val phoneChart = NotificationHistorySource.getDisplayHistory(0L, false, serial)
            check(phoneChart.size == 9 && phoneChart.last().value == 172f)
            check(HistorySyncAccess.currentTime == (localTime + 60L) * 1000L)
            check(HistorySyncAccess.currentValue == 172f && WearSync2.exchangedAt == (localTime + 60L) * 1000L)
        }
        "request-encoding" -> {
            WearSync2.requestSync(deep = true)
            var sent = MessageSender.sent!!
            check(sent.size == 10 && sent[0] == 1.toByte() && sent[9] == 0.toByte())
            val horizon = ByteBuffer.wrap(sent, 1, 8).long
            check(horizon < System.currentTimeMillis() / 1000L - 13L * 24L * 3600L)
            WearSync2.requestSync(deep = true, force = true)
            sent = MessageSender.sent!!
            check(sent.size == 10 && sent[9] == 1.toByte())
        }
        "request-serving" -> {
            fun request(fromSec: Long, force: Int?): ByteArray {
                val buf = ByteBuffer.allocate(if (force == null) 9 else 10)
                buf.put(1).putLong(fromSec)
                if (force != null) buf.put(force.toByte())
                return buf.array()
            }
            val horizon = System.currentTimeMillis() / 1000L - 14L * 24L * 3600L
            WearSync2.onRequest(request(horizon, null))
            check(WearSync2.served.size == 1 && WearSync2.served.last() != WearSync2.TAIL)
            // Within the spacing between full serves only the tail goes, whether
            // the request is the older nine-byte form or says not to force.
            WearSync2.onRequest(request(horizon, null))
            check(WearSync2.served.size == 2 && WearSync2.served.last() == WearSync2.TAIL)
            WearSync2.onRequest(request(horizon, 0))
            check(WearSync2.served.size == 3 && WearSync2.served.last() == WearSync2.TAIL)
            // A watch that threw its copy away gets the full horizon at once...
            WearSync2.onRequest(request(horizon, 1))
            check(WearSync2.served.size == 4 && WearSync2.served.last() != WearSync2.TAIL)
            // ...and that counts as the latest full serve.
            WearSync2.onRequest(request(horizon, null))
            check(WearSync2.served.size == 5 && WearSync2.served.last() == WearSync2.TAIL)
            // A forced request for a short range is just served from there.
            val recent = System.currentTimeMillis() / 1000L - 3600L
            WearSync2.onRequest(request(recent, 1))
            check(WearSync2.served.size == 6 && WearSync2.served.last() == recent)
            // Unusable requests serve nothing.
            WearSync2.onRequest(null)
            WearSync2.onRequest(byteArrayOf(2) + ByteArray(9))
            WearSync2.onRequest(byteArrayOf(1, 0, 0))
            check(WearSync2.served.size == 6)
        }
        else -> error(scenario)
    }
    println("PASS $scenario")
}
'''

scenarios = [
    'phone-libre2', 'phone-alias', 'watch-libre2', 'phone-libre3',
    'phone-sibionics', 'phone-managed', 'phone-managed-family', 'phone-unknown',
    'phone-short-snapshot', 'local-owner', 'phone-managed-owner', 'watch-native-owner',
    'managed-watch-handover', 'removed', 'companion-disabled',
    'request-encoding', 'request-serving',
]
with tempfile.TemporaryDirectory(prefix='wear-sync2-test-') as directory:
    temp = Path(directory)
    (temp / 'Replay.kt').write_text(kotlin)
    subprocess.run([
        'kotlinc', str(temp / 'Replay.kt'), '-nowarn', '-include-runtime',
        '-d', str(temp / 'replay.jar'),
    ], check=True)
    for scenario in scenarios:
        subprocess.run(['java', '-jar', str(temp / 'replay.jar'), scenario], check=True)
