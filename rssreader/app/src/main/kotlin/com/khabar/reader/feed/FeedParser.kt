package com.khabar.reader.feed

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * RSS 2.0, Atom 1.0 and RDF/RSS 1.0, in one namespace-aware SAX pass.
 *
 * Two attempts: the raw bytes first, so a well-formed feed never pays for the repairs, then the
 * sanitised text. Whichever yields more items wins — a document that dies half way through still
 * gives the user the items it managed to produce.
 */
object FeedParser {

    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val MEDIA = "http://search.yahoo.com/mrss/"
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    @Throws(FeedParseException::class)
    fun parse(bytes: ByteArray, headerCharset: String? = null, baseUrl: String? = null): ParsedFeed {
        if (bytes.isEmpty()) throw FeedParseException("Feed khali hai.")

        val direct = attempt(bytes, baseUrl)
        if (direct.clean && direct.feed != null) return direct.feed

        val repaired = try {
            val text = XmlSanitizer.sanitize(XmlSanitizer.decode(bytes, headerCharset))
            attempt(text.toByteArray(Charsets.UTF_8), baseUrl)
        } catch (e: Exception) {
            Attempt(null, false, e)
        }

        val best = listOfNotNull(repaired.feed, direct.feed).maxByOrNull { it.items.size }
        if (best != null && (best.items.isNotEmpty() || best.title != null)) return best
        throw FeedParseException(
            "Ye feed padha nahi ja saka.",
            repaired.error ?: direct.error
        )
    }

    /** Cheap sniff for the discovery path — no parse, just the root element. */
    fun looksLikeFeed(bytes: ByteArray): Boolean =
        FeedDiscovery.looksLikeFeedText(XmlSanitizer.decode(bytes.copyOf(minOf(bytes.size, 4096)), null))

    private class Attempt(val feed: ParsedFeed?, val clean: Boolean, val error: Exception?)

    private fun attempt(bytes: ByteArray, baseUrl: String?): Attempt {
        val handler = FeedHandler(baseUrl)
        return try {
            newParser().parse(ByteArrayInputStream(bytes), handler)
            val feed = handler.build().takeIf { handler.isFeedDocument() }
            Attempt(feed, feed != null, null)
        } catch (e: SAXException) {
            // Partial is better than nothing: a feed truncated mid-transfer still has items.
            Attempt(partial(handler), false, e)
        } catch (e: Exception) {
            Attempt(partial(handler), false, e)
        }
    }

    private fun partial(handler: FeedHandler): ParsedFeed? =
        handler.build().takeIf { handler.isFeedDocument() && it.items.isNotEmpty() }

