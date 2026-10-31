package tk.glucodata.share.ican

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep

@Keep
class ICanHealthCloudSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT, value).apply()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
        }

    var tenantId: String
        get() = prefs.getString(KEY_TENANT_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TENANT_ID, value).apply()
        }

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_ID, value).apply()
        }

    var lastSyncMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_MS, value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "ican_cloud"
        private const val KEY_ACCOUNT = "ican_cloud_account"
        private const val KEY_TOKEN = "ican_cloud_token"
        private const val KEY_REFRESH_TOKEN = "ican_cloud_refresh_token"
        private const val KEY_TENANT_ID = "ican_cloud_tenant_id"
        private const val KEY_USER_ID = "ican_cloud_user_id"
        private const val KEY_LAST_SYNC_MS = "ican_cloud_last_sync_ms"
    }
}
