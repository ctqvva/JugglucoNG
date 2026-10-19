package tk.glucodata.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering and artifact selection for the in-app updater.
 *
 * These two are the only places where a wrong answer is silently destructive: a bad comparison
 * offers a downgrade (or hides a real update forever), and a bad artifact match offers a `-dub`
 * or `-wear` APK that installs as a second app or not at all.
 */
class UpdateVersionTests {

    @Test
    fun `newer patch beats older patch`() {
        assertTrue(AppVersion.isNewer("1.1.0-Alpha", "1.0.9-Alpha"))
        assertFalse(AppVersion.isNewer("1.0.9-Alpha", "1.1.0-Alpha"))
    }

    @Test
    fun `identical versions are not newer`() {
        assertFalse(AppVersion.isNewer("1.1.0-Alpha", "1.1.0-Alpha"))
        assertEquals(0, AppVersion.compare("1.1.0-Alpha", "1.1.0-Alpha"))
    }

    @Test
    fun `leading v is ignored`() {
        assertEquals(0, AppVersion.compare("v1.1.0", "1.1.0"))
        assertTrue(AppVersion.isNewer("v1.2.0", "1.1.0"))
    }

    @Test
    fun `missing components count as zero`() {
        assertEquals(0, AppVersion.compare("1.1", "1.1.0"))
        assertTrue(AppVersion.isNewer("1.1.1", "1.1"))
    }

    @Test
    fun `final release outranks a qualifier of the same number`() {
        assertTrue(AppVersion.isNewer("1.1.0", "1.1.0-Alpha"))
        assertFalse(AppVersion.isNewer("1.1.0-Alpha", "1.1.0"))
    }

    @Test
    fun `qualifiers compare digit-aware, not lexicographically`() {
        // Plain string ordering would put Alpha10 before Alpha2.
        assertTrue(AppVersion.isNewer("1.1.0-Alpha10", "1.1.0-Alpha2"))
        assertTrue(AppVersion.isNewer("1.1.0-Alpha2", "1.1.0-Alpha"))
    }

    @Test
    fun `double digit minor sorts above single digit`() {
        assertTrue(AppVersion.isNewer("1.10.0-Alpha", "1.9.0-Alpha"))
    }

    @Test
    fun `mobile release picks the untagged apk`() {
        val assets = listOf(
            "JugglucoNG-1.1.0-Alpha.apk",
            "JugglucoNG-1.1.0-Alpha-dub.apk",
            "JugglucoNG-1.1.0-Alpha-dub2.apk",
            "JugglucoNG-1.1.0-Alpha-wear.apk",
            "update-manifest.json"
        )
        assertEquals(
            "JugglucoNG-1.1.0-Alpha.apk",
            UpdateArtifacts.selectAssetName(assets, "tk.glucodata.ng")
        )
    }

    @Test
    fun `dub variants pick their own apk`() {
        val assets = listOf(
            "JugglucoNG-1.1.0-Alpha.apk",
            "JugglucoNG-1.1.0-Alpha-dub.apk",
            "JugglucoNG-1.1.0-Alpha-dub2.apk"
        )
        assertEquals(
            "JugglucoNG-1.1.0-Alpha-dub.apk",
            UpdateArtifacts.selectAssetName(assets, "tk.glucodata.ng.dub")
        )
        assertEquals(
            "JugglucoNG-1.1.0-Alpha-dub2.apk",
            UpdateArtifacts.selectAssetName(assets, "tk.glucodata.ng.dub2")
        )
    }

    @Test
    fun `a wear-dub build never matches the phone dub build`() {
        // "JugglucoNG-<v>-wear-dub.apk" ends with "-dub.apk"; a suffix test would take it.
        val assets = listOf("JugglucoNG-1.1.0-Alpha-wear-dub.apk")
        assertNull(UpdateArtifacts.selectAssetName(assets, "tk.glucodata.ng.dub"))
        assertNull(UpdateArtifacts.selectAssetName(assets, "tk.glucodata.ng"))
    }

    @Test
    fun `debug builds are never offered an artifact`() {
        val assets = listOf("JugglucoNG-1.1.0-Alpha.apk", "JugglucoNG-1.1.0-Alpha-debug.apk")
        assertNull(UpdateArtifacts.expectedTokens("tk.glucodata.ng.debug"))
        assertNull(UpdateArtifacts.selectAssetName(assets, "tk.glucodata.ng.debug"))
    }

    @Test
    fun `foreign filenames are ignored`() {
        val assets = listOf("Juggluco-1.1.0.apk", "JugglucoNG-1.1.0-Alpha.zip", "notes.txt")
        assertNull(UpdateArtifacts.selectAssetName(assets, "tk.glucodata.ng"))
    }
}
