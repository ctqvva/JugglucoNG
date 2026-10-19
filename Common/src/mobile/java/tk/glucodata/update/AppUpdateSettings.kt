package tk.glucodata.update

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import tk.glucodata.BuildConfig

/**
 * Preferences for the in-app updater, plus the cached result of the last check.
 *
 * Caching the found release matters for a background check: the worker runs while no UI exists,
 * and the settings card has to be able to say "1.2.0-Alpha is available" without going back to
 * the network the moment the user opens Settings.
 */
object AppUpdateSettings {

    private const val PREFS = "app_updates"

    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_INTRO_ANSWERED = "intro_answered"
    private const val KEY_SOURCE = "update_source"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_CACHED_UPDATE = "cached_update"
    private const val KEY_DISMISSED = "dismissed_update"
    private const val KEY_DISMISSED_AT = "dismissed_update_at"

    /**
     * How long an X on the update card silences that release for.
     *
     * Permanent dismissal was wrong: one stray tap and a pending update never announces itself
     * again, which for a release that fixes something is worse than a card the user has already
     * learned to ignore. A week is long enough not to nag and short enough that the update is
     * not lost.
     */
    private const val DISMISSAL_TTL_MS = 7L * 24 * 60 * 60 * 1000

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether the one-time "JugglucoNG can check for updates" card has been answered.
     * Unanswered means the card is still owed to the user — including to users upgrading from a
     * build that had no updater at all.
     */
    fun isIntroAnswered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTRO_ANSWERED, false)

    fun setIntroAnswered(context: Context, answered: Boolean) {
        prefs(context).edit().putBoolean(KEY_INTRO_ANSWERED, answered).apply()
    }

    /**
     * Off until the user says yes. An update check is an outbound request that reveals the
     * device's IP to the update source, so it is opt-in rather than opt-out.
     */
    fun isAutoCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK, false)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_CHECK, enabled)
            .putBoolean(KEY_INTRO_ANSWERED, true)
            .apply()
    }

    /** The https URL releases are read from. Defaults to this build's own project. */
    fun updateSource(context: Context): String {
        val stored = prefs(context).getString(KEY_SOURCE, null)
        return stored?.takeIf { UpdateSource.isValid(it) } ?: defaultUpdateSource
    }

    val defaultUpdateSource: String get() = "https://github.com/${BuildConfig.UPDATE_REPO}"

    fun isDefaultUpdateSource(context: Context): Boolean =
        updateSource(context) == defaultUpdateSource

    /** Pass null to go back to the default. Invalid values are ignored rather than stored. */
    fun setUpdateSource(context: Context, url: String?) {
        val editor = prefs(context).edit()
        val cleaned = url?.let(UpdateSource::sanitize)
        if (cleaned == null || cleaned == defaultUpdateSource) {
            editor.remove(KEY_SOURCE)
        } else if (UpdateSource.isValid(cleaned)) {
            editor.putString(KEY_SOURCE, cleaned)
        } else {
            return
        }
        editor.apply()
    }

    fun lastCheckAtMillis(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK_AT, 0L)

    fun lastError(context: Context): UpdateError? =
        prefs(context).getString(KEY_LAST_ERROR, null)
            ?.let { name -> UpdateError.entries.firstOrNull { it.name == name } }

    /** Records the outcome of a check; a successful check clears any previous error. */
    fun recordCheck(context: Context, result: UpdateCheckResult, atMillis: Long) {
        val editor = prefs(context).edit().putLong(KEY_LAST_CHECK_AT, atMillis)
        when (result) {
            is UpdateCheckResult.Available -> editor
                .remove(KEY_LAST_ERROR)
                .putString(KEY_CACHED_UPDATE, encode(result.update))
            UpdateCheckResult.UpToDate -> editor
                .remove(KEY_LAST_ERROR)
                .remove(KEY_CACHED_UPDATE)
            is UpdateCheckResult.Failed -> editor.putString(KEY_LAST_ERROR, result.error.name)
        }
        editor.apply()
    }

    fun cachedUpdate(context: Context): AvailableUpdate? =
        prefs(context).getString(KEY_CACHED_UPDATE, null)?.let(::decode)

    fun clearCachedUpdate(context: Context) {
        prefs(context).edit().remove(KEY_CACHED_UPDATE).apply()
    }

    /** Banner dismissal is per release and expires, so a still-pending update comes back. */
    fun dismissedIdentity(context: Context): String? {
        val prefs = prefs(context)
        val identity = prefs.getString(KEY_DISMISSED, null) ?: return null
        val dismissedAt = prefs.getLong(KEY_DISMISSED_AT, 0L)
        val age = System.currentTimeMillis() - dismissedAt
        // A clock that moved backwards counts as expired rather than as dismissed forever.
        if (age !in 0 until DISMISSAL_TTL_MS) {
            prefs.edit().remove(KEY_DISMISSED).remove(KEY_DISMISSED_AT).apply()
            return null
        }
        return identity
    }

    fun setDismissedIdentity(context: Context, identity: String?) {
        prefs(context).edit().apply {
            if (identity == null) {
                remove(KEY_DISMISSED).remove(KEY_DISMISSED_AT)
            } else {
                putString(KEY_DISMISSED, identity).putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
            }
        }.apply()
    }

    private fun encode(update: AvailableUpdate): String = JSONObject().apply {
        put("versionName", update.versionName)
        update.versionCode?.let { put("versionCode", it) }
        put("tagName", update.tagName)
        put("notes", update.notes)
        put("publishedAt", update.publishedAtMillis)
        put("prerelease", update.prerelease)
        put("fileName", update.artifact.fileName)
        put("downloadUrl", update.artifact.downloadUrl)
        put("size", update.artifact.sizeBytes)
        update.artifact.sha256?.let { put("sha256", it) }
    }.toString()

    private fun decode(raw: String): AvailableUpdate? = runCatching {
        val obj = JSONObject(raw)
        val url = obj.getString("downloadUrl")
        // A cached entry survives app restarts, and the source setting may have changed since.
        if (!UpdateSourceClient.isDownloadableUrl(url)) return@runCatching null
        AvailableUpdate(
            versionName = obj.getString("versionName"),
            versionCode = if (obj.has("versionCode")) obj.getInt("versionCode") else null,
            tagName = obj.getString("tagName"),
            notes = obj.optString("notes"),
            publishedAtMillis = obj.optLong("publishedAt", 0L),
            prerelease = obj.optBoolean("prerelease", false),
            artifact = UpdateArtifact(
                fileName = obj.getString("fileName"),
                downloadUrl = url,
                sizeBytes = obj.optLong("size", 0L),
                sha256 = obj.optString("sha256").takeIf { it.length == 64 }
            )
        )
    }.getOrNull()
}
