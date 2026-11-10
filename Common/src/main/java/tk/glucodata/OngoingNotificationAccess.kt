package tk.glucodata

import android.app.Notification
import android.content.Context

/**
 * The watch's ongoing-activity notification (direction.md §4, category S).
 *
 * The phone copy was a Kotlin object of two `@JvmStatic` no-ops, one of which returned the
 * notification it was given, and the only two shared callers are both inside
 * `if (isWearable)`. So the phone shell existed purely to make the shared reference
 * compile, and it is deleted rather than renamed: the watch class was already named for the
 * watch, so with one side gone there is no duplicate left to disambiguate.
 *
 * Two call sites, so absence is a nullable registry and a caller-side check, per the ruling
 * on #465. The `isWearable` guard in front of both already kept the phone away; the check is
 * the contract, not the mechanism.
 */
interface OngoingNotification {
    /**
     * The notification as an ongoing activity. The phone's version returned its argument
     * unchanged, so a caller that gets null here and keeps its own notification behaves
     * exactly as it did before.
     */
    fun attach(context: Context, notification: Notification, notificationId: Int): Notification

    /** Updates the ongoing activity's status, if there is one. */
    fun updateStatus(context: Context?, notificationId: Int)
}

object OngoingNotificationAccess {
    @Volatile
    private var ongoing: OngoingNotification? = null

    @JvmStatic
    fun register(ongoing: OngoingNotification) {
        this.ongoing = ongoing
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = ongoing != null

    /** Null on a flavour without an ongoing activity (the phone). */
    @JvmStatic
    fun get(): OngoingNotification? = ongoing
}
