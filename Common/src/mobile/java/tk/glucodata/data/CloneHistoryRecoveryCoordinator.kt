package tk.glucodata.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.glucodata.Applic
import tk.glucodata.CloneHistoryRecoveryProtocol
import tk.glucodata.CloneRecoveryCategories
import tk.glucodata.CloneRecoveryDirection
import tk.glucodata.CloneRecoveryManifest
import tk.glucodata.CloneRecoveryMode
import tk.glucodata.CloneRecoveryRequest
import tk.glucodata.CloneRecoveryStaging
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
            manifest
        }

    /** Accepts a sender-initiated package manifest on the receiving phone. */
    suspend fun prepareIncomingPush(manifestJson: String): Long = jobsMutex.withLock {
        val manifest = CloneHistoryRecoveryProtocol.decodeManifest(manifestJson).also {
            require(it.direction == CloneRecoveryDirection.SEND_TO_RECEIVER) {
                "Clone recovery push has the wrong direction"
            }
        }
        requireSupported(manifest)
        val authenticatedPeerRequest = manifest.asRequest()
        staging.prepareIncoming(manifest, authenticatedPeerRequest)
    }

    /** Accepts a pulled manifest only when it matches this phone's confirmed request. */
    suspend fun prepareIncomingPull(manifestJson: String): Long = jobsMutex.withLock {
        val manifest = CloneHistoryRecoveryProtocol.decodeManifest(manifestJson)
        val request = staging.readRequest(manifest.jobId)
        require(request.direction == CloneRecoveryDirection.RECOVER_FROM_RECEIVER) {
            "Clone recovery pull has the wrong direction"
        }
        requireSupported(request)
        staging.prepareIncoming(manifest, request)
    }

    suspend fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): Long =
        jobsMutex.withLock { staging.writeIncomingChunk(jobId, offset, bytes) }

    suspend fun readPackageChunk(jobId: String, offset: Long, maximumBytes: Int): ByteArray =
        jobsMutex.withLock { staging.readPackageChunk(jobId, offset, maximumBytes) }

    suspend fun commitIncoming(jobId: String, cloneSource: String): CloneRecoveryManifest =
        jobsMutex.withLock {
            require(GlucoseReadingSource.cloneTransport(cloneSource) != null) {
                "Clone recovery requires a Clone delivery source"
            }
            val request = staging.readRequest(jobId)
            val manifest = staging.readManifest(jobId)
            requireSupported(request)
            CloneHistoryRecoveryProtocol.requireManifestMatchesRequest(manifest, request)
            val packageFile = staging.verifiedPackageFile(jobId)
            history.importPackage(
                file = packageFile,
                manifest = manifest,
                recoverySource = cloneSource,
            )
            require(staging.clearJob(jobId)) { "Could not clear committed Clone recovery job" }
            manifest
        }

    suspend fun discard(jobId: String): Boolean =
        jobsMutex.withLock { staging.clearJob(jobId) }

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
