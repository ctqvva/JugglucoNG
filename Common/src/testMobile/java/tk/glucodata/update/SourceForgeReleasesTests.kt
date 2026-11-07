package tk.glucodata.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SourceForge mirror is a file tree, not a releases API, so what counts as a release, which
 * files belong to it and which one is newest are all decided by parsing here. Fixtures are cut
 * from the real project listing and folder feed.
 */
class SourceForgeReleasesTests {

    private fun folderRow(name: String) =
        """<tr title="$name" class="folder ">  <th scope="row" headers="files_name_h"><a href="/projects/jugglucong/files/$name/">$name</a></th></tr>"""

    private val listing = listOf(
        "nightly-2026-11-06-98162d46913e",
        "nightly-2026-11-05-8c181f65b0b0",
        "1.2.8-Alpha",
        "nightly-2026-11-01-2904b3b7d1d5",
        "1.2.7-Alpha",
        "ican-test-v1",
        "1.2.10-Alpha",
        "0.9.4"
    ).joinToString("\n") { folderRow(it) } +
        """<tr title="README.md" class="file "><th>README.md</th></tr>"""

    private fun item(folder: String, name: String, size: Long, date: String) = """
        <item>
          <title><![CDATA[/$folder/$name]]></title>
          <link>https://sourceforge.net/projects/jugglucong/files/$folder/$name/download</link>
          <guid>https://sourceforge.net/projects/jugglucong/files/$folder/$name/download</guid>
          <pubDate>$date</pubDate>
          <media:content xmlns:media="http://video.search.yahoo.com/mrss/" type="application/x" url="https://sourceforge.net/projects/jugglucong/files/$folder/$name/download" filesize="$size"><media:hash algo="md5">00</media:hash></media:content>
        </item>
    """.trimIndent()

    private val feed = """
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
        ${item("1.2.8-Alpha", "JugglucoNG-1.2.8-Alpha-wear-dub.apk", 44933895, "Sat, 07 Nov 2026 16:23:11 UT")}
        ${item("1.2.8-Alpha", "JugglucoNG-1.2.8-Alpha-dub.apk", 59428679, "Mon, 02 Nov 2026 03:21:53 UT")}
        ${item("1.2.8-Alpha", "JugglucoNG-1.2.8-Alpha.apk", 59483711, "Mon, 02 Nov 2026 03:21:53 UT")}
        ${item("1.2.8-Alpha", "update-manifest.json", 582, "Mon, 02 Nov 2026 03:21:53 UT")}
        ${item("1.2.8-Alpha", "README.md", 3000, "Sun, 01 Nov 2026 22:39:06 UT")}
        </channel></rss>
    """.trimIndent()

    @Test
    fun `reads the project name from a files url`() {
        assertEquals(
            "jugglucong",
            SourceForgeReleases.project("https://sourceforge.net/projects/jugglucong/files/")?.name
        )
        assertEquals(
            "jugglucong",
            SourceForgeReleases.project(" https://sourceforge.net/projects/jugglucong/files ")?.name
        )
    }

    @Test
    fun `anything that is not a sourceforge files url turns the fallback off`() {
        assertNull(SourceForgeReleases.project(""))
        assertNull(SourceForgeReleases.project(null))
        assertNull(SourceForgeReleases.project("http://sourceforge.net/projects/jugglucong/files/"))
        assertNull(SourceForgeReleases.project("https://example.org/projects/jugglucong/files/"))
        assertNull(SourceForgeReleases.project("https://sourceforge.net/projects/jugglucong/files/1.2.8-Alpha/"))
    }

    @Test
    fun `lists folders and ignores file rows`() {
        val folders = SourceForgeReleases.parseFolders(listing)
        assertEquals(8, folders.size)
        assertFalse("README.md" in folders)
        assertEquals("nightly-2026-11-06-98162d46913e", folders.first())
    }

