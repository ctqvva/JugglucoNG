package tk.glucodata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Sender-initiated download. The caller serializes state changes and owns the import coroutine. */
internal class ClonePullRecoveryWorkflow(private val staging: CloneRecoveryStaging) {
    private val controls = mutableMapOf<String, ByteArrayOutputStream>()

    fun request(state: CloneOutgoingState): CloneRecoveryRequest =
        staging.readRequest(requireNotNull(state.jobId)).also {
            require(it.direction == CloneRecoveryDirection.RECOVER_FROM_RECEIVER &&
                state.direction == it.direction) { "Invalid Clone recovery pull binding" }
        }

    fun nextAction(state: CloneOutgoingState): CloneOutgoingAction? {
        if (state.phase.isTerminal || state.phase == CloneOutgoingPhase.IMPORTING_LOCAL) return null
        val request = request(state)
        return when (state.phase) {
            CloneOutgoingPhase.PROBING -> readControl(state,
                CloneOutgoingActionKind.PROBE_CAPABILITIES, CloneHistoryRecoveryProtocol.CAPABILITY_PATH)
            CloneOutgoingPhase.REQUESTING_PACKAGE -> CloneOutgoingAction(
                CloneOutgoingActionKind.PUT_REQUEST, 0L,
                CloneHistoryRecoveryProtocol.jobRequestPath(request.jobId),
                CloneHistoryRecoveryProtocol.encodeRequest(request).toByteArray(StandardCharsets.UTF_8))
            CloneOutgoingPhase.GETTING_MANIFEST -> readControl(state,
                CloneOutgoingActionKind.GET_MANIFEST, CloneHistoryRecoveryProtocol.jobManifestPath(request.jobId))
            CloneOutgoingPhase.GETTING_PACKAGE -> {
                val manifest = staging.readManifest(request.jobId)
                CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
                val count = minOf(state.maximumChunkBytes.toLong(),
                    CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES.toLong(),
                    manifest.compressedBytes - state.nextOffset).toInt()
                require(count > 0) { "Clone recovery download is already complete" }
                CloneOutgoingAction(CloneOutgoingActionKind.GET_PACKAGE_CHUNK, state.nextOffset,
                    CloneHistoryRecoveryProtocol.jobPackagePath(request.jobId),
                    CloneOutgoingRecoveryProtocol.readRequestBytes(count))
            }
            else -> null
        }
    }

    fun accept(state: CloneOutgoingState, result: CloneOutgoingResult, now: Long): CloneOutgoingState {
        require(result.connectionGeneration == state.connectionGeneration) { "Stale Clone recovery result" }
        require(!state.phase.isTerminal && state.phase != CloneOutgoingPhase.IMPORTING_LOCAL) {
            "Clone recovery pull has no pending network action"
        }
        request(state)
        if (result.outcome != CloneOutgoingResultOutcome.OK) {
            controls.remove(state.iceLabel)
            if (result.outcome == CloneOutgoingResultOutcome.NOT_FOUND && state.phase == CloneOutgoingPhase.PROBING) {
                return failed(state, "Clone peer does not support history recovery")
            }
            val retry = state.retryCount + 1
            if (retry >= 30) return failed(state, "Clone history download could not continue")
            // A repeated immutable request also restarts export after a peer process restart.
            return state.copy(phase = if (result.outcome == CloneOutgoingResultOutcome.NOT_FOUND &&
                state.phase == CloneOutgoingPhase.GETTING_MANIFEST) CloneOutgoingPhase.REQUESTING_PACKAGE
                else CloneOutgoingPhase.PROBING,
                capabilityGeneration = null, retryCount = retry,
                nextAttemptAtMillis = now + minOf(10_000L, 500L * (1L shl minOf(5, retry - 1))))
        }
        return when (state.phase) {
            CloneOutgoingPhase.PROBING, CloneOutgoingPhase.GETTING_MANIFEST -> acceptControl(state, result.payload)
            CloneOutgoingPhase.REQUESTING_PACKAGE -> {
                require(result.payload.isEmpty()) { "Unexpected Clone export request response" }
                state.copy(phase = CloneOutgoingPhase.GETTING_MANIFEST, remoteJobEstablished = true,
                    nextAttemptAtMillis = now + 250L, error = null)
            }
            CloneOutgoingPhase.GETTING_PACKAGE -> {
                val action = requireNotNull(nextAction(state))
                require(result.payload.size == CloneOutgoingRecoveryProtocol.requestedReadBytes(action.payload)) {
                    "Truncated Clone history download chunk"
                }
                val offset = staging.writeIncomingChunk(requireNotNull(state.jobId), state.nextOffset, result.payload)
                val manifest = staging.readManifest(state.jobId)
                state.copy(phase = if (offset == manifest.compressedBytes) CloneOutgoingPhase.IMPORTING_LOCAL
                    else CloneOutgoingPhase.GETTING_PACKAGE,
                    nextOffset = offset, retryCount = 0, nextAttemptAtMillis = 0L, error = null)
            }
            else -> error("Unexpected Clone pull response")
        }
    }

