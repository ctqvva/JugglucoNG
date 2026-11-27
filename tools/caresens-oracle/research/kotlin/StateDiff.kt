import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tk.glucodata.drivers.caresens.calibration.*
// args: recs.bin vendorstate.bin n slope vref eapp
fun main(a: Array<String>) {
    val b = ByteBuffer.wrap(File(a[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val n = a[2].toInt()
    val p = CareSensAirFactoryParameters(a[3].toFloat(), a[4].toFloat(), a[5].toFloat())
    val e = CareSensAirCalibration(p)
    repeat(n) {
        val seq = b.short.toInt() and 0xffff; val time = b.int.toLong() and 0xffffffffL
        val adc = IntArray(30) { b.short.toInt() and 0xffff }
        e.process(CareSensAirCalibration.Record(seq, time, adc, b.double))
    }
    val v = CareSensAirVendorState.read(File(a[1]).readBytes(), p)
    for (f in CareSensAirCalibrationState::class.java.declaredFields) {
        f.isAccessible = true
        val x = f.get(e.state); val y = f.get(v)
        val sx = when (x) { is DoubleArray -> x.contentToString(); is IntArray -> x.contentToString(); is LongArray -> x.contentToString(); else -> x.toString() }
        val sy = when (y) { is DoubleArray -> y.contentToString(); is IntArray -> y.contentToString(); is LongArray -> y.contentToString(); else -> y.toString() }
        if (sx != sy) println("${f.name}\n  fresh  $sx\n  vendor $sy")
    }
}
