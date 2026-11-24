package tk.glucodata.data.journal

import android.content.Context
import tk.glucodata.Applic

/** Which half of a treatment sync cycle broke, so the UI can name it. */
enum class JournalSyncFailure(val storageValue: Int) {
    NONE(0),
    UPLOAD(1),
    DELETE(2);

    companion object {
        fun fromStorage(value: Int): JournalSyncFailure =
            entries.firstOrNull { it.storageValue == value } ?: NONE
    }
}

/**
 * @param lastSuccessAt when Nightscout last accepted a treatment document from us.
 * @param failingSince the start of the current run of failures, 0 while healthy.
 * @param lastAttemptAt when the treatment path was last exercised at all, whatever came of
 *   it. "Failing since" without it reads as something being tried and refused over and over,
 *   which is not the same state as one refusal hours ago that nothing has followed.
 */
data class JournalSyncState(
    val lastSuccessAt: Long = 0L,
    val failingSince: Long = 0L,
    val failureCode: Int = 0,
    val failure: JournalSyncFailure = JournalSyncFailure.NONE,
    val lastAttemptAt: Long = 0L,
    val pendingDeleteCount: Int = 0,
    val pendingDeleteAuthBlocked: Boolean = false,
    val pendingDeleteConfigBlocked: Boolean = false,
    val pendingDeleteNextRetryAt: Long = 0L,
    val pendingDeleteLastError: String = "",
    val lastDeleteConfigKey: String = "",
) {
    val isFailing: Boolean get() = failure != JournalSyncFailure.NONE || pendingDeleteCount > 0
    val hasUnresolvedDeletes: Boolean get() = pendingDeleteCount > 0
}

internal fun applySyncSuccess(
    state: JournalSyncState,
    now: Long,
    acceptedDocument: Boolean
): JournalSyncState = JournalSyncState(
    // A cycle with nothing pending proves the path is not broken, but it is not a send:
    // leaving lastSuccessAt alone keeps "last sent" honest.
    lastSuccessAt = if (acceptedDocument) now else state.lastSuccessAt,
    failingSince = 0L,
    failureCode = 0,
    failure = JournalSyncFailure.NONE,
    lastAttemptAt = now,
    lastDeleteConfigKey = state.lastDeleteConfigKey,
)

internal fun applySyncFailure(
    state: JournalSyncState,
    now: Long,
    code: Int,
    failure: JournalSyncFailure
): JournalSyncState = state.copy(
    // Keep the first failure of the run: "failing since" is the number that shows
    // a sync path has been dead for days rather than for one cycle.
    failingSince = if (state.isFailing && state.failingSince > 0L) state.failingSince else now,
    failureCode = code,
    failure = failure,
    lastAttemptAt = now
)

/**
 * Applies the outcome of one send cycle while surfacing unresolved pending deletions from Room
 * even when every remaining tombstone is cooling off in backoff.
 */
internal fun applySyncCycleOutcome(
    state: JournalSyncState,
    now: Long,
    acceptedDocument: Boolean,
    uploadFailureCode: Int?,
    deleteFailureCode: Int?,
    unresolvedDeletes: DeleteDiagnostics = DeleteDiagnostics(),
    configKey: String = state.lastDeleteConfigKey,
): JournalSyncState {
    val base = when {
        uploadFailureCode != null ->
            applySyncFailure(state, now, uploadFailureCode, JournalSyncFailure.UPLOAD)
        deleteFailureCode != null ->
            applySyncFailure(state, now, deleteFailureCode, JournalSyncFailure.DELETE)
        unresolvedDeletes.hasUnresolved -> {
            val code = unresolvedDeletes.lastErrorCode.takeIf { it != 0 } ?: state.failureCode
            val since = when {
                state.isFailing && state.failingSince > 0L -> state.failingSince
                unresolvedDeletes.oldestDeletedAt > 0L -> unresolvedDeletes.oldestDeletedAt
                else -> now
            }
            state.copy(
                lastSuccessAt = if (acceptedDocument) now else state.lastSuccessAt,
                failingSince = since,
                failureCode = code,
                failure = JournalSyncFailure.DELETE,
                lastAttemptAt = if (state.lastAttemptAt > 0L) state.lastAttemptAt else now,
            )
        }
        else -> applySyncSuccess(state, now, acceptedDocument)
    }
    val updatedSuccessAt = if (acceptedDocument && uploadFailureCode == null && deleteFailureCode == null) {
        now
    } else {
        base.lastSuccessAt
    }
    return base.copy(
        lastSuccessAt = updatedSuccessAt,
        pendingDeleteCount = unresolvedDeletes.pendingCount,
        pendingDeleteAuthBlocked = unresolvedDeletes.authBlocked,
        pendingDeleteConfigBlocked = unresolvedDeletes.configBlocked,
        pendingDeleteNextRetryAt = unresolvedDeletes.nextRetryAt,
        pendingDeleteLastError = unresolvedDeletes.lastErrorMessage,
        lastDeleteConfigKey = configKey.ifEmpty { base.lastDeleteConfigKey },
    )
}

