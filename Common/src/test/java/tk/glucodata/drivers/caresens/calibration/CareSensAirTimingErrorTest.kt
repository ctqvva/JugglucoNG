package tk.glucodata.drivers.caresens.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Timing error 32 with interruptions and a jump in sequence numbers. The
 * expected records are the reference library's (tools/caresens-oracle): its
 * 864 window counts accepted records, not sequence numbers.
 */
class CareSensAirTimingErrorTest {
    private val spec = CareSensAirScenarios.Spec(
        mapOf(
            "seed" to 9, "readings" to 1000, "start" to 1, "slope100" to 3.5226, "vref" to 1.49594, "eapp" to 0.10067,
            "lo" to 70, "hi" to 180, "knot_min" to 60, "knot_max" to 300, "noise" to 12,
            "temp_mode" to "const", "temp_lo" to 3400, "temp_hi" to 3400, "temp_knot_min" to 30, "temp_knot_max" to 200,
            "decay" to 1.0, "gap_permille" to 0, "gap_max" to 1, "jitter" to 0,
            "dropout_permille" to 0, "dropout_min" to 3, "dropout_max" to 40, "spike_permille" to 0,
            "clock_jump_at" to -1, "clock_jump" to 0,
        ),
    )

    /** Index of the first record with error 32, or null. */
    private fun firstError(interruptions: List<Int>, sequenceJumpAt: Int?, jump: Int = 300): Int? {
        val records = CareSensAirScenarios.generate(spec).mapIndexed { i, r ->
            var time = r.timeSeconds + 1100L * interruptions.count { i >= it }
            var sequence = r.sequence
            if (sequenceJumpAt != null && i >= sequenceJumpAt) {
                sequence += jump
                time += jump * 300L
            }
            r.copy(sequence = sequence, timeSeconds = time)
        }
        val engine = CareSensAirCalibration(spec.parameters)
        return records.indexOfFirst { engine.process(it).errorCode and CareSensAirCalibration.ERROR_TIMING != 0 }.takeIf { it >= 0 }
    }

    @Test
    fun fourInterruptionsWithin864RecordsLatchTheError() {
        assertEquals(900, firstError(listOf(50, 400, 700, 900), null))
        // 1150 sequence numbers apart, but 850 records: still an error.
        assertEquals(900, firstError(listOf(50, 400, 700, 900), 450))
        assertEquals(914, firstError(listOf(50, 400, 700, 914), 450, jump = 30))
        assertEquals(null, firstError(listOf(50, 400, 700, 915), 450, jump = 30))
        assertEquals(null, firstError(listOf(20, 400, 700, 890), null))
    }
}
