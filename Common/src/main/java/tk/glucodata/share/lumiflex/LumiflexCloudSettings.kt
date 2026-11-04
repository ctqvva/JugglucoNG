package tk.glucodata.share.lumiflex

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep

@Keep
class LumiflexCloudSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

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

    /** Commits account, token and user id together as one authenticated session. */
    fun startSession(account: String, token: String, userId: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putLong(KEY_SESSION_GENERATION, nextGeneration())
            .apply()
    }

    /** Drops the credentials of the current session; [account] stays for the next login. */
    fun endSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .putLong(KEY_SESSION_GENERATION, nextGeneration())
            .apply()
    }

    fun isSignedIn(): Boolean = token.isNotBlank() && userId.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
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
                .getString(KEY_ACCOUNT, "").orEmpty().isNotBlank()
    }
}
