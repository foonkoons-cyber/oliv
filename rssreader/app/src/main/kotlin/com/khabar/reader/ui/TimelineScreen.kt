package com.khabar.reader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khabar.reader.ui.components.ArticleRow
import com.khabar.reader.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    state: UiState,
    onTab: (ArticleTab) -> Unit,
    onFeedFilter: (Long?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onOpen: (com.khabar.reader.data.ArticleWithFeed) -> Unit,
    onToggleBookmark: (com.khabar.reader.data.ArticleWithFeed) -> Unit,
    onToggleRead: (com.khabar.reader.data.ArticleWithFeed) -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onAddFeed: () -> Unit,
    onOpenFeeds: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val filteredFeed = state.feeds.firstOrNull { it.id == state.feedFilterId }

    // Jumping back to the top when the filter changes; otherwise the list keeps a scroll
    // position that belonged to a different set of articles.
    LaunchedEffect(state.tab, state.feedFilterId, state.query) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(state.searching) {
        if (state.searching) searchFocus.requestFocus()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (state.searching) {
                        SearchField(
                            query = state.query,
                            onQueryChange = onQueryChange,
                            focusRequester = searchFocus
                        )
                    } else {
                        Text(
                            text = filteredFeed?.title ?: "Khabar",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (state.searching) {
                        IconButton(onClick = { onSearchToggle(false) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Search band karo")
                        }
                    }
                },
                actions = {
                    if (!state.searching) {
                        IconButton(onClick = { onSearchToggle(true) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = onMarkAllRead) {
                            Icon(Icons.Filled.DoneAll, contentDescription = "Sab read mark karo")
                        }
                        IconButton(onClick = onOpenFeeds) {
                            Icon(Icons.Filled.RssFeed, contentDescription = "Feeds")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { inner ->
        Column(modifier = Modifier.padding(inner)) {
            TabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = state.tab == ArticleTab.All,
                    onClick = { onTab(ArticleTab.All) },
                    text = { Text("All") }
                )
                Tab(
                    selected = state.tab == ArticleTab.Unread,
                    onClick = { onTab(ArticleTab.Unread) },
                    text = { Text(if (state.unreadCount > 0) "Unread ${state.unreadCount}" else "Unread") }
                )
                Tab(
                    selected = state.tab == ArticleTab.Saved,
                    onClick = { onTab(ArticleTab.Saved) },
                    text = { Text(if (state.savedCount > 0) "Saved ${state.savedCount}" else "Saved") }
                )
            }

            if (state.feeds.size > 1) {
                FeedFilterRow(
                    feeds = state.feeds,
                    selectedId = state.feedFilterId,
                    unreadByFeed = state.unreadByFeed,
                    onSelect = onFeedFilter
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                // The empty states live inside the list so pull-to-refresh still works when
                // there is nothing to show — which is exactly when you want to pull.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (state.articles.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                TimelineEmptyState(state, onAddFeed, onRefresh)
                            }
                        }
                    } else {
                        items(
                            items = state.articles,
                            key = { it.article.id }
                        ) { item ->
                            ArticleRow(
                                item = item,
                                showImage = state.prefs.showImages,
                                onClick = { onOpen(item) },
                                onToggleBookmark = { onToggleBookmark(item) },
                                onToggleRead = { onToggleRead(item) },
                                modifier = Modifier.animateItem()
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEmptyState(
    state: UiState,
    onAddFeed: () -> Unit,
    onRefresh: () -> Unit
) {
    when {
        state.loading -> CircularProgressIndicator()

        state.feeds.isEmpty() -> EmptyState(
            icon = Icons.Filled.RssFeed,
            title = "Abhi tak koi feed nahi",
            body = "Kisi bhi news site ya blog ka URL daalo — feed apne aap dhoondh lenge. " +
                "Na koi account, na koi server.",
            actionLabel = "Feed add karo",
            onAction = onAddFeed
        )

        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Filled.Search,
            title = "Kuch nahi mila",
            body = "\"${state.query}\" ke liye koi article nahi hai."
        )

        state.tab == ArticleTab.Unread -> EmptyState(
            icon = Icons.Filled.DoneAll,
            title = "Sab padh liya",
            body = "Naya aane par yahin dikhega."
        )

        state.tab == ArticleTab.Saved -> EmptyState(
            icon = Icons.Filled.Inbox,
            title = "Koi saved article nahi",
            body = "Kisi article par bookmark dabao — wo offline padhne ke liye yahan rahega."
        )

        else -> EmptyState(
            icon = Icons.Filled.Inbox,
            title = "Abhi kuch nahi aaya",
            body = "Feeds add hain, lekin articles nahi. Ek baar refresh karke dekho.",
            actionLabel = "Refresh",
            onAction = onRefresh
        )
    }
}

@Composable
private fun FeedFilterRow(
    feeds: List<com.khabar.reader.data.FeedEntity>,
    selectedId: Long?,
    unreadByFeed: Map<Long, Int>,
    onSelect: (Long?) -> Unit
) {
    ChipRow {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Sab") }
        )
        feeds.forEach { feed ->
            Spacer(Modifier.width(8.dp))
            val unread = unreadByFeed[feed.id] ?: 0
            FilterChip(
                selected = selectedId == feed.id,
                // Tapping the selected chip clears the filter — the same gesture both ways.
                onClick = { onSelect(if (selectedId == feed.id) null else feed.id) },
                label = {
                    Text(
                        text = if (unread > 0) "${feed.title} ($unread)" else feed.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

/** A plain horizontally scrolling row; LazyRow would buy nothing at these counts. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("Search") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}
