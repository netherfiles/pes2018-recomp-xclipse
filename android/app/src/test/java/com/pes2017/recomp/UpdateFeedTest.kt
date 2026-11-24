package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release feed and the choice of update, on plain JVM strings. */
class UpdateFeedTest {

    // JSON with its ESCAPES in the strings, not raw newlines inside a quoted
    // body: the lines are joined with real newlines, the bodies carry \n.
    private val feed = listOf(
        "[",
        "  {",
        "    \"tag_name\": \"0.3.3\",",
        "    \"html_url\": \"https://example.test/releases/0.3.3\",",
        "    \"prerelease\": false,",
        "    \"draft\": false,",
        "    \"body\": \"Notes for 0.3.3\\nAPK versionCode: 26\\nMore notes.\",",
        "    \"assets\": [",
        "      {\"name\": \"notes.txt\", \"content_type\": \"text/plain\", \"browser_download_url\": \"https://example.test/notes.txt\"},",
        "      {\"name\": \"other.apk\", \"content_type\": \"application/octet-stream\", \"browser_download_url\": \"https://example.test/other.apk\"},",
        "      {\"name\": \"PES2018-recomp-0.3.3.apk\", \"content_type\": \"application/vnd.android.package-archive\", \"browser_download_url\": \"https://example.test/recomp.apk\", \"digest\": \"sha256:abc123\"}",
        "    ]",
        "  },",
        "  {",
        "    \"tag_name\": \"0.3.4-rc1\",",
        "    \"html_url\": \"https://example.test/releases/0.3.4-rc1\",",
        "    \"prerelease\": true,",
        "    \"draft\": false,",
        "    \"body\": \"APK versionCode: 27\",",
        "    \"assets\": [",
        "      {\"name\": \"PES2018-recomp-0.3.4-rc1.apk\", \"browser_download_url\": \"https://example.test/rc.apk\"}",
        "    ]",
        "  },",
        "  {",
        "    \"tag_name\": \"0.3.5\",",
        "    \"html_url\": \"https://example.test/releases/0.3.5\",",
        "    \"prerelease\": false,",
        "    \"draft\": true,",
        "    \"body\": \"APK versionCode: 99\",",
        "    \"assets\": [",
        "      {\"name\": \"PES2018-recomp-0.3.5.apk\", \"browser_download_url\": \"https://example.test/draft.apk\"}",
        "    ]",
        "  },",
        "  {",
        "    \"tag_name\": \"0.3.6\",",
        "    \"html_url\": \"https://example.test/releases/0.3.6\",",
        "    \"prerelease\": false,",
        "    \"draft\": false,",
        "    \"body\": \"APK versionCode: 28\",",
        "    \"assets\": [",
        "      {\"name\": \"notes.txt\", \"content_type\": \"text/plain\", \"browser_download_url\": \"https://example.test/notes.txt\"}",
        "    ]",
        "  },",
        "  {",
        "    \"tag_name\": \"0.3.7\",",
        "    \"html_url\": \"https://example.test/releases/0.3.7\",",
        "    \"prerelease\": false,",
        "    \"draft\": false,",
        "    \"body\": \"No build number in this body.\",",
        "    \"assets\": [",
        "      {\"name\": \"PES2018-recomp-0.3.7.apk\", \"browser_download_url\": \"https://example.test/no-code.apk\"}",
        "    ]",
        "  }",
        "]",
    ).joinToString("\n")

    @Test
    fun parseReleasesReadsTheFeed() {
        val releases = UpdateFeed.parseReleases(feed)
        assertEquals(5, releases.size)
        val final = releases[0]
        assertEquals("0.3.3", final.tag)
        assertEquals(26, final.code)
        assertEquals("0.3.3", final.versionName)
        assertEquals("https://example.test/releases/0.3.3", final.pageUrl)
        assertFalse(final.prerelease)
        assertFalse(final.draft)
        assertFalse(final.notes.contains("\\n"))   // the escape became a real newline
        assertTrue(final.notes.contains("APK versionCode: 26"))
        assertEquals("abc123", final.sha256)
    }

    @Test
    fun theNamedApkWinsOverTheFirstOneInTheList() {
        val final = UpdateFeed.parseReleases(feed)[0]
        assertEquals("https://example.test/recomp.apk", final.apkUrl)
    }

    @Test
    fun anAndroidPackageAssetIsAnApkEvenWithAnotherName() {
        val releases = UpdateFeed.parseReleases(
            """[{"tag_name":"1.0","assets":[{"name":"build.bin","content_type":"application/vnd.android.package-archive","browser_download_url":"https://example.test/bin"}]}]""",
        )
        assertEquals("https://example.test/bin", releases[0].apkUrl)
    }

