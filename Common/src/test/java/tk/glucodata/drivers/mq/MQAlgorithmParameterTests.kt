package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQAlgorithmParameterTests {
    @Test fun packet248DoesNotLoseOneHundredthOfKThroughFloatTruncation() {
        // The trace solves 29.41, persists it as Float, then incorrectly truncates to 29.40.
        var storedK = 29.41f
        var previous = 100.0
        repeat(100) { offset ->
            val result = MQAlgorithm.calculateResult(
                1, 744.0 + offset * 3, 248.0 + offset, 103.0, previous,
                MQAlgorithm.decimalParameter(storedK), 0.0, 2.0, 720.0, 1.0,
            )
            assertEquals(29.41, result.kValue, 0.0)
            storedK = result.kValue.toFloat()
            previous = result.reviseCurrent2
        }
    }

    @Test fun normalizedSensitivityAndMultiplierKeepTheirDecimalValues() {
        assertEquals(29.3, MQAlgorithm.decimalParameter(29.3f), 0.0)
        assertEquals(1.1, MQAlgorithm.decimalParameter(1.1f), 0.0)
    }
}
