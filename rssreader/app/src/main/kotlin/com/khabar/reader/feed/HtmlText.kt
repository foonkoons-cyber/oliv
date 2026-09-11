package com.khabar.reader.feed

/**
 * HTML handling for feed content. Not a parser — a tolerant scanner.
 *
 * Feed bodies are arbitrary publisher markup: unclosed tags, attributes containing '>', entities
 * that survived a CDATA section undecoded, and bodies that were escaped twice on the way out of a
 * CMS. None of that may throw, and none of it may leak markup into the UI.
 */
object HtmlText {

    /** Plain text: tags out, entities decoded, whitespace collapsed. */
    fun toPlainText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var text = stripInvisible(html)
        text = stripTags(text)
        text = decodeEntities(text)
        // Escaped-twice bodies only reveal their markup after the first decode. Test for a real
        // tag, not just a '<' — "5 < 6 and 7 > 2" is arithmetic, and stripping it loses the line.
        if (looksLikeMarkup(text)) text = stripTags(text)
        return collapseWhitespace(text)
    }

    /** [toPlainText], cut on a word boundary, with an ellipsis only if something was cut. */
    fun snippet(html: String?, maxChars: Int = 300): String {
        val text = toPlainText(html)
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val body = if (lastSpace > maxChars / 2) cut.substring(0, lastSpace) else cut
        return body.trimEnd(' ', ',', ';', ':', '-', '–', '—') + "…"
    }

    /** The first real <img> in the markup, absolutised. Tracking pixels are skipped. */
    fun firstImageUrl(html: String?, baseUrl: String? = null): String? {
        if (html.isNullOrBlank()) return null
        var index = 0
        while (true) {
            val open = html.indexOf("<img", index, ignoreCase = true)
            if (open < 0) return null
            val close = findTagEnd(html, open)
            if (close < 0) return null
            val tag = html.substring(open, close + 1)
            index = close + 1

            val width = attribute(tag, "width")?.filter { it.isDigit() }?.toIntOrNull()
            val height = attribute(tag, "height")?.filter { it.isDigit() }?.toIntOrNull()
            if (width == 1 || height == 1) continue          // a counter, not a picture

            val src = attribute(tag, "src")
                ?: attribute(tag, "data-src")
                ?: attribute(tag, "srcset")?.substringBefore(' ')?.substringBefore(',')
                ?: continue
            if (src.startsWith("data:", ignoreCase = true)) continue
            val resolved = Urls.resolve(baseUrl, decodeEntities(src.trim()))
            if (resolved != null) return resolved
        }
    }

    /**
     * Named, decimal and hex references in one pass. One pass matters: decoding twice would turn
     * an escaped "&amp;lt;b&amp;gt;" into a real tag.
     */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            val body = if (semi > i && semi - i <= MAX_ENTITY_LENGTH) text.substring(i + 1, semi) else null
            val decoded = body?.let { decodeOne(it) }
            if (decoded != null) {
                out.append(decoded)
                i = semi + 1
                continue
            }
            // A missing semicolon is common in hand-written feeds, but only a few names are
            // safe to match without one — "&nested" must not become "≠sted".
            val loose = looseNamedAt(text, i + 1)
            if (loose != null) {
                out.append(loose.second)
                i += 1 + loose.first
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    // ---- internals ---------------------------------------------------------------------------

    private const val MAX_ENTITY_LENGTH = 32

    private fun decodeOne(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val codePoint = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            } ?: return null
            if (codePoint <= 0 || codePoint > 0x10FFFF) return null
            return try {
                String(Character.toChars(codePoint))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return NAMED[body] ?: NAMED[body.lowercase()]
    }

    private val LOOSE = listOf(
        "nbsp" to "\u00A0", "quot" to "\"", "apos" to "'", "amp" to "&", "lt" to "<", "gt" to ">"
    )

    private fun looseNamedAt(text: String, start: Int): Pair<Int, String>? {
        for ((name, value) in LOOSE) {
            if (text.regionMatches(start, name, 0, name.length)) return name.length to value
        }
        return null
    }

    private val TAG_SHAPED = Regex("</?[a-zA-Z][a-zA-Z0-9]{0,15}(\\s[^<>]*)?/?>")

    private fun looksLikeMarkup(text: String): Boolean =
        text.contains('<') && TAG_SHAPED.containsMatchIn(text)

    private fun stripInvisible(html: String): String {
        var s = html
        for (tag in listOf("script", "style", "noscript", "iframe", "svg", "form", "template")) {
            s = removeBlock(s, tag)
        }
        return removeComments(s)
    }

    private fun removeComments(html: String): String {
        if (!html.contains("<!--")) return html
        val out = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val start = html.indexOf("<!--", i)
            if (start < 0) {
                out.append(html, i, html.length)
                break
            }
            out.append(html, i, start)
            val end = html.indexOf("-->", start + 4)
            i = if (end < 0) html.length else end + 3
        }
        return out.toString()
    }

    private fun removeBlock(html: String, tag: String): String {
        if (!html.contains("<$tag", ignoreCase = true)) return html
        val out = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val start = html.indexOf("<$tag", i, ignoreCase = true)
            if (start < 0) {
                out.append(html, i, html.length)
                break
            }
            // "<sv" must not match "<svgfoo"-style neighbours or a <span> when tag is "s".
            val after = start + tag.length + 1
            val boundary = after >= html.length || !html[after].isLetterOrDigit()
            if (!boundary) {
                out.append(html, i, start + 1)
                i = start + 1
                continue
            }
            out.append(html, i, start)
            val closing = html.indexOf("</$tag", start, ignoreCase = true)
            i = if (closing < 0) html.length else (findTagEnd(html, closing).takeIf { it >= 0 }?.plus(1) ?: html.length)
        }
        return out.toString()
    }

    private fun stripTags(html: String): String {
        val out = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c != '<') {
                out.append(c)
                i++
                continue
            }
            val end = findTagEnd(html, i)
            if (end < 0) {
                // A stray '<' that never closes is text, not a tag.
                out.append(html, i, html.length)
                break
            }
            val tag = html.substring(i, end + 1)
            // Block tags break the line; inline ones join, or "<a>link</a>." gains a space
            // before the full stop and every summary reads slightly broken.
            if (isBreaking(tag)) out.append('\n')
            i = end + 1
        }
        return out.toString()
    }

    private fun isBreaking(tag: String): Boolean {
        val name = tagName(tag) ?: return false
        return name in BREAKING
    }

    private val BREAKING = setOf(
        "p", "br", "div", "li", "tr", "ul", "ol", "blockquote", "pre", "hr", "section",
        "article", "header", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "table", "figure"
    )

    internal fun tagName(tag: String): String? {
        var i = 1
        if (i < tag.length && tag[i] == '/') i++
        val start = i
        while (i < tag.length && (tag[i].isLetterOrDigit())) i++
        return if (i > start) tag.substring(start, i).lowercase() else null
    }

    /** Index of the '>' that closes the tag starting at [open], honouring quoted attributes. */
    internal fun findTagEnd(html: String, open: Int): Int {
        var i = open + 1
        var quote = ' '
        while (i < html.length) {
            val c = html[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    /** Attribute value from a full tag string. Handles ", ' and unquoted values. */
    internal fun attribute(tag: String, name: String): String? {
        var i = 0
        while (true) {
            i = tag.indexOf(name, i, ignoreCase = true)
            if (i < 0) return null
            val before = if (i == 0) ' ' else tag[i - 1]
            var j = i + name.length
            if (!before.isLetterOrDigit() && before != '-') {
                while (j < tag.length && tag[j] == ' ') j++
                if (j < tag.length && tag[j] == '=') {
                    j++
                    while (j < tag.length && tag[j] == ' ') j++
                    if (j >= tag.length) return null
                    val quote = tag[j]
                    return if (quote == '"' || quote == '\'') {
                        val end = tag.indexOf(quote, j + 1)
                        if (end < 0) null else tag.substring(j + 1, end)
                    } else {
                        val end = tag.indexOfFrom(j) { it == ' ' || it == '>' || it == '\n' }
                        tag.substring(j, if (end < 0) tag.length else end).trimEnd('/')
                    }
                }
            }
            i += name.length
        }
    }

    private inline fun String.indexOfFrom(from: Int, predicate: (Char) -> Boolean): Int {
        for (k in from until length) if (predicate(this[k])) return k
        return -1
    }

    private fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var lastWasSpace = false
        for (ch in text) {
            val c = if (ch == '\u00A0' || ch == '\u200B' || ch.isWhitespace()) ' ' else ch
            if (c == ' ') {
                if (!lastWasSpace) out.append(' ')
                lastWasSpace = true
            } else {
                out.append(c)
                lastWasSpace = false
            }
        }
        return out.toString().trim()
    }

    /** The five XML predefines a parser already knows; everything else must be rewritten. */
    internal fun isXmlPredefined(name: String): Boolean =
        name == "amp" || name == "lt" || name == "gt" || name == "quot" || name == "apos"

    /** Replacement text for a named entity, for the XML sanitiser. */
    internal fun namedEntity(name: String): String? = NAMED[name] ?: NAMED[name.lowercase()]

    /** The named entities that actually turn up in feeds, plus the XML five. */
    private val NAMED: Map<String, String> = buildMap {
        put("amp", "&"); put("lt", "<"); put("gt", ">"); put("quot", "\""); put("apos", "'")
        put("nbsp", "\u00A0"); put("ensp", " "); put("emsp", " "); put("thinsp", " ")
        put("shy", ""); put("zwnj", ""); put("zwj", "")
        put("mdash", "—"); put("ndash", "–"); put("hellip", "…"); put("bull", "•")
        put("middot", "·"); put("sdot", "⋅"); put("frasl", "⁄"); put("dagger", "†")
        put("lsquo", "‘"); put("rsquo", "’"); put("sbquo", "‚")
        put("ldquo", "“"); put("rdquo", "”"); put("bdquo", "„")
        put("laquo", "«"); put("raquo", "»"); put("lsaquo", "‹"); put("rsaquo", "›")
        put("copy", "©"); put("reg", "®"); put("trade", "™"); put("deg", "°")
        put("plusmn", "±"); put("times", "×"); put("divide", "÷"); put("minus", "−")
        put("frac12", "½"); put("frac14", "¼"); put("frac34", "¾")
        put("euro", "€"); put("pound", "£"); put("yen", "¥"); put("cent", "¢"); put("curren", "¤")
        put("sect", "§"); put("para", "¶"); put("micro", "µ"); put("permil", "‰")
        put("larr", "←"); put("rarr", "→"); put("uarr", "↑"); put("darr", "↓"); put("harr", "↔")
        put("infin", "∞"); put("ne", "≠"); put("le", "≤"); put("ge", "≥"); put("asymp", "≈")
        put("prime", "′"); put("Prime", "″"); put("oline", "‾"); put("circ", "ˆ"); put("tilde", "˜")
        put("agrave", "à"); put("aacute", "á"); put("acirc", "â"); put("atilde", "ã")
        put("auml", "ä"); put("aring", "å"); put("aelig", "æ"); put("ccedil", "ç")
        put("egrave", "è"); put("eacute", "é"); put("ecirc", "ê"); put("euml", "ë")
        put("igrave", "ì"); put("iacute", "í"); put("icirc", "î"); put("iuml", "ï")
        put("ntilde", "ñ"); put("ograve", "ò"); put("oacute", "ó"); put("ocirc", "ô")
        put("otilde", "õ"); put("ouml", "ö"); put("oslash", "ø"); put("ugrave", "ù")
        put("uacute", "ú"); put("ucirc", "û"); put("uuml", "ü"); put("yuml", "ÿ")
        put("szlig", "ß"); put("thorn", "þ"); put("eth", "ð")
        put("Agrave", "À"); put("Aacute", "Á"); put("Acirc", "Â"); put("Auml", "Ä")
        put("Ccedil", "Ç"); put("Egrave", "È"); put("Eacute", "É"); put("Ecirc", "Ê")
        put("Iacute", "Í"); put("Ntilde", "Ñ"); put("Oacute", "Ó"); put("Ouml", "Ö")
        put("Uacute", "Ú"); put("Uuml", "Ü"); put("AElig", "Æ"); put("Oslash", "Ø")
        put("alpha", "α"); put("beta", "β"); put("gamma", "γ"); put("delta", "δ")
        put("pi", "π"); put("mu", "μ"); put("omega", "ω"); put("Omega", "Ω")
        put("star", "☆"); put("hearts", "♥"); put("check", "✓"); put("cross", "✗")
    }
}
