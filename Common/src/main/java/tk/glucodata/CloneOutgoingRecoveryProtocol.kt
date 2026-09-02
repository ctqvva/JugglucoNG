package tk.glucodata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal enum class CloneOutgoingPhase(val wireValue: String) {
    PROBING("probing"),
    PROBE_READY("probe_ready"),
    PREPARING("preparing"),
    PUTTING_MANIFEST("putting_manifest"),
    PUTTING_PACKAGE("putting_package"),
    PUTTING_COMMIT("putting_commit"),
    POLLING_STATUS("polling_status"),
    PUTTING_CANCEL("putting_cancel"),
    POLLING_CANCEL("polling_cancel"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED

    companion object {
        fun fromWireValue(value: String): CloneOutgoingPhase =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported outgoing Clone recovery phase")
    }
}

internal enum class CloneOutgoingActionKind(val code: Int) {
    PROBE_CAPABILITIES(1),
    PUT_MANIFEST(2),
    PUT_PACKAGE_CHUNK(3),
    PUT_COMMIT(4),
    GET_STATUS(5),
    PUT_CANCEL(6);

    companion object {
        fun fromCode(code: Int): CloneOutgoingActionKind =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unsupported outgoing Clone recovery action")
    }
}

internal enum class CloneOutgoingResultOutcome(val code: Int) {
    OK(1),
    NOT_FOUND(2),
    TRANSPORT_ERROR(3),
    REJECTED(4);

    companion object {
        fun fromCode(code: Int): CloneOutgoingResultOutcome =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unsupported outgoing Clone recovery result")
    }
}

internal data class CloneOutgoingState(
    val protocolVersion: Int = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
    val iceLabel: String,
    val connectionGeneration: Long,
    val jobId: String?,
    val phase: CloneOutgoingPhase,
    val nextOffset: Long = 0L,
    val maximumChunkBytes: Int = CloneHistoryRecoveryProtocol.DEFAULT_CHUNK_BYTES,
    val negotiatedProtocolVersion: Int? = null,
    val remoteCategories: Int? = null,
    val remoteMaximumCompressedBytes: Long? = null,
    val capabilityGeneration: Long? = null,
    val reconcileAfterProbe: Boolean = false,
    val remoteJobEstablished: Boolean = false,
    val cancelRequested: Boolean = false,
    val remoteTerminal: Boolean = false,
    val retryCount: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
    val error: String? = null,
)

internal data class CloneOutgoingAction(
    val kind: CloneOutgoingActionKind,
    val offset: Long,
    val path: String,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is CloneOutgoingAction &&
        kind == other.kind && offset == other.offset && path == other.path &&
        payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * (31 * kind.hashCode() + offset.hashCode()) +
        path.hashCode()) + payload.contentHashCode()
}

internal data class CloneOutgoingResult(
    val outcome: CloneOutgoingResultOutcome,
    val connectionGeneration: Long,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is CloneOutgoingResult &&
        outcome == other.outcome && connectionGeneration == other.connectionGeneration &&
        payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * outcome.hashCode() +
        connectionGeneration.hashCode()) + payload.contentHashCode()
}

