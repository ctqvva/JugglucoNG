package tk.glucodata

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClonePullRecoveryWorkflowTests {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("clone-pull-test").toFile()
        val staging = CloneRecoveryStaging(File(root, "destination"))
        var workflow = ClonePullRecoveryWorkflow(staging)
        val request = CloneRecoveryRequest(CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
            CloneHistoryRecoveryProtocol.newJobId(), CloneRecoveryDirection.RECOVER_FROM_RECEIVER,
            CloneRecoveryMode.ONLY_MISSING, CloneRecoveryCategories.GLUCOSE)
        val source = File(root, "source.gz")
        val manifest = runBlocking {
            val stats = CloneRecoveryPackageIO.write(source, setOf("glucose")) { sink ->
                repeat(200) { sink.write("glucose", JSONObject().put("timestamp", it + 1).put("value", it * 17)) }
            }
            CloneRecoveryManifest(request.protocolVersion, request.jobId, request.direction, request.mode,
                request.categories, stats.compressedBytes, stats.uncompressedBytes, stats.recordCounts, stats.sha256)
        }
        var state = CloneOutgoingState(iceLabel = "paired-phone", connectionGeneration = 1, jobId = request.jobId,
            phase = CloneOutgoingPhase.PROBING, direction = request.direction)
        init { staging.stageRequest(request); staging.writeOutgoingState(state) }
        fun accept(payload: ByteArray = byteArrayOf(), outcome: CloneOutgoingResultOutcome = CloneOutgoingResultOutcome.OK) {
            state = workflow.accept(state, CloneOutgoingResult(outcome, state.connectionGeneration, payload), 1000)
            staging.writeOutgoingState(state)
        }
        fun step() {
            val action = requireNotNull(workflow.nextAction(state))
            assertEquals(action, CloneOutgoingRecoveryProtocol.decodeAction(CloneOutgoingRecoveryProtocol.encodeAction(action)))
            when (action.kind) {
                CloneOutgoingActionKind.PROBE_CAPABILITIES -> accept(CloneHistoryRecoveryProtocol.encodeCapabilities(
                    CloneHistoryRecoveryProtocol.localCapabilities(CloneRecoveryCategories.GLUCOSE).copy(maximumChunkBytes = 37)
                ).toByteArray())
                CloneOutgoingActionKind.PUT_REQUEST -> {
                    assertEquals(request, CloneHistoryRecoveryProtocol.decodeRequest(action.payload.toString(Charsets.UTF_8)))
                    accept()
                }
                CloneOutgoingActionKind.GET_MANIFEST -> accept(CloneHistoryRecoveryProtocol.encodeManifest(manifest).toByteArray())
                CloneOutgoingActionKind.GET_PACKAGE_CHUNK -> {
                    val bytes = source.readBytes()
                    val offset = action.offset.toInt()
                    accept(bytes.copyOfRange(offset, minOf(bytes.size, offset + CloneOutgoingRecoveryProtocol.requestedReadBytes(action.payload))))
                }
                else -> error("Unexpected pull action ${action.kind}")
            }
        }
        fun download() { repeat(1000) { if (state.phase == CloneOutgoingPhase.IMPORTING_LOCAL) return; step() }; error("Stalled pull") }
        override fun close() { root.deleteRecursively() }
    }

    @Test fun downloadsBoundedChunksBeforeLocalCompletion() = runBlocking {
        Fixture().use { f ->
            f.download()
            assertFalse(f.state.localCommitted)
            assertFalse(f.state.remoteTerminal)
            assertArrayEquals(f.source.readBytes(), f.staging.verifiedPackageFile(f.request.jobId).readBytes())
            var imports = 0
            val done = f.workflow.importLocal(f.state) { file, manifest ->
                CloneRecoveryPackageIO.validate(file, manifest)
                imports++
            }
            assertEquals(1, imports)
            assertTrue(done.localCommitted)
            assertEquals(CloneOutgoingPhase.COMPLETED, done.phase)
            assertEquals(done, CloneOutgoingRecoveryProtocol.decodeState(CloneOutgoingRecoveryProtocol.encodeState(done)))
        }
    }

    @Test fun resumesAfterRestartAndGenerationChangeWithoutLosingAcceptedBytes() {
        Fixture().use { f ->
            repeat(5) { f.step() }
            val offset = f.state.nextOffset
            assertTrue(offset > 0)
            f.workflow = ClonePullRecoveryWorkflow(CloneRecoveryStaging(File(f.root, "destination")))
            f.state = f.workflow.resume(f.staging.readOutgoingState(f.state.iceLabel), 2)
            repeat(3) { f.step() }
            assertEquals(offset, f.state.nextOffset)
            assertEquals(offset, f.workflow.nextAction(f.state)!!.offset)
            f.download()
            assertEquals(f.manifest.compressedBytes, f.state.nextOffset)
        }
    }

    @Test fun onlyMissingIsTheDefaultSelection() {
        val selection = tk.glucodata.ui.CloneHistoryRecoverySelection()
        assertEquals(CloneRecoveryMode.ONLY_MISSING, selection.mode)
        assertFalse(selection.includeJournal)
    }

    @Test fun rejectsPeersWithoutPullCapability() {
        Fixture().use { f ->
            val legacy = JSONObject(CloneHistoryRecoveryProtocol.encodeCapabilities(
                CloneHistoryRecoveryProtocol.localCapabilities(CloneRecoveryCategories.GLUCOSE))).apply { remove("supportsPull") }
            assertFalse(CloneHistoryRecoveryProtocol.decodeCapabilities(legacy.toString()).supportsPull)
            expectFailure { f.accept(legacy.toString().toByteArray()) }
            assertEquals(CloneOutgoingPhase.PROBING, f.state.phase)
        }
    }

    @Test fun rejectsAChangedDirectionOrModeBeforeStaging() {
        Fixture().use { f ->
            repeat(2) { f.step() }
            for (changed in listOf(f.manifest.copy(mode = CloneRecoveryMode.FULL_HISTORY),
                f.manifest.copy(direction = CloneRecoveryDirection.SEND_TO_RECEIVER))) {
                expectFailure { f.accept(CloneHistoryRecoveryProtocol.encodeManifest(changed).toByteArray()) }
                assertNull(f.staging.existingManifest(f.request.jobId))
            }
        }
    }

    @Test fun rejectsShortDownloadChunksWithoutAdvancingProgress() {
        Fixture().use { f ->
            repeat(3) { f.step() }
            expectFailure { f.accept(byteArrayOf(1)) }
            assertEquals(0L, f.staging.readStatus(f.request.jobId).acceptedBytes)
        }
    }

    @Test fun corruptionNeverReachesImporter() = runBlocking {
        Fixture().use { f ->
            f.download()
            val packageFile = f.staging.verifiedPackageFile(f.request.jobId)
            val bytes = packageFile.readBytes().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
            packageFile.writeBytes(bytes)
            var imports = 0
            try { f.workflow.importLocal(f.state) { _, _ -> imports++ }; fail("Corrupt package imported") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, imports)
        }
    }

    @Test fun failedImportNeverReportsCompletion() = runBlocking {
        Fixture().use { f ->
            f.download()
            try { f.workflow.importLocal(f.state) { _, _ -> error("injected failure") }; fail("Import succeeded") }
            catch (error: IllegalStateException) { assertEquals("injected failure", error.message) }
            assertFalse(f.state.localCommitted)
            assertNotEquals(CloneRecoveryPhase.COMPLETED, f.staging.readStatus(f.request.jobId).phase)
        }
    }

    @Test fun restartAfterCompletionDoesNotImportAgain() = runBlocking {
        Fixture().use { f ->
            f.download()
            f.workflow.importLocal(f.state) { _, _ -> }
            val restarted = ClonePullRecoveryWorkflow(CloneRecoveryStaging(File(f.root, "destination")))
            val result = restarted.importLocal(f.state) { _, _ -> fail("Already committed job imported again") }
            assertTrue(result.localCommitted)
        }
    }

    @Test fun cancellationBeforeImportPreventsFurtherNetworkActions() {
        Fixture().use { f ->
            repeat(4) { f.step() }
            f.state = f.workflow.cancel(f.state)
            assertEquals(CloneOutgoingPhase.CANCELLED, f.state.phase)
            assertNull(f.workflow.nextAction(f.state))
            assertEquals(CloneRecoveryPhase.CANCELLED, f.staging.readStatus(f.request.jobId).phase)
        }
    }

    @Test fun cancellationCannotInterruptLocalCommit() {
        Fixture().use { f -> f.download(); assertEquals(f.state, f.workflow.cancel(f.state)) }
    }

    @Test fun missingExportIsRetriedAndRequestedAgain() {
        Fixture().use { f ->
            repeat(2) { f.step() }
            f.accept(outcome = CloneOutgoingResultOutcome.NOT_FOUND)
            assertEquals(CloneOutgoingPhase.REQUESTING_PACKAGE, f.state.phase)
            assertTrue(f.state.nextAttemptAtMillis > 1000)
            assertEquals(CloneOutgoingActionKind.PUT_REQUEST, f.workflow.nextAction(f.state)!!.kind)
        }
    }

    @Test fun staleGenerationIsRejected() {
        Fixture().use { f -> expectFailure { f.workflow.accept(f.state,
            CloneOutgoingResult(CloneOutgoingResultOutcome.OK, 0), 1000) } }
    }

    @Test fun pullCompletionRequiresLocalCommitReceipt() {
        Fixture().use { f -> expectFailure { CloneOutgoingRecoveryProtocol.validateState(
            f.state.copy(phase = CloneOutgoingPhase.COMPLETED, remoteJobEstablished = true, remoteTerminal = true)) } }
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
}
