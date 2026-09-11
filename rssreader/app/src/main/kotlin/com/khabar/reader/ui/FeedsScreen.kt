package com.khabar.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khabar.reader.data.FeedEntity
import com.khabar.reader.feed.Urls
import com.khabar.reader.ui.components.EmptyState
import com.khabar.reader.ui.components.ErrorBanner
import com.khabar.reader.util.TimeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(
    feeds: List<FeedEntity>,
    unreadByFeed: Map<Long, Int>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenFeed: (Long) -> Unit,
    onRefreshFeed: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FeedEntity?>(null) }
    var deleting by remember { mutableStateOf<FeedEntity?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Feeds") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wapas")
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = "Feed add karo")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Aur options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Import OPML") },
                            onClick = {
                                menuOpen = false
                                onImportOpml()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export OPML") },
                            onClick = {
                                menuOpen = false
                                onExportOpml()
                            }
                        )
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
        if (feeds.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.RssFeed,
                title = "Abhi tak koi feed nahi",
                body = "Kisi site ka URL daalo — feed apne aap dhoondh lenge.",
                actionLabel = "Feed add karo",
                onAction = onAdd,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
            ) {
                items(items = feeds, key = { it.id }) { feed ->
                    FeedRow(
                        feed = feed,
                        unread = unreadByFeed[feed.id] ?: 0,
                        onOpen = { onOpenFeed(feed.id) },
                        onRefresh = { onRefreshFeed(feed.id) },
                        onRename = { renaming = feed },
                        onDelete = { deleting = feed }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    renaming?.let { feed ->
        RenameDialog(
            feed = feed,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                onRename(feed.id, title)
                renaming = null
            }
        )
    }

    deleting?.let { feed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Feed hataana hai?") },
            text = {
                Text(
                    "\"${feed.title}\" aur iske saare articles — saved bhi — hat jayenge."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(feed.id)
                    deleting = null
                }) { Text("Hatao") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Rehne do") }
            }
        )
    }
}

@Composable
private fun FeedRow(
    feed: FeedEntity,
    unread: Int,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (unread > 0) "${feed.title}  ($unread)" else feed.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(Urls.host(feed.url) ?: feed.url)
                        feed.lastFetchedAt?.let {
                            append("  ·  ")
                            append(TimeText.relative(it, System.currentTimeMillis()))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Is feed ko refresh karo")
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Naam badlo")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Feed hatao")
            }
        }

        // The whole reason this screen exists: "why is this feed not updating?"
        val error = feed.lastError
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            ErrorBanner(text = error, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

@Composable
private fun RenameDialog(
    feed: FeedEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(feed.id) { mutableStateOf(feed.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Naam badlo") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = feed.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                enabled = draft.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(4.dp))
            }
        }
    )
}
