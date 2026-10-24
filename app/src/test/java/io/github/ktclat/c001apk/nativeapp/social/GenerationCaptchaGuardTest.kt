// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic provider proof and intercepted responses only; no real verification or network calls. */
class GenerationCaptchaGuardTest {
    private lateinit var accounts: AccountStore
    private lateinit var repository: SocialRepository
    private lateinit var client: OkHttpClient
    private val requests = CopyOnWriteArrayList<Request>()
    private var code = 200
    private var json = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""
    private val proof = "NEC:414e5c9b:synthetic-human-proof"
    private val official = "https://api.coolapk.com/v6/feed/createFeed".toHttpUrl()
    // The account adapter intentionally rejects loopback origins; map only synthetic session
    // callbacks to their production API scope, as in SocialCaptchaContractTest. HTTP stays intercepted.
    private fun mapped(session: RequestSession): RequestSession = session.copy(
        cookieFor = { session.cookieFor(official) },
        acceptCookies = { _, headers -> session.acceptCookies(official, headers) },
        acceptChallengeCookies = { _, headers -> session.acceptChallengeCookies?.invoke(official, headers)?.let(::mapped) },
    )
    @Before fun setup() {
        accounts = syntheticAccounts()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "127.0.0.1")
            check(request.url.encodedPath in setOf("/v6/feed/createFeed", "/v6/message/send"))
            requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                .body(json.toResponseBody()).build() // Never chain.proceed.
        }.build()
        repository = SocialRepository(accounts) { snapshot -> CoolapkHttpTransport(client = client,
            endpoints = ApiEndpoints("http://127.0.0.1:9/".toHttpUrl(), "http://127.0.0.1:9/".toHttpUrl()),
            requestSession = { mapped(accounts.requestSessionFor(snapshot)) }) }
    }
    @After fun cleanup() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    private fun WriteOutcome.pending() = requireNotNull((this as WriteOutcome.Rejected).continuation)
    private fun Request.fields(): Map<String, String> = (body as FormBody).let { f -> (0 until f.size).associate { f.name(it) to f.value(it) } }
    @Test fun verifiedPublishRetryKeepsOriginalPrivateDraftAndGeneration() = runBlocking {
        val generation = accounts.state.value.generation
        var draft = ComposerDraft("private original", selfOnly = true)
        val pending = repository.publish(draft, expectedGeneration = generation).pending()
        draft = draft.copy(text = "later public edit", selfOnly = false)
        assertFalse(draft.selfOnly)
        assertTrue(pending.acceptHumanProof(proof)); assertEquals(1, requests.size)
        json = """{"data":{"entityType":"feed","id":42}}"""
        assertTrue(pending.retryOnce() is WriteOutcome.Confirmed)
        assertEquals(2, requests.size)
        val original = requests[0].fields(); val replay = requests[1].fields()
        assertEquals("private original", replay["message"])
        assertEquals("1", replay["status"]); assertEquals("1", replay["publish_status"])
        assertEquals(original, replay - "_v2_post_token")
        assertEquals(generation, accounts.state.value.generation)
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected); assertEquals(2, requests.size)
    }
    @Test fun verifiedPublishCannotReplayAfterReturningToSameAccountWithNewGeneration() = runBlocking {
        val generation = accounts.state.value.generation
        val pending = repository.publish(ComposerDraft("private", selfOnly = true), expectedGeneration = generation).pending()
        assertTrue(pending.acceptHumanProof(proof))
        accounts.useGuest(); accounts.switchTo("1")
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
        assertEquals(1, requests.size)
    }
    @Test fun verifiedChatCannotReplayForNewlyActiveAccount() = runBlocking {
        val generation = accounts.state.value.generation
        val pending = repository.sendText("9", "original private message", expectedGeneration = generation).pending()
        assertTrue(pending.acceptHumanProof(proof)); accounts.switchTo("2")
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
        assertEquals(1, requests.size)
    }
    @Test fun unknownChatDeliveryAfterExplicitRetryIsTerminalAndDoesNotConfirmUi() = runBlocking {
        val generation = accounts.state.value.generation
        val runner = MutationRunner(); var confirmations = 0
        runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }, onConfirmed = { confirmations++ }) {
            repository.sendText("9", "original private message", expectedGeneration = generation)
        }
        val pending = ((runner.state.value as DeliveryState.Finished).outcome).pending()
        assertTrue(pending.acceptHumanProof(proof)); assertEquals(1, requests.size)
        code = 503; json = "unavailable"
        runner.resumeChallenge(pending)
        assertTrue((runner.state.value as DeliveryState.Finished).outcome is WriteOutcome.Uncertain)
        assertEquals(0, confirmations); assertFalse(runner.beginNext()); assertFalse(runner.allowCorrection())
        assertEquals("9", requests[1].url.queryParameter("uid"))
        val sent = Buffer().also { requests[1].body!!.writeTo(it) }.readUtf8()
        assertTrue(sent.contains("original private message")); assertTrue(sent.contains("_v2_post_token"))
        runner.resumeChallenge(pending)
        assertEquals(2, requests.size)
    }
}
