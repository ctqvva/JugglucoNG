package tk.glucodata.update

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads releases from the SourceForge mirror of this project, which is the updater's backup for
 * when GitHub cannot be reached. Pure parsing, no I/O.
 *
 * The mirror is a plain file tree, not a releases API: one folder per release, named after the
 * tag (`1.2.8-Alpha/`), holding the same APKs, `update-manifest.json` and `README.md` as the
 * GitHub release. Nightlies and test drops live in sibling folders (`nightly-2026-11-06-…/`,
 * `ican-test-v1/`) and SourceForge carries no pre-release flag, so "a regular release" is
 * decided here, from the folder name alone: it has to look like a version number.
 *
 * The folders are turned into [GithubReleaseParser.Release]s so everything after this point —
 * manifest lookup, variant matching, SHA-256, version comparison — is the exact code the GitHub
 * path runs, rather than a second implementation to keep in step.
 */
object SourceForgeReleases {

    /** A SourceForge project, as named in a `…/projects/<name>/files/` URL. */
    data class Project(val name: String) {
        /** The file tree's root page, which lists every folder. */
        val filesUrl: String get() = "https://sourceforge.net/projects/$name/files/"

        /** RSS feed of one folder's files; the only machine-readable listing SourceForge has. */
        fun folderFeedUrl(folder: String): String =
            "https://sourceforge.net/projects/$name/rss?path=/$folder"
    }

    private val PROJECT_FILES_URL =
        Regex("^https://sourceforge\\.net/projects/([A-Za-z0-9_-]{1,100})/files/?$")

    /** Null when [url] is empty or not a SourceForge project files URL (the fallback is then off). */
    fun project(url: String?): Project? {
        val match = PROJECT_FILES_URL.find(url?.trim().orEmpty()) ?: return null
        return Project(match.groupValues[1])
    }

    // `<tr title="1.2.8-Alpha" class="folder …">` — one row per folder on the files page.
    private val FOLDER_ROW = Regex("<tr title=\"([^\"]+)\" class=\"folder")

    /** Folder names on the project's root files page, in page order. */
    fun parseFolders(html: String): List<String> =
        FOLDER_ROW.findAll(html).map { it.groupValues[1] }.distinct().toList()

    // 1.2.8-Alpha, 0.9.4, 1.10.0-Alpha2 … a version, optionally followed by one qualifier.
    private val VERSION_FOLDER = Regex("^\\d+(\\.\\d+)+(-[A-Za-z0-9._]+)?$")

    // A qualifier that marks a build as not-for-everyone. "Alpha" is deliberately absent: the
    // project's regular releases are all called -Alpha.
    private val PRE_RELEASE_QUALIFIER =
        Regex("-(nightly|rc|beta|preview|pre|dev|snapshot|test)", RegexOption.IGNORE_CASE)

    /** True for a folder holding a regular release; false for nightlies, test drops, anything else. */
    fun isRegularRelease(folder: String): Boolean =
        VERSION_FOLDER.matches(folder) && !PRE_RELEASE_QUALIFIER.containsMatchIn(folder)

    /**
     * The [limit] highest-versioned regular releases, newest first. Ordered by version rather
     * than by the page, so the answer does not depend on how SourceForge happens to sort.
     * More than one is returned because a mirror can briefly hold a release folder before all
     * of its files have arrived; the caller then falls back to the one before it.
     */
    fun newestRegularReleases(folders: List<String>, limit: Int): List<String> =
        folders.filter(::isRegularRelease)
            .sortedWith { a, b -> AppVersion.compare(b, a) }
            .take(limit)

    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val ITEM_TITLE = Regex("<title><!\\[CDATA\\[(.*?)]]></title>", RegexOption.DOT_MATCHES_ALL)
    private val ITEM_LINK = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
    private val ITEM_DATE = Regex("<pubDate>(.*?)</pubDate>")
    private val ITEM_SIZE = Regex("filesize=\"(\\d+)\"")

    private val RSS_DATE = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'UT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /**
     * Builds the release held in [folder] from that folder's RSS [feed]. [notes] is the
     * folder's `README.md` when it was fetched. Null when the feed lists no files for the folder.
     *
     * Download URLs are the feed's own `/download` links; SourceForge redirects those to a
     * mirror host, over HTTPS throughout.
     */
    fun parseFolderRelease(folder: String, feed: String, notes: String? = null): GithubReleaseParser.Release? {
        val prefix = "/$folder/"
        val assets = ArrayList<GithubReleaseParser.ReleaseAsset>()
        var earliest = Long.MAX_VALUE
        for (item in ITEM.findAll(feed)) {
            val body = item.groupValues[1]
            val title = ITEM_TITLE.find(body)?.groupValues?.get(1)?.trim() ?: continue
            val link = ITEM_LINK.find(body)?.groupValues?.get(1)?.trim() ?: continue
            // Only direct children: the feed for a folder should not contain others, but a
            // path from a feed is not something to trust blindly.
            if (!title.startsWith(prefix)) continue
            val name = title.removePrefix(prefix)
            if (name.isBlank() || '/' in name) continue
            if (!link.startsWith("https://sourceforge.net/")) continue

            assets += GithubReleaseParser.ReleaseAsset(
                name = name,
                downloadUrl = link,
                sizeBytes = ITEM_SIZE.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            )
            ITEM_DATE.find(body)?.groupValues?.get(1)?.let { raw ->
                val millis = runCatching { RSS_DATE.get()!!.parse(raw.trim())?.time }.getOrNull()
                if (millis != null && millis < earliest) earliest = millis
            }
        }
        if (assets.isEmpty()) return null
        return GithubReleaseParser.Release(
            tagName = folder,
            title = folder,
            body = notes.orEmpty(),
            prerelease = false,
            // A re-uploaded file carries a newer date than the release it belongs to, so the
            // earliest file is the closest thing to "when this was published".
            publishedAtMillis = if (earliest == Long.MAX_VALUE) 0L else earliest,
            assets = assets
        )
    }
}
