// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class UserProfileDetailsTest {
    private val utc = ZoneId.of("UTC")
    @Test fun secondBasedActivityMatchesFlutterThresholds() {
        val now = 1_770_000_000L
        for ((elapsed, label) in listOf(0L to "刚刚", 59L to "59秒前", 60L to "1分钟前", 3600L to "60分钟前",
            3601L to "1小时前", 86400L to "24小时前", 86401L to "1天前", 172801L to "2天前", 259201L to "3天前"))
            assertEquals("${label}活跃", profileLastActive(now - elapsed, now, utc))
    }
    @Test fun epochSecondsUseExplicitLocalZoneAndOlderDatesRemainCalendarDates() {
        assertEquals("1970年01月01日 00:01:40", profileRegisteredAt(100, 300, utc))
        assertEquals("1970年01月01日 08:01:40", profileRegisteredAt(100, 300, ZoneId.of("Asia/Shanghai")))
        assertEquals("1970年01月01日活跃", profileLastActive(100, 1_770_000_000, utc))
    }
    @Test fun clockRollbackFutureOrInvalidValuesNeverFabricateJustNowOrAge() {
        for (seconds in listOf(null, 0L, -1L, 301L, Long.MAX_VALUE, 1_770_000_000_000L)) {
            assertNull(profileRegisteredAt(seconds, 300, utc)); assertNull(profileLastActive(seconds, 300, utc))
        }
    }
}
