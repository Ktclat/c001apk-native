// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NotificationCountsDecoderTest {
    private fun decode(body: String) = decodeNotificationCounts(Json.parseToJsonElement(body), "123")
    @Test fun allSixSourceFieldsAndZeroAreExact() {
        val result = decode("""{"atme":0,"atcommentme":1,"feedlike":99,"contacts_follow":100,"message":7,"notification":9}""")
        assertEquals("123", result.uid); assertEquals(0L, result.atMe); assertEquals(1L, result.atCommentMe)
        assertEquals(99L, result.feedLike); assertEquals(100L, result.contactsFollow)
        assertEquals(7L, result.message); assertEquals(9L, result.notification)
        assertEquals(16L, result.messagesAndNotifications); assertFalse(result.hasUnknown)
    }
    @Test fun malformedIndividualFieldsStayUnknownWithoutPoisoningValidNeighbors() {
        listOf("null", "true", "false", "-1", "-0", "1.0", "1.5", "1e2", "1E+2", "\"9\"", "\"0\"", "[]", "{}", "9223372036854775808").forEach { invalid ->
            val result = decode("""{"atme":$invalid,"atcommentme":1}""")
            assertNull("Invalid literal $invalid", result.atMe); assertEquals(1L, result.atCommentMe)
            assertTrue(result.hasUnknown)
        }
        assertNull(decode("{}").atMe)
    }
    @Test fun longBoundaryAndCheckedSumDoNotClampWrapOrInferMissingComponents() {
        assertEquals(Long.MAX_VALUE, decode("""{"atme":9223372036854775807}""").atMe)
        assertEquals(Long.MAX_VALUE, decode("""{"message":9223372036854775807,"notification":0}""").messagesAndNotifications)
        assertNull(decode("""{"message":9223372036854775807,"notification":1}""").messagesAndNotifications)
        assertNull(decode("""{"message":0}""").messagesAndNotifications)
        assertNull(decode("""{"notification":0}""").messagesAndNotifications)
        assertEquals(0L, decode("""{"message":0,"notification":0}""").messagesAndNotifications)
    }
    @Test fun wrongDataShapeIsInvalidResponseNotAnEmptySuccessfulSnapshot() {
        listOf("[]", "null", "0", "true", "\"object\"").forEach { body ->
            try { decode(body); fail("Wrong data shape accepted: $body") }
            catch (problem: ApiProblem) { assertEquals(FailureKind.INVALID_RESPONSE, problem.failure.kind) }
        }
    }
}
