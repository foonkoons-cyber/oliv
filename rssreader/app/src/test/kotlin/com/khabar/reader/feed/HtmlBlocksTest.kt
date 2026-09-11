package com.khabar.reader.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlBlocksTest {

    private fun text(block: HtmlBlock): String = when (block) {
        is HtmlBlock.Paragraph -> block.spans.joinToString("") { it.text }
        is HtmlBlock.Heading -> block.spans.joinToString("") { it.text }
        is HtmlBlock.Bullet -> block.spans.joinToString("") { it.text }
        is HtmlBlock.Quote -> block.spans.joinToString("") { it.text }
        is HtmlBlock.Code -> block.text
        is HtmlBlock.Image -> block.url
        HtmlBlock.Rule -> "---"
    }

    @Test fun emptyInputYieldsNoBlocks() {
        assertEquals(emptyList<HtmlBlock>(), HtmlBlocks.parse(null))
        assertEquals(emptyList<HtmlBlock>(), HtmlBlocks.parse("   "))
        assertEquals(emptyList<HtmlBlock>(), HtmlBlocks.parse("<p></p><div>  </div>"))
    }

    @Test fun bareTextBecomesOneParagraph() {
        val blocks = HtmlBlocks.parse("Just a sentence with no markup at all.")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is HtmlBlock.Paragraph)
        assertEquals("Just a sentence with no markup at all.", text(blocks[0]))
    }

    @Test fun paragraphsHeadingsAndRules() {
        val blocks = HtmlBlocks.parse("<h2>Title</h2><p>One.</p><hr><p>Two.</p>")
        assertEquals(4, blocks.size)
        assertEquals(2, (blocks[0] as HtmlBlock.Heading).level)
        assertEquals("Title", text(blocks[0]))
        assertEquals("One.", text(blocks[1]))
        assertTrue(blocks[2] is HtmlBlock.Rule)
        assertEquals("Two.", text(blocks[3]))
    }

    @Test fun inlineStylesBecomeSpans() {
        val blocks = HtmlBlocks.parse("<p>plain <b>bold</b> and <em>italic</em> and <code>x=1</code></p>")
        val spans = (blocks[0] as HtmlBlock.Paragraph).spans
        assertEquals("plain bold and italic and x=1", spans.joinToString("") { it.text })
        assertTrue(spans.any { it.bold && it.text == "bold" })
        assertTrue(spans.any { it.italic && it.text == "italic" })
        assertTrue(spans.any { it.code && it.text == "x=1" })
    }

    @Test fun linksCarryAnAbsoluteHref() {
        val blocks = HtmlBlocks.parse(
            """<p>see <a href="/next">next</a></p>""",
            "https://example.com/posts/1"
        )
        val spans = (blocks[0] as HtmlBlock.Paragraph).spans
        val link = spans.first { it.link != null }
        assertEquals("next", link.text)
        assertEquals("https://example.com/next", link.link)
    }

    @Test fun orderedListsNumberAndUnorderedListsBullet() {
        val blocks = HtmlBlocks.parse("<ol><li>first</li><li>second</li></ol><ul><li>dot</li></ul>")
        val bullets = blocks.filterIsInstance<HtmlBlock.Bullet>()
        assertEquals(3, bullets.size)
        assertEquals("1.", bullets[0].marker)
        assertEquals("2.", bullets[1].marker)
        assertEquals("•", bullets[2].marker)
        assertEquals("first", text(bullets[0]))
    }

    @Test fun nestedListsDoNotCrashOrRenumber() {
        val blocks = HtmlBlocks.parse("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>")
        val bullets = blocks.filterIsInstance<HtmlBlock.Bullet>()
        assertTrue(bullets.size >= 3)
        assertTrue(bullets.all { it.marker == "•" })
    }

    @Test fun blockquoteBecomesAQuote() {
        val blocks = HtmlBlocks.parse("<blockquote><p>quoted line</p></blockquote><p>after</p>")
        assertTrue(blocks.any { it is HtmlBlock.Quote && text(it) == "quoted line" })
        assertTrue(blocks.last() is HtmlBlock.Paragraph)
    }

    @Test fun preservesWhitespaceInsidePre() {
        val blocks = HtmlBlocks.parse("<pre><code>fun main() {\n    println(1)\n}</code></pre>")
        val code = blocks.filterIsInstance<HtmlBlock.Code>().single()
        assertEquals("fun main() {\n    println(1)\n}", code.text)
    }

    @Test fun imagesAreEmittedAndAbsolutised() {
        val blocks = HtmlBlocks.parse(
            """<p>before</p><figure><img src="/a.jpg" alt="A photo"></figure><p>after</p>""",
            "https://example.com/post/"
        )
        val image = blocks.filterIsInstance<HtmlBlock.Image>().single()
        assertEquals("https://example.com/a.jpg", image.url)
        assertEquals("A photo", image.alt)
    }

    @Test fun scriptStyleAndIframeContentIsDropped() {
        val blocks = HtmlBlocks.parse(
            "<p>real</p><script>var a = '<p>fake</p>';</script><style>p{}</style>" +
                "<iframe src=\"https://x\"><p>nope</p></iframe>"
        )
        assertEquals(1, blocks.size)
        assertEquals("real", text(blocks[0]))
    }

    @Test fun unclosedTagsDoNotSwallowTheRest() {
        val blocks = HtmlBlocks.parse("<p>start <b>bold never closed</p><p>next</p>")
        assertTrue(blocks.size >= 2)
        assertEquals("next", text(blocks.last()))
    }

    @Test fun attributeContainingAngleBracketIsSurvived() {
        val blocks = HtmlBlocks.parse("""<p title="a > b">body</p>""")
        assertEquals("body", text(blocks.single()))
    }

    @Test fun strayAngleBracketIsKeptAsText() {
        val blocks = HtmlBlocks.parse("<p>3 &lt; 4</p>")
        assertEquals("3 < 4", text(blocks.single()))
    }

    @Test fun entitiesAreDecodedInsideBlocks() {
        val blocks = HtmlBlocks.parse("<p>it&rsquo;s &amp; more&hellip;</p>")
        assertEquals("it’s & more…", text(blocks.single()))
    }

    @Test fun brBecomesALineBreakNotALostSpace() {
        val blocks = HtmlBlocks.parse("<p>line one<br>line two</p>")
        assertEquals("line one\nline two", text(blocks.single()))
    }

    @Test fun aWholeWordpressShapedBodyParsesIntoTheExpectedShape() {
        val body = """
            <p>Intro paragraph with a <a href="https://example.com/x">link</a>.</p>
            <h3>A heading</h3>
            <figure class="wp-block-image"><img src="https://cdn.example.com/p.jpg" alt="pic"/>
            <figcaption>Caption text</figcaption></figure>
            <ul><li>point one</li><li>point two</li></ul>
            <blockquote><p>A quote.</p></blockquote>
            <p>Closing words.</p>
        """.trimIndent()
        val blocks = HtmlBlocks.parse(body, "https://example.com/post/1")
        assertTrue(blocks.any { it is HtmlBlock.Heading })
        assertTrue(blocks.any { it is HtmlBlock.Image })
        assertTrue(blocks.any { it is HtmlBlock.Quote })
        assertEquals(2, blocks.filterIsInstance<HtmlBlock.Bullet>().size)
        assertEquals("Closing words.", text(blocks.last()))
        assertTrue(blocks.none { it is HtmlBlock.Paragraph && text(it).isBlank() })
    }

    @Test fun doubleEscapedBodyStillProducesReadableText() {
        val blocks = HtmlBlocks.parse("&lt;p&gt;escaped twice&lt;/p&gt;")
        assertTrue(text(blocks.single()).contains("escaped twice"))
    }
}
