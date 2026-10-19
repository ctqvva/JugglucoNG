package tk.glucodata.drivers.mq

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MQCloudRecoveryTests {
    private val point = MQBootstrapHistoryPoint(1789339342000L, 249, 66.6f)

    @Test fun emptyRangeCanRecoverHistoryFromTheSameSnapshotsDetailEndpoint() {
        val result = MQCloudRecovery.history({ MQCloudHistoryResult() }, { MQCloudHistoryResult(listOf(point)) })
        assertEquals(listOf(point), result.history)
    }

    @Test fun populatedRangeDoesNotCauseAnExtraSnapshotRequest() {
        val result = MQCloudRecovery.history({ MQCloudHistoryResult(listOf(point)) }, { error("Unnecessary lookup") })
        assertEquals(listOf(point), result.history)
    }

    @Test fun authenticationFailureStopsRecoveryAndOtherFailuresRemainVisible() {
        val result = MQCloudRecovery.history({ MQCloudHistoryResult(failure = MQBootstrapFailure.AUTH_EXPIRED) },
            { error("Must reauthenticate before another request") })
        assertEquals(MQBootstrapFailure.AUTH_EXPIRED, result.failure)
        assertEquals(MQBootstrapFailure.SERVER, MQCloudRecovery.history(
            { MQCloudHistoryResult(failure = MQBootstrapFailure.SERVER) }, { MQCloudHistoryResult() }).failure)
    }

    @Test fun onlyTheCapturedAlreadyMonitoringResponseStopsResumeRetries() {
        assertTrue(MQCloudRecovery.isAlreadyMonitoring(302, true, "手机号在监测周期中"))
        assertFalse(MQCloudRecovery.isAlreadyMonitoring(302, false, "手机号在监测周期中"))
        assertFalse(MQCloudRecovery.isAlreadyMonitoring(302, true, "other error"))
        assertFalse(MQCloudRecovery.isAlreadyMonitoring(500, true, "手机号在监测周期中"))
    }

    @Test fun vendorDetailRecordPreservesPacketTimestampAndMmolToMgdlUnits() {
        // Vendor calculateData format; packet 249, raw 110, battery byte 0x32,
        // processed 108, glucose 37 (mmol/L times ten), trailing vendor nibble.
        val item = JSONObject().put("cd", "40f9006e00326c0025000").put("rd", 1789339342000L)
        val decoded = decode(JSONArray().put(item)).history.single()
        assertEquals(249, decoded.packetIndex)
        assertEquals(1789339342000L, decoded.timestampMs)
        assertEquals((3.7 * MQConstants.MMOL_TO_MGDL).toFloat(), decoded.glucoseMgdl, 0.001f)
    }

    @Test fun malformedPayloadIsNotReportedAsEmptyHistoryOrTurnedIntoAReading() {
        assertEquals(MQBootstrapFailure.NONE, decode(JSONArray()).failure)
        val junk = JSONObject().put("cd", "junk40f9006e00326c0025000").put("rd", 1789339342000L)
        val rejected = decode(JSONArray().put(junk))
        assertEquals(MQBootstrapFailure.SERVER, rejected.failure)
        assertTrue(rejected.history.isEmpty())
        assertEquals(MQBootstrapFailure.SERVER,
            MQCloudClient.decodeHistory(MQCloudPostResult(root = JSONObject()), "test").failure)
    }

    private fun decode(array: JSONArray) = MQCloudClient.decodeHistory(
        MQCloudPostResult(root = JSONObject().put("result", array)), "test",
    )
}
