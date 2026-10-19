package tk.glucodata.drivers.api

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API follower's remote IOB snapshot must neither outlive its source nor be
 * committed by a poll that raced a disable: parsing stages, refresh commits
 * behind a stop gate, and every change retires the broadcast IOB cache.
 *
 * Source checks because the Handler-thread poll, Android preferences, and the
 * Room-backed broadcast cache cannot be constructed in a local JVM test.
 */
class ApiIobLifecycleSafetyTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/NightPost.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private fun manager(): String = source(
        "Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceManager.kt"
    ).replace(Regex("\\s+"), " ")

    @Test
    fun refreshRechecksStopAfterTheNetworkReturns() {
        val flattened = manager()
        val body = flattened.substring(
            flattened.indexOf("private fun refresh("),
            flattened.indexOf("private fun importHistory("),
        )
        val fetch = body.indexOf("fetchReadings()")
        assertTrue("refresh must poll through fetchReadings()", fetch >= 0)
        // The entry check sits before the fetch; the lifecycle gate sits after it.
        assertTrue(
            "refresh must re-check stop after fetchReadings() returns",
            body.lastIndexOf("if (stop)") > fetch,
        )
        assertTrue(
            "a raced poll must drop its snapshot instead of resurrecting cleared state",
            body.substring(fetch).contains("dropApiIobSnapshot()"),
        )
    }

    @Test
    fun parsingStagesSnapshotsInsteadOfPublishingThem() {
        val flattened = manager()
        assertTrue(flattened.contains("noteIobSnapshot(ApiIobSnapshot.parse("))
        assertTrue(flattened.contains("noteIobSnapshot(ApiIobSnapshot.fromTextFields("))
        val outbound = flattened.substring(
            flattened.indexOf("private fun parseOutboundJson("),
            flattened.indexOf("private fun parseMessageText("),
        )
        assertFalse(
            "parseOutboundJson must not publish to the shared snapshot",
            outbound.contains("ApiIobSnapshot.update("),
        )
        val text = flattened.substring(
            flattened.indexOf("private fun parseGlucoWatchMessage("),
            flattened.indexOf("private fun parseTelegramAllowedPeers("),
        )
        assertFalse(
            "parseGlucoWatchMessage must not publish to the shared snapshot",
            text.contains("ApiIobSnapshot.update("),
        )
        assertTrue(flattened.contains("fun commitPendingIobSnapshot()"))
    }

    @Test
    fun pauseAndTerminateDropTheSnapshot() {
        val flattened = manager()
        val pause = flattened.substring(
            flattened.indexOf("override fun softDisconnect()"),
            flattened.indexOf("override fun softReconnect()"),
        )
        assertTrue(pause.contains("dropApiIobSnapshot()"))
        val terminate = flattened.substring(flattened.indexOf("override fun terminateManagedSensor("))
        assertTrue(terminate.contains("dropApiIobSnapshot()"))
    }

    @Test
    fun snapshotChangesInvalidateTheBroadcastCache() {
        val bridge = source("Common/src/main/java/tk/glucodata/JournalSnapshotBridge.kt")
        val access = source("Common/src/main/java/tk/glucodata/JournalSnapshotAccess.kt")
        val snapshots = source("Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt")
            .replace(Regex("\\s+"), " ")
        assertTrue(bridge.contains("fun invalidateBroadcastIobCache()"))
        assertTrue(access.contains("fun invalidateBroadcastIobCache()"))
        assertTrue(snapshots.contains("override fun invalidateBroadcastIobCache()"))
        val registry = source(
            "Common/src/main/java/tk/glucodata/drivers/api/ApiGlucoseSourceRegistry.kt"
        ).replace(Regex("\\s+"), " ")
        assertTrue(registry.contains("ApiIobSnapshot.clear()"))
        assertTrue(registry.contains("invalidateBroadcastIobCache()"))
        val flattened = manager()
        assertTrue(flattened.contains("ApiIobSnapshot.clear()"))
        assertTrue(flattened.contains("invalidateBroadcastIobCache()"))
    }
}
