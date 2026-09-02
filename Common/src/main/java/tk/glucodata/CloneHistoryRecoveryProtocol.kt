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

internal object CloneHistoryRecoveryProtocol {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_CHUNK_BYTES = 64 * 1024
    const val MAXIMUM_CHUNK_BYTES = 256 * 1024
    const val MAXIMUM_RECORD_BYTES = 512 * 1024
    const val MAXIMUM_COMPRESSED_BYTES = 512L * 1024L * 1024L
    const val MAXIMUM_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
    const val MAXIMUM_RECORD_COUNT = 10_000_000L

    const val CAPABILITY_PATH = "mirror/backfill/capabilities-v1"
    const val CAPABILITY_SCHEMA = "tk.glucodata.clone.recovery.capabilities"
    const val MANIFEST_SCHEMA = "tk.glucodata.clone.recovery.manifest"

    private val jobIdPattern = Regex("^[a-f0-9]{32}$")
    private val digestPattern = Regex("^[a-f0-9]{64}$")
    private val countKeyPattern = Regex("^[a-z][a-z0-9_]{0,47}$")

    fun newJobId(): String = UUID.randomUUID().toString().replace("-", "")

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
        val root = JSONObject(raw)
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
        val root = JSONObject(raw)
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
        require(jobIdPattern.matches(manifest.jobId)) { "Invalid Clone recovery job identifier" }
        CloneRecoveryCategories.validate(manifest.categories)
        require(manifest.compressedBytes in 1..MAXIMUM_COMPRESSED_BYTES) {
            "Clone recovery package is too large"
        }
        require(manifest.uncompressedBytes in 0..MAXIMUM_UNCOMPRESSED_BYTES) {
            "Clone recovery payload is too large"
        }
        require(manifest.recordCounts.isNotEmpty()) { "Missing Clone recovery record counts" }
        manifest.recordCounts.forEach { (name, count) ->
            validateRecordName(name)
            require(count in 0..MAXIMUM_RECORD_COUNT) { "Invalid Clone recovery record count" }
        }
        require(digestPattern.matches(manifest.sha256)) { "Invalid Clone recovery digest" }
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
