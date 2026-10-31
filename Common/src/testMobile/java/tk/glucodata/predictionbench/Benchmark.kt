package tk.glucodata.predictionbench

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A lane to forecast from, and the lane its outcomes are read from. Usually the same; a
 * decimated input is scored against the full-resolution truth of the same sensor.
 */
class LanePair(
    val input: Lane,
    val truth: Lane = input,
    /** Forecast only from moments in this range; the lane before it still counts as history. */
    val originFromMs: Long = Long.MIN_VALUE,
    val originToMs: Long = Long.MAX_VALUE,
    val label: String = input.id,
)

class BenchConfig(
    val horizons: IntArray = intArrayOf(15, 30, 45, 60, 90, 120),
    val originStepMinutes: Int = 5,
    /** History a lane must have before its first forecast, so the kernel has its full window. */
    val warmupMinutes: Int = 60,
    val journalMode: JournalMode = JournalMode.NONE,
    val lowMgdl: Float = 70f,
    val bootstrapRounds: Int = 1000,
)

/** Every scored forecast of one run, column-wise. */
class BenchRun(
    val title: String,
    val config: BenchConfig,
    val lanes: List<LanePair>,
    val names: List<String>,
) {
    val laneIndex = ArrayList<Int>()
    val originTime = ArrayList<Long>()
    val day = ArrayList<Long>()
    val level = ArrayList<Float>()
    /** The arrow's velocity at the origin, mg/dL/min: the stratum, read causally. */
    val rate = ArrayList<Float>()
    val truth = ArrayList<FloatArray>()
    /** pred[origin][forecaster][horizon] */
    val pred = ArrayList<Array<FloatArray>>()
    val abstained = IntArray(names.size)
    var profiles: String = "app defaults everywhere"

    val size: Int get() = originTime.size

    /** Scored at horizon k: a truth exists and every forecaster answered. */
    fun scored(o: Int, k: Int): Boolean = truth[o][k].isFinite() && pred[o].all { it[k].isFinite() }
}

object Benchmark {

    fun run(title: String, lanes: List<LanePair>, dataset: BenchDataset, forecasters: List<Forecaster>, config: BenchConfig = BenchConfig()): BenchRun {
        val result = BenchRun(title, config, lanes, forecasters.map { it.name })
        val profiles = lanes.mapNotNull { dataset.profileFor(it.input)?.parametersAtMinute(0) }
        if (profiles.isNotEmpty()) {
            result.profiles = "${profiles.size} of ${lanes.size} lanes carry a profile: ISF " +
                "${profiles.minOf { it.insulinSensitivityMgDlPerUnit }.toInt()}–${profiles.maxOf { it.insulinSensitivityMgDlPerUnit }.toInt()} mg/dL/U, " +
                "carb ratio ${"%.1f".format(profiles.minOf { it.carbRatioGramsPerUnit })}–${"%.1f".format(profiles.maxOf { it.carbRatioGramsPerUnit })} g/U " +
                "(forecasters named \"app defaults\" ignore them)"
        }
        val horizons = config.horizons
        val maxHorizonMs = (horizons.maxOrNull() ?: 0) * 60_000L
        val zone = ZoneId.systemDefault()
        for ((laneIdx, pair) in lanes.withIndex()) {
            val lane = pair.input
            if (lane.size < 2) continue
            val firstAllowed = maxOf(lane.times[0] + config.warmupMinutes * 60_000L, pair.originFromMs)
            var nextOrigin = firstAllowed
            val laneJournal = dataset.journalFor(lane)
            val laneHistory = LaneJournal(laneJournal, dataset.presets)
            val profile = dataset.profileFor(lane)
            val truthGap = max(6 * 60_000L, (pair.truth.cadenceMinutes * 2.5 * 60_000).toLong())
            for (i in 1 until lane.size) {
                val t0 = lane.times[i]
                if (t0 > pair.originToMs) break
                if (t0 < nextOrigin) continue
                nextOrigin = t0 + config.originStepMinutes * 60_000L - 30_000L
                // A fresh reading with an hour of company, or the moment is not a live forecast.
                if (t0 - lane.times[i - 1] > 15 * 60_000L) continue
                val view = OriginView(lane, i, OriginView.journalFor(laneJournal, config.journalMode, t0, maxHorizonMs),
                    dataset.presets, dataset.unit, profile, laneHistory, config.journalMode)
                if (view.size - view.indexAtOrBefore(t0 - 60 * 60_000L) < 5) continue
                // An outcome is only ever the origin's own sensor: never the next one in a pooled lane.
                val segment = if (pair.truth.segments != null) lane.segmentAt(i) else null
                val truths = FloatArray(horizons.size) { truthAt(pair.truth, t0 + horizons[it] * 60_000L, truthGap, segment) }
                if (truths.none { it.isFinite() }) continue
                val preds = Array(forecasters.size) { f ->
                    val p = try {
                        forecasters[f].forecast(view, horizons)
                    } catch (e: Exception) {
                        throw IllegalStateException("${forecasters[f].name} failed at ${lane.id} t=$t0", e)
                    }
                    if (p.any { !it.isFinite() }) result.abstained[f]++
                    p
                }
                result.laneIndex += laneIdx
                result.originTime += t0
                // The bootstrap block: one person-day. Days of different people are independent.
                result.day += laneIdx * 1_000_000L + Instant.ofEpochMilli(t0).atZone(zone).toLocalDate().toEpochDay()
                result.level += view.mgdl(view.end)
                result.rate += trendEngineVelocity(view)
                result.truth += truths
                result.pred += preds
            }
        }
        return result
    }

