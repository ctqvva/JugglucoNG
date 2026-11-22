package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthCalibrationStoreTests {

    @Test
    fun roundTripKeepsAllSixFields() {
        val bean = ICanHealthCeCalibrationResult(
            caliK = 1.237f,
            caliAdjustBool = true,
            caliAdjustNo = 3,
            calibBg = 118.5f,
            calik0 = 0.875f,
            calikM = 1.0625f,
        )
        val restored = ICanHealthCalibrationStore.decode(ICanHealthCalibrationStore.encode(bean))
        assertEquals(bean, restored)
    }

    @Test
    fun roundTripKeepsVendorDefaults() {
        val defaults = ICanHealthCeCalibration.vendorDefaults()
        assertEquals(
            defaults,
            ICanHealthCalibrationStore.decode(ICanHealthCalibrationStore.encode(defaults)),
        )
    }

    @Test
    fun malformedValuesAreRejected() {
        assertNull(ICanHealthCalibrationStore.decode(""))
        assertNull(ICanHealthCalibrationStore.decode("1|0|2"))
        assertNull(ICanHealthCalibrationStore.decode("NaN|0|2|1|1|1"))
        assertNull(ICanHealthCalibrationStore.decode("1|maybe|2|1|1|1"))
    }

    @Test
    fun encodingIsLocaleIndependent() {
        val bean = ICanHealthCeCalibrationResult(1.5f, false, -1, -1f, 1f, 1f)
        val encoded = ICanHealthCalibrationStore.encode(bean)
        assertTrue(encoded.contains("1.5"))
        assertEquals(6, encoded.split("|").size)
    }
}
