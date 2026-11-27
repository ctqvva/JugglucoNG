import tk.glucodata.drivers.caresens.calibration.*
import java.io.*
import java.nio.*
// stdin: f32 slope,vref,eapp ; i32 n ; n * (u16 seq, u32 time, 30*u16, f64 temp) ; optional restore index i32 (-1 none)
// stdout per record: seqFinal i32, glucose f64, trend f64, err i32, dtype i32, exact i32, hist 6*f64, histseq 6*i32, cg f64, resc f64, corr f64, level i32
fun main() {
    val inp = DataInputStream(BufferedInputStream(System.`in`))
    val buf = inp.readBytes(); val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
    val p = CareSensAirFactoryParameters(b.float, b.float, b.float); val n = b.int; val restoreAt = b.int
    val vendorAt = b.int
    var e = CareSensAirCalibration(p)
    if (vendorAt >= 0) { val st = ByteArray(117312); b.get(st); e = CareSensAirCalibration(p, CareSensAirVendorState.read(st)) }
    val out = ByteBuffer.allocate(n * 200).order(ByteOrder.LITTLE_ENDIAN)
    for (k in 0 until n) {
        if (k == restoreAt) e = CareSensAirCalibration(p, CareSensAirCalibrationState.fromBytes(e.state.toBytes())!!)
        val seq = b.short.toInt() and 0xffff; val t = b.int.toLong() and 0xffffffffL
        val adc = IntArray(30) { b.short.toInt() and 0xffff }; val temp = b.double
        val r = e.process(CareSensAirCalibration.Record(seq, t, adc, temp))
        out.putInt(r.sequenceFinal); out.putDouble(r.glucose); out.putDouble(r.trend); out.putInt(r.errorCode); out.putInt(r.dataType); out.putInt(if (r.exact) 1 else 0)
        r.history.forEach { out.putDouble(it) }; r.historySequence.forEach { out.putInt(it) }
        out.putDouble(r.intermediates.factoryGlucose); out.putDouble(r.intermediates.rescaled); out.putDouble(r.intermediates.correctedCurrent); out.putInt(r.intermediates.diabetesLevel); r.intermediates.minuteCurrents.forEach { out.putDouble(it) }
    }
    System.out.write(out.array(), 0, out.position()); System.out.flush()
}
