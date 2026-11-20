package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Health Connect finds a driver's native record through nativeSensorPtr(), and
 * the export cursor survives a direct-stream window rebase.
 *
 * iCan writes its native record by name and keeps no dataptr, so the base
 * getsensorptr(dataptr) lookup never found it and no iCan reading reached
 * Health Connect. rebaseDirectStreamWindow zeroed pollcount but left the
 * cursor pointing into the old window, stalling the export and then skipping
 * everything refilled below it. Source checks, like
 * HealthConnectNullSensorptrTests: the natives live in libg.so.
 */
class HealthConnectNativeRecordTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private fun flattened(relative: String): String =
        File(repoRoot(), relative).readText().replace(Regex("\\s+"), " ")

    @Test
    fun exportAsksTheCallbackForItsNativeRecord() {
        val callback = flattened("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        val export = callback.substring(callback.indexOf("protected final void exportToHealthConnect()"))
            .substringBefore("protected void handleGlucoseResult(")
        assertTrue(export.contains("final long sensorptr = nativeSensorPtr();"))
        assertFalse(
            "the export must not bypass nativeSensorPtr(), or drivers without a dataptr drop out again",
            export.contains("getsensorptr(dataptr)"),
        )
        assertTrue(callback.contains("protected long nativeSensorPtr() { return Natives.getsensorptr(dataptr); }"))
    }

    @Test
    fun iCanResolvesItsNativeRecordByName() {
        val ican = flattened("Common/src/main/java/tk/glucodata/drivers/icanhealth/ICanHealthBleManager.kt")
        assertTrue(ican.contains("override fun nativeSensorPtr(): Long = resolveNativeSensorPtr(SerialNumber)"))
    }

    @Test
    fun nativeBackfillRewindsHealthConnectCursor() {
        val jni = flattened("Common/src/main/cpp/g.cpp")
        val store = jni.substring(jni.indexOf("static bool storeGlucoseStreamSample("))
            .substringBefore("static bool addGlucoseStreamInternal(")
        val gap = store.indexOf("fillsPollGap")
        val invalidate = store.indexOf("healthconnect::gapFilled(&info->healthconnectiter")
        assertTrue("every new gap fill must invalidate an in-flight snapshot", gap >= 0 && invalidate > gap)
    }

    @Test
    fun overlappingHealthConnectTriggersStayQueued() {
        val health = flattened("Common/src/mobile/java/tk/glucodata/HealthConnection.kt")
        val entry = health.substring(health.indexOf("private fun writeAllIns("))
            .substringBefore("private suspend fun exportOneSensor(")
        assertTrue(entry.contains("pendingExports[sensorptr] = sensorName"))
        assertTrue(entry.contains("if (exportWorkerActive)"))
        assertTrue(entry.contains("while (true)"))
        assertTrue(entry.contains("pendingExports.remove(next.key)"))
    }

    @Test
    fun glucoseReplayUsesStableHealthConnectClientRecordIds() {
        val list = flattened("Common/src/mobile/java/tk/glucodata/GlucoseList.java")
        val iterator = list
        val health = flattened("Common/src/mobile/java/tk/glucodata/HealthConnection.kt")

        assertTrue(list.contains("String sensorName"))
        assertTrue(
            iterator.contains(
                "\"juggluco-ng:glucose:\" + sensorName + \":\" + time"
            )
        )
        assertTrue(iterator.contains("Metadata.unknownRecordingMethod("))
        assertTrue(health.contains("GlucoseList(meta, sensorptr, start, take, sensorName)"))
    }

    @Test
    fun rebaseCarriesTheHealthConnectCursorIntoTheNewWindow() {
        val hpp = flattened("Common/src/main/cpp/SensorGlucoseData.hpp")
        val rebase = hpp.substring(hpp.indexOf("void rebaseDirectStreamWindow(uint32_t starttime) {"))
            .substringBefore("void sendbluetoothOn(")
        val shift = rebase.indexOf("(static_cast<int64_t>(starttime) - info->starttime) / 60")
        val moved = rebase.indexOf("healthconnect::reset(&info->healthconnectiter, static_cast<uint16_t>(")
        val overwrite = rebase.indexOf("info->starttime = starttime;")
        assertTrue("rebase must shift the cursor by the window's move in minutes", shift >= 0 && moved > shift)
        assertTrue("the shift must be measured against the old starttime", overwrite > moved)
        assertTrue(
            "a cursor behind the new window start clamps to 0 (from pollstart), never wraps",
            rebase.contains("std::clamp<int64_t>(moved, 0, UINT16_MAX)"),
        )
    }
}
