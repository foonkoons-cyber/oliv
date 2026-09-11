package com.khabar.reader.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedDiscoveryTest {

    private val base = "https://blog.example.com/about/"

    @Test fun findsDeclaredFeedsInDocumentOrder() {
        val html = """
            <html><head>
            <link rel="stylesheet" href="/s.css">
            <link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml">
            <link rel="alternate" type="application/atom+xml" title="Atom" href="/atom.xml">
            </head><body>hi</body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://blog.example.com/feed.xml", "https://blog.example.com/atom.xml"),
            FeedDiscovery.fromHtml(html, base)
        )
    }

    @Test fun toleratesAttributeOrderQuotingAndCase() {
        val html = """
            <LINK HREF=/one.xml TYPE="application/rss+xml" REL=alternate>
            <link href='/two.xml' rel='alternate home' type='application/atom+xml'>
            <link
                rel="alternate"
                type="application/rss+xml"
                href="/three.xml" />
        """.trimIndent()
        assertEquals(
            listOf(
                "https://blog.example.com/one.xml",
                "https://blog.example.com/two.xml",
                "https://blog.example.com/three.xml"
            ),
            FeedDiscovery.fromHtml(html, base)
        )
    }

    @Test fun ignoresNonFeedAlternates() {
        val html = """
            <link rel="alternate" type="text/html" hreflang="fr" href="/fr/">
            <link rel="canonical" href="https://blog.example.com/">
            <link rel="alternate" type="application/rss+xml" href="/real.xml">
        """.trimIndent()
        assertEquals(listOf("https://blog.example.com/real.xml"), FeedDiscovery.fromHtml(html, base))
    }

    @Test fun deduplicatesRepeatedDeclarations() {
        val html = """
            <link rel="alternate" type="application/rss+xml" href="/feed.xml">
            <link rel="alternate" type="application/rss+xml" href="/feed.xml">
        """.trimIndent()
        assertEquals(1, FeedDiscovery.fromHtml(html, base).size)
    }

    @Test fun decodesEntitiesInHrefs() {
        val html = """<link rel="alternate" type="application/rss+xml" href="/f?a=1&amp;b=2">"""
        assertEquals(
            listOf("https://blog.example.com/f?a=1&b=2"),
            FeedDiscovery.fromHtml(html, base)
        )
    }

    @Test fun fallsBackToAnchorsOnlyWhenNothingIsDeclared() {
        val html = """<body><a href="/feed">Subscribe</a><a href="/about">About</a></body>"""
        assertEquals(listOf("https://blog.example.com/feed"), FeedDiscovery.fromHtml(html, base))
    }

    @Test fun neverReturnsSomethingUnfetchable() {
        val html = """
            <link rel="alternate" type="application/rss+xml" href="javascript:void(0)">
            <a href="mailto:me@example.com">mail.xml</a>
        """.trimIndent()
        assertTrue(FeedDiscovery.fromHtml(html, base).isEmpty())
    }

    @Test fun commonPathsAreRootedAtTheOrigin() {
        val paths = FeedDiscovery.commonPaths("https://blog.example.com/deep/page?x=1")
        assertTrue(paths.contains("https://blog.example.com/feed"))
        assertTrue(paths.contains("https://blog.example.com/rss.xml"))
        assertTrue(paths.all { it.startsWith("https://blog.example.com/") })
        assertTrue(FeedDiscovery.commonPaths("not a url").isEmpty())
    }

    @Test fun sniffsFeedRootsThroughPrologueNoise() {
        assertTrue(FeedDiscovery.looksLikeFeedText("<rss version=\"2.0\"><channel/></rss>"))
        assertTrue(FeedDiscovery.looksLikeFeedText("<?xml version=\"1.0\"?>\n<feed xmlns=\"x\"/>"))
        assertTrue(FeedDiscovery.looksLikeFeedText("\uFEFF<?xml version=\"1.0\"?><rdf:RDF/>"))
        assertTrue(
            FeedDiscovery.looksLikeFeedText(
                "<?xml version=\"1.0\"?><!DOCTYPE rss><!-- note --><rss/>"
            )
        )
        assertFalse(FeedDiscovery.looksLikeFeedText("<!DOCTYPE html><html><body/></html>"))
        assertFalse(FeedDiscovery.looksLikeFeedText(""))
    }
}
