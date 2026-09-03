package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.drivers.nightscout.NightscoutModePreference

class NightscoutSettingsLogicTests {
    @Test
    fun connectionTestUsesTheSelectedModesApiVersion() {
        assertEquals(
            false,
            nightscoutModeUsesV3(NightscoutModePreference.Mode.UPLOAD, false, true),
        )
        assertEquals(
            true,
            nightscoutModeUsesV3(NightscoutModePreference.Mode.FOLLOW, false, true),
        )
    }

    @Test
    fun inactiveNightscoutDisclosesNoDependentSettings() {
        val visibility = nightscoutSettingsVisibility(
            active = false,
            journalEnabled = true,
            sendTreatments = true,
            mode = NightscoutModePreference.Mode.UPLOAD,
            uploaderV3 = true,
            followerV3 = false,
        )

        assertEquals(false, visibility.configuration)
        assertEquals(false, visibility.journalOptions)
        assertEquals(false, visibility.longInsulin)
        assertEquals(false, visibility.tokenRefresh)
    }

    @Test
    fun journalAndTreatmentDependenciesControlTheirOwnRows() {
        val journalOff = nightscoutSettingsVisibility(
            active = true,
            journalEnabled = false,
            sendTreatments = true,
            mode = NightscoutModePreference.Mode.UPLOAD,
            uploaderV3 = false,
            followerV3 = false,
        )
        val treatmentsOff = nightscoutSettingsVisibility(
            active = true,
            journalEnabled = true,
            sendTreatments = false,
            mode = NightscoutModePreference.Mode.UPLOAD,
            uploaderV3 = false,
            followerV3 = false,
        )

        assertEquals(false, journalOff.journalOptions)
        assertEquals(false, journalOff.longInsulin)
        assertEquals(true, treatmentsOff.journalOptions)
        assertEquals(false, treatmentsOff.longInsulin)
    }

    @Test
    fun v3RefusalShowsTheServersPermissionMessage() {
        assertEquals(
            "Missing permission api:entries:update",
            nightscoutResponseDetail(
                """{"status":403,"message":"Missing permission api:entries:update"}"""
            )
        )
    }

    @Test
    fun responseWhitespaceIsCollapsedForTheStatusCard() {
        assertEquals(
            "Bad gateway response",
            nightscoutResponseDetail("  Bad gateway\n response  ")
        )
    }
}