    private fun newParser(): javax.xml.parsers.SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        // A <!DOCTYPE> pointing at a remote DTD would otherwise make the parser hit the network
        // on a background refresh — slow at best, an XXE vector at worst.
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newSAXParser()
    }

    private fun setFeature(factory: SAXParserFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (e: Exception) {
            // Not every platform parser knows every feature; the entity resolver below is the
            // backstop that actually matters.
        }
    }

    private class FeedHandler(private val baseUrl: String?) : DefaultHandler() {

        private var feedTitle: String? = null
        private var feedLink: String? = null
        private var feedAtomLink: String? = null
        private var feedDescription: String? = null
        private var feedIcon: String? = null

        private val items = mutableListOf<ParsedItem>()
        private var item: ItemBuilder? = null

        private val text = StringBuilder()
        private var capture: CaptureState? = null
        private var depth = 0
        private var imageDepth = -1
        private var authorDepth = -1
        private var currentElement: String? = null
        private var root: String? = null

        private class CaptureState(val startDepth: Int, val target: String)

        private class ItemBuilder {
            var guid: String? = null
            var guidIsPermalink = true
            var title: String? = null
            var link: String? = null
            var atomLink: String? = null
            var author: String? = null
            var summary: String? = null
            var content: String? = null
            var image: String? = null
            var date: Long? = null
            var fallbackDate: Long? = null
        }

        /** Never fetch anything a document points at, DTD or otherwise. */
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            depth++
            val name = (localName ?: qName ?: return).lowercase()
            val ns = uri ?: ""
            if (root == null) root = name

            // Inside a markup-capturing element every child is content, not structure.
            capture?.let { state ->
                if (depth > state.startDepth) {
                    text.append(serializeStart(qName ?: name, attributes))
                    return
                }
            }

            currentElement = name
            text.setLength(0)

            when {
                name == "item" || (ns == ATOM && name == "entry") -> item = ItemBuilder()
                name == "image" && item == null && ns != ITUNES && ns != MEDIA ->
                    imageDepth = depth
                name == "author" && ns == ATOM -> authorDepth = depth

                name == "link" && ns == ATOM -> {
                    val href = attributes?.getValue("href")?.let { Urls.resolve(baseUrl, it) }
                    val rel = attributes?.getValue("rel")?.lowercase() ?: "alternate"
                    val type = attributes?.getValue("type")?.lowercase()
                    if (href != null) {
                        when {
                            rel == "enclosure" && type?.startsWith("image/") == true ->
                                item?.let { if (it.image == null) it.image = href }
                            rel == "alternate" && (type == null || type.contains("html")) -> {
                                if (item != null) {
                                    if (item?.atomLink == null) item?.atomLink = href
                                } else if (feedAtomLink == null) {
                                    feedAtomLink = href
                                }
                            }
                        }
                    }
                }

                name == "enclosure" -> {
                    val type = attributes?.getValue("type")?.lowercase()
                    val url = attributes?.getValue("url")?.let { Urls.resolve(baseUrl, it) }
                    // A podcast enclosure is audio; only an image is a thumbnail.
                    if (url != null && type?.startsWith("image/") == true) {
                        item?.let { if (it.image == null) it.image = url }
                    }
                }

                ns == MEDIA && (name == "content" || name == "thumbnail") -> {
                    val url = attributes?.getValue("url")?.let { Urls.resolve(baseUrl, it) }
                    val medium = attributes?.getValue("medium")?.lowercase()
                    val type = attributes?.getValue("type")?.lowercase()
                    val isImage = name == "thumbnail" || medium == "image" ||
                        type?.startsWith("image/") == true
                    if (url != null && isImage) {
                        val target = item
                        if (target != null) {
                            if (target.image == null) target.image = url
                        } else if (feedIcon == null) {
                            feedIcon = url
                        }
                    }
                }

                ns == ITUNES && name == "image" -> {
                    val href = attributes?.getValue("href")?.let { Urls.resolve(baseUrl, it) }
                    if (href != null) {
                        val target = item
                        if (target != null) {
                            if (target.image == null) target.image = href
                        } else if (feedIcon == null) {
                            feedIcon = href
                        }
                    }
                }

                name == "guid" -> item?.guidIsPermalink =
                    attributes?.getValue("isPermaLink")?.equals("false", ignoreCase = true) != true

                // These carry publisher HTML; keep the markup instead of flattening it.
                name == "description" || name == "summary" || name == "encoded" ||
                    (ns == ATOM && name == "content") ->
                    capture = CaptureState(depth, name)
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            // SAX splits text across calls at buffer and entity boundaries; accumulate always.
            if (ch != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (localName ?: qName ?: "").lowercase()
            val ns = uri ?: ""

            capture?.let { state ->
                if (depth > state.startDepth) {
                    text.append("</").append(qName ?: name).append('>')
                    depth--
                    return
                }
            }

            val value = text.toString().trim()
            text.setLength(0)
            val inCapture = capture?.startDepth == depth
            if (inCapture) capture = null

            val current = item
            when {
                name == "item" || (ns == ATOM && name == "entry") -> {
                    current?.let { items += it.toItem() }
                    item = null
                }

                depth == imageDepth -> imageDepth = -1
                depth == authorDepth -> authorDepth = -1

                current != null -> applyItemField(current, name, ns, value)
                imageDepth > 0 -> if (name == "url" && feedIcon == null) {
                    feedIcon = Urls.resolve(baseUrl, value)
                }
                else -> applyFeedField(name, ns, value)
            }
            depth--
        }

        private fun applyItemField(target: ItemBuilder, name: String, ns: String, value: String) {
            if (value.isEmpty() && name != "guid") return
            when {
                name == "title" -> if (target.title == null) target.title = clean(value)
                name == "link" && ns != ATOM -> if (target.link == null) {
                    target.link = Urls.resolve(baseUrl, value)
                }
                name == "guid" || (ns == ATOM && name == "id") ->
                    if (target.guid == null) target.guid = value.takeIf { it.isNotEmpty() }
                name == "encoded" || (ns == ATOM && name == "content") ->
                    if (target.content == null) target.content = value
                name == "description" || name == "summary" ->
                    if (target.summary == null) target.summary = value
                name == "creator" || name == "author" ->
                    if (target.author == null && authorDepth < 0) target.author = clean(value)
                name == "name" && authorDepth > 0 ->
                    if (target.author == null) target.author = clean(value)
                name == "pubdate" || name == "date" || name == "published" ->
                    if (target.date == null) target.date = DateParsing.parse(value)
                name == "updated" || name == "modified" ->
                    if (target.fallbackDate == null) target.fallbackDate = DateParsing.parse(value)
            }
        }

        private fun applyFeedField(name: String, ns: String, value: String) {
            if (value.isEmpty()) return
            when {
                name == "title" -> if (feedTitle == null) feedTitle = clean(value)
                name == "link" && ns != ATOM -> if (feedLink == null) {
                    feedLink = Urls.resolve(baseUrl, value)
                }
                name == "description" || name == "subtitle" || name == "tagline" ->
                    if (feedDescription == null) feedDescription = clean(value)
                name == "icon" || name == "logo" -> if (feedIcon == null) {
                    feedIcon = Urls.resolve(baseUrl, value)
                }
            }
        }

        private fun serializeStart(qName: String, attributes: Attributes?): String = buildString {
            append('<').append(qName)
            if (attributes != null) {
                for (i in 0 until attributes.length) {
                    append(' ').append(attributes.getQName(i)).append("=\"")
                    append(escapeAttribute(attributes.getValue(i))).append('"')
                }
            }
            append('>')
        }

        private fun escapeAttribute(value: String?): String =
            (value ?: "").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

        /** Titles arrive as HTML often enough that leaving tags in them is not an option. */
        private fun clean(value: String): String? =
            HtmlText.toPlainText(value).takeIf { it.isNotEmpty() }

        private fun ItemBuilder.toItem(): ParsedItem {
            val resolvedLink = link ?: atomLink
                ?: guid?.takeIf { guidIsPermalink && it.startsWith("http", ignoreCase = true) }
                    ?.let { Urls.resolve(baseUrl, it) }
            val body = content ?: summary
            return ParsedItem(
                guid = guid,
                title = title,
                link = resolvedLink,
                author = author,
                summaryHtml = summary,
                contentHtml = content,
                imageUrl = image ?: HtmlText.firstImageUrl(body, resolvedLink ?: baseUrl),
                publishedAt = date ?: fallbackDate
            )
        }

        /** An HTML page can be well-formed XML; only these roots are actually a feed. */
        fun isFeedDocument(): Boolean = root in setOf("rss", "feed", "rdf", "channel")

        fun build(): ParsedFeed {
            // A half-built item at the end means the document was cut off; keep it.
            item?.let {
                if (it.title != null || it.link != null) {
                    items += it.toItem()
                    item = null
                }
            }
            return ParsedFeed(
                title = feedTitle,
                siteUrl = feedLink ?: feedAtomLink,
                description = feedDescription,
                iconUrl = feedIcon,
                items = items.filter { it.title != null || it.link != null }
                    .distinctBy { it.guid ?: it.link ?: it.title }
            )
        }
    }
}
