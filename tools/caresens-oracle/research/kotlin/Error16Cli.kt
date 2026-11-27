import java.nio.ByteBuffer
import java.nio.ByteOrder
import tk.glucodata.drivers.caresens.calibration.CareSensAirCalibration
import tk.glucodata.drivers.caresens.calibration.CareSensAirFactoryParameters
import tk.glucodata.drivers.caresens.calibration.CareSensAirVendorState
import tk.glucodata.drivers.caresens.calibration.Error128RevisedCurrent
import tk.glucodata.drivers.caresens.calibration.Error16Prototype

/** Runs Error128RevisedCurrent and Error16Prototype, emitting revised current and detector state tails. */
fun main() {
    val input = ByteBuffer.wrap(System.`in`.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val parameters = CareSensAirFactoryParameters(input.float, input.float, input.float)
    val count = input.int
    val detector = Error16Prototype()
    val revised = Error128RevisedCurrent()
    var lastCurrent = 0.0
    val engine = if (input.int != 0) {
        val vendor = ByteArray(CareSensAirVendorState.SIZE).also { input.get(it) }
        val block = ByteBuffer.wrap(vendor).order(ByteOrder.LITTLE_ENDIAN)
        detector.restoreVendor(block)
        revised.restoreVendor(block)
        lastCurrent = revised.revisedValue
        CareSensAirCalibration(parameters, CareSensAirVendorState.read(vendor, parameters))
    } else CareSensAirCalibration(parameters)
    val output = ByteBuffer.allocate(count * (12 + (1 + Error16Prototype.STAT_COUNT) * 8)).order(ByteOrder.LITTLE_ENDIAN)
    repeat(count) {
        val sequence = input.short.toInt() and 0xffff
        val time = input.int.toLong() and 0xffffffffL
        val adc = IntArray(30) { input.short.toInt() and 0xffff }
        val temperature = input.double
        val currentMode = input.get().toInt()
        val injectedCurrent = input.double
        val reading = engine.process(CareSensAirCalibration.Record(sequence, time, adc, temperature))
        val accepted = reading.dataType != 2 && parameters.valid
        if (accepted) {
            val ordinaryCurrent = CareSensAirCalibration.trimmedMean(reading.intermediates.minuteCurrents)
            val autonomousCurrent = revised.update(sequence, time, ordinaryCurrent, reading.glucose, detector.profile)
            val current = when (currentMode) {
                1 -> injectedCurrent
                2 -> ordinaryCurrent
                else -> autonomousCurrent
            }
            lastCurrent = current
            val day7Profile = reading.intermediates.diabetesLevel.takeIf { sequence >= 2017 && engine.state.diabetesCount > 288 }
            detector.update(sequence, time, current, reading.glucose, parameters.slope100, day7Profile)
        }
        output.putInt(if (accepted) detector.conditions else 0)
        output.putInt(if (accepted && detector.latched) 16 else 0)
        output.putInt(if (accepted) 1 else 0)
        output.putDouble(lastCurrent)
        detector.writeStatistics(output)
    }
    System.out.write(output.array())
}
