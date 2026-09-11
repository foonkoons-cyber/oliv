package com.khabar.reader.feed

import java.nio.charset.Charset

/**
 * Making a real-world feed parseable.
 *
 * Every repair here is for something observed in live feeds: entities XML never declared, bare
 * '&' inside image URLs, a newline in front of the XML declaration, a prolog that contradicts the
 * bytes, and control characters a CMS let through.
 */
object XmlSanitizer {

    private val PROLOG_ENCODING = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Bytes to text, honouring BOM > prolog > HTTP charset > UTF-8. Never throws. */
    fun decode(bytes: ByteArray, headerCharset: String?): String {
        if (bytes.isEmpty()) return ""

        // A BOM outranks every declaration, including one that contradicts it.
        bomCharset(bytes)?.let { (charset, skip) ->
            return String(bytes, skip, bytes.size - skip, charset)
        }

        // The prolog is ASCII by definition, so latin-1 always decodes enough of it to read.
        val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.ISO_8859_1)
        val declared = if (head.startsWith("<?xml")) {
            PROLOG_ENCODING.find(head.substringBefore("?>", head))?.groupValues?.get(1)
        } else {
            null
        }

        val charset = charsetOrNull(declared) ?: charsetOrNull(headerCharset) ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /**
     * Repairs the text so a namespace-aware parser will accept it. Idempotent, and it never
     * touches the inside of a CDATA section or a comment — '&' is ordinary text in both.
     */
    fun sanitize(xml: String): String {
        if (xml.isEmpty()) return xml
        val trimmed = dropJunkBeforeRoot(xml)
        val out = StringBuilder(trimmed.length + 64)
        var i = 0
        while (i < trimmed.length) {
            when {
                trimmed.startsWith("<![CDATA[", i) -> {
                    val end = trimmed.indexOf("]]>", i)
                    val stop = if (end < 0) trimmed.length else end + 3
                    out.append(stripControlChars(trimmed.substring(i, stop)))
                    i = stop
                }

                trimmed.startsWith("<!--", i) -> {
                    val end = trimmed.indexOf("-->", i)
                    val stop = if (end < 0) trimmed.length else end + 3
                    out.append(trimmed, i, stop)
                    i = stop
                }

                trimmed[i] == '&' -> {
                    val (replacement, consumed) = repairAmpersand(trimmed, i)
                    out.append(replacement)
                    i += consumed
                }

                else -> {
                    val c = trimmed[i]
                    if (isLegalXmlChar(c)) out.append(c)
                    i++
                }
            }
        }
        // We hand the parser UTF-8 bytes, so a prolog claiming otherwise would be a lie.
        return rewritePrologEncoding(out.toString())
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return Charsets.UTF_8 to 3
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE to 2
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE to 2
        }
        return null
    }

    private fun charsetOrNull(name: String?): Charset? {
        val n = name?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            if (Charset.isSupported(n)) Charset.forName(n) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun dropJunkBeforeRoot(xml: String): String {
        val first = xml.indexOf('<')
        return when {
            first < 0 -> xml
            first == 0 -> xml.trimStart('\uFEFF')
            else -> xml.substring(first)
        }
    }

    /**
     * Turns whatever follows an '&' into something XML accepts: a valid reference is kept, a
     * known HTML entity becomes its numeric form, and anything else becomes a literal ampersand.
     */
    private fun repairAmpersand(text: String, at: Int): Pair<String, Int> {
        val semi = text.indexOf(';', at + 1)
        val name = if (semi > at + 1 && semi - at <= 34) text.substring(at + 1, semi) else null
        if (name != null) {
            if (name.startsWith("#")) {
                val valid = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) {
                    name.substring(2).isNotEmpty() && name.substring(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                } else {
                    name.length > 1 && name.substring(1).all { it.isDigit() }
                }
                // A reference to a control character is rejected by the parser even when escaped.
                if (valid && isLegalCodePointRef(name)) return text.substring(at, semi + 1) to (semi - at + 1)
                if (valid) return "" to (semi - at + 1)
            } else if (name.all { it.isLetterOrDigit() }) {
                if (HtmlText.isXmlPredefined(name)) return "&$name;" to (name.length + 2)
                HtmlText.namedEntity(name)?.let { value ->
                    return value.toNumericRefs() to (name.length + 2)
                }
            }
        }
        return "&amp;" to 1
    }

    private fun String.toNumericRefs(): String = buildString {
        var i = 0
        while (i < this@toNumericRefs.length) {
            val cp = this@toNumericRefs.codePointAt(i)
            append("&#").append(cp).append(';')
            i += Character.charCount(cp)
        }
    }

    private fun isLegalCodePointRef(name: String): Boolean {
        val cp = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) {
            name.substring(2).toIntOrNull(16)
        } else {
            name.substring(1).toIntOrNull()
        } ?: return false
        return cp == 0x9 || cp == 0xA || cp == 0xD || (cp in 0x20..0xD7FF) ||
            (cp in 0xE000..0xFFFD) || (cp in 0x10000..0x10FFFF)
    }

    private fun isLegalXmlChar(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\r' || (c >= ' ' && c != '￾' && c != '￿')

    private fun stripControlChars(text: String): String =
        if (text.all { isLegalXmlChar(it) }) text else text.filter { isLegalXmlChar(it) }

    private fun rewritePrologEncoding(xml: String): String {
        if (!xml.startsWith("<?xml")) return xml
        val end = xml.indexOf("?>")
        if (end < 0) return xml
        val prolog = xml.substring(0, end)
        val match = PROLOG_ENCODING.find(prolog) ?: return xml
        val fixed = prolog.replaceRange(match.range, "encoding=\"UTF-8\"")
        return fixed + xml.substring(end)
    }
}
