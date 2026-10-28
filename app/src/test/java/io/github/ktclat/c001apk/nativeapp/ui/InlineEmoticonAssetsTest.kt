// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Original Android resources, not replacement drawable loaders or synthesized emoji artwork. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineEmoticonAssetsTest {
    private fun fixture(): List<JsonObject> {
        val raw = requireNotNull(javaClass.getResourceAsStream("/inline-emoticons-flutter-0d9ed002.json"))
            .use { it.readBytes() }
        assertEquals("13852ba6df5f681cceb30df17b65be7e7d7d82e21485bc6b758d251dca7ea5cc", sha256(raw))
        val root = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        assertEquals("0d9ed002980d935c1c9351393e9f16784aa78903", root.getValue("source_commit").jsonPrimitive.content)
        return root.getValue("entries").jsonArray.map { it.jsonObject }
    }

    @Test fun all187CatalogEntriesMatchOriginalFlutterTokenSourcePairs() {
        val entries = fixture()
        val expected = entries.associate { it.getValue("source").jsonPrimitive.content to it.getValue("token").jsonPrimitive.content }
        assertEquals(187, entries.size)
        assertEquals(187, expected.size)
        assertEquals(expected, InlineEmoticons.sourceTokens)
        assertEquals(expected.values.toSet(), InlineEmoticons.tokens)
        assertEquals(setOf("[置顶]", "[楼主]", "[层主]"), InlineEmoticons.labels)
        assertEquals("[图片]", InlineEmoticons.PHOTO)
        assertEquals(128, InlineEmoticons.MAX_PER_TEXT)
        assertEquals(expected.values.maxOf { it.length }, InlineEmoticons.maxTokenLength)
        expected.values.forEach { assertTrue(InlineEmoticons.contains(it)) }
        assertFalse(InlineEmoticons.contains("[不存在]"))
        assertFalse(InlineEmoticons.contains("[[笑哭]"))
        assertNull(InlineEmoticons.images["[不存在]"])
    }

    @Test fun all183ResourcesHaveOriginalBytesCorrectNamesAndDecodeActualPixels() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val entries = fixture().filter { it.getValue("source").jsonPrimitive.content.endsWith(".png") }
        assertEquals(183, InlineEmoticons.images.size)
        var bytes = 0L
        for (entry in entries) {
            val token = entry.getValue("token").jsonPrimitive.content
            val source = entry.getValue("source").jsonPrimitive.content
            val id = requireNotNull(InlineEmoticons.images[token])
            assertEquals(source.removeSuffix(".png"), resources.getResourceEntryName(id))
            val original = resources.openRawResource(id).use { it.readBytes() }
            bytes += original.size
            assertEquals("Original PNG bytes for $token", entry.getValue("sha256").jsonPrimitive.content, sha256(original))
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(original, 0, original.size))
            assertTrue("Decoded artwork dimensions for $token", bitmap.width > 0 && bitmap.height > 0)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Original artwork is not an empty placeholder for $token", pixels.any { it ushr 24 != 0 })
            bitmap.recycle()
        }
        assertEquals(1_702_238L, bytes)
    }

    @Test fun userReportedNamedEmojisKeepTheirIndependentlyRecordedOriginalHashes() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        for ((token, expected) in mapOf(
            "[笑哭]" to "bdb3fcad097330e13b732b8f48fce58698c109f1b9a3749b64060db499a74e3f",
            "[受虐滑稽]" to "b1e66aef41afb66b39666498217b374ea9772a7169b4dfc10805179310a2d025",
        )) {
            assertEquals(expected, sha256(resources.openRawResource(requireNotNull(InlineEmoticons.images[token])).use { it.readBytes() }))
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
