package tk.glucodata.ui

import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecordedDisplaySignatureTests {
    @Test fun anInteriorRecordOrLaneChangeInvalidatesTheHistoryCache() {
        val original = (0..100).map { GlucosePoint(value = 100f, rawValue = 100f, time = "", timestamp = it*60_000L, sensorSerial = "A") }
        val sealed = original.toMutableList().also { it[37] = it[37].copy(sealedDisplayValue = 120f, sealedDisplayViewMode = 0) }
        val otherLane = sealed.toMutableList().also { it[37] = it[37].copy(sealedDisplayViewMode = 1) }
        assertNotEquals(recordedDisplaySignature(original), recordedDisplaySignature(sealed))
        assertNotEquals(recordedDisplaySignature(sealed), recordedDisplaySignature(otherLane))
    }
}
