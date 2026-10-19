package tk.glucodata

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private, resumable staging for one explicitly confirmed Clone recovery job. */
internal class CloneRecoveryStaging(
    private val filesDirectory: File,
) {
    private val verifiedForRead = mutableMapOf<String, VerifiedPackage>()
    private val jobsDirectory: File by lazy {
        safeDirectory(CloneHistoryRecoveryProtocol.JOB_PATH_PREFIX)
    }
    private val outgoingDirectory: File by lazy {
        safeDirectory(CloneOutgoingRecoveryProtocol.OUTGOING_PATH_PREFIX)
    }

    @Synchronized
    fun stageRequest(request: CloneRecoveryRequest): File {
        CloneHistoryRecoveryProtocol.validateRequest(request)
        require(existingStatus(request.jobId)?.phase?.isTerminal != true) {
            "Clone recovery job has already ended"
        }
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

    @Synchronized
    fun readStatus(jobId: String): CloneRecoveryStatus =
        CloneHistoryRecoveryProtocol.decodeStatus(readBoundedText(statusFile(jobId)))

    @Synchronized
    fun existingStatus(jobId: String): CloneRecoveryStatus? {
        val file = statusFile(jobId, createDirectory = false)
        return if (file.isFile) {
            CloneHistoryRecoveryProtocol.decodeStatus(readBoundedText(file))
        } else {
            null
        }
    }

    @Synchronized
    fun writeOutgoingState(state: CloneOutgoingState): File {
        CloneOutgoingRecoveryProtocol.validateState(state)
        val current = existingOutgoingState(state.iceLabel)
        require(current == null || current.iceLabel == state.iceLabel) {
            "Outgoing Clone recovery label binding changed"
        }
        require(current?.jobId == null || current.jobId == state.jobId) {
            "Outgoing Clone recovery job binding changed"
        }
        require(current?.phase?.isTerminal != true || current == state) {
            "Terminal outgoing Clone recovery state cannot change"
        }
        state.jobId?.let { jobId ->
            require(listOutgoingStates().none { existing ->
                existing.iceLabel != state.iceLabel && existing.jobId == jobId
            }) { "Outgoing Clone recovery job is already bound to another ICE label" }
        }
        return atomicRewrite(
            outgoingStateFile(state.iceLabel),
            CloneOutgoingRecoveryProtocol.encodeState(state),
        )
    }

    @Synchronized
    fun readOutgoingState(iceLabel: String): CloneOutgoingState =
        existingOutgoingState(iceLabel)
            ?: throw IllegalArgumentException("Outgoing Clone recovery job was not found")

    @Synchronized
    fun existingOutgoingState(iceLabel: String): CloneOutgoingState? {
        val file = outgoingStateFile(iceLabel)
        if (!file.isFile) return null
        return CloneOutgoingRecoveryProtocol.decodeState(readBoundedText(file)).also { state ->
            require(state.iceLabel == CloneOutgoingRecoveryProtocol.validateIceLabel(iceLabel)) {
                "Outgoing Clone recovery label binding changed"
            }
        }
    }

    @Synchronized
    fun listOutgoingStates(): List<CloneOutgoingState> {
        if (!outgoingDirectory.isDirectory) return emptyList()
        return outgoingDirectory.listFiles().orEmpty()
            .asSequence()
            .filter { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
            .mapNotNull { candidate ->
                runCatching {
                    require(candidate.canonicalFile == candidate.absoluteFile &&
                        candidate.canonicalFile.parentFile == outgoingDirectory.canonicalFile
                    ) { "Invalid outgoing Clone recovery state file" }
                    val state = CloneOutgoingRecoveryProtocol.decodeState(readBoundedText(candidate))
                    require(outgoingStateFile(state.iceLabel).name == candidate.name) {
                        "Outgoing Clone recovery state has the wrong storage key"
                    }
                    state
                }.getOrNull()
            }
            .sortedBy(CloneOutgoingState::iceLabel)
            .toList()
    }

    @Synchronized
    fun clearOutgoingState(iceLabel: String): Boolean {
        val file = outgoingStateFile(iceLabel)
        return !file.exists() || file.delete()
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
        CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, confirmedRequest)
        existingStatus(manifest.jobId)?.let { status ->
            CloneHistoryRecoveryProtocol.requireStatusMatchesManifest(status, manifest)
            when (status.phase) {
                CloneRecoveryPhase.COMPLETED -> return status.acceptedBytes
                CloneRecoveryPhase.CANCELLED,
                CloneRecoveryPhase.FAILED -> throw IllegalStateException(
                    "Clone recovery job has already ended as ${status.phase.wireValue}"
                )
                CloneRecoveryPhase.VERIFYING,
                CloneRecoveryPhase.IMPORTING -> return status.acceptedBytes
                CloneRecoveryPhase.PREPARING,
                CloneRecoveryPhase.RECEIVING -> Unit
            }
        }
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
        writeStatus(
            CloneHistoryRecoveryProtocol.statusFor(
                manifest = manifest,
                phase = CloneRecoveryPhase.RECEIVING,
                acceptedBytes = file.length(),
            )
        )
        touchJob(manifest.jobId)
        return file.length()
    }

    /** Appends a new chunk or accepts an identical, fully written retry. */
    @Synchronized
    fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): Long {
        val manifest = readManifest(jobId)
        val request = readRequest(jobId)
        CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
        val status = readStatus(jobId)
        CloneHistoryRecoveryProtocol.requireStatusMatchesManifest(status, manifest)
        require(status.phase == CloneRecoveryPhase.RECEIVING) {
            "Clone recovery job is not receiving data"
        }
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
        writeStatus(status.copy(acceptedBytes = file.length()))
        touchJob(jobId)
        return file.length()
    }

    /**
     * Durably accepts an exact commit record. VERIFYING and IMPORTING jobs may be
     * resumed after process death; terminal jobs are never started a second time.
     */
    @Synchronized
    fun beginCommit(commit: CloneRecoveryCommit): CommitStart {
        CloneHistoryRecoveryProtocol.validateCommit(commit)
        val status = readStatus(commit.jobId)
        requireControlMatchesStatus(commit.protocolVersion, commit.jobId, commit.sha256, status)
        when (status.phase) {
            CloneRecoveryPhase.COMPLETED,
            CloneRecoveryPhase.CANCELLED,
            CloneRecoveryPhase.FAILED -> return CommitStart(status, shouldImport = false)
            CloneRecoveryPhase.VERIFYING,
            CloneRecoveryPhase.IMPORTING -> {
                requireStagedCommit(commit)
                return CommitStart(status, shouldImport = true)
            }
            CloneRecoveryPhase.PREPARING -> throw IllegalStateException(
                "Clone recovery package is not ready to commit"
            )
            CloneRecoveryPhase.RECEIVING -> Unit
        }
        require(status.acceptedBytes == status.totalBytes) {
            "Clone recovery package is incomplete"
        }
        val manifest = readManifest(commit.jobId)
        val request = readRequest(commit.jobId)
        CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
        CloneHistoryRecoveryProtocol.requireStatusMatchesManifest(status, manifest)
        writeOnce(commitFile(commit.jobId), CloneHistoryRecoveryProtocol.encodeCommit(commit))
        return CommitStart(
            status = transitionStatus(commit.jobId, CloneRecoveryPhase.VERIFYING),
            shouldImport = true,
        )
    }

    @Synchronized
    fun beginImport(jobId: String): ImportStart {
        val status = readStatus(jobId)
        if (status.phase.isTerminal) {
            return ImportStart(status, manifest = null, packageFile = null)
        }
        require(status.phase == CloneRecoveryPhase.VERIFYING ||
            status.phase == CloneRecoveryPhase.IMPORTING
        ) {
            "Clone recovery job is not ready to import"
        }
        val manifest = readManifest(jobId)
        CloneHistoryRecoveryProtocol.requireStatusMatchesManifest(status, manifest)
        requireStagedCommit(
            CloneRecoveryCommit(
                protocolVersion = status.protocolVersion,
                jobId = status.jobId,
                sha256 = status.sha256,
            )
        )
        val packageFile = verifiedPackageFile(manifest)
        return ImportStart(
            status = if (status.phase == CloneRecoveryPhase.VERIFYING) {
                transitionStatus(jobId, CloneRecoveryPhase.IMPORTING)
            } else {
                status
            },
            manifest = manifest,
            packageFile = packageFile,
        )
    }

    @Synchronized
    fun completeImport(jobId: String): CloneRecoveryStatus {
        val status = readStatus(jobId)
        if (status.phase == CloneRecoveryPhase.COMPLETED) return status
        require(status.phase == CloneRecoveryPhase.IMPORTING) {
            "Clone recovery job is not importing"
        }
        return finishTerminal(
            status.copy(phase = CloneRecoveryPhase.COMPLETED, error = null)
        )
    }

    @Synchronized
    fun fail(jobId: String, error: String?): CloneRecoveryStatus {
        val status = readStatus(jobId)
        if (status.phase.isTerminal) return status
        return finishTerminal(
            status.copy(
                phase = CloneRecoveryPhase.FAILED,
                error = CloneHistoryRecoveryProtocol.boundedStatusError(error),
            )
        )
    }

    @Synchronized
    fun cancel(cancel: CloneRecoveryCancel): CloneRecoveryStatus {
        CloneHistoryRecoveryProtocol.validateCancel(cancel)
        val status = readStatus(cancel.jobId)
        requireControlMatchesStatus(cancel.protocolVersion, cancel.jobId, cancel.sha256, status)
        if (status.phase == CloneRecoveryPhase.CANCELLED) return status
        require(!status.phase.isTerminal && status.phase != CloneRecoveryPhase.IMPORTING) {
            "Clone recovery can only be cancelled before import"
        }
        writeOnce(cancelFile(cancel.jobId), CloneHistoryRecoveryProtocol.encodeCancel(cancel))
        return finishTerminal(
            status.copy(phase = CloneRecoveryPhase.CANCELLED, error = null)
        )
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

    fun relativeStatusPath(jobId: String): String =
        CloneHistoryRecoveryProtocol.jobStatusPath(jobId)

    private fun writeStatus(status: CloneRecoveryStatus): File {
        CloneHistoryRecoveryProtocol.validateStatus(status)
        val target = statusFile(status.jobId)
        existingStatus(status.jobId)?.let { current ->
            requireSameStatusIdentity(current, status)
            require(validTransition(current, status)) {
                "Invalid Clone recovery status transition"
            }
        }
        return atomicRewrite(target, CloneHistoryRecoveryProtocol.encodeStatus(status)).also {
            touchJob(status.jobId)
        }
    }

    private fun transitionStatus(
        jobId: String,
        phase: CloneRecoveryPhase,
    ): CloneRecoveryStatus {
        val current = readStatus(jobId)
        return current.copy(phase = phase, error = null).also(::writeStatus)
    }

    private fun finishTerminal(status: CloneRecoveryStatus): CloneRecoveryStatus {
        require(status.phase.isTerminal) { "Clone recovery status is not terminal" }
        writeStatus(status)
        verifiedForRead.remove(status.jobId)
        listOf(
            requestFile(status.jobId),
            manifestFile(status.jobId),
            packageFile(status.jobId),
            commitFile(status.jobId),
            cancelFile(status.jobId),
        ).forEach { file ->
            require(!file.exists() || file.delete()) {
                "Could not remove completed Clone recovery payload"
            }
        }
        touchJob(status.jobId)
        return status
    }

    private fun requireControlMatchesStatus(
        protocolVersion: Int,
        jobId: String,
        sha256: String,
        status: CloneRecoveryStatus,
    ) {
        require(protocolVersion == status.protocolVersion &&
            jobId == status.jobId &&
            sha256 == status.sha256
        ) { "Clone recovery control record does not match its job" }
    }

    private fun requireStagedCommit(expected: CloneRecoveryCommit) {
        val file = commitFile(expected.jobId)
        require(file.isFile) { "Clone recovery commit record is missing" }
        val staged = CloneHistoryRecoveryProtocol.decodeCommit(readBoundedText(file))
        require(staged == expected) { "Clone recovery commit changed during a job" }
    }

    private fun requireSameStatusIdentity(
        current: CloneRecoveryStatus,
        next: CloneRecoveryStatus,
    ) {
        require(current.copy(phase = next.phase, acceptedBytes = next.acceptedBytes, error = next.error) == next) {
            "Clone recovery status identity changed during a job"
        }
    }

    private fun validTransition(
        current: CloneRecoveryStatus,
        next: CloneRecoveryStatus,
    ): Boolean {
        if (current.phase.isTerminal) return current == next
        if (current.phase == next.phase) {
            return current.phase == CloneRecoveryPhase.RECEIVING &&
                next.acceptedBytes >= current.acceptedBytes
        }
        if (next.phase == CloneRecoveryPhase.FAILED) return true
        if (next.phase == CloneRecoveryPhase.CANCELLED) {
            return current.phase != CloneRecoveryPhase.IMPORTING
        }
        return when (current.phase) {
            CloneRecoveryPhase.PREPARING -> next.phase == CloneRecoveryPhase.RECEIVING
            CloneRecoveryPhase.RECEIVING -> next.phase == CloneRecoveryPhase.VERIFYING
            CloneRecoveryPhase.VERIFYING -> next.phase == CloneRecoveryPhase.IMPORTING
            CloneRecoveryPhase.IMPORTING -> next.phase == CloneRecoveryPhase.COMPLETED
            CloneRecoveryPhase.COMPLETED,
            CloneRecoveryPhase.CANCELLED,
            CloneRecoveryPhase.FAILED -> false
        }
    }

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

    private fun statusFile(jobId: String, createDirectory: Boolean = true): File =
        File(jobDirectory(jobId, createDirectory), "status.json")

    private fun commitFile(jobId: String): File = File(jobDirectory(jobId), "commit.json")

    private fun cancelFile(jobId: String): File = File(jobDirectory(jobId), "cancel.json")

    private fun outgoingStateFile(iceLabel: String): File {
        val validated = CloneOutgoingRecoveryProtocol.validateIceLabel(iceLabel)
        val storageKey = CloneHistoryRecoveryProtocol.sha256(
            validated.toByteArray(StandardCharsets.UTF_8).inputStream()
        )
        val parent = outgoingDirectory.absoluteFile
        return File(parent, "$storageKey.json").absoluteFile.also { candidate ->
            require(candidate.parentFile == parent) { "Invalid outgoing Clone recovery state path" }
        }
    }

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
        require(bytes.size <= CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES) {
            "Clone recovery control record is too large"
        }
        require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
            "Could not create Clone recovery job directory"
        }
        if (target.exists()) {
            require(target.isFile &&
                target.length() <= CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES &&
                target.readBytes().contentEquals(bytes)
            ) {
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
        return target
    }

    private fun atomicRewrite(target: File, text: String): File {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES) {
            "Clone recovery control record is too large"
        }
        require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
            "Could not create Clone recovery job directory"
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
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            completed = true
        } finally {
            if (!completed) temporary.delete()
        }
        return target
    }

    private fun readBoundedText(file: File): String {
        require(file.isFile &&
            file.length() in 1..CloneHistoryRecoveryProtocol.MAXIMUM_CONTROL_BYTES.toLong()
        ) {
            "Invalid Clone recovery control record"
        }
        return file.readText(StandardCharsets.UTF_8)
    }

    private fun touchJob(jobId: String) {
        jobDirectory(jobId).setLastModified(System.currentTimeMillis())
    }

    private data class VerifiedPackage(
        val length: Long,
        val lastModified: Long,
        val sha256: String,
    )

    data class CommitStart(
        val status: CloneRecoveryStatus,
        val shouldImport: Boolean,
    )

    data class ImportStart(
        val status: CloneRecoveryStatus,
        val manifest: CloneRecoveryManifest?,
        val packageFile: File?,
    )
}
