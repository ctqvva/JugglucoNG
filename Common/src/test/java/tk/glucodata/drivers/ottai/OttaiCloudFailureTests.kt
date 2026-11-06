package tk.glucodata.drivers.ottai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiCloudFailureTests {
    @Test
    fun bindV3OtaUpgradeRequiredIsRecognised() {
        val failure = OttaiCloudClient.CloudFailure(
            "http=200 biz=OTA_UPGRADE_REQUIRED OTA_UPGRADE_REQUIRED",
            OttaiCloudClient.BIZ_OTA_UPGRADE_REQUIRED,
            200,
        )
        assertTrue(failure.isOtaUpgradeRequired)
        assertFalse(failure.isTokenInvalid)
    }

    @Test
    fun otherBusinessCodesAreNotOtaUpgradeRequired() {
        assertFalse(OttaiCloudClient.CloudFailure("x", OttaiCloudClient.BIZ_UPGRADE_VERSION, 200).isOtaUpgradeRequired)
        assertFalse(OttaiCloudClient.CloudFailure("x").isOtaUpgradeRequired)
    }
}
