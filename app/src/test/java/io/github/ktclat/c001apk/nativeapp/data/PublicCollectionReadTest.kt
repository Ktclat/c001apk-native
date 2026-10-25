// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Intercepted exact-path read fixtures only: never chain.proceed or write to a collection. */
class PublicCollectionReadTest {
    @Test fun guestReadIsAnIdOnlyGetAndKeepsRawTreeButUsesKnownListLeafCursor() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient(requests, """{"data":[{"id":"wrapper","entityId":"source-wrapper","entityType":"card","entityTemplate":"listCard","entities":[{"id":42,"entityType":"feed","message":"content"}]}]}""")
        try {
            val repo = HttpCoolapkRepository(client = client, requestSession = { guest() })
            val page = (repo.collectionItems("2001", PageCursor(2, "first", "last")) as ApiResult.Success).value
            assertEquals("wrapper", page.items.single().id)
            assertEquals(PageCursor(3, "first", "42"), page.nextCursor)
            val request = requests.single()
            assertEquals("GET", request.method); assertEquals("/v6/collection/itemList", request.url.encodedPath)
            assertEquals(setOf("id", "page", "firstItem", "lastItem"), request.url.queryParameterNames)
            assertEquals("2001", request.url.queryParameter("id")); assertEquals("2", request.url.queryParameter("page"))
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        } finally { close(client) }
    }
    @Test fun repeatedKnownWrapperKeepsPagingByNewLeafIdsWhileOtherCardsKeepTheirOwnIds() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient(requests, """{"data":[{"id":"same-wrapper","entityType":"card","entityTemplate":"listCard","entities":[{"id":43,"entityType":"feed","message":"next page"}]}]}""")
        try {
            val page = (HttpCoolapkRepository(client = client).collectionItems("2001", PageCursor(2, "42", "42")) as ApiResult.Success).value
            assertEquals(PageCursor(3, "42", "43"), page.nextCursor)
            assertEquals("same-wrapper", page.items.single().id)
        } finally { close(client) }
        val child = ContentItem("leaf", "feed")
        for (template in listOf("iconLinkGridCard", "imageCarouselCard_1", "futureCard")) {
            assertEquals(ListCursorBoundary("wrapper", "wrapper", true), listCardCursorBoundary(listOf(ContentItem("wrapper", "card", entityTemplate = template, children = listOf(child)))))
        }
        assertFalse(listCardCursorBoundary(listOf(ContentItem("bad", "card", entityTemplate = "listCard", childrenIncomplete = true))).complete)
    }
    @Test fun invalidIdsFailBeforeAnyRequestAndPrivateEmptyErrorsRemainHonest() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient(requests, """{"data":[]}""")
        try {
            val repo = HttpCoolapkRepository(client = client, requestSession = { guest() })
            for (id in listOf("", "-1", "1/2", "javascript:1", "https://host/1", "1".repeat(65))) {
                assertEquals(FailureKind.INVALID_ARGUMENT, (repo.collectionItems(id) as ApiResult.Failure).kind)
            }
            assertTrue(requests.isEmpty())
            val empty = (repo.collectionItems("2001") as ApiResult.Success).value
            assertTrue(empty.items.isEmpty()); assertNull(empty.nextCursor)
        } finally { close(client) }
        val privateClient = fixtureClient(mutableListOf(), """{"status":-1,"messageStatus":"err_request_login","message":"private collection"}""")
        try {
            assertEquals(FailureKind.AUTHENTICATION, (HttpCoolapkRepository(client = privateClient).collectionItems("2001") as ApiResult.Failure).kind)
        } finally { close(privateClient) }
        val failedClient = fixtureClient(mutableListOf(), """{"status":-2,"message":"collection unavailable"}""")
        try {
            assertEquals(FailureKind.BUSINESS, (HttpCoolapkRepository(client = failedClient).collectionItems("2001") as ApiResult.Failure).kind)
        } finally { close(failedClient) }
    }
    @Test fun supersededAccountScopeCannotPublishCollectionResponse() = runBlocking {
        var current = true
        val requests = mutableListOf<Request>()
        val client = fixtureClient(requests, """{"data":[{"id":42,"entityType":"feed","message":"stale"}]}""") { current = false }
        try {
            val repo = HttpCoolapkRepository(client = client, requestSession = { guest { current } })
            try { repo.collectionItems("2001"); fail("Departed account response must cancel") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, requests.size)
        } finally { close(client) }
    }
    private fun guest(current: () -> Boolean = { true }) = RequestSession({ null }, current, { _, _ -> }, isGuest = true)
    private fun fixtureClient(requests: MutableList<Request>, body: String, beforeResponse: () -> Unit = {}) = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request(); beforeResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun close(client: OkHttpClient) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
}
