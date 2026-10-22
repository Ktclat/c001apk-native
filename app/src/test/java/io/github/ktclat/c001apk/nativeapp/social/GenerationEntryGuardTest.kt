// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic accounts and intercepted HTTP only; no sockets, real tokens, or remote mutations. */
class GenerationEntryGuardTest {
    private lateinit var accounts: AccountStore
    private lateinit var repository: SocialRepository
    private lateinit var client: OkHttpClient
    private lateinit var image: SelectedImage
    private val factories = AtomicInteger()
    private val requests = CopyOnWriteArrayList<Request>()
    private var responseCode = 200
    private var responseBody = """{"data":{"entityType":"message","id":77}}"""
    private var beforeResponse: () -> Unit = {}

    @Before fun setup() {
        accounts = syntheticAccounts()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); check(request.url.host == "127.0.0.1")
            requests.add(request); beforeResponse()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode)
                .message("synthetic").body(responseBody.toResponseBody()).build()
            // Never chain.proceed: no request leaves this process.
        }.build()
        repository = SocialRepository(accounts) { captured ->
            factories.incrementAndGet()
            CoolapkHttpTransport(client = client, endpoints = ApiEndpoints("http://127.0.0.1:9/".toHttpUrl(), "http://127.0.0.1:9/".toHttpUrl()),
                requestSession = { accounts.requestSessionFor(captured) })
        }
        val file = File.createTempFile("synthetic-generation-", ".png").apply { writeBytes(byteArrayOf(1,2,3)) }
        image = SelectedImage(file, "image/png", 1, 1, "00".repeat(16), "AAAAAAAAAAAAAAAAAAAAAA==", "synthetic.png")
    }
    @After fun cleanup() {
        image.close(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
    }
    private fun action(name: String, block: suspend (Long) -> WriteOutcome) = name to block
    @Test fun switchBetweenSuccessfulUiCheckAndRepositoryEntryRejectsEveryWriteBeforeTransport() = runBlocking {
        val actions: List<Pair<String, suspend (Long) -> WriteOutcome>> = listOf(
            action("publish") { g -> repository.publish(ComposerDraft("synthetic", selfOnly = true), expectedGeneration = g) },
            action("reply") { g -> repository.publish(ComposerDraft("synthetic", replyId = "4", replyToReply = true), expectedGeneration = g) },
            action("text") { g -> repository.sendText("9", "synthetic", expectedGeneration = g) },
            action("feed share") { g -> repository.sendFeed("9", "42", expectedGeneration = g) },
            action("image") { g -> repository.sendImage("9", image, expectedGeneration = g) },
            action("pin") { g -> repository.conversationPinned("1_9", true, expectedGeneration = g) },
            action("message delete") { g -> repository.delete(DeleteTarget.MESSAGE, "4", "1_9", expectedGeneration = g) },
            action("conversation delete") { g -> repository.delete(DeleteTarget.CONVERSATION, "4", "1_9", expectedGeneration = g) },
            action("feed delete") { g -> repository.delete(DeleteTarget.FEED, "4", expectedGeneration = g) },
            action("reply delete") { g -> repository.delete(DeleteTarget.REPLY, "4", expectedGeneration = g) },
            action("feed like") { g -> repository.like("4", false, true, expectedGeneration = g) },
            action("reply unlike") { g -> repository.like("4", true, false, expectedGeneration = g) },
            action("follow") { g -> repository.follow(FollowTarget.USER, "9", true, expectedGeneration = g) },
        )
        for ((name, action) in actions) {
            accounts.switchTo("1")
            val generation = accounts.state.value.generation
            val runner = MutationRunner()
            var checked = false
            var outcome: WriteOutcome? = null
            runner.submit(isCurrent = {
                val matches = repository.isCurrentGeneration(generation)
                if (!checked) { assertTrue(matches); checked = true; accounts.switchTo("2") }
                matches
            }) { action(generation).also { outcome = it } }
            assertTrue("UI precheck must really have passed: $name", checked)
            assertTrue("$name: $outcome", outcome is WriteOutcome.Rejected)
            assertEquals(name, FailureKind.AUTHENTICATION, (outcome as WriteOutcome.Rejected).kind)
            assertEquals(name, 0, factories.get()); assertTrue(name, requests.isEmpty())
        }
    }
    @Test fun sameAccountReturningWithNewGenerationCannotReuseOldIntent() = runBlocking {
        val generation = accounts.state.value.generation
        accounts.useGuest(); accounts.switchTo("1")
        assertFalse(repository.isCurrentGeneration(generation))
        assertTrue(repository.publish(ComposerDraft("synthetic"), expectedGeneration = generation) is WriteOutcome.Rejected)
        assertTrue(repository.sendImage("9", image, expectedGeneration = generation) is WriteOutcome.Rejected)
        assertEquals(0, factories.get()); assertTrue(requests.isEmpty())
    }
    @Test fun currentGenerationCanPublishAndSendWithoutChangingPrivacyOrPayload() = runBlocking {
        val generation = accounts.state.value.generation
        assertTrue(repository.publish(ComposerDraft("synthetic", selfOnly = true), expectedGeneration = generation) is WriteOutcome.Confirmed)
        assertTrue(repository.sendText("9", "synthetic message", expectedGeneration = generation) is WriteOutcome.Confirmed)
        assertEquals(2, requests.size)
        assertEquals("/v6/feed/createFeed", requests[0].url.encodedPath)
        val form = requests[0].body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("1", fields["publish_status"]); assertEquals("1", fields["status"])
        assertEquals("synthetic", fields["message"])
        assertEquals("/v6/message/send", requests[1].url.encodedPath)
        assertEquals("9", requests[1].url.queryParameter("uid"))
    }
    @Test fun switchDuringRequestCannotCommitSuccessOrReplay() = runBlocking {
        val generation = accounts.state.value.generation
        beforeResponse = { accounts.switchTo("2") }
        val runner = MutationRunner()
        try {
            runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) {
                repository.sendText("9", "synthetic", expectedGeneration = generation)
            }
            fail("Stale in-flight result must cancel")
        } catch (_: CancellationException) { }
        assertFalse(runner.beginNext()); assertFalse(runner.allowCorrection())
        runner.submit { fail("A stale pending write must not replay"); WriteOutcome.Confirmed() }
        assertEquals(1, requests.size)
    }
    @Test fun unknownDeliveryIsTerminalAndNeverAutomaticallyReplayed() = runBlocking {
        responseCode = 503
        val generation = accounts.state.value.generation
        val runner = MutationRunner()
        runner.submit { repository.sendText("9", "synthetic", expectedGeneration = generation) }
        assertTrue((runner.state.value as DeliveryState.Finished).outcome is WriteOutcome.Uncertain)
        assertFalse(runner.allowCorrection()); assertFalse(runner.beginNext())
        runner.submit { fail("Uncertain writes cannot replay"); WriteOutcome.Confirmed() }
        assertEquals(1, requests.size)
    }
    @Test fun captchaRejectionDoesNotAutomaticallyRetryWithoutExplicitHumanProof() = runBlocking {
        responseBody = """{"messageStatus":"err_request_captcha_v2"}"""
        val generation = accounts.state.value.generation
        val runner = MutationRunner()
        runner.submit { repository.publish(ComposerDraft("synthetic"), expectedGeneration = generation) }
        val outcome = (runner.state.value as DeliveryState.Finished).outcome as WriteOutcome.Rejected
        assertEquals(FailureKind.CAPTCHA, outcome.kind)
        assertFalse(runner.allowCorrection()); assertFalse(runner.beginNext())
        accounts.useGuest(); accounts.switchTo("1")
        runner.submit { fail("Unverified CAPTCHA cannot replay through normal submit"); WriteOutcome.Confirmed() }
        assertEquals(1, requests.size)
    }
}

internal fun syntheticAccounts(): AccountStore {
    val store = AccountStore(object : CredentialPersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun reset() { bytes = null }
    })
    for (id in listOf("1", "2")) assertTrue(store.saveAuthenticated(id, "synthetic-$id", "synthetic-device",
        listOf(Cookie.Builder().name("token").value("synthetic-only-$id").domain("coolapk.com").path("/")
            .secure().expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot()))
    store.switchTo("1")
    return store
}
