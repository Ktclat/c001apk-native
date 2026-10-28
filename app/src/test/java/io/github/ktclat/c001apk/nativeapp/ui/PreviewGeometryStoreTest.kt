// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.ktclat.c001apk.nativeapp.domain.CoolapkRepository
import java.lang.reflect.Proxy
import org.junit.Test
import org.junit.Assert.*

class PreviewGeometryStoreTest {
    private fun key(body: String = "body") = PreviewGeometryKey(requireNotNull(previewBodyIdentity(body)),
        Constraints(maxWidth = 600), 2f, 1f, LayoutDirection.Ltr, "zh-CN", 0, TextStyle(fontSize = 16.sp),
        TextStyle(fontSize = 12.sp), TextStyle(fontSize = 14.sp), TextStyle.Default, 48.dp, false, 0, false)
    @Test fun boundedRawIdentityNeverHashesOnlyAnOversizePrefix() {
        assertNotNull(previewBodyIdentity("a".repeat(12000)))
        assertNull(previewBodyIdentity("a".repeat(12000) + "x"))
        assertNull(previewBodyIdentity("a".repeat(12000) + "y"))
        assertNotEquals(previewBodyIdentity("\uD83D"), previewBodyIdentity("\uFFFD"))
        assertNotEquals(previewBodyIdentity("same-one"), previewBodyIdentity("same-two"))
    }
    @Test fun exactTypedLayoutAndBodyAreRequired() {
        val store = PreviewGeometryStore(); val a = key(); store.put(a, 1200)
        val mismatches = listOf(key("changed"), a.copy(constraints = Constraints(maxWidth = 599)),
            a.copy(localeTags = "ja-JP"), a.copy(fontWeightAdjustment = 300),
            a.copy(density = 3f), a.copy(fontScale = 1.6f), a.copy(direction = LayoutDirection.Rtl),
            a.copy(readingStyle = a.readingStyle.copy(lineHeight = 28.sp)),
            a.copy(readingStyle = a.readingStyle.copy(fontFamily = FontFamily.Monospace)),
            a.copy(readingStyle = a.readingStyle.copy(letterSpacing = 1.sp)),
            a.copy(smallStyle = TextStyle(fontSize = 20.sp)), a.copy(buttonStyle = TextStyle(fontSize = 20.sp)),
            a.copy(ambientStyle = TextStyle(fontSize = 20.sp)), a.copy(minimumInteractiveSize = 60.dp),
            a.copy(expanded = true), a.copy(page = 1), a.copy(sourceIncomplete = true), a.copy(showEmoticons = false))
        mismatches.forEach { assertNull(store.height(it)) }
        assertEquals(1200, store.height(a))
    }
    @Test fun heightsAndOwnersAreStrictlyBounded() {
        val store = PreviewGeometryStore(); val a = key()
        for (height in listOf(-1,0,16385,131073,Int.MAX_VALUE)) { store.put(a,height); assertNull(store.height(a)) }
        store.put(a,16384); assertEquals(16384,store.height(a))
        store.close(); assertEquals(0,store.size()); store.put(a,100); assertNull(store.height(a))
        assertNull(PreviewGeometryStore().height(a))
    }
    @Test fun lruNeverExceeds64AndRecentlyUsedEntrySurvives() {
        val store = PreviewGeometryStore()
        repeat(64) { store.put(key("$it"),100) }
        assertEquals(100,store.height(key("0")))
        store.put(key("64"),100)
        assertEquals(64,store.size()); assertNull(store.height(key("1"))); assertEquals(100,store.height(key("0")))
    }
    @Test fun ownerGenerationClearsOldModelAndRejectsLateWrites() {
        val owner = SessionViewModelStore()
        val repo = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,arrayOf(CoolapkRepository::class.java)) { _, _, _ ->
            throw AssertionError("Geometry ownership must not call repository")
        } as CoolapkRepository
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowseViewModel(repo) as T
        }
        owner.select(1)
        val first = ViewModelProvider(owner.viewModelStore,factory)[BrowseViewModel::class.java]
        val a=key(); first.previewGeometry.put(a,100)
        owner.select(2)
        val next = ViewModelProvider(owner.viewModelStore,factory)[BrowseViewModel::class.java]
        assertNotSame(first,next); assertEquals(0,first.previewGeometry.size()); assertNull(next.previewGeometry.height(a))
        first.previewGeometry.put(a,100); assertEquals(0,first.previewGeometry.size())
        owner.viewModelStore.clear()
    }
}
