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
}
