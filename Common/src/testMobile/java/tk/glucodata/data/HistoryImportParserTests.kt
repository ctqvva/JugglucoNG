package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.util.Calendar

class HistoryImportParserTests {

    private fun parse(text: String): HistoryImportParser.ParseResult =
        HistoryImportParser.parse(BufferedReader(StringReader(text)))

    private fun parseFixture(name: String): HistoryImportParser.ParseResult {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("missing fixture $name")
        return stream.bufferedReader().use { HistoryImportParser.parse(it) }
    }

    // ---- Juggluco TSV ----

    @Test
    fun jugglucoHistoryExport_importsEveryRowWithSecondsScaledToMilliseconds() {
        val result = parseFixture("juggluco-export-sample.tsv")

        assertNull(result.errorMessage)
        assertEquals(0, result.failedRows)
        assertEquals(15, result.readings.size)

        val first = result.readings.first()
        assertEquals(1_783_300_064_000L, first.timestamp)
        assertEquals(202f, first.valueMgDl, 0.001f)
        // The history export has no raw column, so the value doubles as raw.
        assertEquals(202f, first.rawValueMgDl, 0.001f)

        val last = result.readings.last()
        assertEquals(1_783_304_264_000L, last.timestamp)
        assertEquals(170f, last.valueMgDl, 0.001f)
    }

    @Test
    fun jugglucoMmolExport_convertsValuesToMgDl() {
        val result = parse(
            "Sensorid\tnr\tUnixTime\tYYYY-mm-ddTHH:MM:SS\tTZ\tMin\tmmol/L\n" +
                "SENSOR\t13\t1783300064\t2026-07-06T03:07:44\t2\t60\t5.5\n"
        )

        assertNull(result.errorMessage)
        assertEquals(1, result.readings.size)
        assertEquals(99.1f, result.readings[0].valueMgDl, 0.1f)
    }

    @Test
    fun jugglucoBothUnitLabel_isTreatedAsMgDl() {
        // Juggluco writes unitlabels[3] = "both" when the unit setting is
        // "both"; the exported number is mg/dL in that case.
        val result = parse(
            "Sensorid\tnr\tUnixTime\tYYYY-mm-ddTHH:MM:SS\tTZ\tMin\tboth\n" +
                "SENSOR\t13\t1783300064\t2026-07-06T03:07:44\t2\t60\t202\n"
        )

        assertEquals(1, result.readings.size)
        assertEquals(202f, result.readings[0].valueMgDl, 0.001f)
    }

    @Test
    fun jugglucoStreamExport_prefersItsRawColumnForTheRawValue() {
        val result = parse(
            "Sensorid\tnr\tUnixTime\tYYYY-mm-ddTHH:MM:SS\tTZ\tMin\tmg/dL\tRaw\tdiff\tRate\tChangeLabel\n" +
                "SENSOR\t13\t1783300064\t2026-07-06T03:07:44\t2\t60\t202\t196\t6.00\t+0\tFlat\n"
        )

        assertNull(result.errorMessage)
        assertEquals(1, result.readings.size)
        assertEquals(202f, result.readings[0].valueMgDl, 0.001f)
        assertEquals(196f, result.readings[0].rawValueMgDl, 0.001f)
    }

    @Test
    fun jugglucoExportWithoutUnixTime_fallsBackToTheLocalDateColumn() {
        val result = parse(
            "Sensorid\tnr\tYYYY-mm-ddTHH:MM:SS\tTZ\tMin\tmg/dL\n" +
                "SENSOR\t13\t2026-07-06T03:07:44\t2\t60\t202\n"
        )

        assertNull(result.errorMessage)
        assertEquals(1, result.readings.size)
        assertEquals(localMillis(2026, 7, 6, 3, 7, 44), result.readings[0].timestamp)
    }

    // ---- JugglucoNG CSV regressions ----

    @Test
    fun nativeCurrentCsv_importsGlucoseRowsAndIgnoresJournalAndPresetRows() {
        val result = parse(
            "Timestamp,Date,Value,RawValue,CalibratedValue,Unit,SensorSerial,RecordType," +
                "JournalId,JournalType,JournalTitle\n" +
                "1700000000000,2023-11-14 22:13:20,100,98,120,mg/dL,ABC123,glucose\n" +
                "\"1700000300000\",\"2023-11-14 22:18:20\",\"\",\"\",\"\",\"\",\"ABC123\"," +
                "\"journal_entry\",\"7\",\"meal\",\"Lunch\"\n" +
                "\"0\",\"\",\"\",\"\",\"\",\"\",\"\",\"journal_insulin_preset\",\"\",\"\",\"\"\n"
        )

        assertNull(result.errorMessage)
        assertEquals(0, result.failedRows)
        assertEquals(1, result.readings.size)
        assertEquals(1_700_000_000_000L, result.readings[0].timestamp)
        // CalibratedValue is derived and must not replace Value/RawValue.
        assertEquals(100f, result.readings[0].valueMgDl, 0.001f)
        assertEquals(98f, result.readings[0].rawValueMgDl, 0.001f)
    }