/** Pure lifecycle decisions kept separate from Android scheduling and storage. */
internal object CloneOutgoingRecoveryTransitions {
    fun acceptCapabilities(
        state: CloneOutgoingState,
        capabilities: CloneRecoveryCapabilities,
        requiredCategories: Int?,
        compressedBytes: Long?,
    ): CloneOutgoingState {
        val negotiated = requireNotNull(
            CloneHistoryRecoveryProtocol.negotiatedProtocolVersion(capabilities)
        ) { "Clone peer has no compatible history recovery version" }
        if (state.jobId != null) {
            val categories = requireNotNull(requiredCategories)
            val size = requireNotNull(compressedBytes)
            require(capabilities.categories and categories == categories) {
                "Clone peer does not support the selected recovery categories"
            }
            require(size <= capabilities.maximumCompressedBytes) {
                "Clone recovery package exceeds the peer limit"
            }
        }
        val nextPhase = when {
            state.jobId == null -> CloneOutgoingPhase.PROBE_READY
            state.reconcileAfterProbe && state.cancelRequested -> CloneOutgoingPhase.POLLING_CANCEL
            state.reconcileAfterProbe -> CloneOutgoingPhase.POLLING_STATUS
            else -> CloneOutgoingPhase.PUTTING_MANIFEST
        }
        return state.copy(
            phase = nextPhase,
            maximumChunkBytes = CloneHistoryRecoveryProtocol.negotiatedChunkBytes(capabilities),
            negotiatedProtocolVersion = negotiated,
            remoteCategories = capabilities.categories,
            remoteMaximumCompressedBytes = capabilities.maximumCompressedBytes,
            capabilityGeneration = state.connectionGeneration,
            reconcileAfterProbe = false,
            retryCount = if (state.reconcileAfterProbe) state.retryCount else 0,
            nextAttemptAtMillis = 0L,
            error = null,
        ).also(CloneOutgoingRecoveryProtocol::validateState)
    }

    fun requestCancel(state: CloneOutgoingState): CloneOutgoingState {
        if (state.phase.isTerminal) return state
        if (state.jobId == null || !state.remoteJobEstablished &&
            !state.reconcileAfterProbe &&
            (state.phase == CloneOutgoingPhase.PREPARING ||
                state.phase == CloneOutgoingPhase.PROBING)
        ) {
            return state.copy(
                phase = CloneOutgoingPhase.CANCELLED,
                cancelRequested = true,
                retryCount = 0,
                nextAttemptAtMillis = 0L,
                error = null,
            ).also(CloneOutgoingRecoveryProtocol::validateState)
        }
        val capabilitiesCurrent = state.capabilityGeneration == state.connectionGeneration
        val nextPhase = when {
            state.phase == CloneOutgoingPhase.PREPARING -> CloneOutgoingPhase.PREPARING
            !capabilitiesCurrent -> CloneOutgoingPhase.PROBING
            state.phase == CloneOutgoingPhase.PROBING ||
                state.phase == CloneOutgoingPhase.PUTTING_MANIFEST -> state.phase
            else -> CloneOutgoingPhase.PUTTING_CANCEL
        }
        return state.copy(
            phase = nextPhase,
            reconcileAfterProbe = nextPhase == CloneOutgoingPhase.PROBING,
            cancelRequested = true,
            retryCount = 0,
            nextAttemptAtMillis = 0L,
            error = null,
        ).also(CloneOutgoingRecoveryProtocol::validateState)
    }

    /**
     * An exact commit is replay-safe. Re-send it while the receiver reports a
     * non-terminal commit phase so a receiver process restart relaunches the
     * durable import instead of leaving both peers polling forever.
     */
    fun continueRemoteCommit(
        state: CloneOutgoingState,
        remotePhase: CloneRecoveryPhase,
        acceptedBytes: Long,
    ): CloneOutgoingState {
        require(remotePhase == CloneRecoveryPhase.VERIFYING ||
            remotePhase == CloneRecoveryPhase.IMPORTING
        ) { "Remote Clone recovery is not in a commit phase" }
        val canStillCancel = remotePhase == CloneRecoveryPhase.VERIFYING
        return state.copy(
            phase = if (canStillCancel && state.cancelRequested) {
                CloneOutgoingPhase.PUTTING_CANCEL
            } else {
                CloneOutgoingPhase.PUTTING_COMMIT
            },
            cancelRequested = state.cancelRequested && canStillCancel,
            nextOffset = acceptedBytes,
            retryCount = 0,
            nextAttemptAtMillis = 0L,
            error = null,
        ).also(CloneOutgoingRecoveryProtocol::validateState)
    }

