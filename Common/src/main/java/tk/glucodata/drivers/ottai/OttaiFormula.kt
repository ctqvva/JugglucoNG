package tk.glucodata.drivers.ottai

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

object OttaiFormula {

    fun evaluate(methodExpression: String, coefficientsCsv: String, raw: OttaiParser.GlucoseRecord): Float {
        if (methodExpression.isBlank()) return linearFallback(coefficientsCsv, raw)

        val coeffs = coefficientsCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (coeffs.isEmpty()) return linearFallback(coefficientsCsv, raw)

        val vars = Variables(raw, coeffs)
        val groups = methodExpression.split(";")

        var lastResult = 0f
        for (group in groups) {
            val trimmed = group.trim()
            if (trimmed.isEmpty()) continue
            val tokens = trimmed.split("\\s+".toRegex())
            lastResult = evalGroup(tokens, vars)
            vars.storeResult(lastResult)
        }

        return if (lastResult < OttaiConstants.FORMULA_MIN_CLAMP) 0f else lastResult
    }

    private fun linearFallback(coefficientsCsv: String, raw: OttaiParser.GlucoseRecord): Float {
        val coeffs = coefficientsCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (coeffs.isEmpty()) return raw.rawCurrent.toFloat() / 18f
        // V1.5 only — linear calibration: adjustGlucose = rawCurrent / coefficient or similar
        // For now, skip method-less readings (will be calibrated via fingerstick)
        return Float.NaN
    }

    class Variables(
        val raw: OttaiParser.GlucoseRecord,
        val coeffs: List<Float>,
    ) {
        val results = mutableListOf<Float>()
        val parserBytes = run {
            val b = ByteArray(12)
            b[0] = 0; b[1] = 0
            b[2] = (raw.dataNo and 0xFF).toByte(); b[3] = ((raw.dataNo shr 8) and 0xFF).toByte()
            b[4] = raw.voltage.toByte()
            b[5] = ((raw.runtime shr 16) and 0xFF).toByte()
            b[6] = (raw.runtime and 0xFF).toByte()
            b[7] = ((raw.runtime shr 8) and 0xFF).toByte()
            b[8] = (raw.rawCurrent and 0xFF).toByte(); b[9] = ((raw.rawCurrent shr 8) and 0xFF).toByte()
            val t = (raw.temperature * 100).toInt()
            b[10] = (t and 0xFF).toByte(); b[11] = ((t shr 8) and 0xFF).toByte()
            b
        }

        fun storeResult(value: Float) { results.add(value) }

        fun getV(index: Int): Float = when (index) {
            0 -> raw.rawCurrent.toFloat()
            1 -> raw.temperature
            2 -> raw.runtime.toFloat()
            3 -> raw.dataNo.toFloat()
            4 -> (raw.runtime / 3600).toFloat()
            5 -> raw.voltage.toFloat()
            else -> 0f
        }

        fun getB(index: Int): Float = if (index in 0..11) parserBytes[index].toFloat() else 0f
        fun getC(index: Int): Float = coeffs.getOrElse(index) { 0f }
        fun getR(index: Int): Float = results.getOrElse(results.size - 1 - index) { 0f }
    }

