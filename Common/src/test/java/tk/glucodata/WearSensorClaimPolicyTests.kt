package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSensorClaimPolicyTests {
    private val requestedAtMs = 10_000L

    @Test
    fun syncedOrRestoredStateCannotProveLocalOwnership() {
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = false,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "X-222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs + 1L,
            )
        )
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = null,
                localReadingAcceptedAtMs = 0L,
            )
        )
    }

    @Test
    fun readingMustArriveAfterRequestFromMatchingCallback() {
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs - 1L,
            )
        )
        assertFalse(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "OTHER",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs + 1L,
            )
        )
    }

    @Test
    fun connectedLocalGattAndPostRequestAliasReadingProveOwnership() {
        assertTrue(
            WearSensorClaimPolicy.hasLocalOwnershipProof(
                targetSensorId = "X-222227JR7C",
                callbackSerial = "222227JR7C",
                hasLocallyConnectedGatt = true,
                requestedAtMs = requestedAtMs,
                localReadingSerial = "X-222227JR7C",
                localReadingAcceptedAtMs = requestedAtMs + 1L,
            )
        )
    }
}