    /** The lane's value at [t]: the reading on it, or a straight line between readings at most [maxGapMs] apart. */
    fun truthAt(lane: Lane, t: Long, maxGapMs: Long, segment: Int? = null): Float {
        var lo = 0
        var hi = lane.size - 1
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lane.times[mid] <= t) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (i < 0) return Float.NaN
        if (segment != null && lane.segmentAt(i) != segment) return Float.NaN
        if (lane.times[i] == t) return lane.mgdl(i)
        if (i + 1 >= lane.size) return Float.NaN
        if (segment != null && lane.segmentAt(i + 1) != segment) return Float.NaN
        val t0 = lane.times[i]
        val t1 = lane.times[i + 1]
        if (t1 - t0 > maxGapMs) return Float.NaN
        return lane.mgdl(i) + (lane.mgdl(i + 1) - lane.mgdl(i)) * ((t - t0).toFloat() / (t1 - t0).toFloat())
    }
}

/** Error statistics over a subset of origins, with day-block bootstrap intervals. */
class Scorer(private val run: BenchRun) {
    private val days = run.day.distinct().sorted()
    private val dayIndex = days.withIndex().associate { it.value to it.index }

    class Sums(val sse: DoubleArray, val sae: DoubleArray, val se: DoubleArray, val n: IntArray)

    /** Per-day sums for forecaster [f] at horizon [k] over origins passing [filter]. */
    fun sums(f: Int, k: Int, filter: (Int) -> Boolean = { true }): Sums {
        val s = Sums(DoubleArray(days.size), DoubleArray(days.size), DoubleArray(days.size), IntArray(days.size))
        for (o in 0 until run.size) {
            if (!run.scored(o, k) || !filter(o)) continue
            val d = dayIndex.getValue(run.day[o])
            val e = (run.pred[o][f][k] - run.truth[o][k]).toDouble()
            s.sse[d] += e * e
            s.sae[d] += abs(e)
            s.se[d] += e
            s.n[d]++
        }
        return s
    }

    fun rmse(s: Sums): Double = sqrt(s.sse.sum() / max(1, s.n.sum()))
    fun mae(s: Sums): Double = s.sae.sum() / max(1, s.n.sum())
    fun bias(s: Sums): Double = s.se.sum() / max(1, s.n.sum())

    /** 95 % interval of RMSE(a) − RMSE(b), resampling whole lane-days (readings within a day are not independent). */
    fun deltaInterval(a: Sums, b: Sums, seed: Int = 7): Pair<Double, Double> {
        val rng = Random(seed)
        val nd = days.size
        if (nd < 2) return Double.NaN to Double.NaN
        val deltas = DoubleArray(run.config.bootstrapRounds) {
            var sa = 0.0; var sb = 0.0; var n = 0
            repeat(nd) {
                val d = rng.nextInt(nd)
                sa += a.sse[d]; sb += b.sse[d]; n += a.n[d]
            }
            if (n == 0) 0.0 else sqrt(sa / n) - sqrt(sb / n)
        }
        deltas.sort()
        return deltas[(0.025 * deltas.size).toInt()] to deltas[(0.975 * deltas.size).toInt().coerceAtMost(deltas.size - 1)]
    }
}

object BenchReport {

