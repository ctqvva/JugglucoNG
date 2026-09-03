package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoverPolicyTests {
    @Test
    fun replacementNetworkKeepsExistingRecoveryPathAlive() {
        assertTrue(
            NetworkHandoverPolicy.hasUsableReplacement(
                remainingInternetNetworks = 1,
                hasIpAddress = true,
            )
        )
    }

    @Test
    fun finalNetworkLossStillResetsConnectivity() {
        assertFalse(
            NetworkHandoverPolicy.hasUsableReplacement(
                remainingInternetNetworks = 0,
                hasIpAddress = false,
            )
        )
    }

    @Test
    fun unusableTrackedNetworkDoesNotSuppressReset() {
        assertFalse(
            NetworkHandoverPolicy.hasUsableReplacement(
                remainingInternetNetworks = 1,
                hasIpAddress = false,
            )
        )
    }
}
