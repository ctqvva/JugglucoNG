package tk.glucodata.drivers.caresens.calibration

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Compares the open engine with outputs of the vendor library recorded by
 * tools/caresens-oracle/make_fixtures.py. Values are stored as float, so the
 * tolerance is the float resolution, not the engine's (about 1e-12 mg/dL).
 */
class CareSensAirReferenceFixtureTest {
    private class Expected(
        val count: Int, val sequenceFinal: Int, val errorCode: Int, val dataType: Int,
        val glucose: Float, val trend: Float, val oldestHistory: Float, val factoryGlucose: Float,
    )

    private class Fixture(val name: String, val spec: CareSensAirScenarios.Spec, val inputDigest: ByteArray, val expected: List<Expected>)

    private fun names(set: String): List<String> =
        javaClass.getResourceAsStream("/caresens/index.txt")!!.bufferedReader().readLines()
            .map { it.split(' ') }.filter { it[0] == set }.map { it[1] }

    private fun load(name: String): Fixture {
        val bytes = GZIPInputStream(javaClass.getResourceAsStream("/caresens/$name.bin.gz")!!).readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(5).also { b.get(it) }
        assertEquals("CSAF1", String(magic))
        val header = ByteArray(b.short.toInt() and 0xffff).also { b.get(it) }
        b.position(b.position() + 32) // reference library digest
        val inputs = ByteArray(32).also { b.get(it) }
        val n = b.int
        val expected = List(n) {
            Expected(
                b.get().toInt() and 0xff, b.short.toInt() and 0xffff, b.short.toInt() and 0xffff, b.get().toInt() and 0xff,
                b.float, b.float, b.float, b.float,
            )
        }
        return Fixture(name, CareSensAirScenarios.Spec(parseFlatJson(String(header))), inputs, expected)
    }

    private fun digest(records: List<CareSensAirCalibration.Record>): ByteArray {
        val packed = ByteBuffer.allocate(records.size * 74).order(ByteOrder.LITTLE_ENDIAN)
        for (r in records) {
            packed.putShort(r.sequence.toShort()); packed.putInt(r.timeSeconds.toInt())
            r.adc.forEach { packed.putShort(it.toShort()) }
            packed.putDouble(r.temperatureC)
        }
        return MessageDigest.getInstance("SHA-256").digest(packed.array())
    }

    private fun close(what: String, expected: Float, actual: Double) {
        if (expected.isNaN() && actual.isNaN()) return
        if (Math.abs(expected - actual) > 1e-4 * Math.max(1.0, Math.abs(actual))) fail("$what: reference $expected, engine $actual")
    }

    /** Error checks the engine reproduces; the others are listed in the documentation. */
    private val modelledErrors = CareSensAirCalibration.ERROR_TIMING or CareSensAirCalibration.ERROR_PARAMETERS

    private fun check(fixture: Fixture): Map<Int, Int> {
        val records = CareSensAirScenarios.generate(fixture.spec)
        assertArrayEquals("${fixture.name}: generated inputs differ from the fixture's", fixture.inputDigest, digest(records))
        val engine = CareSensAirCalibration(fixture.spec.parameters)
        val unmodelled = HashMap<Int, Int>()
        records.forEachIndexed { i, record ->
            val want = fixture.expected[i]
            val got = engine.process(record)
            val at = "${fixture.name} record ${i + 1} (seq ${record.sequence})"
            if (want.count > 1) {
                assertTrue("$at: the reference filled missed records; the engine must say it is not exact", !got.exact)
                return unmodelled
            }
            assertEquals("$at sequence", want.sequenceFinal, got.sequenceFinal)
            assertEquals("$at modelled error bits", want.errorCode and modelledErrors, got.errorCode)
            (want.errorCode and modelledErrors.inv()).takeIf { it != 0 }?.let { unmodelled.merge(it, 1, Int::plus) }
            assertEquals("$at rejected record", want.dataType == 2, got.dataType == 2)
            if (want.dataType == 2 || want.errorCode and CareSensAirCalibration.ERROR_PARAMETERS != 0) return@forEachIndexed
            close("$at factory glucose", want.factoryGlucose, got.intermediates.factoryGlucose)
            close("$at glucose", want.glucose, got.glucose)
            // Inexact only marks a trend the reference may withhold (its low flag).
            if (got.exact) close("$at trend", want.trend, got.trend) else unmodelled.merge(-1, 1, Int::plus)
            close("$at oldest history value", want.oldestHistory, got.history[0])
        }
        return unmodelled
    }

    private fun checkSet(set: String) {
        val names = names(set)
        assertTrue(names.isNotEmpty())
        val report = StringBuilder()
        for (name in names) {
            val unmodelled = check(load(name))
            if (unmodelled.isNotEmpty()) report.append("$name: reference-only error bits / inexact trends (-1) $unmodelled\n")
        }
        if (report.isNotEmpty()) println(report)
    }

    @Test
    fun developmentScenariosMatchTheReference() = checkSet("development")

    @Test
    fun holdoutScenariosMatchTheReference() = checkSet("holdout")

    @Test
    fun restoringStateAnywhereContinuesIdentically() {
        val fixture = load("life-wide")
        val records = CareSensAirScenarios.generate(fixture.spec)
        val straight = CareSensAirCalibration(fixture.spec.parameters)
        var resumed = CareSensAirCalibration(fixture.spec.parameters)
        records.forEachIndexed { i, record ->
            if (i % 97 == 0) {
                resumed = CareSensAirCalibration(
                    fixture.spec.parameters,
                    CareSensAirCalibrationState.fromBytes(resumed.state.toBytes())!!,
                )
            }
            val a = straight.process(record)
            val b = resumed.process(record)
            assertEquals("glucose at ${i + 1}", a.glucose, b.glucose, 0.0)
            assertEquals("trend at ${i + 1}", a.trend, b.trend, 0.0)
            assertArrayEquals("history at ${i + 1}", a.history, b.history, 0.0)
        }
        assertArrayEquals(straight.state.toBytes(), resumed.state.toBytes())
    }

    @Test
    fun damagedOrForeignStateIsRefused() {
        val bytes = CareSensAirCalibrationState().toBytes()
        assertEquals(null, CareSensAirCalibrationState.fromBytes(bytes.copyOf(bytes.size - 1)))
        assertEquals(null, CareSensAirCalibrationState.fromBytes(bytes + 0))
        bytes[3] = 99
        assertEquals(null, CareSensAirCalibrationState.fromBytes(bytes))
    }

    companion object {
        /** Enough JSON for the flat spec objects the generator writes. */
        fun parseFlatJson(text: String): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            val body = text.trim().removePrefix("{").removeSuffix("}")
            val token = Regex("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|[-0-9.eE+]+)")
            for (m in token.findAll(body)) {
                val raw = m.groupValues[2]
                out[m.groupValues[1]] = when {
                    raw.startsWith("\"") -> raw.trim('"')
                    raw.contains('.') || raw.contains('e') || raw.contains('E') -> raw.toDouble()
                    else -> raw.toLong()
                }
            }
            return out
        }
    }
}
