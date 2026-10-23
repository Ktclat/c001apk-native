// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.LocalLoadState
import io.github.ktclat.c001apk.nativeapp.local.loadStates
import io.github.ktclat.c001apk.nativeapp.settings.AppSettings
import io.github.ktclat.c001apk.nativeapp.social.OssImageUploader
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Actual NativeApp presentation with exact fractional viewport mapping and shared original PNGs.
 * Both API and OSS use in-process interceptors that consume real bodies and NEVER proceed.
 * Native fixed theme and pinned Noto fonts. HostedScreen remains deliberate; this is NOT source-modal or physical-device parity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w369dp-h822dp-520dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeImageComposerPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Captured(val request: Request, val bytes: ByteArray)
    private enum class FinalResponse { CONFIRMED, UNCERTAIN, CHALLENGE }
    private val apiCalls = CopyOnWriteArrayList<Captured>()
    private val puts = CopyOnWriteArrayList<Captured>()
    private val order = CopyOnWriteArrayList<String>()
    private val interceptorFailures = CopyOnWriteArrayList<Throwable>()
    private val clients = mutableListOf<OkHttpClient>()
    private val factories = AtomicInteger()
    private val homeReads = AtomicInteger()
    private val detailReads = AtomicInteger()
    private val replyReads = AtomicInteger()
    private val completedPuts = AtomicInteger()
    @Volatile private var firstPutGate: CountDownLatch? = null
    @Volatile private var failPut = 0
    @Volatile private var finalResponse = FinalResponse.CONFIRMED
    private lateinit var accounts: AccountRuntime
    private lateinit var picker: PickerRegistry
    private lateinit var provider: ImageProvider
    private lateinit var sourceDirectory: File
    private lateinit var model: BrowseViewModel

    private lateinit var imageLoader: coil.ImageLoader
    private val decodedFiles = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    private val unexpectedImages = CopyOnWriteArrayList<String>()
    private val allowedFixtureBytes = CopyOnWriteArrayList<ByteArray>().apply { addAll(IMAGE_BYTES) }
    private var viewportWidth = 1200
    private var viewportHeight = 2670
    private var chosenFontScale by mutableFloatStateOf(1f)

    @Before fun setup() {
        val context = compose.activity.applicationContext
        assertEquals("Same portrait original as Flutter", "45b09d6ec2b55b33e2d4acedbe4e5684b62c302fc66269548e1133a854558e03", IMAGE_BYTES[0].toByteString().sha256().hex())
        assertEquals("Same landscape original as Flutter", "b27d4467a37da97ec1c66debe001686d16d3c2a08cd788a52c1211da70199191", IMAGE_BYTES[1].toByteString().sha256().hex())
        imageLoader = coil.ImageLoader.Builder(context).components {
            add(object : coil.intercept.Interceptor {
                override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
                    val file = chain.request.data as? File
                    if (file == null || !file.isFile || allowedFixtureBytes.none { it.contentEquals(file.readBytes()) }) {
                        unexpectedImages.add(chain.request.data.toString())
                        return coil.request.ErrorResult(null, chain.request, IllegalArgumentException("Not a shared local PNG fixture"))
                    }
                    val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
                    decodedFiles[file.path] = bitmap.width to bitmap.height
                    // Decode the exact selected private-file bytes; never proceed to a network fetcher.
                    return coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(context.resources, bitmap),
                        chain.request, coil.decode.DataSource.MEMORY_CACHE)
                }
            })
        }.build()
        sourceDirectory = File(context.cacheDir, "synthetic-image-picker").apply { mkdirs() }
        provider = ImageProvider(sourceDirectory).apply {
            attachInfo(context, ProviderInfo().also { it.authority = AUTHORITY })
        }
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        picker = PickerRegistry(context)
        val store = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        })
        store.saveAuthenticated("1", "synthetic", "synthetic-device", listOf(Cookie.Builder()
            .name("token").value("synthetic-not-a-real-token").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot())
        val blocked = OkHttpClient.Builder().addInterceptor {
            throw IOException("Unexpected request outside the synthetic image pipeline; networking is disabled")
        }.build()
        val api = OkHttpClient.Builder().addInterceptor { chain ->
            guardedResponse(chain.request()) { request ->
                check(request.url.isHttps && request.url.host == "api.coolapk.com" && request.url.port == 443)
                check(request.method == "POST")
                check(request.header("Cookie")?.contains("synthetic-not-a-real-token") == true)
                val capture = capture(request)
                apiCalls.add(capture)
                when (request.url.encodedPath) {
                    PREPARE -> {
                        order.add("prepare")
                        response(request, prepareResponse(request))
                    }
                    CREATE, REPLY -> {
                        order.add("publish")
                        when (finalResponse) {
                            FinalResponse.CONFIRMED -> response(request, """{"data":{"id":777}}""")
                            FinalResponse.UNCERTAIN -> response(request, "", 503)
                            FinalResponse.CHALLENGE -> response(request,
                                """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}""")
                        }
                    }
                    else -> error("Unexpected API path: ${request.url.encodedPath}")
                }
            }
            // Never chain.proceed, including a path that the fixture does not understand.
        }.build()
        val oss = OkHttpClient.Builder().addInterceptor { chain ->
            guardedResponse(chain.request()) { request ->
                check(request.url.isHttps && request.url.host == "synthetic-bucket.oss-cn-shenzhen.aliyuncs.com")
                check(request.method == "PUT" && request.url.encodedPath.startsWith("/feed/synthetic/"))
                check(request.header("Cookie") == null && request.header("X-App-Token") == null)
                check(request.header("Authorization")?.startsWith("OSS synthetic-access:") == true)
                val capture = capture(request) // Exercise the production file RequestBody, not a fake success hook.
                puts.add(capture)
                val index = puts.size
                order.add("put:$index")
                if (index == 1 && firstPutGate?.await(30, TimeUnit.SECONDS) == false)
                    throw IOException("Synthetic first PUT was not released")
                completedPuts.incrementAndGet()
                response(request, "", if (failPut == index) 503 else 200)
            }
            // Never chain.proceed: even the official-looking synthetic grant cannot reach a socket.
        }.build()
        clients.addAll(listOf(blocked, api, oss))
        val social = SocialRepository(store, { snapshot ->
            factories.incrementAndGet()
            // Use the fixed official origin with synthetic cookies so actual challenge scoping is exercised.
            CoolapkHttpTransport(client = api, requestSession = { store.requestSessionFor(snapshot) })
        }, OssImageUploader(oss))
        val login = HttpLoginTransport(blocked)
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"),
            HttpCoolapkRepository(client = blocked), social)
    }

    @After fun cleanup() {
        firstPutGate?.countDown()
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        if (::imageLoader.isInitialized) imageLoader.shutdown()
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        if (::sourceDirectory.isInitialized) sourceDirectory.deleteRecursively()
        assertTrue("Synthetic interceptor failed: $interceptorFailures", interceptorFailures.isEmpty())
        assertTrue("Unexpected image data: $unexpectedImages", unexpectedImages.isEmpty())
    }

    private fun guardedResponse(request: Request, block: (Request) -> Response): Response = try {
        block(request)
    } catch (failure: Throwable) {
        interceptorFailures.add(failure)
        throw IOException("Synthetic interception failed closed", failure)
    }
    private fun capture(request: Request): Captured {
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        return Captured(request, buffer.readByteArray())
    }
    private fun response(request: Request, text: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .body(text.toResponseBody()).build()

    private fun multipart(request: Request): Map<String, String> {
        val body = request.body as MultipartBody
        val fields = body.parts.associate { part ->
            val disposition = requireNotNull(part.headers?.get("Content-Disposition"))
            val name = requireNotNull(Regex("name=\"([^\"]+)\"").find(disposition)).groupValues[1]
            name to Buffer().also { part.body.writeTo(it) }.readUtf8()
        }
        assertEquals("No duplicate multipart fields", body.parts.size, fields.size)
        return fields
    }
    private fun finalForm(): Map<String, String> {
        val request = apiCalls.last().request
        val body = request.body as FormBody
        val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals("No duplicate form fields", body.size, fields.size)
        return fields
    }
    private fun prepareResponse(request: Request): String {
        val files = Json.parseToJsonElement(multipart(request).getValue("uploadFileList")).jsonArray
        val expires = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(System.currentTimeMillis() + 3_600_000))
        return buildJsonObject {
            put("data", buildJsonObject {
                put("uploadPrepareInfo", buildJsonObject {
                    put("endPoint", "oss-cn-shenzhen.aliyuncs.com")
                    put("bucket", "synthetic-bucket")
                    put("accessKeyId", "synthetic-access")
                    put("accessKeySecret", "synthetic-secret")
                    put("securityToken", "synthetic-token")
                    put("uploadImagePrefix", "https://image.coolapk.com")
                    put("expiration", expires)
                })
                // Reverse grant response order deliberately: publication must follow picker order, not server order.
                put("fileInfo", buildJsonArray { files.reversed().forEach { entry ->
                    val file = entry.jsonObject
                    add(buildJsonObject {
                        put("name", file.getValue("name"))
                        put("md5", file.getValue("md5"))
                        put("uploadFileName", "feed/synthetic/${file.getValue("name").jsonPrimitive.content}")
                    })
                } })
            })
        }.toString()
    }

    private fun showRoot(route: String? = "compose", widthPx: Int = 1200, heightPx: Int = 2670, fontScale: Float = 1f) {
        viewportWidth = widthPx; viewportHeight = heightPx; chosenFontScale = fontScale
        compose.runOnUiThread {
            org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(widthPx); setHeight(heightPx); setRealWidth(widthPx); setRealHeight(heightPx)
            }
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setLayout(widthPx, heightPx)
        }
        val container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false
        runBlocking { withTimeout(5_000) { container.local.loadStates().first { it is LocalLoadState.Ready } } }
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,
            arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "home" -> {
                    homeReads.incrementAndGet()
                    ApiResult.Success(Page(listOf(ContentItem("901", "feed", userName = "合成作者",
                        message = HOME_REFRESHED)), null))
                }
                "feedDetail" -> {
                    val read = detailReads.incrementAndGet()
                    ApiResult.Success(ContentItem("900", "feed", userName = "合成作者", userId = "2",
                        message = if (read == 1) FEED_ORIGINAL else FEED_REFRESHED, feed = FeedMetadata(replyCount = 1)))
                }
                "orderedReplies", "replies", "replyThread" -> {
                    val read = replyReads.incrementAndGet()
                    ApiResult.Success(Page(listOf(ContentItem("123", "feed_reply", userName = "合成评论者", userId = "3",
                        message = if (read == 1) "原评论" else "刷新后的评论",
                        replyInteraction = ReplyInteractionMetadata(ExplicitLikeState.NOT_LIKED, 0, 0))), null))
                }
                else -> throw AssertionError("Unexpected root browse call: ${method.name}")
            }
        } as CoolapkRepository
        model = BrowseViewModel(repository)
        val snapshot = accounts.store.state.value
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        val rootContent: @Composable () -> Unit = {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,
                LocalDensity provides Density(density, chosenFontScale), coil.compose.LocalImageLoader provides imageLoader,
                io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles provides io.github.ktclat.c001apk.nativeapp.settings.readingStyles(
                    io.github.ktclat.c001apk.nativeapp.settings.ReadingPreset.COMFORTABLE, io.github.ktclat.c001apk.nativeapp.settings.FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = io.github.ktclat.c001apk.nativeapp.settings.fixedColorScheme(0, false),
                    typography = io.github.ktclat.c001apk.nativeapp.settings.appTypography(io.github.ktclat.c001apk.nativeapp.settings.FontChoice.NOTO_SANS)) {
                    // This presentation fixture does not change the selected account mid-route.
                    NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false,
                        font = io.github.ktclat.c001apk.nativeapp.settings.FontChoice.NOTO_SANS, fontScale = chosenFontScale),
                        accounts, snapshot, initialRoute = route)
                }
            }
        }
        compose.setContent(rootContent)
        injectInsets()
        if (route == "compose") awaitVisible("composer", hasSetTextAction())
        else if (route?.startsWith("feed:") == true) awaitVisible("origin feed", hasTestTag("feed-detail-list")) {
            detailReads.get() == 1 && replyReads.get() == 1
        }
        else if (route == null) awaitVisible("actual home publish entry", hasTestTag("home-publish")) { homeReads.get() == 1 }
        assertViewport()
    }

    /** Always service actual semantics before checking worker counters; counter-only waits can starve Main work. */
    private fun awaitVisible(label: String, matcher: SemanticsMatcher, predicate: () -> Boolean = { true }) {
        try {
            compose.waitUntil(10_000) {
                val nodes = compose.onAllNodes(matcher)
                val visible = nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
                predicate() && visible
            }
        } catch (failure: ComposeTimeoutException) {
            val before = "api=${apiCalls.map { it.request.url.encodedPath }}, puts=${puts.size}, order=$order, " +
                "factories=${factories.get()}, home=${homeReads.get()}, details=${detailReads.get()}, replies=${replyReads.get()}, errors=$interceptorFailures"
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("tree unavailable")
            failure.addSuppressed(AssertionError("$label timed out: $before\n$tree"))
            throw failure
        }
        assertTrue("Synthetic interceptor failed: $interceptorFailures", interceptorFailures.isEmpty())
    }
    private fun typeDraft() = compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(DRAFT)
    private fun selectImages(vararg indices: Int) {
        compose.onNodeWithText("添加图片（", substring = true).performScrollTo().performClick()
        val launched = requireNotNull(picker.launchedIntent)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launched.action)
        // AndroidX 1.9.3 OpenMultipleDocuments sets */* plus MIME_TYPES; it does not add OPENABLE.
        assertEquals("*/*", launched.type)
        assertTrue(launched.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(setOf("image/jpeg", "image/png", "image/webp", "image/gif"),
            launched.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toSet())
        assertEquals(0, launched.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val uris = indices.map { provider.put(it.toString(), IMAGE_BYTES[it]) }
        compose.runOnIdle { picker.deliver(uris) }
        awaitVisible("selected attachments", hasText("添加图片（${indices.size}/9）"))
        compose.onNodeWithText("添加图片（${indices.size}/9）").assertIsEnabled()
        assertNoWrites()
    }
    private fun openConfirmation() {
        compose.onNodeWithText("检查并发送").performScrollTo().assertIsEnabled().performClick()
        awaitVisible("explicit confirmation", hasText("确认发送？"))
        assertNoWrites()
    }
    private fun confirm() = compose.onNodeWithText("确认发送", substring = false).assertIsEnabled().performClick()
    private fun assertNoWrites() {
        compose.waitForIdle()
        assertTrue(apiCalls.isEmpty())
        assertTrue(puts.isEmpty())
        assertEquals(0, factories.get())
    }
    private fun assertStableCounts(api: Int, uploads: Int, home: Int, detail: Int = 0, replies: Int = 0) {
        // Pump additional real frames and semantics after the terminal/returned UI, without sleeping or retrying work.
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        compose.waitForIdle()
        assertEquals(api, apiCalls.size)
        assertEquals(uploads, puts.size)
        assertEquals(home, homeReads.get())
        assertEquals(detail, detailReads.get())
        assertEquals(replies, replyReads.get())
    }
    private fun assertImageContract(expectedIndices: List<Int>) {
        assertEquals(listOf(PREPARE, apiCalls.last().request.url.encodedPath), apiCalls.map { it.request.url.encodedPath })
        val prepare = apiCalls.first().request
        assertEquals("POST", prepare.method)
        assertNull(prepare.url.query)
        assertEquals(MultipartBody.FORM, (prepare.body as MultipartBody).type)
        val fields = multipart(prepare)
        assertEquals(setOf("uploadBucket", "uploadDir", "is_anonymous", "uploadFileList"), fields.keys)
        assertEquals("image", fields["uploadBucket"])
        assertEquals("feed", fields["uploadDir"])
        assertEquals("0", fields["is_anonymous"])
        val files = Json.parseToJsonElement(fields.getValue("uploadFileList")).jsonArray.map { it.jsonObject }
        assertEquals(expectedIndices.size, files.size)
        assertEquals(expectedIndices.size, puts.size)
        val names = files.map { it.getValue("name").jsonPrimitive.content }
        assertEquals(names.size, names.distinct().size)
        files.forEachIndexed { index, file ->
            assertEquals(setOf("name", "resolution", "md5"), file.keys)
            val bytes = IMAGE_BYTES[expectedIndices[index]]
            val digest = bytes.toByteString().md5()
            assertEquals(digest.hex(), file.getValue("md5").jsonPrimitive.content)
            assertEquals(if (expectedIndices[index] == 0) "480x720" else "720x480", file.getValue("resolution").jsonPrimitive.content)
            assertTrue(names[index].endsWith(".png"))
            val upload = puts[index]
            assertEquals("/feed/synthetic/${names[index]}", upload.request.url.encodedPath)
            assertEquals("PUT", upload.request.method)
            assertEquals("image/png", upload.request.header("Content-Type"))
            assertEquals(digest.base64(), upload.request.header("Content-MD5"))
            assertNull(upload.request.header("Cookie"))
            assertNull(upload.request.header("X-App-Token"))
            assertArrayEquals(bytes, upload.bytes)
        }
        assertEquals(names.joinToString(",") { "https://image.coolapk.com/feed/synthetic/$it" }, finalForm()["pic"])
        assertEquals(listOf("prepare") + expectedIndices.indices.map { "put:${it + 1}" } + "publish", order.toList())
        assertEquals(1, factories.get())
    }

    private fun injectInsets(imeDp: Int = 0, systemDp: Int = 0) {
        compose.runOnUiThread {
            val density = compose.activity.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).toInt()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView,
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, px(systemDp), 0, 0))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, px(systemDp)))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, px(imeDp)))
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), imeDp > 0).build())
        }
        compose.waitForIdle()
    }
    private fun assertViewport() {
        val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
        assertEquals("Actual Android width", viewportWidth, view.width)
        assertEquals("Actual Android height", viewportHeight, view.height)
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(viewportWidth.toFloat(), bounds.width, .01f)
        assertEquals(viewportHeight.toFloat(), bounds.height, .01f)
        assertEquals(3.25f, compose.activity.resources.displayMetrics.density, .001f)
    }
    private fun awaitOriginalImages() {
        compose.onNodeWithTag("composer-images").performScrollTo()
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("composer-images").fetchSemanticsNode()
            decodedFiles.values.toSet().containsAll(listOf(480 to 720, 720 to 480))
        }
        compose.waitForIdle()
        assertTrue(unexpectedImages.isEmpty())
    }
    private fun assertOriginalAspectGeometry(widths: List<Float> = listOf(50f, 112.5f)) {
        compose.onNodeWithTag("composer-images").performScrollTo()
        widths.forEachIndexed { index, width ->
            val node = compose.onNodeWithTag("composer-image-${index + 1}", useUnmergedTree = true)
            node.assertIsDisplayed()
            val rect = node.getUnclippedBoundsInRoot()
            // Native raster layout rounds independently to physical pixels, so allow one pixel only.
            assertEquals(75f, (rect.bottom - rect.top).value, 1f / 3.25f)
            assertEquals(width, (rect.right - rect.left).value, 1f / 3.25f)
        }
    }
    private fun assertReachableTarget(tag: String) {
        val node = compose.onNodeWithTag(tag)
        node.performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val rect = node.getUnclippedBoundsInRoot()
        assertTrue("$tag left boundary: $rect", rect.left.value >= -.31f)
        assertTrue("$tag right boundary: $rect", rect.right.value <= viewportWidth / 3.25f + .31f)
        assertTrue("$tag top boundary: $rect", rect.top.value >= -.31f)
        assertTrue("$tag bottom boundary: $rect", rect.bottom.value <= viewportHeight / 3.25f + .31f)
    }
    private fun assertComposerTargets() {
        val toolbarRects = listOf("composer-add-images", "composer-audience", "composer-review").map {
            compose.onNodeWithTag(it).getUnclippedBoundsInRoot()
        }
        toolbarRects.forEachIndexed { index, a -> toolbarRects.drop(index + 1).forEach { b ->
            val overlapX = minOf(a.right.value, b.right.value) - maxOf(a.left.value, b.left.value)
            val overlapY = minOf(a.bottom.value, b.bottom.value) - maxOf(a.top.value, b.top.value)
            assertFalse("Toolbar targets must reflow without overlapping: $a / $b", overlapX > .01f && overlapY > .01f)
        } }
        listOf("composer-add-images", "composer-audience", "composer-review").forEach(::assertReachableTarget)
        compose.onNodeWithTag("composer-images").performScrollTo()
        listOf("composer-remove-1", "composer-remove-2").forEachIndexed { index, tag ->
            assertReachableTarget(tag)
            compose.onNodeWithTag(tag).assertContentDescriptionEquals("移除第${index + 1}张图片")
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        }
        compose.onNodeWithText("原图可能包含位置", substring = true).performScrollTo().assertIsDisplayed()
    }
    private fun capture(name: String) {
        assertViewport()
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val directory = File("build/verification/composer-presentation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val geometry = buildJsonObject {
            put("widthPx", viewportWidth); put("heightPx", viewportHeight); put("density", 3.25f); put("fontScale", chosenFontScale)
            put("theme", "Production fixedColorScheme(0,false), appTypography(NOTO_SANS), dynamicColor=false")
            put("scope", "Actual NativeApp HostedScreen; shared original PNG bytes; zero live I/O; not modal or device parity")
            put("rectsDp", buildJsonObject {
                listOf("composer-surface", "composer-editor", "composer-toolbar", "composer-add-images", "composer-audience",
                    "composer-review", "composer-images", "composer-image-1", "composer-image-2", "composer-remove-1", "composer-remove-2").forEach { tag ->
                    if (compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size == 1) {
                        val r = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                        put(tag, buildJsonArray { add(r.left.value); add(r.top.value); add((r.right - r.left).value); add((r.bottom - r.top).value) })
                    }
                }
            })
        }
        File(directory, "$name.metrics.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), geometry))
    }
    private fun dialogText(text: String, substring: Boolean = false) = compose.onNode(
        hasText(text, substring = substring) and hasAnyAncestor(isDialog()), useUnmergedTree = true)

    /** Query the actual text engine result, not the requested outer CompositionLocal or a pixel guess. */
    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(results)) }
        assertEquals("One actual Text layout result", 1, results.size)
        return results.single()
    }

    private fun captureConfirmationTextDiagnostics(captureName: String) {
        listOf("title" to dialogText("确认发送？"), "warning" to dialogText("包含2张图片", substring = true),
            "dismiss" to dialogText("返回编辑"), "confirm" to dialogText("确认发送")).forEach { (name, node) ->
            val result = textLayout(node)
            val input = result.layoutInput
            val paragraph = result.multiParagraph
            val probeClipped = node.fetchSemanticsNode().boundsInRoot
            val probeUnclipped = node.getUnclippedBoundsInRoot()
            val probe = buildJsonObject {
                put("capture", captureName); put("slot", name)
                put("expectedEffectiveFontScale", chosenFontScale)
                put("actualDensity", input.density.density); put("actualFontScale", input.density.fontScale)
                put("fontSize", input.style.fontSize.toString()); put("lineHeight", input.style.lineHeight.toString())
                put("layoutWidthPx", result.size.width); put("layoutHeightPx", result.size.height)
                put("paragraphWidthPx", paragraph.width); put("paragraphHeightPx", paragraph.height)
                put("didExceedMaxLines", paragraph.didExceedMaxLines)
                put("didOverflowWidth", result.didOverflowWidth); put("didOverflowHeight", result.didOverflowHeight)
                put("hasVisualOverflow", result.hasVisualOverflow)
                put("minWidthPx", input.constraints.minWidth); put("maxWidthPx", input.constraints.maxWidth)
                put("minHeightPx", input.constraints.minHeight); put("maxHeightPx", input.constraints.maxHeight)
                put("maxLines", input.maxLines); put("softWrap", input.softWrap); put("overflow", input.overflow.toString())
                put("textLength", input.text.length); put("lineCount", result.lineCount)
                put("clippedPx", buildJsonArray {
                    add(probeClipped.left); add(probeClipped.top); add(probeClipped.right); add(probeClipped.bottom)
                })
                put("unclippedDp", buildJsonArray {
                    add(probeUnclipped.left.value); add(probeUnclipped.top.value)
                    add(probeUnclipped.right.value); add(probeUnclipped.bottom.value)
                })
                put("lines", buildJsonArray {
                    (0 until result.lineCount).forEach { line -> add(buildJsonObject {
                        put("index", line); put("start", result.getLineStart(line)); put("end", result.getLineEnd(line))
                        put("leftPx", result.getLineLeft(line)); put("rightPx", result.getLineRight(line))
                        put("topPx", result.getLineTop(line)); put("bottomPx", result.getLineBottom(line))
                        put("baselinePx", result.getLineBaseline(line)); put("ellipsized", result.isLineEllipsized(line))
                    }) }
                })
            }
            val probeDirectory = File("build/verification/composer-presentation").apply { mkdirs() }
            File(probeDirectory, "$captureName-text-layout-before-assert-$name.json")
                .writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), probe))
        }
    }

    private fun measureConfirmationText(captureName: String): JsonObject = buildJsonObject {
        captureConfirmationTextDiagnostics(captureName)
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        listOf("title" to dialogText("确认发送？"), "warning" to dialogText("包含2张图片", substring = true),
            "dismiss" to dialogText("返回编辑"), "confirm" to dialogText("确认发送")).forEach { (name, node) ->
            node.assertIsDisplayed()
            val result = textLayout(node)
            val input = result.layoutInput
            assertEquals("$name actual density", 3.25f, input.density.density, .001f)
            assertEquals("$name actual effective font scale", chosenFontScale, input.density.fontScale, .001f)
            assertTrue("$name font size must be resolved", input.style.fontSize.value > 0f)
            // Compose 1.7.6 String Text semantics rebuilds MultiParagraph at parent max width,
            // while retaining the real narrow layoutSize. Its generic width flag is therefore
            // not a clipping witness. Check every actual line and complete text coverage instead.
            assertFalse("$name text must not overflow vertically", result.didOverflowHeight)
            assertFalse("$name text must not exceed max lines", result.multiParagraph.didExceedMaxLines)
            assertTrue("$name must contain at least one line", result.lineCount > 0)
            assertEquals("$name must include the start of the text", 0, result.getLineStart(0))
            assertEquals("$name must include all text", input.text.length, result.getLineEnd(result.lineCount - 1))
            (0 until result.lineCount).forEach { line ->
                assertFalse("$name line must not be ellipsized", result.isLineEllipsized(line))
                assertTrue("$name line $line must fit actual layout width",
                    result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
                assertTrue("$name line $line must fit actual layout height",
                    result.getLineTop(line) >= -1f && result.getLineBottom(line) <= result.size.height + 1f)
                if (line > 0) assertEquals("$name lines must cover text without gaps",
                    result.getLineEnd(line - 1), result.getLineStart(line))
            }
            val uncut = node.getUnclippedBoundsInRoot()
            val clipped = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$name must fit horizontally inside Dialog", uncut.left.value * 3.25f >= dialog.left - 1f &&
                uncut.right.value * 3.25f <= dialog.right + 1f)
            if (name != "warning") {
                assertTrue("$name must fit vertically inside Dialog", uncut.top.value * 3.25f >= dialog.top - 1f &&
                    uncut.bottom.value * 3.25f <= dialog.bottom + 1f)
                assertEquals("$name must not be horizontally clipped", (uncut.right - uncut.left).value * 3.25f, clipped.width, 1f)
                assertEquals("$name must not be vertically clipped", (uncut.bottom - uncut.top).value * 3.25f, clipped.height, 1f)
            }
            put(name, buildJsonObject {
                put("density", input.density.density); put("fontScale", input.density.fontScale)
                put("fontSizeSp", input.style.fontSize.value); put("lineCount", result.lineCount)
                put("layoutWidthPx", result.size.width); put("layoutHeightPx", result.size.height)
                put("maxWidthPx", input.constraints.maxWidth); put("maxHeightPx", input.constraints.maxHeight)
                put("unclippedDp", buildJsonArray { add(uncut.left.value); add(uncut.top.value); add(uncut.right.value); add(uncut.bottom.value) })
                put("clippedPx", buildJsonArray { add(clipped.left); add(clipped.top); add(clipped.right); add(clipped.bottom) })
            })
        }
    }

    private fun captureConfirmationWarningEnds(name: String, selfOnly: Boolean) {
        val scroll = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
        val warning = dialogText("包含2张图片", substring = true)
        val layout = textLayout(warning)
        listOf(false, true).forEach { tail ->
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val target = if (tail) range.maxValue() else 0f
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(0f, target - range.value()) }
            compose.waitForIdle()
            val viewport = scroll.fetchSemanticsNode().boundsInRoot
            val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val title = dialogText("确认发送？").fetchSemanticsNode().boundsInRoot
            val actionsTop = listOf("返回编辑", "确认发送").minOf {
                compose.onNode(hasText(it) and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot.top
            }
            assertTrue("Warning viewport must stay inside Dialog and separate from title/actions", viewport.left >= dialog.left - 1f &&
                viewport.right <= dialog.right + 1f && viewport.top >= title.bottom - 1f && viewport.bottom <= actionsTop + 1f)
            val origin = warning.getUnclippedBoundsInRoot().top.value * 3.25f
            val line = if (tail) layout.lineCount - 1 else 0
            assertTrue("Warning ${if (tail) "last" else "first"} line must be fully reachable", origin + layout.getLineTop(line) >= viewport.top - 1f &&
                origin + layout.getLineBottom(line) <= viewport.bottom + 1f)
            captureConfirmation("$name-${if (tail) "tail" else "top"}", selfOnly)
        }
    }

    /** Capture the real confirmation window, never the Activity that remains visible behind it. */
    private fun captureConfirmation(name: String, selfOnly: Boolean) {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNode(hasText("确认发送？") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        val warning = compose.onNode(hasText("包含2张图片", substring = true) and hasAnyAncestor(isDialog()))
        warning.assertIsDisplayed().assertTextContains("确认后才会上传并发送。", substring = true)
            .assertTextContains("原图可能包含位置等元数据。", substring = true)
        if (selfOnly) warning.assertTextContains("仅自己可见不保证图片链接私密。", substring = true)
        val dialogBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        listOf("返回编辑", "确认发送").forEach { label ->
            val action = compose.onNode(hasText(label, substring = false) and hasAnyAncestor(isDialog()))
            action.assertIsDisplayed().assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val uncut = action.getUnclippedBoundsInRoot()
            val clipped = action.fetchSemanticsNode().boundsInRoot
            assertTrue("$label action must fit inside Dialog", uncut.left.value * 3.25f >= dialogBounds.left - 1f &&
                uncut.right.value * 3.25f <= dialogBounds.right + 1f && uncut.top.value * 3.25f >= dialogBounds.top - 1f &&
                uncut.bottom.value * 3.25f <= dialogBounds.bottom + 1f)
            assertEquals("$label action must not be horizontally clipped", (uncut.right - uncut.left).value * 3.25f, clipped.width, 1f)
            assertEquals("$label action must not be vertically clipped", (uncut.bottom - uncut.top).value * 3.25f, clipped.height, 1f)
        }
        compose.waitForIdle()
        val measuredText = measureConfirmationText(name)
        val (bitmap, location, windowConfiguration) = compose.runOnUiThread {
            val shown = org.robolectric.shadows.ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()
            check(shown.size == 1) { "Expected exactly one shown confirmation Dialog" }
            val dialog = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            check(dialog === shown.single()) { "Latest Dialog must be the sole current confirmation" }
            val window = requireNotNull(dialog.window)
            check(window !== compose.activity.window)
            val decor = window.decorView
            check(decor !== compose.activity.window.decorView && decor.isAttachedToWindow)
            check(window.callback === dialog)
            check(window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
            check(decor.width > 0 && decor.height > 0)
            val position = IntArray(2).also(decor::getLocationOnScreen)
            Triple(android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                decor.draw(android.graphics.Canvas(it))
            }, position, decor.resources.configuration.fontScale)
        }
        val directory = File("build/verification/composer-presentation").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            val facts = buildJsonObject {
                put("captureTarget", "Sole shown and latest confirmation Dialog.window.decorView, not Activity")
                put("composition", "Unmodified native dialog-window pixels; not composited with the underlying Activity or dim layer")
                put("widthPx", bitmap.width); put("heightPx", bitmap.height)
                put("windowXpx", location[0]); put("windowYpx", location[1])
                put("activityWidthPx", viewportWidth); put("activityHeightPx", viewportHeight)
                put("expectedEffectiveDensity", 3.25f); put("expectedEffectiveFontScale", chosenFontScale)
                put("windowResourceFontScale", windowConfiguration)
                put("activityResourceFontScale", compose.activity.resources.configuration.fontScale)
                put("measuredText", measuredText); put("selfOnly", selfOnly)
                put("title", "确认发送？"); put("visibleActions", buildJsonArray { add("返回编辑"); add("确认发送") })
            }
            File(directory, "$name.metrics.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), facts))
        } finally { bitmap.recycle() }
        assertNoWrites()
    }

    private fun captureTop(name: String) {
        compose.onNodeWithTag("composer-editor").performScrollTo()
        capture(name)
    }
    private fun clickPublishEntry() = compose.onNodeWithTag("home-publish").assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
    private fun confirmationBack() {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.runOnUiThread {
            val dialog = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            check(dialog.isShowing)
            check(dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)))
            check(dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)))
        }
    }

    @Test fun exactUserViewportPreservesOriginalArtworkPublicPrivateAndRemoval() {
        showRoot(); typeDraft(); selectImages(0, 1); awaitOriginalImages()
        compose.onNode(hasSetTextAction() and hasContentDescription("内容")).assertTextContains(DRAFT)
        assertOriginalAspectGeometry(); assertComposerTargets()
        val surface = compose.onNodeWithTag("composer-surface", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(viewportHeight / 3.25f, surface.bottom.value, 1f / 3.25f)
        assertTrue("Short composer must dock at bottom, not fill the HostedScreen body", surface.top.value > 80f)
        captureTop("native-user369-selected-public")
        compose.onNodeWithTag("composer-audience").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("不保证上传图片链接本身私密", substring = true).performScrollTo().assertIsDisplayed()
        captureTop("native-user369-private-enabled")
        openConfirmation(); captureConfirmationWarningEnds("native-user369-confirmation-private", selfOnly = true)
        compose.onNodeWithText("返回编辑").performClick()
        compose.onNodeWithTag("composer-images").performScrollTo()
        compose.onNodeWithContentDescription("移除第1张图片").performClick()
        awaitVisible("one original remains", hasText("添加图片（1/9）"))
        assertOriginalAspectGeometry(listOf(112.5f))
        captureTop("native-user369-first-image-removed")
        assertNoWrites()
    }

    @Test @Config(qualifiers = "w320dp-h480dp-520dpi")
    fun compactLargeTextReflowsInsteadOfClippingAndReviewRemainsReachable() {
        showRoot(widthPx = 1040, heightPx = 1560, fontScale = 1.6f)
        typeDraft(); selectImages(0, 1); awaitOriginalImages()
        assertOriginalAspectGeometry(); assertComposerTargets()
        captureTop("native-compact-large-selected-public-top")
        compose.onNodeWithTag("composer-audience").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("不保证上传图片链接本身私密", substring = true).performScrollTo().assertIsDisplayed()
        capture("native-compact-large-private-tail")
        openConfirmation()
        compose.onNodeWithText("包含2张图片", substring = true).performScrollTo().assertIsDisplayed()
            .assertTextContains("原图可能包含位置等元数据。", substring = true)
            .assertTextContains("仅自己可见不保证图片链接私密。", substring = true)
        compose.onNodeWithText("确认发送", substring = false).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        captureConfirmationWarningEnds("native-compact-large-confirmation-private", selfOnly = true)
        compose.onNodeWithText("返回编辑").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertReachableTarget("composer-review")
        assertNoWrites()
    }

    @Test @Config(qualifiers = "w822dp-h369dp-land-520dpi")
    fun rotatedExactViewportPreservesAspectAndAllActionsRemainReachable() {
        showRoot(widthPx = 2670, heightPx = 1200)
        typeDraft(); selectImages(0, 1); awaitOriginalImages()
        assertOriginalAspectGeometry(); assertComposerTargets()
        captureTop("native-landscape-selected-public-top")
        compose.onNodeWithTag("composer-audience").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("不保证上传图片链接本身私密", substring = true).performScrollTo().assertIsDisplayed()
        capture("native-landscape-private-tail")
        openConfirmation()
        compose.onNodeWithText("确认发送", substring = false).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        captureConfirmationWarningEnds("native-landscape-confirmation-private", selfOnly = true)
        compose.onNodeWithText("返回编辑").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertNoWrites()
    }

    @Test @Config(qualifiers = "w822dp-h369dp-land-520dpi")
    fun landscapeConfirmationKeepsLiveEffectiveFontScaleWithoutDoubleMultiplication() {
        showRoot(widthPx = 2670, heightPx = 1200)
        typeDraft(); selectImages(0, 1); awaitOriginalImages()
        compose.onNodeWithTag("composer-audience").performScrollTo().performClick().assertIsOn()
        openConfirmation()
        captureConfirmationWarningEnds("native-landscape-live-scale-1-confirmation", selfOnly = true)
        // Simulate a live effective system × app density update without leaving this composer.
        // Neither the fixture nor the confirmation should multiply this effective value again.
        listOf(1.3f * 1.2f, 1.6f).forEach { effectiveScale ->
            compose.runOnIdle { chosenFontScale = effectiveScale }
            compose.waitForIdle()
            captureConfirmationWarningEnds("native-landscape-live-scale-${effectiveScale}-confirmation", selfOnly = true)
        }
        compose.onNodeWithText("返回编辑").performClick()
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT)
        assertNoWrites()
    }

    @Test @Config(qualifiers = "w320dp-h480dp-520dpi")
    fun compactInjectedImeKeepsScrolledActionsAboveKeyboardWithoutChangingDraft() {
        showRoot(widthPx = 1040, heightPx = 1560, fontScale = 1.6f)
        typeDraft(); selectImages(0, 1); awaitOriginalImages()
        injectInsets(imeDp = 160, systemDp = 24)
        listOf("composer-add-images", "composer-audience", "composer-review").forEach { tag ->
            assertReachableTarget(tag)
            assertTrue("$tag must be wholly above synthetic IME", compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom.value <= 320.31f)
        }
        val surface = compose.onNodeWithTag("composer-surface", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Composer surface must not overlap IME", surface.bottom.value <= 320.31f)
        capture("native-compact-large-injected-ime-review-button")
        openConfirmation(); captureConfirmationWarningEnds("native-compact-large-after-ime-confirmation", selfOnly = false)
        compose.onNodeWithText("返回编辑").performClick()
        injectInsets()
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT)
        assertOriginalAspectGeometry(); assertNoWrites()
    }

    @Test @Config(qualifiers = "w320dp-h480dp-520dpi")
    fun extremeAcceptedImageDimensionsStayBoundedAndRemovableAtLargeText() {
        showRoot(widthPx = 1040, heightPx = 1560, fontScale = 1.6f)
        typeDraft()
        val dimensions = listOf(16000 to 1, 1 to 16000)
        val bytes = dimensions.map { (width, height) ->
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(20, 140, 180))
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                bitmap.recycle(); output.toByteArray()
            }
        }
        allowedFixtureBytes.addAll(bytes)
        compose.onNodeWithTag("composer-add-images").performScrollTo().performClick()
        val uris = bytes.mapIndexed { index, value -> provider.put("extreme-$index", value) }
        compose.runOnIdle { picker.deliver(uris) }
        awaitVisible("extreme dimensions staged by the production picker", hasText("添加图片（2/9）"))
        compose.onNodeWithTag("composer-images").performScrollTo()
        // The production staging path decoded these bounds; deliberately unrecognizable
        // thumbnails use honest placeholders rather than pretending that blank Fit pixels are useful.
        compose.onNodeWithContentDescription("附件1，16000×1，超宽图预览受限，原图保持不变").assertExists()
        compose.onNodeWithContentDescription("附件2，1×16000，长图预览受限，原图保持不变").assertExists()
        assertEquals(2, provider.reads.get())
        assertTrue("No pointless full decode for an unreadable preview", decodedFiles.isEmpty())
        listOf(290f, 144f).forEachIndexed { index, expectedWidth ->
            compose.onNodeWithTag("composer-images").performScrollToIndex(index)
            val image = compose.onNodeWithTag("composer-image-${index + 1}", useUnmergedTree = true)
            image.assertIsDisplayed()
            assertReachableTarget("composer-remove-${index + 1}")
            val rect = image.getUnclippedBoundsInRoot()
            assertEquals(expectedWidth, (rect.right - rect.left).value, 1f / 3.25f)
            assertTrue("Placeholder may grow with text, but cannot be shorter than 75 dp", (rect.bottom - rect.top).value >= 75f - 1f / 3.25f)
            assertTrue("Current placeholder must fit the actual viewport", rect.left.value >= -.31f &&
                rect.right.value <= 320.31f && rect.top.value >= -.31f && rect.bottom.value <= 480.31f)
            compose.onNodeWithTag("composer-remove-${index + 1}").assertContentDescriptionEquals("移除第${index + 1}张图片")
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val removeRect = compose.onNodeWithTag("composer-remove-${index + 1}").getUnclippedBoundsInRoot()
            assertTrue("Removal target must not overlap the placeholder", removeRect.top.value >= rect.bottom.value - .31f)
            val label = compose.onNodeWithText(if (index == 0) "超宽图预览受限" else "长图预览受限", useUnmergedTree = true)
            val dimensionsLabel = compose.onNodeWithText(if (index == 0) "16000×1" else "1×16000", useUnmergedTree = true)
            label.assertIsDisplayed(); dimensionsLabel.assertIsDisplayed()
            val labelRect = label.getUnclippedBoundsInRoot()
            val dimensionsRect = dimensionsLabel.getUnclippedBoundsInRoot()
            assertTrue("Placeholder labels must not overlap", labelRect.bottom.value <= dimensionsRect.top.value + .31f)
            listOf(labelRect, dimensionsRect).forEach { textRect ->
                assertTrue("Placeholder text must remain wholly inside its slot", textRect.left.value >= rect.left.value - .31f &&
                    textRect.right.value <= rect.right.value + .31f && textRect.top.value >= rect.top.value - .31f &&
                    textRect.bottom.value <= rect.bottom.value + .31f)
            }
            capture("native-compact-extreme-placeholder-${index + 1}")
        }
        compose.onNodeWithTag("composer-remove-2").performClick()
        compose.onNodeWithTag("composer-images").performScrollToIndex(0)
        assertReachableTarget("composer-remove-1")
        compose.onNodeWithTag("composer-remove-1").performClick()
        awaitVisible("both extremes explicitly removed", hasText("添加图片（0/9）"))
        assertNoWrites()
    }

    @Test fun guestActualHomePublishClickRoutesOnlyToExistingLogin() {
        accounts.store.useGuest()
        showRoot(route = null)
        clickPublishEntry()
        awaitVisible("existing login route", hasText("登录酷安账号"))
        compose.onNodeWithTag("composer-editor").assertDoesNotExist()
        compose.onNodeWithTag("composer-review").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        awaitVisible("actual home restored", hasTestTag("home-publish"))
        assertStableCounts(0, 0, 1)
    }

    @Test fun authenticatedActualHomeEntryConfirmationBackAndCloseNeverReloadOrUpload() {
        showRoot(route = null); clickPublishEntry()
        awaitVisible("real tapped composer", hasTestTag("composer-editor"))
        typeDraft(); selectImages(0, 1); awaitOriginalImages()
        val stagedFiles = decodedFiles.keys.map(::File)
        assertTrue(stagedFiles.all(File::isFile))
        openConfirmation(); confirmationBack()
        awaitVisible("dialog Back returns to current editor", hasTestTag("composer-review"))
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT)
        compose.onNodeWithText("添加图片（2/9）").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitVisible("route Back returns to actual home", hasTestTag("home-publish"))
        compose.waitUntil(5_000) { stagedFiles.none(File::exists) }
        assertStableCounts(0, 0, 1)
        clickPublishEntry()
        awaitVisible("new composer instance", hasTestTag("composer-editor"))
        compose.onNodeWithText(DRAFT).assertDoesNotExist()
        compose.onNodeWithText("添加图片（0/9）").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        awaitVisible("toolbar close returns home", hasTestTag("home-publish"))
        assertStableCounts(0, 0, 1)
    }

    @Test fun authenticatedActualHomeClickPublishesExactlyOnceOnlyAfterReview() {
        firstPutGate = CountDownLatch(1)
        showRoot(route = null); clickPublishEntry()
        awaitVisible("composer reached by real entry", hasTestTag("composer-editor"))
        typeDraft(); selectImages(0, 1); awaitOriginalImages()
        openConfirmation(); confirm()
        compose.onNodeWithTag("composer-review").performScrollTo()
        awaitVisible("real first upload body held", hasTestTag("composer-review") and isNotEnabled()) { puts.size == 1 }
        compose.onNodeWithTag("composer-review").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, homeReads.get()); assertEquals(1, apiCalls.size); assertEquals(1, puts.size)
        firstPutGate!!.countDown()
        awaitVisible("confirmed publication refreshed actual home once", hasTestTag("home-publish")) {
            homeReads.get() == 2 && apiCalls.size == 2 && puts.size == 2
        }
        compose.onNodeWithTag("composer-editor").assertDoesNotExist()
        assertImageContract(listOf(0, 1))
        assertEquals("0", finalForm()["publish_status"])
        assertStableCounts(2, 2, 2)
    }

    @Test fun actualFeedReplyEntryBackKeepsOriginAndDoesNotRefresh() {
        showRoot(route = "feed:900")
        compose.onNodeWithText("回复动态").performScrollTo().performClick()
        awaitVisible("actual feed reply composer", hasTestTag("composer-editor"))
        typeDraft(); selectImages(0, 1); awaitOriginalImages()
        compose.onNodeWithTag("composer-audience").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("回复并转发").assertExists()
        assertOriginalAspectGeometry()
        captureTop("native-reply-user369-forward-enabled")
        compose.onNodeWithContentDescription("返回").performClick()
        awaitVisible("same actual feed", hasTestTag("feed-detail-list"))
        compose.onNodeWithText(FEED_ORIGINAL).assertExists()
        assertStableCounts(0, 0, 0, detail = 1, replies = 1)
    }

    /** A content provider serves only files created by this fixture. No content/storage permissions are granted. */
    private class ImageProvider(private val directory: File) : ContentProvider() {
        val reads = AtomicInteger()
        private val files = mutableMapOf<Uri, File>()
        fun put(name: String, bytes: ByteArray): Uri = Uri.parse("content://$AUTHORITY/$name.png").also { uri ->
            files[uri] = File(directory, "$name.png").apply { writeBytes(bytes) }
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r")
            val file = files[uri] ?: throw FileNotFoundException("Unknown synthetic image")
            reads.incrementAndGet()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only synthetic provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only synthetic provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only synthetic provider")
    }

    /** Captures the system picker launch, then dispatches raw Activity results through the production contract. */
    private class PickerRegistry(private val context: Context) : ActivityResultRegistry() {
        private var requestCode: Int? = null
        var launchedIntent: Intent? = null
            private set
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            check(this.requestCode == null) { "Picker launched twice without receiving a result" }
            this.requestCode = requestCode
            launchedIntent = contract.createIntent(context, input)
        }
        fun deliver(uris: List<Uri>) {
            val intent = Intent()
            if (uris.isNotEmpty()) {
                // Use ClipData, even for one image, so the same OpenMultipleDocuments parser owns order/deduplication.
                intent.clipData = ClipData.newRawUri("synthetic selected images", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
            dispatch(Activity.RESULT_OK, intent)
        }
        fun cancel() = dispatch(Activity.RESULT_CANCELED, null)
        private fun dispatch(code: Int, intent: Intent?) {
            val pending = requireNotNull(requestCode)
            requestCode = null
            check(dispatchResult(pending, code, intent)) { "Picker result did not reach the registered real contract" }
        }
    }

    private companion object {
        const val AUTHORITY = "io.github.ktclat.c001apk.synthetic.presentationpicker"
        const val PREPARE = "/v6/upload/ossUploadPrepare"
        const val CREATE = "/v6/feed/createFeed"
        const val REPLY = "/v6/feed/reply"
        const val DRAFT = "分享两张测试图片：竖图在前，横图在后。\n仅用于离线界面对照，不会上传。"
        const val HOME_REFRESHED = "发布后实际首页已刷新"
        const val FEED_ORIGINAL = "合成动态原始正文"
        const val FEED_REFRESHED = "回复后实际原动态已刷新"
        val IMAGE_BYTES = listOf("portrait.png", "landscape.png").map { name ->
            requireNotNull(NativeImageComposerPresentationTest::class.java.getResource("/composer-reference/$name")).readBytes()
        }
    }
}
