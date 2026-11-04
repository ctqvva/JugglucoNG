// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.share

import android.os.Build
import java.util.Locale
import tk.glucodata.ClarityStatus
import tk.glucodata.Log
import tk.glucodata.Natives

/**
 * The Kotlin side of the native Dexcom Clarity uploader (`cpp/net/clarity`). Tokens, the client
 * certificate and the outbox stay native; this only carries options, status and the sign-in
 * callback across JNI. Every call may block briefly on the uploader's lock, so call it off the
 * main thread.
 *
 * Failures are logged by exception class only: native and URI error text can carry tokens.
 */
object Clarity {
    private const val LOG_ID = "Clarity"

    /** Upstream's lower bound for the upload start date (2020-09-01, UTC seconds). */
    const val EARLIEST_START_SECONDS = 1_598_911_200L

    /** Native snapshot, or null when the call itself failed. */
    @JvmStatic
    fun status(): ClarityStatus? = guarded("Read status") { Natives.clarityStatus() }

    /** Whether an account is signed in, for showing the Settings entry. */
    @JvmStatic
    fun isSignedIn(): Boolean = status()?.let { it.available && it.account.isNotEmpty() } ?: false

    /** Saves the options and returns an error message, or "" when saved. */
    @JvmStatic
    fun configure(enabled: Boolean, libre3History: Boolean, numbers: Boolean, sinceSeconds: Long): String? =
        guarded("Save settings") { Natives.clarityConfigure(enabled, libre3History, numbers, sinceSeconds) }

    /** Asks the worker to look for new data now. */
    @JvmStatic
    fun wake() {
        guarded("Wake") { Natives.clarityWake() }
    }

    /** Returns {authorizationUrl, error} (exactly one non-empty), or null on failure. */
    @JvmStatic
    fun beginLogin(country: String): Array<String>? = guarded("Begin sign-in") {
        Natives.clarityLoginBegin(
            country, Locale.getDefault().toLanguageTag(), Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE
        )
    }

    /**
     * Completes a sign-in with the `dexcomg7:` callback URL, or resumes one whose tokens were
     * saved before endpoint discovery failed when [callback] is empty. Returns "" on success.
     */
    @JvmStatic
    fun finishLogin(callback: String): String? = guarded("Complete sign-in") { Natives.clarityLoginFinish(callback) }

    private inline fun <T> guarded(operation: String, action: () -> T): T? = try {
        action()
    } catch (t: Throwable) {
        Log.e(LOG_ID, "$operation: ${t.javaClass.name}")
        null
    }
}
