package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScanNfcVTests {
    @Test
    public void decodedLibreScanRequestsHistorySync() {
        assertTrue(ScanNfcV.shouldSyncDecodedHistory(0, 123));
        assertTrue(ScanNfcV.shouldSyncDecodedHistory(5, 0));
        assertTrue(ScanNfcV.shouldSyncDecodedHistory(0x85, 0));
    }

    @Test
    public void nativeDecodeErrorsDoNotRequestHistorySync() {
        assertFalse(ScanNfcV.shouldSyncDecodedHistory(10, 0));
        assertFalse(ScanNfcV.shouldSyncDecodedHistory(12, 0));
    }
}
