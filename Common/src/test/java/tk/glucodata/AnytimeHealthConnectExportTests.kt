package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anytime is a managed driver: its live/current publication bypasses
 * SuperGattCallback.handleGlucoseResult(), so Health Connect must be triggered
 * explicitly after its native mirror accepts data.
 */
class AnytimeHealthConnectExportTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private fun flattened(relative: String): String =
        File(repoRoot(), relative).readText().replace(Regex("\\s+"), " ")

    @Test
    fun exporterIsAvailableToManagedCallbacks() {
        val callback = flattened("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        assertTrue(callback.contains("protected final void exportToHealthConnect()"))
    }

    @Test
    fun liveNativeWriteTriggersHealthConnectOnlyAfterSuccessfulStore() {
        val anytime = flattened(
            "Common/src/main/java/tk/glucodata/drivers/anytime/AnytimeBleManager.kt"
        )
        val start = anytime.indexOf("private fun mirrorValuesIntoNative(")
        val end = anytime.indexOf("private fun mirrorHistoryBatchIntoNative(", start)
        val body = anytime.substring(start, end)
        val store = body.indexOf("Natives.addGlucoseStreamWithRawTemp(")
        val success = body.indexOf("if (stored) {", store)
        val export = body.indexOf("exportToHealthConnect()", success)
        assertTrue(store >= 0 && success > store && export > success)
    }

    @Test
    fun historyBatchTriggersOneHealthConnectExportAfterSuccessfulStore() {
        val anytime = flattened(
            "Common/src/main/java/tk/glucodata/drivers/anytime/AnytimeBleManager.kt"
        )
        val start = anytime.indexOf("private fun mirrorHistoryBatchIntoNative(")
        val end = anytime.indexOf("private fun effectiveLifetimeDays()", start)
        val body = anytime.substring(start, end)
        val store = body.indexOf("Natives.addGlucoseStreamBatchWithRawTemp(")
        val success = body.indexOf("if (stored > 0) {", store)
        val export = body.indexOf("exportToHealthConnect()", success)
        assertTrue(store >= 0 && success > store && export > success)
        assertTrue(Regex("exportToHealthConnect\\(\\)").findAll(body).count() == 1)
    }
}
