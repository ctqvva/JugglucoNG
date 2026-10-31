package tk.glucodata.predictionbench

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The benchmark itself. Does nothing unless pointed at data, so CI never runs it and no
 * personal record has to live in the repository:
 *
 * ```
 * JUGGLUCO_PREDBENCH_DATA='fixture:sibionics_v116a_real_events.csv:calibrated,stock;/path/to/Juggluco_Export.csv' \
 * JUGGLUCO_PREDBENCH_OUT=/tmp/predbench \
 *   ./gradlew :Common:testMobileDebugUnitTest --tests '*PredictionBenchmarkRun*' --rerun
 * ```
 *
 * `--rerun` matters: the environment is not a task input, so without it Gradle calls the
 * task up to date after any earlier run and silently produces nothing. JDK 17, as for every
 * unit test here.
 *
 * Sources are `;`-separated: an app CSV export, or `fixture:<test resource>:<columns>` for the
 * mmol/L column fixtures, or `azt1d:<folder>` for the AZT1D type 1 dataset (CC BY 4.0,
 * downloaded separately). Optional: `JUGGLUCO_PREDBENCH_SCENARIOS` (native, 5min, rounded, person;
 * default native,5min), `JUGGLUCO_PREDBENCH_JOURNAL` (comma list of none, operational,
 * oracle; default none), `JUGGLUCO_PREDBENCH_LANES` for exports (default auto,calibrated).
 * One Markdown report per source, scenario and journal mode lands in the output directory.
 */
class PredictionBenchmarkRun {

    @Test
    fun run() {
        val spec = System.getenv("JUGGLUCO_PREDBENCH_DATA")
        assumeTrue("set JUGGLUCO_PREDBENCH_DATA to run the prediction benchmark", !spec.isNullOrBlank())
        val out = File(System.getenv("JUGGLUCO_PREDBENCH_OUT") ?: "build/reports/predbench").apply { mkdirs() }
        val scenarios = (System.getenv("JUGGLUCO_PREDBENCH_SCENARIOS") ?: "native,5min").split(',').map { it.trim() }
        val journals = (System.getenv("JUGGLUCO_PREDBENCH_JOURNAL") ?: "none").split(',').map { JournalMode.valueOf(it.trim().uppercase()) }
        val lanes = (System.getenv("JUGGLUCO_PREDBENCH_LANES") ?: "auto,calibrated").split(',').map { it.trim() }.toSet()

        for (source in spec!!.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val dataset = load(source, lanes)
            for (scenario in scenarios) for (journal in journals) {
                val (pairs, note) = when (scenario) {
                    "native" -> dataset.lanes.map { LanePair(it) } to "Input and truth at the sensor's own cadence."
                    "5min" -> dataset.lanes.map { LanePair(it.decimated(5), it) } to
                        "Input thinned to one reading per 5 min, truth at full resolution. Same sensor noise — not a Dexcom/Libre 2 stream."
                    "rounded" -> dataset.lanes.map { LanePair(it.rounded(0.1f), it) } to
                        "Input rounded to 0.1 display units (as the CSV export stores it), truth unrounded."
                    "person" -> {
                        // One person's sensors end to end: learners keep what they learned across a
                        // sensor change. Scored per sensor period, so it lines up with "native".
                        // Oldest sensor first: the pairs share one learner, which may only move forward in time.
                        val auto = dataset.lanes.filter { it.laneName == "auto" }.ifEmpty { dataset.lanes }
                            .sortedBy { it.times.first() }
                        val person = Lane.person("person", auto)
                        auto.map { LanePair(person, person, it.times.first(), it.times.last(), "${it.id} (pooled)") } to
                            "All sensors as one person, scored per sensor period."
                    }
                    else -> error("unknown scenario $scenario")
                }
                val started = System.nanoTime()
                val run = Benchmark.run("Prediction benchmark — ${dataset.name} — $scenario", pairs, dataset,
                    Rosters.standard(), BenchConfig(journalMode = journal))
                val seconds = (System.nanoTime() - started) / 1e9
                val report = BenchReport.markdown(run, listOf(note, "Unit: ${dataset.unit}; run took ${"%.1f".format(seconds)} s"))
                File(out, "${dataset.name}-$scenario-${journal.name.lowercase()}.md").writeText(report)
                println(report)
            }
        }
    }

    private fun load(source: String, lanes: Set<String>): BenchDataset {
        if (source.startsWith("fixture:")) {
            val parts = source.removePrefix("fixture:").split(':')
            val resource = requireNotNull(javaClass.classLoader?.getResource(parts[0])) { "no test resource ${parts[0]}" }
            val columns = parts.getOrNull(1)?.split(',') ?: listOf("calibrated")
            return BenchLoaders.columnCsv(File(resource.toURI()), "timestampMs", columns, isMmol = true)
        }
        if (source.startsWith("azt1d:")) return BenchLoaders.azt1d(File(source.removePrefix("azt1d:")))
        if (source.startsWith("hupa:")) return BenchLoaders.hupa(File(source.removePrefix("hupa:")))
        return BenchLoaders.jugglucoExport(File(source), lanes)
    }
}
