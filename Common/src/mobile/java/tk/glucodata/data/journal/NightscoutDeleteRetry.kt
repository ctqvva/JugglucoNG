package tk.glucodata.data.journal

import org.json.JSONObject
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.NightscoutTokenGrant
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale

/** Persisted classification of the last failure for a pending Nightscout deletion. */
enum class DeleteFailureKind(val storageValue: Int) {
    NONE(0),
    TRANSIENT(1),
    AUTH(2),
    CONFIG(3);

    companion object {
        fun fromStorage(value: Int): DeleteFailureKind =
            entries.firstOrNull { it.storageValue == value } ?: NONE
    }
}

/** Diagnostic summary derived directly from Room's pending deletion rows for the active server. */
data class DeleteDiagnostics(
    val pendingCount: Int = 0,
    val authBlocked: Boolean = false,
    val configBlocked: Boolean = false,
    val nextRetryAt: Long = 0L,
    val lastErrorCode: Int = 0,
    val lastErrorMessage: String = "",
    val oldestDeletedAt: Long = 0L,
) {
    val hasUnresolved: Boolean get() = pendingCount > 0
}

/**
 * Pure retry, failure-classification, server-binding, and HTTP absence-verification rules for
 * Nightscout treatment deletions. All durable state lives in Room's `journal_pending_deletes`.
 */
internal object NightscoutDeleteRetry {
    const val TRANSIENT_BACKOFF_FIRST_MILLIS = 60_000L
    const val TRANSIENT_BACKOFF_MAX_MILLIS = 4L * 60 * 60_000L

    /**
     * Auth and configuration refusals back off much more slowly than transient outages so they
     * never spin in a tight loop, while still recovering automatically if permissions or server
     * routes (e.g. Care Portal / reverse proxy) are fixed server-side without changing phone settings.
     */
    const val SLOW_BACKOFF_FIRST_MILLIS = 30L * 60_000L
    const val SLOW_BACKOFF_MAX_MILLIS = 6L * 60 * 60_000L

    /** Overflow-safe capped exponential backoff for [attempts] (1-indexed). */
    fun backoffMillis(attempts: Int, kind: DeleteFailureKind): Long {
        val (first, cap) = when (kind) {
            DeleteFailureKind.AUTH,
            DeleteFailureKind.CONFIG -> SLOW_BACKOFF_FIRST_MILLIS to SLOW_BACKOFF_MAX_MILLIS
            DeleteFailureKind.TRANSIENT,
            DeleteFailureKind.NONE -> TRANSIENT_BACKOFF_FIRST_MILLIS to TRANSIENT_BACKOFF_MAX_MILLIS
        }
        if (attempts <= 1) return first
        var delay = first
        var remaining = attempts - 1
        while (remaining > 0 && delay < cap) {
            delay = if (delay >= cap / 2L) cap else delay * 2L
            remaining--
        }
        return delay.coerceAtMost(cap)
    }

    fun maxBackoffMillis(kind: DeleteFailureKind): Long = when (kind) {
        DeleteFailureKind.AUTH,
        DeleteFailureKind.CONFIG -> SLOW_BACKOFF_MAX_MILLIS
        DeleteFailureKind.TRANSIENT,
        DeleteFailureKind.NONE -> TRANSIENT_BACKOFF_MAX_MILLIS
    }

    /**
     * Classifies a DELETE response code into confirmed-clear (`null`) or a failure kind.
     * Callers must run [isRouteMissingResponse] / [isUnambiguousDocumentAbsence] before treating
     * 404/410 as confirmed document absence.
     */
    fun failureKindForDeleteCode(code: Int): DeleteFailureKind? = when {
        code == HttpURLConnection.HTTP_OK ||
            code == HttpURLConnection.HTTP_NO_CONTENT ||
            code == HttpURLConnection.HTTP_NOT_FOUND ||
            code == HttpURLConnection.HTTP_GONE -> null
        code == HttpURLConnection.HTTP_UNAUTHORIZED ||
            code == HttpURLConnection.HTTP_FORBIDDEN ||
            code == HttpURLConnection.HTTP_PROXY_AUTH -> DeleteFailureKind.AUTH
        code == NightPost.ERROR_INVALID_URL ||
            code == NightPost.ERROR_CONFIG ||
            code == HttpURLConnection.HTTP_BAD_METHOD -> DeleteFailureKind.CONFIG
        code == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            code == 429 ||
            code == HttpURLConnection.HTTP_CONFLICT ||
            code == 425 ||
            code in 500..599 ||
            code < 0 -> DeleteFailureKind.TRANSIENT
        code in 400..499 -> DeleteFailureKind.CONFIG
        else -> DeleteFailureKind.TRANSIENT
    }

