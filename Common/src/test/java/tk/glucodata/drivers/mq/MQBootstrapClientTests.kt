package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQBootstrapClientTests {
    private val point = MQBootstrapHistoryPoint(1_789_000_000_000L, 42, 106.3f)

    @Test fun qrNormalizationAndSessionHistorySurviveTheEntireFetchPipeline() {
        val result = fetch()
        assertEquals(29.3f, result.config!!.sensitivity!!, 0f)
        assertEquals("session", result.config.snapshotId)
        assertEquals(listOf(point), result.history)
    }

    @Test fun resetSuppressesSessionLookupAndHistoryButKeepsNormalizedQrSeed() {
        val result = fetch(allowRestore = false, session = { error("Must not restore after reset") })
        assertTrue(result.history.isEmpty())
        assertNull(result.config!!.snapshotId)
        assertEquals(29.3f, result.config.sensitivity!!, 0f)
    }

    @Test fun failedRestoreCannotManufactureHistoryOrEraseStaticConfiguration() {
        val result = fetch(session = { MQBootstrapFetchResult(failure = MQBootstrapFailure.NETWORK) })
        assertEquals(MQBootstrapFailure.NETWORK, result.failure)
        assertTrue(result.history.isEmpty())
        assertEquals(29.3f, result.config!!.sensitivity!!, 0f)
    }

    @Test fun ordinaryTransmitterDoesNotReceiveTenfoldSensitivityScaling() {
        assertEquals(2.93f, fetch(transmitter10 = 0).config!!.sensitivity!!, 0f)
    }

    private fun fetch(
        allowRestore: Boolean = true,
        transmitter10: Int = 1,
        session: () -> MQBootstrapFetchResult = {
            MQBootstrapFetchResult(MQBootstrapConfig(snapshotId = "session"), history = listOf(point))
        },
    ) = MQBootstrapClient.fetchBestEffortOnce(
        endpoints = MQConstants.vendorEndpoints(null),
        bleId = "W25101399", qrCode = "test-qr", authToken = "test-token", account = "test-account",
        allowContinueWearRestore = allowRestore,
        bleLookup = {
            assertEquals("W25101399", it)
            MQBootstrapFetchResult(MQBootstrapConfig(transmitter10 = transmitter10))
        },
        qrLookup = { MQBootstrapFetchResult(MQBootstrapConfig(sensitivity = 2.93f)) },
        sessionLookup = session,
    )
}
