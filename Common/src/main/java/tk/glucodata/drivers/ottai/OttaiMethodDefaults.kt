package tk.glucodata.drivers.ottai

/**
 * Official-app fallback for the common 14-coefficient Ottai CGM formula.
 *
 * The vendor app stores the decrypted method locally and keeps using it when a
 * later cloud response omits `method` but still returns per-sensor coefficients.
 * V1.5 and V1.7 captures seen so far share this exact 14-value coefficient
 * profile, and this RPN expression references C0..C13 only.
 */
internal object OttaiMethodDefaults {
    const val STANDARD_14_COEFF_METHOD: String =
        "V1 C0 ad C1 ml C2 ad;" +
            "V0 R0 2 pw dv;" +
            "C3 0 ml C4 R1 2 pw ml ad C5 R1 ml ad C6 ad;" +
            "C7 R2 2 pw ml C8 R2 ml ad C9 ad;" +
            "C10 V2 86400 dv C11 ml sb V2 C12 1 le ml V2 C12 1 gt C13 ml ad;" +
            "R3 R4 ml 1 rd"

    fun resolve(method: String, coefficient: String): String {
        val explicit = method.trim()
        if (explicit.isNotEmpty()) return explicit
        return if (matchesStandard14CoefficientProfile(coefficient)) STANDARD_14_COEFF_METHOD else ""
    }

    fun matchesStandard14CoefficientProfile(coefficient: String): Boolean {
        val values = coefficient.split(',').map { part ->
            part.trim().toDoubleOrNull() ?: return false
        }
        if (values.size != 14) return false
        if (values.any { !java.lang.Double.isFinite(it) }) return false
        val splitAgeSeconds = values[12]
        val lateWearMask = values[13]
        return splitAgeSeconds in 86_400.0..604_800.0 && lateWearMask in 0.0..2.0
    }
}
