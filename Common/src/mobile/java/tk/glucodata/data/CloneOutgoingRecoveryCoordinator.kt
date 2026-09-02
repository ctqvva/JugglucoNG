package tk.glucodata.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import tk.glucodata.Applic
import tk.glucodata.CloneHistoryRecoveryProtocol
import tk.glucodata.CloneOutgoingAction
import tk.glucodata.CloneOutgoingActionKind
import tk.glucodata.CloneOutgoingPhase
import tk.glucodata.CloneOutgoingRecoveryProtocol
import tk.glucodata.CloneOutgoingRecoveryTransitions
import tk.glucodata.CloneOutgoingResultOutcome
import tk.glucodata.CloneOutgoingState
import tk.glucodata.CloneRecoveryCancel
import tk.glucodata.CloneRecoveryCategories
import tk.glucodata.CloneRecoveryCommit
import tk.glucodata.CloneRecoveryDirection
import tk.glucodata.CloneRecoveryManifest
import tk.glucodata.CloneRecoveryMode
import tk.glucodata.CloneRecoveryPhase
import tk.glucodata.CloneRecoveryRequest
import tk.glucodata.CloneRecoveryStaging
import tk.glucodata.Natives

/** Durable action-driven sender for one active SEND_TO_RECEIVER job per ICE label. */
internal class CloneOutgoingRecoveryCoordinator private constructor(context: Context) {
    private val staging = CloneRecoveryStaging(context.applicationContext.filesDir)
    private val history = CloneHistoryRecoveryStore(
        HistoryDatabase.getInstance(context.applicationContext)
    )
    private val mutex = Mutex()
    private val verifiedJobs = mutableSetOf<String>()
    private val readBuffers = mutableMapOf<String, ByteArrayOutputStream>()

    suspend fun probe(iceLabel: String, generation: Long): CloneOutgoingState = mutex.withLock {
        CloneOutgoingRecoveryProtocol.validateIceLabel(iceLabel)
        require(generation >= 0L) { "Invalid Clone recovery connection generation" }
        val current = staging.existingOutgoingState(iceLabel)
        if (current?.jobId != null) return@withLock resumeState(current, generation)
        if (current?.phase == CloneOutgoingPhase.PROBE_READY &&
            current.capabilityGeneration == generation
        ) {
            return@withLock current
        }
        writeState(
            CloneOutgoingState(
                iceLabel = iceLabel,
                connectionGeneration = generation,
                jobId = null,
                phase = CloneOutgoingPhase.PROBING,
            )
        )
    }

    suspend fun start(
        iceLabel: String,
        generation: Long,
        mode: CloneRecoveryMode,
        includeJournal: Boolean,
    ): CloneOutgoingState = mutex.withLock {
        CloneOutgoingRecoveryProtocol.validateIceLabel(iceLabel)
        require(generation >= 0L) { "Invalid Clone recovery connection generation" }
        val categories = CloneRecoveryCategories.selected(
            includeJournal = includeJournal,
            includeHypoClassifications = false,
        )
        val previous = staging.existingOutgoingState(iceLabel)
        previous?.let { existing ->
            if (existing.jobId != null && !existing.phase.isTerminal) {
                val request = requestFor(existing)
                require(request.mode == mode && request.categories == categories) {
                    "A different Clone recovery job is already active for this ICE label"
                }
                return@withLock resumeState(existing, generation)
            }
            require(!existing.phase.isTerminal) {
                "The previous outgoing Clone recovery job must be cleared first"
            }
        }
        val request = CloneRecoveryRequest(
            protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
            jobId = CloneHistoryRecoveryProtocol.newJobId(),
            direction = CloneRecoveryDirection.SEND_TO_RECEIVER,
            mode = mode,
            categories = categories,
        )
        staging.stageRequest(request)
        writeState(
            CloneOutgoingState(
                iceLabel = iceLabel,
                connectionGeneration = generation,
                jobId = request.jobId,
                phase = CloneOutgoingPhase.PREPARING,
                maximumChunkBytes = previous?.maximumChunkBytes
                    ?: CloneHistoryRecoveryProtocol.DEFAULT_CHUNK_BYTES,
                negotiatedProtocolVersion = previous?.negotiatedProtocolVersion,
                remoteCategories = previous?.remoteCategories,
                remoteMaximumCompressedBytes = previous?.remoteMaximumCompressedBytes,
                capabilityGeneration = previous?.capabilityGeneration,
            )
        )
    }

