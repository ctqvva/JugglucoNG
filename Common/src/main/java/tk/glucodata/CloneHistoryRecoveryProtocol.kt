package tk.glucodata

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal enum class CloneRecoveryDirection(val wireValue: String) {
    SEND_TO_RECEIVER("send_to_receiver"),
    RECOVER_FROM_RECEIVER("recover_from_receiver");

    companion object {
        fun fromWireValue(value: String): CloneRecoveryDirection =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported Clone recovery direction")
    }
}

internal enum class CloneRecoveryMode(val wireValue: String) {
    ONLY_MISSING("only_missing"),
    FULL_HISTORY("full_history");

    companion object {
        fun fromWireValue(value: String): CloneRecoveryMode =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported Clone recovery mode")
    }
}

internal object CloneRecoveryCategories {
    const val GLUCOSE = 1
    const val JOURNAL = 1 shl 1
    const val HYPO_CLASSIFICATIONS = 1 shl 2
    const val ALL = GLUCOSE or JOURNAL or HYPO_CLASSIFICATIONS

    fun selected(includeJournal: Boolean, includeHypoClassifications: Boolean): Int =
        GLUCOSE or
            (if (includeJournal) JOURNAL else 0) or
            (if (includeHypoClassifications) HYPO_CLASSIFICATIONS else 0)

    fun validate(mask: Int, requireGlucose: Boolean = true): Int {
        require(mask and ALL.inv() == 0) { "Unknown Clone recovery category" }
        require(!requireGlucose || mask and GLUCOSE != 0) {
            "Glucose history is required for Clone recovery"
        }
        return mask
    }
}

internal data class CloneRecoveryCapabilities(
    val minimumProtocolVersion: Int,
    val maximumProtocolVersion: Int,
    val categories: Int,
    val maximumChunkBytes: Int,
    val maximumCompressedBytes: Long,
)

internal data class CloneRecoveryManifest(
    val protocolVersion: Int,
    val jobId: String,
    val direction: CloneRecoveryDirection,
    val mode: CloneRecoveryMode,
    val categories: Int,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val recordCounts: Map<String, Long>,
    val sha256: String,
)

internal data class CloneRecoveryRequest(
    val protocolVersion: Int,
    val jobId: String,
    val direction: CloneRecoveryDirection,
    val mode: CloneRecoveryMode,
    val categories: Int,
)

internal enum class CloneRecoveryPhase(val wireValue: String) {
    PREPARING("preparing"),
    RECEIVING("receiving"),
    VERIFYING("verifying"),
    IMPORTING("importing"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED

    companion object {
        fun fromWireValue(value: String): CloneRecoveryPhase =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported Clone recovery phase")
    }
}

/** Durable progress returned by the destination of a recovery job. */
internal data class CloneRecoveryStatus(
    val protocolVersion: Int,
    val jobId: String,
    val direction: CloneRecoveryDirection,
    val mode: CloneRecoveryMode,
    val categories: Int,
    val phase: CloneRecoveryPhase,
    val acceptedBytes: Long,
    val totalBytes: Long,
    val recordCounts: Map<String, Long>,
    val sha256: String,
    val error: String? = null,
)

internal data class CloneRecoveryCommit(
    val protocolVersion: Int,
    val jobId: String,
    val sha256: String,
)

internal data class CloneRecoveryCancel(
    val protocolVersion: Int,
    val jobId: String,
    val sha256: String,
)

internal object CloneHistoryRecoveryProtocol {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_CHUNK_BYTES = 64 * 1024
    const val MAXIMUM_CHUNK_BYTES = 256 * 1024
    const val MAXIMUM_RECORD_BYTES = 512 * 1024
    const val MAXIMUM_COMPRESSED_BYTES = 512L * 1024L * 1024L
    const val MAXIMUM_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
    const val MAXIMUM_RECORD_COUNT = 10_000_000L
    const val MAXIMUM_CONTROL_BYTES = 256 * 1024
    const val MAXIMUM_STATUS_ERROR_CHARS = 512

