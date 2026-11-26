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
    private val modelledErrors =
        CareSensAirCalibration.ERROR_LOW or CareSensAirCalibration.ERROR_TIMING or CareSensAirCalibration.ERROR_PARAMETERS

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
            // Inexact marks values that rest on the reference's low-signal flag.
            if (!got.exact) {
                unmodelled.merge(-1, 1, Int::plus)
                return@forEachIndexed
            }
            close("$at factory glucose", want.factoryGlucose, got.intermediates.factoryGlucose)
            close("$at glucose", want.glucose, got.glucose)
            close("$at trend", want.trend, got.trend)
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
            if (unmodelled.isNotEmpty()) report.append("$name: reference-only error bits / records not claimed exact (-1) $unmodelled\n")
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
    fun continuesFromTheVendorLibrarysState() {
        val bytes = GZIPInputStream(javaClass.getResourceAsStream("/caresens/vendor-states.bin.gz")!!).readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("CSAS1", String(ByteArray(5).also { b.get(it) }))
        val fixture = load(String(ByteArray(b.get().toInt()).also { b.get(it) }))
        val records = CareSensAirScenarios.generate(fixture.spec)
        repeat(b.get().toInt()) {
            val after = b.int
            val block = ByteArray(CareSensAirVendorState.SIZE).also { b.get(it) }
            val engine = CareSensAirCalibration(fixture.spec.parameters, CareSensAirVendorState.read(block, fixture.spec.parameters))
            for (i in after until records.size) {
                val want = fixture.expected[i]
                val got = engine.process(records[i])
                val at = "${fixture.name} from vendor state after $after, record ${i + 1}"
                assertEquals("$at rejected record", want.dataType == 2, got.dataType == 2)
                if (want.dataType == 2) continue
                close("$at glucose", want.glucose, got.glucose)
                if (got.exact) close("$at trend", want.trend, got.trend)
                close("$at oldest history value", want.oldestHistory, got.history[0])
            }
        }
    }

    @Test
    fun shadowComparisonAcceptsTheReferenceAndSpotsADifference() {
        val bytes = GZIPInputStream(javaClass.getResourceAsStream("/caresens/vendor-states.bin.gz")!!).readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(5)
        val fixture = load(String(ByteArray(b.get().toInt()).also { b.get(it) }))
        b.get()
        b.int // first block: after the third record
        val state = ByteArray(CareSensAirVendorState.SIZE).also { b.get(it) }
        val after = b.int
        val block = ByteArray(CareSensAirVendorState.SIZE).also { b.get(it) }
        val record = CareSensAirScenarios.generate(fixture.spec)[after]
        val want = fixture.expected[after]
        val device = ByteBuffer.allocate(CareSensAirFactoryParameters.BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(5, fixture.spec.slope100).putFloat(434, fixture.spec.vref).putFloat(438, fixture.spec.eapp).array()
        val input = ByteBuffer.allocate(74).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(record.sequence.toShort()); putInt(record.timeSeconds.toInt())
            record.adc.forEach { putShort(it.toShort()) }; putDouble(record.temperatureC)
        }.array()
        fun output(glucose: Double) = ByteBuffer.allocate(155).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(2, want.sequenceFinal.toShort()); putDouble(68, glucose); putDouble(76, want.trend.toDouble())
            putShort(151, want.errorCode.toShort()); put(154, want.dataType.toByte())
        }.array()
        // Float fixture values: the engine's double result is within float rounding of them.
        val exact = CareSensAirCalibration(fixture.spec.parameters, CareSensAirVendorState.read(block, fixture.spec.parameters)).process(record)
        assertTrue(CareSensAirOpenCalibrationShadow.compare(device, block, input, output(exact.glucose), 1).matches)
        assertTrue(!CareSensAirOpenCalibrationShadow.compare(device, block, input, output(exact.glucose + 0.5), 1).matches)
        assertTrue(!CareSensAirOpenCalibrationShadow.compare(device, block, input, output(exact.glucose), 2).compared)
        close("glucose", want.glucose, exact.glucose)
        assertTrue(state.isNotEmpty())
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
