package tk.glucodata

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class CloneRecoveryStagingTests {
    @Test
    fun chunksResumeAtVerifiedBoundariesAndCompleteByDigest() = withStaging { staging ->
        val payload = "bounded clone recovery payload".toByteArray()
        val request = request()
        val manifest = manifest(request, payload)

        assertEquals(0L, staging.prepareIncoming(manifest, request))
        assertEquals(12L, staging.writeIncomingChunk(request.jobId, 0L, payload.copyOfRange(0, 12)))
        assertEquals(
            12L,
            staging.writeIncomingChunk(request.jobId, 0L, payload.copyOfRange(0, 12)),
        )
        assertEquals(
            payload.size.toLong(),
            staging.writeIncomingChunk(
                request.jobId,
                12L,
                payload.copyOfRange(12, payload.size),
            ),
        )

        assertArrayEquals(payload, staging.verifiedPackageFile(request.jobId).readBytes())
        assertArrayEquals(
            payload.copyOfRange(5, 13),
            staging.readPackageChunk(request.jobId, 5L, 8),
        )
    }

    @Test
    fun changedConfirmationOrChunkCannotReuseAJob() = withStaging { staging ->
        val payload = "immutable transfer".toByteArray()
        val request = request()
        val manifest = manifest(request, payload)
        staging.prepareIncoming(manifest, request)
        staging.writeIncomingChunk(request.jobId, 0L, payload.copyOfRange(0, 8))

        assertThrows(IllegalArgumentException::class.java) {
            staging.prepareIncoming(
                manifest.copy(mode = CloneRecoveryMode.FULL_HISTORY),
                request.copy(mode = CloneRecoveryMode.FULL_HISTORY),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            staging.writeIncomingChunk(request.jobId, 0L, "different".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            staging.writeIncomingChunk(request.jobId, 9L, byteArrayOf(1))
        }
    }

    @Test
    fun requestBindingRejectsAChangedDirectionOrCategory() = withStaging { staging ->
        val payload = byteArrayOf(1, 2, 3)
        val request = request()

        assertThrows(IllegalArgumentException::class.java) {
            staging.prepareIncoming(
                manifest(request, payload).copy(
                    direction = CloneRecoveryDirection.SEND_TO_RECEIVER,
                ),
                request,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            staging.prepareIncoming(
                manifest(request, payload).copy(
                    categories = CloneRecoveryCategories.GLUCOSE,
                ),
                request,
            )
        }
    }

    @Test
    fun cleanupRemovesOnlyExpiredValidJobDirectories() = withStaging { staging ->
        val old = request()
        val current = old.copy(jobId = "fedcba9876543210fedcba9876543210")
        staging.stageRequest(old)
        staging.stageRequest(current)
        val oldDirectory = staging.relativeManifestPath(old.jobId)
            .substringBeforeLast('/')
            .let { File(testRoot, it) }
        val unrelated = File(testRoot, "mirror/backfill/jobs/not-a-job")
        assertTrue(unrelated.mkdirs())
        assertTrue(oldDirectory.setLastModified(1_000L))

        assertEquals(1, staging.cleanJobsOlderThan(2_000L))
        assertFalse(oldDirectory.exists())
        assertTrue(unrelated.exists())
        assertEquals(current, staging.readRequest(current.jobId))
    }

    @Test
    fun clearingAnAliasedJobCannotDeleteAnotherJob() = withStaging { staging ->
        val target = request().copy(jobId = "fedcba9876543210fedcba9876543210")
        staging.stageRequest(target)
        val jobs = File(testRoot, "mirror/backfill/jobs")
        val alias = File(jobs, request().jobId)
        try {
            Files.createSymbolicLink(alias.toPath(), File(jobs, target.jobId).toPath())
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }

        assertThrows(IllegalArgumentException::class.java) {
            staging.clearJob(request().jobId)
        }
        assertEquals(target, staging.readRequest(target.jobId))
        Files.deleteIfExists(alias.toPath())
    }

    private lateinit var testRoot: File

    private fun withStaging(block: (CloneRecoveryStaging) -> Unit) {
        testRoot = Files.createTempDirectory("clone-recovery-staging-test").toFile()
        try {
            block(CloneRecoveryStaging(testRoot))
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun request() = CloneRecoveryRequest(
        protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
        jobId = "0123456789abcdef0123456789abcdef",
        direction = CloneRecoveryDirection.RECOVER_FROM_RECEIVER,
        mode = CloneRecoveryMode.ONLY_MISSING,
        categories = CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
    )

    private fun manifest(request: CloneRecoveryRequest, payload: ByteArray) = CloneRecoveryManifest(
        protocolVersion = request.protocolVersion,
        jobId = request.jobId,
        direction = request.direction,
        mode = request.mode,
        categories = request.categories,
        compressedBytes = payload.size.toLong(),
        uncompressedBytes = payload.size.toLong(),
        recordCounts = mapOf("glucose_reading" to 1L),
        sha256 = CloneHistoryRecoveryProtocol.sha256(payload.inputStream()),
    )
}
