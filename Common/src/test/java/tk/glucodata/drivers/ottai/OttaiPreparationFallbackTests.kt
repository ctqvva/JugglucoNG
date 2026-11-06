package tk.glucodata.drivers.ottai

import org.junit.Assert.*
import org.junit.Test

class OttaiPreparationFallbackTests {
    private val cached = OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0,
        "E1.2.6(V1.7.SH2616.1),STDRD", 1)

    @Test fun unavailableCloudCanUseCachedMaterialsOnlyForTheRunningFirmware() {
        val refreshed = cached.copy(coefficient = "3,4")
        assertSame(refreshed, OttaiFirmwareSession.preparationMaterials(cached.deviceVersion, cached) { refreshed })
        assertSame(cached, OttaiFirmwareSession.preparationMaterials(cached.deviceVersion, cached) { error("ota_session") })
        val failure = IllegalStateException("materials_refresh")
        for (invalid in listOf(cached.copy(keyAHex = ""), cached.copy(coefficient = "NaN"),
            cached.copy(deviceVersion = "E1.2.6(V1.7.SH2616.2),STDRD"), cached.copy(deviceVersion = "V1.8"))) {
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                OttaiFirmwareSession.preparationMaterials(cached.deviceVersion, invalid) { throw failure }
            })
        }
    }

    @Test fun recoveryCannotInventMissingCalibrationOrContinueAfterCancellation() {
        val v18 = cached.copy(deviceVersion = "V1.8", calibrationValueK = 1.0, calibrationValueB = -2.0)
        assertSame(v18, OttaiFirmwareSession.preparationMaterials("V1.8", v18) { error("offline") })
        for (invalid in listOf(v18.copy(calibrationValueK = null), v18.copy(calibrationValueB = Double.NaN))) {
            assertThrows(IllegalStateException::class.java) {
                OttaiFirmwareSession.preparationMaterials("V1.8", invalid) { error("offline") }
            }
        }
        val cancelled = OttaiOtaCloudException("cancelled")
        assertSame(cancelled, assertThrows(OttaiOtaCloudException::class.java) {
            OttaiFirmwareSession.preparationMaterials("V1.8", v18) { throw cancelled }
        })
        assertThrows(InterruptedException::class.java) {
            OttaiFirmwareSession.preparationMaterials("V1.8", v18) { throw InterruptedException() }
        }
    }
}
