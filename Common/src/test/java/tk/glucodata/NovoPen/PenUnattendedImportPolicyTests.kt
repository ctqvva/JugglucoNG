package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.NovoPen.PenUnattendedImportPolicy.Plan

/**
 * A pen read with the app in the background has no sheet to untick a dose on, so the
 * mode is "everything new": air shots are dropped rather than pre-unticked, a pen with
 * no insulin chosen waits for the sheet, and only an ISO-DEP tag is a pen at all.
 */
class PenUnattendedImportPolicyTests {

    private fun dose(seconds: Long, units: Float, priming: Boolean = false) =
        PenDose(timestampSeconds = seconds, units = units, flags = 0, priming = priming)

    @Test
    fun airShotsAreLeftOutOfAnUnattendedImport() {
        val fresh = listOf(dose(300, 6f), dose(240, 2f, priming = true), dose(100, 4f))

        val plan = PenUnattendedImportPolicy.plan(fresh, hasPreset = true)

        assertEquals(Plan.Import(listOf(dose(300, 6f), dose(100, 4f))), plan)
    }

    @Test
    fun onlyAirShotsMeansNothingNew() {
        val fresh = listOf(dose(300, 2f, priming = true), dose(240, 1f, priming = true))

        assertEquals(Plan.NothingNew, PenUnattendedImportPolicy.plan(fresh, hasPreset = true))
    }

    @Test
    fun aPenWithoutAnInsulinWaitsForTheSheetWithEverything() {
        // The sheet shows air shots pre-unticked, so it gets the whole fresh list.
        val fresh = listOf(dose(300, 6f), dose(240, 2f, priming = true))

        assertEquals(Plan.Review(fresh), PenUnattendedImportPolicy.plan(fresh, hasPreset = false))
    }

    @Test
    fun nothingFreshIsNothingNewWithOrWithoutAnInsulin() {
        assertEquals(Plan.NothingNew, PenUnattendedImportPolicy.plan(emptyList(), hasPreset = true))
        assertEquals(Plan.NothingNew, PenUnattendedImportPolicy.plan(emptyList(), hasPreset = false))
    }

    @Test
    fun anImportKeepsTheOrderItWasGiven() {
        val fresh = listOf(dose(300, 6f), dose(200, 5f), dose(100, 4f))

        val plan = PenUnattendedImportPolicy.plan(fresh, hasPreset = true) as Plan.Import

        assertEquals(listOf(300L, 200L, 100L), plan.doses.map(PenDose::timestampSeconds))
    }

    @Test
    fun onlyAnIsoDepTagIsAPen() {
        assertTrue(PenUnattendedImportPolicy.isPenTag(arrayOf("android.nfc.tech.IsoDep", "android.nfc.tech.NfcA")))
        assertTrue(PenUnattendedImportPolicy.isPenTag(arrayOf("android.nfc.tech.NfcA", "android.nfc.tech.IsoDep")))
        assertFalse("a Libre sensor", PenUnattendedImportPolicy.isPenTag(arrayOf("android.nfc.tech.NfcV", "android.nfc.tech.NdefFormatable")))
        assertFalse(PenUnattendedImportPolicy.isPenTag(emptyArray()))
        assertFalse(PenUnattendedImportPolicy.isPenTag(null))
    }
}
