package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the BLE payload framing + 12-byte record field extraction. */
class OttaiParserTests {

    private fun rec12(
        dataNo: Int, voltage: Int, b5: Int, b6: Int, b7: Int,
        curLo: Int, curHi: Int, tempLo: Int, tempHi: Int,
    ) = byteArrayOf(
        0, 0,
        (dataNo and 0xFF).toByte(), ((dataNo ushr 8) and 0xFF).toByte(),
        voltage.toByte(), b5.toByte(), b6.toByte(), b7.toByte(),
        curLo.toByte(), curHi.toByte(), tempLo.toByte(), tempHi.toByte(),
    )

    @Test
    fun parseRecord_fieldsAndMixedEndianRuntime() {
        // runtime = (b5<<16)|(b7<<8)|b6 ; choose b5=0x01,b6=0x03,b7=0x02 -> 0x010203
        val rec = rec12(
            dataNo = 300, voltage = 5, b5 = 0x01, b6 = 0x03, b7 = 0x02,
            curLo = 0x6D, curHi = 0x00, tempLo = 0xAC, tempHi = 0x0D,
        )
        val r = OttaiParser.parseRecord(rec)
        assertEquals(300, r.dataNo)
        assertEquals(5, r.voltage)
        assertEquals(0x010203, r.runtimeSec) // 66051
        assertEquals(109, r.rawCurrent)       // 0x006D
        assertEquals(35.0, r.temperatureC, 1e-9) // 0x0DAC=3500 /100
    }

    @Test
    fun frameRecords_injectsSequentialDataNo() {
        // header: 0..3 prefix, 4..5 frontDataNo=5 (LE), 6..7 marker, then 2x 8-byte records
        val payload = byteArrayOf(
            0, 0, 0, 0,
            5, 0,            // frontDataNo = 5
            2, 0,            // marker/count
            // record 0
            1, 11, 12, 13, 14, 15, 16, 17,
            // record 1
            2, 21, 22, 23, 24, 25, 26, 27,
        )
        val recs = OttaiParser.frameRecords(payload)
        assertEquals(2, recs.size)
        // dataNo injected at [2..3] = frontDataNo + index
        assertEquals(5, OttaiParser.parseRecord(recs[0]).dataNo)
        assertEquals(6, OttaiParser.parseRecord(recs[1]).dataNo)
        // voltage = record byte 0 lands at parser[4]
        assertEquals(1, OttaiParser.parseRecord(recs[0]).voltage)
        assertEquals(2, OttaiParser.parseRecord(recs[1]).voltage)
    }

    @Test
    fun isRecordSane_gate() {
        fun mk(dataNo: Int, runtime: Int) = OttaiRecord(dataNo, 5, runtime, 100, 35.0, ByteArray(12))
        assertFalse(OttaiParser.isRecordSane(mk(65535, 0)))
        assertTrue(OttaiParser.isRecordSane(mk(100, 6000)))   // |100 - 100| = 0
        assertTrue(OttaiParser.isRecordSane(mk(100, 0)))      // |100 - 0| = 100 <= 120
        assertFalse(OttaiParser.isRecordSane(mk(200, 0)))     // |200 - 0| = 200 > 120
        assertTrue(OttaiParser.isRecordSane(mk(50, 0)))       // dataNo < 60 -> skew check skipped
    }

    @Test
    fun averageTick_rule() {
        fun mk(dataNo: Int, runtime: Int) = OttaiRecord(dataNo, 5, runtime, 100, 35.0, ByteArray(12))
        assertTrue(OttaiParser.isAverageTick(mk(10, 3200)))
        assertFalse(OttaiParser.isAverageTick(mk(10, 3000)))  // below warmup
        assertFalse(OttaiParser.isAverageTick(mk(7, 4000)))   // not multiple of 5
    }

