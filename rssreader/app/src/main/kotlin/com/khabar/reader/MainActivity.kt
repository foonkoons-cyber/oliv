package com.khabar.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khabar.reader.repo.Suggestions
import com.khabar.reader.ui.AddFeedScreen
import com.khabar.reader.ui.AppViewModel
import com.khabar.reader.ui.ArticleScreen
import com.khabar.reader.ui.FeedsScreen
import com.khabar.reader.ui.Screen
import com.khabar.reader.ui.SettingsScreen
import com.khabar.reader.ui.TimelineScreen
import com.khabar.reader.ui.UiState
import com.khabar.reader.ui.theme.KhabarTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A URL shared or opened into the app, handed to the ViewModel once composition is up. */
    private val incomingUrl = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUrl.value = urlFrom(intent)
        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsState()
            KhabarTheme(state.prefs.themeMode) {
                AppRoot(vm = vm, state = state, incoming = incomingUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl.value = urlFrom(intent)
    }

    /** Pulls a subscribable URL out of a share or a feed:// link. */
    private fun urlFrom(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.split(Regex("\\s+"))
                ?.firstOrNull { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("feed:") }

            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }
}

@Composable
private fun AppRoot(
    vm: AppViewModel,
    state: UiState,
    incoming: MutableStateFlow<String?>
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        incoming.collect { url ->
            if (!url.isNullOrBlank()) {
                vm.handleSharedUrl(url)
                incoming.value = null
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importOpmlFrom)
    }
    // The picker needs a concrete type; .opml is XML and every file manager offers it under this.
    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        uri?.let(vm::exportOpmlTo)
    }

    val openLink: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            vm.showMessage("Ise kholne ke liye koi app nahi mila.")
        }
    }

    // Back is only intercepted when there is somewhere to go; otherwise the system closes the
    // app, which is what a reader should do from its own timeline.
    val canGoBack = state.screen != Screen.Timeline ||
        state.searching ||
        state.query.isNotEmpty() ||
        state.feedFilterId != null
    BackHandler(enabled = canGoBack) { vm.back() }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val screen = state.screen) {
            Screen.Timeline -> TimelineScreen(
                state = state,
                onTab = vm::setTab,
                onFeedFilter = vm::setFeedFilter,
                onQueryChange = vm::setQuery,
                onSearchToggle = vm::setSearching,
                onOpen = vm::openArticle,
                onToggleBookmark = { item ->
                    vm.toggleBookmark(item.article.id, !item.article.bookmarked)
                },
                onToggleRead = { item -> vm.toggleRead(item.article.id, !item.article.read) },
                onRefresh = vm::refresh,
                onMarkAllRead = vm::markAllRead,
                onAddFeed = { vm.navigate(Screen.AddFeed) },
                onOpenFeeds = { vm.navigate(Screen.Feeds) },
                onOpenSettings = { vm.navigate(Screen.Settings) },
                modifier = Modifier.fillMaxSize()
            )

            is Screen.Article -> {
                val item = state.openArticle
                if (item == null || item.article.id != screen.id) {
                    // Only reachable after process death restored the stack without the row.
                    LaunchedEffect(screen.id) { vm.back() }
                } else {
                    ArticleScreen(
                        item = item,
                        textScale = state.prefs.textScale,
                        showImages = state.prefs.showImages,
                        onBack = { vm.back() },
                        onToggleBookmark = { value -> vm.toggleBookmark(item.article.id, value) },
                        onOpenLink = openLink,
                        onShare = {
                            val link = item.article.link
                            if (link.isNullOrBlank()) {
                                vm.showMessage("Is item ka koi link nahi hai.")
                            } else {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, item.article.title)
                                    putExtra(Intent.EXTRA_TEXT, "${item.article.title}\n$link")
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Screen.Feeds -> FeedsScreen(
                feeds = state.feeds,
                unreadByFeed = state.unreadByFeed,
                onBack = { vm.back() },
                onAdd = { vm.navigate(Screen.AddFeed) },
                onOpenFeed = { feedId ->
                    vm.setFeedFilter(feedId)
                    vm.back()
                },
                onRefreshFeed = vm::refreshFeed,
                onRename = vm::renameFeed,
                onDelete = vm::deleteFeed,
                onImportOpml = { importOpml.launch(arrayOf("*/*")) },
                onExportOpml = { exportOpml.launch("khabar-feeds.opml") },
                modifier = Modifier.fillMaxSize()
            )

            Screen.AddFeed -> AddFeedScreen(
                state = state.addFeed,
                suggestions = Suggestions.all,
                onInput = vm::setAddFeedInput,
                onSubmit = vm::submitAddFeed,
                onPickCandidate = vm::pickCandidate,
                onAddSuggested = vm::addSuggested,
                onBack = { vm.back() },
                modifier = Modifier.fillMaxSize()
            )

            Screen.Settings -> SettingsScreen(
                prefs = state.prefs,
                feedCount = state.feeds.size,
                articleCount = state.articles.size,
                onThemeMode = vm::setThemeMode,
                onTextScale = vm::setTextScale,
                onRefreshIntervalHours = vm::setRefreshIntervalHours,
                onWifiOnly = vm::setWifiOnly,
                onKeepPerFeed = vm::setKeepPerFeed,
                onMarkReadOnOpen = vm::setMarkReadOnOpen,
                onShowImages = vm::setShowImages,
                onClearUnsaved = vm::clearUnsaved,
                onBack = { vm.back() },
                modifier = Modifier.fillMaxSize()
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}
