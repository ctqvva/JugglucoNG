package tk.glucodata.drivers.mq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MQConstantsTests {

    @Test
    fun matchesCanonicalOrKnownNativeAlias_acceptsObservedNativeSuffixAlias() {
        assertTrue(
            MQConstants.matchesCanonicalOrKnownNativeAlias(
                "CFD8EBDDF969",
                "BDDF969"
            )
        )
    }

    @Test
    fun matchesCanonicalOrKnownNativeAlias_rejectsUnrelatedSuffixLength() {
        assertFalse(
            MQConstants.matchesCanonicalOrKnownNativeAlias(
                "CFD8EBDDF969",
                "F969"
            )
        )
    }

    @Test
    fun matchesCanonicalOrKnownNativeAlias_rejectsUnrelatedHexAlias() {
        assertFalse(
            MQConstants.matchesCanonicalOrKnownNativeAlias(
                "CFD8EBDDF969",
                "1234567"
            )
        )
    }
}
