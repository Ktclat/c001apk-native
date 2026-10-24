// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PlatformTest {
    @Test fun linksRestrictAuthoritiesAndStripQueries() {
        assertEquals(AppLink.Internal("feed:42"), parseAppLink("https://www.coolapk.com/feed/42?share=1#x"))
        assertEquals(AppLink.Internal("event:4"), parseAppLink("coolmarket://event/4"))
        assertEquals(AppLink.Internal("user:测试"), parseAppLink("/u/%E6%B5%8B%E8%AF%95"))
        assertEquals(AppLink.Internal("coolpic:摄影"), parseAppLink("/t/摄影?type=8"))
        assertTrue(parseAppLink("https://coolapk.com.evil.test/feed/1") is AppLink.External)
        assertEquals(AppLink.Unsupported, parseAppLink("javascript:alert(1)"))
        assertEquals(AppLink.Unsupported, parseAppLink("https://www.coolapk.com@evil.test/feed/1"))
        assertEquals(AppLink.Unsupported, parseAppLink("/feed/1%2F2"))
        assertEquals(AppLink.Unsupported, parseAppLink("//evil.test/feed/1"))
    }
    @Test fun publicWebNeverLoadsUntrustedSchemesOrUserInfo() {
        assertTrue(isSafePublicWebUrl("https://example.com/path?q=a"))
        for (url in listOf("http://example.com", "file:///etc/passwd", "content://x", "intent://example.com", "javascript:alert(1)", "https://user:secret@example.com", "https://example.com:8443")) assertFalse(url, isSafePublicWebUrl(url))
    }
    @Test fun downloadPreviewRejectsPrivateAndAmbiguousTargets() {
        assertTrue(isReviewableApkBrowserUrl("https://downloads.example.com/file.apk?signature=fixture"))
        for (url in listOf("https://127.0.0.1/a", "https://[::1]/a", "https://10.0.0.1/a", "https://0x7f.0.0.1/a", "https://0x7f.0x0.0x0.0x1/a", "https://2130706433/a", "https://host.local./a", "https://host.internal/a", "https://user:secret@example.com/a", "http://example.com/a", "https://example.com:8443/a")) assertFalse(url, isReviewableApkBrowserUrl(url))
    }
    @Test fun releaseVersionsAreNumericStableAndBounded() {
        assertTrue(ReleaseVersion.parse("v1.10.0")!! > ReleaseVersion.parse("1.9.9")!!)
        assertEquals(ReleaseVersion(1,2,3), ReleaseVersion.parse("1.2.3-release+42"))
        listOf("01.2.3", "1.2", "1.2.3-beta", "999999999999999999999.0.0").forEach { assertNull(ReleaseVersion.parse(it)) }
    }
    private fun release(repo: String = "c001apk-native", assetRepo: String = repo) = Json.parseToJsonElement("""{"draft":false,"prerelease":false,"tag_name":"v1.2.3","html_url":"https://github.com/Ktclat/$repo/releases/tag/v1.2.3","body":"Notes","assets":[{"name":"native.apk","state":"uploaded","size":100,"browser_download_url":"https://github.com/Ktclat/$assetRepo/releases/download/v1.2.3/native.apk"}]}""").jsonObject
    @Test fun nativeUpdaterNeverAcceptsFlutterRepository() {
        assertEquals(1, parseNativeRelease(release()).downloads.size)
        assertTrue(parseNativeRelease(release(assetRepo = "c001apk")).downloads.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { parseNativeRelease(release(repo = "c001apk")) }
    }
    @Test fun releasesRejectLookalikeHostsAndReleaseAssetQueries() {
        val valid = release().toString()
        for (host in listOf("github.com.evil.test", "evil.test")) {
            assertThrows(IllegalArgumentException::class.java) { parseNativeRelease(Json.parseToJsonElement(valid.replace("github.com", host)).jsonObject) }
        }
        val query = valid.replace("native.apk\"}", "native.apk?token=x\"}")
        assertTrue(parseNativeRelease(Json.parseToJsonElement(query).jsonObject).downloads.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { parseNativeRelease(Json.parseToJsonElement(valid.replace("\"prerelease\":false", "\"prerelease\":true")).jsonObject) }
    }
    @Test fun sortingHasDeterministicTieBreakers() {
        val apps = listOf(InstalledApp("b", "Same", lastUpdateTime = 1), InstalledApp("a", "same", lastUpdateTime = 2))
        assertEquals(listOf("a", "b"), sortInstalledApps(apps, AppSort.NAME).map { it.packageName })
        assertEquals(listOf("a", "b"), sortInstalledApps(apps, AppSort.UPDATED).map { it.packageName })
    }
}
