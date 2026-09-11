package com.khabar.reader.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test fun stripsTagsAndKeepsWordBoundaries() {
        assertEquals("end Start", HtmlText.toPlainText("<p>end</p><p>Start</p>"))
        assertEquals("a b", HtmlText.toPlainText("a<br>b"))
        assertEquals("one two", HtmlText.toPlainText("<div>one</div><div>two</div>"))
    }

    @Test fun dropsScriptAndStyleContent() {
        assertEquals("hello", HtmlText.toPlainText("<script>var x = 1;</script>hello"))
        assertEquals("hello", HtmlText.toPlainText("<style>p{color:red}</style>hello"))
        assertEquals("hello", HtmlText.toPlainText("hello<!-- a comment -->"))
    }

    @Test fun decodesNamedDecimalAndHexEntities() {
        assertEquals("a&b", HtmlText.decodeEntities("a&amp;b"))
        assertEquals("it’s", HtmlText.decodeEntities("it&rsquo;s"))
        assertEquals("it’s", HtmlText.decodeEntities("it&#8217;s"))
        assertEquals("it’s", HtmlText.decodeEntities("it&#x2019;s"))
        assertEquals("Sankü", HtmlText.decodeEntities("Sank&uuml;"))
        assertEquals("a\u00A0b", HtmlText.decodeEntities("a&nbsp;b"))
    }

    @Test fun astralCodePointsSurvive() {
        assertEquals("😀", HtmlText.decodeEntities("&#128512;"))
        assertEquals("😀", HtmlText.decodeEntities("&#x1F600;"))
    }

    @Test fun unknownEntitiesAreLeftAlone() {
        assertEquals("a&foo;b", HtmlText.decodeEntities("a&foo;b"))
        assertEquals("R&D", HtmlText.decodeEntities("R&D"))
        assertEquals("a&#;b", HtmlText.decodeEntities("a&#;b"))
    }

    @Test fun ampersandDecodingIsSinglePass() {
        // One pass only: "&amp;lt;" becomes the text "&lt;". Decoding twice would hand the
        // caller a real <b> tag that the publisher never wrote.
        assertEquals("&lt;b&gt;", HtmlText.decodeEntities("&amp;lt;b&amp;gt;"))
    }

    @Test fun looseEntitiesOnlyForTheSafeNames() {
        assertEquals("a\u00A0b", HtmlText.decodeEntities("a&nbspb"))
        assertEquals("a&nestedb", HtmlText.decodeEntities("a&nestedb"))
    }

    @Test fun doubleEscapedBodiesStillComeOutAsText() {
        assertEquals("hi there", HtmlText.toPlainText("&lt;p&gt;hi there&lt;/p&gt;"))
    }

    @Test fun entitiesThatSurvivedCdataAreDecoded() {
        // A CDATA section hides entities from the XML parser, so they reach us undecoded.
        // decodeEntities keeps the non-breaking space; toPlainText collapses it like any
        // other whitespace, which is what a one-line list summary wants.
        assertEquals("a\u00A0b", HtmlText.decodeEntities("a&nbsp;b"))
        assertEquals("a b", HtmlText.toPlainText("a&nbsp;b"))
    }

    @Test fun collapsesWhitespace() {
        assertEquals("a b c", HtmlText.toPlainText("  a \n\n  b\t\tc  "))
    }

    @Test fun snippetCutsOnAWordBoundary() {
        val text = "The quick brown fox jumps over the lazy dog and keeps running for a while"
        val s = HtmlText.snippet("<p>$text</p>", 20)
        assertTrue(s.endsWith("…"))
        assertTrue(s.length <= 21)
        assertTrue(!s.dropLast(1).endsWith(" "))
        assertEquals(text, HtmlText.snippet("<p>$text</p>", 500))
    }

    @Test fun snippetOfNothingIsEmpty() {
        assertEquals("", HtmlText.snippet(null))
        assertEquals("", HtmlText.snippet("<p></p>"))
    }

    @Test fun firstImageIsFoundAndAbsolutised() {
        val html = """<p>hi</p><img src="/img/a.jpg" alt="x"><img src="/b.jpg">"""
        assertEquals(
            "https://example.com/img/a.jpg",
            HtmlText.firstImageUrl(html, "https://example.com/post/1")
        )
    }

    @Test fun trackingPixelsAndDataUrisAreSkipped() {
        val html = """<img src="https://t.example.com/p.gif" width="1" height="1">""" +
            """<img src='https://cdn.example.com/real.jpg'>"""
        assertEquals(
            "https://cdn.example.com/real.jpg",
            HtmlText.firstImageUrl(html, "https://example.com/")
        )
        assertNull(HtmlText.firstImageUrl("""<img src="data:image/gif;base64,AAAA">""", "https://x.com/"))
    }

    @Test fun attributesInAnyOrderQuotingAndCase() {
        assertEquals(
            "https://example.com/a.jpg",
            HtmlText.firstImageUrl("""<IMG ALT="hi" SRC=https://example.com/a.jpg >""", null)
        )
        assertEquals(
            "https://example.com/b.jpg",
            HtmlText.firstImageUrl("""<img data-src='https://example.com/b.jpg' class="x">""", null)
        )
    }

    @Test fun attributeValueContainingAngleBracketDoesNotBreakScanning() {
        val html = """<img alt="a > b" src="https://example.com/c.jpg">"""
        assertEquals("https://example.com/c.jpg", HtmlText.firstImageUrl(html, null))
    }

    @Test fun strayAngleBracketIsTreatedAsText() {
        assertEquals("5 < 6 and 7 > 2", HtmlText.toPlainText("5 &lt; 6 and 7 &gt; 2"))
        assertTrue(HtmlText.toPlainText("a < b").contains("a"))
    }

    @Test fun nullAndBlankAreSafe() {
        assertEquals("", HtmlText.toPlainText(null))
        assertEquals("", HtmlText.toPlainText(""))
        assertNull(HtmlText.firstImageUrl(null, null))
    }
}
