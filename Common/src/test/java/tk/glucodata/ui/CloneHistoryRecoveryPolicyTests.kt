package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.CloneHistoryRecoveryProtocol
import tk.glucodata.CloneRecoveryCategories
import tk.glucodata.CloneRecoveryMode

class CloneHistoryRecoveryPolicyTests {
    @Test
    fun authenticatedSendOnlyConnectionCanSendHistory() {
        val connection = eligibleConnection()

        assertNull(connection.recoveryBlocker())
        assertTrue(connection.canSendHistory())
    }

    @Test
    fun everyUnsafeConnectionStateBlocksRecovery() {
        val cases = listOf(
            eligibleConnection().copy(isIce = false) to CloneHistoryRecoveryBlocker.NOT_ICE,
            eligibleConnection().copy(isWearOs = true) to CloneHistoryRecoveryBlocker.WEAR_OS,
            eligibleConnection().copy(sendsData = false) to CloneHistoryRecoveryBlocker.WRONG_DIRECTION,
            eligibleConnection().copy(receivesData = true) to CloneHistoryRecoveryBlocker.WRONG_DIRECTION,
            eligibleConnection().copy(isDeactivated = true) to CloneHistoryRecoveryBlocker.DEACTIVATED,
            eligibleConnection().copy(isPending = true) to CloneHistoryRecoveryBlocker.PENDING,
            eligibleConnection().copy(hasPassword = false) to CloneHistoryRecoveryBlocker.NO_PASSWORD,
        )

        cases.forEach { (connection, expectedBlocker) ->
            assertEquals(expectedBlocker, connection.recoveryBlocker())
            assertFalse(connection.canSendHistory())
        }
    }

    @Test
    fun selectionDefaultsToOnlyMissingGlucose() {
        val capabilities = CloneHistoryRecoveryProtocol.localCapabilities(
            CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
        )

        assertEquals(CloneRecoveryMode.ONLY_MISSING, CloneHistoryRecoverySelection().mode)
        assertEquals(
            CloneRecoveryCategories.GLUCOSE,
            CloneHistoryRecoverySelection().categories(capabilities),
        )
    }

    @Test
    fun journalRequiresBothSelectionAndRemoteSupport() {
        val selected = CloneHistoryRecoverySelection(includeJournal = true)
        val glucoseOnly = CloneHistoryRecoveryProtocol.localCapabilities(
            CloneRecoveryCategories.GLUCOSE,
        )
        val withJournal = CloneHistoryRecoveryProtocol.localCapabilities(
            CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
        )

        assertEquals(CloneRecoveryCategories.GLUCOSE, selected.categories(glucoseOnly))
        assertEquals(
            CloneRecoveryCategories.GLUCOSE or CloneRecoveryCategories.JOURNAL,
            selected.categories(withJournal),
        )
    }

    private fun eligibleConnection() = CloneHistoryRecoveryConnection(
        index = 3,
        isIce = true,
        isWearOs = false,
        sendsData = true,
        receivesData = false,
        isDeactivated = false,
        isPending = false,
        hasPassword = true,
    )
}
