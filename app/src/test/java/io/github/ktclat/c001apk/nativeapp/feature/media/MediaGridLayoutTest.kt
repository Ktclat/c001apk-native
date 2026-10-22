// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import org.junit.Assert.*
import org.junit.Test

class MediaGridLayoutTest {
    @Test fun sourceDimensionsControlSingleImageWidthsAndBoundLongHeight() {
        assertEquals(MediaGridLayout(310f, 155f, 1), mediaGridLayout(310f, 1, sourceImageSize("https://image.coolapk.com/a@800x400.jpg")))
        assertEquals(MediaGridLayout(200f, 200f, 1), mediaGridLayout(310f, 1, SourceImageSize()))
        val long = mediaGridLayout(310f, 1, SourceImageSize(100f, 1000f))
        assertEquals(100f, long.width, 0.001f); assertEquals(100f * 22 / 9, long.height, 0.001f)
    }
    @Test fun fourImagesUseTwoColumnsAndNineUseThree() {
        assertEquals(MediaGridLayout(100f, 100f, 2), mediaGridLayout(310f, 4, SourceImageSize()))
        assertEquals(MediaGridLayout(100f, 100f, 3), mediaGridLayout(310f, 9, SourceImageSize()))
    }
    @Test fun badgesAndMalformedDimensionsAreSafe() {
        assertEquals("GIF", mediaBadge("https://image.coolapk.com/a@100x1000.gif"))
        assertEquals("长图", mediaBadge("https://image.coolapk.com/a@100x1000.jpg"))
        assertNull(mediaBadge("https://image.coolapk.com/a@200x100.jpg"))
        assertEquals(SourceImageSize(), sourceImageSize("https://image.coolapk.com/a@0x0.jpg"))
        assertEquals("https://image.coolapk.com/a@200x100.jpg.s.jpg", thumbnailUrl("https://image.coolapk.com/a@200x100.jpg"))
        assertEquals("file:///fixture.png", thumbnailUrl("file:///fixture.png"))
    }
}
