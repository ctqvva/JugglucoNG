package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PhotoScanTest {
    private static final int REQUEST_BARCODE = 0x10;
    private static final String GS = "\u001D";
    private static final String ACCUCHEK_COMPACT = "01040156300880101125040417260213211R000199040";

    @Test
    public void trimOuterScannerWhitespacePreservesLeadingGsSeparator() {
        String raw = GS + ACCUCHEK_COMPACT;

        assertEquals(raw, PhotoScan.trimOuterScannerWhitespace(raw));
    }

    @Test
    public void normalizeAccuChekPayloadKeepsNativeExpectedLeadingSeparator() {
        String raw = GS + ACCUCHEK_COMPACT;

        assertEquals(raw, PhotoScan.normalizeScanPayload(raw, REQUEST_BARCODE));
    }

    @Test
    public void normalizeAccuChekPayloadRestoresScannerStrippedLeadingSeparator() {
        assertEquals(GS + ACCUCHEK_COMPACT, PhotoScan.normalizeScanPayload(ACCUCHEK_COMPACT, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadDoesNotSplitSerialInternal21() {
        String raw = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        String normalized = PhotoScan.normalizeScanPayload(raw, REQUEST_BARCODE);

        assertEquals(raw, normalized);
        assertFalse(normalized.contains("P2251" + GS + "211237"));
    }

    @Test
    public void normalizeSibionicsPayloadRepairsSeparatorInsideSerial() {
        String broken = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251" + GS + "211237GDR75";
        String expected = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        assertEquals(expected, PhotoScan.normalizeScanPayload(broken, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadRepairsSeparatorInsideSerialWithoutBatchSeparator() {
        String broken = "0106972831641476112512161727061510LT46251211C"
                + "21P2251" + GS + "211237GDR75";
        String expected = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        assertEquals(expected, PhotoScan.normalizeScanPayload(broken, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadAddsMissingSerialSeparator() {
        String compact = "0106972831641476112512161727061510LT46251211C"
                + "21P2251211237GDR75";
        String expected = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        assertEquals(expected, PhotoScan.normalizeScanPayload(compact, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadKeepsExistingLongSerial() {
        String raw = GS + "0106972831640165112312091724120810LT41231108C"
                + GS + "21231108GEPD802JPP76";

        assertEquals(raw, PhotoScan.normalizeScanPayload(raw, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadLeavesExistingCompactLongSerialWithoutGsAlone() {
        String compact = "0106972831640165112312091724120810LT41231108C"
                + "21231108GEPD802JPP76";

        assertEquals(compact, PhotoScan.normalizeScanPayload(compact, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadKeepsSymbologyPrefixLongSerialFallback() {
        String prefixed = "^]0106972831640165112312091724120810LT41231108C"
                + "21231108GEPD802JPP76";
        String expected = GS + "0106972831640165112312091724120810LT41231108C"
                + GS + "21231108GEPD802JPP76";

        assertEquals(expected, PhotoScan.normalizeScanPayload(prefixed, REQUEST_BARCODE));
    }

    // Synthetic CareSens Air code: i-SENS GTIN company prefix, made-up serial, PIN and code.
    private static final String AIR_GTIN = "08806712345675";
    private static final String AIR_SERIAL = "C1Q470A02339";
    private static final String AIR_PIN = "123456";
    private static final String AIR_CODE = "AB12CD34EF56GH78";
    private static final String AIR_CANONICAL = GS + "01" + AIR_GTIN + "17271231" + "21" + AIR_SERIAL
            + GS + "240" + AIR_PIN + GS + "250" + AIR_CODE;

    @Test
    public void careSensAirCanonicalPayloadHasNativeRecordLayout() {
        // sizeof(careSenseAirScan_t) in cpp/SensorGlucoseData.hpp
        assertEquals(69, AIR_CANONICAL.length());
        assertEquals(AIR_CANONICAL, PhotoScan.normalizeScanPayload(AIR_CANONICAL, REQUEST_BARCODE));
    }

    @Test
    public void careSensAirAcceptsUpstreams53ByteCodeWithoutGtin() {
        String shortCode = GS + "17271231" + "21" + AIR_SERIAL
                + GS + "240" + AIR_PIN + GS + "250" + AIR_CODE;
        String stored = AIR_CANONICAL.replace(AIR_GTIN, "00000000000000");

        assertEquals(53, shortCode.length());
        assertEquals(stored, PhotoScan.normalizeScanPayload(shortCode, REQUEST_BARCODE));
        // The Bluetooth PIN keeps its fixed native offset even without AI 01.
        assertEquals(AIR_PIN, stored.substring(43, 49));
        assertEquals(stored, PhotoScan.normalizeScanPayload(shortCode.replace(GS, ""), REQUEST_BARCODE));
    }

    @Test
    public void careSensAirMissingGtinStillRequiresAllFourAirFields() {
        String shortCode = GS + "17271231" + "21" + AIR_SERIAL
                + GS + "240" + AIR_PIN + GS + "250" + AIR_CODE;

        assertNull(PhotoScan.buildCareSensAirPayload(shortCode.replace(GS + "240" + AIR_PIN, "")));
        assertNull(PhotoScan.buildCareSensAirPayload(shortCode + GS + "11260101"));
        assertNull(PhotoScan.buildCareSensAirPayload(shortCode.replace(AIR_SERIAL, "short")));
    }

    @Test
    public void careSensAirRestoresSeparatorsTheScannerDropped() {
        String leadingDropped = AIR_CANONICAL.substring(1);
        String allDropped = AIR_CANONICAL.replace(GS, "");

        assertEquals(AIR_CANONICAL, PhotoScan.normalizeScanPayload(leadingDropped, REQUEST_BARCODE));
        assertEquals(AIR_CANONICAL, PhotoScan.normalizeScanPayload(allDropped, REQUEST_BARCODE));
    }

    @Test
    public void careSensAirAcceptsSymbologyPrefixAndCaretSeparators() {
        String caret = "]Q3" + AIR_CANONICAL.substring(1).replace(GS, "^]");

        assertEquals(AIR_CANONICAL, PhotoScan.normalizeScanPayload(caret, REQUEST_BARCODE));
    }

    @Test
    public void careSensAirAcceptsApplicationIdentifiersInAnyOrder() {
        String reordered = GS + "240" + AIR_PIN + GS + "250" + AIR_CODE + GS + "01" + AIR_GTIN
                + "21" + AIR_SERIAL + GS + "17271231";

        assertEquals(AIR_CANONICAL, PhotoScan.normalizeScanPayload(reordered, REQUEST_BARCODE));
    }

    @Test
    public void careSensAirRejectsOtherManufacturersAndWrongLengths() {
        String otherCompany = AIR_CANONICAL.replace(AIR_GTIN, "08806799345675");
        String shortSerial = AIR_CANONICAL.replace("21" + AIR_SERIAL, "21C1Q470A0233");
        String missingCode = AIR_CANONICAL.substring(0, AIR_CANONICAL.indexOf(GS + "250"));

        assertNull(PhotoScan.buildCareSensAirPayload(otherCompany));
        assertNull(PhotoScan.buildCareSensAirPayload(shortSerial));
        assertNull(PhotoScan.buildCareSensAirPayload(missingCode));
    }

    @Test
    public void careSensAirParserLeavesOtherVendorsAlone() {
        String sibionics = GS + "0106972831641476112512161727061510LT46251211C" + GS + "21P2251211237GDR75";

        assertNull(PhotoScan.buildCareSensAirPayload(GS + ACCUCHEK_COMPACT));
        assertNull(PhotoScan.buildCareSensAirPayload(sibionics));
    }
}
