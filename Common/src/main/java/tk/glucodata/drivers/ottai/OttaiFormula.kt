// JugglucoNG — Ottai driver
// OttaiFormula.kt — server-supplied glucose expression evaluator.
//
// Faithful port of the stack/RPN evaluator inside a1.a.e() (1.1.0 watch decompile,
// see AGENTS/ottai-phase0-confirmed.md). The cloud returns an encrypted `method`
// (RPN expression) and `coefficient` (CSV) per device; after decryption the app
// substitutes variables and evaluates to produce `adjustGlucose`.
//
// Method format: `;`-separated groups; each group is space-separated tokens.
// Tokens are either operators (Operator enum), numeric literals, or variable
// references substituted before evaluation:
//   C{i}  coefficient[i]            (from decrypted coefficient CSV)
//   V{i}  V0..V5 = current, temperature, runtime, dataNo, runtime/3600 (int), voltage
//   B{i}  signed byte data[i] of the 12-byte parser record
//   R{i}  result of the i-th previously evaluated group
// Each group evaluates to stack[0]; the LAST group's result is adjustGlucose;
// values < 0.1 are forced to 0.0.

package tk.glucodata.drivers.ottai

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object OttaiFormula {

    /** The 29 operators, ordinal-aligned with `com.ottai.tag.watch.cgm.Operator`. */
    private val OPERATORS = setOf(
        "AD", "SB", "ML", "DV", "NG", "AB", "SQ", "PW", "BW", "BE", "GT", "GE",
        "LT", "LE", "RD", "LN", "MX", "MI", "SN", "CS", "TN", "AS", "AC", "AT",
        "BA", "BO", "BN", "BX", "MD",
    )

    /**
     * Evaluate the decrypted [methodText] for one record.
     *
     * @param coefficients decrypted coefficient CSV values (C0..Cn)
     * @param v V0..V5: [current, temperature, runtime, dataNo, runtime/3600(int), voltage]
     * @param recordBytes the 12-byte parser record (00 00 ‖ dataNoLE ‖ 8-byte record);
     *                    B{i} uses the SIGNED value of recordBytes[i]
     * @return adjustGlucose (last group result; values < 0.1 forced to 0.0). NaN when any
     *         group is malformed (an operator short of operands, a negative RD precision, an MD by
     *         zero): the whole evaluation is void, because a later group reading the broken one
     *         through R{i} with a comparison or bitwise operator would turn it into a plausible
     *         number. NaN as well when any group names a coefficient C{i} the set does not hold.
     */
    fun evaluate(
        methodText: String,
        coefficients: List<Double>,
        v: DoubleArray,
        recordBytes: ByteArray,
    ): Double {
        if (methodText.isBlank()) return 0.0
        val groups = methodText.split(';')
        val groupResults = ArrayList<Double>(groups.size)

        for (group in groups) {
            val rawTokens = group.split(' ').filter { it.isNotEmpty() }
            if (rawTokens.isEmpty()) continue
            // A coefficient the method names but the set does not hold (a short set, or one
            // OttaiRegistry.parseCoefficients voided) would stay a literal token and read as 0.0:
            // a glucose computed with a zero coefficient. No value at all instead.
            if (rawTokens.any { namesMissingCoefficient(it, coefficients) }) return Double.NaN
            val tokens = rawTokens.map { substitute(it, coefficients, v, recordBytes, groupResults) }
            groupResults.add(evalGroup(tokens) ?: return Double.NaN)
        }

        if (groupResults.isEmpty()) return 0.0
        var adjust = groupResults.last()
        if (adjust < 0.1) adjust = 0.0
        return adjust
    }

    /** A C{i} reference, as [substitute] reads it, with no coefficient at i. */
    private fun namesMissingCoefficient(token: String, coefficients: List<Double>): Boolean {
        if (token.length < 2 || token[0] != 'C') return false
        val idx = token.substring(1).toIntOrNull() ?: return false
        return idx >= 0 && idx >= coefficients.size
    }

    private fun substitute(
        token: String,
        coefficients: List<Double>,
        v: DoubleArray,
        recordBytes: ByteArray,
        priorResults: List<Double>,
    ): String {
        if (token.length >= 2) {
            val idx = token.substring(1).toIntOrNull()
            if (idx != null && idx >= 0) {
                when (token[0]) {
                    'C' -> if (idx < coefficients.size) return coefficients[idx].toString()
                    'V' -> if (idx < v.size) return v[idx].toString()
                    'B' -> if (idx < recordBytes.size) return recordBytes[idx].toInt().toString() // signed
                    'R' -> if (idx < priorResults.size) return priorResults[idx].toString()
                }
            }
        }
        return token
    }

    /**
     * Evaluate one space-tokenised, already-substituted group as the decompiled
     * stack machine: pointer `sp` starts at -1; operators adjust `sp` and write
     * the result; literals push (sp++). Group result is stack[0], or null when the group is
     * malformed and the evaluation is void.
     */
    private fun evalGroup(tokens: List<String>): Double? {
        val stack = DoubleArray(tokens.size)
        var sp = -1
        for (tok in tokens) {
            val op = tok.uppercase(Locale.ROOT)
            if (op in OPERATORS) {
                // The method comes from the cloud, so it is untrusted input. An operator with
                // fewer operands than it reads (a malformed or future-syntax method) used to index
                // below the stack and throw on every live notify and history page, a crash loop.
                // Void instead: evaluate returns NaN, which OttaiOutputFilter.hardRejectReason refuses.
                if (sp + 1 < arity(op)) return null
                var r = 1.0 // decompiled default
                when (op) {
                    "AD" -> { sp--; r = stack[sp + 1] + stack[sp] }
                    "SB" -> { sp--; r = stack[sp] - stack[sp + 1] }
                    "ML" -> { sp--; r = stack[sp + 1] * stack[sp] }
                    "DV" -> { sp--; r = stack[sp] / stack[sp + 1] }
                    "NG" -> { r = -stack[sp] }
                    "AB" -> { r = abs(stack[sp]) }
                    "SQ" -> { r = sqrt(stack[sp]) }
                    "PW" -> { sp--; r = stack[sp].pow(stack[sp + 1]) }
                    "BW" -> {
                        sp -= 3
                        r = if (stack[sp + 1] >= stack[sp] || stack[sp] >= stack[sp + 2]) 0.0
                        else if (stack[sp + 3] != 1.0) stack[sp] else 1.0
                    }
                    "BE" -> {
                        sp -= 3
                        r = if (stack[sp + 1] <= stack[sp] && stack[sp] <= stack[sp + 2] && stack[sp + 3] != 1.0) {
                            stack[sp]
                        } else 1.0
                    }
                    "GT" -> { sp -= 2; r = if (stack[sp] > stack[sp + 1]) stack[sp + 2] else 0.0 }
                    "GE" -> { sp -= 2; r = if (stack[sp] >= stack[sp + 1]) stack[sp + 2] else 0.0 }
                    "LT" -> { sp -= 2; r = if (stack[sp] < stack[sp + 1]) stack[sp + 2] else 0.0 }
                    "LE" -> { sp -= 2; r = if (stack[sp] <= stack[sp + 1]) stack[sp + 2] else 0.0 }
                    "RD" -> {
                        sp--
                        // A negative precision is not a format String.format accepts: it throws, the
                        // same crash loop as a missing operand. A huge one allocates that many
                        // digits; past MAX_RD_DECIMALS they are only zeros, so the cap changes nothing.
                        val decimals = stack[sp + 1].toInt()
                        if (decimals < 0) return null
                        r = String.format(
                            Locale.ROOT, "%." + min(decimals, MAX_RD_DECIMALS) + "f", stack[sp],
                        ).toDouble()
                    }
                    "LN" -> { r = ln(stack[sp]) }
                    "MX" -> { sp--; r = max(stack[sp], stack[sp + 1]) }
                    "MI" -> { sp--; r = min(stack[sp], stack[sp + 1]) }
                    "SN" -> { r = sin(stack[sp]) }
                    "CS" -> { r = cos(stack[sp]) }
                    "TN" -> { r = tan(stack[sp]) }
                    "AS" -> { r = asin(stack[sp]) }
                    "AC" -> { r = acos(stack[sp]) }
                    "AT" -> { r = atan(stack[sp]) }
                    "BA" -> { sp--; r = (stack[sp].toInt() and stack[sp + 1].toInt()).toDouble() }
                    "BO" -> { sp--; r = (stack[sp].toInt() or stack[sp + 1].toInt()).toDouble() }
                    "BN" -> { r = (stack[sp].toInt().inv()).toDouble() }
                    "BX" -> { sp--; r = (stack[sp].toInt() xor stack[sp + 1].toInt()).toDouble() }
                    "MD" -> {
                        sp--
                        val divisor = stack[sp + 1].toInt()
                        // Integer modulo by zero throws: the same crash loop as a missing operand.
                        if (divisor == 0) return null
                        r = (stack[sp].toInt() % divisor).toDouble()
                    }
                }
                stack[sp] = r
            } else {
                sp++
                if (sp >= stack.size) return if (stack.isEmpty()) 0.0 else stack[0]
                stack[sp] = tok.toDoubleOrNull() ?: 0.0
            }
        }
        return if (stack.isEmpty()) 0.0 else stack[0]
    }

    /**
     * Fraction digits after which %f of any double prints only zeros: the smallest subnormal,
     * 4.9E-324, needs 324 places plus 17 significant digits.
     */
    private const val MAX_RD_DECIMALS = 400

    /** Stack values [op] reads: the stack must hold this many before it runs. */
    private fun arity(op: String): Int = when (op) {
        "BW", "BE" -> 4
        "GT", "GE", "LT", "LE" -> 3
        "NG", "AB", "SQ", "LN", "SN", "CS", "TN", "AS", "AC", "AT", "BN" -> 1
        else -> 2 // AD SB ML DV PW RD MX MI BA BO BX MD
    }

    /** Build the V0..V5 vector from parsed record fields (matches a1.a.e()). */
    fun buildVariables(
        rawCurrent: Int,
        temperature: Double,
        runtimeSec: Int,
        dataNo: Int,
        voltage: Int,
    ): DoubleArray = doubleArrayOf(
        rawCurrent.toDouble(),
        temperature,
        runtimeSec.toDouble(),
        dataNo.toDouble(),
        (runtimeSec / 3600).toDouble(), // integer division, as in the decompile
        voltage.toDouble(),
    )
}