    @Test
    fun `only version-shaped folders are regular releases`() {
        assertTrue(SourceForgeReleases.isRegularRelease("1.2.8-Alpha"))
        assertTrue(SourceForgeReleases.isRegularRelease("0.9.4"))
        assertTrue(SourceForgeReleases.isRegularRelease("1.10.0-Alpha2"))
        assertFalse(SourceForgeReleases.isRegularRelease("nightly-2026-11-06-98162d46913e"))
        assertFalse(SourceForgeReleases.isRegularRelease("ican-test-v1"))
        assertFalse(SourceForgeReleases.isRegularRelease("1.3.0-rc1"))
        assertFalse(SourceForgeReleases.isRegularRelease("1.3.0-beta.2"))
        assertFalse(SourceForgeReleases.isRegularRelease("1.3.0-Nightly"))
        assertFalse(SourceForgeReleases.isRegularRelease("1.2.8-Alpha/../x"))
        assertFalse(SourceForgeReleases.isRegularRelease(""))
    }

    @Test
    fun `newest is chosen by version, not by page order`() {
        // 1.2.10 sorts after 1.2.8 numerically even though it appears later on the page and
        // would sort before it as text.
        val newest = SourceForgeReleases.newestRegularReleases(SourceForgeReleases.parseFolders(listing), 2)
        assertEquals(listOf("1.2.10-Alpha", "1.2.8-Alpha"), newest)
    }

    @Test
    fun `nightlies alone leave no release`() {
        val onlyNightlies = SourceForgeReleases.parseFolders(
            folderRow("nightly-2026-11-06-98162d46913e") + folderRow("ican-test-v1")
        )
        assertTrue(SourceForgeReleases.newestRegularReleases(onlyNightlies, 3).isEmpty())
    }

    @Test
    fun `builds a release from a folder feed`() {
        val release = SourceForgeReleases.parseFolderRelease("1.2.8-Alpha", feed, notes = "Notes")
        assertNotNull(release)
        release!!
        assertEquals("1.2.8-Alpha", release.tagName)
        assertFalse(release.prerelease)
        assertEquals("Notes", release.body)
        assertEquals(5, release.assets.size)
        val apk = release.asset("JugglucoNG-1.2.8-Alpha.apk")!!
        assertEquals(59483711L, apk.sizeBytes)
        assertEquals(
            "https://sourceforge.net/projects/jugglucong/files/1.2.8-Alpha/JugglucoNG-1.2.8-Alpha.apk/download",
            apk.downloadUrl
        )
        // Earliest file wins, so the re-uploaded wear APK (dated "today") does not move it.
        // 2026-11-01 22:39:06 UTC
        assertEquals(1793572746000L, release.publishedAtMillis)
    }

    @Test
    fun `files belonging to another folder or off the host are dropped`() {
        val hostile = """
            <rss><channel>
            ${item("1.2.8-Alpha", "JugglucoNG-1.2.8-Alpha.apk", 10, "Mon, 02 Nov 2026 03:21:53 UT")}
            ${item("1.2.7-Alpha", "JugglucoNG-1.2.7-Alpha.apk", 10, "Mon, 02 Nov 2026 03:21:53 UT")}
            ${item("1.2.8-Alpha", "sub/JugglucoNG-1.2.8-Alpha-dub.apk", 10, "Mon, 02 Nov 2026 03:21:53 UT")}
            <item>
              <title><![CDATA[/1.2.8-Alpha/JugglucoNG-1.2.8-Alpha-wear.apk]]></title>
              <link>https://evil.example.org/JugglucoNG-1.2.8-Alpha-wear.apk</link>
            </item>
            </channel></rss>
        """.trimIndent()
        val release = SourceForgeReleases.parseFolderRelease("1.2.8-Alpha", hostile)!!
        assertEquals(listOf("JugglucoNG-1.2.8-Alpha.apk"), release.assets.map { it.name })
    }

    @Test
    fun `an empty or foreign feed is not a release`() {
        assertNull(SourceForgeReleases.parseFolderRelease("1.2.8-Alpha", "<rss><channel></channel></rss>"))
        assertNull(SourceForgeReleases.parseFolderRelease("1.2.9-Alpha", feed))
    }

