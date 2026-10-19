package tk.glucodata.drivers.mq

import android.content.Context
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import tk.glucodata.Log

data class MQBootstrapConfig(
    val protocolType: Int? = null,
    val deviation: Int? = null,
    val transmitter10: Int? = null,
    val sensitivity: Float? = null,
    val algorithmVersion: Int? = null,
    val packages: Int? = null,
    val multiplier: Float? = null,
    val snapshotId: String? = null,
    val sensorStartAtMs: Long? = null,
    val restoredPacketIndex: Int? = null,
    val restoredLastProcessed: Float? = null,
    val restoredKValue: Float? = null,
    val restoredBValue: Float? = null,
)

data class MQAuthCredentials(
    val account: String,
    val password: String,
)

enum class MQBootstrapFailure {
    NONE,
    AUTH_EXPIRED,
    NETWORK,
    SERVER,
}

data class MQBootstrapFetchResult(
    val config: MQBootstrapConfig? = null,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
    val refreshedToken: String? = null,
    val history: List<MQBootstrapHistoryPoint> = emptyList(),
)

data class MQBootstrapHistoryPoint(
    val timestampMs: Long,
    val packetIndex: Int,
    val glucoseMgdl: Float,
)

object MQBootstrapClient {
    private const val TAG = MQConstants.TAG

    private data class SnapshotRestore(
        val packetIndex: Int,
        val sampleCurrent: Int,
        val reviseCurrent2: Double,
        val glucoseTimes10Mmol: Int,
        val kValue: Double,
        val bValue: Double,
        val recordedAtMs: Long,
    )

