package com.khabar.reader.feed

import java.net.URI

/**
 * Finding a feed when the user pasted a web page instead of a feed.
 *
 * HTML is not XML and there is no HTML parser on hand, so this scans. It only has to be right
 * about <link rel="alternate"> in real pages, and wrong answers are cheap: every candidate is
 * fetched and parsed before it is offered to the user.
 */
object FeedDiscovery {

    private val FEED_TYPES = setOf(
        "application/rss+xml",
        "application/atom+xml",
        "application/rdf+xml",
        "application/xml",
        "text/xml",
        "application/json"      // JSON Feed pages advertise this; we will fail it on parse
    )

    /** Declared feeds, in document order, absolutised and deduped. */
    fun fromHtml(html: String, baseUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val head = html.take(MAX_SCAN)

        var i = 0
        while (true) {
            val open = head.indexOf("<link", i, ignoreCase = true)
            if (open < 0) break
            val close = HtmlText.findTagEnd(head, open)
            if (close < 0) break
            val tag = head.substring(open, close + 1)
            i = close + 1

            val rel = HtmlText.attribute(tag, "rel")?.lowercase()?.split(' ', '\t') ?: continue
            if (!rel.contains("alternate") && !rel.contains("feed")) continue
            val type = HtmlText.attribute(tag, "type")?.lowercase()?.trim()
            if (type != null && type !in FEED_TYPES) continue
            if (type == null && !rel.contains("feed")) continue
            val href = HtmlText.attribute(tag, "href") ?: continue
            Urls.resolve(baseUrl, HtmlText.decodeEntities(href).trim())?.let { found.add(it) }
        }

        if (found.isEmpty()) found.addAll(fromAnchors(head, baseUrl))
        return found.toList()
    }

    /** Last resort before guessing: links on the page that are shaped like a feed. */
    private fun fromAnchors(html: String, baseUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        var i = 0
        while (found.size < 5) {
            val open = html.indexOf("<a", i, ignoreCase = true)
            if (open < 0) break
            val close = HtmlText.findTagEnd(html, open)
            if (close < 0) break
            val tag = html.substring(open, close + 1)
            i = close + 1
            if (HtmlText.tagName(tag) != "a") continue

            val href = HtmlText.attribute(tag, "href")?.trim() ?: continue
            val lower = href.lowercase()
            val looksRight = lower.endsWith(".xml") || lower.endsWith(".rss") ||
                lower.endsWith(".atom") || lower.endsWith(".rdf") ||
                lower.endsWith("/feed") || lower.endsWith("/feed/") ||
                lower.endsWith("/rss") || lower.endsWith("/rss/") ||
                lower.contains("/feed?") || lower.contains("feed=rss")
            if (!looksRight) continue
            Urls.resolve(baseUrl, HtmlText.decodeEntities(href))?.let { found.add(it) }
        }
        return found.toList()
    }

    /** The paths blog engines actually use, tried when a page declares nothing. */
    fun commonPaths(baseUrl: String): List<String> {
        val root = try {
            val uri = URI(baseUrl)
            val scheme = uri.scheme ?: return emptyList()
            val authority = uri.authority ?: return emptyList()
            "$scheme://$authority"
        } catch (e: Exception) {
            return emptyList()
        }
        return listOf(
            "$root/feed",
            "$root/rss",
            "$root/rss.xml",
            "$root/feed.xml",
            "$root/atom.xml",
            "$root/index.xml",
            "$root/feed/",
            "$root/blog/feed",
            "$root/feeds/posts/default",   // Blogger
            "$root/?feed=rss2"             // WordPress without pretty permalinks
        )
    }

    /** Cheap sniff: does this text begin with a feed root element? */
    fun looksLikeFeedText(text: String): Boolean {
        var s = text.take(2000).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        // Step over the prolog, a doctype and any comments before the root element.
        var guard = 0
        while (guard++ < 8) {
            s = s.trimStart()
            when {
                s.startsWith("<?") -> s = s.substringAfter("?>", "")
                s.startsWith("<!--") -> s = s.substringAfter("-->", "")
                s.startsWith("<!") -> s = s.substringAfter(">", "")
                else -> break
            }
        }
        val head = s.trimStart().lowercase()
        return head.startsWith("<rss") || head.startsWith("<feed") ||
            head.startsWith("<rdf:rdf") || head.startsWith("<rdf") ||
            head.startsWith("<channel")
    }

    private const val MAX_SCAN = 200_000
}