    /**
     * Classifies a lookup / verification failure code. A failed lookup can never prove document
     * absence, so 404/410 here become [DeleteFailureKind.CONFIG].
     */
    fun failureKindForLookupCode(code: Int): DeleteFailureKind = when {
        code == HttpURLConnection.HTTP_UNAUTHORIZED ||
            code == HttpURLConnection.HTTP_FORBIDDEN ||
            code == HttpURLConnection.HTTP_PROXY_AUTH -> DeleteFailureKind.AUTH
        code == NightPost.ERROR_INVALID_URL ||
            code == NightPost.ERROR_CONFIG ||
            code == HttpURLConnection.HTTP_NOT_FOUND ||
            code == HttpURLConnection.HTTP_GONE ||
            code == HttpURLConnection.HTTP_BAD_METHOD -> DeleteFailureKind.CONFIG
        code == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            code == 429 ||
            code in 500..599 ||
            code < 0 -> DeleteFailureKind.TRANSIENT
        code in 400..499 -> DeleteFailureKind.CONFIG
        else -> DeleteFailureKind.TRANSIENT
    }

    /**
     * Distinguishes endpoint-wide failures (server down/offline, auth refusal, invalid server
     * route/config) from document-specific failures (e.g. 409 Conflict or 400 Bad Request on one
     * treatment). An endpoint-wide failure holds back the entire server queue so an unavailable
     * server is probed once per backoff window rather than once per pending deletion.
     */
    fun isEndpointWideFailure(kind: DeleteFailureKind, code: Int): Boolean = when (kind) {
        DeleteFailureKind.NONE -> false
        DeleteFailureKind.AUTH -> true
        DeleteFailureKind.CONFIG -> code == NightPost.ERROR_INVALID_URL ||
            code == NightPost.ERROR_CONFIG ||
            code == HttpURLConnection.HTTP_BAD_METHOD ||
            code == HttpURLConnection.HTTP_NOT_FOUND ||
            code == HttpURLConnection.HTTP_GONE
        DeleteFailureKind.TRANSIENT -> code != HttpURLConnection.HTTP_CONFLICT && code != 425
    }

