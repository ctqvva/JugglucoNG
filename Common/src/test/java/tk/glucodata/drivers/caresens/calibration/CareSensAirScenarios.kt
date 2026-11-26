package tk.glucodata.drivers.caresens.calibration

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin copy of tools/caresens-oracle/scenarios.py. Both use SplitMix64 and
 * only +, -, *, / and floor on doubles, so they produce identical records.
 */
object CareSensAirScenarios {
    const val BASE_TIME = 1709726400L

    class Spec(values: Map<String, Any>) {
        private val v = values
        private fun i(k: String) = (v[k] as Number).toInt()
        private fun l(k: String) = (v[k] as Number).toLong()
        private fun d(k: String) = (v[k] as Number).toDouble()
        val seed = l("seed"); val readings = i("readings"); val start = i("start")
        val slope100 = d("slope100").toFloat(); val vref = d("vref").toFloat(); val eapp = d("eapp").toFloat()
        val lo = i("lo"); val hi = i("hi"); val knotMin = i("knot_min"); val knotMax = i("knot_max")
        val noise = i("noise")
        val tempMode = v["temp_mode"] as String; val tempLo = i("temp_lo"); val tempHi = i("temp_hi")
        val tempKnotMin = i("temp_knot_min"); val tempKnotMax = i("temp_knot_max")
        val decay = d("decay")
        val gapPermille = i("gap_permille"); val gapMax = i("gap_max"); val jitter = i("jitter")
        val dropoutPermille = i("dropout_permille"); val dropoutMin = i("dropout_min"); val dropoutMax = i("dropout_max")
        val spikePermille = i("spike_permille")
        val clockJumpAt = i("clock_jump_at"); val clockJump = l("clock_jump")
        val parameters get() = CareSensAirFactoryParameters(slope100, vref, eapp)
    }

    private class SplitMix(seed: Long) {
        private var s = seed
        fun next(): Long {
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        fun below(m: Int): Int = ((next() ushr 1) % m).toInt()
        fun between(a: Int, b: Int): Int = a + below(b - a + 1)
    }

    fun generate(p: Spec): List<CareSensAirCalibration.Record> {
        val rng = SplitMix(p.seed)
        val n = p.readings
        val slope = p.slope100.toDouble(); val vref = p.vref.toDouble(); val eapp = p.eapp.toDouble()
        val offset = if (p.eapp < 0.075) 0.243 else 0.7
        val total = n * 30
        val knots = mutableListOf(0 to rng.between(p.lo * 10, p.hi * 10))
        while (knots.last().first < total) {
            knots += (knots.last().first + 30 * rng.between(p.knotMin, p.knotMax)) to rng.between(p.lo * 10, p.hi * 10)
        }
        val tempKnots = mutableListOf(0 to rng.between(p.tempLo, p.tempHi))
        while (tempKnots.last().first < n) {
            tempKnots += (tempKnots.last().first + rng.between(p.tempKnotMin, p.tempKnotMax)) to rng.between(p.tempLo, p.tempHi)
        }
        val out = ArrayList<CareSensAirCalibration.Record>(n)
        var seq = p.start
        var k = 0
        var tk = 0
        var dropout = 0
        for (r in 0 until n) {
            if (r > 0) {
                seq++
                if (p.gapPermille != 0 && rng.below(1000) < p.gapPermille) seq += rng.between(1, p.gapMax)
            }
            while (tempKnots[tk + 1].first <= r) tk++
            val (a, ta) = tempKnots[tk]
            val (b, tb) = tempKnots[tk + 1]
            val hundredths = if (p.tempMode == "step") ta.toDouble() else floor(ta + (tb - ta).toDouble() * (r - a) / (b - a))
            val temperature = hundredths / 100.0
            val factor = 1.0 - (1.0 - p.decay) * r / n
            if (p.dropoutPermille != 0 && dropout == 0 && rng.below(1000) < p.dropoutPermille) {
                dropout = rng.between(p.dropoutMin, p.dropoutMax)
            }
            val row = IntArray(30)
            for (j in 0 until 30) {
                val pos = r * 30 + j
                while (knots[k + 1].first <= pos) k++
                val (ka, ga) = knots[k]
                val (kb, gb) = knots[k + 1]
                val glucose = (ga + (gb - ga).toDouble() * (pos - ka) / (kb - ka)) / 10.0
                val noise = (rng.between(-p.noise, p.noise) + rng.between(-p.noise, p.noise) +
                    rng.between(-p.noise, p.noise) + rng.between(-p.noise, p.noise)) / 2.0
                val current = if (dropout != 0) 0.15
                else glucose * slope / 100.0 * (1.0 + 0.0328 * (temperature - 34.0854)) * factor + offset
                val adc = floor((current / 100.0 + eapp) * 40950.0 / vref + noise + 0.5)
                row[j] = min(65535.0, max(0.0, adc)).toInt()
            }
            if (dropout != 0) dropout--
            if (p.spikePermille != 0 && rng.below(1000) < p.spikePermille) {
                val j = rng.below(2)
                row[j] = min(65535.0, floor(row[j] * 1.6)).toInt()
            }
            var t = BASE_TIME + 300L * seq
            if (p.jitter != 0) t += rng.between(-p.jitter, p.jitter)
            if (p.clockJumpAt in 0..r) t += p.clockJump
            out += CareSensAirCalibration.Record(seq and 0xffff, t and 0xffffffffL, row, temperature)
        }
        return out
    }
}
