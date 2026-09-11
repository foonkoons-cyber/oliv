package com.khabar.reader.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The fixtures come from an audit of 47 live feeds. Every "weird" case here — undeclared
 * entities, a bare '&' in an image URL, a newline before the prolog, a lying encoding
 * declaration, RDF items outside the channel — was observed in a real publisher's feed.
 */
class FeedParserTest {

    private fun parse(xml: String, base: String? = "https://example.com/feed.xml") =
        FeedParser.parse(xml.toByteArray(Charsets.UTF_8), null, base)

    // ---- the three formats -------------------------------------------------------------------

    @Test fun rss2() {
        val feed = parse(
            """<?xml version="1.0"?>
            <rss version="2.0"><channel>
              <title>Example News</title>
              <link>https://example.com/</link>
              <description>All the news</description>
              <item>
                <title>First post</title>
                <link>https://example.com/1</link>
                <description>Summary one</description>
                <guid>tag:example.com,2024:1</guid>
                <pubDate>Fri, 23 Sep 2022 10:11:21 GMT</pubDate>
              </item>
            </channel></rss>"""
        )
        assertEquals("Example News", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals("All the news", feed.description)
        assertEquals(1, feed.items.size)
        val item = feed.items[0]
        assertEquals("First post", item.title)
        assertEquals("https://example.com/1", item.link)
        assertEquals("tag:example.com,2024:1", item.guid)
        assertEquals(1663927881000L, item.publishedAt)
    }

    @Test fun atom() {
        val feed = parse(
            """<?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Blog</title>
              <link rel="self" href="https://example.com/atom.xml"/>
              <link rel="alternate" type="text/html" href="https://example.com/"/>
              <subtitle>Notes</subtitle>
              <entry>
                <title>Entry one</title>
                <id>urn:uuid:1234</id>
                <link rel="edit" href="https://example.com/edit/1"/>
                <link rel="alternate" type="text/html" href="https://example.com/post/1"/>
                <published>2024-01-15T10:30:00Z</published>
                <author><name>Asha</name></author>
                <content type="html">&lt;p&gt;Body&lt;/p&gt;</content>
              </entry>
            </feed>"""
        )
        assertEquals("Atom Blog", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        val item = feed.items.single()
        // rel="alternate" wins over self and edit.
        assertEquals("https://example.com/post/1", item.link)
        assertEquals("urn:uuid:1234", item.guid)
        assertEquals("Asha", item.author)
        assertEquals(1705314600000L, item.publishedAt)
        assertTrue(item.contentHtml!!.contains("<p>Body</p>"))
    }

    @Test fun atomLinkWithNoRelDefaultsToAlternate() {
        val feed = parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>T</title>
              <entry><title>E</title><link href="https://example.com/x"/></entry></feed>"""
        )
        assertEquals("https://example.com/x", feed.items.single().link)
    }

    @Test fun rdfRss1ItemsAreSiblingsOfChannel() {
        val feed = parse(
            """<?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://example.com/">
                <title>Slashdot-ish</title>
                <link>https://example.com/</link>
              </channel>
              <item rdf:about="https://example.com/a">
                <title>Story A</title>
                <link>https://example.com/a</link>
                <dc:date>2024-01-15T10:30:00Z</dc:date>
                <dc:creator>someone</dc:creator>
              </item>
              <item rdf:about="https://example.com/b">
                <title>Story B</title><link>https://example.com/b</link>
              </item>
            </rdf:RDF>"""
        )
        assertEquals("Slashdot-ish", feed.title)
        assertEquals(2, feed.items.size)
        assertEquals("Story A", feed.items[0].title)
        assertEquals("someone", feed.items[0].author)
        assertEquals(1705314600000L, feed.items[0].publishedAt)
    }

    // ---- the repairs -------------------------------------------------------------------------

    @Test fun undeclaredHtmlEntityDoesNotLoseTheFeed() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title>
              <item><title>Sank&uuml;-tachi&nbsp;here</title><link>https://example.com/1</link></item>
            </channel></rss>"""
        )
        assertEquals(1, feed.items.size)
        assertTrue(feed.items[0].title!!.startsWith("Sankü-tachi"))
    }

    @Test fun bareAmpersandInAnAttributeUrl() {
        val feed = parse(
            """<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>T</title>
              <item><title>P</title><link>https://example.com/1</link>
                <media:content url="https://i.example.com/x.jpg?width=140&quality=85&auto=format" medium="image"/>
              </item></channel></rss>"""
        )
        assertEquals(1, feed.items.size)
        assertEquals(
            "https://i.example.com/x.jpg?width=140&quality=85&auto=format",
            feed.items[0].imageUrl
        )
    }

    @Test fun junkBeforeThePrologue() {
        val feed = parse(
            "\n<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>" +
                "<title>T</title><item><title>A</title><link>https://example.com/a</link></item>" +
                "</channel></rss>"
        )
        assertEquals("T", feed.title)
        assertEquals(1, feed.items.size)
    }

    @Test fun byteOrderMarkIsHandled() {
        val xml = "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>" +
            "<title>BOM feed</title><item><title>A</title><link>https://example.com/a</link></item>" +
            "</channel></rss>"
        val feed = FeedParser.parse(xml.toByteArray(Charsets.UTF_8), null, null)
        assertEquals("BOM feed", feed.title)
    }

    @Test fun declaredEncodingThatContradictsTheBytes() {
        // Latin-1 bytes with a prolog claiming UTF-8 is fatal to a strict parser.
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>" +
            "<title>Café</title><item><title>Menü</title>" +
            "<link>https://example.com/a</link></item></channel></rss>"
        val feed = FeedParser.parse(xml.toByteArray(Charsets.ISO_8859_1), "ISO-8859-1", null)
        assertEquals(1, feed.items.size)
        assertNotNull(feed.title)
    }

    @Test fun illegalControlCharactersAreStripped() {
        val backspace = "\u0008"
        val formFeed = "\u000C"
        val xml = "<rss version=\"2.0\"><channel><title>T" + backspace + "X</title>" +
            "<item><title>A" + formFeed + "B</title><link>https://example.com/a</link></item>" +
            "</channel></rss>"
        val feed = FeedParser.parse(xml.toByteArray(Charsets.UTF_8), null, null)
        assertEquals(1, feed.items.size)
        assertEquals("TX", feed.title)
    }

    @Test fun doctypeWithAnExternalDtdIsNotFetched() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE rss SYSTEM "https://10.255.255.1/never-resolves.dtd">
            <rss version="2.0"><channel><title>T</title>
            <item><title>A</title><link>https://example.com/a</link></item></channel></rss>"""
        val start = System.currentTimeMillis()
        val feed = FeedParser.parse(xml.toByteArray(Charsets.UTF_8), null, null)
        // If the parser had gone to the network this would be a timeout, not milliseconds.
        assertTrue(System.currentTimeMillis() - start < 5_000)
        assertEquals(1, feed.items.size)
    }

