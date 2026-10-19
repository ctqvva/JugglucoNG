package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIobSnapshotSafetyTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/NightPost.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    @Test
    fun nightscoutUploadCannotRefreshAReceivedRemoteSnapshot() {
        val uploader = source("Common/src/main/java/tk/glucodata/NightPost.java")
        val snapshots = source("Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt")
            .replace(Regex("\\s+"), " ")

        assertTrue(uploader.contains("JournalIobAccess.nightscoutUploadSnapshot(now)"))
        assertTrue(snapshots.contains("allowCloneRemote = false"))
        assertTrue(snapshots.contains("allowNightscoutRemote = false"))
    }

    @Test
    fun cloneIobMethodsAreRegisteredAndSurviveReleaseMinification() {
        val snapshots = source("Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt")
            .replace(Regex("\\s+"), " ")
        val access = source("Common/src/main/java/tk/glucodata/JournalSnapshotAccess.kt")
            .replace(Regex("\\s+"), " ")
        val rules = source("Common/proguard-rules.my")
            .replace(Regex("\\s+"), " ")

        // Reached through JournalSnapshotBridge instead of by name, so R8 keeps the
        // implementations because the interface calls reach them.
        assertTrue(snapshots.contains("object OutboundApiJournalSnapshot : JournalSnapshotBridge"))
        assertTrue(snapshots.contains("@Keep override fun cloneIobSnapshotJson"))
        assertTrue(snapshots.contains("@Keep override fun importCloneIobSnapshot"))
        assertTrue(snapshots.contains("@Keep override fun cloneJournalSnapshotJson"))
        assertTrue(snapshots.contains("@Keep override fun importCloneJournalSnapshot"))
        assertTrue(access.contains("fun cloneIobSnapshotJson("))
        assertTrue(access.contains("fun importCloneJournalSnapshot("))
        // The reflection-era keep rules are gone; the names no longer need pinning.
        assertFalse(rules.contains("tk.glucodata.OutboundApiJournalSnapshot"))
    }
}
