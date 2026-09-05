package tk.glucodata.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guards for the Android/native boundary that local JVM tests cannot instantiate. */
class CloneHistoryRecoveryCoordinatorSafetyTests {
    @Test
    fun nativeFacadePublishesStableControlMethodsAndQueuesImport() {
        val source = coordinatorSource().replace(Regex("\\s+"), " ")

        assertTrue(source.contains("@Keep object CloneHistoryRecoveryAccess"))
        assertTrue(source.contains("@JvmStatic fun capabilitiesJson(): String"))
        assertTrue(source.contains("fun prepareIncomingPush(manifestJson: String): String"))
        assertTrue(
            source.contains(
                "fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): String"
            )
        )
        assertTrue(source.contains("fun statusJson(jobId: String): String"))
        assertTrue(source.contains("fun cancelIncoming(cancelJson: String): String"))
        assertTrue(
            source.contains(
                "fun commitIncomingAsync(commitJson: String, transportCode: Int): Boolean"
            )
        )
        assertTrue(source.contains("CoroutineScope(SupervisorJob() + Dispatchers.IO)"))
        assertTrue(source.contains("scope.launch(start = CoroutineStart.LAZY)"))
    }

    @Test
    fun commitIsDurableBeforeLaunchAndRechecksReceiverGateBeforeImport() {
        val source = coordinatorSource().replace(Regex("\\s+"), " ")
        val facadeCommit = source.substring(source.indexOf("fun commitIncomingAsync"))
        val durableAccept = facadeCommit.indexOf("coordinator.beginIncomingCommit(commitJson)")
        val asynchronousLaunch = facadeCommit.indexOf("scope.launch(start = CoroutineStart.LAZY)")

        assertTrue(durableAccept >= 0 && asynchronousLaunch > durableAccept)
        assertTrue(source.contains("receiverEnabled: () -> Boolean"))
        assertTrue(source.contains("require(receiverEnabled()) { \"Clone reception is disabled\" }"))
        assertTrue(
            source.contains("receiverEnabled = CloneSensorRegistry::isReceptionEnabled")
        )
        assertTrue(source.contains("staging.beginImport(current.jobId)"))
        assertTrue(
            source.contains(
                "current.phase == CloneRecoveryPhase.VERIFYING || current.phase == CloneRecoveryPhase.IMPORTING"
            )
        )
        assertTrue(source.contains("staging.completeImport(manifest.jobId)"))
        assertTrue(source.contains("staging.fail(start.status.jobId, error.message)"))
    }

    private fun coordinatorSource(): String = File(
        repoRoot(),
        "Common/src/mobile/java/tk/glucodata/data/CloneHistoryRecoveryCoordinator.kt",
    ).readText()

    private fun repoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "Common/src/main/java/tk/glucodata").isDirectory) {
                return directory
            }
            directory = directory.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }
}
