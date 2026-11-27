import java.nio.ByteBuffer
import java.nio.ByteOrder
import tk.glucodata.drivers.caresens.calibration.CareSensAirCalibration
import tk.glucodata.drivers.caresens.calibration.CareSensAirFactoryParameters
import tk.glucodata.drivers.caresens.calibration.CareSensAirVendorState
import tk.glucodata.drivers.caresens.calibration.Error16Prototype

/** Adds a revised-current flag and double to each input, and emits the detector's state tails. */
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
    val output = ByteBuffer.allocate(count * (12 + Error16Prototype.STAT_COUNT * 8)).order(ByteOrder.LITTLE_ENDIAN)
    repeat(count) {
        val sequence = input.short.toInt() and 0xffff
        val time = input.int.toLong() and 0xffffffffL
        val adc = IntArray(30) { input.short.toInt() and 0xffff }
        val temperature = input.double
        val useRevisedCurrent = input.get().toInt() != 0
        val revisedCurrent = input.double
        val reading = engine.process(CareSensAirCalibration.Record(sequence, time, adc, temperature))
        val accepted = reading.dataType != 2 && parameters.valid
        if (accepted) {
            val current = if (useRevisedCurrent) revisedCurrent else {
                val minute = reading.intermediates.minuteCurrents
                (minute.sum() - minute.min() - minute.max()) / (minute.size - 2)
            }
            val day7Profile = reading.intermediates.diabetesLevel.takeIf { sequence >= 2017 && engine.state.diabetesCount > 288 }
            detector.update(sequence, time, current, reading.glucose, parameters.slope100, day7Profile)
        }
        output.putInt(if (accepted) detector.conditions else 0)
        output.putInt(if (accepted && detector.latched) 16 else 0)
        output.putInt(if (accepted) 1 else 0)
        detector.writeStatistics(output)
    }
    System.out.write(output.array())
}
