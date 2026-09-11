package com.khabar.reader.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlTest {

    private fun parse(xml: String) = Opml.parse(xml.toByteArray(Charsets.UTF_8))

    @Test fun readsAFlatOpml2Export() {
        val entries = parse(
            """<?xml version="1.0"?>
            <opml version="2.0"><head><title>subs</title></head><body>
              <outline type="rss" text="A Blog" title="A Blog"
                       xmlUrl="https://a.example.com/feed" htmlUrl="https://a.example.com/"/>
              <outline type="rss" text="B" xmlUrl="https://b.example.com/rss.xml"/>
            </body></opml>"""
        )
        assertEquals(2, entries.size)
        assertEquals("A Blog", entries[0].title)
        assertEquals("https://a.example.com/feed", entries[0].xmlUrl)
        assertEquals("https://a.example.com/", entries[0].siteUrl)
        assertEquals("B", entries[1].title)
        assertNull(entries[1].siteUrl)
    }

    @Test fun descendsIntoCategoryFoldersWithoutEmittingThem() {
        val entries = parse(
            """<opml version="1.0"><body>
              <outline text="News">
                <outline text="Deep">
                  <outline type="rss" text="Inner" xmlUrl="https://x.example.com/feed"/>
                </outline>
              </outline>
              <outline text="Empty folder"/>
            </body></opml>"""
        )
        assertEquals(1, entries.size)
        assertEquals("Inner", entries[0].title)
    }

    @Test fun acceptsTheAttributeSpellingsExportsActuallyUse() {
        val entries = parse(
            """<opml><body>
              <outline TEXT="Upper" XMLURL="https://u.example.com/feed"/>
              <outline text='Single' xmlurl='https://s.example.com/feed'/>
            </body></opml>"""
        )
        assertEquals(2, entries.size)
        assertEquals("Upper", entries[0].title)
        assertEquals("https://s.example.com/feed", entries[1].xmlUrl)
    }

    @Test fun titleWinsOverTextAndEntitiesAreDecoded() {
        val entries = parse(
            """<opml><body>
              <outline text="fallback" title="Tom &amp; Jerry&rsquo;s" xmlUrl="https://t.example.com/f"/>
            </body></opml>"""
        )
        assertEquals("Tom & Jerry’s", entries.single().title)
    }

    @Test fun duplicateFeedsCollapse() {
        val entries = parse(
            """<opml><body>
              <outline xmlUrl="https://a.example.com/feed"/>
              <outline xmlUrl="http://www.a.example.com/feed/"/>
            </body></opml>"""
        )
        assertEquals(1, entries.size)
    }

    @Test fun nonWebUrlsAreDropped() {
        val entries = parse(
            """<opml><body>
              <outline xmlUrl="javascript:alert(1)"/>
              <outline xmlUrl=""/>
              <outline xmlUrl="https://ok.example.com/feed"/>
            </body></opml>"""
        )
        assertEquals(listOf("https://ok.example.com/feed"), entries.map { it.xmlUrl })
    }

    @Test fun somethingThatIsNotOpmlGivesAnEmptyList() {
        assertTrue(parse("<html><body>hi</body></html>").isEmpty())
        assertTrue(parse("this is not xml").isEmpty())
        assertTrue(Opml.parse(ByteArray(0)).isEmpty())
    }

    @Test fun truncatedFileKeepsWhatItRead() {
        val entries = parse(
            """<opml><body>
              <outline xmlUrl="https://a.example.com/feed"/>
              <outline xmlUrl="https://b.example"""
        )
        assertEquals(1, entries.size)
    }

    @Test fun buildRoundTripsThroughParse() {
        val original = listOf(
            Opml.Entry("Tom & Jerry's \"best\"", "https://a.example.com/feed?x=1&y=2", "https://a.example.com/"),
            Opml.Entry(null, "https://b.example.com/feed", null)
        )
        val xml = Opml.build(original)
        val back = Opml.parse(xml.toByteArray(Charsets.UTF_8))
        assertEquals(2, back.size)
        assertEquals("Tom & Jerry's \"best\"", back[0].title)
        assertEquals("https://a.example.com/feed?x=1&y=2", back[0].xmlUrl)
        assertEquals("https://a.example.com/", back[0].siteUrl)
        assertEquals("https://b.example.com/feed", back[1].xmlUrl)
    }

    @Test fun builtDocumentLooksLikeOpmlToOtherReaders() {
        val xml = Opml.build(listOf(Opml.Entry("A", "https://a.example.com/feed", null)))
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<opml version=\"2.0\">"))
        assertTrue(xml.contains("type=\"rss\""))
        assertTrue(xml.contains("xmlUrl=\"https://a.example.com/feed\""))
    }
}
