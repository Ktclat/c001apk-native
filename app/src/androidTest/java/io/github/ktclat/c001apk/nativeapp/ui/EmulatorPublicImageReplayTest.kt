// SPDX-License-Identifier: AGPL-3.0-only
// Exact production Coil factory/fetcher/decoders, but synthetic HTTP bytes only. Not live CDN recovery.
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import coil.ImageLoader
import io.github.ktclat.c001apk.nativeapp.diagnostics.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.feature.media.*
import io.github.ktclat.c001apk.nativeapp.settings.*
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
class EmulatorPublicImageReplayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val name = org.junit.rules.TestName()
    private val requests = CopyOnWriteArrayList<String>()
    private val denied = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private lateinit var client: OkHttpClient
    private lateinit var loader: ImageLoader
    private val rootUrl = "https://image.coolapk.com/qa-reconstructed-synthetic/"
    private val staticBytes get() = checkedAsset("static.png").first
    private fun checkedAsset(file: String): Pair<ByteArray, JSONObject> {
        val metadata = JSONObject(EmulatorEvidence.asset("media-replay/provenance.json").toString(Charsets.UTF_8)).getJSONArray("files")
        val entry = (0 until metadata.length()).map { metadata.getJSONObject(it) }.single { it.getString("file") == file }
        val bytes = EmulatorEvidence.asset("media-replay/$file")
        assertEquals(entry.getInt("bytes"), bytes.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        assertEquals(entry.getString("sha256"), hash)
        return bytes to entry
    }
    private fun transport(respond: (String, Int) -> Triple<Int, String, ByteArray>?) {
        client = publicImageClient().newBuilder().addInterceptor { chain ->
            val request = chain.request(); val url = request.url
            if (request.method != "GET" || url.scheme != "https" || url.host != "image.coolapk.com" || url.port != 443 ||
                url.query != null || url.fragment != null || !url.encodedPath.startsWith("/qa-reconstructed-synthetic/") ||
                request.headers.names().any { it.equals("Cookie", true) || it.equals("Authorization", true) || it.startsWith("X-App-", true) }) {
                denied += "${request.method} $url"; throw IOException("Synthetic media transport denied before sockets")
            }
            val key = url.encodedPath.substringAfterLast('/')
            val number = requests.count { it == url.toString() } + 1
            val answer = respond(key, number) ?: run { denied += "${request.method} $url"; throw IOException("Unallowlisted synthetic media") }
            requests += url.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(answer.first).message("synthetic")
                .header("Cache-Control", "no-store").body(answer.third.toResponseBody(answer.second.toMediaType())).build()
            // Never chain.proceed. Null networkResponse may be classified DISK by Coil; record it honestly.
        }.build()
        loader = buildPublicMediaImageLoader(compose.activity, client).newBuilder().memoryCache(null).diskCache(null).build()
    }
    private fun render(dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
        EmulatorEvidence.configureWindow(compose.activity, dark)
        compose.setContent { MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
            CompositionLocalProvider(LocalMediaImageLoader provides loader,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding()) { content() } }
            }
        } }
    }
    private fun carousel(url: String) = ContentItem("fixture", "card", entityTemplate = HomeCardTemplate.CAROUSEL.wire,
        children = listOf(ContentItem("entry", "url", targetUrl = "/feed/9899", cardFields = HomeCardFields(picture = url, title = "合成轮播入口"))))
    private fun awaitTag(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun awaitState(tag: String, state: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription) == state
    }
    private fun capture(stage: String) { compose.waitForIdle(); EmulatorEvidence.capture("media-${name.methodName}-$stage",
        compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString()); ledger() }
    private fun ledger() = EmulatorEvidence.text("media-${name.methodName}-requests.txt", requests.joinToString("\n") +
        "\nDenied=$denied\nOpened=$opened\nActualCounters=${PublicImageDiagnostics.counters.snapshot().joinToString { "${it.first.text()} count=${it.second}" }}\n" +
        "Scope=synthetic transport production decoder only; DISK metadata is not a wire/network/cache recovery claim\n")
    @After fun close() {
        val errors = mutableListOf<Throwable>(); fun attempt(block: () -> Unit) { try { block() } catch (e: Throwable) { errors += e } }
        attempt { ledger() }; if (::loader.isInitialized) attempt { loader.shutdown() }
        if (::client.isInitialized) { attempt { client.dispatcher.cancelAll() }; attempt { client.connectionPool.evictAll() }; attempt { client.dispatcher.executorService.shutdownNow() } }
        attempt { assertTrue("No unexpected media requests: $denied", denied.isEmpty()) }
        val primary = EmulatorPrimaryFailure.current
        if (primary != null) errors.forEach(primary::addSuppressed)
        else if (errors.isNotEmpty()) { errors.drop(1).forEach(errors.first()::addSuppressed); throw errors.first() }
    }
    @Test fun actualCarouselManualRetryDoesNotOpenItsParentAndDiagnosticsAreBounded() = recordPrimaryTestFailure(name.methodName) {
        transport { file, number -> if (file == "carousel.png") Triple(200, "image/png", if (number == 1) byteArrayOf(1,2,3) else staticBytes) else null }
        var diagnostics by mutableStateOf(false)
        render { if (diagnostics) NativeDiagnosticsScreen(false, SystemAccessibility(false, false), { diagnostics = false })
            else { HomeTemplateCard(carousel(rootUrl + "carousel.png"), HomeCardTemplate.CAROUSEL, { opened += it }, {})
                TextButton(onClick = { diagnostics = true }) { Text("合成本地诊断") } } }
        awaitTag("public-image-retry"); capture("decode-error")
        compose.onNodeWithTag("public-image-retry").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) {
            requests.size == 2 && compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "图片已加载")
                and hasAnyAncestor(hasTestTag("home-entry:fixture:0")), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("home-entry:fixture:0").assertIsDisplayed(); assertTrue(opened.isEmpty())
        capture("retry-loaded")
        assertEquals(listOf(rootUrl+"carousel.png",rootUrl+"carousel.png"), requests.toList())
        compose.onNodeWithText("合成本地诊断").performClick(); awaitTag("native-diagnostics"); capture("diagnostics-scope")
        compose.onNodeWithText("公共图片加载（本次进程）").performScrollTo()
        compose.onNode(hasText("host=IMAGE_CDN", substring = true)).performScrollTo().assertIsDisplayed()
        capture("diagnostics-visible-groups")
        val counters = PublicImageDiagnostics.counters.snapshot()
        assertTrue(counters.size <= 16); assertTrue(counters.all { it.second in 1..9999 })
    }
    @Test fun blockedBannerStopsAfterOneRetryAndGallery404StaysExplicit() = recordPrimaryTestFailure(name.methodName) {
        transport { file, _ -> when(file) {
            "blocked.png" -> Triple(567,"text/html","Synthetic denied".toByteArray())
            "missing.png.s.jpg" -> Triple(200,"image/png",staticBytes)
            "missing.png" -> Triple(404,"text/html","Synthetic missing".toByteArray())
            else -> null
        } }
        var gallery by mutableStateOf(false)
        render { if (gallery) MediaGrid(listOf(rootUrl+"missing.png"), loader)
            else { HomeTemplateCard(carousel(rootUrl+"blocked.png"),HomeCardTemplate.CAROUSEL,{ opened += it },{})
                TextButton(onClick = { gallery = true }) { Text("合成404图库") } } }
        awaitTag("public-image-retry"); compose.onNodeWithText("服务器返回 HTTP 567").assertIsDisplayed(); capture("blocked-initial")
        compose.onNodeWithTag("public-image-retry").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("已重试").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("public-image-retry").assertDoesNotExist(); assertTrue(opened.isEmpty()); capture("blocked-terminal")
        assertEquals(2, requests.size)
        compose.onNodeWithText("合成404图库").performClick(); awaitState("media-grid-image-0", "图片 1，已加载")
        compose.onNodeWithTag("media-cell-0").performClick(); awaitTag("media-viewer-retry-0")
        compose.onNodeWithText("服务器返回 HTTP 404").assertIsDisplayed(); capture("gallery404")
        val before = requests.size; compose.waitForIdle(); assertEquals(before, requests.size)
        compose.onNodeWithTag("media-viewer-retry-0").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { compose.onRoot().fetchSemanticsNode(); requests.size == before + 1 }
        awaitTag("media-viewer-retry-0"); capture("gallery404-after-explicit-retry")
        assertEquals(listOf(rootUrl+"blocked.png",rootUrl+"blocked.png",rootUrl+"missing.png.s.jpg",rootUrl+"missing.png",rootUrl+"missing.png"),requests.toList())
    }
    @Test fun darkLargeFontRetryIsOnlyShownWhenGlyphAndTouchBoundsFit() = recordPrimaryTestFailure(name.methodName) {
        transport { file,_ -> if(file == "small.png" || file == "large.png") Triple(567,"text/html","Synthetic denied".toByteArray()) else null }
        render(dark=true,scale=2.4f) {
            Text("合成短容器与足够空间",Modifier.testTag("fixture-label"))
            PublicImage(rootUrl+"small.png",modifier=Modifier.size(266.dp,120.dp).testTag("small-image"),allowRetry=true)
            PublicImage(rootUrl+"large.png",modifier=Modifier.size(266.dp,220.dp).testTag("large-image"),allowRetry=true)
        }
        awaitState("small-image","图片加载失败"); awaitState("large-image","图片加载失败")
        compose.onAllNodes(hasTestTag("public-image-retry") and hasAnyAncestor(hasTestTag("small-image"))).assertCountEquals(0)
        val retry=compose.onNode(hasTestTag("public-image-retry") and hasAnyAncestor(hasTestTag("large-image"))).assertIsDisplayed()
        val bounds=retry.getUnclippedBoundsInRoot();assertTrue(bounds.width.value>=48f&&bounds.height.value>=48f)
        val parent=compose.onNodeWithTag("large-image").getUnclippedBoundsInRoot();assertTrue(bounds.top>=parent.top&&bounds.bottom<=parent.bottom)
        val label = compose.onNodeWithText("重试一次").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
        val layout = layouts.single()
        assertFalse(layout.didOverflowHeight)
        assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        val labelBounds = label.getUnclippedBoundsInRoot()
        assertTrue(labelBounds.left >= bounds.left && labelBounds.right <= bounds.right && labelBounds.top >= bounds.top && labelBounds.bottom <= bounds.bottom)
        capture("dark-large-retry-contained")
        retry.performClick(); compose.waitUntil(30_000){compose.onAllNodesWithText("已重试").fetchSemanticsNodes().size==1}
        capture("dark-large-terminal");assertEquals(3,requests.size)
    }
    private fun successCounts() = PublicImageDiagnostics.counters.snapshot().filter { it.first.host==PublicImageHost.IMAGE_CDN && it.first.failure==null && it.first.contentType==PublicImageType.IMAGE }.associate { it.first to it.second }
    private fun delta(before:Map<PublicImageObservation,Int>):Map<PublicImageObservation,Int> = successCounts().mapValues { (key,value) -> value-(before[key]?:0) }.also { check(it.values.all { n->n>=0 }) }
    private fun nodes(root:AccessibilityNodeInfo):List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo,depth:Int){ if(depth>30||result.size>=2000)return;result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let{walk(it,depth+1)} }
        walk(root,0);return result
    }
    private fun enabledAction(root:AccessibilityNodeInfo,text:String):AccessibilityNodeInfo? {
        val label=nodes(root).singleOrNull{it.text?.toString()==text&&it.isVisibleToUser}?:return null
        var current:AccessibilityNodeInfo?=label
        repeat(4){val n=current?:return null;if(n.isClickable&&n.isEnabled&&n.isVisibleToUser)return n;current=n.parent};return null
    }
    @Test fun productionGifDecoderAnimatesOriginalWhileGridUsesStaticThumbnail() = recordPrimaryTestFailure(name.methodName) {
        val (gif,metadata)=checkedAsset("animated.gif");val(_,staticMetadata)=checkedAsset("static.png")
        assertTrue(metadata.getInt("frameCount")>1);assertEquals(1,staticMetadata.getInt("frameCount"))
        EmulatorEvidence.text("gif-source-provenance.json",metadata.toString(2))
        transport { file,_ -> when(file){"animated.gif.s.jpg"->Triple(200,"image/png",staticBytes);"animated.gif"->Triple(200,"image/gif",gif);else->null} }
        render { MediaGrid(listOf(rootUrl+"animated.gif"),loader) }
        awaitState("media-grid-image-0","图片 1，已加载");capture("static-grid")
        assertEquals(listOf(rootUrl+"animated.gif.s.jpg"),requests.toList())
        val before=successCounts();val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        val grid=compose.onNodeWithTag("media-cell-0").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        // Fresh observed static bounds, OS-injected click; do not query Compose semantics while GIF animates.
        assertTrue(device.click(grid.center.x.toInt(),grid.center.y.toInt()))
        val started=SystemClock.uptimeMillis();var loaded:EmulatorAnimatedEvidence.LoadedOccurrence?=null
        fun observe():EmulatorAnimatedEvidence.LoadedOccurrence? {
            val root=instrumentation.uiAutomation.rootInActiveWindow?:return null
            if(root.packageName?.toString()!="io.github.ktclat.c001apk.nativeapp")return null
            if(requests.toList()!=listOf(rootUrl+"animated.gif.s.jpg",rootUrl+"animated.gif")||denied.isNotEmpty())return null
            val changes=delta(before);if(changes.values.sum()!=1)return null
            if(enabledAction(root,"放大")==null||enabledAction(root,"适合屏幕")==null)return null
            val image=nodes(root).singleOrNull{it.contentDescription?.toString()=="图片 1，双指缩放"&&it.isVisibleToUser}?:return null
            val bounds=Rect();image.getBoundsInScreen(bounds);if(bounds.isEmpty)return null
            return EmulatorAnimatedEvidence.LoadedOccurrence(FrameIdentity(device.displayWidth,device.displayHeight,root.windowId,
                PixelBounds(bounds.left,bounds.top,bounds.right,bounds.bottom)),rootUrl+"animated.gif",changes.toString())
        }
        while(SystemClock.uptimeMillis()-started<15_000&&loaded==null){
            EmulatorEvidence.text("gif-navigation-stage.txt","phase=open-commit beforeClock=${compose.mainClock.currentTime} uptime=${SystemClock.uptimeMillis()}")
            compose.mainClock.advanceTimeByFrame();SystemClock.sleep(100);loaded=observe()
        }
        check(loaded!=null){"Original gallery not positively loaded; no temporal measurement attempted"}
        val clock=compose.mainClock.currentTime
        EmulatorEvidence.text("gif-before-temporal.txt","clock=$clock\nloaded=$loaded\nactualSourceDelta=${delta(before)}")
        EmulatorAnimatedEvidence.capture("gif",rootUrl+"animated.gif",metadata.getInt("frameCount"),::observe)
        assertEquals("No test clock pumping during temporal evidence",clock,compose.mainClock.currentTime)
        ledger()
        val root=requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val back=requireNotNull(enabledAction(root,"返回"));val backBounds=Rect();back.getBoundsInScreen(backBounds)
        assertTrue(device.click(backBounds.centerX(),backBounds.centerY()))
        val returnStart=SystemClock.uptimeMillis();var returned=false
        while(SystemClock.uptimeMillis()-returnStart<15_000&&!returned){
            compose.mainClock.advanceTimeByFrame();SystemClock.sleep(100)
            val current=instrumentation.uiAutomation.rootInActiveWindow
            returned=current!=null && current.packageName?.toString()=="io.github.ktclat.c001apk.nativeapp" && nodes(current).any{it.contentDescription?.toString()=="查看原图 1"&&it.isVisibleToUser}
        }
        check(returned){"Normal Back did not return to static grid"}
        awaitState("media-grid-image-0","图片 1，已加载");capture("after-normal-back-static")
        assertEquals(listOf(rootUrl+"animated.gif.s.jpg",rootUrl+"animated.gif"),requests.toList())
    }
}