    @Test
    fun parseReleasesReadsPrereleaseAndDraftFlags() {
        val releases = UpdateFeed.parseReleases(feed)
        assertEquals("0.3.4-rc1", releases[1].tag)
        assertTrue(releases[1].prerelease)
        assertFalse(releases[1].draft)
        assertTrue(releases[2].draft)
        assertEquals(99, releases[2].code)
    }

    @Test
    fun aReleaseWithoutAnApkHasNoDownloadUrl() {
        val noAssets = UpdateFeed.parseReleases(feed)[3]
        assertEquals("", noAssets.apkUrl)
        assertEquals(28, noAssets.code)
    }

    @Test
    fun aBodyWithoutAVersionCodeGivesCodeZero() {
        val noCode = UpdateFeed.parseReleases(feed)[4]
        assertEquals(0, noCode.code)
        assertEquals("https://example.test/no-code.apk", noCode.apkUrl)
    }

    @Test
    fun brokenJsonIsNoReleases() {
        assertTrue(UpdateFeed.parseReleases("{").isEmpty())
        assertTrue(UpdateFeed.parseReleases("not json").isEmpty())
        assertTrue(UpdateFeed.parseReleases("""{"tag_name":"1.0"}""").isEmpty())   // not an array
        assertTrue(UpdateFeed.parseReleases("").isEmpty())
    }

    @Test
    fun duplicateTagsDoNotCrash() {
        val releases = UpdateFeed.parseReleases(
            """[{"tag_name":"1.0"},{"tag_name":"1.0"}]""",
        )
        assertEquals(2, releases.size)
    }

    @Test
    fun aNullEntryIsSkippedAndNullFieldsTakeTheirDefaults() {
        val releases = UpdateFeed.parseReleases(
            """[null,{"tag_name":"1.0","body":null,"assets":null,"prerelease":null,"draft":null}]""",
        )
        assertEquals(1, releases.size)
        assertEquals("1.0", releases[0].tag)
        assertEquals(0, releases[0].code)
        assertFalse(releases[0].prerelease)
        assertFalse(releases[0].draft)
        assertEquals("", releases[0].sha256)
    }

    @Test
    fun anEscapedBackslashBeforeNIsNotANewline() {
        // JSON "\\n" is a backslash followed by an n, not the \n escape.
        val releases = UpdateFeed.parseReleases(
            "[{\"tag_name\":\"1.0\",\"body\":\"line\\\\nAPK versionCode: 30\"}]",
        )
        assertEquals(30, releases[0].code)
        assertTrue(releases[0].notes.contains("\\n"))
        assertFalse(releases[0].notes.contains("\n"))
    }

    @Test
    fun aUnicodeEscapeIsRead() {
        val releases = UpdateFeed.parseReleases(
            "[{\"tag_name\":\"1.0\",\"body\":\"APK versionCode: 31 \\u00b7 notes\"}]",
        )
        assertEquals(31, releases[0].code)
        assertTrue(releases[0].notes.contains("\u00b7"))
    }

    @Test
    fun aDigestWithoutItsAlgorithmPrefixIsKeptAsIs() {
        val releases = UpdateFeed.parseReleases(
            """[{"tag_name":"1.0","assets":[{"name":"a.apk","browser_download_url":"https://example.test/a.apk","digest":"def456"}]}]""",
        )
        assertEquals("def456", releases[0].sha256)
    }

    @Test
    fun parseVersionJsonReadsTheCodeAndName() {
        assertEquals(
            23 to "0.3.2-rc2",
            UpdateFeed.parseVersionJson("""{"code":23,"versionName":"0.3.2-rc2"}"""),
        )
        assertNull(UpdateFeed.parseVersionJson("""{"versionName":"0.3.2-rc2"}"""))
        assertNull(UpdateFeed.parseVersionJson("["))
    }

    @Test
    fun parseVersionJsonAcceptsANumericStringAndANullName() {
        assertEquals(23 to "", UpdateFeed.parseVersionJson("""{"code":"23","versionName":null}"""))
        assertNull(UpdateFeed.parseVersionJson("""{"code":null}"""))
    }

    @Test
    fun aFinalBuildIsPickedWhenTestBuildsAreNotWanted() {
        val releases = listOf(
            release("0.3.3", code = 26, prerelease = false),
            release("0.3.4-rc1", code = 27, prerelease = true),
        )
        val pick = UpdateFeed.pickUpdate(
            releases,
            installedCode = 18,
            installedName = "0.3.0 (abc)",
            allowPrerelease = false,
        )
        assertNotNull(pick)
        assertEquals("0.3.3", pick!!.tag)
        assertEquals(26, pick.code)
    }

    @Test
    fun aTestBuildIsPickedOnAPhoneAlreadyCarryingOne() {
        val releases = listOf(
            release("0.3.3", code = 26, prerelease = false),
            release("0.3.4-rc1", code = 27, prerelease = true),
        )
        val pick = UpdateFeed.pickUpdate(
            releases,
            installedCode = 18,
            installedName = "0.3.0-rc1 (abc)",
            allowPrerelease = true,
        )
        assertNotNull(pick)
        assertEquals("0.3.4-rc1", pick!!.tag)
        assertEquals(27, pick.code)
    }

