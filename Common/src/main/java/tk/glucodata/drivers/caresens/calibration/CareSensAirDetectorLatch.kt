/*
 * This file is part of JugglucoNG.
 *
 * JugglucoNG is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package tk.glucodata.drivers.caresens.calibration

internal object CareSensAirDetectorLatch {
    /** Errors 1, 2 and 4 clear on rejection or on a sequence gap without a fresh trigger. */
    fun consecutive(previous: Boolean, triggered: Boolean, consecutive: Boolean, accepted: Boolean): Boolean =
        accepted && (triggered || (previous && consecutive))

    /** Errors 16 and 32 retain their state. Reporting on rejection is detector-specific. */
    fun permanent(previous: Boolean, triggered: Boolean): Boolean = previous || triggered
}
