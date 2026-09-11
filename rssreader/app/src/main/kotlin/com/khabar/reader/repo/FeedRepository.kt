package com.khabar.reader.repo

import com.khabar.reader.data.ArticleDao
import com.khabar.reader.data.ArticleEntity
import com.khabar.reader.data.ArticleWithFeed
import com.khabar.reader.data.FeedDao
import com.khabar.reader.data.FeedEntity
import com.khabar.reader.feed.FeedDiscovery
import com.khabar.reader.feed.FeedParseException
import com.khabar.reader.feed.FeedParser
import com.khabar.reader.feed.HtmlText
import com.khabar.reader.feed.ParsedFeed
import com.khabar.reader.feed.ParsedItem
import com.khabar.reader.feed.Urls
import com.khabar.reader.feed.XmlSanitizer
import com.khabar.reader.net.FeedFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface AddFeedResult {
    data class Added(val feed: FeedEntity, val newArticles: Int) : AddFeedResult
    /** The URL was a web page advertising more than one feed — the user picks. */
    data class Choices(val candidates: List<FeedCandidate>) : AddFeedResult
    data class Duplicate(val feed: FeedEntity) : AddFeedResult
    data class Failed(val message: String) : AddFeedResult
}

data class FeedCandidate(val url: String, val title: String)
data class RefreshSummary(val feedsOk: Int, val feedsFailed: Int, val newArticles: Int)
data class FeedOutcome(val feedId: Long, val ok: Boolean, val newArticles: Int, val error: String?)
data class ImportSummary(val added: Int, val skipped: Int, val failed: Int)

/**
 * Everything the app knows about feeds and articles.
 *
 * Offline-first: a failed refresh never removes what is already stored, and the only network
 * calls are plain GETs to the feed URLs the user chose. There is no server of ours in the loop.
 */