    fun mayClear(state: CloneOutgoingState): Boolean =
        state.jobId == null || state.phase == CloneOutgoingPhase.FAILED ||
            state.phase == CloneOutgoingPhase.CANCELLED && !state.remoteJobEstablished ||
            state.remoteTerminal &&
            (state.phase == CloneOutgoingPhase.COMPLETED ||
                state.phase == CloneOutgoingPhase.CANCELLED)
}

/** Stable persisted sender state and the compact Java/native action boundary. */
internal object CloneOutgoingRecoveryProtocol {
    const val STATE_SCHEMA = "tk.glucodata.clone.recovery.outgoing"
    const val ACTION_VERSION = 1
    const val RESULT_VERSION = 1
    const val MAXIMUM_ICE_LABEL_BYTES = 256
    const val ACTION_HEADER_BYTES = 20
    const val RESULT_HEADER_BYTES = 16
    const val GET_PAGE_BYTES = 64 * 1024
    const val OUTGOING_PATH_PREFIX = "mirror/backfill/outgoing"

    private const val MAXIMUM_PATH_BYTES = 256
    private const val MAXIMUM_RETRY_COUNT = 30
    private val jobPathPattern = Regex(
        "^${CloneHistoryRecoveryProtocol.JOB_PATH_PREFIX}/[a-f0-9]{32}/" +
            "(manifest\\.json|package\\.jsonl\\.gz|status\\.json|commit\\.json|cancel\\.json)$"
    )

