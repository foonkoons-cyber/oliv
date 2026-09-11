package com.khabar.reader.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The format list is taken from an audit of 47 live feeds — every shape here was observed in the
 * wild, which is why the ugly ones are not "edge cases" worth skipping.
 */
class DateParsingTest {

    @Test fun epochAnchors() {
        assertEquals(0L, DateParsing.parse("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(0L, DateParsing.parse("1970-01-01T00:00:00Z"))
        assertEquals(0L, DateParsing.parse("1970-01-01"))
    }

    @Test fun rfc822WithNamedZone() {
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 Sep 2022 10:11:21 GMT"))
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 Sep 2022 10:11:21 UT"))
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 Sep 2022 10:11:21 UTC"))
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 Sep 2022 10:11:21 +0000"))
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 Sep 2022 10:11:21 -0000"))
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 Sep 2022 10:11:21 Z"))
    }

    @Test fun rfc822WithNumericOffsets() {
        assertEquals(1586961029000L, DateParsing.parse("Wed, 15 Apr 2020 16:30:29 +0200"))
        assertEquals(1664002468000L, DateParsing.parse("Fri, 23 Sep 2022 23:54:28 -0700"))
        assertEquals(1586961029000L, DateParsing.parse("Wed, 15 Apr 2020 16:30:29 +02:00"))
        assertEquals(1586961029000L, DateParsing.parse("Wed, 15 Apr 2020 16:30:29 +02"))
    }

    @Test fun usTimeZoneAbbreviations() {
        assertEquals(1489455373000L, DateParsing.parse("Mon, 13 Mar 2017 20:36:13 EST"))
        assertNotNull(DateParsing.parse("Mon, 13 Mar 2017 20:36:13 PDT"))
        assertNotNull(DateParsing.parse("Mon, 13 Mar 2017 20:36:13 CDT"))
        assertNotNull(DateParsing.parse("Mon, 13 Mar 2017 20:36:13 MST"))
    }

    @Test fun missingWeekdayOrWrongWeekday() {
        assertEquals(1663927881000L, DateParsing.parse("23 Sep 2022 10:11:21 GMT"))
        // Publishers get the weekday wrong constantly; it must not be trusted or validated.
        assertEquals(1663927881000L, DateParsing.parse("Mon, 23 Sep 2022 10:11:21 GMT"))
    }

    @Test fun missingSeconds() {
        assertEquals(
            1663927860000L,
            DateParsing.parse("Fri, 23 Sep 2022 10:11 GMT")
        )
    }

    @Test fun twoDigitYearsPivotAt50() {
        assertEquals(946684799000L, DateParsing.parse("Fri, 31 Dec 99 23:59:59 GMT"))
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 Sep 22 10:11:21 GMT"))
    }

    @Test fun lowercaseAndMixedCaseMonths() {
        assertEquals(1663927881000L, DateParsing.parse("fri, 23 sep 2022 10:11:21 gmt"))
        assertEquals(1663927881000L, DateParsing.parse("Fri, 23 SEP 2022 10:11:21 GMT"))
    }

    @Test fun trailingZoneCommentIsIgnored() {
        assertEquals(
            1663927881000L,
            DateParsing.parse("Fri, 23 Sep 2022 10:11:21 GMT (Greenwich Mean Time)")
        )
    }

    @Test fun asctimeShape() {
        assertEquals(1663927881000L, DateParsing.parse("Fri Sep 23 10:11:21 2022"))
        assertEquals(1663927881000L, DateParsing.parse("Fri Sep 23 10:11:21 GMT 2022"))
    }

    @Test fun iso8601Variants() {
        assertEquals(1705314600000L, DateParsing.parse("2024-01-15T10:30:00Z"))
        assertEquals(1705314600000L, DateParsing.parse("2024-01-15T10:30:00+00:00"))
        assertEquals(1705314600000L, DateParsing.parse("2024-01-15T10:30Z"))
        assertEquals(1705314600500L, DateParsing.parse("2024-01-15T10:30:00.500Z"))
        assertEquals(1705314600123L, DateParsing.parse("2024-01-15T10:30:00.1234567Z"))
        assertEquals(1710249000000L, DateParsing.parse("2024-03-12T18:40:00+05:30"))
        assertEquals(1710249000000L, DateParsing.parse("2024-03-12T18:40:00+0530"))
        assertEquals(1705314600000L, DateParsing.parse("2024-01-15t10:30:00z"))
    }

    @Test fun sqlStyleAndDateOnly() {
        // Treated as UTC: nothing in the string says otherwise, and inventing a local zone
        // would make the same feed sort differently on two phones.
        assertEquals(1705314600000L, DateParsing.parse("2024-01-15 10:30:00"))
        assertEquals(1705276800000L, DateParsing.parse("2024-01-15"))
    }

    @Test fun garbageReturnsNullRatherThanGuessing() {
        assertNull(DateParsing.parse(null))
        assertNull(DateParsing.parse(""))
        assertNull(DateParsing.parse("   "))
        assertNull(DateParsing.parse("yesterday"))
        assertNull(DateParsing.parse("Mon, 45 Xyz 2024 10:00:00 GMT"))
        assertNull(DateParsing.parse("0000-00-00"))
        assertNull(DateParsing.parse("not a date at all"))
    }

    @Test fun leapDayAndCenturyBoundaries() {
        // 2000 is a leap year, 2100 is not — the civil-days arithmetic has to know the difference.
        assertEquals(951782400000L, DateParsing.parse("2000-02-29T00:00:00Z"))
        assertEquals(4107542400000L, DateParsing.parse("2100-03-01T00:00:00Z"))
        assertEquals(4102444800000L, DateParsing.parse("2100-01-01T00:00:00Z"))
    }

    @Test fun impossibleDaysRollOverRatherThanFailing() {
        // "30 Feb" is nonsense, but a feed that emits it still wants to be read; rolling into
        // March sorts the item sanely, which is all this value is used for.
        assertEquals(
            DateParsing.parse("Fri, 01 Mar 2024 10:00:00 GMT"),
            DateParsing.parse("Fri, 30 Feb 2024 10:00:00 GMT")
        )
    }

    @Test fun leapSecondIsClampedNotRejected() {
        assertEquals(
            DateParsing.parse("2016-12-31T23:59:59Z"),
            DateParsing.parse("2016-12-31T23:59:60Z")
        )
    }
}
