package tk.glucodata.drivers.icanhealth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The identity adapter and the BLE manager both delete through ICanHealthRegistry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ICanHealthCalibrationRemovalTests {

    @Test
    fun registryRemovalClearsPerSensorCalFactor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensorId = "TESTSENSOR0001"
        ICanHealthCalibrationStore.save(context, sensorId, ICanHealthCeCalibration.vendorDefaults())
        assertNotNull(ICanHealthCalibrationStore.load(context, sensorId))

        ICanHealthManagedSensorIdentityAdapter.removePersistedSensor(context, sensorId)

        assertNull(ICanHealthCalibrationStore.load(context, sensorId))
    }
}