    @Test
    fun theSameBuildIsNotAnUpdate() {
        val current = listOf(release("0.3.3", code = 26, prerelease = false))
        assertNull(UpdateFeed.pickUpdate(current, installedCode = 26, installedName = "0.3.3 (abc)", allowPrerelease = false))
    }

    @Test
    fun aDraftIsNeverPicked() {
        val releases = listOf(
            release("0.3.3", code = 26, prerelease = false),
            release("0.3.5", code = 99, prerelease = false, draft = true),
        )
        val pick = UpdateFeed.pickUpdate(
            releases,
            installedCode = 0,
            installedName = "0.0.1 (abc)",
            allowPrerelease = true,
        )
        assertEquals("0.3.3", pick!!.tag)   // not the draft at 99
    }

    @Test
    fun withoutABuildCodeTheVersionNamesDecide() {
        val final = release("0.3.2", code = 0, prerelease = false)
        assertNotNull(UpdateFeed.pickUpdate(listOf(final), 0, "0.3.2-rc2 (abc)", false))
        assertNull(UpdateFeed.pickUpdate(listOf(final), 0, "0.3.2 (abc)", false))
        assertNull(UpdateFeed.pickUpdate(listOf(final), 0, "0.3.3 (abc)", false))
    }

    @Test
    fun rc2IsNewerThanRc1() {
        val rc2 = release("0.3.2-rc2", code = 0, prerelease = true)
        assertNotNull(UpdateFeed.pickUpdate(listOf(rc2), 0, "0.3.2-rc1 (abc)", true))
        assertNull(UpdateFeed.pickUpdate(listOf(rc2), 0, "0.3.2-rc2 (abc)", true))
    }

    @Test
    fun aCodedReleaseWinsOverANameOnlyReleaseInTheSameFeed() {
        val releases = listOf(
            release("9.9.9", code = 0, prerelease = false),
            release("0.3.3", code = 26, prerelease = false),
        )
        val pick = UpdateFeed.pickUpdate(
            releases,
            installedCode = 18,
            installedName = "0.3.2 (abc)",
            allowPrerelease = false,
        )
        assertNotNull(pick)
        assertEquals("0.3.3", pick!!.tag)   // the code, never the higher name
        assertEquals(26, pick.code)
    }

    @Test
    fun aNameOnlyReleaseIsIgnoredWhenTheFeedCarriesCodes() {
        val releases = listOf(
            release("9.9.9", code = 0, prerelease = false),
            release("0.3.3", code = 26, prerelease = false),
        )
        // The coded release is not an update, and the name-only one is not
        // consulted: the feed is judged by its codes alone.
        assertNull(UpdateFeed.pickUpdate(releases, 30, "0.3.3 (abc)", false))
    }

    @Test
    fun theNewestNameWinsWhenNoReleaseCarriesACode() {
        val releases = listOf(
            release("0.3.3", code = 0, prerelease = false),
            release("0.3.10", code = 0, prerelease = false),
            release("0.3.4", code = 0, prerelease = false),
        )
        val pick = UpdateFeed.pickUpdate(releases, 0, "0.3.2 (abc)", false)
        assertEquals("0.3.10", pick!!.tag)
    }

    @Test
    fun isNewerNameUsesTheNameRulesOnly() {
        assertTrue(UpdateFeed.isNewerName("0.3.2 (abc)", "0.3.3"))
        assertTrue(UpdateFeed.isNewerName("0.3.2-rc1 (abc)", "0.3.2"))
        assertFalse(UpdateFeed.isNewerName("0.3.3 (abc)", "0.3.3"))
        assertFalse(UpdateFeed.isNewerName("0.3.4 (abc)", "0.3.3"))
        assertFalse(UpdateFeed.isNewerName("0.3.3 (abc)", "not a version"))
        assertFalse(UpdateFeed.isNewerName("", "0.3.3"))
    }

    @Test
    fun aTestBuildAllowsTheNextOne() {
        assertTrue(UpdateFeed.allowPrerelease("0.3.2-rc2 (local)"))
        assertTrue(UpdateFeed.allowPrerelease("0.3.0-pad1 (abc)"))
        assertTrue(UpdateFeed.allowPrerelease("0.3.0-pr1 (abc)"))
        assertFalse(UpdateFeed.allowPrerelease("0.3.0 (abc)"))
    }

    private fun release(tag: String, code: Int, prerelease: Boolean, draft: Boolean = false) = UpdateFeed.Release(
        tag = tag,
        code = code,
        versionName = tag,
        apkUrl = "",
        pageUrl = "",
        prerelease = prerelease,
        draft = draft,
        notes = "",
        sha256 = "",
    )
}
