// SPDX-License-Identifier: AGPL-3.0-only
// Matched wire JSON through actual API/decoder and reachable NativeApp routes; no sockets.
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReachableEntityNativeAppTest {
 @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
 private lateinit var images: IconFixtureImages
 private lateinit var client: OkHttpClient
 private val forbiddenDns = CopyOnWriteArrayList<String>()
 private val paths = CopyOnWriteArrayList<String>()
 @After fun close() {
  if (::images.isInitialized) images.close()
  if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
  assertTrue("No external DNS: $forbiddenDns", forbiddenDns.isEmpty())
 }
 @Test fun personalFollow() = show("personal-follow", "mylist:FOLLOW", "开源观察员")
 @Test fun personalCollection() = show("personal-collection", "mylist:COLLECTION", "周末摄影精选")
 @Test fun feedRelations() = show("feed-relations", null, "关联入口与资料链接")
 @Test fun personalFollowDark() = show("personal-follow", "mylist:FOLLOW", "开源观察员", "-dark", true)
 @Test fun personalCollectionDark() = show("personal-collection", "mylist:COLLECTION", "周末摄影精选", "-dark", true)
 @Test fun feedRelationsDark() = show("feed-relations", null, "关联入口与资料链接", "-dark", true)
 @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun personalFollowNarrow() = show("personal-follow", "mylist:FOLLOW", "开源观察员", "-narrow-font2", scale = 2f)
 @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun personalCollectionNarrow() = show("personal-collection", "mylist:COLLECTION", "周末摄影精选", "-narrow-font2", scale = 2f)
 @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun feedRelationsNarrow() = show("feed-relations", null, "关联入口与资料链接", "-narrow-font2", scale = 2f)
 @Test fun relationWithoutUrlStillDisplaysDecodedLogo() = show("feed-relations", null, "关联入口与资料链接", "-readonly-relation", omitRelationUrl = true)
 private fun show(fixture: String, route: String?, expected: String, suffix: String = "", dark: Boolean = false, scale: Float = 1f, omitRelationUrl: Boolean = false) {
  val name = fixture + suffix
  images = IconFixtureImages(compose.activity)
  val store = AccountStore(object : CredentialPersistence {
   override fun read(): ByteArray? = null
   override fun write(bytes: ByteArray) = Unit
   override fun reset() = Unit
  }, guestDeviceId = "audit-synthetic-device")
  if (route != null) assertTrue(store.saveAuthenticated("123", "Synthetic fixture account", "audit-synthetic-device", listOf(Cookie.Builder()
   .name("token").value("synthetic-not-real").domain("coolapk.com").path("/").secure()
   .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot()))
  val originalSource = requireNotNull(javaClass.getResource("/reachable-cards/$fixture.json")).readText()
  // A valid optional-field omission is exercised through the wire decoder, never a manual DTO.
  val source = if (omitRelationUrl) {
   val root = Json.parseToJsonElement(originalSource).jsonObject
   val row = root.getValue("data").jsonArray.single().jsonObject
   val relation = row.getValue("relationRows").jsonArray.single().jsonObject
   JsonObject(root + ("data" to JsonArray(listOf(JsonObject(row + ("relationRows" to JsonArray(listOf(JsonObject(relation - "url"))))))))).toString()
  } else originalSource
  File("build/verification/reachable-cards/$name-input.json").apply { parentFile!!.mkdirs() }.writeText(source)
  client = OkHttpClient.Builder().dns(object : Dns {
   override fun lookup(host: String): List<InetAddress> = if (host in setOf("localhost", "127.0.0.1", "::1")) listOf(InetAddress.getLoopbackAddress())
    else { forbiddenDns += host; throw UnknownHostException("Audit blocks external DNS") }
  }).addInterceptor { chain ->
   val request = chain.request(); check(request.method == "GET")
   check(request.url.host in setOf("api.coolapk.com", "api2.coolapk.com", "www.coolapk.com"))
   val path = request.url.encodedPath; paths += path
   val body = when (path) {
    "/v6/user/followList" -> { assertEquals("123", request.url.queryParameter("uid")); check(fixture == "personal-follow"); source }
    "/v6/collection/list" -> { assertEquals("0", request.url.queryParameter("showDefault")); assertNull(request.url.queryParameter("uid")); check(fixture == "personal-collection"); source }
    "/v6/main/indexV8" -> if (fixture == "feed-relations") source else """{"data":[]}"""
    "/v6/user/feedList" -> { assertEquals("1002", request.url.queryParameter("uid")); """{"data":[{"id":"9301","entityType":"feed","username":"合成作者","message":"已打开联系人"}]}""" }
    "/v6/collection/itemList" -> {
     assertEquals("2001", request.url.queryParameter("id")); assertEquals("123", request.url.queryParameter("uid"))
     """{"data":[{"id":"9302","entityType":"feed","username":"合成作者","message":"本人收藏条目"}]}"""
    }
    "/v6/product/detail" -> { assertEquals("42", request.url.queryParameter("id")); """{"data":{"id":"42","entityType":"product","title":"已打开相机"}}""" }
    "/v6/topic/newTagDetail" -> { assertEquals("周末摄影", request.url.queryParameter("tag")); """{"data":{"id":"43","entityType":"topic","title":"已打开摄影话题"}}""" }
    else -> throw AssertionError("Unexpected fixture endpoint $path")
   }
   Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture")
    .body(body.toResponseBody("application/json".toMediaType())).build()
   // No chain.proceed: never sends fixture credentials or opens a network socket.
  }.build()
  val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
  val login = HttpLoginTransport(client)
  val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
  val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
   LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
  val accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "audit-synthetic-device"), repository, social)
  val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
  val model = BrowseViewModel(repository)
  compose.setContent {
   val state by store.state.collectAsState()
   CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, scale),
    LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
    MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
     NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false, openInBrowser = true), accounts, state, initialRoute = route)
    }
   }
  }
  try {
   compose.waitUntil(15_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
  } catch (failure: Throwable) {
   capture("$name-readiness-failure")
   throw failure
  }
  compose.onNodeWithText(expected).assertIsDisplayed()
  fun awaitText(text: String, key: String? = null) {
   compose.waitUntil(15_000) {
    val visible = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    visible && (key == null || model.states.value[key]?.let { it.loaded && !it.loading } == true)
   }
   compose.onNodeWithText(text).assertIsDisplayed()
  }
  fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
  val numbers = when (fixture) { "personal-follow" -> listOf(5); "personal-collection" -> listOf(6); else -> listOf(4, 8, 9) }
  compose.waitUntil(5_000) { numbers.all { "https://fixture.invalid/icon-$it.png" in images.loaded } }
  if (fixture == "feed-relations") {
   compose.onNodeWithText("摄影入门资料").performScrollTo().assertIsDisplayed()
   capture("$name-link", linkGlyph = true)
   compose.onNodeWithText("周末摄影").performScrollTo().assertIsDisplayed()
   capture(name, listOf("feed-relation-icon:42" to 8, "feed-relation-icon:43" to 9))
   compose.onNodeWithText("轻便相机").performScrollTo().performTouchInput { click() }
   awaitText("已打开相机", "product:42"); capture("$name-product"); back()
   if (omitRelationUrl) compose.onNodeWithText("周末摄影").assertHasNoClickAction()
   else {
    compose.onNodeWithText("周末摄影").performScrollTo().performTouchInput { click() }
    awaitText("已打开摄影话题", "topic:周末摄影"); capture("$name-topic"); back()
   }
   compose.onNodeWithText("摄影入门资料").performScrollTo().performTouchInput { click() }
   val intent = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
   assertNotNull("Explicit external-link gesture must dispatch", intent)
   assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
   assertEquals("https://example.com/article", intent.dataString)
   assertNull("No account data shared with public URL", intent.extras)
   assertEquals(1, paths.count { it == "/v6/main/indexV8" })
  } else {
   val collection = fixture == "personal-collection"
   val tag = if (collection) "entity-card:collection:2001" else "entity-card:contacts:1002"
   val icon = if (collection) "entity-icon:collection:2001" to 6 else "entity-icon:contacts:1002" to 5
   if (collection) { compose.onNodeWithText("128人关注").assertIsDisplayed(); compose.onNodeWithText("24个内容").assertIsDisplayed() }
   else { compose.onNodeWithText("120关注").assertIsDisplayed(); compose.onNodeWithText("980粉丝").assertIsDisplayed() }
   capture(name, listOf(icon))
   compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { click() }
   awaitText(if (collection) "本人收藏条目" else "已打开联系人", if (collection) null else "user:1002")
   capture("$name-destination")
   assertEquals("123", store.state.value.activeId)
   assertEquals(1, paths.count { it == if (collection) "/v6/collection/itemList" else "/v6/user/feedList" })
  }
 }
 private fun capture(name: String, expected: List<Pair<String, Int>> = emptyList(), linkGlyph: Boolean = false) {
  compose.waitForIdle()
  val bitmap = compose.runOnUiThread {
   val view = compose.activity.findViewById<View>(android.R.id.content)
   Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
  }
  expected.forEach { (tag, number) ->
   val rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
   assertTrue("Whole icon must be in viewport: $tag $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= bitmap.width && rect.bottom <= bitmap.height)
   val original = requireNotNull(javaClass.getResourceAsStream("/icon-grid/icon-$number.png")).use { requireNotNull(android.graphics.BitmapFactory.decodeStream(it)) }
   val colors = mutableMapOf<Int, Int>()
   for (y in 0 until original.height) for (x in 0 until original.width) {
    val pixel = original.getPixel(x, y)
    if (android.graphics.Color.alpha(pixel) > 200) colors[pixel] = (colors[pixel] ?: 0) + 1
   }
   val color = colors.maxBy { it.value }.key; original.recycle()
   var ink = 0
   for (y in rect.top.toInt() until rect.bottom.toInt()) for (x in rect.left.toInt() until rect.right.toInt()) if (bitmap.getPixel(x, y) == color) ink++
   assertTrue("Decoded source-colored icon ink is visible for $tag", ink > rect.width * rect.height / 10)
  }
  if (linkGlyph) {
   val rect = compose.onNodeWithTag("feed-link-icon:5001", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
   assertTrue(rect.top >= 0 && rect.bottom <= bitmap.height)
   // Inspect only the central icon area: rounded box corners cannot masquerade as glyph ink.
   val insetX = rect.width * .2f; val insetY = rect.height * .2f
   val colors = mutableMapOf<Int, Int>()
   for (y in (rect.top + insetY).toInt() until (rect.bottom - insetY).toInt()) for (x in (rect.left + insetX).toInt() until (rect.right - insetX).toInt()) {
    val color = bitmap.getPixel(x, y); colors[color] = (colors[color] ?: 0) + 1
   }
   val counts = colors.values.sortedDescending()
   assertTrue("Link glyph must have central foreground ink, not just colored box", counts.size >= 2 && counts[1] > rect.width * rect.height / 40)
  }
  File("build/verification/reachable-cards/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
  bitmap.recycle()
  File("build/verification/reachable-cards/$name.txt").writeText("API paths=$paths; image requests=${images.loaded}\n" + compose.onRoot(useUnmergedTree = true).printToString())
 }
}
