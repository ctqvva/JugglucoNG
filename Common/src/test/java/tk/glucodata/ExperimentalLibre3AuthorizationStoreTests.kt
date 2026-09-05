package tk.glucodata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentalLibre3AuthorizationStoreTests {
    @Test
    fun exactAuthorizationRecordRoundTrips() {
        val record = ByteArray(ExperimentalLibre3AuthorizationStore.RECORD_SIZE) { (it * 37).toByte() }
        val encoded = ExperimentalLibre3AuthorizationStore.encode(record)
        assertEquals(ExperimentalLibre3AuthorizationStore.RECORD_SIZE * 2, encoded!!.length)
        assertArrayEquals(record, ExperimentalLibre3AuthorizationStore.decode(encoded))
    }

    @Test
    fun malformedRecordsFailClosed() {
        assertNull(ExperimentalLibre3AuthorizationStore.encode(null))
        assertNull(ExperimentalLibre3AuthorizationStore.encode(ByteArray(148)))
        assertNull(ExperimentalLibre3AuthorizationStore.encode(ByteArray(150)))
        assertNull(ExperimentalLibre3AuthorizationStore.decode(null))
        assertNull(ExperimentalLibre3AuthorizationStore.decode("00".repeat(148)))
        assertNull(ExperimentalLibre3AuthorizationStore.decode("gg".repeat(149)))
    }
}