    const val CAPABILITY_PATH = "mirror/backfill/capabilities-v1"
    const val CAPABILITY_SCHEMA = "tk.glucodata.clone.recovery.capabilities"
    const val MANIFEST_SCHEMA = "tk.glucodata.clone.recovery.manifest"
    const val REQUEST_SCHEMA = "tk.glucodata.clone.recovery.request"
    const val STATUS_SCHEMA = "tk.glucodata.clone.recovery.status"
    const val COMMIT_SCHEMA = "tk.glucodata.clone.recovery.commit"
    const val CANCEL_SCHEMA = "tk.glucodata.clone.recovery.cancel"
    const val JOB_PATH_PREFIX = "mirror/backfill/jobs"

    private val jobIdPattern = Regex("^[a-f0-9]{32}$")
    private val digestPattern = Regex("^[a-f0-9]{64}$")
    private val countKeyPattern = Regex("^[a-z][a-z0-9_]{0,47}$")

    fun newJobId(): String = UUID.randomUUID().toString().replace("-", "")

    fun validateJobId(jobId: String): String {
        require(jobIdPattern.matches(jobId)) { "Invalid Clone recovery job identifier" }
        return jobId
    }

    fun jobManifestPath(jobId: String): String =
        "$JOB_PATH_PREFIX/${validateJobId(jobId)}/manifest.json"

    fun jobPackagePath(jobId: String): String =
        "$JOB_PATH_PREFIX/${validateJobId(jobId)}/package.jsonl.gz"

    fun jobRequestPath(jobId: String): String =
        "$JOB_PATH_PREFIX/${validateJobId(jobId)}/request.json"

    fun jobCommitPath(jobId: String): String =
        "$JOB_PATH_PREFIX/${validateJobId(jobId)}/commit.json"

    fun jobStatusPath(jobId: String): String =
        "$JOB_PATH_PREFIX/${validateJobId(jobId)}/status.json"

    fun jobCancelPath(jobId: String): String =
        "$JOB_PATH_PREFIX/${validateJobId(jobId)}/cancel.json"

    fun validateRecordName(name: String): String {
        require(countKeyPattern.matches(name)) { "Invalid Clone recovery record category" }
        return name
    }

    fun localCapabilities(categories: Int): CloneRecoveryCapabilities =
        CloneRecoveryCapabilities(
            minimumProtocolVersion = PROTOCOL_VERSION,
            maximumProtocolVersion = PROTOCOL_VERSION,
            categories = CloneRecoveryCategories.validate(categories),
            maximumChunkBytes = DEFAULT_CHUNK_BYTES,
            maximumCompressedBytes = MAXIMUM_COMPRESSED_BYTES,
        )

    fun encodeCapabilities(capabilities: CloneRecoveryCapabilities): String {
        validateCapabilities(capabilities)
        return JSONObject()
            .put("schema", CAPABILITY_SCHEMA)
            .put("minimumProtocolVersion", capabilities.minimumProtocolVersion)
            .put("maximumProtocolVersion", capabilities.maximumProtocolVersion)
            .put("categories", capabilities.categories)
            .put("maximumChunkBytes", capabilities.maximumChunkBytes)
            .put("maximumCompressedBytes", capabilities.maximumCompressedBytes)
            .toString()
    }

    fun decodeCapabilities(raw: String): CloneRecoveryCapabilities {
        val root = decodeControl(raw)
        require(root.optString("schema") == CAPABILITY_SCHEMA) {
            "Unsupported Clone recovery capability schema"
        }
        return CloneRecoveryCapabilities(
            minimumProtocolVersion = root.requireInt("minimumProtocolVersion"),
            maximumProtocolVersion = root.requireInt("maximumProtocolVersion"),
            categories = root.requireInt("categories"),
            maximumChunkBytes = root.requireInt("maximumChunkBytes"),
            maximumCompressedBytes = root.requireLong("maximumCompressedBytes"),
        ).also(::validateCapabilities)
    }

