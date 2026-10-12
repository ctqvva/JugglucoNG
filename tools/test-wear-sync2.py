#!/usr/bin/env python3
"""Execute production WearSync2 chunk ingestion with deterministic storage fakes.

JNI, Room scheduling and device transport are outside this test. The fake native
writer models the minute-derived cursor that caused issue #593.
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

object Applic { var isWearable = false }
object SensorIdentity {
    var managed = false
    fun usesNativeDirectStreamShell(serial: String) = managed
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
    fun getSensorUiSnapshot(serial: String): LongArray? {
        snapshotReads++
        check(serial == "3038HVEAD0D")
        return snapshot
    }
    fun ensureSensorShell(serial: String, start: Long) { shellWrites++ }
    fun addGlucoseStreamBatchWithRawTemp(
        times: LongArray, values: FloatArray, raws: FloatArray,
        temps: FloatArray, serial: String,
    ) {
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
    fun stack(tag: String, message: String, error: Throwable): Nothing = throw AssertionError(message, error)
}
object WearSync2 {
    private const val LOG_ID = "WearSync2"
    private const val VERSION = 1
    private const val doLog = false
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
kotlin += method('fun onChunk(') + '\n}\n'
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
        "local-owner", "removed", "companion-disabled" -> {
            when (scenario) {
                "local-owner" -> SensorOwnershipRuntime.local = true
                "removed" -> WearSync2.removed = true
                "companion-disabled" -> WearSync2.enabled = false
            }
            WearSync2.onChunk(chunk())
            check(Natives.snapshotReads == 0 && Natives.shellWrites == 0 && Natives.batches == 0)
            check(HistorySyncAccess.historyTimes.isEmpty() && UiRefreshBus.refreshes == 0)
        }
        else -> error(scenario)
    }
    println("PASS $scenario")
}
'''

scenarios = [
    'phone-libre2', 'phone-alias', 'watch-libre2', 'phone-libre3',
    'phone-sibionics', 'phone-managed', 'phone-managed-family', 'phone-unknown',
    'phone-short-snapshot', 'local-owner', 'removed', 'companion-disabled',
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