    /** Builds or verifies the immutable package on the facade's Java IO scope. */
    suspend fun ensurePrepared(iceLabel: String): CloneOutgoingState {
        val (initial, request) = mutex.withLock {
            val state = staging.readOutgoingState(iceLabel)
            val jobId = requireNotNull(state.jobId) { "Outgoing Clone recovery has no job" }
            val candidate = staging.readRequest(jobId).also(::requireOutgoing)
            state to candidate.takeUnless { jobId in verifiedJobs }
        }
        if (request == null) return initial
        val manifest = preparePackage(iceLabel, request)
        return mutex.withLock {
            val current = staging.readOutgoingState(iceLabel)
            require(current.jobId == manifest.jobId) {
                "Outgoing Clone recovery job changed during preparation"
            }
            verifiedJobs += manifest.jobId
            if (current.phase != CloneOutgoingPhase.PREPARING) return@withLock current
            val capabilityReady = current.capabilityGeneration == current.connectionGeneration &&
                current.negotiatedProtocolVersion == manifest.protocolVersion &&
                current.remoteCategories?.let { it and manifest.categories == manifest.categories } == true &&
                current.remoteMaximumCompressedBytes?.let {
                    manifest.compressedBytes <= it
                } == true
            writeState(
                current.copy(
                    phase = if (capabilityReady) {
                        CloneOutgoingPhase.PUTTING_MANIFEST
                    } else {
                        CloneOutgoingPhase.PROBING
                    },
                    reconcileAfterProbe = false,
                    retryCount = 0,
                    nextAttemptAtMillis = 0L,
                    error = null,
                )
            )
        }
    }

    suspend fun failPreparation(iceLabel: String, error: Throwable): CloneOutgoingState =
        mutex.withLock {
            val current = staging.readOutgoingState(iceLabel)
            if (current.phase.isTerminal) return@withLock current
            writeState(
                current.copy(
                    phase = CloneOutgoingPhase.FAILED,
                    nextAttemptAtMillis = 0L,
                    error = CloneOutgoingRecoveryProtocol.boundedError(error.message),
                )
            )
        }

    suspend fun nextAction(
        iceLabel: String,
        generation: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): ByteArray = mutex.withLock {
        val state = staging.readOutgoingState(iceLabel)
        if (state.connectionGeneration != generation || state.phase.isTerminal ||
            state.phase == CloneOutgoingPhase.PREPARING ||
            state.phase == CloneOutgoingPhase.PROBE_READY ||
            state.nextAttemptAtMillis > nowMillis ||
            state.jobId != null && state.jobId !in verifiedJobs
        ) {
            return@withLock ByteArray(0)
        }
        val action = when (state.phase) {
            CloneOutgoingPhase.PROBING -> readAction(
                state,
                CloneOutgoingActionKind.PROBE_CAPABILITIES,
                CloneHistoryRecoveryProtocol.CAPABILITY_PATH,
            )
            CloneOutgoingPhase.PUTTING_MANIFEST -> manifestFor(state).let { manifest ->
                CloneOutgoingAction(
                    CloneOutgoingActionKind.PUT_MANIFEST,
                    0L,
                    CloneHistoryRecoveryProtocol.jobManifestPath(manifest.jobId),
                    CloneHistoryRecoveryProtocol.encodeManifest(manifest)
                        .toByteArray(StandardCharsets.UTF_8),
                )
            }
            CloneOutgoingPhase.PUTTING_PACKAGE -> manifestFor(state).let { manifest ->
                require(state.nextOffset < manifest.compressedBytes) {
                    "Outgoing Clone recovery package offset is complete"
                }
                CloneOutgoingAction(
                    CloneOutgoingActionKind.PUT_PACKAGE_CHUNK,
                    state.nextOffset,
                    CloneHistoryRecoveryProtocol.jobPackagePath(manifest.jobId),
                    staging.readPackageChunk(
                        manifest.jobId,
                        state.nextOffset,
                        state.maximumChunkBytes,
                    ),
                )
            }
            CloneOutgoingPhase.PUTTING_COMMIT -> manifestFor(state).let { manifest ->
                CloneOutgoingAction(
                    CloneOutgoingActionKind.PUT_COMMIT,
                    0L,
                    CloneHistoryRecoveryProtocol.jobCommitPath(manifest.jobId),
                    commitJson(manifest).toByteArray(StandardCharsets.UTF_8),
                )
            }
            CloneOutgoingPhase.POLLING_STATUS,
            CloneOutgoingPhase.POLLING_CANCEL -> manifestFor(state).let { manifest ->
                readAction(
                    state,
                    CloneOutgoingActionKind.GET_STATUS,
                    CloneHistoryRecoveryProtocol.jobStatusPath(manifest.jobId),
                )
            }
            CloneOutgoingPhase.PUTTING_CANCEL -> manifestFor(state).let { manifest ->
                CloneOutgoingAction(
                    CloneOutgoingActionKind.PUT_CANCEL,
                    0L,
                    CloneHistoryRecoveryProtocol.jobCancelPath(manifest.jobId),
                    cancelJson(manifest).toByteArray(StandardCharsets.UTF_8),
                )
            }
            else -> return@withLock ByteArray(0)
        }
        CloneOutgoingRecoveryProtocol.encodeAction(action)
    }

