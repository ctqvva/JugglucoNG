import java.nio.ByteBuffer
import java.nio.ByteOrder
import tk.glucodata.drivers.caresens.calibration.CareSensAirCalibration
import tk.glucodata.drivers.caresens.calibration.CareSensAirFactoryParameters
import tk.glucodata.drivers.caresens.calibration.CareSensAirVendorState
import tk.glucodata.drivers.caresens.calibration.Error16Prototype

/** Uses the standard oracle CLI input layout and emits the research detector's flags only. */
fun main() {
    val input = ByteBuffer.wrap(System.`in`.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val parameters = CareSensAirFactoryParameters(input.float, input.float, input.float)
    val count = input.int
    val detector = Error16Prototype()
    val engine = if (input.int != 0) {
        val vendor = ByteArray(CareSensAirVendorState.SIZE).also { input.get(it) }
        detector.restoreVendor(ByteBuffer.wrap(vendor).order(ByteOrder.LITTLE_ENDIAN))
        CareSensAirCalibration(parameters, CareSensAirVendorState.read(vendor, parameters))
    } else CareSensAirCalibration(parameters)
    val output = ByteBuffer.allocate(count * 8).order(ByteOrder.LITTLE_ENDIAN)
    repeat(count) {
        val sequence = input.short.toInt() and 0xffff
        val time = input.int.toLong() and 0xffffffffL
        val adc = IntArray(30) { input.short.toInt() and 0xffff }
        val reading = engine.process(CareSensAirCalibration.Record(sequence, time, adc, input.double))
        if (reading.dataType == 2 || !parameters.valid) {
            output.putInt(0)
            output.putInt(0)
        } else {
            val minute = reading.intermediates.minuteCurrents
            val rawCurrent = (minute.sum() - minute.min() - minute.max()) / (minute.size - 2)
            detector.update(sequence, time, rawCurrent, reading.glucose, parameters.slope100, reading.intermediates.diabetesLevel)
            output.putInt(detector.conditions)
            output.putInt(if (detector.latched) 16 else 0)
        }
    }
    System.out.write(output.array())
}