    @Test
    fun nativeLegacyFiveColumnCsv_stillImports() {
        val result = parse(
            "Timestamp,Date,Value,RawValue,Unit\n" +
                "1700000000000,2023-11-14 22:13:20,100,98,mg/dL\n"
        )

        assertNull(result.errorMessage)
        assertEquals(1, result.readings.size)
        assertEquals(1_700_000_000_000L, result.readings[0].timestamp)
        assertEquals(100f, result.readings[0].valueMgDl, 0.001f)
        assertEquals(98f, result.readings[0].rawValueMgDl, 0.001f)
    }

    @Test
    fun nativeLegacySixColumnCsv_stillImports() {
        val result = parse(
            "Timestamp,Date,Value,RawValue,Unit,SensorSerial\n" +
                "1700000000000,2023-11-14 22:13:20,5.5,5.4,mmol/L,ABC123\n"
        )

        assertNull(result.errorMessage)
        assertEquals(1, result.readings.size)
        assertEquals(99.1f, result.readings[0].valueMgDl, 0.1f)
        assertEquals(97.3f, result.readings[0].rawValueMgDl, 0.1f)
    }

    @Test
    fun nativeCsvWithUnknownColumnNames_fallsBackToLegacyPositions() {
        val result = parse(
            "Timestamp,Date,Glucose,Raw,Units\n" +
                "1700000000000,2023-11-14 22:13:20,100,98,mg/dL\n"
        )

        assertNull(result.errorMessage)
        assertEquals(1, result.readings.size)
        assertEquals(100f, result.readings[0].valueMgDl, 0.001f)
        assertEquals(98f, result.readings[0].rawValueMgDl, 0.001f)
    }

    // ---- Timestamps, units, robustness ----

    @Test
    fun secondsAndMillisecondTimestamps_landOnTheSameInstant() {
        val fromJuggluco = parse(
            "Sensorid\tnr\tUnixTime\tYYYY-mm-ddTHH:MM:SS\tTZ\tMin\tmg/dL\n" +
                "SENSOR\t13\t1700000000\t2023-11-14T22:13:20\t1\t60\t100\n"
        )
        val fromNative = parse(
            "Timestamp,Date,Value,RawValue,Unit\n" +
                "1700000000000,2023-11-14 22:13:20,100,98,mg/dL\n"
        )

        assertEquals(1, fromJuggluco.readings.size)
        assertEquals(1, fromNative.readings.size)
        assertEquals(fromNative.readings[0].timestamp, fromJuggluco.readings[0].timestamp)
    }

    @Test
    fun bomCrlfBlankLinesAndShortRows_keepTheImportGoing() {
        val result = parse(
            "﻿Timestamp,Date,Value,RawValue,Unit\r\n" +
                "1700000000000,2023-11-14 22:13:20,100,98,mg/dL\r\n" +
                "\r\n" +
                "1700000300000,2023-11-14 22:18:20\r\n" +
                "1700000600000,2023-11-14 22:23:20,102,101,mg/dL\r\n"
        )

        assertNull(result.errorMessage)
        assertEquals(2, result.readings.size)
        assertEquals(1, result.failedRows)
        assertEquals(100f, result.readings[0].valueMgDl, 0.001f)
        assertEquals(102f, result.readings[1].valueMgDl, 0.001f)
    }

    @Test
    fun unparseableTimestampOrValue_countsTheRowAndContinues() {
        val result = parse(
            "Sensorid\tnr\tUnixTime\tYYYY-mm-ddTHH:MM:SS\tTZ\tMin\tmg/dL\n" +
                "SENSOR\t13\tnot-a-time\t2026-07-06T03:07:44\t2\t60\t202\n" +
                "SENSOR\t14\t1783300364\t2026-07-06T03:12:44\t2\t65\t---\n" +
                "SENSOR\t15\t1783300663\t2026-07-06T03:17:43\t2\t70\t193\n"
        )

        assertNull(result.errorMessage)
        assertEquals(2, result.failedRows)
        assertEquals(1, result.readings.size)
        assertEquals(193f, result.readings[0].valueMgDl, 0.001f)
    }

    @Test
    fun reparsingTheSameFile_yieldsTheSameReadings() {
        val first = parseFixture("juggluco-export-sample.tsv")
        val second = parseFixture("juggluco-export-sample.tsv")

        assertEquals(first.readings, second.readings)
        assertEquals(
            first.readings.size,
            first.readings.distinctBy { it.timestamp }.size
        )
    }

    // ---- Rejection ----

    @Test
    fun unrecognisedHeader_namesTheDelimiterAndTheColumnItFound() {
        val result = parse("device\ttime\tglucose\n1\t2\t3\n")

        assertTrue(result.readings.isEmpty())
        val message = requireNotNull(result.errorMessage)
        assertTrue(message, message.contains("tab"))
        assertTrue(message, message.contains("\"device\""))
        assertTrue(message, message.contains("Timestamp"))
        assertTrue(message, message.contains("Sensorid"))
    }

    @Test
    fun unrecognisedCommaHeader_saysComma() {
        val result = parse("device,time,glucose\n1,2,3\n")

        val message = requireNotNull(result.errorMessage)
        assertTrue(message, message.contains("comma"))
    }

    @Test
    fun emptyFile_isReportedAsEmpty() {
        val result = parse("")

        assertTrue(result.readings.isEmpty())
        assertEquals("The file is empty.", result.errorMessage)
    }

    private fun localMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis
}