    /** Returns -1 for idle, 0 for immediate work, or a positive Java-scheduled delay. */
    suspend fun reportResult(
        iceLabel: String,
        generation: Long,
        rawResult: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = mutex.withLock {
        val result = CloneOutgoingRecoveryProtocol.decodeResult(rawResult)
        require(result.connectionGeneration == generation) {
            "Stale Clone recovery result generation"
        }
        val state = staging.readOutgoingState(iceLabel)
        require(state.connectionGeneration == generation) {
            "Stale Clone recovery connection generation"
        }
        require(!state.phase.isTerminal && state.phase != CloneOutgoingPhase.PREPARING &&
            state.phase != CloneOutgoingPhase.PROBE_READY
        ) { "Outgoing Clone recovery has no pending action" }

        if (result.outcome == CloneOutgoingResultOutcome.TRANSPORT_ERROR ||
            result.outcome == CloneOutgoingResultOutcome.REJECTED
        ) {
            readBuffers.remove(iceLabel)
            return@withLock retryThroughProbe(
                state,
                nowMillis,
                if (result.outcome == CloneOutgoingResultOutcome.REJECTED) {
                    "Clone recovery action was rejected"
                } else {
                    "Clone recovery transport failed"
                },
            )
        }
        if (result.outcome == CloneOutgoingResultOutcome.NOT_FOUND) {
            readBuffers.remove(iceLabel)
            return@withLock when (state.phase) {
                CloneOutgoingPhase.POLLING_STATUS,
                CloneOutgoingPhase.POLLING_CANCEL -> {
                    writeState(
                        state.copy(
                            phase = CloneOutgoingPhase.PUTTING_MANIFEST,
                            nextOffset = 0L,
                            nextAttemptAtMillis = 0L,
                            error = null,
                        )
                    )
                    0L
                }
                CloneOutgoingPhase.PROBING -> {
                    writeState(
                        state.copy(
                            phase = CloneOutgoingPhase.FAILED,
                            nextAttemptAtMillis = 0L,
                            error = "Clone peer does not support history recovery",
                        )
                    )
                    -1L
                }
                else -> retryThroughProbe(state, nowMillis, "Clone recovery target was not found")
            }
        }
        when (state.phase) {
            CloneOutgoingPhase.PROBING,
            CloneOutgoingPhase.POLLING_STATUS,
            CloneOutgoingPhase.POLLING_CANCEL -> reportReadPage(state, result.payload, nowMillis)
            CloneOutgoingPhase.PUTTING_MANIFEST -> {
                require(result.payload.isEmpty()) { "Unexpected Clone recovery manifest response" }
                writeState(
                    state.copy(
                        phase = if (state.cancelRequested) {
                            CloneOutgoingPhase.PUTTING_CANCEL
                        } else {
                            CloneOutgoingPhase.POLLING_STATUS
                        },
                        remoteJobEstablished = true,
                        retryCount = 0,
                        nextAttemptAtMillis = 0L,
                        error = null,
                    )
                )
                0L
            }
            CloneOutgoingPhase.PUTTING_PACKAGE -> {
                require(result.payload.isEmpty()) { "Unexpected Clone recovery package response" }
                val manifest = manifestFor(state)
                val nextOffset = minOf(
                    manifest.compressedBytes,
                    state.nextOffset + state.maximumChunkBytes,
                )
                writeState(
                    state.copy(
                        phase = when {
                            state.cancelRequested -> CloneOutgoingPhase.PUTTING_CANCEL
                            nextOffset == manifest.compressedBytes -> CloneOutgoingPhase.PUTTING_COMMIT
                            else -> CloneOutgoingPhase.PUTTING_PACKAGE
                        },
                        nextOffset = nextOffset,
                        retryCount = 0,
                        nextAttemptAtMillis = 0L,
                        error = null,
                    )
                )
                0L
            }
            CloneOutgoingPhase.PUTTING_COMMIT -> {
                require(result.payload.isEmpty()) { "Unexpected Clone recovery commit response" }
                delayedPoll(state.copy(phase = CloneOutgoingPhase.POLLING_STATUS), nowMillis)
            }
            CloneOutgoingPhase.PUTTING_CANCEL -> {
                require(result.payload.isEmpty()) { "Unexpected Clone recovery cancel response" }
                delayedPoll(state.copy(phase = CloneOutgoingPhase.POLLING_CANCEL), nowMillis)
            }
            else -> -1L
        }
    }

    suspend fun status(iceLabel: String): CloneOutgoingState =
        mutex.withLock { staging.readOutgoingState(iceLabel) }

    suspend fun labels(): List<String> =
        mutex.withLock { staging.listOutgoingStates().map(CloneOutgoingState::iceLabel) }

    suspend fun cancel(iceLabel: String): CloneOutgoingState = mutex.withLock {
        val current = staging.readOutgoingState(iceLabel)
        readBuffers.remove(iceLabel)
        writeState(CloneOutgoingRecoveryTransitions.requestCancel(current))
    }

    suspend fun resume(iceLabel: String, generation: Long): CloneOutgoingState = mutex.withLock {
        resumeState(staging.readOutgoingState(iceLabel), generation)
    }

    suspend fun clear(iceLabel: String): Boolean = mutex.withLock {
        val state = staging.readOutgoingState(iceLabel)
        require(CloneOutgoingRecoveryTransitions.mayClear(state)) {
            "Outgoing Clone recovery can only be cleared after remote completion or cancellation"
        }
        readBuffers.remove(iceLabel)
        state.jobId?.let { jobId ->
            verifiedJobs.remove(jobId)
            require(staging.clearJob(jobId)) { "Could not clear outgoing Clone recovery payload" }
        }
        staging.clearOutgoingState(iceLabel)
    }

    private suspend fun preparePackage(
        iceLabel: String,
        request: CloneRecoveryRequest,
    ): CloneRecoveryManifest {
        requireOutgoing(request)
        staging.stageRequest(request)
        staging.existingManifest(request.jobId)?.let { existing ->
            CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(existing, request)
            staging.verifiedPackageFile(existing.jobId)
            return existing
        }
        val file = staging.packageFileForCreation(request)
        val manifest = history.createPackage(
            file = file,
            direction = request.direction,
            mode = request.mode,
            includeJournal = request.categories and CloneRecoveryCategories.JOURNAL != 0,
            includeHypoClassifications = false,
            jobId = request.jobId,
        )
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            val state = staging.readOutgoingState(iceLabel)
            require(state.jobId == request.jobId && state.phase == CloneOutgoingPhase.PREPARING) {
                "Outgoing Clone recovery preparation was cancelled"
            }
            staging.stageManifest(manifest, request)
        }
        staging.verifiedPackageFile(manifest.jobId)
        return manifest
    }

