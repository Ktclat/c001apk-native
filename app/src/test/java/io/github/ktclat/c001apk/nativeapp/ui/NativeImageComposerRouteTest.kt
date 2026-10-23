// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.runners.model.MultipleFailureException
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-only host: every recreated Activity composes a fresh NativeApp and its own navigation callbacks. */
class ImageComposerRouteActivity : ComponentActivity() {
    companion object { var content: (@Composable () -> Unit)? = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { requireNotNull(content).invoke() }
    }
}

/**
 * Real NativeApp routes, system-contract result parsing, private image staging and typed writes.
 * Both API and OSS use in-process interceptors that consume real bodies and NEVER proceed.
 * These are synthetic contract fixtures, not evidence of a real account, grant or upload working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeImageComposerRouteTest {
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
    private var recreationController: ActivityController<ImageComposerRouteActivity>? = null

    @Before fun setup() {
        val context = compose.activity.applicationContext
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
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: Throwable) { failures.add(failure) }
        }
        firstPutGate?.countDown()
        try {
            attempt {
                recreationController?.let { controller ->
                    // Teardown cannot wait for Compose idleness after failed hierarchy readiness.
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val activity = controller.get()
                        controller.pause().stop().destroy()
                        assertTrue("Owned Activity must be destroyed during cleanup", activity.isDestroyed)
                        assertFalse("Owned decor must be detached during cleanup", activity.window.decorView.isAttachedToWindow)
                    }
                }
            }
        } finally {
            recreationController = null
            ImageComposerRouteActivity.content = null
            clients.forEach { client ->
                attempt { client.dispatcher.cancelAll() }
                attempt { client.connectionPool.evictAll() }
                attempt { client.dispatcher.executorService.shutdownNow() }
            }
            attempt { if (::sourceDirectory.isInitialized) sourceDirectory.deleteRecursively() }
            attempt { assertTrue("Synthetic interceptor failed: $interceptorFailures", interceptorFailures.isEmpty()) }
        }
        // JUnit's RunAfters keeps an original test failure alongside any teardown failures.
        MultipleFailureException.assertEmpty(failures)
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

    private fun showRoot(route: String = "compose", recreatableHost: Boolean = false, fontScale: Float = 1f) {
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
                LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    // Keep the rendered generation frozen in stale-intent tests: repository/runner guards must hold
                    // even when a confirmation callback races the next account-state recomposition.
                    NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false),
                        accounts, snapshot, initialRoute = route)
                }
            }
        }
        if (recreatableHost) {
            ImageComposerRouteActivity.content = rootContent
            compose.runOnUiThread {
                recreationController = Robolectric.buildActivity(ImageComposerRouteActivity::class.java).setup()
            }
        } else compose.setContent(rootContent)
        if (route == "compose") awaitVisible("composer", hasSetTextAction())
        else if (route.startsWith("feed:")) awaitVisible("origin feed", hasTestTag("feed-detail-list")) {
            detailReads.get() == 1 && replyReads.get() == 1
        }
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
            assertEquals(if (expectedIndices[index] == 0) "2x3" else "3x2", file.getValue("resolution").jsonPrimitive.content)
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

    @Test fun cancelledSystemPickerDoesNotReadUploadOrPublish() {
        showRoot(); typeDraft()
        compose.onNodeWithText("添加图片（0/9）").performScrollTo().performClick()
        compose.runOnIdle { picker.cancel() }
        awaitVisible("cancelled picker", hasText("添加图片（0/9）"))
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT)
        assertEquals(0, provider.reads.get())
        assertNoWrites()
    }

    @Test fun selectionPreviewRemovalAndDismissedConfirmationNeverWrite() {
        showRoot(); typeDraft(); selectImages(1, 0)
        compose.onNodeWithText("原图可能包含位置", substring = true).assertExists()
        openConfirmation()
        compose.onNodeWithText("包含2张图片", substring = true).assertIsDisplayed()
        compose.onNodeWithText("返回编辑").performClick()
        compose.onNodeWithContentDescription("移除第2张图片").performScrollTo().performClick()
        awaitVisible("one remaining image", hasText("添加图片（1/9）"))
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT).assertIsEnabled()
        assertEquals(2, provider.reads.get())
        assertNoWrites()
    }

    private fun publishFeed(selfOnly: Boolean) {
        firstPutGate = CountDownLatch(1)
        showRoot(); typeDraft(); selectImages(1, 0)
        if (selfOnly) {
            compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOn()
            compose.onNodeWithText("不保证上传图片链接本身私密", substring = true).assertExists()
        }
        openConfirmation()
        compose.onNodeWithText(if (selfOnly) "发布仅自己可见的动态" else "公开发布动态", substring = true).assertIsDisplayed()
        confirm()
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("first PUT held", hasText("检查并发送") and isNotEnabled()) {
            puts.size == 1 && apiCalls.size == 1
        }
        assertEquals(listOf("prepare", "put:1"), order.toList())
        assertEquals(0, completedPuts.get())
        // A second touch while the first real RequestBody is in flight must not queue publication or another upload.
        compose.onNodeWithText("检查并发送").assertIsNotEnabled().performTouchInput { click() }
        assertStableCounts(1, 1, 0)
        firstPutGate!!.countDown()
        awaitVisible("returned to refreshed actual home", hasText(HOME_REFRESHED)) {
            apiCalls.size == 2 && puts.size == 2 && homeReads.get() == 1
        }
        compose.onNodeWithText("检查并发送").assertDoesNotExist()
        assertImageContract(listOf(1, 0))
        assertEquals(CREATE, apiCalls.last().request.url.encodedPath)
        assertNull(apiCalls.last().request.url.query)
        assertEquals(mapOf("message" to DRAFT, "type" to "feed", "status" to "1",
            "publish_status" to if (selfOnly) "1" else "0", "pic" to finalForm().getValue("pic")), finalForm())
        assertStableCounts(2, 2, 1)
    }
    @Test fun publicImagePublishUsesOrderedContractAndRefreshesActualHomeOnce() = publishFeed(false)
    @Test fun privateImagePublishUsesOrderedContractAndRefreshesActualHomeOnce() = publishFeed(true)

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-420dpi")
    fun compactLandscapeLargeTextKeepsPrivateImageConfirmationWarningsAndButtonsReachable() {
        showRoot(fontScale = 2f)
        typeDraft(); selectImages(0, 1)
        compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOn()
        openConfirmation()
        val confirmation = compose.onNodeWithText("包含2张图片", substring = true)
        confirmation.performScrollTo().assertIsDisplayed()
            .assertTextContains("原图可能包含位置等元数据。", substring = true)
            .assertTextContains("仅自己可见不保证图片链接私密。", substring = true)
        // These actions are outside the bounded scrolling body and must remain reachable at large text sizes.
        compose.onNodeWithText("确认发送", substring = false).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("返回编辑").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("检查并发送").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT).assertIsEnabled()
        assertNoWrites()
    }

    @Test fun inFlightRotationReturnsCurrentRootAndRefreshesHomeExactlyOnceWithoutRepeatingUploads() {
        firstPutGate = CountDownLatch(1)
        showRoot(recreatableHost = true)
        typeDraft(); selectImages(1, 0)
        compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOn()
        openConfirmation(); confirm()
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("first PUT held before rotation", hasText("检查并发送") and isNotEnabled()) {
            apiCalls.size == 1 && puts.size == 1
        }
        val controller = requireNotNull(recreationController)
        val originalActivity = controller.get()
        compose.runOnIdle {
            controller.configurationChange(Configuration(originalActivity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 852
                screenHeightDp = 393
            }).visible() // Robolectric configurationChange resumes the new host but does not attach its decor.
        }
        val rotatedActivity = controller.get()
        assertNotSame("This must destroy/recreate the host, not just recompose the old navigation closure",
            originalActivity, rotatedActivity)
        assertTrue(originalActivity.isDestroyed)
        assertEquals(Lifecycle.State.RESUMED, rotatedActivity.lifecycle.currentState)
        assertTrue("Recreated composer decor must attach before checking Compose readiness",
            rotatedActivity.window.decorView.isAttachedToWindow)
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("new activity restored the same pending composer", hasText("检查并发送") and isNotEnabled()) {
            apiCalls.size == 1 && puts.size == 1 && completedPuts.get() == 0
        }
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT).assertIsNotEnabled()
        compose.onNodeWithText("添加图片（2/9）").assertExists()
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        assertEquals(listOf("prepare", "put:1"), order.toList())
        firstPutGate!!.countDown()
        awaitVisible("current recreated host returned to actual refreshed home", hasText(HOME_REFRESHED)) {
            apiCalls.size == 2 && puts.size == 2 && homeReads.get() == 1
        }
        compose.onNodeWithText("检查并发送").assertDoesNotExist()
        assertImageContract(listOf(1, 0))
        assertEquals(CREATE, apiCalls.last().request.url.encodedPath)
        assertEquals("1", finalForm()["publish_status"])
        assertStableCounts(2, 2, 1)
        // Recreate again after delivery: an unconsumed completion event must not refresh or publish twice.
        compose.runOnIdle {
            controller.configurationChange(Configuration(rotatedActivity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 393
                screenHeightDp = 852
            }).visible()
        }
        assertNotSame(rotatedActivity, controller.get())
        assertTrue(rotatedActivity.isDestroyed)
        assertEquals(Lifecycle.State.RESUMED, controller.get().lifecycle.currentState)
        assertTrue("Recreated home decor must attach before checking Compose readiness",
            controller.get().window.decorView.isAttachedToWindow)
        awaitVisible("home survives a later recreation without replay", hasText(HOME_REFRESHED)) {
            homeReads.get() == 1
        }
        compose.onNodeWithText("检查并发送").assertDoesNotExist()
        assertStableCounts(2, 2, 1)
    }

    @Test fun imageReplyNavigatesFromActualFeedAndRefreshesOnlyThatOriginOnce() {
        showRoot("feed:900")
        compose.onNodeWithText("回复动态").performScrollTo().performClick()
        awaitVisible("actual reply composer", hasSetTextAction())
        typeDraft(); selectImages(0, 1)
        compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("回复并转发").assertExists()
        openConfirmation(); confirm()
        awaitVisible("returned to refreshed actual feed", hasText(FEED_REFRESHED)) {
            apiCalls.size == 2 && detailReads.get() == 2 && replyReads.get() == 2
        }
        compose.onNodeWithTag("feed-detail-list").assertIsDisplayed()
        compose.onNodeWithText("检查并发送").assertDoesNotExist()
        assertImageContract(listOf(0, 1))
        val request = apiCalls.last().request
        assertEquals(REPLY, request.url.encodedPath)
        assertEquals(setOf("id", "type"), request.url.queryParameterNames)
        assertEquals("900", request.url.queryParameter("id"))
        assertEquals("feed", request.url.queryParameter("type"))
        assertEquals(mapOf("message" to DRAFT, "replyAndForward" to "1", "pic" to finalForm().getValue("pic")), finalForm())
        assertStableCounts(2, 2, 0, detail = 2, replies = 2)
    }

    private fun unknownUpload(which: Int) {
        failPut = which
        showRoot(); typeDraft(); selectImages(0, 1); openConfirmation(); confirm()
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("uncertain image upload", hasText("第${which}张图片上传结果未确认", substring = true)) {
            apiCalls.size == 1 && puts.size == which
        }
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT).assertIsNotEnabled()
        compose.onNodeWithText("修改后重试").assertDoesNotExist()
        compose.onNodeWithText("重试原操作一次").assertDoesNotExist()
        compose.onNodeWithText("检查并发送").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(listOf("prepare") + (1..which).map { "put:$it" }, order.toList())
        assertStableCounts(1, which, 0)
    }
    @Test fun unknownFirstUploadDoesNotStartSecondPublishOrAutomaticRetry() = unknownUpload(1)
    @Test fun partiallyUploadedBatchNeverPublishesOrRepeatsCompletedUpload() = unknownUpload(2)

    @Test fun unknownFinalPublicationRetainsDraftWithoutReturningRefreshingOrRepeating() {
        finalResponse = FinalResponse.UNCERTAIN
        showRoot(); typeDraft(); selectImages(0, 1); openConfirmation(); confirm()
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("uncertain publication", hasText("服务端结果未确认", substring = true)) { apiCalls.size == 2 }
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT).assertIsNotEnabled()
        compose.onNodeWithText("添加图片（2/9）").assertExists()
        compose.onNodeWithText("修改后重试").assertDoesNotExist()
        compose.onNodeWithText("检查并发送").assertIsNotEnabled().performTouchInput { click() }
        assertImageContract(listOf(0, 1))
        assertStableCounts(2, 2, 0)
    }

    @Test fun staleGenerationAtConfirmationNeverPreparesUploadsOrPublishes() {
        showRoot(); typeDraft(); selectImages(0); openConfirmation()
        compose.runOnIdle { accounts.store.useGuest(); accounts.store.switchTo("1") }
        confirm()
        awaitVisible("stale confirmation dismissed", hasText("检查并发送"))
        compose.onNodeWithText("确认发送？").assertDoesNotExist()
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT)
        assertNoWrites()
    }

    @Test fun guestComposerCannotPrepareUploadOrPublish() {
        accounts.store.useGuest()
        showRoot(); typeDraft(); selectImages(0); openConfirmation(); confirm()
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("guest rejected", hasText("请先登录后操作"))
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT)
        assertNoWrites()
    }

    @Test fun guestReplyRouteRequiresLoginWithoutOpeningComposerOrWriting() {
        accounts.store.useGuest()
        showRoot("compose-reply:feed:900")
        awaitVisible("guest reply entry", hasText("登录后回复"))
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("添加图片（", substring = true).assertDoesNotExist()
        compose.onNodeWithText("登录", substring = false).assertIsEnabled()
        assertEquals(0, provider.reads.get())
        assertNoWrites()
    }

    @Test fun generationChangeWhileFirstPutIsPendingStopsRemainingPipelineAndRootCallback() {
        firstPutGate = CountDownLatch(1)
        showRoot(); typeDraft(); selectImages(0, 1); openConfirmation(); confirm()
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("pending first image", hasText("检查并发送") and isNotEnabled()) { puts.size == 1 }
        compose.runOnIdle { accounts.store.useGuest() }
        firstPutGate!!.countDown()
        awaitVisible("old root still owns no success", hasText("检查并发送")) { completedPuts.get() == 1 }
        assertEquals(listOf("prepare", "put:1"), order.toList())
        assertStableCounts(1, 1, 0)
    }

    @Test fun cancellingFinalChallengeRetainsEditableDraftAndNeverRepeatsCompletedUploads() {
        finalResponse = FinalResponse.CHALLENGE
        showRoot(); typeDraft(); selectImages(0, 1); openConfirmation(); confirm()
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("final challenge awaits explicit user action", hasText("亲自完成验证")) { apiCalls.size == 2 }
        // Do not open or solve any external challenge. Exercise the actual route's explicit abandon action.
        compose.onNodeWithText("放弃验证，返回编辑").performScrollTo().performClick()
        awaitVisible("editable draft after challenge abandonment", hasText("检查并发送") and isEnabled())
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT).assertIsEnabled()
        compose.onNodeWithText("添加图片（2/9）").assertExists()
        compose.onNodeWithText("亲自完成验证").assertDoesNotExist()
        assertImageContract(listOf(0, 1))
        assertStableCounts(2, 2, 0)
    }

    @Test fun nonContentPickerResultFailsVisiblyWithoutReadingOrWriting() {
        showRoot(); typeDraft()
        compose.onNodeWithText("添加图片（0/9）").performScrollTo().performClick()
        compose.runOnIdle { picker.deliver(listOf(Uri.parse("https://synthetic.invalid/not-an-image.png"))) }
        compose.onNodeWithText("检查并发送").performScrollTo()
        awaitVisible("non-content selection rejected", hasText("请选择系统文件选择器中的图片"))
        compose.onNodeWithText(DRAFT).assertTextContains(DRAFT).assertIsEnabled()
        compose.onNodeWithText("添加图片（0/9）").assertExists()
        assertEquals(0, provider.reads.get())
        assertNoWrites()
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
        const val AUTHORITY = "io.github.ktclat.c001apk.synthetic.imagepicker"
        const val PREPARE = "/v6/upload/ossUploadPrepare"
        const val CREATE = "/v6/feed/createFeed"
        const val REPLY = "/v6/feed/reply"
        const val DRAFT = "合成图片草稿，不发送真实请求"
        const val HOME_REFRESHED = "发布后实际首页已刷新"
        const val FEED_ORIGINAL = "合成动态原始正文"
        const val FEED_REFRESHED = "回复后实际原动态已刷新"
        val IMAGE_BYTES = listOf(
            Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAADCAYAAAC56t6BAAAAEUlEQVR4nGPYIiLyH4QZMBgAk8MLI2iMZVMAAAAASUVORK5CYII="),
            Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAEUlEQVR4nGMQ0djyH4YZkDkAjQULm9UCjMUAAAAASUVORK5CYII="),
        )
    }
}
