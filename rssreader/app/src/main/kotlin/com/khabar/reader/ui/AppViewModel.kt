package com.khabar.reader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khabar.reader.KhabarApp
import com.khabar.reader.data.ArticleEntity
import com.khabar.reader.data.ArticleWithFeed
import com.khabar.reader.data.FeedEntity
import com.khabar.reader.data.Prefs
import com.khabar.reader.data.ThemeMode
import com.khabar.reader.repo.AddFeedResult
import com.khabar.reader.repo.FeedCandidate
import com.khabar.reader.repo.RefreshSummary
import com.khabar.reader.repo.SuggestedFeed
import com.khabar.reader.work.RefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Timeline : Screen
    data object Feeds : Screen
    data object AddFeed : Screen
    data object Settings : Screen
    data class Article(val id: Long) : Screen
}

enum class ArticleTab { All, Unread, Saved }

data class AddFeedState(
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val candidates: List<FeedCandidate> = emptyList()
)

data class UiState(
    val screen: Screen = Screen.Timeline,
    val loading: Boolean = true,
    val feeds: List<FeedEntity> = emptyList(),
    val articles: List<ArticleWithFeed> = emptyList(),
    val unreadByFeed: Map<Long, Int> = emptyMap(),
    val tab: ArticleTab = ArticleTab.All,
    val feedFilterId: Long? = null,
    val query: String = "",
    val searching: Boolean = false,
    val refreshing: Boolean = false,
    val unreadCount: Int = 0,
    val savedCount: Int = 0,
    val openArticle: ArticleWithFeed? = null,
    val addFeed: AddFeedState = AddFeedState(),
    val prefs: Prefs = Prefs(),
    val message: String? = null
)