    private fun readAction(
        state: CloneOutgoingState,
        kind: CloneOutgoingActionKind,
        path: String,
    ): CloneOutgoingAction {
        val received = readBuffers[state.iceLabel]?.size() ?: 0
        require(received < CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES) {
            "Clone recovery control response is too large"
        }
        val requested = minOf(
            CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES,
            CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES - received,
        )
        return CloneOutgoingAction(
            kind,
            received.toLong(),
            path,
            CloneOutgoingRecoveryProtocol.readRequestBytes(requested),
        )
    }

    private fun reportReadPage(
        state: CloneOutgoingState,
        payload: ByteArray,
        nowMillis: Long,
    ): Long {
        val buffer = readBuffers.getOrPut(state.iceLabel) { ByteArrayOutputStream() }
        val requested = minOf(
            CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES,
            CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES - buffer.size(),
        )
        require(requested > 0 && payload.size <= requested) {
            "Clone recovery control response exceeds its requested page"
        }
        buffer.write(payload)
        if (payload.size == requested) {
            require(buffer.size() < CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES) {
                "Clone recovery control response is too large"
            }
            return 0L
        }
        readBuffers.remove(state.iceLabel)
        val raw = decodeUtf8(buffer.toByteArray())
        return try {
            when (state.phase) {
                CloneOutgoingPhase.PROBING -> acceptCapabilities(state, raw)
                CloneOutgoingPhase.POLLING_STATUS,
                CloneOutgoingPhase.POLLING_CANCEL -> acceptStatus(state, raw, nowMillis)
                else -> error("Clone recovery read completed in a write phase")
            }
        } catch (error: Throwable) {
            writeState(
                state.copy(
                    phase = CloneOutgoingPhase.FAILED,
                    nextAttemptAtMillis = 0L,
                    error = CloneOutgoingRecoveryProtocol.boundedError(error.message),
                )
            )
            -1L
        }
    }