class FeedRepository(
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val fetcher: FeedFetcher = FeedFetcher()
) {

    val feeds: Flow<List<FeedEntity>> = feedDao.observeAll()

    fun timeline(
        feedId: Long?,
        unreadOnly: Boolean,
        savedOnly: Boolean,
        query: String
    ): Flow<List<ArticleWithFeed>> = articleDao.observeTimeline(
        feedId = feedId,
        unreadOnly = if (unreadOnly) 1 else 0,
        savedOnly = if (savedOnly) 1 else 0,
        query = query.trim(),
        limit = TIMELINE_LIMIT
    )

    fun unreadCount(): Flow<Int> = articleDao.observeUnreadCount()

    fun savedCount(): Flow<Int> = articleDao.observeSavedCount()

    fun unreadByFeed(): Flow<Map<Long, Int>> =
        articleDao.observeUnreadByFeed().map { rows -> rows.associate { it.feedId to it.unread } }

    // ---- subscribing -------------------------------------------------------

    /**
     * Accepts whatever the user pasted: a feed URL, a site URL, or a bare domain. When the URL
     * turns out to be an ordinary web page we look for the feeds it declares, and only ask the
     * user to choose when there is genuinely more than one.
     */
    suspend fun addFeed(input: String): AddFeedResult {
        val url = Urls.normalizeInput(input)
            ?: return AddFeedResult.Failed("Ye URL samajh nahi aaya.")
        duplicateOf(url)?.let { return AddFeedResult.Duplicate(it) }

        return when (val fetched = fetcher.fetch(url)) {
            is FeedFetcher.Result.Failed -> AddFeedResult.Failed(fetched.message)
            is FeedFetcher.Result.NotModified -> AddFeedResult.Failed("Server ne kuch nahi bheja.")
            is FeedFetcher.Result.Ok -> {
                val parsed = tryParse(fetched)
                if (parsed != null) {
                    duplicateOf(fetched.finalUrl)?.let { return AddFeedResult.Duplicate(it) }
                    insertParsed(fetched.finalUrl, parsed, fetched, null)
                } else {
                    discoverFrom(fetched)
                }
            }
        }
    }

    /** A URL we already believe is a feed: OPML import, a suggestion, a picked candidate. */
    suspend fun addFeedUrl(url: String, fallbackTitle: String? = null): AddFeedResult {
        val normalized = Urls.normalizeInput(url)
            ?: return AddFeedResult.Failed("Ye URL samajh nahi aaya.")
        duplicateOf(normalized)?.let { return AddFeedResult.Duplicate(it) }

        return when (val fetched = fetcher.fetch(normalized)) {
            is FeedFetcher.Result.Failed -> AddFeedResult.Failed(fetched.message)
            is FeedFetcher.Result.NotModified -> AddFeedResult.Failed("Server ne kuch nahi bheja.")
            is FeedFetcher.Result.Ok -> {
                val parsed = tryParse(fetched)
                    ?: return AddFeedResult.Failed("Ye feed nahi hai.")
                duplicateOf(fetched.finalUrl)?.let { return AddFeedResult.Duplicate(it) }
                insertParsed(fetched.finalUrl, parsed, fetched, fallbackTitle)
            }
        }
    }

    suspend fun deleteFeed(id: Long) = feedDao.delete(id)

    suspend fun renameFeed(id: Long, title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) feedDao.rename(id, clean)
    }

    // ---- refreshing --------------------------------------------------------

    suspend fun refreshAll(keepPerFeed: Int): RefreshSummary = coroutineScope {
        val all = feedDao.all()
        // A handful at a time: enough to make 40 feeds feel quick, few enough that a phone on
        // a weak connection is not fighting itself.
        val gate = Semaphore(PARALLEL_REFRESH)
        val outcomes = all.map { feed ->
            async { gate.withPermit { refresh(feed, keepPerFeed) } }
        }.awaitAll()
        RefreshSummary(
            feedsOk = outcomes.count { it.ok },
            feedsFailed = outcomes.count { !it.ok },
            newArticles = outcomes.sumOf { it.newArticles }
        )
    }

    suspend fun refreshFeed(feedId: Long, keepPerFeed: Int): FeedOutcome {
        val feed = feedDao.byId(feedId)
            ?: return FeedOutcome(feedId, false, 0, "Feed nahi mila.")
        return refresh(feed, keepPerFeed)
    }

    private suspend fun refresh(feed: FeedEntity, keepPerFeed: Int): FeedOutcome {
        val now = System.currentTimeMillis()
        return when (val fetched = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
            is FeedFetcher.Result.NotModified -> {
                feedDao.update(feed.copy(lastFetchedAt = now, lastError = null))
                FeedOutcome(feed.id, true, 0, null)
            }

            is FeedFetcher.Result.Failed -> {
                // Keep the articles: being offline must not empty the app.
                feedDao.update(feed.copy(lastFetchedAt = now, lastError = fetched.message))
                FeedOutcome(feed.id, false, 0, fetched.message)
            }

            is FeedFetcher.Result.Ok -> {
                val parsed = tryParse(fetched)
                if (parsed == null) {
                    val message = "Feed ka format samajh nahi aaya."
                    feedDao.update(feed.copy(lastFetchedAt = now, lastError = message))
                    return FeedOutcome(feed.id, false, 0, message)
                }
                val added = storeItems(feed.id, fetched.finalUrl, parsed.items, now)
                articleDao.prune(feed.id, keepPerFeed)
                feedDao.update(
                    feed.copy(
                        // title is deliberately not overwritten — the user may have renamed it.
                        siteUrl = parsed.siteUrl ?: feed.siteUrl,
                        iconUrl = parsed.iconUrl ?: feed.iconUrl,
                        etag = fetched.etag,
                        lastModified = fetched.lastModified,
                        lastFetchedAt = now,
                        lastError = null
                    )
                )
                FeedOutcome(feed.id, true, added, null)
            }
        }
    }

    // ---- reading state -----------------------------------------------------

    suspend fun setRead(articleId: Long, read: Boolean) = articleDao.setRead(articleId, read)

    suspend fun markAllRead(feedId: Long?) = articleDao.markAllRead(feedId)

    suspend fun setBookmarked(articleId: Long, value: Boolean) =
        articleDao.setBookmarked(articleId, value)

    suspend fun articleById(id: Long): ArticleWithFeed? = articleDao.byId(id)

    suspend fun feedCount(): Int = feedDao.count()

    suspend fun articleCount(): Int = articleDao.count()

    suspend fun clearUnsaved() = articleDao.deleteAllUnsaved()

    // ---- OPML --------------------------------------------------------------

    suspend fun importOpml(bytes: ByteArray): ImportSummary {
        val entries = try {
            Opml.parse(bytes)
        } catch (e: Exception) {
            return ImportSummary(0, 0, 0)
        }
        var added = 0
        var skipped = 0
        var failed = 0
        for (entry in entries) {
            when (addFeedUrl(entry.xmlUrl, entry.title)) {
                is AddFeedResult.Added -> added++
                is AddFeedResult.Duplicate -> skipped++
                else -> failed++
            }
        }
        return ImportSummary(added, skipped, failed)
    }

    suspend fun exportOpml(): String = Opml.build(
        feedDao.all().map { Opml.Entry(it.title, it.url, it.siteUrl) }
    )

    // ---- internals ---------------------------------------------------------

    private fun tryParse(fetched: FeedFetcher.Result.Ok): ParsedFeed? = try {
        FeedParser.parse(fetched.bytes, fetched.charset, fetched.finalUrl)
    } catch (e: FeedParseException) {
        null
    }

    /**
     * The URL was not a feed. Look at the page for declared feeds, then at the handful of paths
     * every blog engine uses, and verify each candidate by actually parsing it.
     */
    private suspend fun discoverFrom(fetched: FeedFetcher.Result.Ok): AddFeedResult {
        val html = XmlSanitizer.decode(fetched.bytes, fetched.charset)
        val declared = FeedDiscovery.fromHtml(html, fetched.finalUrl)
        val guesses = if (declared.isEmpty()) FeedDiscovery.commonPaths(fetched.finalUrl) else emptyList()
        val candidates = (declared + guesses).distinct().take(MAX_CANDIDATES)
        if (candidates.isEmpty()) {
            return AddFeedResult.Failed("Is page par koi feed nahi mila.")
        }

        val verified = mutableListOf<FeedCandidate>()
        for (candidate in candidates) {
            val response = fetcher.fetch(candidate)
            if (response !is FeedFetcher.Result.Ok) continue
            val parsed = tryParse(response) ?: continue
            val title = parsed.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: Urls.host(response.finalUrl)
                ?: response.finalUrl
            verified += FeedCandidate(response.finalUrl, title)
            // One good feed found by guessing is the answer; no reason to probe the rest.
            if (declared.isEmpty()) break
        }

        return when (verified.size) {
            0 -> AddFeedResult.Failed("Is page par koi chalu feed nahi mila.")
            1 -> addFeedUrl(verified[0].url, verified[0].title)
            else -> AddFeedResult.Choices(verified)
        }
    }

    private suspend fun insertParsed(
        feedUrl: String,
        parsed: ParsedFeed,
        fetched: FeedFetcher.Result.Ok,
        fallbackTitle: String?
    ): AddFeedResult {
        val now = System.currentTimeMillis()
        val title = parsed.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: Urls.host(feedUrl)
            ?: feedUrl
        val entity = FeedEntity(
            url = feedUrl,
            title = title,
            siteUrl = parsed.siteUrl,
            iconUrl = parsed.iconUrl,
            etag = fetched.etag,
            lastModified = fetched.lastModified,
            lastFetchedAt = now,
            lastError = null,
            sortIndex = feedDao.count()
        )
        val id = try {
            feedDao.insert(entity)
        } catch (e: Exception) {
            // Lost a race with another add, or the unique index caught a URL we normalised
            // differently. Either way the user already has it.
            val existing = feedDao.byUrl(feedUrl)
            return if (existing != null) AddFeedResult.Duplicate(existing)
            else AddFeedResult.Failed("Feed save nahi ho paya.")
        }
        val added = storeItems(id, feedUrl, parsed.items, now)
        return AddFeedResult.Added(entity.copy(id = id), added)
    }

    private suspend fun storeItems(
        feedId: Long,
        feedUrl: String,
        items: List<ParsedItem>,
        now: Long
    ): Int {
        if (items.isEmpty()) return 0
        // Feeds do repeat a guid inside one document; the unique index would reject the whole
        // batch, so collapse duplicates before they reach SQLite.
        val rows = items.map { toEntity(feedId, feedUrl, it, now) }.distinctBy { it.guid }
        val ids = articleDao.insertAll(rows)
        return ids.count { it != -1L }
    }

    private fun toEntity(
        feedId: Long,
        feedUrl: String,
        item: ParsedItem,
        now: Long
    ): ArticleEntity {
        val link = Urls.resolve(feedUrl, item.link)
        val body = item.contentHtml?.takeIf { it.isNotBlank() } ?: item.summaryHtml
        val title = item.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: HtmlText.snippet(body, 80).takeIf { it.isNotBlank() }
            ?: "(bina title)"
        return ArticleEntity(
            feedId = feedId,
            guid = stableGuid(item, link, title),
            title = title,
            link = link,
            author = item.author?.trim()?.takeIf { it.isNotEmpty() },
            summary = HtmlText.snippet(item.summaryHtml ?: item.contentHtml, 300),
            contentHtml = body,
            imageUrl = item.imageUrl ?: HtmlText.firstImageUrl(body, link ?: feedUrl),
            // No date is common in hand-written feeds. Sorting by fetch time keeps such items
            // at the top once, instead of burying them in 1970.
            publishedAt = item.publishedAt ?: now,
            fetchedAt = now
        )
    }

    /**
     * The dedup key. It has to stay identical across refreshes or every poll re-adds the same
     * article as unread, so it never involves the fetch time.
     */
    private fun stableGuid(item: ParsedItem, link: String?, title: String): String {
        item.guid?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        link?.takeIf { it.isNotEmpty() }?.let { return it }
        return "khabar:" + fnv1a64("$title|${item.publishedAt ?: 0L}")
    }

    private fun fnv1a64(input: String): String {
        var hash = -0x340d631b7bdddcdbL          // FNV offset basis
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L               // FNV prime
        }
        return java.lang.Long.toHexString(hash)
    }

    private suspend fun duplicateOf(url: String): FeedEntity? {
        feedDao.byUrl(url)?.let { return it }
        val canonical = Urls.canonical(url) ?: return null
        return feedDao.all().firstOrNull { Urls.canonical(it.url) == canonical }
    }

    private companion object {
        const val TIMELINE_LIMIT = 500
        const val PARALLEL_REFRESH = 4
        const val MAX_CANDIDATES = 6
    }
}
