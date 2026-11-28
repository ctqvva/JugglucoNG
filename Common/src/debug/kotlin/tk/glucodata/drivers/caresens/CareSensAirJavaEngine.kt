// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

/**
 * Binds the pinned private Java JAR when present. Use its algorithm entry point because the public
 * calibrator facade drops the final sequence and measurement time needed for native storage.
 */
internal class CareSensAirJavaEngine private constructor() {
    private val deviceInfo = model("DeviceInfo")
    private val input = model("CgmInput")
    private val state = model("AlgorithmState")
    private val calibrationList = model("CalibrationList")
    private val output = model("AlgorithmOutput")
    private val debug = model("DebugOutput")
    private val process = Class.forName("com.opencaresens.air.CalibrationAlgorithm")
        .getDeclaredMethod("process", deviceInfo, input, calibrationList, state, output, debug)
        .apply { isAccessible = true }
    private val infoFields = deviceInfo.fields.associateBy { it.name }
    private val sequenceInput = input.getField("seqNumber")
    private val timeInput = input.getField("measurementTimeStandard")
    private val samplesInput = input.getField("workout")
    private val temperatureInput = input.getField("temperature")
    private val sequenceOutput = output.getField("seqNumberFinal")
    private val timeOutput = output.getField("measurementTimeStandard")
    private val glucoseOutput = output.getField("resultGlucose")
    private val rateOutput = output.getField("trendrate")
    private val errorOutput = output.getField("errcode")
    private val stageOutput = output.getField("currentStage")

    fun open(packed: ByteArray): CareSensAirEngine {
        val config = deviceInfo.getConstructor().newInstance()
        CareSensAirFactoryConfig.decode(packed) { name, value -> infoFields.getValue(name).set(config, value) }
        val arguments = state.getConstructor().newInstance()
        val calibrations = calibrationList.getConstructor().newInstance()
        val current = input.getConstructor().newInstance()
        val result = output.getConstructor().newInstance()
        val intermediate = debug.getConstructor().newInstance()
        val workout = samplesInput.get(current) as IntArray
        return CareSensAirEngine { sequence, time, samples, temperature ->
            sequenceInput.setInt(current, sequence)
            timeInput.setLong(current, time)
            temperatureInput.setDouble(current, temperature)
            samples.copyInto(workout)
            val processed = process.invoke(null, config, current, calibrations, arguments, result, intermediate) as Int
            check(processed != 0) { "CareSens Air algorithm did not process the record" }
            CareSensAirOutput(
                sequenceOutput.getInt(result),
                timeOutput.getLong(result),
                glucoseOutput.getDouble(result),
                rateOutput.getDouble(result),
                errorOutput.getInt(result),
                stageOutput.getInt(result),
            )
        }
    }

    private fun model(name: String): Class<*> = Class.forName("com.opencaresens.air.model.$name")

    companion object {
        val available: CareSensAirJavaEngine? by lazy {
            try {
                CareSensAirJavaEngine()
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
    }
}

internal fun careSensAirEngineFactory(): ((ByteArray) -> CareSensAirEngine)? =
    CareSensAirJavaEngine.available?.let { it::open }
