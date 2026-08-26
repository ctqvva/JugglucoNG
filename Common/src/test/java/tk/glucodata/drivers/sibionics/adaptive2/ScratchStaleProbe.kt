package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.random.Random
import org.junit.Test
import tk.glucodata.drivers.sibionics.SibionicsSensorObservation

class ScratchStaleProbe {
    private fun obs(v: Float, i: Int) = SibionicsSensorObservation(
        calibratedMmol = v, chemicalMmol = v, sensorStateCompensationMmol = 0f,
        qualityFlags = 0, factorySensitivity = 1.4f, activeSensitivity = 1.4f,
        sensorAgeMinutes = i, family = 115,
    )

    @Test
    fun cleanSustainedFall() {
        val c = SibionicsAdaptiveV2Context().apply { configure(1.4f); enableDiagnostics(400) }
        val rnd = Random(3)
        // Settle flat at 6.0
        repeat(240) { k -> c.process(obs(6f + (rnd.nextFloat()-0.5f)*0.14f, 130+k), 34f, 2900f, (130+k)*60_000L) }
        // Fall 6.0 -> 3.9 over 21 min, then hold 3.9 for 30 min
        var v = 6f
        val rows = ArrayList<AdaptiveV2Diagnostics>()
        repeat(21) { k ->
            v -= 0.10f
            c.process(obs(v + (rnd.nextFloat()-0.5f)*0.14f, 370+k), 34f, 2900f, (370+k)*60_000L)
            c.latestEstimate()
        }
        repeat(30) { k ->
            c.process(obs(3.9f + (rnd.nextFloat()-0.5f)*0.14f, 391+k), 34f, 2900f, (391+k)*60_000L)
        }
        c.diagnostics().takeLast(51).forEachIndexed { n, d ->
            if (n % 3 == 0 || n > 45) println(
                "S n=%2d obs=%.2f B=%.2f I=%.2f rate=%+.4f lo=%.2f hi=%.2f inn=%+.3f R=%.4f pS=%.2f pD=%.2f pA=%.2f pDr=%.2f s=%.4f b=%+.3f art=%+.3f lag=%.1f".format(
                    n, d.chemicalMmol, d.glucoseMmol, d.interstitialMmol, d.rateMmolPerMin,
                    d.lower90Mmol, d.upper90Mmol, d.innovation, d.measurementNoise,
                    d.steadyProbability, d.dynamicProbability, d.artifactProbability, d.driftProbability,
                    d.sensitivity, d.biasMmol, d.artifactMmol, d.lagMinutes))
        }
    }
}