    fun encodeRequest(request: CloneRecoveryRequest): String {
        validateRequest(request)
        return JSONObject()
            .put("schema", REQUEST_SCHEMA)
            .put("protocolVersion", request.protocolVersion)
            .put("jobId", request.jobId)
            .put("direction", request.direction.wireValue)
            .put("mode", request.mode.wireValue)
            .put("categories", request.categories)
            .toString()
    }

    fun decodeRequest(raw: String): CloneRecoveryRequest {
        val root = decodeControl(raw)
        require(root.optString("schema") == REQUEST_SCHEMA) {
            "Unsupported Clone recovery request schema"
        }
        return CloneRecoveryRequest(
            protocolVersion = root.requireInt("protocolVersion"),
            jobId = root.requireString("jobId"),
            direction = CloneRecoveryDirection.fromWireValue(root.requireString("direction")),
            mode = CloneRecoveryMode.fromWireValue(root.requireString("mode")),
            categories = root.requireInt("categories"),
        ).also(::validateRequest)
    }

    fun encodeManifest(manifest: CloneRecoveryManifest): String {
        validateManifest(manifest)
        val counts = JSONObject()
        manifest.recordCounts.toSortedMap().forEach { (name, count) -> counts.put(name, count) }
        return JSONObject()
            .put("schema", MANIFEST_SCHEMA)
            .put("protocolVersion", manifest.protocolVersion)
            .put("jobId", manifest.jobId)
            .put("direction", manifest.direction.wireValue)
            .put("mode", manifest.mode.wireValue)
            .put("categories", manifest.categories)
            .put("compressedBytes", manifest.compressedBytes)
            .put("uncompressedBytes", manifest.uncompressedBytes)
            .put("recordCounts", counts)
            .put("sha256", manifest.sha256)
            .toString()
    }

    fun decodeManifest(raw: String): CloneRecoveryManifest {
        val root = decodeControl(raw)
        require(root.optString("schema") == MANIFEST_SCHEMA) {
            "Unsupported Clone recovery manifest schema"
        }
        val countObject = root.optJSONObject("recordCounts")
            ?: throw IllegalArgumentException("Missing Clone recovery record counts")
        require(countObject.length() <= 32) { "Too many Clone recovery record categories" }
        val counts = buildMap {
            val names = countObject.keys()
            while (names.hasNext()) {
                val name = names.next()
                validateRecordName(name)
                put(name, countObject.requireLong(name))
            }
        }
        return CloneRecoveryManifest(
            protocolVersion = root.requireInt("protocolVersion"),
            jobId = root.requireString("jobId"),
            direction = CloneRecoveryDirection.fromWireValue(root.requireString("direction")),
            mode = CloneRecoveryMode.fromWireValue(root.requireString("mode")),
            categories = root.requireInt("categories"),
            compressedBytes = root.requireLong("compressedBytes"),
            uncompressedBytes = root.requireLong("uncompressedBytes"),
            recordCounts = counts,
            sha256 = root.requireString("sha256"),
        ).also(::validateManifest)
    }

    fun statusFor(
        manifest: CloneRecoveryManifest,
        phase: CloneRecoveryPhase,
        acceptedBytes: Long,
        error: String? = null,
    ): CloneRecoveryStatus {
        validateManifest(manifest)
        return CloneRecoveryStatus(
            protocolVersion = manifest.protocolVersion,
            jobId = manifest.jobId,
            direction = manifest.direction,
            mode = manifest.mode,
            categories = manifest.categories,
            phase = phase,
            acceptedBytes = acceptedBytes,
            totalBytes = manifest.compressedBytes,
            recordCounts = manifest.recordCounts,
            sha256 = manifest.sha256,
            error = error,
        ).also(::validateStatus)
    }

