// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/** API loopback plus an in-process OSS interceptor. No real upload or publication can occur. */
class FeedImagePipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var accounts: AccountStore
    private lateinit var repository: SocialRepository
    private val clients = mutableListOf<OkHttpClient>()
    private val official = "https://api.coolapk.com/v6/feed/createFeed".toHttpUrl()
    private fun mapped(session: RequestSession): RequestSession = session.copy(cookieFor = { session.cookieFor(official) },
        acceptCookies = { _, headers -> session.acceptCookies(official, headers) },
        acceptChallengeCookies = { _, headers -> session.acceptChallengeCookies?.invoke(official, headers)?.let(::mapped) })
    @Before fun setup() {
        server = MockWebServer(); server.start()
        accounts = AccountStore(object : CredentialPersistence {
            private var value: ByteArray? = null
            override fun read() = value?.copyOf()
            override fun write(bytes: ByteArray) { value = bytes.copyOf() }
            override fun reset() { value = null }
        })
        accounts.saveAuthenticated("1", "synthetic", "synthetic-device", listOf(Cookie.Builder().name("token").value("synthetic-only")
            .domain("coolapk.com").path("/").secure().expiresAt(System.currentTimeMillis() + 3_600_000).build()), accounts.snapshot())
        val apiClient = OkHttpClient().also(clients::add)
        repository = SocialRepository(accounts) { captured -> CoolapkHttpTransport(client = apiClient, endpoints = ApiEndpoints(server.url("/"), server.url("/")),
            requestSession = { mapped(accounts.requestSessionFor(captured)) }) }
    }
    @After fun teardown() {
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        server.shutdown()
    }
    private fun image(name: String): SelectedImage {
        val file = temporary.newFile(name); file.writeBytes("synthetic image $name".toByteArray())
        val md5 = MessageDigest.getInstance("MD5").digest(file.readBytes()).toByteString()
        return SelectedImage(file, "image/jpeg", 120, 80, md5.hex(), md5.base64(), name)
    }
    private fun prepare(images: List<SelectedImage>): JsonObject = buildJsonObject {
        put("uploadPrepareInfo", buildJsonObject {
            put("endPoint", "oss-cn-shenzhen.aliyuncs.com"); put("bucket", "synthetic-bucket")
            put("accessKeyId", "synthetic-access"); put("accessKeySecret", "synthetic-secret"); put("securityToken", "synthetic-token")
            put("uploadImagePrefix", "https://image.coolapk.com")
            put("expiration", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(System.currentTimeMillis() + 3_600_000)))
        })
        put("fileInfo", buildJsonArray { images.forEach { add(buildJsonObject {
            put("name", it.name); put("md5", it.md5Hex); put("uploadFileName", "feed/2026/${it.name}")
        }) } })
    }
    private fun enqueuePrepare(images: List<SelectedImage>) { server.enqueue(MockResponse().setBody(buildJsonObject { put("data", prepare(images)) }.toString())) }
    private fun uploader(calls: AtomicInteger, status: Int = 200) = OssImageUploader(OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet(); assertEquals("synthetic-bucket.oss-cn-shenzhen.aliyuncs.com", chain.request().url.host)
        assertNull(chain.request().header("Cookie")); assertNull(chain.request().header("X-App-Token"))
        chain.request().body!!.writeTo(okio.Buffer())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
            .body("".toResponseBody()).build()
    }.build().also(clients::add))
    private fun fields(body: String) = ("https://synthetic.invalid/?$body").toHttpUrl()
    @Test fun privateImagePublishFreezesPrivacyAndPicturesAcrossOneExplicitCaptchaRetry() = runBlocking {
        val images = listOf(image("a.jpg"), image("b.jpg")); val uploads = AtomicInteger()
        enqueuePrepare(images)
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""))
        var editableDraft = ComposerDraft("private frozen", selfOnly = true)
        val pending = requireNotNull((repository.publish(editableDraft, images, uploader(uploads)) as WriteOutcome.Rejected).continuation)
        editableDraft = editableDraft.copy(text = "edited later", selfOnly = false)
        val prepared = server.takeRequest(); val preparation = prepared.body.readUtf8()
        assertEquals("/v6/upload/ossUploadPrepare", prepared.path)
        assertTrue(preparation.contains("name=\"uploadBucket\"") && preparation.contains("\r\n\r\nimage\r\n")); assertTrue(preparation.contains("name=\"uploadDir\"") && preparation.contains("\r\n\r\nfeed\r\n"))
        assertFalse(preparation.contains("name=\"toUid\""))
        val original = fields(server.takeRequest().body.readUtf8())
        assertEquals("1", original.queryParameter("status")); assertEquals("1", original.queryParameter("publish_status"))
        assertEquals("https://image.coolapk.com/feed/2026/a.jpg,https://image.coolapk.com/feed/2026/b.jpg", original.queryParameter("pic"))
        assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof")); assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"data":{"id":77}}"""))
        assertEquals(WriteOutcome.Confirmed("77"), pending.retryOnce())
        val replay = fields(server.takeRequest().body.readUtf8())
        for (field in listOf("message", "status", "publish_status", "pic")) assertEquals(original.queryParameter(field), replay.queryParameter(field))
        assertFalse(editableDraft.selfOnly); assertEquals(2, uploads.get()); assertEquals(3, server.requestCount)
    }
    @Test fun unknownUploadStopsBeforePublicationAndNeverRepeatsOtherUploads() = runBlocking {
        val images = listOf(image("a.jpg"), image("b.jpg")); val uploads = AtomicInteger(); enqueuePrepare(images)
        assertTrue(repository.publish(ComposerDraft("draft"), images, uploader(uploads, 503)) is WriteOutcome.Uncertain)
        assertEquals(1, uploads.get()); assertEquals(1, server.requestCount)
    }
    @Test fun imageReplyPreservesReplyContractRatherThanFeedPrivacyFields() = runBlocking {
        val images = listOf(image("reply.jpg")); enqueuePrepare(images)
        server.enqueue(MockResponse().setBody("""{"data":{"id":78}}"""))
        assertTrue(repository.publish(ComposerDraft("reply", replyId = "77", replyToReply = true, replyAndForward = true), images, uploader(AtomicInteger())) is WriteOutcome.Confirmed)
        server.takeRequest(); val request = server.takeRequest(); val form = fields(request.body.readUtf8())
        assertEquals("/v6/feed/reply?id=77&type=reply", request.path); assertEquals("1", form.queryParameter("replyAndForward"))
        assertNotNull(form.queryParameter("pic")); assertNull(form.queryParameter("publish_status"))
    }
    @Test fun feedGrantRejectsChatObjectsForeignPrefixesDuplicatesAndExtraFiles() {
        val images = listOf(image("a.jpg")); val base = prepare(images); val now = System.currentTimeMillis()
        assertNotNull(parseFeedImageGrants(base, images, now))
        val info = base["uploadPrepareInfo"]!!.jsonObject
        assertNull(parseFeedImageGrants(JsonObject(base + ("uploadPrepareInfo" to JsonObject(info + ("uploadImagePrefix" to JsonPrimitive("https://evil.test"))))), images, now))
        val file = base["fileInfo"]!!.jsonArray.single().jsonObject
        assertNull(parseFeedImageGrants(JsonObject(base + ("fileInfo" to JsonArray(listOf(JsonObject(file + ("uploadFileName" to JsonPrimitive("message/a.jpg"))))))), images, now))
        assertNull(parseFeedImageGrants(JsonObject(base + ("fileInfo" to JsonArray(listOf(file, file)))), images, now))
        assertNull(parseFeedImageGrants(JsonObject(base + ("fileInfo" to JsonArray(listOf(JsonObject(file + ("uploadFileName" to JsonPrimitive("feed/a,b.jpg"))))))), images, now))
    }
    @Test fun publicImagePublicationUsesExplicitPublicFlag() = runBlocking {
        val images = listOf(image("public.jpg")); enqueuePrepare(images)
        server.enqueue(MockResponse().setBody("""{"data":{"id":80}}"""))
        assertTrue(repository.publish(ComposerDraft("public", selfOnly = false), images, uploader(AtomicInteger())) is WriteOutcome.Confirmed)
        server.takeRequest(); val form = fields(server.takeRequest().body.readUtf8())
        assertEquals("1", form.queryParameter("status")); assertEquals("0", form.queryParameter("publish_status"))
    }
    @Test fun uncertainPublicationNeverCreatesReplayOrRepeatsCompletedUpload() = runBlocking {
        val images = listOf(image("uncertain.jpg")); val uploads = AtomicInteger(); enqueuePrepare(images)
        server.enqueue(MockResponse().setResponseCode(503))
        val runner = MutationRunner()
        runner.submit { repository.publish(ComposerDraft("unknown delivery", selfOnly = true), images, uploader(uploads)) }
        assertTrue((runner.state.value as DeliveryState.Finished).outcome is WriteOutcome.Uncertain)
        assertFalse(runner.beginNext()); assertFalse(runner.allowCorrection())
        assertEquals(1, uploads.get()); assertEquals(2, server.requestCount)
    }
    @Test fun prepareChallengePerformsNoUploadUntilExplicitRetryAndCannotLoop() = runBlocking {
        val images = listOf(image("prepare.jpg")); val uploads = AtomicInteger()
        val challenge = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""
        server.enqueue(MockResponse().setBody(challenge))
        val pending = requireNotNull((repository.publish(ComposerDraft("frozen", selfOnly = true), images, uploader(uploads)) as WriteOutcome.Rejected).continuation)
        assertEquals(0, uploads.get()); assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
        assertEquals(0, uploads.get()); assertEquals(1, server.requestCount)
        enqueuePrepare(images); server.enqueue(MockResponse().setBody(challenge))
        val result = pending.retryOnce() as WriteOutcome.Rejected
        assertNull(result.continuation); assertEquals(1, uploads.get()); assertEquals(3, server.requestCount)
    }

    @Test fun overLimitPublicationRejectsBeforePrepareUploadOrPublish() = runBlocking {
        val calls = AtomicInteger()
        val image = image("too-long.jpg")
        try {
            val result = repository.publish(ComposerDraft("中".repeat(501), selfOnly = true), listOf(image), uploader(calls))
            assertTrue(result is WriteOutcome.Rejected)
            assertEquals(0, calls.get()); assertEquals(0, server.requestCount)
        } finally { image.close() }
    }

    @Test fun staleGenerationRejectsBeforeImagePreparationOrUpload() = runBlocking {
        val generation = accounts.state.value.generation
        val selected = image("stale.jpg"); val uploads = AtomicInteger(); val factories = AtomicInteger()
        val guarded = SocialRepository(accounts) { factories.incrementAndGet(); error("Stale image intent reached transport") }
        accounts.useGuest(); accounts.switchTo("1")
        try {
            val result = guarded.publish(ComposerDraft("private", selfOnly = true), listOf(selected), uploader(uploads), expectedGeneration = generation)
            assertTrue(result is WriteOutcome.Rejected)
            assertEquals(FailureKind.AUTHENTICATION, (result as WriteOutcome.Rejected).kind)
            assertEquals(0, factories.get()); assertEquals(0, uploads.get()); assertEquals(0, server.requestCount)
        } finally { selected.close() }
    }
    @Test fun verifiedImagePreparationCannotContinueAfterGenerationChanges() = runBlocking {
        val generation = accounts.state.value.generation
        val selected = image("stale-verified.jpg"); val uploads = AtomicInteger()
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""))
        try {
            val pending = requireNotNull((repository.publish(ComposerDraft("private", selfOnly = true), listOf(selected), uploader(uploads), expectedGeneration = generation) as WriteOutcome.Rejected).continuation)
            assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
            accounts.useGuest(); accounts.switchTo("1")
            assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
            assertEquals(0, uploads.get()); assertEquals(1, server.requestCount)
        } finally { selected.close() }
    }

    @Test fun deletedOrReplacedSpoolRejectsBeforePrepareRatherThanTextOnlyFallback() = runBlocking {
        for (delete in listOf(false, true)) {
            val selected = image(if (delete) "deleted.jpg" else "changed.jpg")
            if (delete) selected.close() else selected.file.writeBytes("different frozen bytes".toByteArray())
            val uploads = AtomicInteger()
            val result = repository.publish(ComposerDraft("preserve draft", selfOnly = true), listOf(selected), uploader(uploads))
            assertTrue(result is WriteOutcome.Rejected)
            assertEquals(0, uploads.get()); assertEquals(0, server.requestCount)
        }
    }
    @Test fun shuffledGrantOrderKeepsUserSelectedPictureOrder() = runBlocking {
        val images = listOf(image("first.jpg"), image("second.jpg"))
        val grant = prepare(images)
        server.enqueue(MockResponse().setBody(buildJsonObject { put("data", JsonObject(grant + ("fileInfo" to JsonArray(grant["fileInfo"]!!.jsonArray.reversed())))) }.toString()))
        server.enqueue(MockResponse().setBody("""{"data":{"id":83}}"""))
        assertTrue(repository.publish(ComposerDraft("ordered"), images, uploader(AtomicInteger())) is WriteOutcome.Confirmed)
        server.takeRequest()
        assertEquals("https://image.coolapk.com/feed/2026/first.jpg,https://image.coolapk.com/feed/2026/second.jpg", fields(server.takeRequest().body.readUtf8()).queryParameter("pic"))
    }
    @Test fun grantWithChangedMd5CannotUploadOrPublish() = runBlocking {
        val images = listOf(image("grant.jpg")); val grant = prepare(images)
        val entry = grant["fileInfo"]!!.jsonArray.single().jsonObject
        val badFile = JsonObject(entry + ("md5" to JsonPrimitive("0".repeat(32))))
        val badGrant = JsonObject(grant + ("fileInfo" to JsonArray(listOf(badFile))))
        server.enqueue(MockResponse().setBody(buildJsonObject { put("data", badGrant) }.toString()))
        val calls = AtomicInteger()
        assertTrue(repository.publish(ComposerDraft("keep"), images, uploader(calls)) is WriteOutcome.Rejected)
        assertEquals(0, calls.get()); assertEquals(1, server.requestCount)
    }
    @Test fun secondPutUnknownStopsRemainingUploadsAndPublication() = runBlocking {
        val images = listOf(image("one.jpg"), image("two.jpg"), image("three.jpg")); enqueuePrepare(images)
        val calls = AtomicInteger()
        val upload = OssImageUploader(OkHttpClient.Builder().addInterceptor { chain ->
            val n = calls.incrementAndGet(); chain.request().body!!.writeTo(okio.Buffer())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (n == 2) 503 else 200).message("synthetic").body("".toResponseBody()).build()
        }.build().also(clients::add))
        val runner = MutationRunner()
        runner.submit { repository.publish(ComposerDraft("keep partial", selfOnly = true), images, upload) }
        assertTrue((runner.state.value as DeliveryState.Finished).outcome is WriteOutcome.Uncertain)
        assertFalse(runner.allowCorrection()); assertFalse(runner.beginNext())
        assertEquals(2, calls.get()); assertEquals(1, server.requestCount)
    }
    @Test fun invalidatedSpoolDuringFinalChallengeDoesNotPublishWithoutImage() = runBlocking {
        val images = listOf(image("challenge.jpg")); val calls = AtomicInteger(); enqueuePrepare(images)
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""))
        val pending = requireNotNull((repository.publish(ComposerDraft("private", selfOnly = true), images, uploader(calls)) as WriteOutcome.Rejected).continuation)
        assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
        images.single().close()
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
        assertEquals(1, calls.get()); assertEquals(2, server.requestCount)
    }

    @Test fun cancelledPutCannotContinueIntoPublicationOrClaimRemoteDeletion() = runBlocking {
        val images = listOf(image("cancel.jpg")); enqueuePrepare(images)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); chain.request().body!!.writeTo(okio.Buffer()); started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body("".toResponseBody()).build()
        }.build()
        val runner = MutationRunner()
        val job = launch(Dispatchers.Default) { runner.submit { repository.publish(ComposerDraft("keep cancellation"), images, OssImageUploader(client)) } }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel(); release.countDown(); job.join()
            assertTrue((runner.state.value as DeliveryState.Finished).outcome is WriteOutcome.Uncertain)
            assertFalse(runner.allowCorrection()); assertEquals(1, calls.get()); assertEquals(1, server.requestCount)
        } finally { release.countDown(); job.cancelAndJoin(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun accountChangeAfterPutDoesNotPublishOrCallOldUiConfirmation() = runBlocking {
        val images = listOf(image("account.jpg")); enqueuePrepare(images)
        val generation = accounts.state.value.generation
        val calls = AtomicInteger(); val confirmations = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); chain.request().body!!.writeTo(okio.Buffer())
            accounts.useGuest()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body("".toResponseBody()).build()
        }.build()
        try {
            val runner = MutationRunner()
            try { runner.submit(isCurrent = { accounts.state.value.generation == generation }, onConfirmed = { confirmations.incrementAndGet() }) {
                repository.publish(ComposerDraft("keep account", selfOnly = true), images, OssImageUploader(client), expectedGeneration = generation)
            } } catch (_: CancellationException) { }
            assertEquals(1, calls.get()); assertEquals(1, server.requestCount); assertEquals(0, confirmations.get())
        } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun distinctImagesCannotShareOneIssuedObjectAndOverwriteEachOther() = runBlocking {
        val images = listOf(image("distinct-a.jpg"), image("distinct-b.jpg"))
        assertNotEquals(images[0].md5Hex, images[1].md5Hex)
        val grant = prepare(images)
        val aliases = grant["fileInfo"]!!.jsonArray.map { JsonObject(it.jsonObject + ("uploadFileName" to JsonPrimitive("feed/shared.jpg"))) }
        val invalid = JsonObject(grant + ("fileInfo" to JsonArray(aliases)))
        assertNull(parseFeedImageGrants(invalid, images, System.currentTimeMillis()))
        server.enqueue(MockResponse().setBody(buildJsonObject { put("data", invalid) }.toString()))
        val uploads = AtomicInteger()
        assertTrue(repository.publish(ComposerDraft("keep both", selfOnly = true), images, uploader(uploads)) is WriteOutcome.Rejected)
        assertEquals(0, uploads.get()); assertEquals(1, server.requestCount)
    }

}
