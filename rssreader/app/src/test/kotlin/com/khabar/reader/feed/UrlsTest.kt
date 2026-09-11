package com.khabar.reader.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {

    @Test fun bareDomainGetsHttps() {
        assertEquals("https://example.com", Urls.normalizeInput("example.com"))
        assertEquals("https://example.com/feed", Urls.normalizeInput("  example.com/feed  "))
    }

    @Test fun existingSchemeIsKept() {
        assertEquals("http://example.com/rss", Urls.normalizeInput("http://example.com/rss"))
        assertEquals("https://example.com/rss", Urls.normalizeInput("https://example.com/rss"))
    }

    @Test fun subscribeSchemesAreUnwrapped() {
        assertEquals("https://example.com/rss", Urls.normalizeInput("feed://example.com/rss"))
        assertEquals("https://example.com/rss", Urls.normalizeInput("feed:https://example.com/rss"))
        assertEquals("https://example.com/rss", Urls.normalizeInput("FEED://example.com/rss"))
        assertEquals("https://example.com/rss", Urls.normalizeInput("rss://example.com/rss"))
    }

    @Test fun anglesAreStripped() {
        assertEquals("https://example.com/rss", Urls.normalizeInput("<https://example.com/rss>"))
    }

    @Test fun nonWebInputIsRejected() {
        assertNull(Urls.normalizeInput(null))
        assertNull(Urls.normalizeInput(""))
        assertNull(Urls.normalizeInput("   "))
        assertNull(Urls.normalizeInput("javascript:alert(1)"))
        assertNull(Urls.normalizeInput("mailto:me@example.com"))
        assertNull(Urls.normalizeInput("ftp://example.com/x"))
        assertNull(Urls.normalizeInput("data:text/xml,<rss/>"))
        assertNull(Urls.normalizeInput("just some words"))
        assertNull(Urls.normalizeInput("localhostx"))
    }

    @Test fun resolveHandlesEveryRelativeShape() {
        val base = "https://blog.example.com/posts/index.html"
        assertEquals("https://blog.example.com/feed", Urls.resolve(base, "/feed"))
        assertEquals("https://blog.example.com/posts/feed.xml", Urls.resolve(base, "feed.xml"))
        assertEquals("https://cdn.example.com/f.xml", Urls.resolve(base, "//cdn.example.com/f.xml"))
        assertEquals("https://other.example.com/f", Urls.resolve(base, "https://other.example.com/f"))
        assertEquals("https://blog.example.com/posts/?feed=rss2", Urls.resolve(base, "?feed=rss2"))
    }

    @Test fun resolveRefusesWhatShouldNotBeFetched() {
        val base = "https://blog.example.com/posts/"
        assertNull(Urls.resolve(base, null))
        assertNull(Urls.resolve(base, ""))
        assertNull(Urls.resolve(base, "javascript:void(0)"))
        assertNull(Urls.resolve(base, "mailto:me@example.com"))
        assertNull(Urls.resolve(null, "/feed"))
    }

    @Test fun hostDropsWww() {
        assertEquals("example.com", Urls.host("https://www.example.com/feed"))
        assertEquals("blog.example.com", Urls.host("http://blog.example.com"))
        assertNull(Urls.host("not a url"))
    }

    @Test fun canonicalIgnoresSchemeWwwAndTrailingSlash() {
        val a = Urls.canonical("http://example.com/feed/")
        assertEquals(a, Urls.canonical("https://www.example.com/feed"))
        assertEquals(a, Urls.canonical("HTTPS://WWW.EXAMPLE.COM/feed"))
        assertEquals(a, Urls.canonical("https://example.com:443/feed"))
    }

    @Test fun canonicalKeepsWhatActuallyDistinguishesFeeds() {
        val base = Urls.canonical("https://example.com/feed")
        // A different path or query is a different feed, however similar it looks.
        assert(base != Urls.canonical("https://example.com/feed2"))
        assert(base != Urls.canonical("https://example.com/feed?type=atom"))
        assert(base != Urls.canonical("https://other.com/feed"))
    }
}
