package tk.glucodata.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update source is user-editable free text, so what it accepts and what it turns into
 * decides where the app goes looking for an APK. Worth pinning down.
 */
class UpdateSourceTests {

    @Test
    fun `a github project url becomes that project's releases api`() {
        val resolved = UpdateSource.resolve("https://github.com/ctqvva/JugglucoNG")
        assertEquals(
            UpdateSource.Resolved.GithubReleases("https://api.github.com/repos/ctqvva/JugglucoNG/releases"),
            resolved
        )
    }

    @Test
    fun `a trailing slash is tolerated`() {
        assertEquals(
            UpdateSource.Resolved.GithubReleases("https://api.github.com/repos/owner/repo/releases"),
            UpdateSource.resolve("  https://github.com/owner/repo/  ")
        )
    }

    @Test
    fun `an explicit releases api url is used as-is`() {
        assertEquals(
            UpdateSource.Resolved.GithubReleases("https://api.github.com/repos/owner/repo/releases"),
            UpdateSource.resolve("https://api.github.com/repos/owner/repo/releases")
        )
    }

    @Test
    fun `a self-hosted manifest is treated as a document`() {
        assertEquals(
            UpdateSource.Resolved.Document("https://updates.example.org/juggluco/stable.json"),
            UpdateSource.resolve("https://updates.example.org/juggluco/stable.json")
        )
    }

    @Test
    fun `a deep github url that is not a project root stays a document`() {
        // github.com/owner/repo/tree/main is a page, not a project root; fetching it as an API
        // would 404. Treating it as a document at least gives a sensible parse failure.
        val resolved = UpdateSource.resolve("https://github.com/owner/repo/tree/main")
        assertTrue(resolved is UpdateSource.Resolved.Document)
    }

    @Test
    fun `plain http and garbage are rejected`() {
        assertNull(UpdateSource.resolve("http://github.com/owner/repo"))
        assertNull(UpdateSource.resolve("owner/repo"))
        assertNull(UpdateSource.resolve("not a url"))
        assertNull(UpdateSource.resolve(""))
        assertFalse(UpdateSource.isValid("ftp://example.org/manifest.json"))
    }

    @Test
    fun `artifact urls resolve relative to their manifest`() {
        assertEquals(
            "https://updates.example.org/juggluco/JugglucoNG-1.2.0.apk",
            UpdateSource.resolveArtifactUrl(
                "https://updates.example.org/juggluco/stable.json",
                "JugglucoNG-1.2.0.apk"
            )
        )
        assertEquals(
            "https://cdn.example.org/a.apk",
            UpdateSource.resolveArtifactUrl(
                "https://updates.example.org/juggluco/stable.json",
                "https://cdn.example.org/a.apk"
            )
        )
    }

    @Test
    fun `an artifact url may not downgrade the transport`() {
        assertNull(
            UpdateSource.resolveArtifactUrl(
                "https://updates.example.org/stable.json",
                "http://updates.example.org/a.apk"
            )
        )
    }

    @Test
    fun `a self-hosted manifest yields an update with its own url`() {
        val manifest = GithubReleaseParser.parseManifest(
            """
            {
              "schema": 1,
              "versionName": "1.2.0-Alpha",
              "versionCode": 1011,
              "minSdk": 26,
              "artifacts": [
                {
                  "applicationId": "tk.glucodata.ng",
                  "file": "JugglucoNG-1.2.0-Alpha.apk",
                  "url": "apks/JugglucoNG-1.2.0-Alpha.apk",
                  "size": 76108854
                }
              ]
            }
            """.trimIndent()
        )
        val update = GithubReleaseParser.fromManifest(
            manifest = manifest!!,
            manifestUrl = "https://updates.example.org/juggluco/stable.json",
            applicationId = "tk.glucodata.ng",
            deviceSdk = 34
        )
        assertEquals(
            "https://updates.example.org/juggluco/apks/JugglucoNG-1.2.0-Alpha.apk",
            update!!.artifact.downloadUrl
        )
        assertEquals(1011, update.versionCode)
    }

    @Test
    fun `a self-hosted manifest without this variant offers nothing`() {
        val manifest = GithubReleaseParser.parseManifest(
            """
            {
              "schema": 1, "versionName": "1.2.0", "versionCode": 1011,
              "artifacts": [{"applicationId": "tk.glucodata.ng.dub", "file": "x.apk"}]
            }
            """.trimIndent()
        )
        assertNull(
            GithubReleaseParser.fromManifest(
                manifest!!, "https://example.org/m.json", "tk.glucodata.ng", 34
            )
        )
    }
}
