// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil.imageLoader
import io.github.ktclat.c001apk.nativeapp.data.ApiDecoder
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** Deterministic transport regression, NOT real-CDN acceptance. No Coil interceptor or fake bitmap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicImageLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun applicationRegistersOneAnonymousPublicLoader() {
        val application = compose.activity.application
        assertSame(application.imageLoader, publicMediaImageLoader(application))
        val client = publicImageClient()
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertFalse(client.followSslRedirects)
    }

    @Test fun decodedLegacyUrlReachesRealCoilHttpFetcherAndFailureIsNotBlankOrRetried() {
        val requests = CopyOnWriteArrayList<Request>()
        val bytes = checkNotNull(javaClass.getResourceAsStream("/gallery/original-landscape.png")).use { it.readBytes() }
        val client = publicImageClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            check(request.url.isHttps && request.url.host == "avatar.coolapk.com")
            check(request.headers.names().none { it.equals("Cookie", true) || it.equals("Authorization", true) || it.startsWith("X-App-", true) })
            val failed = request.url.encodedPath == "/blocked.jpg"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (failed) 567 else 200).message("Transport regression")
                .body(if (failed) "RestrictedAccess".toResponseBody("text/html".toMediaType())
                    else bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = buildPublicMediaImageLoader(compose.activity, client).newBuilder().diskCache(null).memoryCache(null).build()
        fun decoded(path: String) = ApiDecoder.item(Json.parseToJsonElement(
            """{"entityType":"feed","userAvatar":"http://avatar.coolapk.com/$path"}""")).avatarUrl
        val url = mutableStateOf(decoded("blocked.jpg"))
        try {
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides loader) {
                PublicImage(url.value, modifier = Modifier.size(48.dp).testTag("public-image"), avatar = true)
            } } }
            awaitPhase("头像加载失败")
            compose.onNodeWithContentDescription("头像加载失败", useUnmergedTree = true).assertExists()
            compose.waitForIdle()
            assertEquals("No repeated request after a terminal error", 1, requests.size)
            compose.runOnIdle { url.value = decoded("working.png") }
            awaitPhase("头像已加载")
            assertEquals(2, requests.size)
            assertEquals("https://avatar.coolapk.com/working.png", requests.last().url.toString())
        } finally {
            loader.shutdown()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun awaitPhase(label: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("public-image", useUnmergedTree = true).fetchSemanticsNodes().any {
            it.config.contains(SemanticsProperties.StateDescription) && it.config[SemanticsProperties.StateDescription] == label
        }
    }
}
