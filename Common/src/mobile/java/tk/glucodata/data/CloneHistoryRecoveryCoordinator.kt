package tk.glucodata.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.glucodata.Applic
import tk.glucodata.CloneHistoryRecoveryProtocol
import tk.glucodata.CloneRecoveryCategories
import tk.glucodata.CloneRecoveryDirection
import tk.glucodata.CloneRecoveryManifest
import tk.glucodata.CloneRecoveryMode
import tk.glucodata.CloneRecoveryPhase
import tk.glucodata.CloneRecoveryRequest
import tk.glucodata.CloneRecoveryStaging
import tk.glucodata.CloneRecoveryStatus
import tk.glucodata.CloneSensorRegistry
import tk.glucodata.CloneTransport
import tk.glucodata.GlucoseReadingSource

/** Owns the local half of an explicit, resumable Clone recovery operation. */
internal class CloneHistoryRecoveryCoordinator private constructor(
    context: Context,
) {
    private val staging = CloneRecoveryStaging(context.applicationContext.filesDir)
    private val history = CloneHistoryRecoveryStore(
        HistoryDatabase.getInstance(context.applicationContext)
    )
    private val jobsMutex = Mutex()
    private val importMutex = Mutex()

    fun capabilitiesJson(): String = CloneHistoryRecoveryProtocol.encodeCapabilities(
        CloneHistoryRecoveryProtocol.localCapabilities(SUPPORTED_CATEGORIES)
    )

    fun beginRequest(
        direction: CloneRecoveryDirection,
        mode: CloneRecoveryMode,
        includeJournal: Boolean,
    ): CloneRecoveryRequest = CloneRecoveryRequest(
        protocolVersion = CloneHistoryRecoveryProtocol.PROTOCOL_VERSION,
        jobId = CloneHistoryRecoveryProtocol.newJobId(),
        direction = direction,
        mode = mode,
        categories = CloneRecoveryCategories.selected(
            includeJournal = includeJournal,
            includeHypoClassifications = false,
        ),
    ).also(staging::stageRequest)

    /** Creates or reuses a package whose selection was already confirmed locally or by its peer. */
    suspend fun prepareLocalPackage(request: CloneRecoveryRequest): CloneRecoveryManifest =
        jobsMutex.withLock {
            requireSupported(request)
            staging.stageRequest(request)
            staging.existingManifest(request.jobId)?.let { existing ->
                CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(existing, request)
                staging.verifiedPackageFile(existing.jobId)
                return@withLock existing
            }
            val packageFile = staging.packageFileForCreation(request)
            val manifest = history.createPackage(
                file = packageFile,
                direction = request.direction,
                mode = request.mode,
                includeJournal = request.categories and CloneRecoveryCategories.JOURNAL != 0,
                includeHypoClassifications = false,
                jobId = request.jobId,
            )
            staging.stageManifest(manifest, request)
            staging.verifiedPackageFile(manifest.jobId)
            manifest
        }

    /** Accepts a sender-initiated package manifest on the receiving phone. */
    suspend fun prepareIncomingPush(manifestJson: String): CloneRecoveryStatus = jobsMutex.withLock {
        val manifest = CloneHistoryRecoveryProtocol.decodeManifest(manifestJson).also {
            require(it.direction == CloneRecoveryDirection.SEND_TO_RECEIVER) {
                "Clone recovery push has the wrong direction"
            }
        }
        requireSupported(manifest)
        val authenticatedPeerRequest = manifest.asRequest()
        staging.prepareIncoming(manifest, authenticatedPeerRequest)
        staging.readStatus(manifest.jobId)
    }

    /** Accepts a pulled manifest only when it matches this phone's confirmed request. */
    suspend fun prepareIncomingPull(manifestJson: String): CloneRecoveryStatus = jobsMutex.withLock {
        val manifest = CloneHistoryRecoveryProtocol.decodeManifest(manifestJson)
        val request = staging.readRequest(manifest.jobId)
        require(request.direction == CloneRecoveryDirection.RECOVER_FROM_RECEIVER) {
            "Clone recovery pull has the wrong direction"
        }
        requireSupported(request)
        staging.prepareIncoming(manifest, request)
        staging.readStatus(manifest.jobId)
    }

    suspend fun writeIncomingChunk(
        jobId: String,
        offset: Long,
        bytes: ByteArray,
    ): CloneRecoveryStatus = jobsMutex.withLock {
        staging.writeIncomingChunk(jobId, offset, bytes)
        staging.readStatus(jobId)
    }

    suspend fun readPackageChunk(jobId: String, offset: Long, maximumBytes: Int): ByteArray =
        jobsMutex.withLock { staging.readPackageChunk(jobId, offset, maximumBytes) }

    suspend fun status(jobId: String): CloneRecoveryStatus =
        jobsMutex.withLock { staging.readStatus(jobId) }

    /**
     * Durably accepts the commit before any asynchronous import is launched. VERIFYING
     * and IMPORTING results are safe to resume; terminal results must only be polled.
     */
    suspend fun beginIncomingCommit(commitJson: String): CloneRecoveryStaging.CommitStart =
        jobsMutex.withLock {
            val commit = CloneHistoryRecoveryProtocol.decodeCommit(commitJson)
            staging.beginCommit(commit)
        }

    /**
     * Performs digest verification and Room import away from the native receiver lock.
     * The caller supplies the live receiver gate, which is checked immediately before
     * the job enters its non-cancellable IMPORTING phase.
     */
    suspend fun finishIncomingCommit(
        start: CloneRecoveryStaging.CommitStart,
        cloneSource: String,
        receiverEnabled: () -> Boolean,
    ): CloneRecoveryStatus {
        if (!start.shouldImport) return start.status
        require(GlucoseReadingSource.cloneTransport(cloneSource) != null) {
            "Clone recovery requires a Clone delivery source"
        }
        return try {
            importMutex.withLock importLock@{
                val prepared = jobsMutex.withLock {
                    val current = staging.readStatus(start.status.jobId)
                    if (current.phase.isTerminal) {
                        null
                    } else {
                        require(current.phase == CloneRecoveryPhase.VERIFYING ||
                            current.phase == CloneRecoveryPhase.IMPORTING
                        ) {
                            "Clone recovery job is not ready to import"
                        }
                        require(receiverEnabled()) { "Clone reception is disabled" }
                        staging.beginImport(current.jobId)
                    }
                }
                if (prepared == null) {
                    return@importLock jobsMutex.withLock {
                        staging.readStatus(start.status.jobId)
                    }
                }
                val manifest = requireNotNull(prepared.manifest) {
                    "Clone recovery manifest disappeared before import"
                }
                val packageFile = requireNotNull(prepared.packageFile) {
                    "Clone recovery package disappeared before import"
                }
                history.importPackage(
                    file = packageFile,
                    manifest = manifest,
                    recoverySource = cloneSource,
                )
                jobsMutex.withLock { staging.completeImport(manifest.jobId) }
            }
        } catch (error: Throwable) {
            jobsMutex.withLock {
                runCatching { staging.fail(start.status.jobId, error.message) }
            }
            throw error
        }
    }

    suspend fun commitIncoming(
        commitJson: String,
        cloneSource: String,
        receiverEnabled: () -> Boolean,
    ): CloneRecoveryStatus {
        val start = beginIncomingCommit(commitJson)
        return finishIncomingCommit(start, cloneSource, receiverEnabled)
    }

    suspend fun cancelIncoming(cancelJson: String): CloneRecoveryStatus =
        jobsMutex.withLock {
            val cancel = CloneHistoryRecoveryProtocol.decodeCancel(cancelJson)
            staging.cancel(cancel)
        }

    suspend fun failIncoming(jobId: String, error: String?): CloneRecoveryStatus =
        jobsMutex.withLock { staging.fail(jobId, error) }

    /*
     * Hard deletion is reserved for untransmitted local preparation. Incoming jobs use
     * cancelIncoming so their terminal status continues to reject replayed commits.
     */
    suspend fun discard(jobId: String): Boolean = jobsMutex.withLock {
        val terminal = staging.existingStatus(jobId)?.phase?.isTerminal == true
        require(!terminal) { "Terminal Clone recovery status must remain until expiry" }
        staging.clearJob(jobId)
    }

    suspend fun cleanExpiredJobs(nowMillis: Long = System.currentTimeMillis()): Int =
        jobsMutex.withLock {
            staging.cleanJobsOlderThan(nowMillis - JOB_RETENTION_MILLIS)
        }

    suspend fun packageFile(jobId: String): File =
        jobsMutex.withLock { staging.verifiedPackageFile(jobId) }

    private fun requireSupported(request: CloneRecoveryRequest) {
        CloneHistoryRecoveryProtocol.validateRequest(request)
        require(request.categories and SUPPORTED_CATEGORIES == request.categories) {
            "Clone recovery category is not available in this build"
        }
    }

    private fun requireSupported(manifest: CloneRecoveryManifest) {
        CloneHistoryRecoveryProtocol.validateManifest(manifest)
        require(manifest.categories and SUPPORTED_CATEGORIES == manifest.categories) {
            "Clone recovery category is not available in this build"
        }
    }

    private fun CloneRecoveryManifest.asRequest() = CloneRecoveryRequest(
        protocolVersion = protocolVersion,
        jobId = jobId,
        direction = direction,
        mode = mode,
        categories = categories,
    )

    companion object {
        private const val JOB_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        private const val SUPPORTED_CATEGORIES =
            CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL

        @Volatile
        private var INSTANCE: CloneHistoryRecoveryCoordinator? = null

        fun getInstance(
            context: Context = Applic.app,
        ): CloneHistoryRecoveryCoordinator = INSTANCE ?: synchronized(this) {
            INSTANCE ?: CloneHistoryRecoveryCoordinator(context).also { INSTANCE = it }
        }
    }
}