    fun encodeStatus(status: CloneRecoveryStatus): String {
        validateStatus(status)
        val counts = JSONObject()
        status.recordCounts.toSortedMap().forEach { (name, count) -> counts.put(name, count) }
        return JSONObject()
            .put("schema", STATUS_SCHEMA)
            .put("protocolVersion", status.protocolVersion)
            .put("jobId", status.jobId)
            .put("direction", status.direction.wireValue)
            .put("mode", status.mode.wireValue)
            .put("categories", status.categories)
            .put("phase", status.phase.wireValue)
            .put("acceptedBytes", status.acceptedBytes)
            .put("totalBytes", status.totalBytes)
            .put("recordCounts", counts)
            .put("sha256", status.sha256)
            .apply { status.error?.let { put("error", it) } }
            .toString()
    }

    fun decodeStatus(raw: String): CloneRecoveryStatus {
        val root = decodeControl(raw)
        require(root.optString("schema") == STATUS_SCHEMA) {
            "Unsupported Clone recovery status schema"
        }
        return CloneRecoveryStatus(
            protocolVersion = root.requireInt("protocolVersion"),
            jobId = root.requireString("jobId"),
            direction = CloneRecoveryDirection.fromWireValue(root.requireString("direction")),
            mode = CloneRecoveryMode.fromWireValue(root.requireString("mode")),
            categories = root.requireInt("categories"),
            phase = CloneRecoveryPhase.fromWireValue(root.requireString("phase")),
            acceptedBytes = root.requireLong("acceptedBytes"),
            totalBytes = root.requireLong("totalBytes"),
            recordCounts = root.requireRecordCounts(),
            sha256 = root.requireString("sha256"),
            error = if (root.has("error") && !root.isNull("error")) {
                root.requireString("error")
            } else {
                null
            },
        ).also(::validateStatus)
    }

    fun encodeCommit(commit: CloneRecoveryCommit): String {
        validateCommit(commit)
        return JSONObject()
            .put("schema", COMMIT_SCHEMA)
            .put("protocolVersion", commit.protocolVersion)
            .put("jobId", commit.jobId)
            .put("sha256", commit.sha256)
            .toString()
    }

    fun decodeCommit(raw: String): CloneRecoveryCommit {
        val root = decodeControl(raw)
        require(root.optString("schema") == COMMIT_SCHEMA) {
            "Unsupported Clone recovery commit schema"
        }
        return CloneRecoveryCommit(
            protocolVersion = root.requireInt("protocolVersion"),
            jobId = root.requireString("jobId"),
            sha256 = root.requireString("sha256"),
        ).also(::validateCommit)
    }

    fun encodeCancel(cancel: CloneRecoveryCancel): String {
        validateCancel(cancel)
        return JSONObject()
            .put("schema", CANCEL_SCHEMA)
            .put("protocolVersion", cancel.protocolVersion)
            .put("jobId", cancel.jobId)
            .put("sha256", cancel.sha256)
            .toString()
    }

    fun decodeCancel(raw: String): CloneRecoveryCancel {
        val root = decodeControl(raw)
        require(root.optString("schema") == CANCEL_SCHEMA) {
            "Unsupported Clone recovery cancel schema"
        }
        return CloneRecoveryCancel(
            protocolVersion = root.requireInt("protocolVersion"),
            jobId = root.requireString("jobId"),
            sha256 = root.requireString("sha256"),
        ).also(::validateCancel)
    }

    fun boundedStatusError(message: String?): String {
        val normalized = message.orEmpty()
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString(separator = "")
            .trim()
            .ifEmpty { "Clone recovery failed" }
        return normalized.take(MAXIMUM_STATUS_ERROR_CHARS)
    }

    fun negotiatedProtocolVersion(remote: CloneRecoveryCapabilities): Int? {
        validateCapabilities(remote)
        val lowestMaximum = minOf(PROTOCOL_VERSION, remote.maximumProtocolVersion)
        return lowestMaximum.takeIf { it >= remote.minimumProtocolVersion }
    }

