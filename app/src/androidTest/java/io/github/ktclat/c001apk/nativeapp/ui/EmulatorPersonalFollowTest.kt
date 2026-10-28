// SPDX-License-Identifier: AGPL-3.0-only
// New actual-Android companion to CI62's failing readiness case; NOT its causal fix.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ktclat.c001apk.nativeapp.feature.media.buildPublicMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.publicImageClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class EmulatorPersonalFollowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: EmulatorReadOnlyFixture
    private lateinit var images: coil.ImageLoader
    private lateinit var imageClient: OkHttpClient
    private val imageRequests = CopyOnWriteArrayList<String>()
    private val imageDenied = CopyOnWriteArrayList<String>()
    private fun asset(name: String): ByteArray {
        val bytes = EmulatorEvidence.asset("personal-follow/$name")
        val provenance = JSONObject(EmulatorEvidence.asset("personal-follow/provenance.json").toString(Charsets.UTF_8))
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(provenance.getJSONObject(name).getString("sha256"), actual)
        return bytes
    }
    @After fun close() {
        val failures = mutableListOf<Throwable>()
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { failures += error } }
        cleanup { EmulatorEvidence.text("personal-follow-image-requests.txt", imageRequests.joinToString("\n") +
            "\nDenied=$imageDenied\nProduction decoder + synthetic Call.Factory; not default network or live-avatar proof\n") }
        if (::fixture.isInitialized) cleanup { fixture.close() }
        if (::images.isInitialized) cleanup { images.shutdown() }
        if (::imageClient.isInitialized) {
            cleanup { imageClient.dispatcher.cancelAll() }; cleanup { imageClient.connectionPool.evictAll() }
            cleanup { imageClient.dispatcher.executorService.shutdownNow() }
        }
        cleanup { assertTrue(imageDenied.isEmpty()) }
        val primary = EmulatorPrimaryFailure.current
        if (primary != null) failures.forEach(primary::addSuppressed)
        else if (failures.isNotEmpty()) { failures.drop(1).forEach(failures.first()::addSuppressed); throw failures.first() }
    }
    @Test fun actualPersonalFollowUserEntityAndBackStayReadOnly() = recordPrimaryTestFailure("personal-follow") {
        val source = asset("personal-follow.json").toString(Charsets.UTF_8)
        val icon = asset("icon-5.png")
        imageClient = publicImageClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "GET" || request.url.toString() != "https://fixture.invalid/icon-5.png" ||
                request.headers.names().any { it.equals("Cookie", true) || it.equals("Authorization", true) || it.startsWith("X-App-", true) }) {
                imageDenied += "UNEXPECTED_SYNTHETIC_IMAGE_REQUEST"
                throw IOException("Nonallowlisted image denied before any socket")
            }
            imageRequests += "GET https://fixture.invalid/icon-5.png"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .header("Cache-Control", "no-store").body(icon.toResponseBody("image/png".toMediaType())).build()
            // Never chain.proceed; Coil may classify synthetic response metadata DISK.
        }.build()
        images = buildPublicMediaImageLoader(compose.activity, imageClient).newBuilder().memoryCache(null).diskCache(null).build()
        fixture = EmulatorReadOnlyFixture(compose, "personal-follow") { request -> when (request.url.encodedPath) {
            "/v6/user/followList" -> if (request.url.queryParameter("uid") == "123" && request.url.queryParameter("page") == "1") source else null
            "/v6/user/space" -> if (request.url.queryParameter("uid") == "1002") """{"data":{"uid":"1002","username":"合成联系人"}}""" else null
            "/v6/user/feedList" -> if (request.url.queryParameter("uid") == "1002") """{"data":[{"id":"9301","entityType":"feed","username":"合成作者","message":"已打开联系人"}]}""" else null
            else -> null
        } }
        fixture.render(initialRoute = "mylist:FOLLOW", authenticatedUid = "123", mediaImageLoader = images)
        // Keep the original case's 15s initial bound; this is a separate platform execution.
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("开源观察员").fetchSemanticsNodes().size == 1 }
        } catch (failure: Throwable) {
            // Preserve the exact primary before any UI diagnostics / rule teardown.
            runCatching { EmulatorEvidence.text("personal-follow-readiness-primary.txt", failure.stackTraceToString()) }
            runCatching { fixture.capture("readiness-failed-original") }
            throw failure
        }
        compose.onNodeWithText("开源观察员").assertIsDisplayed()
        compose.onNodeWithText("120关注").assertIsDisplayed(); compose.onNodeWithText("980粉丝").assertIsDisplayed()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("entity-icon:contacts:1002", useUnmergedTree = true).fetchSemanticsNodes()
                .singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription) == "头像已加载"
        }
        compose.onNodeWithTag("entity-icon:contacts:1002", useUnmergedTree = true).assertIsDisplayed()
        fixture.capture("loaded-user-entity")
        compose.onNodeWithTag("entity-card:contacts:1002", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click(center) }
        compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); fixture.model.states.value["user:1002"]?.let { it.loaded && !it.loading } == true }
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("feed:9301"))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("已打开联系人").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("已打开联系人").assertIsDisplayed(); fixture.capture("user1002-destination")
        fixture.back()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("开源观察员").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("entity-card:contacts:1002", useUnmergedTree = true).assertIsDisplayed()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("entity-icon:contacts:1002", useUnmergedTree = true).fetchSemanticsNodes()
                .singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription) == "头像已加载"
        }
        compose.onNodeWithTag("entity-icon:contacts:1002", useUnmergedTree = true).assertIsDisplayed()
        fixture.capture("back-personal-follow")
        val followReads = fixture.count("/v6/user/followList")
        assertTrue("Back may reuse or refetch first page; no cache-retention claim", followReads in 1..2)
        assertEquals(1, fixture.count("/v6/user/space")); assertEquals(1, fixture.count("/v6/user/feedList"))
        assertEquals(followReads + 2, fixture.requests.size)
        assertEquals("123", fixture.store.state.value.activeId)
        assertTrue(imageRequests.isNotEmpty()); assertTrue(imageDenied.isEmpty())
        EmulatorEvidence.text("personal-follow-contract.txt", "route=mylist:FOLLOW\nuser=1002\nfollowReads=$followReads\nprofileReads=1\nfeedReads=1\nBack may refetch, not a cache-retention claim\nSynthetic data only; CI62 timeout cause remains unresolved\n")
    }
}
