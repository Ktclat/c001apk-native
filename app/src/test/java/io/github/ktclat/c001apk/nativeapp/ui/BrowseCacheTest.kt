package io.github.ktclat.c001apk.nativeapp.ui
import org.junit.Assert.*
import org.junit.Test
class BrowseCacheTest {
    @Test fun currentAndInflightSurviveEviction() {
        val input = (0..30).associate { "feed:$it" to BrowseState(loaded = true, loading = it == 1) }
        val result = bounded(input, setOf("feed:0"))
        assertEquals(24, result.size)
        assertTrue(result.containsKey("feed:0")); assertTrue(result.containsKey("feed:1"))
    }
    @Test fun rootsSurviveEviction() {
        val input = mapOf("home" to BrowseState()) + (0..30).associate { "feed:$it" to BrowseState() }
        assertTrue(bounded(input).containsKey("home"))
    }
}