    fun markdown(run: BenchRun, notes: List<String> = emptyList()): String {
        val sc = Scorer(run)
        val hs = run.config.horizons
        val names = run.names
        val persistence = names.indexOfFirst { it.startsWith("persistence") }.coerceAtLeast(0)
        val production = names.indexOfFirst { it.startsWith("production") }.coerceAtLeast(0)
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')
        fun f1(x: Double) = if (x.isFinite()) String.format(Locale.US, "%.1f", x) else "–"

        line("# ${run.title}")
        line()
        notes.forEach { line("- $it") }
        line("- Profiles: ${run.profiles}")
        line("- Origins: ${run.size} (every ${run.config.originStepMinutes} min), lane-days (bootstrap blocks): ${run.day.distinct().size}, journal: ${run.config.journalMode}")
        for (pair in run.lanes) {
            val n = run.laneIndex.count { run.lanes[it] === pair }
            val days = (pair.input.times.last() - pair.input.times.first()) / 86_400_000.0
            line("- Lane `${pair.label}` → truth `${pair.truth.id}`: ${pair.input.size} readings, ${f1(days)} days, cadence ${f1(pair.input.cadenceMinutes)} min, $n origins")
        }
        if (run.abstained.any { it > 0 }) {
            line("- Abstentions (origins dropped for everyone at that horizon): " +
                names.indices.filter { run.abstained[it] > 0 }.joinToString { "${names[it]}=${run.abstained[it]}" })
        }
        line()

        line("## RMSE (mg/dL)")
        line()
        line("| forecaster | " + hs.joinToString(" | ") { "${it} min" } + " |")
        line("|---|" + hs.joinToString("") { "---:|" })
        val all = names.indices.map { f -> hs.indices.map { k -> sc.sums(f, k) } }
        for (f in names.indices) line("| ${names[f]} | " + hs.indices.joinToString(" | ") { f1(sc.rmse(all[f][it])) } + " |")
        line("| *scored origins* | " + hs.indices.joinToString(" | ") { all[0][it].n.sum().toString() } + " |")
        line()

        for ((label, ref) in listOf("persistence" to persistence, "the production kernel" to production)) {
            line("## RMSE difference vs $label (negative = better), 95 % day-bootstrap interval")
            line()
            line("| forecaster | " + hs.joinToString(" | ") { "${it} min" } + " |")
            line("|---|" + hs.joinToString("") { "---:|" })
            for (f in names.indices) {
                if (f == ref) continue
                line("| ${names[f]} | " + hs.indices.joinToString(" | ") { k ->
                    val d = sc.rmse(all[f][k]) - sc.rmse(all[ref][k])
                    val (lo, hi) = sc.deltaInterval(all[f][k], all[ref][k])
                    val mark = if (hi < 0) "**" else ""
                    "$mark${f1(d)}$mark [${f1(lo)}, ${f1(hi)}]"
                } + " |")
            }
            line()
        }

        val k30 = hs.indexOf(30).takeIf { it >= 0 } ?: 0
        val k60 = hs.indexOf(60).takeIf { it >= 0 } ?: hs.lastIndex
        line("## MAE and bias (mg/dL) at 30 and 60 min")
        line()
        line("| forecaster | MAE 30 | bias 30 | MAE 60 | bias 60 |")
        line("|---|---:|---:|---:|---:|")
        for (f in names.indices) line("| ${names[f]} | ${f1(sc.mae(all[f][k30]))} | ${f1(sc.bias(all[f][k30]))} | ${f1(sc.mae(all[f][k60]))} | ${f1(sc.bias(all[f][k60]))} |")
        line()

        val strata = listOf<Pair<String, (Int) -> Boolean>>(
            "steady |rate|<0.5" to { o -> abs(run.rate[o]) < 0.5f },
            "moving 0.5–1.5" to { o -> abs(run.rate[o]) in 0.5f..1.5f },
            "fast ≥1.5" to { o -> abs(run.rate[o]) > 1.5f },
            "level <80" to { o -> run.level[o] < 80f },
            "level 80–180" to { o -> run.level[o] in 80f..180f },
            "level >180" to { o -> run.level[o] > 180f },
        )
        for ((label, k) in listOf("30" to k30, "60" to k60)) {
            line("## RMSE at $label min by situation at the forecast moment (rate in mg/dL/min)")
            line()
            line("| forecaster | " + strata.joinToString(" | ") { it.first } + " |")
            line("|---|" + strata.joinToString("") { "---:|" })
            val cells = names.indices.map { f -> strata.map { (_, filter) -> sc.sums(f, k, filter) } }
            for (f in names.indices) line("| ${names[f]} | " + cells[f].joinToString(" | ") { f1(sc.rmse(it)) } + " |")
            line("| *n* | " + cells[0].joinToString(" | ") { it.n.sum().toString() } + " |")
            line()
        }

        line("## Predicting a low (< ${run.config.lowMgdl.toInt()} mg/dL) at 30 min")
        line()
        line("Sensitivity = share of actual lows forecast below the line; precision = share of low forecasts that came true. Lows already present at the forecast moment are excluded.")
        line()
        line("*Same alarm count* ranks every forecaster's 30-minute values from lowest up and raises as many alarms as the production " +
            "kernel does: a threshold-free comparison of how well each one orders the risk.")
        line()
        line("| forecaster | sensitivity | precision | flagged | sensitivity at same alarm count |")
        line("|---|---:|---:|---:|---:|")
        val low = run.config.lowMgdl
        val eligible = (0 until run.size).filter { run.scored(it, k30) && run.level[it] >= low }
        val budget = eligible.count { run.pred[it][production][k30] < low }
        val positives = eligible.count { run.truth[it][k30] < low }
        for (f in names.indices) {
            var tp = 0; var fn = 0; var fp = 0
            for (o in 0 until run.size) {
                if (!run.scored(o, k30) || run.level[o] < low) continue
                val actual = run.truth[o][k30] < low
                val flagged = run.pred[o][f][k30] < low
                if (actual && flagged) tp++ else if (actual) fn++ else if (flagged) fp++
            }
            val sens = if (tp + fn == 0) Double.NaN else 100.0 * tp / (tp + fn)
            val prec = if (tp + fp == 0) Double.NaN else 100.0 * tp / (tp + fp)
            val ranked = eligible.sortedBy { run.pred[it][f][k30] }.take(budget)
            val atBudget = if (positives == 0) Double.NaN else 100.0 * ranked.count { run.truth[it][k30] < low } / positives
            line("| ${names[f]} | ${f1(sens)} % | ${f1(prec)} % | ${tp + fp} (of ${tp + fn} lows) | ${f1(atBudget)} % |")
        }
        line()

        val k15 = hs.indexOf(15).takeIf { it >= 0 } ?: 0
        line("## Per lane")
        line()
        line("Pooled tables weight each lane by its origins, so one long noisy sensor can decide them. " +
            "*Trend continuation* is the least-squares slope of the change seen over the next 15 min on the change the arrow's " +
            "rate predicts for it: 1 = trends run on as straight lines, 0 = they carry no information, < 0 = they reverse.")
        line()
        line("| lane | origins | trend continuation (15 min) | persistence RMSE 30 / 60 |")
        line("|---|---:|---:|---:|")
        for (li in run.lanes.indices) {
            val filter = { o: Int -> run.laneIndex[o] == li }
            var sxy = 0.0; var sxx = 0.0
            for (o in 0 until run.size) {
                if (!filter(o) || !run.truth[o][k15].isFinite()) continue
                val x = run.rate[o] * hs[k15].toDouble()
                sxy += x * (run.truth[o][k15] - run.level[o]); sxx += x * x
            }
            line("| `${run.lanes[li].label}` | ${run.laneIndex.count { it == li }} | ${if (sxx > 0) String.format(Locale.US, "%.2f", sxy / sxx) else "–"} | " +
                "${f1(sc.rmse(sc.sums(persistence, k30, filter)))} / ${f1(sc.rmse(sc.sums(persistence, k60, filter)))} |")
        }
        line()
        line("RMSE change vs persistence at 30 / 60 min, per lane (negative = better):")
        line()
        line("| forecaster | " + run.lanes.joinToString(" | ") { "`${it.label}`" } + " |")
        line("|---|" + run.lanes.joinToString("") { "---:|" })
        fun pct(f: Int, k: Int, filter: (Int) -> Boolean): String {
            val ref = sc.rmse(sc.sums(persistence, k, filter))
            return String.format(Locale.US, "%+.0f%%", 100 * (sc.rmse(sc.sums(f, k, filter)) / ref - 1))
        }
        for (f in names.indices) {
            if (f == persistence) continue
            line("| ${names[f]} | " + run.lanes.indices.joinToString(" | ") { li ->
                val filter = { o: Int -> run.laneIndex[o] == li }
                "${pct(f, k30, filter)} / ${pct(f, k60, filter)}"
            } + " |")
        }
        line()
        return sb.toString()
    }
}
