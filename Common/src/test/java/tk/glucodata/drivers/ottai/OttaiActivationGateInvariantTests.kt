package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activation gate as invariants, checked over every combination of its inputs rather than a
 * handful of cases. Once the 0x03 command has been Issued, nothing automatic may enter the
 * RTC / maxActive / destruction / 0x03 writes again: an acknowledgement can be lost while the
 * sensor starts, and a second activation run must never follow from that (the lost-ACK rule).
 * Only the Advanced gesture may, and only while the command byte still reads 0-2.
 */
class OttaiActivationGateInvariantTests {

    private val statuses = -1..6
    private val bools = listOf(false, true)

    @Test
    fun afterTheCommandIsIssuedNoAutomaticPathEntersTheActivationWrites() {
        for (status in statuses) {
            assertFalse(
                "status=$status",
                OttaiConstants.mayEnterActivationWrites(status, activateCommandIssued = true, advancedActivate = false),
            )
            for (explicit in bools) for (inFlight in bools) for (busy in bools) {
                assertFalse(
                    "status=$status explicit=$explicit inFlight=$inFlight busy=$busy",
                    OttaiConstants.shouldScheduleFirstUseActivation(
                        commandStatus = status,
                        explicitlyRequested = explicit,
                        activateCommandIssued = true,
                        advancedActivate = false,
                        activationInFlight = inFlight,
                        activationBusy = busy,
                    ),
                )
            }
        }
        for (active in bools) for (retry in bools) {
            for (busy in bools) {
                assertFalse(OttaiConstants.shouldResumeLifetimeNegotiation(true, active, retry, busy))
            }
            for (candidates in bools) for (inFlight in bools) {
                assertFalse(OttaiConstants.shouldReconnectToResumeActivation(true, active, retry, candidates, inFlight))
            }
        }
    }

    @Test
    fun aCommandByteOutsideZeroToTwoBlocksEvenTheAdvancedGesture() {
        for (status in statuses) {
            if (status in 0..2) continue
            for (issued in bools) for (advanced in bools) {
                assertFalse(
                    "status=$status issued=$issued advanced=$advanced",
                    OttaiConstants.mayEnterActivationWrites(status, issued, advanced),
                )
                for (explicit in bools) for (inFlight in bools) for (busy in bools) {
                    assertFalse(
                        OttaiConstants.shouldScheduleFirstUseActivation(status, explicit, issued, advanced, inFlight, busy),
                    )
                }
            }
        }
    }

    /** The gate is not closed for everything: without these the two tests above pass vacuously. */
    @Test
    fun theGateStillOpensWhereItMust() {
        for (status in 0..2) {
            assertTrue(OttaiConstants.mayEnterActivationWrites(status, activateCommandIssued = false, advancedActivate = false))
            assertTrue(OttaiConstants.mayEnterActivationWrites(status, activateCommandIssued = true, advancedActivate = true))
            assertTrue(OttaiConstants.shouldScheduleFirstUseActivation(status, true, false, false, false, false))
            assertTrue(OttaiConstants.shouldScheduleFirstUseActivation(status, false, true, true, false, false))
            // Nothing asked for it, or a run already in progress: no new one.
            assertFalse(OttaiConstants.shouldScheduleFirstUseActivation(status, false, false, false, false, false))
            assertFalse(OttaiConstants.shouldScheduleFirstUseActivation(status, true, false, false, true, false))
            assertFalse(OttaiConstants.shouldScheduleFirstUseActivation(status, true, false, false, false, true))
        }
    }

    @Test
    fun acceptedMaxActiveSkipsTheLifetimeRewriteUntilTheActivateCommandIsIssued() {
        assertTrue(OttaiConstants.shouldSkipToPostLifetimeWrites(activateCommandIssued = false, maxActiveAccepted = true))
        assertFalse(OttaiConstants.shouldSkipToPostLifetimeWrites(activateCommandIssued = true, maxActiveAccepted = true))
        assertFalse(OttaiConstants.shouldSkipToPostLifetimeWrites(activateCommandIssued = false, maxActiveAccepted = false))
    }

    @Test
    fun wearDaysDoNotCreateAShellBeforeAStartExists() {
        assertFalse(OttaiConstants.wearUpdateMayTouchNativeShell(startMs = 0L, shellAlreadySized = false))
        assertTrue(OttaiConstants.wearUpdateMayTouchNativeShell(startMs = 1_700_000_000_000L, shellAlreadySized = false))
        assertTrue(OttaiConstants.wearUpdateMayTouchNativeShell(startMs = 0L, shellAlreadySized = true))
    }

    @Test
    fun lifetimeNegotiationResumesInExactlyOneState() {
        assertTrue(OttaiConstants.shouldResumeLifetimeNegotiation(false, true, true, false))
        // Any single input flipped closes it.
        assertFalse(OttaiConstants.shouldResumeLifetimeNegotiation(true, true, true, false))
        assertFalse(OttaiConstants.shouldResumeLifetimeNegotiation(false, false, true, false))
        assertFalse(OttaiConstants.shouldResumeLifetimeNegotiation(false, true, false, false))
        assertFalse(OttaiConstants.shouldResumeLifetimeNegotiation(false, true, true, true))
        var open = 0
        for (issued in bools) for (active in bools) for (retry in bools) for (busy in bools) {
            if (OttaiConstants.shouldResumeLifetimeNegotiation(issued, active, retry, busy)) open++
        }
        assertEquals(1, open)
    }

    /**
     * A write error that means the link went away. On the 0x03 step it fails the activation (the
     * command may have landed, so it is not retried); on the maxActive step it is not a rejection
     * of the value, so the next lifetime candidate is not tried on it.
     */
    @Test
    fun linkLossIsExactlyTheFiveDisconnectStatuses() {
        val linkLoss = setOf(8, 19, 22, 133, 147)
        assertEquals(linkLoss, (0..255).filter { OttaiConstants.isActivationLinkLossWriteStatus(it) }.toSet())
        for (status in 0..255) {
            val lost = status in linkLoss
            assertEquals("status=$status", !lost, OttaiConstants.shouldRetryMaxActiveOnWriteError(status))
            assertEquals("status=$status", !lost, OttaiConstants.writeErrorShouldFailActivation(true, false, status))
            assertTrue("status=$status", OttaiConstants.writeErrorShouldFailActivation(true, true, status))
            assertFalse("status=$status", OttaiConstants.writeErrorShouldFailActivation(false, true, status))
        }
    }
}