    /**
     * Detects 404/410 responses that indicate a missing route or disabled feature (Express HTML
     * 404, `Cannot DELETE`, or Nightscout v3 `Bad operation or collection`) rather than a missing
     * treatment document.
     */
    fun isRouteMissingResponse(code: Int, rawBody: String): Boolean {
        if (code != HttpURLConnection.HTTP_NOT_FOUND && code != HttpURLConnection.HTTP_GONE) return false
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("<")) return true
        val lower = trimmed.lowercase(Locale.US)
        if (lower.contains("cannot delete") || lower.contains("cannot get")) return true
        if (lower.contains("bad operation or collection")) return true
        return false
    }

    /**
     * Returns true only when a 404/410 response carries unambiguous document-level JSON evidence
     * (Nightscout v3 `{"status":404}` / `{"status":410}` without a route-error message). Generic
     * proxy JSON like `{"message":"Not Found"}` without `"status"` returns false so callers verify
     * absence with a follow-up GET before removing the tombstone.
     */
    fun isUnambiguousDocumentAbsence(code: Int, rawBody: String, useV3: Boolean): Boolean {
        if (code != HttpURLConnection.HTTP_NOT_FOUND && code != HttpURLConnection.HTTP_GONE) return false
        if (!useV3) return false
        val trimmed = rawBody.trim()
        if (!trimmed.startsWith("{") || isRouteMissingResponse(code, trimmed)) return false
        return runCatching {
            val json = JSONObject(trimmed)
            json.optInt("status", -1) == code
        }.getOrDefault(false)
    }

    /** True if [tomb] belongs to [currentServer] (or is an unpinned legacy pre-v35 row). */
    fun matchesServer(tomb: JournalPendingDeleteEntity, currentServer: String): Boolean =
        tomb.targetServer.isEmpty() || tomb.targetServer == currentServer

    /** Normalized server URL currently configured for treatment uploads, or empty if unset. */
    fun currentTargetServer(): String =
        NightscoutTokenGrant.normalizeServerUrlPreservingPathCase(
            runCatching { Natives.getnightuploadurl() }.getOrNull()
        )

    /**
     * Effective retry timestamp for [tomb], clamped against backward wall-clock jumps so a clock
     * reset cannot hold a tombstone longer than one max backoff window.
     */
    fun effectiveRetryAt(tomb: JournalPendingDeleteEntity, nowMillis: Long): Long {
        val retryAt = tomb.nextRetryAt
        if (retryAt <= nowMillis) return 0L
        val maxDelay = maxBackoffMillis(DeleteFailureKind.fromStorage(tomb.failureKind))
        return if (retryAt - nowMillis > maxDelay) nowMillis + maxDelay else retryAt
    }

    /**
     * Active endpoint-wide hold timestamp across all tombstones for [currentServer], or 0 if no
     * endpoint-wide hold is currently cooling off.
     */
    fun endpointHoldUntil(
        serverTombstones: List<JournalPendingDeleteEntity>,
        nowMillis: Long,
    ): Long {
        var maxUntil = 0L
        for (tomb in serverTombstones) {
            val kind = DeleteFailureKind.fromStorage(tomb.failureKind)
            if (!isEndpointWideFailure(kind, tomb.lastErrorCode)) continue
            val retryAt = effectiveRetryAt(tomb, nowMillis)
            if (retryAt > maxUntil) maxUntil = retryAt
        }
        return maxUntil
    }

    fun maxEndpointAttempts(serverTombstones: List<JournalPendingDeleteEntity>): Int {
        var maxAttempts = 0
        for (tomb in serverTombstones) {
            val kind = DeleteFailureKind.fromStorage(tomb.failureKind)
            if (isEndpointWideFailure(kind, tomb.lastErrorCode) && tomb.attempts > maxAttempts) {
                maxAttempts = tomb.attempts
            }
        }
        return maxAttempts
    }

    /** Stable fingerprint of URL (preserving base-path case), secret hash, and API version. */
    fun configKey(baseUrl: String?, rawSecret: String?, useV3: Boolean): String {
        val normalizedUrl = NightscoutTokenGrant.normalizeServerUrlPreservingPathCase(baseUrl)
        val secretHash = sha256Hex(rawSecret?.trim().orEmpty())
        return "${if (useV3) "v3" else "v1"}|$normalizedUrl|$secretHash"
    }

    /** Builds UI/status diagnostics directly from Room's pending deletions for [currentServer]. */
    fun summarize(
        allTombstones: List<JournalPendingDeleteEntity>,
        currentServer: String,
        nowMillis: Long,
    ): DeleteDiagnostics {
        val tombstones = allTombstones.filter { matchesServer(it, currentServer) }
        if (tombstones.isEmpty()) return DeleteDiagnostics()
        var authBlocked = false
        var configBlocked = false
        var earliestRetryAt = 0L
        var oldestDeletedAt = Long.MAX_VALUE
        var representative: JournalPendingDeleteEntity? = null
        val endpointUntil = endpointHoldUntil(tombstones, nowMillis)

        for (tomb in tombstones) {
            if (tomb.deletedAt in 1L until oldestDeletedAt) {
                oldestDeletedAt = tomb.deletedAt
            }
            val kind = DeleteFailureKind.fromStorage(tomb.failureKind)
            if (kind == DeleteFailureKind.AUTH) authBlocked = true
            if (kind == DeleteFailureKind.CONFIG) configBlocked = true
            val effective = maxOf(
                effectiveRetryAt(tomb, nowMillis),
                if (isEndpointWideFailure(kind, tomb.lastErrorCode) || kind == DeleteFailureKind.NONE) endpointUntil else 0L
            )
            if (effective > nowMillis && (earliestRetryAt == 0L || effective < earliestRetryAt)) {
                earliestRetryAt = effective
            }
            if (tomb.lastErrorCode != 0 &&
                (representative == null || tomb.nextRetryAt > representative.nextRetryAt)
            ) {
                representative = tomb
            }
        }
        return DeleteDiagnostics(
            pendingCount = tombstones.size,
            authBlocked = authBlocked,
            configBlocked = configBlocked,
            nextRetryAt = earliestRetryAt,
            lastErrorCode = representative?.lastErrorCode ?: 0,
            lastErrorMessage = representative?.lastErrorMessage.orEmpty(),
            oldestDeletedAt = if (oldestDeletedAt == Long.MAX_VALUE) 0L else oldestDeletedAt,
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        return out.toString()
    }
}