    @Test
    fun parseLive_endToEnd_decryptFrameFormula() {
        val sessionKey = "0123456789abcdef0123456789abcdef"
        // one 8-byte record: voltage=5, runtime bytes, current LE16=200 (0xC8,0x00), temp 0x0DAC
        val payload = byteArrayOf(
            0, 0, 0, 0,
            7, 0,            // frontDataNo = 7
            1, 0,            // marker
            5, 0x01, 0x03, 0x02, 0xC8.toByte(), 0x00, 0xAC.toByte(), 0x0D,
        )
        val cipher = OttaiCrypto.encryptPayload(payload, sessionKey)!!
        // method: current * coeff0  => 200 * 0.025 = 5.0
        val reading = OttaiParser.parseLive(
            cipher = cipher,
            sessionKeyHex = sessionKey,
            method = "V0 C0 ML",
            coefficients = listOf(0.025),
            activeTimeMs = 1_700_000_000_000L,
        )
        assertNotNull(reading)
        assertEquals(7, reading!!.record.dataNo)
        assertEquals(200, reading.record.rawCurrent)
        assertEquals(5.0, reading.adjustGlucose, 1e-9)
        assertEquals(1_700_000_000_000L + reading.record.runtimeSec * 1000L, reading.monitorTimeMs)
        assertTrue(reading.valid || reading.record.dataNo < 60)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    @Test
    fun frameRecords_v17_nineByteLayout() {
        // Real decrypted V1.7 notify: header [0000][dataNo=0x4C81 LE][ffff] + one 9-byte
        // record (current[0:2]=0x3C1F, voltage[6]=0x47, temp[7:9]=0x0BC5) + zero padding.
        val payload = hex("00000000814cffff1f3c34e115ba47c50b" + "00".repeat(15))
        val recs = OttaiParser.frameRecords(payload, "V1.7.SH2542.1")
        assertEquals(1, recs.size) // trailing zero-padding record skipped
        val r = OttaiParser.parseRecord(recs[0])
        assertEquals(19585, r.dataNo)              // 0x4C81
        assertEquals(15391, r.rawCurrent)          // 0x3C1F LE
        assertEquals(71, r.voltage)                // 0x47
        assertEquals(30.13, r.temperatureC, 1e-9)  // 0x0BC5 / 100
        assertEquals(19585 * 60, r.runtimeSec)     // derived from dataNo (counter wraps)
    }

    @Test
    fun chooseRecordSize_confirmedVersionsAreDeterministic() {
        // Confirmed E-families trust the version string outright — no structural guessing.
        val nineData = hex("00000000814cffff1f3c34e115ba47c50b" + "00".repeat(15))
        val eightData = hex("000000000a000300" + "05010203401fac0d" + "05010203501fb00d" + "05010203601fb40d")
        assertEquals(OttaiParser.BLE_RECORD_SIZE_E12, OttaiParser.chooseRecordSize(nineData, "E1.2.3(V1.7.SH2542.1)"))
        assertEquals(OttaiParser.BLE_RECORD_SIZE_E12, OttaiParser.chooseRecordSize(nineData, "vE1.2.3(V1.7.SH2542.1)"))
        assertEquals(OttaiParser.BLE_RECORD_SIZE, OttaiParser.chooseRecordSize(eightData, "V1.5.S2428.1"))
    }

    @Test
    fun chooseRecordSize_e11CanUseNineByteLayout() {
        // The 2026-08-23 CN V3 sensor reports the same E1.1.4(V1.7.S2530.1) as the older
        // 8-byte Syai, but its 176-byte history pages advance by 18 and contain 18 nine-byte
        // records plus six padding bytes. The payload, not E1.1, must select the layout.
        val nineByteData = ByteArray(176).also { p ->
            p[4] = 0; p[5] = 0
            for (i in 0 until 18) {
                val src = 8 + i * 9
                p[src] = 0x20; p[src + 1] = 0x1F // current = 7968 LE
                p[src + 6] = 0x47                // voltage
                p[src + 7] = 0xAC.toByte(); p[src + 8] = 0x0D // temp = 35.00 C LE
            }
        }
        assertEquals(
            OttaiParser.BLE_RECORD_SIZE_E12,
            OttaiParser.chooseRecordSize(nineByteData, "E1.1.4(V1.7.S2530.1)"),
        )
        val records = OttaiParser.frameRecords(nineByteData, "E1.1.4(V1.7.S2530.1)")
        assertEquals(18, records.size)
        val first = OttaiParser.parseRecord(records.first())
        assertEquals(7968, first.rawCurrent)
        assertEquals(35.0, first.temperatureC, 1e-9)
    }

    @Test
    fun frameRecords_e11NineByteLiveNotifyIgnoresShortPadding() {
        // The same CN V3 sensor's live frame is header + one 9-byte record + seven zero bytes.
        // Its length is also compatible with two 8-byte records, so content must break the tie.
        val payload = hex("000000007600ffff1f3c34e115ba47c50b" + "00".repeat(7))
        assertEquals(
            OttaiParser.BLE_RECORD_SIZE_E12,
            OttaiParser.chooseRecordSize(payload, "E1.1.4(V1.7.S2530.1)"),
        )
        val records = OttaiParser.frameRecords(payload, "E1.1.4(V1.7.S2530.1)")
        assertEquals(1, records.size)
        assertEquals(118, OttaiParser.parseRecord(records.single()).dataNo)
    }

    @Test
    fun frameRecords_e11SyaiFrameKeepsEveryRecord() {
        // The older Syai with the SAME E1.1 version remains 8-byte: header + 20 records in
        // 168 bytes, front=0, and the next frame starts at 20. Structural selection preserves it.
        val payload = ByteArray(168).also { p ->
            p[4] = 0; p[5] = 0 // frontDataNo = 0
            for (i in 0 until 20) {
                val src = 8 + i * 8
                p[src] = 5                       // voltage
                p[src + 4] = 0x0C; p[src + 5] = 0x1E // current = 7692 LE
                p[src + 6] = 0xA4.toByte(); p[src + 7] = 0x0C // temp = 32.36 C LE
            }
        }
        val recs = OttaiParser.frameRecords(payload, "E1.1.4(V1.7.S2530.1)")
        assertEquals(20, recs.size)
        assertEquals(19, OttaiParser.parseRecord(recs.last()).dataNo)
        val r = OttaiParser.parseRecord(recs[0])
        assertEquals(7692, r.rawCurrent)
        assertEquals(32.36, r.temperatureC, 1e-9)
    }

    @Test
    fun chooseRecordSize_picksNineForUnknownNineByteFirmware() {
        // A brand-new version string we've never enumerated still works, by structure alone.
        val payload = hex("00000000814cffff1f3c34e115ba47c50b" + "00".repeat(15))
        assertEquals(OttaiParser.BLE_RECORD_SIZE_E12, OttaiParser.chooseRecordSize(payload, "E9.9.9(V3.1.ZZ0000.0)"))
        // Including a bare V1.7 with no E-number: ambiguous by name, decided by the data.
        assertEquals(OttaiParser.BLE_RECORD_SIZE_E12, OttaiParser.chooseRecordSize(payload, "V1.7.SH2542.1"))
    }

    @Test
    fun chooseRecordSize_picksEightForGenuineEightByteData() {
        // Real 8-byte records (current[4:6]=8000, temp[6:8]=35C) stay 8-byte for any version.
        val payload = hex("000000000a000300" + "05010203401fac0d" + "05010203501fb00d" + "05010203601fb40d")
        assertEquals(OttaiParser.BLE_RECORD_SIZE, OttaiParser.chooseRecordSize(payload, "E9.9.9(V9.9.UNK)"))
        val r = OttaiParser.parseRecord(OttaiParser.frameRecords(payload, "")[0])
        assertEquals(8000, r.rawCurrent)
        assertEquals(35.0, r.temperatureC, 1e-9)
    }
}
