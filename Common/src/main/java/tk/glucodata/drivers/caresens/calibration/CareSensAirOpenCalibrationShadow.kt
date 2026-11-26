/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Real-sensor check of the open engine in debug builds.
 *
 * For every record the native driver passes the vendor library's state from
 * before the record, the record itself and the vendor's outputs. The engine
 * takes over that state, processes the one record and the results are
 * compared and logged. Nothing here changes what the app shows: the vendor
 * result stays the one used.
 */
object CareSensAirOpenCalibrationShadow {
    private const val TAG = "CareSensAirOpen"
    private const val OUTPUT_SIZE = 155

    data class Comparison(
        val compared: Boolean,
        val matches: Boolean,
        val detail: String,
    )

    private var checked = 0
    private var mismatches = 0
    private var skipped = 0

    @JvmStatic
    fun check(device: ByteArray, vendorState: ByteArray, input: ByteArray, outputs: ByteArray, count: Int) {
        val c = try {
            compare(device, vendorState, input, outputs, count)
        } catch (e: RuntimeException) {
            Comparison(false, false, "engine failed: $e")
        }
        if (!c.compared) skipped++ else {
            checked++
            if (!c.matches) mismatches++
        }
        if (!c.matches) Log.w(TAG, c.detail)
        if ((checked + skipped) % 288 == 0) {
            Log.i(TAG, "open engine: $checked compared, $mismatches differ, $skipped not comparable")
        }
    }

    /** Pure comparison, for tests. */
    fun compare(device: ByteArray, vendorState: ByteArray, input: ByteArray, outputs: ByteArray, count: Int): Comparison {
        val parameters = CareSensAirFactoryParameters.fromDeviceBlock(device)
        val i = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val record = CareSensAirCalibration.Record(
            i.getShort(0).toInt() and 0xffff,
            i.getInt(2).toLong() and 0xffffffffL,
            IntArray(CareSensAirCalibration.SAMPLES) { i.getShort(6 + 2 * it).toInt() and 0xffff },
            i.getDouble(66),
        )
        if (count > 1) {
            return Comparison(false, true, "seq ${record.sequence}: vendor filled ${count - 1} records, not compared")
        }
        val o = ByteBuffer.wrap(outputs).order(ByteOrder.LITTLE_ENDIAN)
        val last = 0
        val vendorGlucose = o.getDouble(last + 68)
        val vendorTrend = o.getDouble(last + 76)
        val vendorError = o.getShort(last + 151).toInt() and 0xffff
        val vendorType = o.get(last + 154).toInt() and 0xff
        val got = CareSensAirCalibration(parameters, CareSensAirVendorState.read(vendorState, parameters)).process(record)
        val at = "seq ${record.sequence}"
        val modelled = CareSensAirCalibration.ERROR_TIMING or CareSensAirCalibration.ERROR_PARAMETERS
        val problems = buildList {
            if ((vendorType == 2) != (got.dataType == 2)) add("rejection vendor=$vendorType engine=${got.dataType}")
            if (vendorError and modelled != got.errorCode) add("error vendor=$vendorError engine=${got.errorCode}")
            if (vendorType != 2 && vendorError and CareSensAirCalibration.ERROR_PARAMETERS == 0) {
                if (abs(vendorGlucose - got.glucose) > 1e-6) add("glucose vendor=$vendorGlucose engine=${got.glucose}")
                if (got.exact && abs(vendorTrend - got.trend) > 1e-6) add("trend vendor=$vendorTrend engine=${got.trend}")
            }
        }
        val unmodelled = vendorError and modelled.inv()
        val note = if (unmodelled != 0) " (vendor error $unmodelled is not reproduced)" else ""
        return if (problems.isEmpty()) Comparison(true, true, "$at matches$note")
        else Comparison(true, false, "$at differs: ${problems.joinToString()}$note")
    }
}
