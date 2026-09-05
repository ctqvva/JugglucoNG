package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.sibionics.v116a.SibionicsExactV116ACore

class SibionicsExactV116ACoreTest {
    @Test
    fun startupReplayMatchesOfficialV116AValuesAndState() {
        val core = SibionicsExactV116ACore(decodedSensitivity = 1.44f)
        val stateHashes = mapOf(
            1 to "c2fec42883fbfcab",
            2 to "f6b369ff13fd358e",
            5 to "0433deef56d902b4",
            50 to "36626f30fef1722b",
            80 to "b3c3dfb7bf444718",
            85 to "7e05a7636421942d",
            100 to "d825ee49e926eae3",
        )

        startupRows().forEach { row ->
            val actual = core.process(row.rawMmol, row.temperatureC, row.index)
            if (row.exactMmol == 0f) {
                assertNull("unexpected V116A display at ${row.index}", actual)
            } else {
                assertNotNull("missing V116A display at ${row.index}", actual)
                assertEquals("V116A display at ${row.index}", row.exactMmol, actual!!, 0.0001f)
            }
            stateHashes[row.index]?.let { expected ->
                assertEquals("V116A native state at ${row.index}", expected, core.stateHash())
            }
        }
    }

    @Test
    fun snapshotRestoresExactV116AContinuation() {
        val rows = startupRows()
        val uninterrupted = SibionicsExactV116ACore(decodedSensitivity = 1.44f)
        val restored = SibionicsExactV116ACore(decodedSensitivity = 1.44f)

        rows.takeWhile { it.index <= 70 }.forEach { row ->
            uninterrupted.process(row.rawMmol, row.temperatureC, row.index)
        }
        assertTrue(restored.restore(uninterrupted.snapshot()))

        rows.dropWhile { it.index <= 70 }.forEach { row ->
            assertEquals(
                "V116A continuation at ${row.index}",
                uninterrupted.process(row.rawMmol, row.temperatureC, row.index),
                restored.process(row.rawMmol, row.temperatureC, row.index),
            )
            assertEquals("V116A state continuation at ${row.index}", uninterrupted.stateHash(), restored.stateHash())
            // The held sensor-state terms live outside the vendor context, so
            // stateHash alone would not notice them being lost across a
            // restore — and losing them costs Adaptive V2 a whole stage of
            // observations rather than failing loudly.
            assertEquals(
                "V116A observation continuation at ${row.index}",
                uninterrupted.latestSensorObservation,
                restored.latestSensorObservation,
            )
        }
    }

    @Test
    fun v120FamiliesUseV116AAndStillEmitEveryMinute() {
        val rows = startupRows()
        for (variant in listOf(
            SibionicsConstants.Variant.EU,
            SibionicsConstants.Variant.HEMATONIX,
            SibionicsConstants.Variant.SIBIONICS2,
        )) {
            val algorithm = SibionicsAlgorithmContext("v116-${variant.id}")
            algorithm.configure(shortCode = "0316015A", sensitivity = 1.44f, variant = variant)
            val values = rows.associate { row ->
                row.index to algorithm.process(
                    rawMmol = row.rawMmol,
                    temperatureC = row.temperatureC,
                    index = row.index,
                    mode = SibionicsAlgorithmMode.REPLAY,
                )
            }

            assertEquals(rows.size, values.size)
            assertTrue(values.values.all { it.isFinite() && it > 0f })
            assertEquals("${variant.id} index 80", 4.0f, values.getValue(80), 0.0001f)
            assertEquals("${variant.id} index 81", 3.9f, values.getValue(81), 0.0001f)
            assertEquals("${variant.id} index 84", 3.7f, values.getValue(84), 0.0001f)
            assertEquals("${variant.id} index 85", 3.8f, values.getValue(85), 0.0001f)
        }
    }

    @Test
    fun wrapperSnapshotCannotCrossAlgorithmFamilies() {
        val source = SibionicsAlgorithmContext("v116-source")
        source.configure("0316015A", 1.44f, SibionicsConstants.Variant.EU)
        startupRows().takeWhile { it.index <= 70 }.forEach { row ->
            source.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
        }
        val snapshot = source.snapshot()

        val matching = SibionicsAlgorithmContext("v116-matching")
        matching.configure("0316015A", 1.44f, SibionicsConstants.Variant.HEMATONIX)
        assertTrue(matching.restore(snapshot))

        val chinese = SibionicsAlgorithmContext("v115")
        chinese.configure("GEPD802J", 1.44f, SibionicsConstants.Variant.CHINESE)
        assertFalse(chinese.restore(snapshot))
    }

    private fun startupRows(): List<StartupRow> =
        javaClass.classLoader!!
            .getResourceAsStream("sibionics_exact_v116a_startup.csv")
            ?.bufferedReader()
            ?.useLines { lines ->
                lines.drop(1).map { line ->
                    val fields = line.split(',')
                    StartupRow(
                        index = fields[0].toInt(),
                        rawMmol = fields[1].toFloat(),
                        temperatureC = fields[2].toFloat(),
                        exactMmol = fields[3].toFloat(),
                    )
                }.toList()
            }
            ?: error("missing Sibionics V116A startup fixture")

    private data class StartupRow(
        val index: Int,
        val rawMmol: Float,
        val temperatureC: Float,
        val exactMmol: Float,
    )
}
