package tk.glucodata.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import tk.glucodata.Log
import java.io.File

/**
 * Hands a verified APK to the platform [PackageInstaller].
 *
 * Note what this deliberately does *not* do: it never sets
 * `SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. On recent Android that flag can
 * suppress the confirmation dialog for a self-update, and it is tempting. But installing an APK
 * kills and restarts this process — which on a CGM app means dropping the foreground service and
 * the sensor's BLE link. Choosing the moment that happens belongs to the user, not to a
 * background worker.
 */
object AppUpdateInstaller {

    private const val LOG_TAG = "AppUpdate"

    const val EXTRA_SESSION_ID = "tk.glucodata.update.SESSION_ID"

    /** Null when the session was committed; the caller then waits for [AppUpdateInstallReceiver]. */
    fun install(context: Context, file: File): UpdateError? {
        val appContext = context.applicationContext
        if (!UpdateEligibility.canRequestPackageInstalls(appContext)) {
            return UpdateError.INSTALL_PERMISSION
        }
        if (!file.isFile || file.length() <= 0L) return UpdateError.STORAGE

        val installer = appContext.packageManager.packageInstaller
        abandonStaleSessions(installer)

        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply { setAppPackageName(appContext.packageName) }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                    session.fsync(output)
                }
                session.commit(statusIntentSender(appContext, sessionId))
            }
            null
        } catch (e: Exception) {
            Log.stack(LOG_TAG, "install session failed", e)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            UpdateError.INSTALL_FAILED
        }
    }

    /**
     * Explicit-component PendingIntent: the status callback goes straight to our own receiver
     * rather than through an implicit broadcast, which Android would drop anyway.
     * It must be mutable — the system fills in the status extras.
     */
    private fun statusIntentSender(context: Context, sessionId: Int): android.content.IntentSender {
        val intent = Intent(context, AppUpdateInstallReceiver::class.java)
            .setAction(AppUpdateInstallReceiver.ACTION_INSTALL_STATUS)
            .putExtra(EXTRA_SESSION_ID, sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    /** A session left behind by a cancelled or crashed attempt keeps its staged copy on disk. */
    private fun abandonStaleSessions(installer: PackageInstaller) {
        runCatching {
            installer.mySessions.forEach { session ->
                runCatching { installer.abandonSession(session.sessionId) }
            }
        }
    }
}
