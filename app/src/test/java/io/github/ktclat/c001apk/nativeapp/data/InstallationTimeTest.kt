// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import org.junit.Assert.*
import org.junit.Test
class InstallationTimeTest {
    @Test fun convertsLegacySecondsOnceWithoutChangingInstallationDate() {
        val actual = InstallationTime.persistedMillis(1770000000L, 1790000000123L)
        assertEquals(1770000000000L, actual)
        assertEquals(actual, InstallationTime.persistedMillis(actual, 1790000000999L))
    }
    @Test fun newInstallUsesMillisecondsAndExistingMillisecondsRemainExact() {
        assertEquals(1790000000123L, InstallationTime.persistedMillis(0, 1790000000123L))
        assertEquals(1770000000123L, InstallationTime.persistedMillis(1770000000123L, 1790000000123L))
    }
    @Test fun corruptSmallPreferencesInitializeOnceEvenWhenNextLaunchTimeChanges() {
        val now = 1790000000123L
        for (stored in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1000L, 1000000L, 999999999L)) {
            val normalized = InstallationTime.persistedMillis(stored, now)
            assertEquals("corrupt input $stored", now, normalized)
            assertEquals("repeat input $stored", normalized, InstallationTime.persistedMillis(normalized, now + 60000))
        }
    }
    @Test fun normalizationIsIdempotentAtUnitBoundariesAndLongExtremes() {
        val now = 1790000000123L
        for (stored in listOf(Long.MIN_VALUE, 1L, 1000L, 999999999L, 1000000000L,
            1770000000L, 99999999999L, 100000000000L, 1770000000123L, Long.MAX_VALUE)) {
            val normalized = InstallationTime.persistedMillis(stored, now)
            assertEquals("input $stored", normalized, InstallationTime.persistedMillis(normalized, now + 60000))
        }
        assertEquals(1000000000000L, InstallationTime.persistedMillis(1000000000L, now))
        assertEquals(100000000000L, InstallationTime.persistedMillis(100000000000L, now))
    }
}
