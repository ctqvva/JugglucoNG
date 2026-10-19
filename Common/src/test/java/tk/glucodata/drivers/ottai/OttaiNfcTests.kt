package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiNfcTests {
    @Test
    fun rawNfcAIsRecognizedAsFieldOnlyWake() {
        assertEquals(
            OttaiNfc.WakeInterface.NFC_A_FIELD_ONLY,
            OttaiNfc.classifyTechs(arrayOf("android.nfc.tech.NfcA")),
        )
    }

    @Test
    fun readableInterfacesTakePriorityOverBaseNfcA() {
        assertEquals(
            OttaiNfc.WakeInterface.MIFARE_ULTRALIGHT,
            OttaiNfc.classifyTechs(
                arrayOf(
                    "android.nfc.tech.NfcA",
                    "android.nfc.tech.MifareUltralight",
                ),
            ),
        )
        assertEquals(
            OttaiNfc.WakeInterface.NFC_V,
            OttaiNfc.classifyTechs(arrayOf("android.nfc.tech.NfcV")),
        )
    }

    @Test
    fun unrelatedNfcTechnologyIsNotAcceptedAsOttaiWake() {
        assertEquals(
            OttaiNfc.WakeInterface.UNSUPPORTED,
            OttaiNfc.classifyTechs(arrayOf("android.nfc.tech.IsoDep")),
        )
    }

    @Test
    fun activationWakeIsDisarmedOnlyByTheMatchingSensor() {
        OttaiNfc.armForActivationRetry("6CA04230E260")
        assertTrue(OttaiNfc.dumpMode)
        assertTrue(OttaiNfc.isActivationRetryArmed("6CA04230E260"))

        OttaiNfc.disarmActivationRetry("08FD5266CCB5")
        assertTrue(OttaiNfc.dumpMode)
        assertTrue(OttaiNfc.isActivationRetryArmed("6CA04230E260"))

        OttaiNfc.disarmActivationRetry("6CA04230E260")
        assertFalse(OttaiNfc.dumpMode)
        assertFalse(OttaiNfc.isActivationRetryArmed("6CA04230E260"))
    }
}
