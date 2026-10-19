package tk.glucodata.update

/**
 * Pure data + comparison logic for the in-app updater. Everything here is free of Android
 * dependencies so it can be unit tested directly (see `Common/src/test/.../update/`).
 *
 * Distribution model: JugglucoNG ships as a direct APK on GitHub Releases. The updater reads
 * the release list, picks the artifact that matches *this* build's applicationId, and hands the
 * downloaded file to the platform [android.content.pm.PackageInstaller]. Android still owns the
 * final confirmation dialog; nothing here installs silently.
 */

/**
 * Where releases are read from. Any `https://` URL — this project, a fork, a mirror, or a
 * self-hosted manifest. Three shapes are understood:
 *
 *  - `https://github.com/<owner>/<repo>` — rewritten to that repo's releases API;
 *  - `https://api.github.com/repos/<owner>/<repo>/releases` — used as-is;
 *  - anything else — fetched and read as either a GitHub-shaped releases array or an
 *    `update-manifest.json` object, decided by the response itself.
 *
 * Deliberately unrestricted. Pointing this at a hostile host still cannot install anything: an
 * APK has to carry the installed app's signing certificate to replace it, and no third party can
 * produce one. An allowlist here would buy nothing and only stop legitimate mirrors.
 */
object UpdateSource {

    /** What a source URL resolves to before anything is fetched. */
    sealed interface Resolved {
        /** A GitHub releases API endpoint; assets come from the API's own listing. */
        data class GithubReleases(val apiUrl: String) : Resolved

        /** A plain document; its content decides how it is parsed. */
        data class Document(val url: String) : Resolved
    }

    private val GITHUB_REPO_PATH = Regex("^/([A-Za-z0-9._-]{1,64})/([A-Za-z0-9._-]{1,100})/?$")

    fun sanitize(raw: String): String = raw.trim().trimEnd('/')

    /** Null when [raw] is not a usable https URL. */
    fun resolve(raw: String): Resolved? {
        val cleaned = sanitize(raw)
        val url = runCatching { java.net.URL(cleaned) }.getOrNull() ?: return null
        if (!url.protocol.equals("https", ignoreCase = true)) return null
        if (url.host.isNullOrBlank()) return null

        if (url.host.equals("github.com", ignoreCase = true)) {
            val match = GITHUB_REPO_PATH.find(url.path)
            if (match != null) {
                val (owner, repo) = match.destructured
                return Resolved.GithubReleases("https://api.github.com/repos/$owner/$repo/releases")
            }
        }
        if (url.host.equals("api.github.com", ignoreCase = true) && url.path.endsWith("/releases")) {
            return Resolved.GithubReleases(cleaned)
        }
        return Resolved.Document(cleaned)
    }

    fun isValid(raw: String): Boolean = resolve(raw) != null

    /** Resolves a possibly-relative artifact URL against the manifest it came from. */
    fun resolveArtifactUrl(manifestUrl: String, artifactUrl: String): String? {
        val resolved = runCatching {
            java.net.URL(java.net.URL(manifestUrl), artifactUrl)
        }.getOrNull() ?: return null
        return resolved.takeIf { it.protocol.equals("https", ignoreCase = true) }?.toString()
    }
}

/** One downloadable APK belonging to a release. */
data class UpdateArtifact(
    val fileName: String,
    /**
     * For a GitHub source this comes from the API's own asset listing rather than from a field
     * inside `update-manifest.json`, so a tampered manifest cannot redirect the download. A
     * self-hosted manifest has no asset listing, so there it is the manifest's own `url`,
     * resolved against the manifest's location.
     */
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String? = null
)

/** A release that is newer than what is installed, already narrowed to this build's variant. */
data class AvailableUpdate(
    /** Base version name as released, e.g. `1.2.0-Alpha` (no `-phone`/`DUB` build suffixes). */
    val versionName: String,
    /** Present only when the release carries an `update-manifest.json` asset. */
    val versionCode: Int?,
    val tagName: String,
    val notes: String,
    val publishedAtMillis: Long,
    val prerelease: Boolean,
    val artifact: UpdateArtifact
) {
    /** Stable identity for "the user already dismissed this one". */
    val identity: String get() = "$tagName/${artifact.fileName}"
}

/** Why a check or download failed. Mapped to a string resource by the UI. */
enum class UpdateError {
    NETWORK,
    RATE_LIMITED,
    PARSE,
    NO_ARTIFACT,
    CHECKSUM,
    SIGNATURE,
    PACKAGE_MISMATCH,
    DOWNGRADE,
    STORAGE,
    INSTALL_PERMISSION,
    INSTALL_FAILED,
    CANCELLED
}