    fun resume(state: CloneOutgoingState, generation: Long): CloneOutgoingState {
        controls.remove(state.iceLabel)
        if (state.phase.isTerminal || state.phase == CloneOutgoingPhase.IMPORTING_LOCAL) return state
        return state.copy(connectionGeneration = generation, phase = CloneOutgoingPhase.PROBING,
            capabilityGeneration = null, retryCount = 0, nextAttemptAtMillis = 0L, error = null)
    }

    fun cancel(state: CloneOutgoingState): CloneOutgoingState {
        if (state.phase.isTerminal || state.phase == CloneOutgoingPhase.IMPORTING_LOCAL) return state
        controls.remove(state.iceLabel)
        staging.existingManifest(requireNotNull(state.jobId))?.let { manifest ->
            staging.cancel(CloneRecoveryCancel(manifest.protocolVersion, manifest.jobId, manifest.sha256))
        }
        return state.copy(phase = CloneOutgoingPhase.CANCELLED, cancelRequested = true,
            nextAttemptAtMillis = 0L, error = null)
    }

    /** The production importer supplies the Room transaction; completion follows its return. */
    suspend fun importLocal(
        state: CloneOutgoingState,
        importer: suspend (File, CloneRecoveryManifest) -> Unit,
    ): CloneOutgoingState {
        require(state.phase == CloneOutgoingPhase.IMPORTING_LOCAL)
        staging.existingStatus(requireNotNull(state.jobId))?.takeIf { it.phase == CloneRecoveryPhase.COMPLETED }?.let { completed ->
            require(completed.direction == state.direction)
            return state.copy(phase = CloneOutgoingPhase.COMPLETED, localCommitted = true,
                nextOffset = completed.totalBytes, retryCount = 0, nextAttemptAtMillis = 0L, error = null)
        }
        val request = request(state)
        val manifest = staging.readManifest(request.jobId)
        CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
        val committed = staging.existingStatus(request.jobId)?.phase == CloneRecoveryPhase.COMPLETED
        if (!committed) {
            staging.beginCommit(CloneRecoveryCommit(manifest.protocolVersion, manifest.jobId, manifest.sha256))
            val start = staging.beginImport(manifest.jobId)
            importer(requireNotNull(start.packageFile), manifest)
            staging.completeImport(manifest.jobId)
        }
        return state.copy(phase = CloneOutgoingPhase.COMPLETED, localCommitted = true,
            nextOffset = manifest.compressedBytes, retryCount = 0, nextAttemptAtMillis = 0L, error = null)
    }

    private fun readControl(state: CloneOutgoingState, kind: CloneOutgoingActionKind, path: String): CloneOutgoingAction {
        val offset = controls[state.iceLabel]?.size() ?: 0
        val count = minOf(CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES,
            CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES - offset)
        require(count > 0) { "Clone recovery control response is too large" }
        return CloneOutgoingAction(kind, offset.toLong(), path, CloneOutgoingRecoveryProtocol.readRequestBytes(count))
    }

    private fun acceptControl(state: CloneOutgoingState, payload: ByteArray): CloneOutgoingState {
        val action = requireNotNull(nextAction(state))
        val requested = CloneOutgoingRecoveryProtocol.requestedReadBytes(action.payload)
        require(payload.size <= requested) { "Oversized Clone recovery response" }
        val buffer = controls.getOrPut(state.iceLabel) { ByteArrayOutputStream() }
        buffer.write(payload)
        if (payload.size == requested) {
            require(buffer.size() < CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES)
            return state.copy(nextAttemptAtMillis = 0L)
        }
        controls.remove(state.iceLabel)
        val raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.toByteArray())).toString()
        val request = request(state)
        if (state.phase == CloneOutgoingPhase.PROBING) {
            val capabilities = CloneHistoryRecoveryProtocol.decodeCapabilities(raw)
            require(capabilities.supportsPull) { "Clone peer does not support recovery from receiver" }
            require(CloneHistoryRecoveryProtocol.negotiatedProtocolVersion(capabilities) != null)
            require(capabilities.categories and request.categories == request.categories)
            return state.copy(phase = CloneOutgoingPhase.REQUESTING_PACKAGE,
                maximumChunkBytes = minOf(CloneHistoryRecoveryProtocol.negotiatedChunkBytes(capabilities),
                    CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES),
                negotiatedProtocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
                remoteCategories = capabilities.categories, remoteSupportsPull = true,
                remoteMaximumCompressedBytes = capabilities.maximumCompressedBytes,
                capabilityGeneration = state.connectionGeneration, nextAttemptAtMillis = 0L, error = null)
        }
        val manifest = CloneHistoryRecoveryProtocol.decodeManifest(raw)
        CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
        require(manifest.compressedBytes <= requireNotNull(state.remoteMaximumCompressedBytes))
        val offset = staging.prepareIncoming(manifest, request)
        return state.copy(phase = if (offset == manifest.compressedBytes) CloneOutgoingPhase.IMPORTING_LOCAL
            else CloneOutgoingPhase.GETTING_PACKAGE,
            nextOffset = offset, retryCount = 0, nextAttemptAtMillis = 0L, error = null)
    }

    fun failed(state: CloneOutgoingState, message: String?): CloneOutgoingState =
        state.copy(phase = CloneOutgoingPhase.FAILED, nextAttemptAtMillis = 0L,
            error = CloneOutgoingRecoveryProtocol.boundedError(message))
}
