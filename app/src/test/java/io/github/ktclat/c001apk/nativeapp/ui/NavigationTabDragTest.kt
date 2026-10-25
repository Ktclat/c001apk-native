package io.github.ktclat.c001apk.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class NavigationTabDragTest {
    @Test fun mapsOnlyTheFixedRowAndRespectsRtl() {
        for (rtl in listOf(false, true)) for (tab in 0..2) {
            val expected = if (rtl) 2 - tab else tab
            assertEquals(expected, navigationTabAt(tab * 100f, 300f, 3, rtl))
            assertEquals(expected, navigationTabAt(tab * 100f + 99.9f, 300f, 3, rtl))
        }
        for (x in listOf(-1f, 300f, 301f, Float.NaN, Float.POSITIVE_INFINITY)) assertNull(navigationTabAt(x, 300f, 3, false))
        assertNull(navigationTabAt(0f, 0f, 3, false))
        assertNull(navigationTabAt(0f, 300f, 0, false))
    }
}
