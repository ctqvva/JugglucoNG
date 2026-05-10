package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayValueResolverTests {

    @Test
    fun autoRawWithCalibrationKeepsTwoLanes() {
        val values = DisplayValueResolver.resolve(
            autoValue = 4.1f,
            rawValue = 3.4f,
            viewMode = 2,
            isMmol = true,
            calibratedValue = 4.2f,
        )

        assertEquals(4.2f, values.primaryValue, 0.001f)
        assertEquals(3.4f, values.secondaryValue ?: 0f, 0.001f)
        assertNull(values.tertiaryValue)
        assertNull(values.tertiaryStr)
    }

    @Test
    fun rawAutoWithCalibrationKeepsTwoLanes() {
        val values = DisplayValueResolver.resolve(
            autoValue = 4.1f,
            rawValue = 3.4f,
            viewMode = 3,
            isMmol = true,
            calibratedValue = 3.5f,
        )

        assertEquals(3.5f, values.primaryValue, 0.001f)
        assertEquals(4.1f, values.secondaryValue ?: 0f, 0.001f)
        assertNull(values.tertiaryValue)
        assertNull(values.tertiaryStr)
    }
}
