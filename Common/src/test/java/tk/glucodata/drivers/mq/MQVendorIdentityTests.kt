package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQVendorIdentityTests {
    @Test fun vendorSerialIsSeparateFromAndroidAndNativeIdentity() {
        assertEquals("W25101399", MQVendorIdentity.bleId("w25101399"))
        assertEquals("W25101399", MQVendorIdentity.bleId("S/N:W25101399"))
        for (name in listOf(null, "", "CF:D8:EB:DD:F9:69", "CFD8EBDDF969", "FD8EBDDF969", "Glutec CGM")) {
            assertNull("name=$name", MQVendorIdentity.bleId(name))
        }
    }

    @Test fun sessionFormMatchesOfficialAppsSeparateSerialAndMacFields() {
        assertEquals("bleId=W25101399&mac=CF%3AD8%3AEB%3ADD%3AF9%3A69&account=test%2Baccount&qrCode=qr%26code",
            MQVendorIdentity.startForm("W25101399", "CFD8EBDDF969", "test+account", "qr&code"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotRepeatTheServersFd8ebddf969ParseFailure() {
        MQVendorIdentity.startForm("CFD8EBDDF969", "CFD8EBDDF969", "account", "qr")
    }

    @Test fun macBasedSnapshotComparisonIsNotAVendorIdentityMatch() {
        assertFalse(MQSessionRestorePolicy.canRestore("CFD8EBDDF969", "CFD8EBDDF969", 1000, 2000))
        assertFalse(MQSessionRestorePolicy.canRestore("W25101399", "W25101398", 1000, 2000))
        assertTrue(MQSessionRestorePolicy.canRestore("W25101399", "w25101399", 1000, 2000))
    }
}
