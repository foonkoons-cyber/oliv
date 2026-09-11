package com.khabar.reader.repo

data class SuggestedFeed(val title: String, val url: String, val category: String)

/**
 * A starter list so a fresh install is not an empty box.
 *
 * These are third-party URLs the app does not control: publishers move and retire feeds, and a
 * dead entry here is an ordinary per-feed error, not a crash. Nothing about the app depends on
 * this list — it is a convenience over typing a URL.
 */
object Suggestions {

    val all: List<SuggestedFeed> = listOf(
        SuggestedFeed("BBC News हिंदी", "https://feeds.bbci.co.uk/hindi/rss.xml", "India"),
        SuggestedFeed("NDTV — Top Stories", "https://feeds.feedburner.com/ndtvnews-top-stories", "India"),
        SuggestedFeed("The Hindu — National", "https://www.thehindu.com/news/national/feeder/default.rss", "India"),
        SuggestedFeed("The Indian Express", "https://indianexpress.com/feed/", "India"),
        SuggestedFeed("Mint", "https://www.livemint.com/rss/news", "India"),
        SuggestedFeed("Times of India — Top", "https://timesofindia.indiatimes.com/rssfeedstopstories.cms", "India"),
        SuggestedFeed("BBC News — World", "https://feeds.bbci.co.uk/news/world/rss.xml", "World"),
        SuggestedFeed("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "World"),
        SuggestedFeed("Hacker News", "https://hnrss.org/frontpage", "Tech"),
        SuggestedFeed("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Tech"),
        SuggestedFeed("The Verge", "https://www.theverge.com/rss/index.xml", "Tech"),
        SuggestedFeed("Android Developers Blog", "https://android-developers.googleblog.com/feeds/posts/default", "Tech"),
        SuggestedFeed("NASA", "https://www.nasa.gov/feed/", "Science"),
        SuggestedFeed("ESPNcricinfo", "https://www.espncricinfo.com/rss/content/story/feeds/0.xml", "Sports"),
        SuggestedFeed("xkcd", "https://xkcd.com/rss.xml", "Fun")
    )

    val categories: List<String> = all.map { it.category }.distinct()
}
