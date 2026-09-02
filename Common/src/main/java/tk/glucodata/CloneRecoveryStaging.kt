package tk.glucodata

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/** App-private, resumable staging for one explicitly confirmed Clone recovery job. */
internal class CloneRecoveryStaging(
    private val filesDirectory: File,
) {
    private val verifiedForRead = mutableMapOf<String, VerifiedPackage>()
    private val jobsDirectory: File by lazy {
        safeDirectory(CloneHistoryRecoveryProtocol.JOB_PATH_PREFIX)
    }

    @Synchronized
    fun stageRequest(request: CloneRecoveryRequest): File {
        CloneHistoryRecoveryProtocol.validateRequest(request)
        return writeOnce(
            requestFile(request.jobId),
            CloneHistoryRecoveryProtocol.encodeRequest(request),
        )
    }

    @Synchronized
    fun readRequest(jobId: String): CloneRecoveryRequest =
        CloneHistoryRecoveryProtocol.decodeRequest(readBoundedText(requestFile(jobId)))

    @Synchronized
    fun stageManifest(
        manifest: CloneRecoveryManifest,
        confirmedRequest: CloneRecoveryRequest,
    ): File {
        CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, confirmedRequest)
        stageRequest(confirmedRequest)
        return writeOnce(
            manifestFile(manifest.jobId),
            CloneHistoryRecoveryProtocol.encodeManifest(manifest),
        )
    }

    @Synchronized
    fun readManifest(jobId: String): CloneRecoveryManifest =
        CloneHistoryRecoveryProtocol.decodeManifest(readBoundedText(manifestFile(jobId)))

    @Synchronized
    fun existingManifest(jobId: String): CloneRecoveryManifest? {
        val file = manifestFile(jobId)
        return if (file.isFile) {
            CloneHistoryRecoveryProtocol.decodeManifest(readBoundedText(file))
        } else {
            null
        }
    }

    /**
     * Creates an empty destination on first receipt while preserving an exact partial retry.
     * A changed manifest or confirmation is rejected instead of truncating prior progress.
     */
    @Synchronized
    fun prepareIncoming(
        manifest: CloneRecoveryManifest,
        confirmedRequest: CloneRecoveryRequest,
    ): Long {
        stageManifest(manifest, confirmedRequest)
        val file = packageFile(manifest.jobId)
        require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "Could not create Clone recovery job directory"
        }
        if (!file.exists()) {
            require(file.createNewFile()) { "Could not create Clone recovery package" }
        }
        require(file.isFile && file.length() <= manifest.compressedBytes) {
            "Invalid partial Clone recovery package"
        }
        verifiedForRead.remove(manifest.jobId)
        touchJob(manifest.jobId)
        return file.length()
    }

    /** Appends a new chunk or accepts an identical, fully written retry. */
    @Synchronized
    fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): Long {
        val manifest = readManifest(jobId)
        val request = readRequest(jobId)
        CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
        require(bytes.isNotEmpty()) { "Empty Clone recovery chunk" }
        require(bytes.size <= CloneHistoryRecoveryProtocol.MAXIMUM_CHUNK_BYTES) {
            "Clone recovery chunk is too large"
        }
        require(offset >= 0L && offset <= manifest.compressedBytes) {
            "Invalid Clone recovery chunk offset"
        }
        require(bytes.size.toLong() <= manifest.compressedBytes - offset) {
            "Clone recovery chunk exceeds its manifest"
        }
        val file = packageFile(jobId)
        require(file.isFile) { "Clone recovery package was not prepared" }
        val currentLength = file.length()
        when {
            offset == currentLength -> RandomAccessFile(file, "rw").use { output ->
                output.seek(offset)
                output.write(bytes)
                output.fd.sync()
            }
            offset < currentLength && offset + bytes.size <= currentLength -> {
                val existing = ByteArray(bytes.size)
                RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    input.readFully(existing)
                }
                require(existing.contentEquals(bytes)) {
                    "Clone recovery retry does not match staged data"
                }
            }
            else -> throw IllegalArgumentException(
                "Clone recovery chunk is not at a resumable boundary"
            )
        }
        verifiedForRead.remove(jobId)
        touchJob(jobId)
        return file.length()
    }

    @Synchronized
    fun readPackageChunk(jobId: String, offset: Long, maximumBytes: Int): ByteArray {
        val manifest = readManifest(jobId)
        require(maximumBytes in 1..CloneHistoryRecoveryProtocol.MAXIMUM_CHUNK_BYTES) {
            "Invalid Clone recovery chunk limit"
        }
        val file = packageFile(manifest.jobId)
        require(file.isFile && file.length() == manifest.compressedBytes) {
            "Clone recovery package is incomplete"
        }
        val verified = verifiedForRead[manifest.jobId]
        if (verified == null || verified.length != file.length() ||
            verified.lastModified != file.lastModified() || verified.sha256 != manifest.sha256
        ) {
            verifiedPackageFile(manifest)
        }
        require(offset in 0..file.length()) { "Invalid Clone recovery chunk offset" }
        val count = minOf(maximumBytes.toLong(), file.length() - offset).toInt()
        if (count == 0) return ByteArray(0)
        return ByteArray(count).also { bytes ->
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                input.readFully(bytes)
            }
            touchJob(jobId)
        }
    }

    @Synchronized
    fun verifiedPackageFile(jobId: String): File = verifiedPackageFile(readManifest(jobId))

    @Synchronized
    fun packageFileForCreation(request: CloneRecoveryRequest): File {
        stageRequest(request)
        val file = packageFile(request.jobId)
        require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "Could not create Clone recovery job directory"
        }
        verifiedForRead.remove(request.jobId)
        return file
    }

    @Synchronized
    fun clearJob(jobId: String): Boolean {
        val directory = jobDirectory(jobId, create = false)
        verifiedForRead.remove(jobId)
        return !directory.exists() || directory.deleteRecursively()
    }

    @Synchronized
    fun cleanJobsOlderThan(cutoffMillis: Long): Int {
        if (!jobsDirectory.isDirectory) return 0
        var removed = 0
        jobsDirectory.listFiles().orEmpty().forEach { candidate ->
            val validJob = runCatching {
                CloneHistoryRecoveryProtocol.validateJobId(candidate.name)
            }.isSuccess
            val containedRealDirectory = runCatching {
                candidate.canonicalFile == candidate.absoluteFile &&
                    candidate.canonicalFile.parentFile == jobsDirectory.canonicalFile
            }.getOrDefault(false)
            if (validJob && containedRealDirectory && candidate.isDirectory &&
                candidate.lastModified() < cutoffMillis &&
                candidate.deleteRecursively()
            ) {
                verifiedForRead.remove(candidate.name)
                removed++
            }
        }
        return removed
    }

    fun relativeManifestPath(jobId: String): String =
        CloneHistoryRecoveryProtocol.jobManifestPath(jobId)

    fun relativePackagePath(jobId: String): String =
        CloneHistoryRecoveryProtocol.jobPackagePath(jobId)

    private fun verifiedPackageFile(manifest: CloneRecoveryManifest): File {
        CloneHistoryRecoveryProtocol.validateManifest(manifest)
        val file = packageFile(manifest.jobId)
        require(file.isFile && file.length() == manifest.compressedBytes) {
            "Clone recovery package is incomplete"
        }
        require(CloneHistoryRecoveryProtocol.sha256(file) == manifest.sha256) {
            "Clone recovery package digest does not match its manifest"
        }
        verifiedForRead[manifest.jobId] = VerifiedPackage(
            length = file.length(),
            lastModified = file.lastModified(),
            sha256 = manifest.sha256,
        )
        return file
    }

    private fun requestFile(jobId: String): File = File(jobDirectory(jobId), "request.json")

    private fun manifestFile(jobId: String): File = File(jobDirectory(jobId), "manifest.json")

    private fun packageFile(jobId: String): File = File(jobDirectory(jobId), "package.jsonl.gz")

    private fun jobDirectory(jobId: String, create: Boolean = true): File {
        val validated = CloneHistoryRecoveryProtocol.validateJobId(jobId)
        val parent = jobsDirectory.absoluteFile
        val directory = File(parent, validated).absoluteFile
        require(directory.parentFile == parent) { "Invalid Clone recovery job directory" }
        if (directory.exists()) {
            require(directory.canonicalFile == directory && directory.isDirectory) {
                "Clone recovery job directory is not a real staging directory"
            }
        } else if (create) {
            require(directory.mkdirs()) { "Could not create Clone recovery job directory" }
        }
        return directory
    }

    private fun safeDirectory(relativePath: String): File {
        val directory = safeFile(relativePath)
        require(directory.isDirectory || directory.mkdirs()) {
            "Could not create Clone recovery staging directory"
        }
        return directory
    }

    private fun safeFile(relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/')) {
            "Invalid Clone recovery staging path"
        }
        val root = filesDirectory.canonicalFile
        val candidate = File(root, relativePath).canonicalFile
        require(candidate.path.startsWith(root.path + File.separator)) {
            "Clone recovery path escapes app storage"
        }
        return candidate
    }

    private fun writeOnce(target: File, text: String): File {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAXIMUM_CONTROL_BYTES) { "Clone recovery control record is too large" }
        require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
            "Could not create Clone recovery job directory"
        }
        if (target.exists()) {
            require(target.isFile && target.readBytes().contentEquals(bytes)) {
                "Clone recovery control record changed during a job"
            }
            touchJob(target.parentFile!!.name)
            return target
        }
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        require(!temporary.exists() || temporary.delete()) {
            "Could not replace stale Clone recovery control staging"
        }
        var completed = false
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            require(temporary.renameTo(target)) { "Could not publish Clone recovery control record" }
            completed = true
        } finally {
            if (!completed) temporary.delete()
        }
        touchJob(target.parentFile!!.name)
        return target
    }

    private fun readBoundedText(file: File): String {
        require(file.isFile && file.length() in 1..MAXIMUM_CONTROL_BYTES.toLong()) {
            "Invalid Clone recovery control record"
        }
        return file.readText(StandardCharsets.UTF_8)
    }

    private fun touchJob(jobId: String) {
        jobDirectory(jobId).setLastModified(System.currentTimeMillis())
    }

    private companion object {
        const val MAXIMUM_CONTROL_BYTES = 256 * 1024
    }

    private data class VerifiedPackage(
        val length: Long,
        val lastModified: Long,
        val sha256: String,
    )
}