/**
 * Last known health of the Nightscout *treatment* path, which is the JVM-side uploader and
 * therefore invisible to the native uploader status the settings screen otherwise reports
 * (issue #191: that line can read HTTP 200 while treatments have been failing for days).
 *
 * Persisted, because "failing since" is only worth anything if it survives a restart.
 */
object JournalSyncStatus {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_LAST_SUCCESS = "journal_sync_last_success"
    private const val KEY_FAILING_SINCE = "journal_sync_failing_since"
    private const val KEY_FAILURE_CODE = "journal_sync_failure_code"
    private const val KEY_FAILURE_KIND = "journal_sync_failure_kind"
    private const val KEY_LAST_ATTEMPT = "journal_sync_last_attempt"
    private const val KEY_PENDING_DELETE_COUNT = "journal_sync_pending_delete_count"
    private const val KEY_PENDING_DELETE_AUTH_BLOCKED = "journal_sync_pending_delete_auth_blocked"
    private const val KEY_PENDING_DELETE_CONFIG_BLOCKED = "journal_sync_pending_delete_config_blocked"
    private const val KEY_PENDING_DELETE_NEXT_RETRY = "journal_sync_pending_delete_next_retry"
    private const val KEY_PENDING_DELETE_LAST_ERROR = "journal_sync_pending_delete_last_error"
    private const val KEY_LAST_DELETE_CONFIG = "journal_sync_last_delete_config"

    @Volatile
    private var cached: JournalSyncState? = null

    private fun prefs() =
        Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun state(): JournalSyncState = cached ?: load().also { cached = it }

    private fun load(): JournalSyncState = with(prefs()) {
        JournalSyncState(
            lastSuccessAt = getLong(KEY_LAST_SUCCESS, 0L),
            failingSince = getLong(KEY_FAILING_SINCE, 0L),
            failureCode = getInt(KEY_FAILURE_CODE, 0),
            failure = JournalSyncFailure.fromStorage(getInt(KEY_FAILURE_KIND, 0)),
            lastAttemptAt = getLong(KEY_LAST_ATTEMPT, 0L),
            pendingDeleteCount = getInt(KEY_PENDING_DELETE_COUNT, 0),
            pendingDeleteAuthBlocked = getBoolean(KEY_PENDING_DELETE_AUTH_BLOCKED, false),
            pendingDeleteConfigBlocked = getBoolean(KEY_PENDING_DELETE_CONFIG_BLOCKED, false),
            pendingDeleteNextRetryAt = getLong(KEY_PENDING_DELETE_NEXT_RETRY, 0L),
            pendingDeleteLastError = getString(KEY_PENDING_DELETE_LAST_ERROR, "").orEmpty(),
            lastDeleteConfigKey = getString(KEY_LAST_DELETE_CONFIG, "").orEmpty(),
        )
    }

    fun recordSuccess(now: Long, acceptedDocument: Boolean) {
        store(applySyncSuccess(state(), now, acceptedDocument))
    }

    fun recordFailure(now: Long, code: Int, failure: JournalSyncFailure) {
        store(applySyncFailure(state(), now, code, failure))
    }

    fun recordCycleOutcome(
        now: Long,
        acceptedDocument: Boolean,
        uploadFailureCode: Int?,
        deleteFailureCode: Int?,
        unresolvedDeletes: DeleteDiagnostics,
        configKey: String = "",
    ) {
        store(
            applySyncCycleOutcome(
                state = state(),
                now = now,
                acceptedDocument = acceptedDocument,
                uploadFailureCode = uploadFailureCode,
                deleteFailureCode = deleteFailureCode,
                unresolvedDeletes = unresolvedDeletes,
                configKey = configKey,
            )
        )
    }

    internal fun resetCacheForTesting() {
        cached = null
    }

    private fun store(next: JournalSyncState) {
        cached = next
        prefs().edit()
            .putLong(KEY_LAST_SUCCESS, next.lastSuccessAt)
            .putLong(KEY_FAILING_SINCE, next.failingSince)
            .putInt(KEY_FAILURE_CODE, next.failureCode)
            .putInt(KEY_FAILURE_KIND, next.failure.storageValue)
            .putLong(KEY_LAST_ATTEMPT, next.lastAttemptAt)
            .putInt(KEY_PENDING_DELETE_COUNT, next.pendingDeleteCount)
            .putBoolean(KEY_PENDING_DELETE_AUTH_BLOCKED, next.pendingDeleteAuthBlocked)
            .putBoolean(KEY_PENDING_DELETE_CONFIG_BLOCKED, next.pendingDeleteConfigBlocked)
            .putLong(KEY_PENDING_DELETE_NEXT_RETRY, next.pendingDeleteNextRetryAt)
            .putString(KEY_PENDING_DELETE_LAST_ERROR, next.pendingDeleteLastError)
            .putString(KEY_LAST_DELETE_CONFIG, next.lastDeleteConfigKey)
            .apply()
    }
}