/** Result of one update check. */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data class Failed(val error: UpdateError) : UpdateCheckResult
}

/**
 * Version-name ordering for JugglucoNG tags (`1.1.0-Alpha`, `1.0.9-Alpha`, `v1.2.0`).
 *
 * Only used as a fallback: once a release carries an `update-manifest.json` with a
 * `versionCode`, that integer decides. Release tags have never contained a version code, so
 * releases published before the manifest existed still need a name comparison to be usable.
 */
object AppVersion {

    /** Standard semver-ish ordering: numeric parts first, then qualifier, release > pre-release. */
    fun compare(left: String, right: String): Int {
        val a = parse(left)
        val b = parse(right)
        val size = maxOf(a.numbers.size, b.numbers.size)
        for (i in 0 until size) {
            val cmp = (a.numbers.getOrNull(i) ?: 0).compareTo(b.numbers.getOrNull(i) ?: 0)
            if (cmp != 0) return cmp
        }
        // No qualifier means a final release, which outranks any qualifier ("1.2.0" > "1.2.0-Alpha").
        if (a.qualifier.isEmpty() != b.qualifier.isEmpty()) {
            return if (a.qualifier.isEmpty()) 1 else -1
        }
        return compareQualifiers(a.qualifier, b.qualifier)
    }

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    private data class Parsed(val numbers: List<Int>, val qualifier: String)

    private fun parse(raw: String): Parsed {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val separator = trimmed.indexOfFirst { it == '-' || it == '+' || it == '_' }
        val head = if (separator >= 0) trimmed.substring(0, separator) else trimmed
        val tail = if (separator >= 0) trimmed.substring(separator + 1) else ""
        val numbers = head.split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return Parsed(numbers, tail)
    }

    /**
     * Digit-aware compare so `Alpha10` sorts after `Alpha2` rather than before it, which a plain
     * lexicographic compare would get wrong.
     */
    private fun compareQualifiers(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val lc = left[i]
            val rc = right[j]
            if (lc.isDigit() && rc.isDigit()) {
                var iEnd = i
                while (iEnd < left.length && left[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < right.length && right[jEnd].isDigit()) jEnd++
                val lNum = left.substring(i, iEnd).toLongOrNull() ?: 0L
                val rNum = right.substring(j, jEnd).toLongOrNull() ?: 0L
                if (lNum != rNum) return lNum.compareTo(rNum)
                i = iEnd
                j = jEnd
            } else {
                val cmp = lc.lowercaseChar().compareTo(rc.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (left.length - i).compareTo(right.length - j)
    }
}

/**
 * Maps this build's applicationId onto the release-asset naming scheme produced by
 * `Common/build.gradle` (`JugglucoNG-<version>[-wear][-<build type>].apk`).
 *
 * Offering the wrong artifact is not a cosmetic bug: a `-dub` APK carries a different
 * applicationId and would install as a *second* app rather than an update, and a `-wear` APK
 * would simply fail. So the match is exact on the variant tokens, not a "contains" guess.
 */
object UpdateArtifacts {

    private const val PREFIX = "JugglucoNG-"
    private const val SUFFIX = ".apk"

    /** Tokens the filename builder can append. Anything else in the name is version text. */
    private val VARIANT_TOKENS = setOf("wear", "debug", "dub", "dub2")

    /**
     * The variant tokens an artifact must carry to be installable over [applicationId], or null
     * when this build must never self-update (debug builds are signed and named per developer
     * machine and have their own applicationId suffix).
     */
    fun expectedTokens(applicationId: String): Set<String>? = when {
        applicationId.endsWith(".debug") -> null
        applicationId.endsWith(".dub2") -> setOf("dub2")
        applicationId.endsWith(".dub") -> setOf("dub")
        else -> emptySet()
    }

    /** Variant tokens actually present in an asset filename, or null if it is not one of ours. */
    fun tokensOf(assetName: String): Set<String>? {
        if (!assetName.startsWith(PREFIX) || !assetName.endsWith(SUFFIX)) return null
        val body = assetName.removeSuffix(SUFFIX)
        return body.split('-').filterTo(mutableSetOf()) { it.lowercase() in VARIANT_TOKENS }
    }

    /** Picks the single asset that can update [applicationId] in place, if the release has one. */
    fun selectAssetName(assetNames: List<String>, applicationId: String): String? {
        val wanted = expectedTokens(applicationId) ?: return null
        return assetNames.firstOrNull { tokensOf(it) == wanted }
    }
}