/**
 * One ViewModel for the whole app. The screen graph is four destinations deep at most, so a
 * back stack of [Screen] beats pulling in a navigation library for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val khabar = app as KhabarApp
    private val repo = khabar.repository
    private val prefsRepo = khabar.prefsRepository

    /** The bits of state only the UI owns; everything else is observed from the database. */
    private data class Local(
        val stack: List<Screen> = listOf(Screen.Timeline),
        val tab: ArticleTab = ArticleTab.All,
        val feedFilterId: Long? = null,
        val query: String = "",
        val searching: Boolean = false,
        val refreshing: Boolean = false,
        val openArticle: ArticleWithFeed? = null,
        val addFeed: AddFeedState = AddFeedState(),
        val message: String? = null
    )

    private val local = MutableStateFlow(Local())

    private data class Counts(
        val unread: Int,
        val saved: Int,
        val unreadByFeed: Map<Long, Int>
    )

    private val counts = combine(
        repo.unreadCount(),
        repo.savedCount(),
        repo.unreadByFeed()
    ) { unread, saved, byFeed -> Counts(unread, saved, byFeed) }

    private val timeline = local
        .map { Query(it.feedFilterId, it.tab, it.query) }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            repo.timeline(
                feedId = q.feedId,
                unreadOnly = q.tab == ArticleTab.Unread,
                savedOnly = q.tab == ArticleTab.Saved,
                query = q.text
            )
        }

    private data class Query(val feedId: Long?, val tab: ArticleTab, val text: String)

    val state: StateFlow<UiState> = combine(
        local,
        repo.feeds,
        timeline,
        counts,
        prefsRepo.prefs
    ) { l, feeds, articles, c, prefs ->
        UiState(
            screen = l.stack.last(),
            loading = false,
            feeds = feeds,
            articles = articles,
            unreadByFeed = c.unreadByFeed,
            tab = l.tab,
            feedFilterId = l.feedFilterId,
            query = l.query,
            searching = l.searching,
            refreshing = l.refreshing,
            unreadCount = c.unread,
            savedCount = c.saved,
            openArticle = l.openArticle,
            addFeed = l.addFeed,
            prefs = prefs,
            message = l.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    // ---- navigation --------------------------------------------------------

    fun navigate(screen: Screen) {
        local.update { it.copy(stack = it.stack + screen) }
    }

    /** True when the press was consumed; false means "let the system close the app". */
    fun back(): Boolean {
        val current = local.value
        if (current.stack.size <= 1) {
            // On the timeline, back should first undo a filtered view rather than exit.
            if (current.searching || current.query.isNotEmpty()) {
                local.update { it.copy(searching = false, query = "") }
                return true
            }
            if (current.feedFilterId != null) {
                local.update { it.copy(feedFilterId = null) }
                return true
            }
            return false
        }
        local.update {
            it.copy(
                stack = it.stack.dropLast(1),
                openArticle = if (it.stack.last() is Screen.Article) null else it.openArticle,
                addFeed = if (it.stack.last() == Screen.AddFeed) AddFeedState() else it.addFeed
            )
        }
        return true
    }

    fun openArticle(item: ArticleWithFeed) {
        local.update {
            it.copy(openArticle = item, stack = it.stack + Screen.Article(item.article.id))
        }
        viewModelScope.launch {
            if (prefsRepo.current().markReadOnOpen && !item.article.read) {
                repo.setRead(item.article.id, true)
                patchOpen(item.article.id) { it.copy(read = true) }
            }
        }
    }

    // ---- timeline ----------------------------------------------------------

    fun setTab(tab: ArticleTab) = local.update { it.copy(tab = tab) }

    fun setFeedFilter(feedId: Long?) = local.update { it.copy(feedFilterId = feedId) }

    fun setQuery(q: String) = local.update { it.copy(query = q) }

    fun setSearching(on: Boolean) =
        local.update { it.copy(searching = on, query = if (on) it.query else "") }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val prefs = prefsRepo.current()
            val summary = repo.refreshAll(prefs.keepPerFeed)
            local.update { it.copy(refreshing = false, message = describe(summary)) }
        }
    }

    fun refreshFeed(feedId: Long) {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val prefs = prefsRepo.current()
            val outcome = repo.refreshFeed(feedId, prefs.keepPerFeed)
            local.update {
                it.copy(
                    refreshing = false,
                    message = outcome.error ?: when (outcome.newArticles) {
                        0 -> "Kuch naya nahi mila."
                        1 -> "1 nayi khabar."
                        else -> "${outcome.newArticles} nayi khabrein."
                    }
                )
            }
        }
    }

    fun toggleBookmark(articleId: Long, value: Boolean) {
        viewModelScope.launch {
            repo.setBookmarked(articleId, value)
            patchOpen(articleId) { it.copy(bookmarked = value) }
            local.update {
                it.copy(message = if (value) "Save kar liya." else "Save hata diya.")
            }
        }
    }

    fun toggleRead(articleId: Long, value: Boolean) {
        viewModelScope.launch {
            repo.setRead(articleId, value)
            patchOpen(articleId) { it.copy(read = value) }
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            repo.markAllRead(local.value.feedFilterId)
            local.update { it.copy(message = "Sab read mark ho gaya.") }
        }
    }

    // ---- subscriptions -----------------------------------------------------

    fun setAddFeedInput(text: String) =
        local.update { it.copy(addFeed = it.addFeed.copy(input = text, error = null)) }

    fun submitAddFeed() {
        val input = local.value.addFeed.input.trim()
        if (input.isEmpty() || local.value.addFeed.busy) return
        startAdd()
        viewModelScope.launch { finishAdd(repo.addFeed(input)) }
    }

    fun pickCandidate(candidate: FeedCandidate) {
        if (local.value.addFeed.busy) return
        startAdd()
        viewModelScope.launch { finishAdd(repo.addFeedUrl(candidate.url, candidate.title)) }
    }

    fun addSuggested(feed: SuggestedFeed) {
        if (local.value.addFeed.busy) return
        startAdd()
        viewModelScope.launch { finishAdd(repo.addFeedUrl(feed.url, feed.title)) }
    }

    fun deleteFeed(feedId: Long) {
        viewModelScope.launch {
            repo.deleteFeed(feedId)
            local.update {
                it.copy(
                    feedFilterId = if (it.feedFilterId == feedId) null else it.feedFilterId,
                    message = "Feed hata diya."
                )
            }
        }
    }

    fun renameFeed(feedId: Long, title: String) {
        viewModelScope.launch { repo.renameFeed(feedId, title) }
    }

    /** A URL shared into the app from a browser: prefill Add feed and go. */
    fun handleSharedUrl(url: String) {
        local.update {
            it.copy(
                stack = listOf(Screen.Timeline, Screen.AddFeed),
                addFeed = AddFeedState(input = url)
            )
        }
        submitAddFeed()
    }

    // ---- OPML --------------------------------------------------------------

    fun importOpmlFrom(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                local.update { it.copy(message = "File padhi nahi ja saki.") }
                return@launch
            }
            local.update { it.copy(message = "Import chal raha hai…") }
            val summary = repo.importOpml(bytes)
            local.update {
                it.copy(
                    message = if (summary.added == 0 && summary.skipped == 0 && summary.failed == 0) {
                        "Is file mein koi feed nahi mila."
                    } else {
                        "${summary.added} add, ${summary.skipped} pehle se, ${summary.failed} fail."
                    }
                )
            }
        }
    }

    fun exportOpmlTo(uri: Uri) {
        viewModelScope.launch {
            val text = repo.exportOpml()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                }.getOrDefault(false)
            }
            local.update { it.copy(message = if (ok) "OPML export ho gaya." else "Export fail ho gaya.") }
        }
    }

    // ---- settings ----------------------------------------------------------

    fun setThemeMode(v: ThemeMode) {
        viewModelScope.launch { prefsRepo.setThemeMode(v) }
    }

    fun setTextScale(v: Float) {
        viewModelScope.launch { prefsRepo.setTextScale(v) }
    }

    fun setRefreshIntervalHours(v: Int) {
        viewModelScope.launch {
            prefsRepo.setRefreshIntervalHours(v)
            RefreshScheduler.apply(getApplication(), prefsRepo.current())
        }
    }

    fun setWifiOnly(v: Boolean) {
        viewModelScope.launch {
            prefsRepo.setWifiOnly(v)
            RefreshScheduler.apply(getApplication(), prefsRepo.current())
        }
    }

    fun setKeepPerFeed(v: Int) {
        viewModelScope.launch { prefsRepo.setKeepPerFeed(v) }
    }

    fun setMarkReadOnOpen(v: Boolean) {
        viewModelScope.launch { prefsRepo.setMarkReadOnOpen(v) }
    }

    fun setShowImages(v: Boolean) {
        viewModelScope.launch { prefsRepo.setShowImages(v) }
    }

    fun clearUnsaved() {
        viewModelScope.launch {
            repo.clearUnsaved()
            local.update { it.copy(message = "Saved ke alawa sab clear ho gaya.") }
        }
    }

    // ---- snackbar ----------------------------------------------------------

    fun showMessage(text: String) = local.update { it.copy(message = text) }

    fun dismissMessage() = local.update { it.copy(message = null) }

    // ---- internals ---------------------------------------------------------

    private fun startAdd() {
        local.update {
            it.copy(addFeed = it.addFeed.copy(busy = true, error = null, candidates = emptyList()))
        }
    }

    private fun finishAdd(result: AddFeedResult) {
        when (result) {
            is AddFeedResult.Added -> local.update {
                it.copy(
                    addFeed = AddFeedState(),
                    stack = listOf(Screen.Timeline),
                    feedFilterId = null,
                    message = "${result.feed.title} add ho gaya — ${result.newArticles} items."
                )
            }

            is AddFeedResult.Duplicate -> local.update {
                it.copy(
                    addFeed = it.addFeed.copy(
                        busy = false,
                        error = "Ye feed pehle se hai: ${result.feed.title}"
                    )
                )
            }

            is AddFeedResult.Choices -> local.update {
                it.copy(addFeed = it.addFeed.copy(busy = false, candidates = result.candidates))
            }

            is AddFeedResult.Failed -> local.update {
                it.copy(addFeed = it.addFeed.copy(busy = false, error = result.message))
            }
        }
    }

    /** Keeps the open article in step with a change written to the database. */
    private fun patchOpen(
        articleId: Long,
        block: (ArticleEntity) -> ArticleEntity
    ) {
        local.update { state ->
            val open = state.openArticle
            if (open == null || open.article.id != articleId) state
            else state.copy(openArticle = open.copy(article = block(open.article)))
        }
    }

    private fun describe(summary: RefreshSummary): String = when {
        summary.feedsOk == 0 && summary.feedsFailed == 0 -> "Pehle koi feed add karo."
        summary.newArticles == 0 && summary.feedsFailed == 0 -> "Kuch naya nahi mila."
        summary.feedsFailed == 0 -> "${summary.newArticles} nayi khabrein."
        summary.newArticles == 0 -> "${summary.feedsFailed} feed fail hue."
        else -> "${summary.newArticles} nayi khabrein, ${summary.feedsFailed} feed fail."
    }
}