/** Native-facing mobile facade. Small staging operations block; database import never does. */
@Keep
object CloneHistoryRecoveryAccess {
    private const val TAG = "CloneHistoryRecovery"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCommits = ConcurrentHashMap<String, Job>()

    private val coordinator: CloneHistoryRecoveryCoordinator
        get() = CloneHistoryRecoveryCoordinator.getInstance()

    @JvmStatic
    fun capabilitiesJson(): String = coordinator.capabilitiesJson()

    @JvmStatic
    fun prepareIncomingPush(manifestJson: String): String = statusResult {
        require(CloneSensorRegistry.isReceptionEnabled()) { "Clone reception is disabled" }
        coordinator.prepareIncomingPush(manifestJson)
    }

    @JvmStatic
    fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): String = statusResult {
        require(CloneSensorRegistry.isReceptionEnabled()) { "Clone reception is disabled" }
        coordinator.writeIncomingChunk(jobId, offset, bytes)
    }

    @JvmStatic
    fun statusJson(jobId: String): String = statusResult { coordinator.status(jobId) }

    @JvmStatic
    fun cancelIncoming(cancelJson: String): String = statusResult {
        coordinator.cancelIncoming(cancelJson)
    }

    /** Returns only after the commit record is durable; completion is polled through statusJson. */
    @JvmStatic
    fun commitIncomingAsync(commitJson: String, transportCode: Int): Boolean {
        val commit = runCatching { CloneHistoryRecoveryProtocol.decodeCommit(commitJson) }
            .getOrElse {
                Log.w(TAG, "Rejected Clone recovery commit", it)
                return false
            }
        activeCommits[commit.jobId]?.let { active ->
            if (active.isActive) return true
            activeCommits.remove(commit.jobId, active)
        }
        if (!CloneSensorRegistry.isReceptionEnabled()) return false
        val start = runCatching {
            runBlocking(Dispatchers.IO) { coordinator.beginIncomingCommit(commitJson) }
        }.getOrElse {
            Log.w(TAG, "Could not stage Clone recovery commit", it)
            return false
        }
        if (!start.shouldImport) return true
        val cloneSource = GlucoseReadingSource.forCloneTransport(
            CloneTransport.fromCode(transportCode)
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                coordinator.finishIncomingCommit(
                    start = start,
                    cloneSource = cloneSource,
                    receiverEnabled = CloneSensorRegistry::isReceptionEnabled,
                )
            }.onFailure { error ->
                Log.e(TAG, "Clone recovery import failed", error)
            }
        }
        val existing = activeCommits.putIfAbsent(commit.jobId, job)
        if (existing != null) {
            job.cancel()
            return true
        }
        job.invokeOnCompletion { activeCommits.remove(commit.jobId, job) }
        job.start()
        return true
    }

    private fun statusResult(block: suspend () -> CloneRecoveryStatus): String =
        runCatching {
            val status = runBlocking(Dispatchers.IO) { block() }
            CloneHistoryRecoveryProtocol.encodeStatus(status)
        }.onFailure { error ->
            Log.w(TAG, "Clone recovery control operation failed", error)
        }.getOrDefault("")

}
