// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import io.github.ktclat.c001apk.nativeapp.domain.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class InstalledAppUpdatesTest {
    private val fake = InstalledApp("org.example.fixture", "DO NOT TRANSMIT LABEL", "v1", 10, 999999)
    @Test fun encodesOnlyExplicitSelectedPackageAndVersion() {
        val decoded = String(Base64.getDecoder().decode(encodeSelectedPackages(listOf(fake))), Charsets.UTF_8)
        assertEquals("""{"org.example.fixture":"0,10,0"}""", decoded)
        assertFalse(decoded.contains(fake.label)); assertFalse(decoded.contains("999999"))
        assertThrows(IllegalArgumentException::class.java) { encodeSelectedPackages(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { encodeSelectedPackages(listOf(fake, fake)) }
        assertThrows(IllegalArgumentException::class.java) { encodeSelectedPackages(listOf(fake.copy(packageName = "../../bad"))) }
    }
    @Test fun constructionDoesNotSendInventoryAndExplicitCheckMapsVersions() = runTest {
        var calls = 0
        val repo = InstalledAppUpdatesRepository(PackageUpdateTransport { encoded ->
            calls++; assertEquals(encodeSelectedPackages(listOf(fake)), encoded)
            ApiResult.Success(Json.parseToJsonElement("""[{"id":42,"packageName":"org.example.fixture","apkversioncode":11,"apkversionname":"v2","title":"Fixture","changelog":"Fixed"}]"""))
        })
        assertEquals(0, calls)
        val result = repo.check(listOf(fake)) as ApiResult.Success
        assertEquals(1, calls)
        assertEquals(11L, result.value.single().versionCode)
        assertEquals(10L, result.value.single().installedVersionCode)
    }
    @Test fun rejectsUnselectedPackageAndMalformedResponse() = runTest {
        for (data in listOf("{}", """[{"id":42,"packageName":"org.example.unselected","apkversioncode":11}]""", """[{"id":"invalid","packageName":"org.example.fixture","apkversioncode":11}]""")) {
            val repo = InstalledAppUpdatesRepository(PackageUpdateTransport { ApiResult.Success(Json.parseToJsonElement(data)) })
            assertTrue(repo.check(listOf(fake)) is ApiResult.Failure)
        }
    }
    @Test fun emptySelectionNeverHitsNetwork() = runTest {
        var calls = 0
        val repo = InstalledAppUpdatesRepository(PackageUpdateTransport { calls++; ApiResult.Success(JsonArray(emptyList())) })
        assertTrue(repo.check(emptyList()) is ApiResult.Failure)
        assertEquals(0, calls)
    }
}
