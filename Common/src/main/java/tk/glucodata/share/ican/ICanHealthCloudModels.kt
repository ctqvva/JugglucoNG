package tk.glucodata.share.ican

import org.json.JSONArray
import org.json.JSONObject

data class LoginRequest(
    val grantType: String = "",
    val scope: String = "",
    val type: String = "",
    val thirdAuth: String = "",
    val country: String = "",
    val ids: String = "",
    val tenantId: String = "",
    val phoneNum: String = "",
    val password: String = "",
    val thirdAuthKey: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("grant_type", grantType)
        put("scope", scope)
        put("type", type)
        put("thirdAuth", thirdAuth)
        put("country", country)
        put("ids", ids)
        put("tenantId", tenantId)
        put("phone_num", phoneNum)
        put("password", password)
        put("thirdAuthKey", thirdAuthKey)
    }
}

data class LoginModel(
    val accessToken: String = "",
    val tokenType: String = "",
    val refreshToken: String = "",
    val expiresIn: Long = 0L,
    val scope: String = "",
    val tenantId: String = "",
    val tenantIds: String = "",
    val jti: String = "",
    val country: String = "",
    val account: String = "",
    val accountId: String = "",
    val accountType: String = "",
    val appId: String = "",
    val avatarUrl: String = "",
    val clientId: String = "",
    val clientType: String = "",
    val email: String = "",
    val host: String = "",
    val imAccount: String = "",
    val languageKey: String = "",
    val mchId: String = "",
    val mchCode: String = "",
    val nickName: String = "",
    val oauthId: String = "",
    val orgId: String = "",
    val phone: String = "",
    val phoneAreaCode: String = "",
    val postId: String = "",
    val realName: String = "",
    val roleId: String = "",
    val roleName: String = "",
    val signAccount: String = "",
    val stateMode: String = "",
    val status: String = "",
    val supportBgm: String = "",
    val centre: String = "",
    val centerCode: String = "",
    val userId: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject?): LoginModel {
            if (json == null) return LoginModel()
            return LoginModel(
                accessToken = json.optString("access_token", ""),
                tokenType = json.optString("token_type", ""),
                refreshToken = json.optString("refresh_token", ""),
                expiresIn = json.optLong("expires_in", 0L),
                scope = json.optString("scope", ""),
                tenantId = json.optString("tenant_id", ""),
                tenantIds = json.optString("tenant_ids", ""),
                jti = json.optString("jti", ""),
                country = json.optString("country", ""),
                account = json.optString("account", ""),
                accountId = json.optString("account_id", ""),
                accountType = json.optString("account_type", ""),
                appId = json.optString("app_id", ""),
                avatarUrl = json.optString("avatar_url", ""),
                clientId = json.optString("client_id", ""),
                clientType = json.optString("client_type", ""),
                email = json.optString("email", ""),
                host = json.optString("host", ""),
                imAccount = json.optString("im_account", ""),
                languageKey = json.optString("language_key", ""),
                mchId = json.optString("mch_id", ""),
                mchCode = json.optString("mch_code", ""),
                nickName = json.optString("nick_name", ""),
                oauthId = json.optString("oauth_id", ""),
                orgId = json.optString("org_id", ""),
                phone = json.optString("phone", ""),
                phoneAreaCode = json.optString("phoneAreaCode", ""),
                postId = json.optString("post_id", ""),
                realName = json.optString("real_name", ""),
                roleId = json.optString("role_id", ""),
                roleName = json.optString("role_name", ""),
                signAccount = json.optString("sign_account", ""),
                stateMode = json.optString("state_mode", ""),
                status = json.optString("status", ""),
                supportBgm = json.optString("supportBgm", ""),
                centre = json.optString("centre", ""),
                centerCode = json.optString("centerCode", ""),
                userId = json.optString("user_id", ""),
            )
        }
    }
}

