package com.khabar.reader.feed

/** One parsed feed document. URLs are already absolutised against the feed's own URL. */
data class ParsedFeed(
    val title: String?,
    val siteUrl: String?,
    val description: String?,
    val iconUrl: String?,
    val items: List<ParsedItem>
)

data class ParsedItem(
    /** <guid>/<id> when the feed gives one. A stability key, not necessarily a URL. */
    val guid: String?,
    val title: String?,
    val link: String?,
    val author: String?,
    /** <description>/<summary>: may be HTML. */
    val summaryHtml: String?,
    /** <content:encoded>/<content>: the full body, when the feed ships one. */
    val contentHtml: String?,
    val imageUrl: String?,
    /** Epoch millis, or null when the feed has no parseable date. */
    val publishedAt: Long?
)

class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
