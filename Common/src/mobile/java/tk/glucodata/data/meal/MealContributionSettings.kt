package tk.glucodata.data.meal

import android.content.Context
import java.util.UUID

/**
 * Opt-in settings for sending label-photo products back to Open Food Facts. Kept in their own
 * preferences file (credentials do not belong in the generic dashboard prefs), read on demand.
 */
data class MealContributionSettings(
    val enabled: Boolean,
    val userId: String,
    val password: String,
    val uploadPhotos: Boolean,
    /** Stable random id per install, sent as OFF's `app_uuid` so moderators can group edits. */
    val appUuid: String
) {
    val isUsable: Boolean get() = enabled && userId.isNotBlank() && password.isNotBlank()

    companion object {
        private const val PREFS = "meal_off_contribution"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_USER = "user_id"
        private const val KEY_PASSWORD = "password"
        private const val KEY_PHOTOS = "upload_photos"
        private const val KEY_UUID = "app_uuid"

        fun load(context: Context): MealContributionSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var uuid = prefs.getString(KEY_UUID, null)
            if (uuid.isNullOrBlank()) {
                uuid = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_UUID, uuid).apply()
            }
            return MealContributionSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                userId = prefs.getString(KEY_USER, "").orEmpty(),
                password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
                uploadPhotos = prefs.getBoolean(KEY_PHOTOS, false),
                appUuid = uuid
            )
        }

        fun save(context: Context, settings: MealContributionSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putString(KEY_USER, settings.userId.trim())
                .putString(KEY_PASSWORD, settings.password)
                .putBoolean(KEY_PHOTOS, settings.uploadPhotos)
                .putString(KEY_UUID, settings.appUuid)
                .apply()
        }
    }
}