data class FollowerFriends(
    val id: String = "",
    val userId: String = "",
    val followerId: String = "",
    val sharerId: String = "",
    val sharerName: String = "",
    val phone: String = "",
    val email: String = "",
    val phoneAreaCode: String = "",
    val countryCode: String = "",
    val followerStatus: String = "",
    val followerStatusName: String = "",
    val invited: String = "",
    val invitedTime: String = "",
    val createTime: String = "",
    val updateTime: String = "",
    val deleteTime: String = "",
    val status: String = "",
    val isDeleted: String = "",
    val unfollow: String = "",
    val viewPermission: String = "",
    val shareFollower: String = "",
    val shareHcp: String = "",
    val dateType: String = "",
    val timeFormat: String = "",
    val unit: String = "",
    val highGlucose: String = "",
    val lowGlucose: String = "",
    val highMessage: String = "",
    val lowMessage: String = "",
    val highSounds: String = "",
    val lowSounds: String = "",
    val hiVibration: String = "",
    val loVibration: String = "",
    val vibrateOnly: String = "",
    val sysSound: String = "",
    val deviceDisconnectSound: String = "",
    val notDisturb: String = "",
    val overrideDisturb: String = "",
    val silenceAll: String = "",
    val silenceAllBeginTime: String = "",
    val silenceAllEndTime: String = "",
    val urgentReminder: String = "",
    val createAccount: String = "",
    val createOrg: String = "",
    val centerCode: String = "",
    val centre: String = "",
    val techSupport: String = "",
    val cloudSync: String = "",
    val appPermissionSwitch: String = "",
    val analyzeDataChn: String = "",
    val analyzeDataEur: String = "",
    val improveProdEur: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject?): FollowerFriends {
            if (json == null) return FollowerFriends()
            return FollowerFriends(
                id = json.optString("id", ""),
                userId = json.optString("userId", ""),
                followerId = json.optString("followerId", ""),
                sharerId = json.optString("sharerId", ""),
                sharerName = json.optString("sharerName", ""),
                phone = json.optString("phone", ""),
                email = json.optString("email", ""),
                phoneAreaCode = json.optString("phoneAreaCode", ""),
                countryCode = json.optString("countryCode", ""),
                followerStatus = json.optString("followerStatus", ""),
                followerStatusName = json.optString("followerStatusName", ""),
                invited = json.optString("invited", ""),
                invitedTime = json.optString("invitedTime", ""),
                createTime = json.optString("createTime", ""),
                updateTime = json.optString("updateTime", ""),
                deleteTime = json.optString("deleteTime", ""),
                status = json.optString("status", ""),
                isDeleted = json.optString("isDeleted", ""),
                unfollow = json.optString("unfollow", ""),
                viewPermission = json.optString("viewPermission", ""),
                shareFollower = json.optString("shareFollower", ""),
                shareHcp = json.optString("shareHcp", ""),
                dateType = json.optString("dateType", ""),
                timeFormat = json.optString("timeFormat", ""),
                unit = json.optString("unit", ""),
                highGlucose = json.optString("highGlucose", ""),
                lowGlucose = json.optString("lowGlucose", ""),
                highMessage = json.optString("highMessage", ""),
                lowMessage = json.optString("lowMessage", ""),
                highSounds = json.optString("highSounds", ""),
                lowSounds = json.optString("lowSounds", ""),
                hiVibration = json.optString("hiVibration", ""),
                loVibration = json.optString("loVibration", ""),
                vibrateOnly = json.optString("vibrateOnly", ""),
                sysSound = json.optString("sysSound", ""),
                deviceDisconnectSound = json.optString("deviceDisconnectSound", ""),
                notDisturb = json.optString("notDisturb", ""),
                overrideDisturb = json.optString("overrideDisturb", ""),
                silenceAll = json.optString("silenceAll", ""),
                silenceAllBeginTime = json.optString("silenceAllBeginTime", ""),
                silenceAllEndTime = json.optString("silenceAllEndTime", ""),
                urgentReminder = json.optString("urgentReminder", ""),
                createAccount = json.optString("createAccount", ""),
                createOrg = json.optString("createOrg", ""),
                centerCode = json.optString("centerCode", ""),
                centre = json.optString("centre", ""),
                techSupport = json.optString("techSupport", ""),
                cloudSync = json.optString("cloudSync", ""),
                appPermissionSwitch = json.optString("appPermissionSwitch", ""),
                analyzeDataChn = json.optString("analyzeDataChn", ""),
                analyzeDataEur = json.optString("analyzeDataEur", ""),
                improveProdEur = json.optString("improveProdEur", ""),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("userId", userId)
        put("followerId", followerId)
        put("sharerId", sharerId)
        put("sharerName", sharerName)
        put("phone", phone)
        put("email", email)
        put("phoneAreaCode", phoneAreaCode)
        put("countryCode", countryCode)
        put("followerStatus", followerStatus)
        put("followerStatusName", followerStatusName)
        put("invited", invited)
        put("invitedTime", invitedTime)
        put("createTime", createTime)
        put("updateTime", updateTime)
        put("deleteTime", deleteTime)
        put("status", status)
        put("isDeleted", isDeleted)
        put("unfollow", unfollow)
        put("viewPermission", viewPermission)
        put("shareFollower", shareFollower)
        put("shareHcp", shareHcp)
        put("dateType", dateType)
        put("timeFormat", timeFormat)
        put("unit", unit)
        put("highGlucose", highGlucose)
        put("lowGlucose", lowGlucose)
        put("highMessage", highMessage)
        put("lowMessage", lowMessage)
        put("highSounds", highSounds)
        put("lowSounds", lowSounds)
        put("hiVibration", hiVibration)
        put("loVibration", loVibration)
        put("vibrateOnly", vibrateOnly)
        put("sysSound", sysSound)
        put("deviceDisconnectSound", deviceDisconnectSound)
        put("notDisturb", notDisturb)
        put("overrideDisturb", overrideDisturb)
        put("silenceAll", silenceAll)
        put("silenceAllBeginTime", silenceAllBeginTime)
        put("silenceAllEndTime", silenceAllEndTime)
        put("urgentReminder", urgentReminder)
        put("createAccount", createAccount)
        put("createOrg", createOrg)
        put("centerCode", centerCode)
        put("centre", centre)
        put("techSupport", techSupport)
        put("cloudSync", cloudSync)
        put("appPermissionSwitch", appPermissionSwitch)
        put("analyzeDataChn", analyzeDataChn)
        put("analyzeDataEur", analyzeDataEur)
        put("improveProdEur", improveProdEur)
    }
}

