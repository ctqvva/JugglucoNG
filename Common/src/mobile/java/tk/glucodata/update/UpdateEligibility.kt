package tk.glucodata.update

import android.content.Context
import android.os.Build
import tk.glucodata.BuildConfig

/**
 * Decides whether the in-app updater should be offered at all on this install.
 *
 * Two builds must never self-update:
 *  - **debug builds**, which carry their own applicationId suffix and are signed per developer
 *    machine, so no published artifact could ever install over them;
 *  - **installs that came from an app store**, where the store already owns updates. Replacing a
 *    store install with a sideloaded APK breaks that store's update path (and, for Play,
 *    violates its distribution policy), so the updater stays out of the way and says why.
 */
object UpdateEligibility {

    enum class Blocker {
        /** Debug build: no matching release artifact exists. */
        DEBUG_BUILD,

        /** Installed by an app store, which handles updates itself. */
        MANAGED_BY_STORE
    }

    private val STORE_INSTALLERS = setOf(
        "com.android.vending",
        "com.google.android.feedback",
        "org.fdroid.fdroid",
        "org.fdroid.fdroid.privileged",
        "com.aurora.store",
        "com.amazon.venezia",
        "com.huawei.appmarket",
        "com.sec.android.app.samsungapps",
        "com.xiaomi.mipicks",
        "ru.vk.store"
    )

    /** Null when the updater may run. */
    fun blocker(context: Context): Blocker? {
        if (UpdateArtifacts.expectedTokens(BuildConfig.APPLICATION_ID) == null) {
            return Blocker.DEBUG_BUILD
        }
        val installer = installerPackage(context)
        if (installer != null && installer in STORE_INSTALLERS) return Blocker.MANAGED_BY_STORE
        return null
    }

    fun isSupported(context: Context): Boolean = blocker(context) == null

    /** Package that installed us, or null for a plain sideload / unknown source. */
    fun installerPackage(context: Context): String? {
        val pm = context.packageManager
        val self = context.packageName
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(self).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(self)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** True once the user has granted "install unknown apps" to JugglucoNG. */
    fun canRequestPackageInstalls(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)
}