    fun negotiatedChunkBytes(remote: CloneRecoveryCapabilities): Int {
        validateCapabilities(remote)
        return minOf(DEFAULT_CHUNK_BYTES, remote.maximumChunkBytes)
    }

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_CHUNK_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun validateManifest(manifest: CloneRecoveryManifest) {
        require(manifest.protocolVersion == PROTOCOL_VERSION) {
            "Unsupported Clone recovery protocol version"
        }
        validateJobId(manifest.jobId)
        CloneRecoveryCategories.validate(manifest.categories)
        require(manifest.compressedBytes in 1..MAXIMUM_COMPRESSED_BYTES) {
            "Clone recovery package is too large"
        }
        require(manifest.uncompressedBytes in 0..MAXIMUM_UNCOMPRESSED_BYTES) {
            "Clone recovery payload is too large"
        }
        require(manifest.recordCounts.isNotEmpty()) { "Missing Clone recovery record counts" }
        require(manifest.recordCounts.size <= 32) { "Too many Clone recovery record categories" }
        manifest.recordCounts.forEach { (name, count) ->
            validateRecordName(name)
            require(count in 0..MAXIMUM_RECORD_COUNT) { "Invalid Clone recovery record count" }
        }
        require(digestPattern.matches(manifest.sha256)) { "Invalid Clone recovery digest" }
    }

    fun validateRequest(request: CloneRecoveryRequest) {
        require(request.protocolVersion == PROTOCOL_VERSION) {
            "Unsupported Clone recovery protocol version"
        }
        validateJobId(request.jobId)
        CloneRecoveryCategories.validate(request.categories)
    }

    fun requireManifestMatchesRequest(
        manifest: CloneRecoveryManifest,
        request: CloneRecoveryRequest,
    ) {
        validateManifest(manifest)
        validateRequest(request)
        require(manifest.protocolVersion == request.protocolVersion) {
            "Clone recovery protocol changed after confirmation"
        }
        require(manifest.jobId == request.jobId) {
            "Clone recovery job does not match the confirmed request"
        }
        require(manifest.direction == request.direction) {
            "Clone recovery direction changed after confirmation"
        }
        require(manifest.mode == request.mode) {
            "Clone recovery mode changed after confirmation"
        }
        require(manifest.categories == request.categories) {
            "Clone recovery categories changed after confirmation"
        }
    }

    fun requireStatusMatchesManifest(
        status: CloneRecoveryStatus,
        manifest: CloneRecoveryManifest,
    ) {
        validateStatus(status)
        validateManifest(manifest)
        require(status.protocolVersion == manifest.protocolVersion &&
            status.jobId == manifest.jobId &&
            status.direction == manifest.direction &&
            status.mode == manifest.mode &&
            status.categories == manifest.categories &&
            status.totalBytes == manifest.compressedBytes &&
            status.recordCounts == manifest.recordCounts &&
            status.sha256 == manifest.sha256
        ) { "Clone recovery status does not match its manifest" }
    }