data class AppConfig(
    val baseUrl: String = "",
    val appHostFirstPath: String = "",
    val appName: String = "",
    val appVersion: String = "",
    val appVersionCode: String = "",
    val authorization: String = "",
    val clientId: String = "",
    val countryCode: String = "",
    val isEncrypt: String = "",
    val langCode: String = "",
    val loginTypes: String = "",
    val passwordRule: String = "",
    val platformType: String = "",
    val rsaPublicKey: String = "",
    val subdomain: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject?): AppConfig {
            if (json == null) return AppConfig()
            return AppConfig(
                baseUrl = json.optString("baseUrl", ""),
                appHostFirstPath = json.optString("appHostFirstPath", ""),
                appName = json.optString("appName", ""),
                appVersion = json.optString("appVersion", ""),
                appVersionCode = json.optString("appVersionCode", ""),
                authorization = json.optString("authorization", ""),
                clientId = json.optString("clientId", ""),
                countryCode = json.optString("countryCode", ""),
                isEncrypt = json.optString("isEncrypt", ""),
                langCode = json.optString("langCode", ""),
                loginTypes = json.optString("loginTypes", ""),
                passwordRule = json.optString("passwordRule", ""),
                platformType = json.optString("platformType", ""),
                rsaPublicKey = json.optString("rsaPublicKey", ""),
                subdomain = json.optString("subdomain", ""),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("baseUrl", baseUrl)
        put("appHostFirstPath", appHostFirstPath)
        put("appName", appName)
        put("appVersion", appVersion)
        put("appVersionCode", appVersionCode)
        put("authorization", authorization)
        put("clientId", clientId)
        put("countryCode", countryCode)
        put("isEncrypt", isEncrypt)
        put("langCode", langCode)
        put("loginTypes", loginTypes)
        put("passwordRule", passwordRule)
        put("platformType", platformType)
        put("rsaPublicKey", rsaPublicKey)
        put("subdomain", subdomain)
    }
}

data class EgvUpload(
    val serial: String = "",
    val timeMs: Long = 0L,
    val mgdl: Int = 0,
    val trend: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject?): EgvUpload {
            if (json == null) return EgvUpload()
            return EgvUpload(
                serial = json.optString("serial", ""),
                timeMs = json.optLong("timeMs", 0L),
                mgdl = json.optInt("mgdl", 0),
                trend = json.optInt("trend", 0),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("serial", serial)
        put("timeMs", timeMs)
        put("mgdl", mgdl)
        put("trend", trend)
    }
}
