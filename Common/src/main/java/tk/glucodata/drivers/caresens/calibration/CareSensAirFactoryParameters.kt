/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The factory values the engine uses. The transmitter sends a 446-byte
 * parameter block; differential tests over every field found that only these
 * three change glucose, trend or error results (the rest feed calibration
 * against fingersticks, which JugglucoNG does not use).
 */
data class CareSensAirFactoryParameters(val slope100: Float, val vref: Float, val eapp: Float) {
    /** Outside these the reference rejects the record with error 64. NaN passes, as there. */
    val valid: Boolean
        get() = !(slope100 < 2.5 || slope100 > 8.1 || vref < 1.4 || vref > 1.6 || eapp < 0.0385 || eapp > 0.11295)

    /** Lot type: 2 for low applied potential, otherwise 1. */
    val lotType: Int get() = if (eapp < 0.075) 2 else 1

    companion object {
        const val BLOCK_SIZE = 446

        /** Reads the packed `air1_opcal4_device_info_t` block (little endian). */
        fun fromDeviceBlock(block: ByteArray): CareSensAirFactoryParameters {
            require(block.size >= BLOCK_SIZE) { "device block is $BLOCK_SIZE bytes" }
            val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            return CareSensAirFactoryParameters(b.getFloat(5), b.getFloat(434), b.getFloat(438))
        }
    }
}