    private fun acceptCapabilities(state: CloneOutgoingState, raw: String): Long {
        val capabilities = CloneHistoryRecoveryProtocol.decodeCapabilities(raw)
        val request = state.jobId?.let(staging::readRequest)
        val manifest = state.jobId?.let(staging::readManifest)
        val accepted = CloneOutgoingRecoveryTransitions.acceptCapabilities(
            state,
            capabilities,
            request?.categories,
            manifest?.compressedBytes,
        )
        writeState(accepted)
        return if (accepted.phase == CloneOutgoingPhase.PROBE_READY) -1L else 0L
    }

    private fun acceptStatus(state: CloneOutgoingState, raw: String, nowMillis: Long): Long {
        val manifest = manifestFor(state)
        val remote = CloneHistoryRecoveryProtocol.decodeStatus(raw)
        CloneHistoryRecoveryProtocol.requireStatusMatchesManifest(remote, manifest)
        val established = state.copy(remoteJobEstablished = true)
        return when (remote.phase) {
            CloneRecoveryPhase.COMPLETED -> terminalState(
                established,
                CloneOutgoingPhase.COMPLETED,
                remote.acceptedBytes,
            )
            CloneRecoveryPhase.CANCELLED -> terminalState(
                established.copy(cancelRequested = true),
                CloneOutgoingPhase.CANCELLED,
                remote.acceptedBytes,
            )
            CloneRecoveryPhase.FAILED -> {
                writeState(
                    established.copy(
                        phase = CloneOutgoingPhase.FAILED,
                        nextOffset = remote.acceptedBytes,
                        retryCount = 0,
                        nextAttemptAtMillis = 0L,
                        error = CloneOutgoingRecoveryProtocol.boundedError(remote.error),
                    )
                )
                -1L
            }
            CloneRecoveryPhase.PREPARING -> delayedPoll(
                established.copy(nextOffset = remote.acceptedBytes),
                nowMillis,
            )
            CloneRecoveryPhase.RECEIVING -> {
                val phase = when {
                    established.cancelRequested -> CloneOutgoingPhase.PUTTING_CANCEL
                    remote.acceptedBytes < remote.totalBytes -> CloneOutgoingPhase.PUTTING_PACKAGE
                    else -> CloneOutgoingPhase.PUTTING_COMMIT
                }
                writeState(
                    established.copy(
                        phase = phase,
                        nextOffset = remote.acceptedBytes,
                        retryCount = 0,
                        nextAttemptAtMillis = 0L,
                        error = null,
                    )
                )
                0L
            }
            CloneRecoveryPhase.VERIFYING,
            CloneRecoveryPhase.IMPORTING -> {
                writeState(
                    CloneOutgoingRecoveryTransitions.continueRemoteCommit(
                        established,
                        remote.phase,
                        remote.acceptedBytes,
                    )
                )
                0L
            }
        }
    }