    fun validateIceLabel(iceLabel: String): String {
        val bytes = iceLabel.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..MAXIMUM_ICE_LABEL_BYTES &&
            iceLabel.none { character -> character.isISOControl() }
        ) { "Invalid Clone recovery ICE label" }
        return iceLabel
    }

    fun encodeState(state: CloneOutgoingState): String {
        validateState(state)
        return JSONObject()
            .put("schema", STATE_SCHEMA)
            .put("protocolVersion", state.protocolVersion)
            .put("iceLabel", state.iceLabel)
            .put("connectionGeneration", state.connectionGeneration)
            .apply { state.jobId?.let { put("jobId", it) } }
            .put("phase", state.phase.wireValue)
            .put("nextOffset", state.nextOffset)
            .put("maximumChunkBytes", state.maximumChunkBytes)
            .apply {
                state.negotiatedProtocolVersion?.let { put("negotiatedProtocolVersion", it) }
                state.remoteCategories?.let { put("remoteCategories", it) }
                state.remoteMaximumCompressedBytes?.let {
                    put("remoteMaximumCompressedBytes", it)
                }
                state.capabilityGeneration?.let { put("capabilityGeneration", it) }
            }
            .put("reconcileAfterProbe", state.reconcileAfterProbe)
            .put("remoteJobEstablished", state.remoteJobEstablished)
            .put("cancelRequested", state.cancelRequested)
            .put("remoteTerminal", state.remoteTerminal)
            .put("retryCount", state.retryCount)
            .put("nextAttemptAtMillis", state.nextAttemptAtMillis)
            .apply { state.error?.let { put("error", it) } }
            .toString()
    }

    fun decodeState(raw: String): CloneOutgoingState {
        requireControlSize(raw)
        val root = JSONObject(raw)
        require(root.optString("schema") == STATE_SCHEMA) {
            "Unsupported outgoing Clone recovery state schema"
        }
        return CloneOutgoingState(
            protocolVersion = root.requireInt("protocolVersion"),
            iceLabel = root.requireString("iceLabel"),
            connectionGeneration = root.requireLong("connectionGeneration"),
            jobId = root.optionalString("jobId"),
            phase = CloneOutgoingPhase.fromWireValue(root.requireString("phase")),
            nextOffset = root.requireLong("nextOffset"),
            maximumChunkBytes = root.requireInt("maximumChunkBytes"),
            negotiatedProtocolVersion = root.optionalInt("negotiatedProtocolVersion"),
            remoteCategories = root.optionalInt("remoteCategories"),
            remoteMaximumCompressedBytes = root.optionalLong("remoteMaximumCompressedBytes"),
            capabilityGeneration = root.optionalLong("capabilityGeneration"),
            reconcileAfterProbe = root.requireBoolean("reconcileAfterProbe"),
            remoteJobEstablished = root.requireBoolean("remoteJobEstablished"),
            cancelRequested = root.requireBoolean("cancelRequested"),
            remoteTerminal = root.requireBoolean("remoteTerminal"),
            retryCount = root.requireInt("retryCount"),
            nextAttemptAtMillis = root.requireLong("nextAttemptAtMillis"),
            error = root.optionalString("error"),
        ).also(::validateState)
    }

    fun validateState(state: CloneOutgoingState) {
        require(state.protocolVersion == CloneHistoryRecoveryProtocol.PROTOCOL_VERSION) {
            "Unsupported outgoing Clone recovery protocol version"
        }
        validateIceLabel(state.iceLabel)
        require(state.connectionGeneration >= 0L) {
            "Invalid Clone recovery connection generation"
        }
        state.jobId?.let(CloneHistoryRecoveryProtocol::validateJobId)
        val jobRequired = state.phase !in setOf(
            CloneOutgoingPhase.PROBING,
            CloneOutgoingPhase.PROBE_READY,
            CloneOutgoingPhase.FAILED,
        )
        require(!jobRequired || state.jobId != null) {
            "Outgoing Clone recovery phase requires a job"
        }
        require(state.nextOffset in 0..CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES) {
            "Invalid outgoing Clone recovery offset"
        }
        require(state.maximumChunkBytes in 1..CloneHistoryRecoveryProtocol.MAXIMUM_CHUNK_BYTES) {
            "Invalid outgoing Clone recovery chunk size"
        }
        state.negotiatedProtocolVersion?.let {
            require(it == CloneHistoryRecoveryProtocol.PROTOCOL_VERSION) {
                "Invalid negotiated Clone recovery version"
            }
        }
        state.remoteCategories?.let { CloneRecoveryCategories.validate(it) }
        state.remoteMaximumCompressedBytes?.let {
            require(it in 1..CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES) {
                "Invalid remote Clone recovery package limit"
            }
        }
        state.capabilityGeneration?.let {
            require(it >= 0L) { "Invalid Clone recovery capability generation" }
        }
        require(state.capabilityGeneration == null ||
            state.negotiatedProtocolVersion != null && state.remoteCategories != null &&
                state.remoteMaximumCompressedBytes != null
        ) { "Incomplete outgoing Clone recovery capability state" }
        require(!state.remoteJobEstablished || state.jobId != null) {
            "Remote Clone recovery job has no local binding"
        }
        require(state.retryCount in 0..MAXIMUM_RETRY_COUNT) {
            "Invalid outgoing Clone recovery retry count"
        }
        require(state.nextAttemptAtMillis >= 0L) {
            "Invalid outgoing Clone recovery retry time"
        }
        require(state.error == null ||
            state.error.length <= CloneHistoryRecoveryProtocol.MAXIMUM_STATUS_ERROR_CHARS
        ) { "Outgoing Clone recovery error is too long" }
        require(!state.remoteTerminal || state.remoteJobEstablished &&
            (state.phase == CloneOutgoingPhase.COMPLETED ||
                state.phase == CloneOutgoingPhase.CANCELLED)
        ) { "Outgoing Clone recovery terminal marker is invalid" }
        require(state.phase != CloneOutgoingPhase.COMPLETED || state.remoteTerminal) {
            "Outgoing Clone recovery completion was not confirmed remotely"
        }
    }

    fun encodeAction(action: CloneOutgoingAction): ByteArray {
        validateAction(action)
        val path = action.path.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(ACTION_HEADER_BYTES + path.size + action.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(ACTION_VERSION.toByte())
            .put(action.kind.code.toByte())
            .putShort(0)
            .putLong(action.offset)
            .putInt(path.size)
            .putInt(action.payload.size)
            .put(path)
            .put(action.payload)
            .array()
    }

    fun decodeAction(raw: ByteArray): CloneOutgoingAction {
        require(raw.size >= ACTION_HEADER_BYTES) { "Outgoing Clone recovery action is truncated" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.get().toInt() and 0xff == ACTION_VERSION && buffer.getShort(2) == 0.toShort()) {
            "Unsupported outgoing Clone recovery action frame"
        }
        val kind = CloneOutgoingActionKind.fromCode(buffer.get(1).toInt() and 0xff)
        buffer.position(4)
        val offset = buffer.long
        val pathBytes = buffer.int
        val payloadBytes = buffer.int
        require(pathBytes in 1..MAXIMUM_PATH_BYTES &&
            payloadBytes in 0..CloneHistoryRecoveryProtocol.MAXIMUM_CHUNK_BYTES &&
            ACTION_HEADER_BYTES.toLong() + pathBytes + payloadBytes == raw.size.toLong()
        ) { "Invalid outgoing Clone recovery action length" }
        val pathRaw = ByteArray(pathBytes).also { buffer.get(it) }
        val payload = ByteArray(payloadBytes).also { buffer.get(it) }
        return CloneOutgoingAction(
            kind = kind,
            offset = offset,
            path = decodeUtf8(pathRaw),
            payload = payload,
        ).also(::validateAction)
    }

    fun encodeResult(result: CloneOutgoingResult): ByteArray {
        validateResult(result)
        return ByteBuffer.allocate(RESULT_HEADER_BYTES + result.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(RESULT_VERSION.toByte())
            .put(result.outcome.code.toByte())
            .putShort(0)
            .putLong(result.connectionGeneration)
            .putInt(result.payload.size)
            .put(result.payload)
            .array()
    }

    fun decodeResult(raw: ByteArray): CloneOutgoingResult {
        require(raw.size >= RESULT_HEADER_BYTES) { "Outgoing Clone recovery result is truncated" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.get().toInt() and 0xff == RESULT_VERSION && buffer.getShort(2) == 0.toShort()) {
            "Unsupported outgoing Clone recovery result frame"
        }
        val outcome = CloneOutgoingResultOutcome.fromCode(buffer.get(1).toInt() and 0xff)
        buffer.position(4)
        val generation = buffer.long
        val payloadBytes = buffer.int
        require(payloadBytes in 0..GET_PAGE_BYTES &&
            RESULT_HEADER_BYTES.toLong() + payloadBytes == raw.size.toLong()
        ) { "Invalid outgoing Clone recovery result length" }
        val payload = ByteArray(payloadBytes).also { buffer.get(it) }
        return CloneOutgoingResult(outcome, generation, payload).also(::validateResult)
    }

    fun boundedError(message: String?): String =
        CloneHistoryRecoveryProtocol.boundedStatusError(message)

    private fun validateAction(action: CloneOutgoingAction) {
        val pathBytes = action.path.toByteArray(StandardCharsets.UTF_8).size
        require(action.offset >= 0L && pathBytes in 1..MAXIMUM_PATH_BYTES) {
            "Invalid outgoing Clone recovery action"
        }
        require(action.payload.size <= CloneHistoryRecoveryProtocol.MAXIMUM_CHUNK_BYTES) {
            "Outgoing Clone recovery action payload is too large"
        }
        when (action.kind) {
            CloneOutgoingActionKind.PROBE_CAPABILITIES -> require(
                action.path == CloneHistoryRecoveryProtocol.CAPABILITY_PATH &&
                    validReadAction(action)
            ) { "Invalid Clone recovery capability action" }
            CloneOutgoingActionKind.GET_STATUS -> require(
                exactJobPath(action.path, "status.json") && validReadAction(action)
            ) { "Invalid Clone recovery status action" }
            CloneOutgoingActionKind.PUT_MANIFEST -> require(
                exactJobPath(action.path, "manifest.json") && action.offset == 0L &&
                    action.payload.isNotEmpty()
            ) { "Invalid Clone recovery manifest action" }
            CloneOutgoingActionKind.PUT_PACKAGE_CHUNK -> require(
                exactJobPath(action.path, "package.jsonl.gz") && action.payload.isNotEmpty() &&
                    action.offset <= CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES &&
                    action.payload.size.toLong() <=
                        CloneHistoryRecoveryProtocol.MAXIMUM_COMPRESSED_BYTES - action.offset
            ) { "Invalid Clone recovery package action" }
            CloneOutgoingActionKind.PUT_COMMIT -> require(
                exactJobPath(action.path, "commit.json") && action.offset == 0L &&
                    action.payload.isNotEmpty()
            ) { "Invalid Clone recovery commit action" }
            CloneOutgoingActionKind.PUT_CANCEL -> require(
                exactJobPath(action.path, "cancel.json") && action.offset == 0L &&
                    action.payload.isNotEmpty()
            ) { "Invalid Clone recovery cancel action" }
        }
    }

    private fun validateResult(result: CloneOutgoingResult) {
        require(result.connectionGeneration >= 0L) {
            "Invalid Clone recovery result generation"
        }
        require(result.payload.size <= GET_PAGE_BYTES) {
            "Outgoing Clone recovery result payload is too large"
        }
        if (result.outcome != CloneOutgoingResultOutcome.OK) {
            require(result.payload.isEmpty()) { "Unexpected Clone recovery error payload" }
        }
    }

    fun readRequestBytes(maximumBytes: Int = GET_PAGE_BYTES): ByteArray {
        require(maximumBytes in 1..GET_PAGE_BYTES) { "Invalid Clone recovery read size" }
        return ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(maximumBytes)
            .array()
    }

    fun requestedReadBytes(payload: ByteArray): Int {
        require(validReadRequest(payload)) { "Invalid Clone recovery read request" }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun validReadRequest(payload: ByteArray): Boolean =
        payload.size == Int.SIZE_BYTES &&
            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int in 1..GET_PAGE_BYTES

    private fun validReadAction(action: CloneOutgoingAction): Boolean =
        action.offset <= CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES &&
            validReadRequest(action.payload) &&
            requestedReadBytes(action.payload).toLong() <=
                CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES - action.offset

    private fun exactJobPath(path: String, filename: String): Boolean =
        jobPathPattern.matches(path) && path.endsWith("/$filename")

    private fun requireControlSize(raw: String) {
        require(raw.toByteArray(StandardCharsets.UTF_8).size in
            1..CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES
        ) { "Outgoing Clone recovery control record is too large" }
    }

    private fun decodeUtf8(raw: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
        .toString()

    private fun JSONObject.requireString(name: String): String {
        require(has(name) && !isNull(name)) { "Missing $name" }
        return get(name) as? String ?: throw IllegalArgumentException("Invalid $name")
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) requireString(name) else null

    private fun JSONObject.requireLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = get(name)
        require(value is Number) { "Invalid $name" }
        val result = value.toLong()
        require(value.toDouble() == result.toDouble()) { "Invalid $name" }
        return result
    }

    private fun JSONObject.requireInt(name: String): Int {
        val value = requireLong(name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Invalid $name" }
        return value.toInt()
    }

    private fun JSONObject.optionalInt(name: String): Int? =
        if (has(name) && !isNull(name)) requireInt(name) else null

    private fun JSONObject.optionalLong(name: String): Long? =
        if (has(name) && !isNull(name)) requireLong(name) else null

    private fun JSONObject.requireBoolean(name: String): Boolean {
        require(has(name) && !isNull(name) && get(name) is Boolean) { "Invalid $name" }
        return getBoolean(name)
    }
}
