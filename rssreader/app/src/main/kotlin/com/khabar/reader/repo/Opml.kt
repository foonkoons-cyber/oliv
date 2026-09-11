package com.khabar.reader.repo

import com.khabar.reader.feed.HtmlText
import com.khabar.reader.feed.Urls
import com.khabar.reader.feed.XmlSanitizer
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * OPML import and export — how 200 subscriptions come across from Feedly or Inoreader, and how
 * they leave again. Nothing about the app is locked in, which is the point.
 */
object Opml {

    data class Entry(val title: String?, val xmlUrl: String, val siteUrl: String?)

    /** Forgiving by design: a file that is not OPML gives an empty list, not an exception. */
    fun parse(bytes: ByteArray): List<Entry> {
        if (bytes.isEmpty()) return emptyList()
        val direct = attempt(bytes)
        if (direct.complete) return direct.entries
        // Exports carry HTML entities in titles ("Tom &amp; Jerry&rsquo;s"), which XML never
        // declared — the same repair the feed parser needs.
        val repaired = try {
            attempt(XmlSanitizer.sanitize(XmlSanitizer.decode(bytes, null)).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
        return if (repaired != null && repaired.entries.size >= direct.entries.size) {
            repaired.entries
        } else {
            direct.entries
        }
    }

    private class Attempt(val entries: List<Entry>, val complete: Boolean)

    private fun attempt(bytes: ByteArray): Attempt {
        val handler = OutlineHandler()
        return try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd")
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities")
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities")
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
            Attempt(handler.entries, true)
        } catch (e: SAXException) {
            // Keep whatever was read before the document went wrong.
            Attempt(handler.entries, false)
        } catch (e: Exception) {
            Attempt(handler.entries, false)
        }
    }

    /** An OPML 2.0 document other readers accept, and that round-trips through [parse]. */
    fun build(entries: List<Entry>, title: String = "Khabar subscriptions"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head>\n    <title>").append(escape(title)).append("</title>\n  </head>\n")
        append("  <body>\n")
        for (entry in entries) {
            append("    <outline type=\"rss\"")
            append(" text=\"").append(escape(entry.title ?: entry.xmlUrl)).append('"')
            append(" title=\"").append(escape(entry.title ?: entry.xmlUrl)).append('"')
            append(" xmlUrl=\"").append(escape(entry.xmlUrl)).append('"')
            if (!entry.siteUrl.isNullOrBlank()) {
                append(" htmlUrl=\"").append(escape(entry.siteUrl)).append('"')
            }
            append("/>\n")
        }
        append("  </body>\n</opml>\n")
    }

    private fun trySetFeature(factory: SAXParserFactory, name: String) {
        try {
            factory.setFeature(name, false)
        } catch (e: Exception) {
            // Older platform parsers do not know the feature; resolveEntity below still blocks.
        }
    }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> append(' ')
                c < ' ' -> Unit
                else -> append(c)
            }
        }
    }

    private class OutlineHandler : DefaultHandler() {

        val entries = mutableListOf<Entry>()
        private val seen = mutableSetOf<String>()

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = (localName?.takeIf { it.isNotEmpty() } ?: qName ?: return).lowercase()
            if (name != "outline" || attributes == null) return

            // Outlines with no xmlUrl are category folders; descend, do not emit.
            val raw = attributes.find("xmlurl") ?: return
            val url = Urls.normalizeInput(HtmlText.decodeEntities(raw).trim()) ?: return
            val key = Urls.canonical(url) ?: url
            if (!seen.add(key)) return

            val title = (attributes.find("title") ?: attributes.find("text"))
                ?.let { HtmlText.decodeEntities(it).trim() }
                ?.takeIf { it.isNotEmpty() }
            val site = attributes.find("htmlurl")
                ?.let { Urls.normalizeInput(HtmlText.decodeEntities(it).trim()) }
            entries += Entry(title, url, site)
        }

        /** Exports in the wild use xmlUrl, xmlurl and XMLURL; match on the lowercase name. */
        private fun Attributes.find(name: String): String? {
            for (i in 0 until length) {
                val attr = (getLocalName(i)?.takeIf { it.isNotEmpty() } ?: getQName(i)).lowercase()
                if (attr == name || attr.endsWith(":$name")) return getValue(i)
            }
            return null
        }
    }
}
