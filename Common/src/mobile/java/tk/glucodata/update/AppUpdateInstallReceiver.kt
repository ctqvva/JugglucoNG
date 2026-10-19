package tk.glucodata.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.annotation.Keep
import tk.glucodata.Log

/**
 * Receives [PackageInstaller] session status. Declared in the manifest (not exported) and
 * targeted explicitly by the commit PendingIntent.
 *
 * The interesting state is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system hands back
 * an Intent that shows the "Update this app?" dialog, and only launching it moves the install
 * forward. Everything else is a terminal result the UI reports.
 */
@Keep
class AppUpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent)
                if (confirm == null) {
                    AppUpdateController.onInstallFailed(context, UpdateError.INSTALL_FAILED)
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    Log.stack(LOG_TAG, "could not show install confirmation", it)
                    AppUpdateController.onInstallFailed(context, UpdateError.INSTALL_FAILED)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // Rarely reached: a successful self-update kills this process almost immediately.
                // The staged APK is cleaned up on next start as well, for exactly that reason.
                UpdateDownloader.clearStaged(context)
                AppUpdateSettings.clearCachedUpdate(context)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppUpdateController.onInstallFailed(context, UpdateError.CANCELLED)
            }

            else -> {
                Log.w(LOG_TAG, "install failed, status=$status message=$message")
                AppUpdateController.onInstallFailed(context, UpdateError.INSTALL_FAILED)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent

    companion object {
        const val ACTION_INSTALL_STATUS = "tk.glucodata.update.INSTALL_STATUS"
        private const val LOG_TAG = "AppUpdate"
    }
}
