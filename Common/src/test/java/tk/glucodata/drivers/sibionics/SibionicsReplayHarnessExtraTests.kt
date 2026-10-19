package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsReplayHarnessExtraTests {

    private fun row(
        index: Int,
        stockMmol: Float = 8f,
        v1Mmol: Float = 7.5f,
        v2Mmol: Float = 8.5f,
    ) = SibionicsReplayHarness.Row(
        index = index,
        timestampMs = 1_700_000_000_000L + index * 60_000L,
        rawMmol = 8f,
        temperatureC = 34f,
        impedance = 1_000f,
        chemicalMmol = 8f,
        calibratedMmol = 8f,
        sensorStateCompensationMmol = 0f,
        activeSensitivity = 1.27f,
        factorySensitivity = 1.27f,
        stockMmol = stockMmol,
        adaptiveV1Mmol = v1Mmol,
        adaptiveV2Mmol = v2Mmol,
        diagnostics = null,
    )

    @Test
    fun emptyRowsSummariseToNaNMeans() {
        val summary = SibionicsReplayHarness.summarise(emptyList())
        assertEquals(0, summary.samples)
        assertTrue(summary.meanV2MinusStock.isNaN())
        assertTrue(summary.meanV1MinusStock.isNaN())
        assertTrue(summary.meanIntervalWidth.isNaN())
        assertTrue(summary.meanArtifactProbability.isNaN())
    }

    @Test
    fun summariseAveragesDeltasAgainstStock() {
        val summary = SibionicsReplayHarness.summarise(listOf(row(0), row(1)))
        assertEquals(2, summary.samples)
        assertEquals(0.5, summary.meanV2MinusStock, 0.0001)
        assertEquals(-0.5, summary.meanV1MinusStock, 0.0001)
        assertEquals(0.5, summary.worstV2MinusStock, 0.0001)
    }

    @Test
    fun worstDeltaKeepsLargestMagnitudeWithSign() {
        val summary = SibionicsReplayHarness.summarise(
            listOf(row(0, v2Mmol = 8.5f), row(1, v2Mmol = 7.2f)),
        )
        assertEquals(-0.8, summary.worstV2MinusStock, 0.0001)
    }

    @Test
    fun fromIndexSkipsEarlierRows() {
        val summary = SibionicsReplayHarness.summarise(listOf(row(0), row(1)), fromIndex = 1)
        assertEquals(1, summary.samples)
    }

    @Test
    fun nonUsableStockRowsAreIgnored() {
        val summary = SibionicsReplayHarness.summarise(
            listOf(row(0, stockMmol = Float.NaN), row(1, stockMmol = 0f)),
        )
        assertEquals(0, summary.samples)
        assertTrue(summary.meanV2MinusStock.isNaN())
    }

    @Test
    fun summaryToStringIsStable() {
        val summary = SibionicsReplayHarness.summarise(listOf(row(0)))
        val text = summary.toString()
        assertTrue(text.startsWith("n=1 V2-stock mean="))
        assertTrue(text.contains("worst="))
        assertTrue(text.contains("width=NaN pArtifact=NaN"))
    }

    @Test
    fun csvWithoutDiagnosticsPadsNaNAndMatchesHeaderWidth() {
        val csv = SibionicsReplayHarness.toCsv(listOf(row(0)))
        val lines = csv.trim().split("\n")
        assertEquals(2, lines.size)
        assertEquals(SibionicsReplayHarness.Row.CSV_HEADER, lines[0])
        val columns = lines[1].split(",")
        assertEquals(SibionicsReplayHarness.Row.CSV_HEADER.split(",").size, columns.size)
        assertTrue(columns.takeLast(17).all { it == "NaN" })
    }
}
