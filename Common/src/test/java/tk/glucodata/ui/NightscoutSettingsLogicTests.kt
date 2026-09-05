package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NightscoutSettingsLogicTests {
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
