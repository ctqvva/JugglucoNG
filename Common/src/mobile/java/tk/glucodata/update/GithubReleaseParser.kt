package tk.glucodata.update

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Turns GitHub Releases JSON into [AvailableUpdate]s. Pure parsing, no I/O, so the shape of
 * every field the updater trusts is unit tested.
 *
 * Two sources of truth, in order:
 *  1. an `update-manifest.json` asset attached to the release — authoritative version code,
 *     per-variant filenames and SHA-256 digests;
 *  2. the release itself — tag name for the version, asset names matched against the build's
 *     variant tokens.
 *
 * (2) exists because every release published so far predates the manifest. The updater has to
 * work against those too, otherwise the feature only starts working one release from now.
 */
object GithubReleaseParser {

    /** Schema version of `update-manifest.json` that this build understands. */
    const val MANIFEST_SCHEMA = 1

    /** Filename of the optional manifest asset attached to a release. */
    const val MANIFEST_ASSET = "update-manifest.json"

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    data class Release(
        val tagName: String,
        val title: String,
        val body: String,
        val prerelease: Boolean,
        val publishedAtMillis: Long,
        val assets: List<ReleaseAsset>
    ) {
        fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
    }

    data class ManifestArtifact(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String?,
        /**
         * Only used by a self-hosted manifest, which has no asset listing to take URLs from.
         * May be relative to the manifest's own location. Ignored for a GitHub source.
         */
        val url: String? = null
    )

    data class UpdateManifest(
        val versionName: String,
        val versionCode: Int,
        val minSdk: Int,
        val notes: String?,
        /** Keyed by applicationId, so a build only ever sees the artifact meant for it. */
        val artifacts: Map<String, ManifestArtifact>
    )

    /** Parses the `GET /repos/{owner}/{repo}/releases` array. Drafts are dropped. */
    fun parseReleases(json: String): List<Release> {
        val array = JSONArray(json)
        val releases = ArrayList<Release>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val tag = obj.optString("tag_name").takeIf { it.isNotBlank() } ?: continue
            releases += Release(
                tagName = tag,
                title = obj.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: tag,
                body = obj.optString("body").takeIf { it != "null" }.orEmpty(),
                prerelease = obj.optBoolean("prerelease", false),
                publishedAtMillis = parseIsoInstant(obj.optString("published_at")),
                assets = parseAssets(obj.optJSONArray("assets"))
            )
        }
        // The API already returns newest-first, but nothing documents that as a guarantee.
        return releases.sortedByDescending { it.publishedAtMillis }
    }

    private fun parseAssets(array: JSONArray?): List<ReleaseAsset> {
        if (array == null) return emptyList()
        val assets = ArrayList<ReleaseAsset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val url = obj.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            assets += ReleaseAsset(name, url, obj.optLong("size", 0L))
        }
        return assets
    }

    /** Parses `update-manifest.json`. Returns null for an unknown schema or malformed content. */
    fun parseManifest(json: String): UpdateManifest? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (obj.optInt("schema", 0) != MANIFEST_SCHEMA) return null
        val versionName = obj.optString("versionName").takeIf { it.isNotBlank() } ?: return null
        val versionCode = obj.optInt("versionCode", 0).takeIf { it > 0 } ?: return null
        val artifacts = HashMap<String, ManifestArtifact>()
        val array = obj.optJSONArray("artifacts")
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val applicationId = entry.optString("applicationId").takeIf { it.isNotBlank() } ?: continue
                val file = entry.optString("file").takeIf { it.isNotBlank() } ?: continue
                artifacts[applicationId] = ManifestArtifact(
                    fileName = file,
                    sizeBytes = entry.optLong("size", 0L),
                    sha256 = entry.optString("sha256").takeIf { it.length == 64 }?.lowercase(),
                    url = entry.optString("url").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }
        if (artifacts.isEmpty()) return null
        return UpdateManifest(
            versionName = versionName,
            versionCode = versionCode,
            minSdk = obj.optInt("minSdk", 0),
            notes = obj.optString("notes").takeIf { it.isNotBlank() && it != "null" },
            artifacts = artifacts
        )
    }

    /**
     * Narrows a release to the one artifact that can update [applicationId] in place.
     * Returns null when the release has nothing for this variant, or when [manifest] declares a
     * `minSdk` above [deviceSdk].
     */
    fun toAvailableUpdate(
        release: Release,
        manifest: UpdateManifest?,
        applicationId: String,
        deviceSdk: Int
    ): AvailableUpdate? {
        val manifestArtifact = manifest?.artifacts?.get(applicationId)
        if (manifest != null && manifestArtifact == null) {
            // The maintainer published a manifest and this variant is not in it: that is an
            // explicit "no build for you", not a reason to fall back to name guessing.
            return null
        }
        if (manifest != null && manifest.minSdk > 0 && deviceSdk < manifest.minSdk) return null

        val assetName = manifestArtifact?.fileName
            ?: UpdateArtifacts.selectAssetName(release.assets.map { it.name }, applicationId)
            ?: return null
        val asset = release.asset(assetName) ?: return null

        return AvailableUpdate(
            versionName = manifest?.versionName ?: release.tagName.removePrefix("v"),
            versionCode = manifest?.versionCode,
            tagName = release.tagName,
            notes = manifest?.notes?.takeIf { it.isNotBlank() } ?: release.body,
            publishedAtMillis = release.publishedAtMillis,
            prerelease = release.prerelease,
            artifact = UpdateArtifact(
                fileName = asset.name,
                downloadUrl = asset.downloadUrl,
                // The API's own size wins; a manifest size is only a cross-check.
                sizeBytes = asset.sizeBytes.takeIf { it > 0 } ?: manifestArtifact?.sizeBytes ?: 0L,
                sha256 = manifestArtifact?.sha256
            )
        )
    }

    /**
     * Builds an update from a manifest that is not attached to a GitHub release — a self-hosted
     * `update-manifest.json`. Artifact URLs come from the manifest itself and are resolved
     * against [manifestUrl], so a manifest can sit next to its APKs and use relative paths.
     */
    fun fromManifest(
        manifest: UpdateManifest,
        manifestUrl: String,
        applicationId: String,
        deviceSdk: Int
    ): AvailableUpdate? {
        val artifact = manifest.artifacts[applicationId] ?: return null
        if (manifest.minSdk > 0 && deviceSdk < manifest.minSdk) return null
        val rawUrl = artifact.url ?: artifact.fileName
        val downloadUrl = UpdateSource.resolveArtifactUrl(manifestUrl, rawUrl) ?: return null
        return AvailableUpdate(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            tagName = manifest.versionName,
            notes = manifest.notes.orEmpty(),
            publishedAtMillis = 0L,
            prerelease = false,
            artifact = UpdateArtifact(
                fileName = artifact.fileName,
                downloadUrl = downloadUrl,
                sizeBytes = artifact.sizeBytes,
                sha256 = artifact.sha256
            )
        )
    }

    /**
     * True when [update] is worth offering over the running build. Version code decides whenever
     * the release declares one; otherwise the version names are compared.
     */
    fun isNewerThanInstalled(
        update: AvailableUpdate,
        installedVersionCode: Int,
        installedVersionName: String
    ): Boolean {
        update.versionCode?.let { return it > installedVersionCode }
        return AppVersion.isNewer(update.versionName, installedVersionName)
    }

    private val ISO_FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun parseIsoInstant(value: String?): Long {
        if (value.isNullOrBlank() || value == "null") return 0L
        return runCatching { ISO_FORMAT.get()!!.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }
}
