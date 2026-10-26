// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UserSpaceDecoderTest {
    private fun decode(raw: String, uid: String = "7") = decodeUserSpace(Json.parseToJsonElement(raw), uid)
    @Test fun exactFlutterSpaceRolesPreserveNestedNameAvatarCoverAndObservedCounts() {
        val user = decode("""{"uid":7,"username":"fallback","userInfo":{"uid":7,"username":"%E6%B5%8B%E8%AF%95+name","userAvatar":"https://example.invalid/nested.png"},"userAvatar":"http://avatar.coolapk.com/real-role.png","cover":"http://image.coolapk.com/cover.jpg","level":0,"feed":"24","be_like_num":106,"follow":12,"fans":48,"isFollow":1}""")
        assertEquals("7", user.userId); assertEquals("user", user.kind); assertEquals("测试+name", user.userName)
        assertEquals("https://avatar.coolapk.com/real-role.png", user.avatarUrl)
        assertEquals(UserSpaceProfile("https://image.coolapk.com/cover.jpg", 0, 24, 106, 12, 48), user.userProfile)
        assertNull(user.feed); assertNull(user.replyInteraction); assertTrue(user.imageUrls.isEmpty())
    }
    @Test fun missingMalformedAndNegativeCountsAreNotInventedZeros() {
        val profile = decode("""{"uid":"7","level":-1,"feed":"","be_like_num":"1e5","follow":null,"fans":"9223372036854775808"}""").userProfile!!
        assertEquals(UserSpaceProfile(), profile)
        assertEquals("", decode("""{"uid":7}""").userName)
    }
    @Test fun missingOrMismatchedRootOrNestedIdentityRejectsTheWholeProfile() {
        for (body in listOf("{}", "null", "[]", """{"uid":8}""", """{"uid":7,"userInfo":{"uid":8,"username":"wrong"}}""")) {
            try { decode(body); fail(body) } catch (error: ApiProblem) { assertEquals(FailureKind.INVALID_RESPONSE, error.failure.kind) }
        }
    }
    @Test fun unknownImagesNeverUpgradeHttpAndNamesUrlsAreBounded() {
        val user = decode("""{"uid":7,"username":"${"a".repeat(600)}","userAvatar":"http://evil.invalid/avatar.png","cover":"file:///private"}""")
        assertEquals(512, user.userName.length); assertNull(user.avatarUrl); assertNull(user.userProfile!!.coverUrl)
        assertNull(decode("""{"uid":7,"userAvatar":"https://example.invalid/${"a".repeat(4096)}"}""").avatarUrl)
    }
    @Test fun independentNestedFieldFallbackAndLiteralNamesDoNotRenderHtml() {
        val user = decode("""{"uid":"7","username":"<b>literal</b>","userInfo":{"username":"","userAvatar":"https://example.invalid/avatar.png"}}""")
        assertEquals("<b>literal</b>", user.userName); assertEquals("https://example.invalid/avatar.png", user.avatarUrl)
    }
}
