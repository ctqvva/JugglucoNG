package tk.glucodata.drivers.mq

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Log
import java.util.Locale
import java.util.TimeZone

enum class MQVerifyCodeAction(val wireValue: Int) {
    REGISTER(1),
    LOGIN(2),
    RESET_PASSWORD(3),
}

data class MQCloudActionResult(
    val success: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
    val alreadyMonitoring: Boolean = false,
)

data class MQCloudAuthResult(
    val token: String? = null,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
) {
    val success: Boolean get() = !token.isNullOrBlank()
}

data class MQCloudTokenStatus(
    val isValid: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQCloudAccountAvailability(
    val exists: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQCloudSessionResult(
    val snapshotId: String? = null,
    val success: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQFriendLookupResult(
    val exists: Boolean = false,
    val friendshipState: Int = 0,
    val isFriend: Boolean = false,
    val monitor: Int = 0,
    val snapshotId: String? = null,
    val friendToUserPermission: Int = 0,
    val name: String? = null,
    val phone: String? = null,
    val avatar: String? = null,
    val remark: String? = null,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQIncomingFriendRequest(
    val requestId: String,
    val phone: String,
    val name: String? = null,
    val avatar: String? = null,
    val remark: String? = null,
    val type: String? = null,
)

data class MQSnapshotStateResult(
    val snapshotId: String? = null,
    val bleId: String? = null,
    val qrCode: String? = null,
    val createTimeMs: Long = 0L,
    val deviceDay: Int = 0,
    val lastMachineBloodSugarMmol: Float = Float.NaN,
    val lastDataBag: Int = 0,
    val success: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQCloudHistoryResult(
    val history: List<MQBootstrapHistoryPoint> = emptyList(),
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

internal data class MQCloudPostResult(
    val root: JSONObject? = null,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

object MQCloudClient {
    private const val TAG = MQConstants.TAG
    private const val DEVICE_MODEL = "Android_Phone"
    private const val DEVICE_TYPE = "4"
    private const val DEFAULT_AVATAR = "Filepath"
    private const val HISTORY_BUCKET_CHUNK_MS = 24L * 60L * 60L * 1000L
    private const val HISTORY_FUTURE_GRACE_MS = 5L * 60L * 1000L

    private data class AgentInfo(
        val agent: String,
        val imei: String,
    )

    fun signInWithPassword(
        context: Context,
        credentials: MQAuthCredentials,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAuthResult {
        val agentInfo = buildAgentInfo(context)
        val form = buildString {
            append("account=").append(credentials.account.urlEncode())
            append("&password=").append(credentials.password.urlEncode())
            append("&agent=").append(agentInfo.agent.urlEncode())
            append("&imei=").append(agentInfo.imei.urlEncode())
            append("&model=").append(DEVICE_MODEL.urlEncode())
            append("&type=").append(DEVICE_TYPE.urlEncode())
        }
        val root = postForm(
            url = endpoints.loginWithPasswordUrl,
            form = form,
            authToken = null,
        )
        val token = root.root?.optJSONObject("result")?.optStringOrNull("token")
        if (!token.isNullOrBlank()) {
            Log.i(TAG, "MQ vendor login refreshed token")
            return MQCloudAuthResult(token = token, message = root.message)
        }
        return MQCloudAuthResult(failure = root.failure, message = root.message ?: "MQ login returned no token")
    }

    fun signInWithCode(
        context: Context,
        phone: String,
        captcha: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAuthResult {
        val agentInfo = buildAgentInfo(context)
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&captcha=").append(captcha.urlEncode())
            append("&agent=").append(agentInfo.agent.urlEncode())
            append("&imei=").append(agentInfo.imei.urlEncode())
            append("&model=").append(DEVICE_MODEL.urlEncode())
            append("&type=").append(DEVICE_TYPE.urlEncode())
        }
        val root = postForm(
            url = endpoints.loginByCodeUrl,
            form = form,
            authToken = null,
        )
        val token = root.root?.optJSONObject("result")?.optStringOrNull("token")
        if (!token.isNullOrBlank()) {
            Log.i(TAG, "MQ vendor SMS login refreshed token")
            return MQCloudAuthResult(token = token, message = root.message)
        }
        return MQCloudAuthResult(failure = root.failure, message = root.message ?: "MQ SMS login returned no token")
    }

    fun requestVerifyCode(
        context: Context,
        phone: String,
        action: MQVerifyCodeAction,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult {
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&type=").append(action.wireValue.toString().urlEncode())
        }
        return postAction(
            url = endpoints.getVerifyCodeUrl,
            form = form,
            authToken = null,
            successMessage = "MQ verification code requested",
        )
    }

    fun registerByCode(
        context: Context,
        phone: String,
        captcha: String,
        password: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAuthResult {
        val agentInfo = buildAgentInfo(context)
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&captcha=").append(captcha.urlEncode())
            append("&agent=").append(agentInfo.agent.urlEncode())
            append("&imei=").append(agentInfo.imei.urlEncode())
            append("&model=").append(DEVICE_MODEL.urlEncode())
            append("&password=").append(password.urlEncode())
            append("&type=").append(DEVICE_TYPE.urlEncode())
            append("&avatar=").append(DEFAULT_AVATAR.urlEncode())
        }
        val root = postForm(
            url = endpoints.registerByCodeUrl,
            form = form,
            authToken = null,
        )
        val token = root.root?.optJSONObject("result")?.optStringOrNull("token")
        if (!token.isNullOrBlank()) {
            Log.i(TAG, "MQ vendor SMS registration returned token")
            return MQCloudAuthResult(token = token, message = root.message)
        }
        return MQCloudAuthResult(failure = root.failure, message = root.message ?: "MQ registration returned no token")
    }

    fun resetPasswordByCode(
        context: Context,
        phone: String,
        captcha: String,
        newPassword: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult {
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&captcha=").append(captcha.urlEncode())
            append("&password=").append(newPassword.urlEncode())
        }
        return postAction(
            url = endpoints.resetPasswordByCodeUrl,
            form = form,
            authToken = null,
            successMessage = "MQ password reset submitted",
        )
    }

    fun isAccountAvailable(
        context: Context,
        phone: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAccountAvailability {
        val root = postForm(
            url = endpoints.isAccountExistUrl,
            form = "phone=${phone.urlEncode()}",
            authToken = null,
        )
        if (root.failure != MQBootstrapFailure.NONE && root.root == null) {
            return MQCloudAccountAvailability(failure = root.failure, message = root.message)
        }
        val exists = root.root?.opt("result")?.let { value ->
            when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> value.toString().equals("true", ignoreCase = true) ||
                    value.toString().equals("1")
            }
        } ?: false
        return MQCloudAccountAvailability(exists = exists, message = root.message)
    }

    fun checkToken(
        context: Context,
        authToken: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudTokenStatus {
        val root = postForm(
            url = endpoints.checkTokenUrl,
            form = "token=${authToken.urlEncode()}",
            authToken = null,
        )
        if (root.failure == MQBootstrapFailure.NONE) {
            return MQCloudTokenStatus(isValid = true, message = root.message)
        }
        return MQCloudTokenStatus(isValid = false, failure = root.failure, message = root.message)
    }

    fun logout(
        context: Context,
        phone: String,
        authToken: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.logoutUrl,
            form = "phone=${phone.urlEncode()}",
            authToken = authToken,
            successMessage = "MQ vendor logout submitted",
        )

    fun findFriendUser(
        context: Context,
        authToken: String,
        userAccount: String,
        friendAccount: String,
        type: Int = 0,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQFriendLookupResult {
        val root = postForm(
            url = endpoints.friendUserFindUrl,
            form = buildString {
                append("userAccount=").append(userAccount.urlEncode())
                append("&friendAccount=").append(friendAccount.urlEncode())
                append("&type=").append(type.toString().urlEncode())
            },
            authToken = authToken,
        )
        val result = root.root?.optJSONObject("result")
        if (result == null) {
            return MQFriendLookupResult(
                exists = false,
                failure = root.failure,
                message = root.message,
            )
        }
        return MQFriendLookupResult(
            exists = true,
            friendshipState = result.optString("isFriend").toIntOrNull() ?: 0,
            isFriend = result.optString("isFriend").toIntOrNull() == 1,
            monitor = result.optString("monitor").toIntOrNull() ?: 0,
            snapshotId = result.optStringOrNull("snapshotId"),
            friendToUserPermission = result.optString("friendTOUserPermission").toIntOrNull() ?: 0,
            name = result.optStringOrNull("name"),
            phone = result.optStringOrNull("phone"),
            avatar = result.optStringOrNull("avatar"),
            remark = result.optStringOrNull("remark"),
            failure = if (root.failure == MQBootstrapFailure.NONE || result.length() > 0) {
                MQBootstrapFailure.NONE
            } else {
                root.failure
            },
            message = root.message,
        )
    }

    fun addFriend(
        context: Context,
        authToken: String,
        userAccount: String,
        friendAccount: String,
        permission: Int = 2,
        remark: String = "",
        type: Int = 2,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.addFriendUrl,
            form = buildString {
                append("friendAccount=").append(friendAccount.urlEncode())
                append("&permission=").append(permission.toString().urlEncode())
                append("&remark=").append(remark.urlEncode())
                append("&type=").append(type.toString().urlEncode())
                append("&userAccount=").append(userAccount.urlEncode())
            },
            authToken = authToken,
            successMessage = "MQ friend request submitted",
        )

    fun fetchIncomingFriendRequests(
        context: Context,
        authToken: String,
        userAccount: String,
        status: Int = 0,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): Pair<List<MQIncomingFriendRequest>, MQCloudActionResult> {
        val root = postForm(
            url = endpoints.applyFriendListUrl,
            form = buildString {
                append("userAccount=").append(userAccount.urlEncode())
                append("&status=").append(status.toString().urlEncode())
            },
            authToken = authToken,
        )
        val records = root.root
            ?.optJSONObject("result")
            ?.optJSONArray("records")
            ?: return emptyList<MQIncomingFriendRequest>() to MQCloudActionResult(
                success = root.failure == MQBootstrapFailure.NONE,
                failure = root.failure,
                message = root.message,
            )

        val requests = ArrayList<MQIncomingFriendRequest>(records.length())
        for (index in 0 until records.length()) {
            val item = records.optJSONObject(index) ?: continue
            val requestId = item.opt("friendId")?.toString()?.trim().orEmpty()
            val phone = item.optStringOrNull("phone").orEmpty()
            if (requestId.isEmpty() || phone.isEmpty()) continue
            requests.add(
                MQIncomingFriendRequest(
                    requestId = requestId,
                    phone = phone,
                    name = item.optStringOrNull("name"),
                    avatar = item.optStringOrNull("avatar"),
                    remark = item.optStringOrNull("remark"),
                    type = item.opt("type")?.toString(),
                ),
            )
        }
        return requests to MQCloudActionResult(
            success = true,
            failure = MQBootstrapFailure.NONE,
            message = root.message,
        )
    }

    fun reviewFriendRequest(
        context: Context,
        authToken: String,
        userAccount: String,
        applyFriendId: String,
        approved: Boolean,
        permission: Int = 2,
        aliasName: String = "",
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.reviewFriendUrl,
            form = buildString {
                append("applyFriendId=").append(applyFriendId.urlEncode())
                append("&permission=").append(permission.toString().urlEncode())
                append("&status=").append(if (approved) "1" else "2")
                append("&userAccount=").append(userAccount.urlEncode())
                append("&aliasName=").append(aliasName.urlEncode())
            },
            authToken = authToken,
            successMessage = if (approved) {
                "MQ friend request approved"
            } else {
                "MQ friend request rejected"
            },
        )

    fun querySnapshotById(
        context: Context,
        authToken: String,
        snapshotId: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQSnapshotStateResult {
        val url = buildString {
            append(endpoints.queryByIdUrl)
            append("?id=").append(snapshotId.urlEncode())
            append("&timeZone=").append(TimeZone.getDefault().id.urlEncode())
        }
        val root = getJson(url = url, authToken = authToken)
        val result = root.root?.optJSONObject("result")
        if (result == null) {
            return MQSnapshotStateResult(
                snapshotId = snapshotId,
                failure = root.failure,
                message = root.message,
            )
        }
        return MQSnapshotStateResult(
            snapshotId = result.optStringOrNull("id") ?: snapshotId,
            bleId = result.optStringOrNull("bleId"),
            qrCode = result.optStringOrNull("qrCode"),
            createTimeMs = parseServerTimeMs(result.opt("createTime")) ?: 0L,
            deviceDay = result.optString("deviceDay").toIntOrNull() ?: 0,
            lastMachineBloodSugarMmol = result.optString("lastMachineBloodSugar").toFloatOrNull() ?: Float.NaN,
            lastDataBag = result.optString("lastDataBag").toIntOrNull() ?: 0,
            success = true,
            failure = MQBootstrapFailure.NONE,
            message = root.message,
        )
    }

    fun fetchSnapshotDetailHistory(
        context: Context,
        authToken: String,
        snapshotId: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudHistoryResult {
        val root = postForm(
            url = endpoints.viewAllSnapshotDetailUrl,
            form = buildString {
                append("snapshotId=").append(snapshotId.urlEncode())
                append("&timeZone=").append(TimeZone.getDefault().id.urlEncode())
                append("&isPage=0&pageNo=1&pageSize=100")
            },
            authToken = authToken,
        )
        return decodeHistory(root, "snapshot detail", alignToLocalClock = false)
    }

    fun fetchSnapshotTimeBucketHistory(
        context: Context,
        authToken: String,
        snapshotId: String,
        startTimeMs: Long,
        endTimeMs: Long,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudHistoryResult {
        if (snapshotId.isBlank()) return MQCloudHistoryResult()
        val start = startTimeMs.coerceAtLeast(1L)
        val end = endTimeMs.coerceAtLeast(start)
        val deduped = LinkedHashMap<Long, MQBootstrapHistoryPoint>()
        var cursor = start
        var failure = MQBootstrapFailure.NONE
        var message: String? = null

        while (cursor <= end) {
            val chunkEnd = minOf(end, cursor + HISTORY_BUCKET_CHUNK_MS - 1L)
            val chunk = fetchSnapshotTimeBucketChunk(
                authToken = authToken,
                snapshotId = snapshotId,
                startTimeMs = cursor,
                endTimeMs = chunkEnd,
                endpoints = endpoints,
            )
            if (chunk.failure == MQBootstrapFailure.AUTH_EXPIRED) {
                return chunk.copy(history = deduped.values.sortedBy { it.timestampMs })
            }
            if (chunk.failure != MQBootstrapFailure.NONE) {
                failure = chunk.failure
                message = chunk.message
                if (deduped.isEmpty()) break
            }
            chunk.history.forEach { point ->
                deduped[point.timestampMs] = point
            }
            cursor = chunkEnd + 1L
        }

        val history = deduped.values
            .sortedWith(compareBy<MQBootstrapHistoryPoint> { it.timestampMs }.thenBy { it.packetIndex })
        if (history.isNotEmpty()) {
            Log.i(
                TAG,
                "MQ cloud timeBucket history: snapshot=$snapshotId points=${history.size} range=$start..$end",
            )
        }
        return MQCloudHistoryResult(
            history = history,
            failure = if (history.isNotEmpty()) MQBootstrapFailure.NONE else failure,
            message = message,
        )
    }

    private fun fetchSnapshotTimeBucketChunk(
        authToken: String,
        snapshotId: String,
        startTimeMs: Long,
        endTimeMs: Long,
        endpoints: MQVendorEndpoints,
    ): MQCloudHistoryResult {
        val root = postForm(
            url = endpoints.timeBucketUrl,
            form = buildString {
                append("snapshotId=").append(snapshotId.urlEncode())
                append("&startTime=").append(startTimeMs.toString().urlEncode())
                append("&endTime=").append(endTimeMs.toString().urlEncode())
                append("&timeZone=").append(TimeZone.getDefault().id.urlEncode())
            },
            authToken = authToken,
        )
        return decodeHistory(root, "timeBucket", alignToLocalClock = true)
    }

    internal fun decodeHistory(
        root: MQCloudPostResult,
        source: String,
        alignToLocalClock: Boolean = false,
    ): MQCloudHistoryResult {
        if (root.failure == MQBootstrapFailure.AUTH_EXPIRED) {
            return MQCloudHistoryResult(failure = root.failure, message = root.message)
        }
        val array = root.root?.optJSONArray("result") ?: return MQCloudHistoryResult(
            failure = root.failure.takeUnless { it == MQBootstrapFailure.NONE } ?: MQBootstrapFailure.SERVER,
            message = root.message ?: "MQ $source response has no history array",
        )
        val responseServerTimeMs = parseServerTimeMs(root.root.opt("timestamp"))
        val history = ArrayList<MQBootstrapHistoryPoint>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parseSnapshotHistoryPoint(
                item = item,
                responseServerTimeMs = responseServerTimeMs,
                alignToLocalClock = alignToLocalClock,
            )?.let(history::add)
        }
        val rejected = array.length() - history.size
        Log.i(TAG, "MQ $source history response: records=${array.length()} parsed=${history.size} rejected=$rejected firstMs=${history.minOfOrNull { it.timestampMs }} lastMs=${history.maxOfOrNull { it.timestampMs }}")
        return MQCloudHistoryResult(
            history = history.sortedWith(compareBy<MQBootstrapHistoryPoint> { it.timestampMs }.thenBy { it.packetIndex }),
            failure = when {
                history.isNotEmpty() -> MQBootstrapFailure.NONE
                rejected > 0 -> MQBootstrapFailure.SERVER
                else -> root.failure
            },
            message = if (rejected > 0 && history.isEmpty()) "MQ $source rejected all $rejected history records" else root.message,
        )
    }

    fun startWearSession(
        context: Context,
        authToken: String,
        bleId: String,
        mac: String,
        account: String,
        qrCode: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudSessionResult {
        val root = postForm(
            url = endpoints.dataRecordStartUrl,
            form = MQVendorIdentity.startForm(bleId, mac, account, qrCode),
            authToken = authToken,
        )
        val snapshotId = root.root?.optJSONObject("result")?.optStringOrNull("snapshotId")
        return MQCloudSessionResult(
            snapshotId = snapshotId,
            success = !snapshotId.isNullOrBlank(),
            failure = if (!snapshotId.isNullOrBlank()) MQBootstrapFailure.NONE else root.failure,
            message = root.message,
        )
    }

    fun continueWearSession(
        context: Context,
        authToken: String,
        snapshotId: String,
        bleId: String,
        qrCode: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult {
        val root = postForm(
            url = endpoints.dataRecordGoOnUrl,
            form = buildString {
                append("snapshotId=").append(snapshotId.urlEncode())
                append("&bleId=").append(bleId.urlEncode())
                append("&qrCode=").append(qrCode.urlEncode())
            },
            authToken = authToken,
        )
        return MQCloudActionResult(
            success = root.failure == MQBootstrapFailure.NONE,
            failure = root.failure,
            message = root.message,
            alreadyMonitoring = MQCloudRecovery.isAlreadyMonitoring(
                root.root?.optInt("code"), root.root?.optBoolean("success") == true, root.message,
            ),
        )
    }

    fun endWearSession(
        context: Context,
        authToken: String,
        snapshotId: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.dataRecordEndUrl,
            form = "snapshotId=${snapshotId.urlEncode()}",
            authToken = authToken,
            successMessage = "MQ end-wear session submitted",
        )

    fun uploadCalibrationEvent(
        context: Context,
        authToken: String,
        snapshotId: String,
        packetIndex: Int,
        bagTimeMs: Long,
        bgValueMmol: Double,
        referenceK: Double,
        referenceB: Double,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.dataRecordEventUrl,
            form = buildString {
                append("bagIndex=").append(packetIndex.toString().urlEncode())
                append("&bagTime=").append(bagTimeMs.toString().urlEncode())
                append("&eventType=1")
                append("&snapshotId=").append(snapshotId.urlEncode())
                append("&bgValue=").append(bgValueMmol.toString().urlEncode())
                append("&referenceB=").append(referenceB.toString().urlEncode())
                append("&referenceK=").append(referenceK.toString().urlEncode())
            },
            authToken = authToken,
            successMessage = "MQ calibration event uploaded",
        )

    fun uploadCalibrationTime(
        context: Context,
        authToken: String,
        snapshotId: String,
        bagTimeMs: Long,
        packetIndex: Int,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.dataRecordCalibrationTimeUrl,
            form = buildString {
                append("snapshotId=").append(snapshotId.urlEncode())
                append("&time=").append(bagTimeMs.toString().urlEncode())
                append("&bagIndex=").append(packetIndex.toString().urlEncode())
            },
            authToken = authToken,
            successMessage = "MQ calibration time uploaded",
        )

    fun reportData(
        context: Context,
        authToken: String,
        snapshotId: String,
        calculateDataHex: List<String>,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult {
        val payload = JSONObject().apply {
            put("snapshopId", snapshotId)
            put(
                "dataInfoList",
                JSONArray().apply {
                    calculateDataHex
                        .filter { it.isNotBlank() }
                        .forEach { hex ->
                            put(JSONObject().apply { put("calculateData", hex) })
                        }
                },
            )
        }
        val root = postJson(
            url = endpoints.dataRecordReportUrl,
            body = payload.toString(),
            authToken = authToken,
        )
        return MQCloudActionResult(
            success = root.failure == MQBootstrapFailure.NONE,
            failure = root.failure,
            message = root.message,
        )
    }

    internal fun postForm(
        url: String,
        form: String,
        authToken: String?,
    ): MQCloudPostResult {
        var connection: HttpURLConnection? = null
        return try {
            val bytes = form.toByteArray(Charsets.UTF_8)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = MQConstants.VENDOR_TIMEOUT_MS
                readTimeout = MQConstants.VENDOR_TIMEOUT_MS
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Content-Length", bytes.size.toString())
                authToken?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Access-Token", it)
                }
            }
            connection.outputStream.use { it.write(bytes) }
            parseResponse(connection, url)
        } catch (t: Throwable) {
            Log.stack(TAG, "MQ cloud POST $url", t)
            MQCloudPostResult(
                failure = MQBootstrapFailure.NETWORK,
                message = t.message,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    internal fun getJson(
        url: String,
        authToken: String?,
    ): MQCloudPostResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = MQConstants.VENDOR_TIMEOUT_MS
                readTimeout = MQConstants.VENDOR_TIMEOUT_MS
                doInput = true
                authToken?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Access-Token", it)
                }
            }
            parseResponse(connection, url)
        } catch (t: Throwable) {
            Log.stack(TAG, "MQ cloud GET $url", t)
            MQCloudPostResult(
                failure = MQBootstrapFailure.NETWORK,
                message = t.message,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun postJson(
        url: String,
        body: String,
        authToken: String?,
    ): MQCloudPostResult {
        var connection: HttpURLConnection? = null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = MQConstants.VENDOR_TIMEOUT_MS
                readTimeout = MQConstants.VENDOR_TIMEOUT_MS
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                setRequestProperty("Content-Length", bytes.size.toString())
                authToken?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Access-Token", it)
                }
            }
            connection.outputStream.use { it.write(bytes) }
            parseResponse(connection, url)
        } catch (t: Throwable) {
            Log.stack(TAG, "MQ cloud POST(JSON) $url", t)
            MQCloudPostResult(
                failure = MQBootstrapFailure.NETWORK,
                message = t.message,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun postAction(
        url: String,
        form: String,
        authToken: String?,
        successMessage: String,
    ): MQCloudActionResult {
        val root = postForm(url = url, form = form, authToken = authToken)
        if (root.failure == MQBootstrapFailure.NONE) {
            Log.i(TAG, successMessage)
        }
        return MQCloudActionResult(
            success = root.failure == MQBootstrapFailure.NONE,
            failure = root.failure,
            message = root.message,
        )
    }

    private fun parseResponse(
        connection: HttpURLConnection,
        url: String,
    ): MQCloudPostResult {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val body = stream?.readUtf8Body().orEmpty()
        if (body.isBlank()) {
            Log.w(TAG, "MQ cloud returned empty body from $url (http=${connection.responseCode})")
            return MQCloudPostResult(
                failure = MQBootstrapFailure.SERVER,
                message = "empty body",
            )
        }
        val root = JSONObject(body)
        val code = root.optString("code").toIntOrNull()
        val message = root.optStringOrNull("message")
        if (connection.responseCode in 200..299 && code == 200) {
            return MQCloudPostResult(root = root, message = message)
        }
        val failure = classifyFailure(connection.responseCode, message)
        Log.w(TAG, "MQ cloud rejected by $url: appCode=$code http=${connection.responseCode} body=$body")
        return MQCloudPostResult(
            root = root,
            failure = failure,
            message = message ?: "http=${connection.responseCode}",
        )
    }

    private fun buildAgentInfo(context: Context): AgentInfo {
        val versionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        val agent = "${Build.BRAND}_${Build.MODEL}_${Build.VERSION.RELEASE}_$versionCode"
        val imei = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.take(15)
            ?.takeIf { it.isNotBlank() }
            ?: "899999999999999"
        return AgentInfo(agent = agent, imei = imei)
    }

    private fun classifyFailure(httpCode: Int, message: String?): MQBootstrapFailure {
        val normalized = message.orEmpty()
        if (httpCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
            httpCode == HttpURLConnection.HTTP_FORBIDDEN ||
            normalized.contains("Token", ignoreCase = true) ||
            normalized.contains("重新登录")
        ) {
            return MQBootstrapFailure.AUTH_EXPIRED
        }
        return MQBootstrapFailure.SERVER
    }

    private fun parseSnapshotHistoryPoint(
        item: JSONObject,
        responseServerTimeMs: Long? = null,
        alignToLocalClock: Boolean = false,
    ): MQBootstrapHistoryPoint? {
        val hex = item.optStringOrNull("cd")
            ?.uppercase(Locale.US)
            ?.takeIf { value -> value.all { it in '0'..'9' || it in 'A'..'F' } }
            ?: return null
        if (hex.length < 20 || !hex.startsWith("40")) return null
        val packetIndex = parseLeU16(hex, 1)
        val glucoseTimes10Mmol = parseLeU16(hex, 8)
        val rawTimestampMs = parseServerTimeMs(item.opt("rd")) ?: return null
        val timestampMs = if (alignToLocalClock && responseServerTimeMs != null) {
            val adjusted = rawTimestampMs + (System.currentTimeMillis() - responseServerTimeMs)
            if (adjusted <= System.currentTimeMillis() + HISTORY_FUTURE_GRACE_MS) adjusted else rawTimestampMs
        } else {
            rawTimestampMs
        }
        if (packetIndex < 0 || glucoseTimes10Mmol <= 0) return null
        return MQBootstrapHistoryPoint(
            timestampMs = timestampMs,
            packetIndex = packetIndex,
            glucoseMgdl = (glucoseTimes10Mmol / 10.0 * MQConstants.MMOL_TO_MGDL).toFloat(),
        )
    }

    private fun parseLeU16(hex: String, byteOffset: Int): Int {
        val start = byteOffset * 2
        if (start + 4 > hex.length) return -1
        val lo = hex.substring(start, start + 2).toIntOrNull(16) ?: return -1
        val hi = hex.substring(start + 2, start + 4).toIntOrNull(16) ?: return -1
        return (hi shl 8) or lo
    }

    private fun parseServerTimeMs(value: Any?): Long? = when (value) {
        null -> null
        JSONObject.NULL -> null
        is Number -> normalizeServerEpoch(value.toLong())
        else -> parseServerTimeMs(value.toString())
    }

    private fun parseServerTimeMs(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        text.toLongOrNull()?.let { return normalizeServerEpoch(it) }
        val patterns = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        )
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = if (pattern.contains("X")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
            }
            val parsed = runCatching { format.parse(text) }.getOrNull() ?: continue
            return parsed.time
        }
        return null
    }

    private fun normalizeServerEpoch(value: Long): Long? = when {
        value <= 0L -> null
        value >= 100_000_000_000L -> value
        value >= 1_000_000_000L -> value * 1000L
        else -> null
    }

    private fun InputStream.readUtf8Body(): String =
        BufferedReader(InputStreamReader(this)).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
        }

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name).takeUnless { it.isBlank() || it == "null" }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "utf-8")
}
