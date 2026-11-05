package tk.glucodata.share.lumiflex

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

data class CgmRecord(
    val serial: String,
    val sensorId: String,
    val sensorIndex: Int,
    val timeOffsetMinutes: Int,
    val timeMs: Long,
    val mgdl: Int,
    val trend: Int,
) {

    fun frontRecordId(): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest("$sensorId$timeOffsetMinutes".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
    }

    fun toJson(
        userId: String,
        appTime: String,
        appTimeZone: String,
        appCreateTime: String,
        dstOffset: String,
    ): JSONObject = JSONObject()
        .put("frontRecordId", frontRecordId())
        .put("userId", userId)
        .put("sensorId", sensorId)
        .put("deviceSn", serial)
        .put("sensorIndex", sensorIndex)
        .put("timeOffset", timeOffsetMinutes)
        .put("glucose", mgdl)
        .put("glucoseIsValid", 1)
        .put("status", 0)
        .put("quality", 0)
        .put("eventWarning", 0)
        .put("deviceStatus", 0)
        .put("appTime", appTime)
        .put("appTimeZone", appTimeZone)
        .put("appCreateTime", appCreateTime)
        .put("dstOffset", dstOffset)
        .put("rawOne", 0.0)
        .put("rawTwo", 0.0)
        .put("rawVc", 0.0)
        .put("rawIsValid", 1)

    companion object {
        fun fromRow(
            serial: String,
            timeMs: Long,
            mgdl: Int,
            trend: Int,
            sensorStartMs: Long,
            sensorIndex: Int = 0,
            sensorId: String = serial,
        ): CgmRecord? {
            if (sensorStartMs <= 0L || timeMs <= sensorStartMs) return null
            val minutes = (timeMs - sensorStartMs) / 60_000L
            if (minutes !in 1..Int.MAX_VALUE.toLong()) return null
            return CgmRecord(
                serial = serial,
                sensorId = sensorId,
                sensorIndex = sensorIndex,
                timeOffsetMinutes = minutes.toInt(),
                timeMs = timeMs,
                mgdl = mgdl,
                trend = trend,
            )
        }
    }
}

data class LoginModel(
    val token: String = "",
    val userId: String = "",
    val email: String = "",
    val phone: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject?): LoginModel {
            if (json == null) return LoginModel()
            return LoginModel(
                token = json.optString("token", ""),
                userId = json.optString("userId", ""),
                email = json.optString("email", ""),
                phone = json.optString("phone", ""),
            )
        }
    }
}

data class FollowerInfo(
    val userAuthorizationId: String = "",
    val dataProviderId: String = "",
    val dataReaderId: String = "",
    val providerUserName: String = "",
    val providerAlias: String = "",
    val readerUserName: String = "",
    val readerAlias: String = "",
    val hideState: Int = 0,
    val normalPushState: Int = 0,
    val emergePushState: Int = 0,
    val extremeHighPushState: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject?): FollowerInfo {
            if (json == null) return FollowerInfo()
            return FollowerInfo(
                userAuthorizationId = json.optString("userAuthorizationId", ""),
                dataProviderId = json.optString("dataProviderId", ""),
                dataReaderId = json.optString("dataReaderId", ""),
                providerUserName = json.optString("providerUserName", ""),
                providerAlias = json.optString("providerAlias", ""),
                readerUserName = json.optString("readerUserName", ""),
                readerAlias = json.optString("readerAlias", ""),
                hideState = json.optInt("hideState", 0),
                normalPushState = json.optInt("normalPushState", 0),
                emergePushState = json.optInt("emergePushState", 0),
                extremeHighPushState = json.optInt("extremeHighPushState", 0),
            )
        }
    }
}
