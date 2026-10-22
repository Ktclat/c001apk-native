// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class CompactRootHeaderPolicyTest {
    @Test fun exact240dpFont2BudgetRequiresScrollableHeader() {
        assertTrue(rootHeaderMustScroll(240f, 88f, 24f, 72f, 8f, 56f))
    }
    @Test fun ordinaryPortraitAndLandscapeBudgetsRetainFixedHeader() {
        assertFalse(rootHeaderMustScroll(890f, 88f, 24f, 62f, 12f, 48f))
        assertFalse(rootHeaderMustScroll(400f, 103.5f, 48f, 72f, 8f, 72f))
    }
    @Test fun boundaryUsesFull48dpTargetAnd8dpClearance() {
        assertFalse(rootHeaderMustScroll(248f, 88f, 24f, 72f, 8f, 48f))
        assertTrue(rootHeaderMustScroll(247.9f, 88f, 24f, 72f, 8f, 48f))
        assertTrue(rootHeaderMustScroll(248f, 88f, 24f, 72f, 8f, 56f))
    }
    @Test fun measuredHeaderAndTextGrowthAreBudgetInputsNotDeviceModel() {
        assertFalse(rootHeaderMustScroll(280f, 88f, 24f, 72f, 8f, 48f))
        assertTrue(rootHeaderMustScroll(280f, 120f, 24f, 72f, 8f, 64f))
    }
    @Test fun largeInsetsStillRequireCompactHeaderButDoNotProveFullPhysicalFit() {
        assertTrue(rootHeaderMustScroll(240f, 88f, 96f, 72f, 8f, 56f))
        val afterHeaderScrollsAway = 240f - 24f - 96f - 72f - 8f - 8f
        assertTrue("Even a fully scrolled-away header cannot make a48dp target fit here", afterHeaderScrollsAway < 48f)
    }
}
