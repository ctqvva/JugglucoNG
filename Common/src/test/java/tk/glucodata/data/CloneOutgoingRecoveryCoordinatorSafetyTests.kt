package tk.glucodata.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source guards for the Android coordinator boundary; protocol transitions are JVM-tested. */
class CloneOutgoingRecoveryCoordinatorSafetyTests {
    private val source = File(
        repoRoot(),
        "Common/src/mobile/java/tk/glucodata/data/CloneOutgoingRecoveryCoordinator.kt",
    ).readText().replace(Regex("\\s+"), " ")

    @Test
    fun facadeIsActionDrivenAndPackagePreparationIsAsynchronous() {
        assertTrue(source.contains("@Keep object CloneOutgoingRecoveryAccess"))
        assertTrue(source.contains("fun probeOutgoing(iceLabel: String, connectionGeneration: Long): String"))
        assertTrue(source.contains("fun startOutgoingPush("))
        assertTrue(source.contains("fun nextOutgoingAction(iceLabel: String, connectionGeneration: Long): ByteArray"))
        assertTrue(source.contains("fun reportOutgoingResult("))
        assertTrue(source.contains("fun outgoingStatusJson(iceLabel: String): String"))
        assertTrue(source.contains("fun cancelOutgoing(iceLabel: String): String"))
        assertTrue(source.contains("fun resumeOutgoing(iceLabel: String, connectionGeneration: Long): Int"))
        assertTrue(source.contains("fun outgoingLabelsJson(): String"))
        assertTrue(source.contains("fun clearOutgoing(iceLabel: String): Boolean"))
        assertTrue(source.contains("scope.launch(start = CoroutineStart.LAZY)"))
        assertTrue(source.contains("scheduleState(coordinator.ensurePrepared(iceLabel))"))
        assertFalse(source.contains("fun packageFile("))
    }

    @Test
    fun generationChangesAndTransportFailuresForceCapabilityReprobe() {
        assertTrue(source.contains("result.connectionGeneration == generation"))
        assertTrue(source.contains("state.connectionGeneration == generation"))
        assertTrue(source.contains("phase = CloneOutgoingPhase.PROBING"))
        assertTrue(source.contains("capabilityGeneration = null"))
        assertTrue(source.contains("reconcileAfterProbe = state.jobId != null"))
        assertTrue(source.contains("Natives.wakeCloneRecovery(iceLabel)"))
        assertTrue(source.contains("delay(delayMillis.coerceAtLeast(1L))"))
    }

    private fun repoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "Common/src/main/java/tk/glucodata").isDirectory) return directory
            directory = directory.parentFile
        }
        throw AssertionError("Common/src not found")
    }
}
