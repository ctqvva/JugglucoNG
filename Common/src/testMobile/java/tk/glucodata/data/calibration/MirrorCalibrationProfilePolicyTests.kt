package tk.glucodata.data.calibration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorCalibrationProfilePolicyTests {

    private fun profile(revision: Long?, calibrationTimestamps: List<Long>): JSONObject {
        val root = JSONObject()
        root.put("version", 2)
        root.put("sensorId", "3MH01HD0P14")
        if (revision != null) {
            root.put("revision", revision)
        }
        val array = JSONArray()
        calibrationTimestamps.forEach { timestamp ->
            array.put(
                JSONObject()
                    .put("timestamp", timestamp)
                    .put("sensorValue", 7.0)
                    .put("userValue", 7.5)
            )
        }
        root.put("calibrations", array)
        return root
    }

    @Test
    fun emptyMasterProfileNeverReplacesLocalCalibration() {
        // The reported case: NFC master has no calibrations, follower calibrated at 5000.
        val incoming = MirrorCalibrationProfilePolicy.incomingRevision(profile(revision = 0L, calibrationTimestamps = emptyList()))
        val local = MirrorCalibrationProfilePolicy.localRevision(
            storedRevision = 0L,
            newestCalibrationTimestamp = 5_000L
        )

        assertFalse(MirrorCalibrationProfilePolicy.shouldApply(incoming, local))
        assertTrue(MirrorCalibrationProfilePolicy.shouldOfferLocalBack(incoming, local))
    }

    @Test
    fun newerPeerProfileStillWins() {
        val incoming = MirrorCalibrationProfilePolicy.incomingRevision(
            profile(revision = 9_000L, calibrationTimestamps = listOf(8_000L))
        )
        val local = MirrorCalibrationProfilePolicy.localRevision(
            storedRevision = 4_000L,
            newestCalibrationTimestamp = 5_000L
        )

        assertTrue(MirrorCalibrationProfilePolicy.shouldApply(incoming, local))
    }

    @Test
    fun peerWithoutRevisionFieldFallsBackToItsNewestCalibration() {
        val incoming = MirrorCalibrationProfilePolicy.incomingRevision(
            profile(revision = null, calibrationTimestamps = listOf(3_000L, 7_500L, 100L))
        )

        assertEquals(7_500L, incoming)
    }

    @Test
    fun convergedPeersDoNotKeepPushingAtEachOther() {
        val revision = 12_345L
        val incoming = MirrorCalibrationProfilePolicy.incomingRevision(
            profile(revision = revision, calibrationTimestamps = listOf(9_000L))
        )
        val local = MirrorCalibrationProfilePolicy.localRevision(
            storedRevision = revision,
            newestCalibrationTimestamp = 9_000L
        )

        assertFalse(MirrorCalibrationProfilePolicy.shouldApply(incoming, local))
        assertFalse(MirrorCalibrationProfilePolicy.shouldOfferLocalBack(incoming, local))
    }

    @Test
    fun deletingTheLastLocalCalibrationStillPropagates() {
        // Deletion leaves no rows behind, so only the bumped stamp can outrank the peer.
        val bumped = MirrorCalibrationProfilePolicy.nextRevision(
            now = 20_000L,
            localRevision = MirrorCalibrationProfilePolicy.localRevision(
                storedRevision = 0L,
                newestCalibrationTimestamp = 5_000L
            )
        )
        val peerLocal = MirrorCalibrationProfilePolicy.localRevision(
            storedRevision = 0L,
            newestCalibrationTimestamp = 5_000L
        )

        assertEquals(20_000L, bumped)
        assertTrue(MirrorCalibrationProfilePolicy.shouldApply(bumped, peerLocal))
    }

    @Test
    fun revisionStampNeverGoesBackwardsWhenTheClockLags() {
        val bumped = MirrorCalibrationProfilePolicy.nextRevision(
            now = 1_000L,
            localRevision = 50_000L
        )

        assertEquals(50_001L, bumped)
    }
}
