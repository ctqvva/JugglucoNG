package tk.glucodata.drivers.sibionics

import org.junit.Test

class ScratchLeadProbe {
    @Test
    fun measureVendorEffectiveLead() {
        // Ramp lengths chosen so each slope stays inside 4.5..12 mmol/L.
        listOf(
            Triple(-0.10f, 11.5f, 60), Triple(-0.05f, 11.5f, 120),
            Triple(-0.02f, 11.0f, 250), Triple(0.05f, 5.0f, 120),
            Triple(0.10f, 5.0f, 60),
        ).forEach { (slope, start, steps) ->
            val core = SibionicsExactV115GCore(1.4f)
            var level = start
            repeat(6000) { i -> core.process(level, 34f, i + 1) }
            var sumOffset = 0.0
            var n = 0
            repeat(steps) { k ->
                val index = 6000 + k + 1
                level += slope
                val out = core.process(level, 34f, index)
                val obs = core.latestSensorObservation?.calibratedMmol
                // First 30 minutes are the ramp-onset transient.
                if (out != null && obs != null && k > 30) { sumOffset += (out - obs); n++ }
            }
            val meanOffset = if (n > 0) sumOffset / n else Double.NaN
            println("V slope=%+.3f meanOffset=%+.4f impliedTauMin=%.2f n=%d".format(
                slope, meanOffset, meanOffset / slope, n))
        }
    }
}
