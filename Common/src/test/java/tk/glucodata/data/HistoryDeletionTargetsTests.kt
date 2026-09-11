package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDeletionTargetsTests {
    @Test
    fun includesTheSerialItsRoomIdsAndItsNativeNames() {
        val targets = HistoryRepository.historyDeletionTargets(
            serial = "SIBI:0683013AQT9",
            roomQueryIds = listOf("0683013AQT9"),
            nativeNames = listOf("31108GEPD802JPP7"),
        )

        assertEquals(listOf("SIBI:0683013AQT9", "0683013AQT9", "31108GEPD802JPP7"), targets)
    }

    @Test
    fun dropsRepeatsAndBlanksButKeepsOrder() {
        val targets = HistoryRepository.historyDeletionTargets(
            serial = " OLD-SENSOR ",
            roomQueryIds = listOf("OLD-SENSOR", "", "old-alias"),
            nativeNames = listOf("   ", "old-alias", "OLD-SENSOR"),
        )

        assertEquals(listOf("OLD-SENSOR", "old-alias"), targets)
    }

    @Test
    fun aBlankSerialWithNoResolutionDeletesNothing() {
        assertEquals(
            emptyList<String>(),
            HistoryRepository.historyDeletionTargets("   ", emptyList(), emptyList()),
        )
    }
}