    @Test fun truncatedDocumentKeepsWhatItParsed() {
        val xml = """<rss version="2.0"><channel><title>T</title>
            <item><title>One</title><link>https://example.com/1</link></item>
            <item><title>Two</title><link>https://example.com/2</link></item>
            <item><title>Thr"""
        val feed = FeedParser.parse(xml.toByteArray(Charsets.UTF_8), null, null)
        assertTrue(feed.items.size >= 2)
        assertEquals("One", feed.items[0].title)
    }

    // ---- content shapes ----------------------------------------------------------------------

    @Test fun cdataWrappedHtmlKeepsItsEntities() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title><item>
              <title><![CDATA[a&nbsp;b]]></title>
              <link>https://example.com/1</link>
              <description><![CDATA[<p>Hello &amp; goodbye</p>]]></description>
            </item></channel></rss>"""
        )
        val item = feed.items.single()
        assertEquals("a b", item.title)
        assertTrue(item.summaryHtml!!.contains("<p>"))
    }

    @Test fun rawUnescapedHtmlInsideDescriptionIsKeptAsMarkup() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title><item>
              <title>P</title><link>https://example.com/1</link>
              <description>Lead <b>bold</b> and <a href="/x">link</a>.</description>
            </item></channel></rss>"""
        )
        val summary = feed.items.single().summaryHtml!!
        assertTrue(summary.contains("<b>bold</b>"))
        assertTrue(HtmlText.toPlainText(summary).contains("Lead bold and link."))
    }

    @Test fun atomXhtmlContentIsReserialised() {
        val feed = parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>T</title><entry>
              <title>E</title><link href="https://example.com/1"/>
              <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                <p>Para one</p><p>Para two</p></div></content>
            </entry></feed>"""
        )
        val content = feed.items.single().contentHtml!!
        assertTrue(content.contains("<p>Para one</p>"))
        assertTrue(content.contains("<p>Para two</p>"))
    }

    @Test fun contentEncodedWinsOverDescriptionAsTheBody() {
        val feed = parse(
            """<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
            <channel><title>T</title><item>
              <title>P</title><link>https://example.com/1</link>
              <description>Short teaser</description>
              <content:encoded><![CDATA[<p>The whole article</p>]]></content:encoded>
            </item></channel></rss>"""
        )
        val item = feed.items.single()
        assertEquals("Short teaser", item.summaryHtml)
        assertTrue(item.contentHtml!!.contains("The whole article"))
    }

    // ---- identity, dates, images -------------------------------------------------------------

    @Test fun guidIsPermaLinkFalseIsNotUsedAsALink() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title><item>
              <title>P</title><guid isPermaLink="false">https://tracker.example/12345</guid>
            </item></channel></rss>"""
        )
        assertNull(feed.items.single().link)
        assertEquals("https://tracker.example/12345", feed.items.single().guid)
    }

    @Test fun guidAsPermalinkBecomesTheLink() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title><item>
              <title>P</title><guid>https://example.com/permalink</guid>
            </item></channel></rss>"""
        )
        assertEquals("https://example.com/permalink", feed.items.single().link)
    }

    @Test fun duplicateGuidsCollapseKeepingTheFirst() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title>
              <item><title>First</title><guid>same</guid><link>https://example.com/1</link></item>
              <item><title>Second</title><guid>same</guid><link>https://example.com/2</link></item>
            </channel></rss>"""
        )
        assertEquals(1, feed.items.size)
        assertEquals("First", feed.items[0].title)
    }

    @Test fun itemWithNoDateGetsNullNotEpochZero() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title>
              <item><title>Undated</title><link>https://example.com/1</link></item></channel></rss>"""
        )
        assertNull(feed.items.single().publishedAt)
    }

    @Test fun relativeUrlsResolveAgainstTheFeedUrl() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title><link>/</link>
              <item><title>P</title><link>/post/1</link>
                <description>&lt;img src="/img/a.jpg"&gt;</description></item>
            </channel></rss>""",
            base = "https://example.com/blog/feed.xml"
        )
        assertEquals("https://example.com/post/1", feed.items.single().link)
        assertEquals("https://example.com/img/a.jpg", feed.items.single().imageUrl)
    }

    @Test fun podcastEnclosureIsAudioNotAThumbnail() {
        val feed = parse(
            """<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
            <channel><title>Pod</title><item>
              <title>Ep 1</title><link>https://example.com/1</link>
              <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="123"/>
              <itunes:image href="https://cdn.example.com/cover.jpg"/>
            </item></channel></rss>"""
        )
        assertEquals("https://cdn.example.com/cover.jpg", feed.items.single().imageUrl)
    }

    @Test fun imageEnclosureIsUsedAsTheThumbnail() {
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title><item>
              <title>P</title><link>https://example.com/1</link>
              <enclosure url="https://cdn.example.com/p.jpg" type="image/jpeg" length="1"/>
            </item></channel></rss>"""
        )
        assertEquals("https://cdn.example.com/p.jpg", feed.items.single().imageUrl)
    }

    @Test fun youtubeStyleMediaGroupThumbnail() {
        val feed = parse(
            """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <title>Channel</title>
              <entry><title>Video</title><link href="https://youtu.be/x"/>
                <media:group>
                  <media:content url="https://youtu.be/x" type="application/x-shockwave-flash"/>
                  <media:thumbnail url="https://i.ytimg.com/vi/x/hq.jpg" width="480" height="360"/>
                </media:group>
              </entry></feed>"""
        )
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", feed.items.single().imageUrl)
    }

    @Test fun channelImageBecomesTheIconAndDoesNotClobberTheTitle() {
        val feed = parse(
            """<rss version="2.0"><channel>
              <title>Real Title</title><link>https://example.com/</link>
              <image><url>https://example.com/logo.png</url><title>Logo Title</title>
                     <link>https://elsewhere.example/</link></image>
              <item><title>P</title><link>https://example.com/1</link></item>
            </channel></rss>"""
        )
        assertEquals("Real Title", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals("https://example.com/logo.png", feed.iconUrl)
    }

    @Test fun dublinCoreCreatorIsAnAuthor() {
        val feed = parse(
            """<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <channel><title>T</title><item><title>P</title><link>https://example.com/1</link>
              <dc:creator>Priya</dc:creator></item></channel></rss>"""
        )
        assertEquals("Priya", feed.items.single().author)
    }

    @Test fun longTitlesSplitAcrossSaxCallbacksAreNotTruncated() {
        val long = "word ".repeat(3000).trim()
        val feed = parse(
            """<rss version="2.0"><channel><title>T</title><item>
              <title>$long</title><link>https://example.com/1</link></item></channel></rss>"""
        )
        assertEquals(long, feed.items.single().title)
    }

    // ---- what must NOT parse -----------------------------------------------------------------

    @Test fun anHtmlPageIsNotAFeed() {
        val html = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Just a web page</title></head><body><p>hi</p></body></html>"""
        try {
            FeedParser.parse(html.toByteArray(Charsets.UTF_8), null, null)
            fail("an XHTML page must not be accepted as a feed")
        } catch (e: FeedParseException) {
            // expected
        }
    }

    @Test fun emptyAndGarbageInputThrow() {
        try {
            FeedParser.parse(ByteArray(0), null, null)
            fail("empty input must throw")
        } catch (e: FeedParseException) {
            // expected
        }
        try {
            FeedParser.parse("not xml at all".toByteArray(Charsets.UTF_8), null, null)
            fail("garbage input must throw")
        } catch (e: FeedParseException) {
            // expected
        }
    }

    @Test fun looksLikeFeedSniffsWithoutParsing() {
        assertTrue(FeedParser.looksLikeFeed("<?xml version=\"1.0\"?><rss/>".toByteArray(Charsets.UTF_8)))
        assertTrue(!FeedParser.looksLikeFeed("<!DOCTYPE html><html></html>".toByteArray(Charsets.UTF_8)))
    }
}
