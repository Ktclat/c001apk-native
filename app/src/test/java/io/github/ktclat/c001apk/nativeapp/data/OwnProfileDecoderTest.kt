// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OwnProfileDecoderTest {
    private fun decode(body: String) = decodeOwnProfile(Json.parseToJsonElement(body), "123")
    @Test fun sourceFieldsAndExplicitZeroArePreserved() {
        val p = decode("""{"uid":123,"username":"a+b%20c","userAvatar":"https://avatar.coolapk.com/a.png","level":7,"experience":25,"next_level_experience":100,"feed":0,"follow":"9","fans":4}""")
        assertEquals("a+b c", p.name); assertEquals(0L, p.feed); assertEquals(9L, p.follow)
        assertEquals(4L, p.fans); assertEquals(7L, p.level); assertEquals(.25f, p.progress!!, .0001f)
        assertEquals("https://avatar.coolapk.com/a.png", p.avatarUrl)
    }
    @Test fun missingMalformedNegativeAndOverflowCountsNeverBecomeZero() {
        val p = decode("""{"uid":"123","feed":null,"follow":-1,"fans":"9223372036854775808","experience":false,"next_level_experience":0}""")
        assertNull(p.feed); assertNull(p.follow); assertNull(p.fans); assertNull(p.level); assertNull(p.experience); assertNull(p.progress)
        assertNull(decode("""{"uid":123,"feed":1.5}""").feed)
        assertNull(decode("""{"uid":123}""").avatarUrl)
    }
    @Test fun wrongMissingOrStructuredUidIsRejected() {
        listOf("{}", "[]", "{\"uid\":124}", "{\"uid\":{}}").forEach {
            try { decode(it); fail("mismatched profile accepted") } catch (_: ApiProblem) { }
        }
    }
    @Test fun badAvatarOriginsAndCredentialsAreNotForwardedToImageLoader() {
        listOf("http://other.example/a", "https://coolapk.com.evil.test/a", "https://u:p@avatar.coolapk.com/a", "file:///a", "https://avatar.coolapk.com:444/a").forEach {
            assertNull(decode("""{"uid":123,"userAvatar":"$it"}""").avatarUrl)
        }
    }
    @Test fun invalidEscapesRemainReadableAndProgressIsBounded() {
        val p = decode("""{"uid":123,"username":"bad%ZZ","experience":200,"next_level_experience":100}""")
        assertEquals("bad%ZZ",p.name); assertEquals(1f,p.progress!!,0f)
    }
}