    fun validateStatus(status: CloneRecoveryStatus) {
        require(status.protocolVersion == PROTOCOL_VERSION) {
            "Unsupported Clone recovery protocol version"
        }
        validateJobId(status.jobId)
        CloneRecoveryCategories.validate(status.categories)
        require(status.totalBytes in 1..MAXIMUM_COMPRESSED_BYTES) {
            "Invalid Clone recovery status size"
        }
        require(status.acceptedBytes in 0..status.totalBytes) {
            "Invalid Clone recovery accepted byte count"
        }
        require(status.recordCounts.isNotEmpty() && status.recordCounts.size <= 32) {
            "Invalid Clone recovery status record counts"
        }
        status.recordCounts.forEach { (name, count) ->
            validateRecordName(name)
            require(count in 0..MAXIMUM_RECORD_COUNT) {
                "Invalid Clone recovery status record count"
            }
        }
        require(digestPattern.matches(status.sha256)) { "Invalid Clone recovery digest" }
        if (status.phase == CloneRecoveryPhase.VERIFYING ||
            status.phase == CloneRecoveryPhase.IMPORTING ||
            status.phase == CloneRecoveryPhase.COMPLETED
        ) {
            require(status.acceptedBytes == status.totalBytes) {
                "Clone recovery phase requires a complete package"
            }
        }
        if (status.phase == CloneRecoveryPhase.FAILED) {
            require(!status.error.isNullOrBlank()) { "Missing Clone recovery failure reason" }
        } else {
            require(status.error == null) { "Unexpected Clone recovery failure reason" }
        }
        require(status.error == null || status.error.length <= MAXIMUM_STATUS_ERROR_CHARS) {
            "Clone recovery failure reason is too long"
        }
    }

    fun validateCommit(commit: CloneRecoveryCommit) {
        validateTerminalControl(commit.protocolVersion, commit.jobId, commit.sha256)
    }

    fun validateCancel(cancel: CloneRecoveryCancel) {
        validateTerminalControl(cancel.protocolVersion, cancel.jobId, cancel.sha256)
    }

    private fun validateCapabilities(capabilities: CloneRecoveryCapabilities) {
        require(capabilities.minimumProtocolVersion > 0) {
            "Invalid minimum Clone recovery protocol version"
        }
        require(capabilities.maximumProtocolVersion >= capabilities.minimumProtocolVersion) {
            "Invalid Clone recovery protocol range"
        }
        CloneRecoveryCategories.validate(capabilities.categories)
        require(capabilities.maximumChunkBytes in 1..MAXIMUM_CHUNK_BYTES) {
            "Invalid Clone recovery chunk limit"
        }
        require(capabilities.maximumCompressedBytes in 1..MAXIMUM_COMPRESSED_BYTES) {
            "Invalid Clone recovery package limit"
        }
    }

    private fun validateTerminalControl(protocolVersion: Int, jobId: String, sha256: String) {
        require(protocolVersion == PROTOCOL_VERSION) {
            "Unsupported Clone recovery protocol version"
        }
        validateJobId(jobId)
        require(digestPattern.matches(sha256)) { "Invalid Clone recovery digest" }
    }

    private fun decodeControl(raw: String): JSONObject {
        val size = raw.toByteArray(Charsets.UTF_8).size
        require(size in 1..MAXIMUM_CONTROL_BYTES) {
            "Clone recovery control record is too large"
        }
        return JSONObject(raw)
    }

    private fun JSONObject.requireRecordCounts(): Map<String, Long> {
        val counts = optJSONObject("recordCounts")
            ?: throw IllegalArgumentException("Missing Clone recovery record counts")
        require(counts.length() <= 32) { "Too many Clone recovery record categories" }
        return buildMap {
            val names = counts.keys()
            while (names.hasNext()) {
                val name = names.next()
                validateRecordName(name)
                put(name, counts.requireLong(name))
            }
        }
    }

    private fun JSONObject.requireInt(name: String): Int {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = get(name)
        require(value is Number) { "Invalid $name" }
        val asLong = value.toLong()
        require(asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Invalid $name" }
        require(value.toDouble() == asLong.toDouble()) { "Invalid $name" }
        return asLong.toInt()
    }

    private fun JSONObject.requireLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = get(name)
        require(value is Number) { "Invalid $name" }
        val asLong = value.toLong()
        require(value.toDouble() == asLong.toDouble()) { "Invalid $name" }
        return asLong
    }

    private fun JSONObject.requireString(name: String): String {
        require(has(name) && !isNull(name)) { "Missing $name" }
        return (get(name) as? String) ?: throw IllegalArgumentException("Invalid $name")
    }
}
