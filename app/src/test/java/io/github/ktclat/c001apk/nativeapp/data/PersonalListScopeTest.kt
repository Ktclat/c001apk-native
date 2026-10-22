package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Synthetic session/loopback only. No real login, cookies, or account operations. */
class PersonalListScopeTest {
 @Test fun switchDuringCaptureSendsNoRequest() = runBlocking {
  MockWebServer().use { server ->
   server.start(); var current = true
   val repository = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"),server.url("/")),requestSession = {
    current = false
    RequestSession({ null }, { true }, { _, _ -> }, isSessionCurrent = { true })
   })
   val scoped = repository.personalLists { current }
   try { scoped.load(PersonalListRequest(PersonalListKind.FAN),"123",PageCursor()); fail("must cancel") } catch(_: CancellationException) { }
   assertEquals(0,server.requestCount)
  }
 }
 @Test fun switchImmediatelyBeforeCloneSendsNoRequest() = runBlocking {
  MockWebServer().use { server ->
   server.start(); var current = true
   val repository = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"),server.url("/")),requestSession = {
    RequestSession({ null }, { current = false; true }, { _, _ -> }, isSessionCurrent = { true })
   })
   try { repository.personalLists { current }.load(PersonalListRequest(PersonalListKind.LIKE),"123",PageCursor()); fail("must cancel") } catch(_: CancellationException) { }
   assertEquals(0,server.requestCount)
  }
 }
 @Test fun capturedSessionIsUsedOnceAndLateResponseIsRejected() = runBlocking {
  MockWebServer().use { server ->
   server.start(); server.enqueue(MockResponse().setBody("""{"data":[{"id":"8","entityType":"collection","title":"old"}]}""").setBodyDelay(200,TimeUnit.MILLISECONDS))
   var captures = 0
   val current = java.util.concurrent.atomic.AtomicBoolean(true)
   val repository = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"),server.url("/")),requestSession = {
    captures++
    RequestSession({ null }, { current.get() }, { _, _ -> }, isSessionCurrent = { current.get() })
   })
   val result = async(Dispatchers.Default) { repository.personalLists { current.get() }.load(PersonalListRequest(PersonalListKind.COLLECTION),"123",PageCursor()) }
   assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) })
   current.set(false)
   try { result.await(); fail("old response published") } catch(_: CancellationException) { }
   assertEquals(1,captures)
  }
 }
}
