package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class SensorSourceResolverTests {
    @Test
    public void resolvesKnownFallbackKindsWithoutSnapshot() {
        assertEquals("Libre2", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_LIBRE2));
        assertEquals("Libre3", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_LIBRE3));
        assertEquals("Sibionics", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_SIBIONICS));
        assertEquals("AccuChek", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_ACCUCHEK));
        assertEquals("G7", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_DEXCOM));
    }

    @Test
    public void unresolvedFallbackDoesNotLie() {
        assertEquals("Unknown", SensorSourceResolver.resolveSourceInfo(null, 0));
        assertEquals("Unknown", SensorSourceResolver.resolveSourceInfo(null, -1));
    }

    @Test
    public void xdripSourceInfoNamesEachSensor() {
        assertEquals("Libre2", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_LIBRE2, false));
        assertEquals("Libre3", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_LIBRE3, false));
        assertEquals("G7", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_DEXCOM, false));
        assertEquals("Sibionics", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_SIBIONICS, false));
        assertEquals("AccuChek", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_ACCUCHEK, false));
        assertEquals("AidexX", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_AIDEX, false));
        assertEquals("Ottai", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_OTTAI, false));
        assertEquals("iCan", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_ICAN, false));
        assertEquals("Anytime", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_ANYTIME, false));
        assertEquals("MQ", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_MQ, false));
        assertEquals("Nightscout", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_NIGHTSCOUT, false));
        assertEquals("Medtrum", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_MEDTRUM, false));
        // The API source relays another app's readings; it must not default to Libre 2.
        assertEquals("Unknown", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_EXTERNAL, false));
        assertEquals("Libre2", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_EXTERNAL, true));
    }

    @Test
    public void genericFamilyHasNoKindOfItsOwn() {
        assertEquals(SensorSourceResolver.SENSOR_KIND_UNKNOWN,
                SensorSourceResolver.kindForManagedFamily(tk.glucodata.drivers.ManagedSensorUiFamily.GENERIC));
        assertEquals(SensorSourceResolver.SENSOR_KIND_NIGHTSCOUT,
                SensorSourceResolver.kindForManagedFamily(tk.glucodata.drivers.ManagedSensorUiFamily.NIGHTSCOUT));
        assertEquals(SensorSourceResolver.SENSOR_KIND_MEDTRUM,
                SensorSourceResolver.kindForManagedFamily(tk.glucodata.drivers.ManagedSensorUiFamily.MEDTRUM));
    }

    @Test
    public void unresolvedXdripSourceInfoStaysLibreFamilyLikeUpstream() {
        // Native kind 0 and an unresolved reading are the Libre family by elimination.
        assertEquals("Libre2", SensorSourceResolver.xdripSourceInfoForKind(0, false));
        assertEquals("Libre2", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_UNKNOWN, false));
    }

    @Test
    public void reportAsLibre2ClaimsLibre2OnlyWhenAsked() {
        assertEquals("Libre2", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_AIDEX, true));
        assertEquals("Libre2", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_SIBIONICS, true));
        assertEquals("Libre2", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_OTTAI, true));
        // Sources AAPS already trusts keep their own name.
        assertEquals("Libre3", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_LIBRE3, true));
        assertEquals("G7", SensorSourceResolver.xdripSourceInfoForKind(SensorSourceResolver.SENSOR_KIND_DEXCOM, true));
    }

    @Test
    public void stampedManagedFamilyOutranksLibreByElimination() {
        // Codes are ManagedSensorUiFamily.nativeCode; native says Libre 2 for all of these shells.
        final int libre2 = SensorSourceResolver.SENSOR_KIND_LIBRE2;
        assertEquals(SensorSourceResolver.SENSOR_KIND_MQ, SensorSourceResolver.kindForSnapshot(libre2, 1));
        assertEquals(SensorSourceResolver.SENSOR_KIND_AIDEX, SensorSourceResolver.kindForSnapshot(libre2, 2));
        assertEquals(SensorSourceResolver.SENSOR_KIND_ICAN, SensorSourceResolver.kindForSnapshot(libre2, 3));
        assertEquals(SensorSourceResolver.SENSOR_KIND_ANYTIME, SensorSourceResolver.kindForSnapshot(libre2, 4));
        assertEquals(SensorSourceResolver.SENSOR_KIND_OTTAI, SensorSourceResolver.kindForSnapshot(libre2, 5));
        assertEquals(SensorSourceResolver.SENSOR_KIND_SIBIONICS, SensorSourceResolver.kindForSnapshot(libre2, 6));
        assertEquals(SensorSourceResolver.SENSOR_KIND_NIGHTSCOUT, SensorSourceResolver.kindForSnapshot(libre2, 7));
        // Codes above 7 use the fourth family bit (the formerly reserved one).
        assertEquals(SensorSourceResolver.SENSOR_KIND_MEDTRUM, SensorSourceResolver.kindForSnapshot(libre2, 8));
        assertFalse(SensorSourceResolver.isLibreKind(SensorSourceResolver.kindForSnapshot(libre2, 5)));
    }

    @Test
    public void unstampedShellKeepsItsNativeKind() {
        assertEquals(SensorSourceResolver.SENSOR_KIND_LIBRE2,
                SensorSourceResolver.kindForSnapshot(SensorSourceResolver.SENSOR_KIND_LIBRE2, 0));
        assertEquals(SensorSourceResolver.SENSOR_KIND_DEXCOM,
                SensorSourceResolver.kindForSnapshot(SensorSourceResolver.SENSOR_KIND_DEXCOM, 0));
    }

    @Test
    public void xdripSourceInfoNamesCareSensAirLikeUpstream() {
        assertEquals("CareSenseAir",
                SensorSourceResolver.resolveXdripSourceInfo(null, SensorSourceResolver.SENSOR_KIND_CARESENS_AIR));
    }
}
