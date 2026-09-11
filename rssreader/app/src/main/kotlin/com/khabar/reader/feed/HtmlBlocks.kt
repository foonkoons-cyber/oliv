package com.khabar.reader.feed

/** A run of text with one styling, as the reader will draw it. */
data class HtmlSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null
)

sealed interface HtmlBlock {
    data class Paragraph(val spans: List<HtmlSpan>) : HtmlBlock
    data class Heading(val level: Int, val spans: List<HtmlSpan>) : HtmlBlock
    /** [marker] is already rendered: "•" or "3.". */
    data class Bullet(val marker: String, val spans: List<HtmlSpan>) : HtmlBlock
    data class Quote(val spans: List<HtmlSpan>) : HtmlBlock
    data class Code(val text: String) : HtmlBlock
    data class Image(val url: String, val alt: String?) : HtmlBlock
    data object Rule : HtmlBlock
}

/**
 * Turns article HTML into a short list of blocks the reader can draw with ordinary composables.
 *
 * A WebView would render more, but it also means a second layout engine, a scroll container that
 * fights the outer one, and remote requests firing on an article you saved to read offline. The subset here covers what
 * feed bodies actually contain.
 */
object HtmlBlocks {

    private const val MAX_BLOCKS = 800

    fun parse(html: String?, baseUrl: String? = null): List<HtmlBlock> {
        if (html.isNullOrBlank()) return emptyList()
        return try {
            Builder(baseUrl).run(html)
        } catch (e: Exception) {
            // Never lose the article to a parsing bug: fall back to plain text.
            val text = HtmlText.toPlainText(html)
            if (text.isEmpty()) emptyList() else listOf(HtmlBlock.Paragraph(listOf(HtmlSpan(text))))
        }
    }

    private class Builder(private val baseUrl: String?) {

        private val blocks = mutableListOf<HtmlBlock>()
        private val spans = mutableListOf<HtmlSpan>()
        private val buffer = StringBuilder()

        private var bold = 0
        private var italic = 0
        private var code = 0
        private val links = ArrayDeque<String>()

        private var heading = 0
        private var quote = 0
        private var listItemMarker: String? = null
        private val listStack = ArrayDeque<ListState>()

        private class ListState(val ordered: Boolean) {
            var index = 0
        }

        fun run(html: String): List<HtmlBlock> {
            var i = 0
            while (i < html.length && blocks.size < MAX_BLOCKS) {
                val c = html[i]
                if (c != '<') {
                    val next = html.indexOf('<', i)
                    val end = if (next < 0) html.length else next
                    buffer.append(html, i, end)
                    i = end
                    continue
                }
                val close = HtmlText.findTagEnd(html, i)
                if (close < 0) {
                    buffer.append(html, i, html.length)
                    break
                }
                val tag = html.substring(i, close + 1)
                val name = HtmlText.tagName(tag)
                if (name == null) {
                    buffer.append(tag)
                    i = close + 1
                    continue
                }
                val closing = tag.startsWith("</")
                i = close + 1
                when (name) {
                    "script", "style", "noscript", "iframe", "svg", "form", "template" -> {
                        if (!closing) i = skipTo(html, name, i)
                    }
                    "pre" -> if (!closing) i = readPre(html, i) else Unit
                    else -> handle(name, tag, closing)
                }
            }
            flush()
            return blocks
        }

        private fun skipTo(html: String, name: String, from: Int): Int {
            val closing = html.indexOf("</$name", from, ignoreCase = true)
            if (closing < 0) return html.length
            val end = HtmlText.findTagEnd(html, closing)
            return if (end < 0) html.length else end + 1
        }

        /** <pre> keeps its whitespace, so it is read whole rather than fed through the scanner. */
        private fun readPre(html: String, from: Int): Int {
            flush()
            val closing = html.indexOf("</pre", from, ignoreCase = true)
            val bodyEnd = if (closing < 0) html.length else closing
            val raw = html.substring(from, bodyEnd)
            val text = HtmlText.decodeEntities(stripInnerTags(raw)).trim('\n', ' ')
            if (text.isNotEmpty()) blocks += HtmlBlock.Code(text)
            if (closing < 0) return html.length
            val end = HtmlText.findTagEnd(html, closing)
            return if (end < 0) html.length else end + 1
        }

