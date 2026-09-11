package tk.glucodata

import java.io.File
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
    fun reflectedCloneIobMethodsSurviveReleaseMinification() {
        val snapshots = source("Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt")
            .replace(Regex("\\s+"), " ")
        val rules = source("Common/proguard-rules.my")
            .replace(Regex("\\s+"), " ")

        assertTrue(snapshots.contains("@Keep @JvmStatic fun cloneIobSnapshotJson"))
        assertTrue(snapshots.contains("@Keep @JvmStatic fun importCloneIobSnapshot"))
        assertTrue(snapshots.contains("@Keep @JvmStatic fun cloneJournalSnapshotJson"))
        assertTrue(snapshots.contains("@Keep @JvmStatic fun importCloneJournalSnapshot"))
        assertTrue(rules.contains("java.lang.String cloneIobSnapshotJson(long);"))
        assertTrue(rules.contains("boolean importCloneIobSnapshot(java.lang.String);"))
        assertTrue(rules.contains("java.lang.String cloneJournalSnapshotJson(long);"))
        assertTrue(rules.contains("boolean importCloneJournalSnapshot(java.lang.String,int);"))
    }
}
