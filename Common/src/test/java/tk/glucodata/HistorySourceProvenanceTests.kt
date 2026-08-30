package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySourceProvenanceTests {
    @Test
    fun newRowsUseTheirIncomingSource() {
        assertEquals(
            GlucoseReadingSource.CLONE_TURN,
            HistorySourceProvenance.stableSource(null, GlucoseReadingSource.CLONE_TURN),
        )
    }

    @Test
    fun knownSourceSurvivesAnOverlapRescan() {
        assertEquals(
            GlucoseReadingSource.CLONE_TURN,
            HistorySourceProvenance.stableSource(
                GlucoseReadingSource.CLONE_TURN,
                GlucoseReadingSource.CLONE_LOCAL_ICE,
            ),
        )
    }

    @Test
    fun aNewKnownSourceCanReplaceTheLegacySensorDefault() {
        assertEquals(
            GlucoseReadingSource.NIGHTSCOUT,
            HistorySourceProvenance.stableSource(
                GlucoseReadingSource.SENSOR,
                GlucoseReadingSource.NIGHTSCOUT,
            ),
        )
    }

    @Test
    fun firstStoredTimeSurvivesOverlapRewrites() {
        assertEquals(
            123L,
            HistorySourceProvenance.stableFirstStoredAt(123L, 999L),
        )
        assertEquals(
            999L,
            HistorySourceProvenance.stableFirstStoredAt(null, 999L),
        )
    }
}
