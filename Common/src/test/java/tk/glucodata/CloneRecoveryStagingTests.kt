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
        assertEquals(CloneRecoveryPhase.RECEIVING, staging.readStatus(request.jobId).phase)
        assertEquals(0L, staging.readStatus(request.jobId).acceptedBytes)
        assertEquals(12L, staging.writeIncomingChunk(request.jobId, 0L, payload.copyOfRange(0, 12)))
        assertEquals(12L, staging.readStatus(request.jobId).acceptedBytes)
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
    fun completedJobRetainsOnlyTerminalStatusAndRejectsCommitReplay() = withStaging { staging ->
        val payload = "completed recovery".toByteArray()
        val request = request().copy(direction = CloneRecoveryDirection.SEND_TO_RECEIVER)
        val manifest = manifest(request, payload)
        val commit = CloneRecoveryCommit(
            protocolVersion = manifest.protocolVersion,
            jobId = manifest.jobId,
            sha256 = manifest.sha256,
        )
        staging.prepareIncoming(manifest, request)
        staging.writeIncomingChunk(request.jobId, 0L, payload)

        val commitStart = staging.beginCommit(commit)
        assertTrue(commitStart.shouldImport)
        assertEquals(CloneRecoveryPhase.VERIFYING, commitStart.status.phase)
        assertTrue(staging.beginCommit(commit).shouldImport)
        val importStart = staging.beginImport(request.jobId)
        assertEquals(CloneRecoveryPhase.IMPORTING, importStart.status.phase)
        assertEquals(manifest, importStart.manifest)
        assertArrayEquals(payload, importStart.packageFile!!.readBytes())
        val completed = staging.completeImport(request.jobId)

        assertEquals(CloneRecoveryPhase.COMPLETED, completed.phase)
        assertEquals(payload.size.toLong(), completed.acceptedBytes)
        val directory = File(testRoot, "mirror/backfill/jobs/${request.jobId}")
        assertEquals(listOf("status.json"), directory.listFiles()!!.map(File::getName).sorted())
        val restarted = CloneRecoveryStaging(testRoot)
        assertEquals(completed, restarted.readStatus(request.jobId))
        assertFalse(restarted.beginCommit(commit).shouldImport)
        assertEquals(payload.size.toLong(), restarted.prepareIncoming(manifest, request))
    }

    @Test
    fun importingJobResumesFromDurableCommitAfterProcessRestart() = withStaging { staging ->
        val payload = "restart during import".toByteArray()
        val request = request().copy(direction = CloneRecoveryDirection.SEND_TO_RECEIVER)
        val manifest = manifest(request, payload)
        val commit = CloneRecoveryCommit(
            protocolVersion = manifest.protocolVersion,
            jobId = manifest.jobId,
            sha256 = manifest.sha256,
        )
        staging.prepareIncoming(manifest, request)
        staging.writeIncomingChunk(request.jobId, 0L, payload)
        staging.beginCommit(commit)
        staging.beginImport(request.jobId)

        val restarted = CloneRecoveryStaging(testRoot)
        val resumedCommit = restarted.beginCommit(commit)
        assertTrue(resumedCommit.shouldImport)
        assertEquals(CloneRecoveryPhase.IMPORTING, resumedCommit.status.phase)
        val resumedImport = restarted.beginImport(request.jobId)
        assertEquals(CloneRecoveryPhase.IMPORTING, resumedImport.status.phase)
        assertEquals(manifest, resumedImport.manifest)
        assertArrayEquals(payload, resumedImport.packageFile!!.readBytes())

        val completed = restarted.completeImport(request.jobId)
        assertEquals(CloneRecoveryPhase.COMPLETED, completed.phase)
        assertFalse(restarted.beginCommit(commit).shouldImport)
    }

    @Test
    fun cancellationIsDurableAndAllowedOnlyBeforeImport() = withStaging { staging ->
        val payload = "cancel recovery".toByteArray()
        val request = request().copy(direction = CloneRecoveryDirection.SEND_TO_RECEIVER)
        val manifest = manifest(request, payload)
        val cancel = CloneRecoveryCancel(
            protocolVersion = manifest.protocolVersion,
            jobId = manifest.jobId,
            sha256 = manifest.sha256,
        )
        staging.prepareIncoming(manifest, request)
        staging.writeIncomingChunk(request.jobId, 0L, payload.copyOfRange(0, 5))

        val cancelled = staging.cancel(cancel)

        assertEquals(CloneRecoveryPhase.CANCELLED, cancelled.phase)
        assertEquals(5L, cancelled.acceptedBytes)
        assertEquals(cancelled, staging.cancel(cancel))
        val directory = File(testRoot, "mirror/backfill/jobs/${request.jobId}")
        assertEquals(listOf("status.json"), directory.listFiles()!!.map(File::getName).sorted())

        val secondRequest = request().copy(
            jobId = "fedcba9876543210fedcba9876543210",
            direction = CloneRecoveryDirection.SEND_TO_RECEIVER,
        )
        val secondManifest = manifest(secondRequest, payload)
        staging.prepareIncoming(secondManifest, secondRequest)
        staging.writeIncomingChunk(secondRequest.jobId, 0L, payload)
        staging.beginCommit(
            CloneRecoveryCommit(
                protocolVersion = secondManifest.protocolVersion,
                jobId = secondManifest.jobId,
                sha256 = secondManifest.sha256,
            )
        )
        staging.beginImport(secondRequest.jobId)
        assertThrows(IllegalArgumentException::class.java) {
            staging.cancel(
                CloneRecoveryCancel(
                    protocolVersion = secondManifest.protocolVersion,
                    jobId = secondManifest.jobId,
                    sha256 = secondManifest.sha256,
                )
            )
        }
    }

    @Test
    fun terminalStatusKeepsExactManifestBindingAndBoundedFailure() = withStaging { staging ->
        val payload = "failed recovery".toByteArray()
        val request = request().copy(direction = CloneRecoveryDirection.SEND_TO_RECEIVER)
        val manifest = manifest(request, payload)
        staging.prepareIncoming(manifest, request)

        val failed = staging.fail(request.jobId, "bad\n" + "x".repeat(1_000))

        assertEquals(CloneRecoveryPhase.FAILED, failed.phase)
        assertTrue(failed.error!!.length <= CloneHistoryRecoveryProtocol.MAXIMUM_STATUS_ERROR_CHARS)
        assertFalse(failed.error!!.contains('\n'))
        assertThrows(IllegalArgumentException::class.java) {
            staging.prepareIncoming(
                manifest.copy(mode = CloneRecoveryMode.FULL_HISTORY),
                request.copy(mode = CloneRecoveryMode.FULL_HISTORY),
            )
        }
    }

    @Test
    fun outgoingLabelBindingAndPhaseSurviveProcessRestart() = withStaging { staging ->
        val request = request().copy(direction = CloneRecoveryDirection.SEND_TO_RECEIVER)
        staging.stageRequest(request)
        val preparing = CloneOutgoingState(
            iceLabel = "peer/ice:stable",
            connectionGeneration = 11L,
            jobId = request.jobId,
            phase = CloneOutgoingPhase.PREPARING,
        )
        staging.writeOutgoingState(preparing)

        val restarted = CloneRecoveryStaging(testRoot)
        assertEquals(preparing, restarted.readOutgoingState(preparing.iceLabel))
        assertEquals(listOf(preparing), restarted.listOutgoingStates())
        val probing = preparing.copy(
            phase = CloneOutgoingPhase.PROBING,
            reconcileAfterProbe = true,
        )
        restarted.writeOutgoingState(probing)
        assertEquals(probing, CloneRecoveryStaging(testRoot).readOutgoingState(preparing.iceLabel))
        val storedNames = File(testRoot, CloneOutgoingRecoveryProtocol.OUTGOING_PATH_PREFIX)
            .listFiles()!!.map(File::getName)
        assertEquals(1, storedNames.size)
        assertFalse(storedNames.single().contains("peer"))

        assertThrows(IllegalArgumentException::class.java) {
            restarted.writeOutgoingState(
                probing.copy(jobId = "fedcba9876543210fedcba9876543210")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            restarted.writeOutgoingState(probing.copy(iceLabel = "another-peer"))
        }
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
