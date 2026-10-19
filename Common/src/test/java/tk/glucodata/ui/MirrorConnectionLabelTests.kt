package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorConnectionLabelTests {
    @Test
    fun generatedNamesUseCurrentLanguage() {
        assertEquals("Lokaler Klon", display("Local Clone"))
        assertEquals("Hybrid-Klon", display("Hybrid Clone"))
        assertEquals(
            "Clone hybride",
            localizedMirrorConnectionLabel("Hybrid Clone", "Clone local", "Clone hybride")
        )
    }

    @Test
    fun customAndAbsentNamesArePreservedExactly() {
        for (label in listOf(null, "", "Kitchen", "Hybrid Clone upstairs", "Local Clone ", "hybrid clone")) {
            assertEquals(label, display(label))
        }
    }

    @Test
    fun displayingDefaultNameDoesNotChangePendingPairingIdentity() {
        val connection = MirrorConnectionSnapshot(
            index = 7,
            label = "Local Clone",
            isIce = false,
            iceSide = false,
            isWearOs = false,
            sendsData = true,
            receivesData = false,
            isDeactivated = false,
            isPending = true
        )
        assertEquals("Lokaler Klon", display(connection.label))
        assertEquals("Local Clone", connection.label)
        assertEquals(7, reusableQuickPairIndex(listOf(connection), QuickPairKind.LOCAL))
    }

    private fun display(label: String?) =
        localizedMirrorConnectionLabel(label, "Lokaler Klon", "Hybrid-Klon")
}
