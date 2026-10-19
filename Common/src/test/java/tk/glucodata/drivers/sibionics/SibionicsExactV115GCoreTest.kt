package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsExactV115GCoreTest {
    @Test
    fun qrContainsSensitivityThatIsMissingFromTheBleName() {
        assertEquals(1.40f, SibionicsSensitivity.tryDecode("46HU804E")!!, 0.0001f)
        assertNull(SibionicsSensitivity.tryDecode("LT260346HU"))
    }

    @Test
    fun manualSensitivityUsesTheSameSupportedRangeAsQrSensitivity() {
        assertTrue(SibionicsSensitivity.isSupported(0.8f))
        assertTrue(SibionicsSensitivity.isSupported(2.5f))
        assertFalse(SibionicsSensitivity.isSupported(0.79f))
        assertFalse(SibionicsSensitivity.isSupported(2.51f))
        assertFalse(SibionicsSensitivity.isSupported(Float.NaN))
    }

    @Test
    fun thisReplayConvergesToTheSameOutputWithFallbackSensitivity() {
        val qrCore = SibionicsExactV115GCore(decodedSensitivity = 1.40f)
        val fallbackCore = SibionicsExactV115GCore(decodedSensitivity = 1.27f)
        var compared = 0
        replayRows().forEach { row ->
            val qrValue = qrCore.process(row.rawMmol, row.temperatureC, row.index)
            val fallbackValue = fallbackCore.process(row.rawMmol, row.temperatureC, row.index)
            if (qrValue != null || fallbackValue != null) {
                compared++
                assertEquals("fallback output at ${row.index}", qrValue, fallbackValue)
            }
        }

        assertEquals(3_153, compared)
    }

    @Test
    fun storageRangeMatchesTheProprietaryAlgorithmRange() {
        assertTrue(SibionicsConstants.isValidAlgorithmGlucoseMgdl(29.2f * SibionicsConstants.MGDL_PER_MMOLL))
        assertTrue(SibionicsConstants.isValidAlgorithmGlucoseMgdl(50.0f * SibionicsConstants.MGDL_PER_MMOLL))
        assertFalse(SibionicsConstants.isValidAlgorithmGlucoseMgdl(50.1f * SibionicsConstants.MGDL_PER_MMOLL))
    }

    @Test
    fun driverEmitsOneReadingForEveryValidOneMinuteSample() {
        val algorithm = SibionicsAlgorithmContext("one-minute-cadence")
        algorithm.configure(shortCode = "", sensitivity = 1.40f)
        val outputs = replayRows().associate { row ->
            row.index to algorithm.process(
                rawMmol = row.rawMmol,
                temperatureC = row.temperatureC,
                index = row.index,
                mode = SibionicsAlgorithmMode.REPLAY,
            )
        }

        assertEquals(replayRows().size, outputs.size)
        assertTrue(outputs.values.all { it.isFinite() && it > 0f })
        assertEquals(29.0f, outputs.getValue(25), 0.0001f)
        assertEquals(16.8f, outputs.getValue(30), 0.0001f)
        assertEquals(16.7f, outputs.getValue(31), 0.0001f)
        assertEquals(6.1f, outputs.getValue(35), 0.0001f)
        assertEquals(6.0f, outputs.getValue(36), 0.0001f)
    }

    @Test
    fun driverSnapshotPreservesOneMinuteContinuation() {
        val rows = replayRows()
        val uninterrupted = SibionicsAlgorithmContext("uninterrupted")
        val restored = SibionicsAlgorithmContext("restored")
        uninterrupted.configure(shortCode = "", sensitivity = 1.40f)
        restored.configure(shortCode = "", sensitivity = 1.40f)
        val splitAt = rows.indexOfFirst { it.index == 7_500 }
        require(splitAt > 0)

        rows.take(splitAt).forEach { row ->
            uninterrupted.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
        }
        assertTrue(restored.restore(uninterrupted.snapshot()))

        rows.drop(splitAt).forEach { row ->
            val expected = uninterrupted.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
            val actual = restored.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
            assertEquals("one-minute output after restore at ${row.index}", expected, actual, 0.0001f)
        }
    }

    @Test
    fun liveOneMinuteReadingsReuseTheLatestReplayCorrection() {
        val rows = replayRows().associateBy { it.index }
        val algorithm = SibionicsAlgorithmContext("history-to-live")
        algorithm.configure(shortCode = "", sensitivity = 1.40f)

        (25..30).forEach { index ->
            val row = rows.getValue(index)
            algorithm.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
        }
        val firstLive = rows.getValue(31)
        val secondLive = rows.getValue(32)

        assertEquals(
            16.7f,
            algorithm.process(firstLive.rawMmol, firstLive.temperatureC, firstLive.index, SibionicsAlgorithmMode.LIVE),
            0.0001f,
        )
        assertEquals(
            16.6f,
            algorithm.process(secondLive.rawMmol, secondLive.temperatureC, secondLive.index, SibionicsAlgorithmMode.LIVE),
            0.0001f,
        )
    }

    @Test
    fun newerReplayCorrectionSupersedesStaleLiveFallbackAfterReconnect() {
        val rows = replayRows().associateBy { it.index }
        val algorithm = SibionicsAlgorithmContext("reconnect-history-to-live")
        algorithm.configure(shortCode = "", sensitivity = 1.40f)

        (25..30).forEach { index ->
            val row = rows.getValue(index)
            algorithm.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
        }
        rows.getValue(31).let { row ->
            algorithm.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.LIVE)
        }
        (32..35).forEach { index ->
            val row = rows.getValue(index)
            algorithm.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
        }

        val current = rows.getValue(36)
        assertEquals(
            6.0f,
            algorithm.process(
                current.rawMmol,
                current.temperatureC,
                current.index,
                SibionicsAlgorithmMode.LIVE,
            ),
            0.0001f,
        )
    }

    @Test
    fun replayMatchesProprietaryV115GOutput() {
        val core = SibionicsExactV115GCore(decodedSensitivity = 1.40f)
        var displayCount = 0
        replayRows().forEach { row ->
            val actual = core.process(row.rawMmol, row.temperatureC, row.index)
            if (row.exactMmol == null) {
                assertNull("unexpected display at index ${row.index}", actual)
            } else {
                displayCount++
                assertNotNull("missing display at index ${row.index}", actual)
                assertEquals("display at index ${row.index}", row.exactMmol, actual!!, 0.0001f)
            }
        }
        assertEquals(3_153, displayCount)
    }

    @Test
    fun snapshotRestoresExactStateMidReplay() {
        val rows = replayRows()
        val uninterrupted = SibionicsExactV115GCore(decodedSensitivity = 1.40f)
        val restored = SibionicsExactV115GCore(decodedSensitivity = 1.40f)
        val splitAt = rows.indexOfFirst { it.index == 7_500 }
        require(splitAt > 0)

        rows.take(splitAt).forEach { row ->
            uninterrupted.process(row.rawMmol, row.temperatureC, row.index)
        }
        assertTrue(restored.restore(uninterrupted.snapshot()))

        rows.drop(splitAt).forEach { row ->
            val expected = uninterrupted.process(row.rawMmol, row.temperatureC, row.index)
            val actual = restored.process(row.rawMmol, row.temperatureC, row.index)
            if (expected == null) {
                assertNull("restored non-display index ${row.index}", actual)
            } else {
                assertNotNull("restored missing display index ${row.index}", actual)
                assertEquals("restored display index ${row.index}", expected, actual!!, 0.0001f)
            }
        }
    }

    @Test
    fun restoresA11xCheckpointWithoutTheHeldStageTerms() {
        // 1.2 appended the held stage terms and bumped the version; rejecting the
        // 1.1.x checkpoint forced a replay of the sensor's whole life on update.
        val rows = replayRows()
        val uninterrupted = SibionicsExactV115GCore(decodedSensitivity = 1.40f)
        val restored = SibionicsExactV115GCore(decodedSensitivity = 1.40f)
        val splitAt = rows.indexOfFirst { it.index == 7_500 }
        require(splitAt > 0)
        rows.take(splitAt).forEach { row ->
            uninterrupted.process(row.rawMmol, row.temperatureC, row.index)
        }

        assertTrue(restored.restore(asPreObservationSnapshot(uninterrupted.snapshot())))

        rows.drop(splitAt).forEach { row ->
            val expected = uninterrupted.process(row.rawMmol, row.temperatureC, row.index)
            val actual = restored.process(row.rawMmol, row.temperatureC, row.index)
            if (expected == null) {
                assertNull("legacy-restored non-display index ${row.index}", actual)
            } else {
                assertNotNull("legacy-restored missing display index ${row.index}", actual)
                assertEquals("legacy-restored display index ${row.index}", expected, actual!!, 0.0001f)
            }
        }
    }

    @Test
    fun rejectsALegacyCheckpointForADifferentSensitivity() {
        val source = SibionicsExactV115GCore(decodedSensitivity = 1.40f)
        replayRows().take(200).forEach { source.process(it.rawMmol, it.temperatureC, it.index) }
        val other = SibionicsExactV115GCore(decodedSensitivity = 1.27f)
        assertFalse(other.restore(asPreObservationSnapshot(source.snapshot())))
    }

    /**
     * Rewrites a current (v3) snapshot into the 1.1.x (v2) layout: the same
     * fields without heldBase (f32), heldEsaCompensation (f32) and
     * hasStageState (bool), which sit just before the trailing clip block.
     */
    private fun asPreObservationSnapshot(current: ByteArray): ByteArray {
        val buffer = java.nio.ByteBuffer.wrap(current)
        val clipSize = (current.size - 4 downTo 1).first { size ->
            buffer.getInt(current.size - 4 - size) == size
        }
        val heldStart = current.size - 4 - clipSize - 9
        val legacy = current.copyOfRange(0, heldStart) + current.copyOfRange(heldStart + 9, current.size)
        java.nio.ByteBuffer.wrap(legacy).putInt(4, 2)
        return legacy
    }

    private fun replayRows(): List<ReplayRow> =
        resourceLines("sibionics_exact_v115g_replay.csv")
            .asSequence()
            .drop(1)
            .map { line ->
                val fields = line.split(',')
                val index = fields[0].toInt()
                ReplayRow(
                    index = index,
                    rawMmol = fields[1].toFloat(),
                    temperatureC = fields[2].toFloat(),
                    exactMmol = fields[3].toFloat().takeIf { index % 5 == 0 },
                )
            }
            .toList()

    private fun resourceLines(name: String): List<String> =
        javaClass.classLoader!!
            .getResourceAsStream(name)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: error("missing Sibionics exact fixture $name")

    private data class ReplayRow(
        val index: Int,
        val rawMmol: Float,
        val temperatureC: Float,
        val exactMmol: Float?,
    )
}
