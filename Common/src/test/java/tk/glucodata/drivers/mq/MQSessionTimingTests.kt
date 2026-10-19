package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQSessionTimingTests {
    @Test fun freshAppDoesNotTurnAnElevenHourOldTransmitterIntoANewSession() {
        val received = 1789337213000L // Trace: 03:06:53, packet 237.
        assertEquals(received - 237 * 180_000L, MQSessionTiming.estimateStartMs(received, 237, 3))
    }

    @Test fun laterReconnectRetainsTheSameEstimatedStartAtNominalCadence() {
        val first = MQSessionTiming.estimateStartMs(1789337213000L, 237, 3)
        assertEquals(first, MQSessionTiming.estimateStartMs(1789337393000L, 238, 3))
    }

    @Test fun invalidOrTerminalPacketsCannotInventAStartTime() {
        for (packet in listOf(-1, 0, 15000, 65535)) {
            assertNull(MQSessionTiming.estimateStartMs(1789337213000L, packet, 3))
        }
        assertNull(MQSessionTiming.estimateStartMs(1000, 237, 3))
    }

    @Test fun repairsPersistedConnectionTimeButPreservesRealStartAndToleratesRadioDelay() {
        val received = 1789337213000L
        val estimated = received - 237 * 180_000L
        assertEquals(estimated, MQSessionTiming.reconcileStartMs(received, received, 237, 3))
        assertEquals(estimated - 30_000, MQSessionTiming.reconcileStartMs(estimated - 30_000, received, 237, 3))
        assertEquals(estimated + 30_000, MQSessionTiming.reconcileStartMs(estimated + 30_000, received, 237, 3))
    }
}
