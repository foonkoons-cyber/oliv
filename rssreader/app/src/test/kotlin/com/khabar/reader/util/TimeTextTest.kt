package com.khabar.reader.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class TimeTextTest {

    private val now = 1_700_000_000_000L   // 2023-11-14T22:13:20Z

    @Before
    fun fixZone() {
        // date() renders in the device zone; pin it so the expectations are stable.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test fun justNow() {
        assertEquals("abhi", TimeText.relative(now, now))
        assertEquals("abhi", TimeText.relative(now - 59_000, now))
    }

    @Test fun futureTimestampsDoNotGoNegative() {
        assertEquals("abhi", TimeText.relative(now + 5 * 60_000, now))
    }

    @Test fun minutesAndHours() {
        assertEquals("5 min pehle", TimeText.relative(now - 5 * 60_000, now))
        assertEquals("59 min pehle", TimeText.relative(now - 59 * 60_000, now))
        assertEquals("1 ghanta pehle", TimeText.relative(now - 60 * 60_000, now))
        assertEquals("3 ghante pehle", TimeText.relative(now - 3 * 3_600_000, now))
        assertEquals("23 ghante pehle", TimeText.relative(now - 23 * 3_600_000, now))
    }

    @Test fun daysThenFallsBackToADate() {
        assertEquals("kal", TimeText.relative(now - 25 * 3_600_000L, now))
        assertEquals("3 din pehle", TimeText.relative(now - 3 * 86_400_000L, now))
        assertEquals("6 din pehle", TimeText.relative(now - 6 * 86_400_000L, now))
        assertEquals("7 Nov 2023", TimeText.relative(now - 7 * 86_400_000L, now))
    }

    @Test fun absoluteFormat() {
        assertEquals("14 Nov 2023, 22:13", TimeText.absolute(now))
        assertEquals("1 Jan 1970, 00:00", TimeText.absolute(0L))
    }
}
