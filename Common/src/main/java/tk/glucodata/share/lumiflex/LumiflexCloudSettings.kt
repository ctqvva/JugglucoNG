package tk.glucodata.share.lumiflex

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep

@Keep
class LumiflexCloudSettings(context: Context) {

    data class Session(val account: String, val token: String, val userId: String, val generation: Long) {
        val signedIn: Boolean get() = token.isNotBlank() && userId.isNotBlank()
    }

    enum class SyncState { IDLE, QUEUED, SYNCING, SYNCED, WAITING_FOR_SENSOR, ERROR, SIGN_IN_REQUIRED }

    data class SyncStatus(
        val state: SyncState = SyncState.IDLE,
        val pending: Int = 0,
        val lastAttemptMs: Long = 0,
        val lastSuccessMs: Long = 0,
        val message: String = "",
        val retrying: Boolean = false,
        val boundSensors: List<String> = emptyList(),
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    val followMode: Boolean get() = prefs.getBoolean("follow_mode", false)
    val modeChosen: Boolean get() = prefs.getBoolean("mode_chosen", false)
    val providerId: String get() = prefs.getString("follow_provider", "").orEmpty()
    val providerName: String get() = prefs.getString("follow_name", "").orEmpty()
    val uploadEnabled: Boolean get() = enabled && !followMode
    val lastReceivedMs: Long get() = prefs.getLong("follow_last_reading_ms", 0L)
    val selectionPending: Boolean get() = prefs.getBoolean("follow_selection_pending", true)
    fun selectionApplied(session: Session) {
        withCurrentSession(session) { prefs.edit().putBoolean("follow_selection_pending", false).apply() }
    }
    fun recordReceived(session: Session, timestampMs: Long) {
        withCurrentSession(session) { prefs.edit().putLong("follow_last_reading_ms", timestampMs).apply() }
    }

    /** A role/provider change retires in-flight work just like a session replacement. */
    fun selectMode(follow: Boolean, provider: FollowerInfo? = null) = synchronized(sessionLock) {
        prefs.edit().putBoolean("follow_mode", follow).putBoolean("mode_chosen", true)
            .putBoolean("follow_selection_pending", true)
            .putString("follow_provider", if (follow) provider?.dataProviderId.orEmpty() else "")
            .putString("follow_name", if (follow) provider?.providerAlias?.ifBlank { provider.providerUserName }.orEmpty() else "")
            .putLong(KEY_SESSION_GENERATION, nextGeneration())
            .putString("sync_state", SyncState.IDLE.name).putInt("pending", 0)
            .putString("sync_message", "").putLong(KEY_LAST_SYNC_MS, 0L)
            .putLong("follow_last_reading_ms", 0L).apply()
    }

    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var lastSyncMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_MS, value).apply()

    /** Monotonic identity of the stored session, bumped on every login and logout. */
    val sessionGeneration: Long
        get() = prefs.getLong(KEY_SESSION_GENERATION, 0L)

    private fun nextGeneration(): Long = maxOf(sessionGeneration + 1, System.currentTimeMillis())

    fun session(): Session = synchronized(sessionLock) { Session(account, token, userId, sessionGeneration) }

    fun isCurrent(session: Session): Boolean = synchronized(sessionLock) { session() == session }

    /** Only local queue/prefs operations belong here; never hold this lock during network I/O. */
    internal fun withCurrentSession(session: Session, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (!isCurrent(session)) return false
        action()
        true
    }

    val sessionExpired: Boolean get() = prefs.getBoolean("session_expired", false)

    var passwordSetupRequired: Boolean
        get() = prefs.getBoolean("password_setup_required", false)
        set(value) = prefs.edit().putBoolean("password_setup_required", value).apply()

    var nextSmsAllowedMs: Long
        get() = prefs.getLong("next_sms_allowed_ms", 0)
        set(value) = prefs.edit().putLong("next_sms_allowed_ms", value).apply()

    fun syncStatus(): SyncStatus = SyncStatus(
        state = runCatching { SyncState.valueOf(prefs.getString("sync_state", "IDLE")!!) }.getOrDefault(SyncState.IDLE),
        pending = prefs.getInt("pending", 0),
        lastAttemptMs = prefs.getLong("last_attempt_ms", 0),
        lastSuccessMs = lastSyncMs,
        message = prefs.getString("sync_message", "").orEmpty(),
        retrying = prefs.getBoolean("sync_retrying", false),
        boundSensors = prefs.getStringSet("bound_sensors", emptySet()).orEmpty().sorted(),
    )