    fun fetchBestEffort(
        context: Context,
        bleId: String?,
        qrCode: String?,
        authToken: String? = null,
        credentials: MQAuthCredentials? = null,
        allowContinueWearRestore: Boolean = true,
    ): MQBootstrapFetchResult {
        val endpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context))
        val account = credentials?.account?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val currentToken = authToken?.trim().orEmpty()
        if (currentToken.isEmpty() && credentials != null) {
            val login = MQCloudClient.signInWithPassword(context, credentials, endpoints)
            val freshToken = login.token?.trim().orEmpty()
            if (freshToken.isNotEmpty()) {
                val seeded = fetchBestEffortOnce(
                    endpoints = endpoints,
                    bleId = bleId,
                    qrCode = qrCode,
                    authToken = freshToken,
                    account = account,
                    allowContinueWearRestore = allowContinueWearRestore,
                )
                return seeded.copy(refreshedToken = freshToken)
            }
        }
        val initial = fetchBestEffortOnce(
            endpoints = endpoints,
            bleId = bleId,
            qrCode = qrCode,
            authToken = currentToken,
            account = account,
            allowContinueWearRestore = allowContinueWearRestore,
        )
        if (initial.failure != MQBootstrapFailure.AUTH_EXPIRED || credentials == null) {
            return initial
        }
        val login = MQCloudClient.signInWithPassword(context, credentials, endpoints)
        val freshToken = login.token?.trim().orEmpty()
        if (freshToken.isEmpty()) {
            return initial.copy(message = login.message ?: initial.message)
        }
        val retried = fetchBestEffortOnce(
            endpoints = endpoints,
            bleId = bleId,
            qrCode = qrCode,
            authToken = freshToken,
            account = account,
            allowContinueWearRestore = allowContinueWearRestore,
        )
        return retried.copy(refreshedToken = freshToken)
    }

    internal fun fetchBestEffortOnce(
        endpoints: MQVendorEndpoints,
        bleId: String?,
        qrCode: String?,
        authToken: String? = null,
        account: String? = null,
        allowContinueWearRestore: Boolean = true,
        bleLookup: (String) -> MQBootstrapFetchResult = { fetchBleConfig(endpoints, it, authToken) },
        qrLookup: (String) -> MQBootstrapFetchResult = { fetchQrConfig(endpoints, it, authToken) },
        sessionLookup: () -> MQBootstrapFetchResult = {
            fetchContinueWearConfig(endpoints, requireNotNull(account), requireNotNull(authToken), bleId)
        },
    ): MQBootstrapFetchResult {
        var merged: MQBootstrapConfig? = null
        var history = emptyList<MQBootstrapHistoryPoint>()
        var failure = MQBootstrapFailure.NONE
        var message: String? = null

        MQVendorIdentity.bleId(bleId)?.let { id ->
            val result = bleLookup(id)
            merged = merged.merge(result.config)
            failure = mergeFailure(failure, result.failure)
            message = mergeMessage(message, result.failure, result.message)
        }

        qrCode?.trim()?.takeIf { it.isNotEmpty() }?.let { code ->
            val result = qrLookup(code)
            val qrConfig = result.config?.let {
                it.copy(sensitivity = MQBootstrapSeed.normalizeSensitivity(it.sensitivity, merged?.transmitter10))
            }
            if (result.config?.sensitivity != null && qrConfig?.sensitivity == null) {
                Log.w(TAG, "MQ QR sensitivity withheld: invalid sensitivity or unavailable transmitter scale")
            }
            Log.i(TAG, "MQ QR seed: raw=${result.config?.sensitivity} transmitter10=${merged?.transmitter10} normalized=${qrConfig?.sensitivity}")
            merged = merged.merge(qrConfig)
            failure = mergeFailure(failure, result.failure)
            message = mergeMessage(message, result.failure, result.message)
        }

        if (allowContinueWearRestore && !authToken.isNullOrBlank() && !account.isNullOrBlank()) {
            val result = sessionLookup()
            merged = merged.merge(result.config)
            history = result.history
            failure = mergeFailure(failure, result.failure)
            message = mergeMessage(message, result.failure, result.message)
        }

        return MQBootstrapFetchResult(
            config = merged,
            failure = failure,
            message = message,
            history = history,
        )
    }

    private fun fetchBleConfig(
        endpoints: MQVendorEndpoints,
        bleId: String,
        authToken: String? = null,
    ): MQBootstrapFetchResult {
        val root = MQCloudClient.postForm(
            url = endpoints.findByBleIdUrl,
            form = "bleId=${bleId.urlEncode()}",
            authToken = authToken,
        )
        if (root.failure != MQBootstrapFailure.NONE && root.root == null) {
            return MQBootstrapFetchResult(failure = root.failure, message = root.message)
        }
        val result = root.root?.optJSONObject("result") ?: return MQBootstrapFetchResult(
            failure = root.failure,
            message = root.message,
        )
        return MQBootstrapFetchResult(
            config = MQBootstrapConfig(
                protocolType = result.optStringOrNull("type")?.toIntOrNull(),
                deviation = result.optStringOrNull("deviation")?.toIntOrNull(),
                transmitter10 = result.optStringOrNull("transmitter10")?.toIntOrNull(),
            ),
            failure = if (result.length() > 0) MQBootstrapFailure.NONE else root.failure,
            message = root.message,
        )
    }

    private fun fetchQrConfig(
        endpoints: MQVendorEndpoints,
        qrCode: String,
        authToken: String? = null,
    ): MQBootstrapFetchResult {
        val root = MQCloudClient.postForm(
            url = endpoints.qrCodeEffectiveUrl,
            form = "qrCode=${qrCode.urlEncode()}",
            authToken = authToken,
        )
        val result = root.root?.optJSONObject("result")
        val config = result?.let {
            MQBootstrapConfig(
                sensitivity = it.optStringOrNull("sensitivity")?.toFloatOrNull(),
                algorithmVersion = it.optStringOrNull("algorithmVersion")?.toIntOrNull()
                    ?: MQConstants.ALGO_DEFAULT_VERSION,
                packages = it.optStringOrNull("packages")?.toIntOrNull()
                    ?: MQConstants.ALGO_DEFAULT_PACKAGES,
                multiplier = it.optStringOrNull("multiplier")?.toFloatOrNull()
                    ?: MQConstants.ALGO_DEFAULT_MULTIPLIER.toFloat(),
            )
        }
        if (config?.sensitivity != null && config.sensitivity > 0f) {
            if (root.failure != MQBootstrapFailure.NONE) {
                Log.w(
                    TAG,
                    "MQ QR bootstrap returned usable seed despite appCode failure: sensitivity=${config.sensitivity} algo=${config.algorithmVersion} packages=${config.packages} multiplier=${config.multiplier} message=${root.message}",
                )
            }
            return MQBootstrapFetchResult(
                config = config,
                message = root.message,
                history = emptyList(),
            )
        }
        if (root.failure != MQBootstrapFailure.NONE) {
            return MQBootstrapFetchResult(failure = root.failure, message = root.message)
        }
        return MQBootstrapFetchResult(config = config, history = emptyList())
    }

    private fun fetchContinueWearConfig(
        endpoints: MQVendorEndpoints,
        account: String,
        authToken: String,
        requestedBleId: String?,
    ): MQBootstrapFetchResult {
        val root = MQCloudClient.postForm(
            url = endpoints.queryNewestUrl,
            form = "account=${account.urlEncode()}",
            authToken = authToken,
        )
        val result = root.root?.optJSONObject("result")
        if (result == null) {
            return MQBootstrapFetchResult(failure = root.failure, message = root.message)
        }

        val startAtMs = parseServerTimeMs(result.opt("createTime"))
        if (!MQSessionRestorePolicy.canRestore(
                requestedBleId, result.optStringOrNull("bleId"), startAtMs, System.currentTimeMillis(),
            )
        ) {
            Log.w(TAG, "Ignoring MQ cloud session: requested=$requestedBleId actual=${result.optStringOrNull("bleId")} start=$startAtMs; transmitter mismatch or invalid/expired start time")
            return MQBootstrapFetchResult()
        }

        val transmitter10 = result.optStringOrNull("transmitter10")?.toIntOrNull()
        val sensitivity = MQBootstrapSeed.normalizeSensitivity(
            result.optStringOrNull("sensitivity")?.toFloatOrNull(), transmitter10,
        )
        val baseConfig = MQBootstrapConfig(
            protocolType = result.optStringOrNull("type")?.toIntOrNull(),
            deviation = result.optStringOrNull("deviation")?.toIntOrNull(),
            transmitter10 = transmitter10,
            sensitivity = sensitivity,
            algorithmVersion = result.optStringOrNull("algorithmVersion")?.toIntOrNull()
                ?: MQConstants.ALGO_DEFAULT_VERSION,
            packages = result.optStringOrNull("packages")?.toIntOrNull()
                ?: MQConstants.ALGO_DEFAULT_PACKAGES,
            multiplier = result.optStringOrNull("multiplier")?.toFloatOrNull()
                ?: MQConstants.ALGO_DEFAULT_MULTIPLIER.toFloat(),
            snapshotId = result.optStringOrNull("id"),
            sensorStartAtMs = startAtMs,
        )

        val historyResult = baseConfig.snapshotId?.let { snapshotId ->
            fetchSnapshotRestore(
                endpoints = endpoints,
                snapshotId = snapshotId,
                authToken = authToken,
                algorithmVersion = baseConfig.algorithmVersion ?: MQConstants.ALGO_DEFAULT_VERSION,
                packages = baseConfig.packages ?: MQConstants.ALGO_DEFAULT_PACKAGES,
                multiplier = baseConfig.multiplier ?: MQConstants.ALGO_DEFAULT_MULTIPLIER.toFloat(),
            )
        } ?: MQBootstrapFetchResult()

        val mergedConfig = baseConfig.merge(historyResult.config)
        if (historyResult.config?.restoredKValue != null) {
            Log.i(
                TAG,
                "MQ snapshot restore: snapshot=${baseConfig.snapshotId} packet=${historyResult.config.restoredPacketIndex} k=${historyResult.config.restoredKValue} b=${historyResult.config.restoredBValue} revise=${historyResult.config.restoredLastProcessed}",
            )
        }

        val usable = mergedConfig?.snapshotId != null || mergedConfig?.sensitivity != null
        if (usable && root.failure != MQBootstrapFailure.NONE) {
            Log.w(
                TAG,
                "MQ continue-wear bootstrap returned usable state despite appCode failure: snapshot=${mergedConfig?.snapshotId} sensitivity=${mergedConfig?.sensitivity} message=${root.message}",
            )
        }

        return MQBootstrapFetchResult(
            config = mergedConfig,
            failure = if (usable) historyResult.failure else mergeFailure(root.failure, historyResult.failure),
            message = historyResult.message ?: root.message,
            history = historyResult.history,
        )
    }

    private fun fetchSnapshotRestore(
        endpoints: MQVendorEndpoints,
        snapshotId: String,
        authToken: String,
        algorithmVersion: Int,
        packages: Int,
        multiplier: Float,
    ): MQBootstrapFetchResult {
        val form = buildString {
            append("snapshotId=").append(snapshotId.urlEncode())
            append("&timeZone=").append(TimeZone.getDefault().id.urlEncode())
            append("&isPage=0&pageNo=1&pageSize=100")
        }
        val root = MQCloudClient.postForm(
            url = endpoints.viewAllSnapshotDetailUrl,
            form = form,
            authToken = authToken,
        )
        val result = root.root?.optJSONArray("result")
        if (result == null) {
            return MQBootstrapFetchResult(failure = root.failure, message = root.message)
        }

        var best: SnapshotRestore? = null
        val history = ArrayList<SnapshotRestore>(result.length())
        for (i in 0 until result.length()) {
            val item = result.optJSONObject(i) ?: continue
            val restore = parseSnapshotRestore(
                item = item,
                algorithmVersion = algorithmVersion,
                packages = packages.toDouble(),
                multiplier = multiplier.toDouble(),
            ) ?: continue
            history.add(restore)
            if (best == null ||
                restore.recordedAtMs > best.recordedAtMs ||
                (restore.recordedAtMs == best.recordedAtMs && restore.packetIndex > best.packetIndex)
            ) {
                best = restore
            }
        }

        if (best == null) {
            return MQBootstrapFetchResult(failure = root.failure, message = root.message)
        }

        return MQBootstrapFetchResult(
            config = MQBootstrapConfig(
                restoredPacketIndex = best.packetIndex,
                restoredLastProcessed = best.reviseCurrent2.toFloat(),
                restoredKValue = best.kValue.toFloat(),
                restoredBValue = best.bValue.toFloat(),
            ),
            failure = MQBootstrapFailure.NONE,
            message = root.message,
            history = history
                .asSequence()
                .filter { it.recordedAtMs > 0L }
                .sortedWith(compareBy<SnapshotRestore> { it.recordedAtMs }.thenBy { it.packetIndex })
                .map {
                    MQBootstrapHistoryPoint(
                        timestampMs = it.recordedAtMs,
                        packetIndex = it.packetIndex,
                        glucoseMgdl = (it.glucoseTimes10Mmol / 10.0 * MQConstants.MMOL_TO_MGDL).toFloat(),
                    )
                }
                .toList(),
        )
    }

    private fun parseSnapshotRestore(
        item: JSONObject,
        algorithmVersion: Int,
        packages: Double,
        multiplier: Double,
    ): SnapshotRestore? {
        val hex = item.optStringOrNull("cd")
            ?.uppercase(Locale.US)
            ?.filter { it in '0'..'9' || it in 'A'..'F' }
            ?: return null
        if (hex.length < 20) return null

        val packetIndex = parseLeU16(hex, 1)
        val sampleCurrent = parseLeU16(hex, 3)
        val reviseCurrent2 = parseLeU16(hex, 6).toDouble()
        val glucoseTimes10Mmol = parseLeU16(hex, 8)
        if (packetIndex < 0 || sampleCurrent <= 0 || reviseCurrent2 <= 0.0 || glucoseTimes10Mmol <= 0) {
            return null
        }

        val glucoseMmol = glucoseTimes10Mmol / 10.0
        val denominator = glucoseMmol - 0.05
        if (denominator <= 0.0) return null

        val adjustedCurrent = MQAlgorithm.adjustSampleCurrent(
            algorithmVersion = algorithmVersion,
            packetIndex = packetIndex.toDouble(),
            sampleCurrent = sampleCurrent.toDouble(),
            packages = packages,
            multiplier = multiplier,
        )
        val kValue = reviseCurrent2 / denominator
        if (!kValue.isFinite() || kValue <= MQConstants.ALGO_MIN_VALID_K) {
            return null
        }
        val bValue = (adjustedCurrent - reviseCurrent2).coerceAtLeast(0.0)

        return SnapshotRestore(
            packetIndex = packetIndex,
            sampleCurrent = sampleCurrent,
            reviseCurrent2 = reviseCurrent2,
            glucoseTimes10Mmol = glucoseTimes10Mmol,
            kValue = kValue,
            bValue = bValue,
            recordedAtMs = parseServerTimeMs(item.opt("rd")) ?: 0L,
        )
    }

    private fun MQBootstrapConfig?.merge(other: MQBootstrapConfig?): MQBootstrapConfig? {
        if (this == null) return other
        if (other == null) return this
        return MQBootstrapConfig(
            protocolType = other.protocolType ?: protocolType,
            deviation = other.deviation ?: deviation,
            transmitter10 = other.transmitter10 ?: transmitter10,
            sensitivity = other.sensitivity ?: sensitivity,
            algorithmVersion = other.algorithmVersion ?: algorithmVersion,
            packages = other.packages ?: packages,
            multiplier = other.multiplier ?: multiplier,
            snapshotId = other.snapshotId ?: snapshotId,
            sensorStartAtMs = other.sensorStartAtMs ?: sensorStartAtMs,
            restoredPacketIndex = other.restoredPacketIndex ?: restoredPacketIndex,
            restoredLastProcessed = other.restoredLastProcessed ?: restoredLastProcessed,
            restoredKValue = other.restoredKValue ?: restoredKValue,
            restoredBValue = other.restoredBValue ?: restoredBValue,
        )
    }

    private fun mergeFailure(
        current: MQBootstrapFailure,
        next: MQBootstrapFailure,
    ): MQBootstrapFailure = when {
        next == MQBootstrapFailure.NONE -> current
        next == MQBootstrapFailure.AUTH_EXPIRED -> next
        current == MQBootstrapFailure.NONE -> next
        else -> current
    }

    private fun mergeMessage(
        current: String?,
        nextFailure: MQBootstrapFailure,
        next: String?,
    ): String? = when {
        next.isNullOrBlank() -> current
        current.isNullOrBlank() -> next
        nextFailure != MQBootstrapFailure.NONE -> next
        else -> current
    }

    private fun parseLeU16(hex: String, byteOffset: Int): Int {
        val start = byteOffset * 2
        if (start + 4 > hex.length) return -1
        val lo = hex.substring(start, start + 2).toIntOrNull(16) ?: return -1
        val hi = hex.substring(start + 2, start + 4).toIntOrNull(16) ?: return -1
        return (hi shl 8) or lo
    }

    private fun parseServerTimeMs(value: Any?): Long? {
        return when (value) {
            null -> null
            JSONObject.NULL -> null
            is Number -> normalizeServerEpoch(value.toLong())
            else -> parseServerTimeMs(value.toString())
        }
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

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name).takeUnless { it.isBlank() || it == "null" }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "utf-8")
}
