package tk.glucodata.ui

import tk.glucodata.CloneRecoveryCapabilities
import tk.glucodata.CloneRecoveryCategories
import tk.glucodata.CloneRecoveryMode

internal data class CloneHistoryRecoveryConnection(
    val index: Int,
    val isIce: Boolean,
    val isWearOs: Boolean,
    val sendsData: Boolean,
    val receivesData: Boolean,
    val isDeactivated: Boolean,
    val isPending: Boolean,
    val hasPassword: Boolean,
)

internal enum class CloneHistoryRecoveryBlocker {
    NOT_ICE,
    WEAR_OS,
    WRONG_DIRECTION,
    DEACTIVATED,
    PENDING,
    NO_PASSWORD,
}

internal data class CloneHistoryRecoverySelection(
    val mode: CloneRecoveryMode = CloneRecoveryMode.ONLY_MISSING,
    val includeJournal: Boolean = false,
) {
    fun categories(remoteCapabilities: CloneRecoveryCapabilities): Int =
        CloneRecoveryCategories.GLUCOSE or
            if (includeJournal &&
                remoteCapabilities.categories and CloneRecoveryCategories.JOURNAL != 0
            ) {
                CloneRecoveryCategories.JOURNAL
            } else {
                0
            }
}

internal fun CloneHistoryRecoveryConnection.recoveryBlocker(): CloneHistoryRecoveryBlocker? =
    when {
        !isIce -> CloneHistoryRecoveryBlocker.NOT_ICE
        isWearOs -> CloneHistoryRecoveryBlocker.WEAR_OS
        !sendsData || receivesData -> CloneHistoryRecoveryBlocker.WRONG_DIRECTION
        isDeactivated -> CloneHistoryRecoveryBlocker.DEACTIVATED
        isPending -> CloneHistoryRecoveryBlocker.PENDING
        !hasPassword -> CloneHistoryRecoveryBlocker.NO_PASSWORD
        else -> null
    }

internal fun CloneHistoryRecoveryConnection.canSendHistory(): Boolean =
    recoveryBlocker() == null