    private fun terminalState(
        state: CloneOutgoingState,
        phase: CloneOutgoingPhase,
        acceptedBytes: Long,
    ): Long {
        writeState(
            state.copy(
                phase = phase,
                nextOffset = acceptedBytes,
                remoteTerminal = true,
                retryCount = 0,
                nextAttemptAtMillis = 0L,
                error = null,
            )
        )
        return -1L
    }

    private fun delayedPoll(state: CloneOutgoingState, nowMillis: Long): Long {
        val retry = minOf(30, state.retryCount + 1)
        val delayMillis = minOf(10_000L, 250L * (1L shl minOf(5, retry - 1)))
        writeState(
            state.copy(
                retryCount = retry,
                nextAttemptAtMillis = nowMillis + delayMillis,
                error = null,
            )
        )
        return delayMillis
    }

    private fun retryThroughProbe(
        state: CloneOutgoingState,
        nowMillis: Long,
        message: String,
    ): Long {
        val retry = state.retryCount + 1
        if (retry >= 30) {
            writeState(
                state.copy(
                    phase = CloneOutgoingPhase.FAILED,
                    capabilityGeneration = null,
                    retryCount = 30,
                    nextAttemptAtMillis = 0L,
                    error = CloneOutgoingRecoveryProtocol.boundedError(message),
                )
            )
            return -1L
        }
        val delayMillis = minOf(60_000L, 1_000L * (1L shl minOf(6, retry - 1)))
        writeState(
            state.copy(
                phase = CloneOutgoingPhase.PROBING,
                capabilityGeneration = null,
                reconcileAfterProbe = state.jobId != null,
                retryCount = retry,
                nextAttemptAtMillis = nowMillis + delayMillis,
                error = CloneOutgoingRecoveryProtocol.boundedError(message),
            )
        )
        return delayMillis
    }

    private fun resumeState(state: CloneOutgoingState, generation: Long): CloneOutgoingState {
        require(generation >= 0L) { "Invalid Clone recovery connection generation" }
        if (state.connectionGeneration == generation || state.phase.isTerminal) return state
        readBuffers.remove(state.iceLabel)
        val phase = when {
            state.jobId == null -> CloneOutgoingPhase.PROBING
            state.phase == CloneOutgoingPhase.PREPARING -> CloneOutgoingPhase.PREPARING
            else -> CloneOutgoingPhase.PROBING
        }
        return writeState(
            state.copy(
                connectionGeneration = generation,
                phase = phase,
                capabilityGeneration = null,
                reconcileAfterProbe = state.jobId != null &&
                    state.phase != CloneOutgoingPhase.PREPARING,
                retryCount = 0,
                nextAttemptAtMillis = 0L,
                error = null,
            )
        )
    }

    private fun requestFor(state: CloneOutgoingState): CloneRecoveryRequest {
        val jobId = requireNotNull(state.jobId) { "Outgoing Clone recovery has no job" }
        return staging.readRequest(jobId).also(::requireOutgoing)
    }

    private fun manifestFor(state: CloneOutgoingState): CloneRecoveryManifest {
        val request = requestFor(state)
        return staging.readManifest(request.jobId).also { manifest ->
            CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
            require(manifest.categories and SUPPORTED_CATEGORIES == manifest.categories) {
                "Clone recovery category is not available in this build"
            }
        }
    }

