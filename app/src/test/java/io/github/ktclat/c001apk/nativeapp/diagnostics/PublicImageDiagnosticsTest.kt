// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import io.github.ktclat.c001apk.nativeapp.feature.media.ImageLoadFailure
import io.github.ktclat.c001apk.nativeapp.feature.media.ImageLoadFailureKind
import org.junit.Assert.*
import org.junit.Test

class PublicImageDiagnosticsTest {
    @Test fun aggregatesAreBoundedAndCopiesContainOnlyClosedFields() {
        val counters = PublicImageCounters()
        fun event(code: Int) = PublicImageObservation(PublicImageHost.IMAGE_CDN,
            ImageLoadFailure(ImageLoadFailureKind.HTTP, code), PublicImageType.HTML, PublicImageSource.NETWORK)
        repeat(30) { counters.record(event(400 + it)) }
        assertEquals(16, counters.snapshot().size)
        repeat(10_100) { counters.record(event(567)) }
        val snapshot = counters.snapshot()
        assertEquals(16, snapshot.size)
        assertEquals(9999, snapshot.last().second)
        assertEquals("host=IMAGE_CDN; result=HTTP; HTTP=567; contentType=HTML; source=NETWORK", snapshot.last().first.text())
        counters.record(event(568))
        assertEquals(567, snapshot.last().first.failure?.httpStatus)
    }
    @Test fun mimeClassifierCannotExportParametersOrArbitraryStrings() {
        assertEquals(PublicImageType.IMAGE, publicImageType("image/png; token=SECRET"))
        assertEquals(PublicImageType.HTML, publicImageType(" text/html ; uid=SECRET"))
        assertEquals(PublicImageType.OTHER, publicImageType("SECRET"))
        assertEquals(PublicImageType.UNKNOWN, publicImageType(null))
    }
}