    @Test
    fun `a mirrored release goes through the same variant matching as a github one`() {
        val release = SourceForgeReleases.parseFolderRelease("1.2.8-Alpha", feed)!!
        val manifest = GithubReleaseParser.parseManifest(
            """
            {"schema":1,"versionName":"1.2.8-Alpha","versionCode":1028,"minSdk":26,"artifacts":[
              {"applicationId":"tk.glucodata.ng.dub","file":"JugglucoNG-1.2.8-Alpha-dub.apk","size":59428679,"sha256":"${"9".repeat(64)}"},
              {"applicationId":"tk.glucodata.ng","file":"JugglucoNG-1.2.8-Alpha.apk","size":59483711,"sha256":"${"a".repeat(64)}"}
            ]}
            """.trimIndent()
        )

        val main = GithubReleaseParser.toAvailableUpdate(release, manifest, "tk.glucodata.ng", 34)!!
        assertEquals("JugglucoNG-1.2.8-Alpha.apk", main.artifact.fileName)
        assertEquals(1028, main.versionCode)
        assertEquals("a".repeat(64), main.artifact.sha256)
        assertTrue(main.artifact.downloadUrl.startsWith("https://sourceforge.net/projects/jugglucong/files/1.2.8-Alpha/"))

        val dub = GithubReleaseParser.toAvailableUpdate(release, manifest, "tk.glucodata.ng.dub", 34)!!
        assertEquals("JugglucoNG-1.2.8-Alpha-dub.apk", dub.artifact.fileName)

        // Without a manifest the filename tokens decide, exactly as for a GitHub release.
        val byName = GithubReleaseParser.toAvailableUpdate(release, null, "tk.glucodata.ng", 34)!!
        assertEquals("JugglucoNG-1.2.8-Alpha.apk", byName.artifact.fileName)
        assertEquals("1.2.8-Alpha", byName.versionName)
    }

    @Test
    fun `the backup is only for the default github source and only when github is unreachable`() {
        val default = "https://github.com/ctqvva/JugglucoNG"

        assertTrue(UpdateSourceClient.shouldTryBackup(default, default, UpdateError.NETWORK))
        assertTrue(UpdateSourceClient.shouldTryBackup("$default/", default, UpdateError.RATE_LIMITED))

        // GitHub answered, so the mirror would only say the same thing.
        assertFalse(UpdateSourceClient.shouldTryBackup(default, default, UpdateError.PARSE))
        assertFalse(UpdateSourceClient.shouldTryBackup(default, default, UpdateError.NO_ARTIFACT))

        // A source the user chose is never swapped for ours, even when it points at the same
        // repository through its API URL.
        assertFalse(UpdateSourceClient.shouldTryBackup("https://github.com/someone/fork", default, UpdateError.NETWORK))
        assertFalse(
            UpdateSourceClient.shouldTryBackup(
                "https://api.github.com/repos/ctqvva/JugglucoNG/releases", default, UpdateError.NETWORK
            )
        )
    }

    @Test
    fun `the backup only replaces github's failure with a verdict`() {
        val github = UpdateCheckResult.Failed(UpdateError.RATE_LIMITED)
        val update = AvailableUpdate(
            versionName = "1.2.8-Alpha", versionCode = 1028, tagName = "1.2.8-Alpha", notes = "",
            publishedAtMillis = 0L, prerelease = false,
            artifact = UpdateArtifact("JugglucoNG-1.2.8-Alpha.apk", "https://sourceforge.net/x", 1L)
        )

        assertEquals(UpdateCheckResult.UpToDate, UpdateSourceClient.afterBackup(github, UpdateCheckResult.UpToDate))
        assertEquals(
            UpdateCheckResult.Available(update),
            UpdateSourceClient.afterBackup(github, UpdateCheckResult.Available(update))
        )
        // Unreadable mirror, or a readable one with no build for this variant: GitHub's error stays.
        assertEquals(github, UpdateSourceClient.afterBackup(github, null))
        assertEquals(
            github,
            UpdateSourceClient.afterBackup(github, UpdateCheckResult.Failed(UpdateError.NO_ARTIFACT))
        )
    }
}
