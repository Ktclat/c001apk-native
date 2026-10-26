// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.social.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Actual AppContainer→AccountRuntime production factory and AccountSessionAdapter.
 * Only credential persistence and the factory's HTTP client are replaced with memory/socket-free
 * fixtures. The original transport factory stays unscoped; SocialRepository must capture it itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeReadProductionWiringTest {
    @Test fun actualFactoryLoadsOrdinaryNoticesAndCreatesAReadCapabilityWithoutPrivilegedFakeScopes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vault = File(app.noBackupFilesDir, "account-vault.bin")
        assertFalse("Fixture must not open an existing credential vault", vault.exists())
        val container = AppContainer(app).also { it.nativeUpdates.enabled = false }
        val runtime = container.accounts(); assertSame(runtime, container.accounts())
        val store = runtime.store; var persistenceWrites = 0
        val memory = object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) { persistenceWrites++ }
            override fun reset() = Unit
        }
        AccountStore::class.java.getDeclaredField("persistence").apply { isAccessible = true }.set(store, memory)
        val origin = "https://api.coolapk.com/".toHttpUrl()
        val cookies = listOf("uid=123", "token=synthetic-production-wiring", "SESSID=synthetic-original").map {
            Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!!
        }
        check(store.saveAuthenticated("123", "Synthetic", "synthetic-production-device", cookies, store.snapshot()))
        val baselineWrites = persistenceWrites; val requests = CopyOnWriteArrayList<Request>()
        var requireChallenge = false
        val client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = throw AssertionError("Fixture may not resolve external hosts")
        })
            .addInterceptor { chain ->
                val request = chain.request(); requests += request
                check(request.method == "GET" && request.url.host == "api.coolapk.com" && request.url.encodedPath in setOf(
                    "/v6/notification/atMeList", "/v6/notification/feedLikeList", "/v6/notification/contactsFollowList"))
                val body = if (requireChallenge && !request.header("Cookie").orEmpty().contains("validate="))
                    """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":""}}"""
                    else """{"data":[{"id":7,"entityType":"notification","fromuid":44,"note":"synthetic ordinary notice"}]}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(body.toResponseBody())
                    .apply { if (requireChallenge && !request.header("Cookie").orEmpty().contains("validate="))
                        addHeader("Set-Cookie", "SESSID=synthetic-read; Domain=coolapk.com; Path=/; Secure") }.build()
                // Never chain.proceed: production authorities/headers, zero sockets.
            }.build()
        try {
            val social = runtime.socialRepository
            val factoryField = SocialRepository::class.java.getDeclaredField("transportFor").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val productionFactory = factoryField.get(social) as (SessionSnapshot) -> CoolapkHttpTransport
            val snapshot = store.snapshot(); val ordinaryTransport = productionFactory(snapshot)
            try { ordinaryTransport.capturedReadSession(); fail("Real initial transport should still be unscoped") }
            catch (_: IllegalStateException) { }
            val captured = requireNotNull(ordinaryTransport.forSingleOperation().capturedReadSession())
            assertSame(snapshot.owner, captured.readOwner); assertEquals(snapshot.generation, captured.diagnosticGeneration)
            assertEquals(snapshot.deviceId, captured.deviceCode); assertTrue(captured.isReadSessionCurrent!!.invoke())
            val clientField = CoolapkHttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
            val socketFreeFactory: (SessionSnapshot) -> CoolapkHttpTransport = { owner ->
                productionFactory(owner).also { transport -> clientField.set(transport, client) }
            }
            factoryField.set(social, socketFreeFactory)
            val normal = social.notices(NoticeCategory.MENTIONS, expectedGeneration = snapshot.generation)
            assertEquals("7", (normal as ApiResult.Success).value.items.single().id)
            assertEquals(1, requests.size); assertFalse(requests.single().header("Cookie").orEmpty().contains("validate="))
            assertTrue(requests.single().header("Cookie").orEmpty().contains("token=synthetic-production-wiring"))
            assertEquals(snapshot.deviceId, requests.single().header("X-App-Device"))
            requireChallenge = true
            val failure = social.notices(NoticeCategory.LIKES, expectedGeneration = snapshot.generation) as ApiResult.Failure
            val pending = requireNotNull(failure.noticeRead); assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic-production-proof"))
            assertEquals(2, requests.size); assertTrue(pending.retryOnce() is ApiResult.Success); assertEquals(3, requests.size)
            assertTrue(social.notices(NoticeCategory.FOLLOWERS) is ApiResult.Success)
            assertTrue(requests.last().header("Cookie").orEmpty().contains("synthetic-production-proof"))
            assertEquals(baselineWrites, persistenceWrites); assertFalse(vault.exists())
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