        private fun stripInnerTags(raw: String): String {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '<') {
                    out.append(c)
                    i++
                    continue
                }
                val end = HtmlText.findTagEnd(raw, i)
                if (end < 0) {
                    out.append(raw, i, raw.length)
                    break
                }
                i = end + 1
            }
            return out.toString()
        }

        private fun handle(name: String, tag: String, closing: Boolean) {
            when (name) {
                "b", "strong" -> { commit(); if (closing) bold-- else bold++ }
                "i", "em", "cite" -> { commit(); if (closing) italic-- else italic++ }
                "code", "kbd", "samp", "tt" -> { commit(); if (closing) code-- else code++ }

                "a" -> {
                    commit()
                    if (closing) {
                        links.removeLastOrNull()
                    } else {
                        val href = Urls.resolve(baseUrl, HtmlText.attribute(tag, "href")?.let {
                            HtmlText.decodeEntities(it)
                        })
                        if (href != null) links.addLast(href)
                    }
                }

                "br" -> buffer.append('\n')

                "p", "div", "section", "article", "figure", "figcaption", "table", "tr", "td" ->
                    flush()

                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flush()
                    heading = if (closing) 0 else name.substring(1).toInt()
                }

                "blockquote" -> {
                    flush()
                    if (closing) quote = (quote - 1).coerceAtLeast(0) else quote++
                }

                "ul", "ol" -> {
                    flush()
                    if (closing) {
                        listStack.removeLastOrNull()
                        listItemMarker = null
                    } else {
                        listStack.addLast(ListState(ordered = name == "ol"))
                    }
                }

                "li" -> {
                    flush()
                    listItemMarker = if (closing) {
                        null
                    } else {
                        val state = listStack.lastOrNull()
                        if (state == null) "•"
                        else if (state.ordered) "${++state.index}." else "•"
                    }
                }

                "img" -> {
                    flush()
                    val src = HtmlText.attribute(tag, "src")
                        ?: HtmlText.attribute(tag, "data-src")
                    val url = Urls.resolve(baseUrl, src?.let { HtmlText.decodeEntities(it.trim()) })
                    if (url != null && !url.startsWith("data:")) {
                        val alt = HtmlText.attribute(tag, "alt")
                            ?.let { HtmlText.decodeEntities(it).trim() }
                            ?.takeIf { it.isNotEmpty() }
                        blocks += HtmlBlock.Image(url, alt)
                    }
                }

                "hr" -> {
                    flush()
                    blocks += HtmlBlock.Rule
                }
            }
        }

        /** Moves the text collected so far into a span carrying the styles now in effect. */
        private fun commit() {
            if (buffer.isEmpty()) return
            val text = HtmlText.decodeEntities(buffer.toString())
            buffer.setLength(0)
            if (text.isBlank() && spans.isEmpty()) return
            spans += HtmlSpan(
                text = text,
                bold = bold > 0,
                italic = italic > 0,
                code = code > 0,
                link = links.lastOrNull()
            )
        }

        private fun flush() {
            commit()
            if (spans.isEmpty()) return
            val cleaned = normalise(spans)
            spans.clear()
            if (cleaned.isEmpty()) return
            val marker = listItemMarker
            blocks += when {
                heading in 1..6 -> HtmlBlock.Heading(heading, cleaned)
                marker != null -> HtmlBlock.Bullet(marker, cleaned)
                quote > 0 -> HtmlBlock.Quote(cleaned)
                else -> HtmlBlock.Paragraph(cleaned)
            }
        }

        /** Collapses whitespace across the block and drops it if nothing readable is left. */
        private fun normalise(input: List<HtmlSpan>): List<HtmlSpan> {
            val out = mutableListOf<HtmlSpan>()
            for (span in input) {
                val text = span.text
                    .replace('\u00A0', ' ')
                    .replace(Regex("[ \\t\\r\\f]+"), " ")
                    .replace(Regex(" *\\n *"), "\n")
                if (text.isBlank() && out.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null &&
                    last.bold == span.bold && last.italic == span.italic &&
                    last.code == span.code && last.link == span.link
                ) {
                    out[out.size - 1] = last.copy(text = last.text + text)
                } else {
                    out += span.copy(text = text)
                }
            }
            while (out.isNotEmpty() && out.last().text.isBlank()) out.removeAt(out.size - 1)
            if (out.isEmpty()) return out
            out[0] = out[0].copy(text = out[0].text.trimStart())
            out[out.size - 1] = out[out.size - 1].copy(text = out.last().text.trimEnd())
            return out.filter { it.text.isNotEmpty() }
        }
    }
}
