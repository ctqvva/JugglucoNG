package tk.glucodata.update

import android.os.Build
import tk.glucodata.BuildConfig
import tk.glucodata.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches release metadata from whatever source the user has configured — this project's GitHub
 * releases by default, or a fork, mirror or self-hosted manifest.
 *
 * There is no host allowlist, on purpose. The source is the user's explicit choice, and the
 * check that actually protects them is elsewhere: an APK must carry this install's signing
 * certificate to replace it (see [ApkVerifier]), which no third party can forge. What is still
 * enforced here is worth keeping though:
 *  - HTTPS only, including after redirects;
 *  - for a GitHub source, download URLs come from the API's asset listing rather than from a
 *    field inside `update-manifest.json`, so a tampered manifest cannot redirect the download;
 *  - response bodies are size-capped, so a hostile or broken endpoint cannot exhaust memory.
 */
object UpdateSourceClient {

    private const val LOG_TAG = "AppUpdate"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    private const val MAX_RELEASES = 15
    private const val MAX_METADATA_BYTES = 512 * 1024

    val userAgent: String = "JugglucoNG/${BuildConfig.BASE_VERSION_NAME} (Android)"

    /**
     * Looks for something newer than the running build at [source] (any https URL).
     *
     * Pre-releases are skipped. The project publishes everything as a full release, so a channel
     * setting would have been a picker with one real option; if that changes, filter here.
     */
    fun check(source: String): UpdateCheckResult {
        return when (val resolved = UpdateSource.resolve(source)) {
            null -> UpdateCheckResult.Failed(UpdateError.PARSE)
            is UpdateSource.Resolved.GithubReleases -> checkGithub(resolved.apiUrl)
            is UpdateSource.Resolved.Document -> checkDocument(resolved.url)
        }
    }

    /** A source that is not a releases API: one document, shape decides how it is read. */
    private fun checkDocument(url: String): UpdateCheckResult {
        val body = when (val response = getText(url, accept = "application/json")) {
            is TextResponse.Ok -> response.body
            is TextResponse.Error -> return UpdateCheckResult.Failed(response.error)
        }
        val trimmed = body.trimStart()
        // A releases array can be served from anywhere — a mirror, a proxy, a cached copy.
        if (trimmed.startsWith("[")) return releasesResult(body, assetUrlsFromApi = true)

        val manifest = GithubReleaseParser.parseManifest(body)
            ?: return UpdateCheckResult.Failed(UpdateError.PARSE)
        val update = GithubReleaseParser.fromManifest(
            manifest = manifest,
            manifestUrl = url,
            applicationId = BuildConfig.APPLICATION_ID,
            deviceSdk = Build.VERSION.SDK_INT
        ) ?: return UpdateCheckResult.Failed(UpdateError.NO_ARTIFACT)
        return verdictFor(update)
    }

    private fun checkGithub(apiUrl: String): UpdateCheckResult {
        val separator = if (apiUrl.contains('?')) "&" else "?"
        val body = when (val response = getText(
            url = "$apiUrl${separator}per_page=$MAX_RELEASES",
            accept = "application/vnd.github+json"
        )) {
            is TextResponse.Ok -> response.body
            is TextResponse.Error -> return UpdateCheckResult.Failed(response.error)
        }
        return releasesResult(body, assetUrlsFromApi = true)
    }

    private fun releasesResult(body: String, assetUrlsFromApi: Boolean): UpdateCheckResult {
        val releases = runCatching { GithubReleaseParser.parseReleases(body) }.getOrNull()
            ?: return UpdateCheckResult.Failed(UpdateError.PARSE)

        val candidates = releases.filterNot { it.prerelease }
        if (candidates.isEmpty()) return UpdateCheckResult.UpToDate

        // Only the newest release matters. Walking further back would mean offering an older
        // build than the newest published one, which is never what the user wants.
        for (release in candidates) {
            val manifest = release.asset(GithubReleaseParser.MANIFEST_ASSET)
                ?.let { fetchManifest(it.downloadUrl) }
            val update = GithubReleaseParser.toAvailableUpdate(
                release = release,
                manifest = manifest,
                applicationId = BuildConfig.APPLICATION_ID,
                deviceSdk = Build.VERSION.SDK_INT
            ) ?: continue

            if (!isDownloadableUrl(update.artifact.downloadUrl)) {
                Log.w(LOG_TAG, "rejecting release asset with a non-https URL")
                return UpdateCheckResult.Failed(UpdateError.NO_ARTIFACT)
            }
            return verdictFor(update)
        }
        return UpdateCheckResult.Failed(UpdateError.NO_ARTIFACT)
    }

    private fun verdictFor(update: AvailableUpdate): UpdateCheckResult =
        if (GithubReleaseParser.isNewerThanInstalled(
                update = update,
                installedVersionCode = BuildConfig.VERSION_CODE,
                installedVersionName = BuildConfig.BASE_VERSION_NAME
            )
        ) {
            UpdateCheckResult.Available(update)
        } else {
            UpdateCheckResult.UpToDate
        }

    private fun fetchManifest(url: String): GithubReleaseParser.UpdateManifest? {
        if (!isDownloadableUrl(url)) return null
        val response = getText(url, accept = "application/json")
        if (response !is TextResponse.Ok) return null
        return GithubReleaseParser.parseManifest(response.body)
    }

    /** The only transport rule left: it has to be HTTPS. */
    fun isDownloadableUrl(url: String): Boolean =
        runCatching { URL(url).protocol.equals("https", ignoreCase = true) }.getOrDefault(false)

    private sealed interface TextResponse {
        data class Ok(val body: String) : TextResponse
        data class Error(val error: UpdateError) : TextResponse
    }

    private fun getText(url: String, accept: String): TextResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    TextResponse.Ok(connection.inputStream.readCapped(MAX_METADATA_BYTES))
                }
                // GitHub answers an exhausted anonymous quota with 403/429 and a zeroed
                // X-RateLimit-Remaining. Worth telling apart from a real outage: the user just
                // has to wait, and a retry loop would make it worse.
                (code == 403 || code == 429) && connection.getHeaderField("X-RateLimit-Remaining") == "0" -> {
                    TextResponse.Error(UpdateError.RATE_LIMITED)
                }
                else -> {
                    Log.w(LOG_TAG, "update metadata request failed, HTTP $code")
                    TextResponse.Error(UpdateError.NETWORK)
                }
            }
        } catch (_: Exception) {
            TextResponse.Error(UpdateError.NETWORK)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Opens an HTTPS connection with our headers and timeouts. Plain HTTP is refused. */
    internal fun openConnection(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "refusing non-https update URL" }
        val connection = parsed.openConnection() as HttpURLConnection
        require(connection is HttpsURLConnection) { "refusing non-TLS update connection" }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        return connection
    }

    private fun InputStream.readCapped(maxBytes: Int): String = use { input ->
        val buffer = ByteArray(16 * 1024)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (bytes.size() + read > maxBytes) throw IOException("update metadata too large")
            bytes.write(buffer, 0, read)
        }
        bytes.toString(Charsets.UTF_8.name())
    }
}
