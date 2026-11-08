package tk.glucodata.share.lumiflex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumiflexCloudModelsTests {

    @Test
    fun frontRecordIdIsMd5OfSensorIdAndOffsetMinutes() {
        val record = CgmRecord("SN1", "SN1", 0, 123, 1_700_000_000_000L, 112, 2)
        assertEquals("a7eb29ff5b7727e9ee4c9426d8fd5313", record.frontRecordId())
    }

    @Test
    fun fromRowComputesMinutesSinceSensorStart() {
        val record = CgmRecord.fromRow(
            serial = "SN1",
            timeMs = 1_700_000_000_000L + 5 * 60_000L,
            mgdl = 112,
            trend = 2,
            sensorStartMs = 1_700_000_000_000L,
        )
        assertEquals(5, record?.timeOffsetMinutes)
        assertEquals("SN1", record?.sensorId)
        assertEquals(112, record?.mgdl)
    }

    @Test
    fun fromRowRejectsUnknownStartAndPreStartRows() {
        assertNull(
            CgmRecord.fromRow("SN1", 1_700_000_000_000L, 112, 0, sensorStartMs = 0L),
        )
        assertNull(
            CgmRecord.fromRow("SN1", 1_700_000_000_000L, 112, 0, sensorStartMs = 1_700_000_060_000L),
        )
    }

    @Test
    fun fromRowRejectsOverflowingOffset() {
        val overflow = 1_700_000_000_000L + (Int.MAX_VALUE.toLong() + 1L) * 60_000L
        assertNull(CgmRecord.fromRow("SN1", overflow, 112, 0, sensorStartMs = 1_700_000_000_000L))
    }

    @Test
    fun recordJsonUsesVendorFieldNames() {
        val record = CgmRecord("SN1", "SN1", 0, 12345, 1_700_000_000_000L, 112, 2)
        val json = record.toJson(
            userId = "u1",
            appTime = "2026-11-01 12:34:56",
            appTimeZone = "Europe/Moscow",
            appCreateTime = "2026-11-01 12:34:58",
            dstOffset = "10800",
        )
        assertEquals("899df6d83daf24a748677ecd2ef8d748", json.getString("frontRecordId"))
        assertEquals("u1", json.getString("userId"))
        assertEquals("SN1", json.getString("sensorId"))
        assertEquals("SN1", json.getString("deviceSn"))
        assertEquals(12345, json.getInt("timeOffset"))
        assertEquals(112, json.getInt("glucose"))
        assertEquals("2026-11-01 12:34:56", json.getString("appTime"))
        assertEquals("Europe/Moscow", json.getString("appTimeZone"))
        assertEquals("10800", json.getString("dstOffset"))
        assertFalse(json.has("mainDeviceId"))
        assertFalse(json.has("sensorStartTime"))
        assertFalse(json.has("trendValue"))
        assertFalse(json.has("cgmSn"))
    }

    @Test
    fun loginModelReadsDataEnvelope() {
        val model = LoginModel.fromJson(
            JSONObject("""{"token":"tok","userId":"u1","email":"a@b.c","phone":""}"""),
        )
        assertEquals("tok", model.token)
        assertEquals("u1", model.userId)
        assertEquals("a@b.c", model.email)
        assertEquals("", LoginModel.fromJson(null).token)
    }

    @Test
    fun followerInfoReadsFlags() {
        val info = FollowerInfo.fromJson(
            JSONObject("""{"userAuthorizationId":"a1","readerUserName":"f@b.c","readerAlias":"Ann","hideState":1,"normalPushState":0,"emergePushState":1,"extremeHighPushState":0}"""),
        )
        assertEquals("a1", info.userAuthorizationId)
        assertEquals("Ann", info.readerAlias)
        assertEquals(1, info.hideState)
        assertEquals("", FollowerInfo.fromJson(null).userAuthorizationId)
        assertTrue(FollowerInfo.fromJson(JSONObject("{}")).readerUserName.isEmpty())
    }
}
