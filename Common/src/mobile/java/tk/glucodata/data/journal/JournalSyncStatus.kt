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
 */
data class JournalSyncState(
    val lastSuccessAt: Long = 0L,
    val failingSince: Long = 0L,
    val failureCode: Int = 0,
    val failure: JournalSyncFailure = JournalSyncFailure.NONE
) {
    val isFailing: Boolean get() = failure != JournalSyncFailure.NONE
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
    failure = JournalSyncFailure.NONE
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
    failure = failure
)

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
            failure = JournalSyncFailure.fromStorage(getInt(KEY_FAILURE_KIND, 0))
        )
    }

    fun recordSuccess(now: Long, acceptedDocument: Boolean) {
        store(applySyncSuccess(state(), now, acceptedDocument))
    }

    fun recordFailure(now: Long, code: Int, failure: JournalSyncFailure) {
        store(applySyncFailure(state(), now, code, failure))
    }

    private fun store(next: JournalSyncState) {
        cached = next
        prefs().edit()
            .putLong(KEY_LAST_SUCCESS, next.lastSuccessAt)
            .putLong(KEY_FAILING_SINCE, next.failingSince)
            .putInt(KEY_FAILURE_CODE, next.failureCode)
            .putInt(KEY_FAILURE_KIND, next.failure.storageValue)
            .apply()
    }
}