    private fun evalGroup(tokens: List<String>, vars: Variables): Float {
        if (tokens.isEmpty()) return 0f
        val stack = ArrayDeque<Float>()

        for (token in tokens) {
            when {
                token.startsWith("V") -> stack.addLast(vars.getV(token.drop(1).toIntOrNull() ?: 0))
                token.startsWith("B") -> stack.addLast(vars.getB(token.drop(1).toIntOrNull() ?: 0))
                token.startsWith("C") -> stack.addLast(vars.getC(token.drop(1).toIntOrNull() ?: 0))
                token.startsWith("R") -> stack.addLast(vars.getR(token.drop(1).toIntOrNull() ?: 0))
                token.toFloatOrNull() != null -> stack.addLast(token.toFloat())
                else -> {
                    val result = when (token) {
                        "AD" -> { val b = stack.removeLastOrNull() ?: 0f; val a = stack.removeLastOrNull() ?: 0f; a + b }
                        "SB" -> { val b = stack.removeLastOrNull() ?: 0f; val a = stack.removeLastOrNull() ?: 0f; a - b }
                        "ML" -> { val b = stack.removeLastOrNull() ?: 0f; val a = stack.removeLastOrNull() ?: 0f; a * b }
                        "DV" -> { val b = stack.removeLastOrNull() ?: 1f; val a = stack.removeLastOrNull() ?: 0f; if (b == 0f) 0f else a / b }
                        "NG" -> -(stack.removeLastOrNull() ?: 0f)
                        "AB" -> abs(stack.removeLastOrNull() ?: 0f)
                        "SQ" -> sqrt(max(0f, stack.removeLastOrNull() ?: 0f))
                        "PW" -> { val exp = stack.removeLastOrNull() ?: 1f; val base = stack.removeLastOrNull() ?: 0f; base.pow(exp) }
                        "BW" -> evalConditional4(stack)
                        "BE" -> evalConditional4(stack)
                        "GT" -> evalConditional3(stack) { a, b -> if (a > b) 1f else 0f }
                        "GE" -> evalConditional3(stack) { a, b -> if (a >= b) 1f else 0f }
                        "LT" -> evalConditional3(stack) { a, b -> if (a < b) 1f else 0f }
                        "LE" -> evalConditional3(stack) { a, b -> if (a <= b) 1f else 0f }
                        "RD" -> { val places = stack.removeLastOrNull() ?: 0f; val v = stack.removeLastOrNull() ?: 0f; roundDecimal(v, places.toInt()) }
                        "LN" -> { val v = stack.removeLastOrNull() ?: 1f; if (v <= 0f) 0f else ln(v) }
                        "MX" -> { val b = stack.removeLastOrNull() ?: 0f; val a = stack.removeLastOrNull() ?: 0f; max(a, b) }
                        "MI" -> { val b = stack.removeLastOrNull() ?: 0f; val a = stack.removeLastOrNull() ?: 0f; min(a, b) }
                        "SN" -> kotlin.math.sin((stack.removeLastOrNull() ?: 0f).toDouble()).toFloat()
                        "CS" -> kotlin.math.cos((stack.removeLastOrNull() ?: 0f).toDouble()).toFloat()
                        "TN" -> kotlin.math.tan((stack.removeLastOrNull() ?: 0f).toDouble()).toFloat()
                        "AS" -> kotlin.math.asin((stack.removeLastOrNull() ?: 0f).toDouble()).toFloat()
                        "AC" -> kotlin.math.acos((stack.removeLastOrNull() ?: 0f).toDouble()).toFloat()
                        "AT" -> kotlin.math.atan((stack.removeLastOrNull() ?: 0f).toDouble()).toFloat()
                        "BA" -> { val b = (stack.removeLastOrNull() ?: 0f).toInt(); val a = (stack.removeLastOrNull() ?: 0f).toInt(); (a and b).toFloat() }
                        "BO" -> { val b = (stack.removeLastOrNull() ?: 0f).toInt(); val a = (stack.removeLastOrNull() ?: 0f).toInt(); (a or b).toFloat() }
                        "BN" -> (stack.removeLastOrNull() ?: 0f).toInt().inv().toFloat()
                        "BX" -> { val b = (stack.removeLastOrNull() ?: 0f).toInt(); val a = (stack.removeLastOrNull() ?: 0f).toInt(); (a xor b).toFloat() }
                        "MD" -> { val b = (stack.removeLastOrNull() ?: 1f).toInt(); val a = (stack.removeLastOrNull() ?: 0f).toInt(); if (b == 0) 0f else (a % b).toFloat() }
                        else -> 0f
                    }
                    stack.addLast(result)
                }
            }
        }

        return stack.lastOrNull() ?: 0f
    }

    private fun evalConditional4(stack: ArrayDeque<Float>): Float {
        val t = stack.removeLastOrNull() ?: 0f
        val f = stack.removeLastOrNull() ?: 0f
        val flag = stack.removeLastOrNull() ?: 0f
        val cond = stack.removeLastOrNull() ?: 0f
        return if (cond != 0f) t else f
    }

    private fun evalConditional3(stack: ArrayDeque<Float>, op: (Float, Float) -> Float): Float {
        val b = stack.removeLastOrNull() ?: 0f
        val a = stack.removeLastOrNull() ?: 0f
        val cmp = op(a, b)
        val default = stack.removeLastOrNull() ?: 1f
        return if (cmp != 0f) 1f else default
    }

    private fun roundDecimal(value: Float, places: Int): Float {
        val factor = 10.0.pow(places.coerceIn(0, 10)).toFloat()
        return round(value * factor) / factor
    }
}