    private fun requireOutgoing(request: CloneRecoveryRequest) {
        CloneHistoryRecoveryProtocol.validateRequest(request)
        require(request.direction == CloneRecoveryDirection.SEND_TO_RECEIVER) {
            "Outgoing Clone recovery has the wrong direction"
        }
        require(request.categories and SUPPORTED_CATEGORIES == request.categories) {
            "Clone recovery category is not available in this build"
        }
    }

    private fun commitJson(manifest: CloneRecoveryManifest): String =
        CloneHistoryRecoveryProtocol.encodeCommit(
            CloneRecoveryCommit(manifest.protocolVersion, manifest.jobId, manifest.sha256)
        )

    private fun cancelJson(manifest: CloneRecoveryManifest): String =
        CloneHistoryRecoveryProtocol.encodeCancel(
            CloneRecoveryCancel(manifest.protocolVersion, manifest.jobId, manifest.sha256)
        )

    private fun writeState(state: CloneOutgoingState): CloneOutgoingState =
        state.also(staging::writeOutgoingState)

    private fun decodeUtf8(raw: ByteArray): String {
        require(raw.isNotEmpty() &&
            raw.size <= CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES
        ) { "Invalid Clone recovery control response" }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
    }

    companion object {
        private const val SUPPORTED_CATEGORIES =
            CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL

        @Volatile
        private var INSTANCE: CloneOutgoingRecoveryCoordinator? = null

        fun getInstance(context: Context = Applic.app): CloneOutgoingRecoveryCoordinator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloneOutgoingRecoveryCoordinator(context).also { INSTANCE = it }
            }
    }
}

/** Stable native-facing sender facade. Native executes only the returned bounded action. */
@Keep
object CloneOutgoingRecoveryAccess {
    private const val TAG = "CloneOutgoingRecovery"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparations = ConcurrentHashMap<String, Job>()
    private val wakeTimers = ConcurrentHashMap<String, Job>()

    private val coordinator: CloneOutgoingRecoveryCoordinator
        get() = CloneOutgoingRecoveryCoordinator.getInstance()

    @JvmStatic
    fun probeOutgoing(iceLabel: String, connectionGeneration: Long): String = stateResult {
        coordinator.probe(iceLabel, connectionGeneration).also(::scheduleState)
    }

    /** Persists PREPARING and returns; compression continues asynchronously. */
    @JvmStatic
    fun startOutgoingPush(
        iceLabel: String,
        connectionGeneration: Long,
        modeWire: String,
        includeJournal: Boolean,
    ): String = stateResult {
        coordinator.start(
            iceLabel,
            connectionGeneration,
            CloneRecoveryMode.fromWireValue(modeWire),
            includeJournal,
        ).also { launchPreparation(it.iceLabel) }
    }

    @JvmStatic
    fun nextOutgoingAction(iceLabel: String, connectionGeneration: Long): ByteArray = runCatching {
        runBlocking(Dispatchers.IO) { coordinator.nextAction(iceLabel, connectionGeneration) }
    }.onFailure { error ->
        Log.w(TAG, "Could not build outgoing Clone recovery action", error)
    }.getOrDefault(ByteArray(0))

    /** Returns 1 for immediate work. Java owns every delayed retry and status wake. */
    @JvmStatic
    fun reportOutgoingResult(
        iceLabel: String,
        connectionGeneration: Long,
        result: ByteArray,
    ): Int = runCatching {
        val plan = runBlocking(Dispatchers.IO) {
            coordinator.reportResult(iceLabel, connectionGeneration, result)
        }
        when {
            plan == 0L -> {
                wakeTimers.remove(iceLabel)?.cancel()
                1
            }
            plan > 0L -> {
                scheduleWake(iceLabel, plan)
                0
            }
            else -> {
                wakeTimers.remove(iceLabel)?.cancel()
                0
            }
        }
    }.onFailure { error ->
        Log.w(TAG, "Rejected outgoing Clone recovery result", error)
    }.getOrDefault(0)

    @JvmStatic
    fun outgoingStatusJson(iceLabel: String): String = stateResult {
        coordinator.status(iceLabel)
    }

