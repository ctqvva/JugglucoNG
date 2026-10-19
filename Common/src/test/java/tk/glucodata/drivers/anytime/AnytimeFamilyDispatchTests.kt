package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Family separation for the Anytime driver. The two protocol namespaces share
 * byte values (0x08, 0x09, 0x58), so the CT-14 (CT2 family) is dispatched on its
 * own and must never reach the generic CT3/CT2.5 handlers.
 */
class AnytimeFamilyDispatchTests {

    @Test
    fun ct14OwnsItsResponseOpcodes() {
        val ct14 = listOf(
            AnytimeConstants.RX_CT2_HANDSHAKE_ACK, // 0x48
            AnytimeConstants.RX_CT2_SET_DATE_ACK,  // 0x54
            AnytimeConstants.RX_CT2_INIT_ACK,      // 0x53
            AnytimeConstants.RX_CT2_CHECK,         // 0x43
            AnytimeConstants.RX_CT2_PUSH_GLUCOSE,  // 0x44
            AnytimeConstants.RX_CT2_PULL_RESPONSE, // 0x47
            AnytimeConstants.RX_UNBIND_ACK_GENERIC, // 0x58
        )
        for (opcode in ct14.toSet()) {
            assertTrue("0x%02X must be CT-14".format(opcode.toInt() and 0xFF), AnytimeConstants.isCt14Opcode(opcode))
        }
    }

    @Test
    fun ct14DoesNotClaimCt3OrCt5Opcodes() {
        val foreign = listOf(
            AnytimeConstants.RX_PUSH_GLUCOSE,        // 0x07 (shared value)
            AnytimeConstants.RX_PULL_GLUCOSE,        // 0x08 — collides with CT2 input-BG
            AnytimeConstants.RX_INPUT_BG_ACK,        // 0x09 — collides with CT2 glucoseByTx
            AnytimeConstants.RX_CHECK,               // 0x05
            AnytimeConstants.RX_INIT,                // 0x06
            AnytimeConstants.RX_UNBIND_ACK,          // 0x0A
            AnytimeConstants.RX_RESET,               // 0x11
            AnytimeConstants.RX_CT5_PUSH_GLUCOSE,    // 0x35
            AnytimeConstants.RX_CT5_SERIES,          // 0x37
            AnytimeConstants.RX_CT5_SET_PARAMETERS,  // 0x38
        )
        for (opcode in foreign.toSet()) {
            assertFalse("0x%02X must not be CT-14".format(opcode.toInt() and 0xFF), AnytimeConstants.isCt14Opcode(opcode))
        }
    }

    @Test
    fun theCollidingOpcodesAreNotCt14Handled() {
        // 0x08/0x09 are CT2 *commands* the driver never sends, but the same bytes are
        // CT3 pull response / input-BG ack. Keeping them out of the CT-14 set is what
        // stops a CT-14 session from misparsing them as CT3 frames.
        assertFalse(AnytimeConstants.isCt14Opcode(0x08.toByte()))
        assertFalse(AnytimeConstants.isCt14Opcode(0x09.toByte()))
    }

    @Test
    fun familyResolvesByTransmitterName() {
        assertEquals(AnytimeConstants.Family.CT2, AnytimeConstants.resolveFamily("SN08402458").family)
        assertEquals(AnytimeConstants.Family.CT3, AnytimeConstants.resolveFamily("SN16").family)
        assertEquals(AnytimeConstants.Family.CT5, AnytimeConstants.resolveFamily("Anytime").family)
    }
}
