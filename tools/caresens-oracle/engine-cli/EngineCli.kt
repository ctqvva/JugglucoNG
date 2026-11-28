// Command-line driver for differential.py: reads records from stdin, runs the
// open engine and writes its results, including intermediates, to stdout.
//
// stdin  (little endian): f32 slope100, vref, eapp; i32 n; i32 vendorStateFollows;
//        [117312-byte vendor state]; n * packed air1_opcal4_cgm_input_t (74 bytes)
// stdout per record: see RESULT in differential.py
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tk.glucodata.drivers.caresens.calibration.CareSensAirCalibration
import tk.glucodata.drivers.caresens.calibration.CareSensAirFactoryParameters
import tk.glucodata.drivers.caresens.calibration.CareSensAirVendorState

fun main() {
    val b = ByteBuffer.wrap(System.`in`.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val p = CareSensAirFactoryParameters(b.float, b.float, b.float)
    val n = b.int
    val engine = if (b.int != 0) {
        CareSensAirCalibration(p, CareSensAirVendorState.read(ByteArray(CareSensAirVendorState.SIZE).also { b.get(it) }, p))
    } else CareSensAirCalibration(p)
    val out = ByteBuffer.allocate(n * 160).order(ByteOrder.LITTLE_ENDIAN)
    repeat(n) {
        val seq = b.short.toInt() and 0xffff
        val time = b.int.toLong() and 0xffffffffL
        val adc = IntArray(30) { b.short.toInt() and 0xffff }
        val r = engine.process(CareSensAirCalibration.Record(seq, time, adc, b.double))
        out.putInt(r.sequenceFinal); out.putDouble(r.glucose); out.putDouble(r.trend)
        out.putInt(r.errorCode); out.putInt(r.dataType); out.putInt(if (r.exact) 1 else 0)
        r.history.forEach(out::putDouble)
        r.intermediates.minuteCurrents.forEach(out::putDouble)
        out.putDouble(r.intermediates.correctedCurrent); out.putDouble(r.intermediates.factoryGlucose)
        out.putDouble(r.intermediates.rescaled)
    }
    System.out.write(out.array(), 0, out.position())
    System.out.flush()
}