    @JvmStatic
    fun cancelOutgoing(iceLabel: String): String = stateResult {
        coordinator.cancel(iceLabel).also { state ->
            if (state.phase == CloneOutgoingPhase.CANCELLED && !state.remoteJobEstablished) {
                preparations.remove(iceLabel)?.cancel()
            }
            scheduleState(state)
        }
    }

    /** Rebinds generation and asynchronously revalidates a retained package after restart. */
    @JvmStatic
    fun resumeOutgoing(iceLabel: String, connectionGeneration: Long): Int = runCatching {
        val state = runBlocking(Dispatchers.IO) {
            coordinator.resume(iceLabel, connectionGeneration)
        }
        when {
            state.phase.isTerminal || state.phase == CloneOutgoingPhase.PROBE_READY -> 0
            state.jobId != null -> {
                launchPreparation(iceLabel)
                0
            }
            state.nextAttemptAtMillis > System.currentTimeMillis() -> {
                scheduleWake(
                    iceLabel,
                    state.nextAttemptAtMillis - System.currentTimeMillis(),
                )
                0
            }
            else -> 1
        }
    }.onFailure { error ->
        Log.w(TAG, "Could not resume outgoing Clone recovery", error)
    }.getOrDefault(0)

    @JvmStatic
    fun outgoingLabelsJson(): String = runCatching {
        val encoded = JSONArray(
            runBlocking(Dispatchers.IO) { coordinator.labels() }
        ).toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <=
            CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES
        ) { "Outgoing Clone recovery label list is too large" }
        encoded
    }.onFailure { error ->
        Log.w(TAG, "Could not enumerate outgoing Clone recovery jobs", error)
    }.getOrDefault("[]")

    @JvmStatic
    fun clearOutgoing(iceLabel: String): Boolean = runCatching {
        val cleared = runBlocking(Dispatchers.IO) { coordinator.clear(iceLabel) }
        if (cleared) {
            preparations.remove(iceLabel)?.cancel()
            wakeTimers.remove(iceLabel)?.cancel()
        }
        cleared
    }.onFailure { error ->
        Log.w(TAG, "Could not clear outgoing Clone recovery job", error)
    }.getOrDefault(false)

    private fun stateResult(block: suspend () -> CloneOutgoingState): String = runCatching {
        CloneOutgoingRecoveryProtocol.encodeState(runBlocking(Dispatchers.IO) { block() })
    }.onFailure { error ->
        Log.w(TAG, "Outgoing Clone recovery control operation failed", error)
    }.getOrDefault("")

    private fun launchPreparation(iceLabel: String) {
        preparations[iceLabel]?.let { active ->
            if (active.isActive) return
            preparations.remove(iceLabel, active)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                scheduleState(coordinator.ensurePrepared(iceLabel))
            } catch (error: Throwable) {
                Log.e(TAG, "Outgoing Clone recovery preparation failed", error)
                runCatching { coordinator.failPreparation(iceLabel, error) }
            }
        }
        val existing = preparations.putIfAbsent(iceLabel, job)
        if (existing != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { preparations.remove(iceLabel, job) }
        job.start()
    }

    private fun scheduleState(state: CloneOutgoingState) {
        if (state.phase.isTerminal || state.phase == CloneOutgoingPhase.PREPARING ||
            state.phase == CloneOutgoingPhase.PROBE_READY
        ) {
            return
        }
        val wait = (state.nextAttemptAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        if (wait == 0L) {
            wakeTimers.remove(state.iceLabel)?.cancel()
            wake(state.iceLabel)
        } else {
            scheduleWake(state.iceLabel, wait)
        }
    }

    private fun scheduleWake(iceLabel: String, delayMillis: Long) {
        wakeTimers.remove(iceLabel)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMillis.coerceAtLeast(1L))
            wake(iceLabel)
        }
        wakeTimers[iceLabel] = job
        job.invokeOnCompletion { wakeTimers.remove(iceLabel, job) }
        job.start()
    }

    private fun wake(iceLabel: String) {
        runCatching { Natives.wakeCloneRecovery(iceLabel) }
            .onFailure { error -> Log.w(TAG, "Could not wake Clone recovery sender", error) }
    }
}