    fun recordBindings(session: Session, serials: Set<String>) {
        withCurrentSession(session) { prefs.edit().putStringSet("bound_sensors", serials).apply() }
    }

    fun recordSync(session: Session, state: SyncState, pending: Int, message: String = "", uploaded: Int = 0, retrying: Boolean = false) {
        synchronized(sessionLock) {
            if (!isCurrent(session)) return
            val editor = prefs.edit().putString("sync_state", state.name)
                .putInt("pending", pending).putString("sync_message", message).putBoolean("sync_retrying", retrying)
            if (state == SyncState.SYNCING) editor.putLong("last_attempt_ms", System.currentTimeMillis())
            if (uploaded > 0) editor.putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
            editor.apply()
        }
    }

    fun startSessionIfCurrent(expected: Session, account: String, token: String, userId: String): Boolean =
        synchronized(sessionLock) {
            if (!isCurrent(expected)) return false
            startSession(account, token, userId)
            true
        }

    fun invalidateSession(expected: Session): Boolean = synchronized(sessionLock) {
        if (!isCurrent(expected)) return false
        endSession()
        prefs.edit().putBoolean("session_expired", true)
            .putString("sync_state", SyncState.SIGN_IN_REQUIRED.name).apply()
        true
    }

    /** Commits account, token and user id together as one authenticated session. */
    fun startSession(account: String, token: String, userId: String) {
        synchronized(sessionLock) {
            val accountChanged = this.account != account
            prefs.edit()
                .putString(KEY_ACCOUNT, account)
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER_ID, userId)
                .putLong(KEY_SESSION_GENERATION, nextGeneration())
                .putBoolean("session_expired", false)
                .putBoolean("password_setup_required", false)
                .putString("sync_state", SyncState.IDLE.name)
                .putString("sync_message", "")
                .putBoolean("sync_retrying", false)
                .putLong(KEY_LAST_SYNC_MS, if (accountChanged) 0L else lastSyncMs)
                .putInt("pending", if (accountChanged) 0 else syncStatus().pending)
                .putStringSet("bound_sensors", if (accountChanged) emptySet() else syncStatus().boundSensors.toSet())
                .putLong("follow_last_reading_ms", if (accountChanged) 0L else lastReceivedMs)
                .putBoolean("follow_selection_pending", accountChanged || selectionPending)
                .putBoolean("mode_chosen", if (accountChanged) false else modeChosen)
                .putBoolean("follow_mode", if (accountChanged) false else followMode)
                .putString("follow_provider", if (accountChanged) "" else providerId)
                .putString("follow_name", if (accountChanged) "" else providerName)
                .apply()
        }
    }

    /** Drops the credentials of the current session; [account] stays for the next login. */
    fun endSession() {
        synchronized(sessionLock) {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_USER_ID)
                .putLong(KEY_SESSION_GENERATION, nextGeneration())
                .putBoolean("session_expired", false)
                .putString("sync_state", SyncState.IDLE.name)
                .putString("sync_message", "")
                .putBoolean("sync_retrying", false)
                .apply()
        }
    }

    fun isSignedIn(): Boolean = session().signedIn

    fun clear() {
        synchronized(sessionLock) {
            val generation = nextGeneration()
            prefs.edit().clear().putLong(KEY_SESSION_GENERATION, generation).apply()
        }
    }

    companion object {
        private val sessionLock = Any()
        private const val PREFS = "lumiflex_cloud"
        private const val KEY_ENABLED = "lumiflex_cloud_enabled"
        private const val KEY_ACCOUNT = "lumiflex_cloud_account"
        private const val KEY_TOKEN = "lumiflex_cloud_token"
        private const val KEY_USER_ID = "lumiflex_cloud_user_id"
        private const val KEY_LAST_SYNC_MS = "lumiflex_cloud_last_sync_ms"
        private const val KEY_SESSION_GENERATION = "lumiflex_cloud_session_generation"

        @JvmStatic
        fun isEnabled(context: Context): Boolean = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

        @JvmStatic
        fun isSetUp(context: Context): Boolean = isEnabled(context) ||
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .let { it.getString(KEY_ACCOUNT, "").orEmpty().isNotBlank() || it.contains(LumiflexCloudCredentials.KEY_DRAFT) }
    }
}
