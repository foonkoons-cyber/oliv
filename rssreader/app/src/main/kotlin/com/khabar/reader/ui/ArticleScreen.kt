package com.khabar.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.khabar.reader.data.ArticleWithFeed
import com.khabar.reader.feed.HtmlBlocks
import com.khabar.reader.ui.components.EmptyState
import com.khabar.reader.util.TimeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    item: ArticleWithFeed,
    textScale: Float,
    showImages: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: (Boolean) -> Unit,
    onOpenLink: (String) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val article = item.article
    val link = article.link

    // Parsing a long body is not free, and recomposition happens on every scroll frame.
    val blocks = remember(article.id, article.contentHtml, article.summary) {
        HtmlBlocks.parse(article.contentHtml?.takeIf { it.isNotBlank() } ?: article.summary, link)
    }
    // A feed that ships only a teaser should say so rather than look like a broken article.
    val summaryOnly = !article.fullContent

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wapas")
                    }
                },
                actions = {
                    IconButton(onClick = { onToggleBookmark(!article.bookmarked) }) {
                        Icon(
                            imageVector = if (article.bookmarked) {
                                Icons.Filled.Bookmark
                            } else {
                                Icons.Filled.BookmarkBorder
                            },
                            contentDescription = if (article.bookmarked) {
                                "Saved se hatao"
                            } else {
                                "Save karo"
                            },
                            tint = if (article.bookmarked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (!link.isNullOrBlank()) {
                        IconButton(onClick = { onOpenLink(link) }) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = "Browser mein kholo")
                        }
                        IconButton(onClick = onShare) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = buildString {
                    append(item.feedTitle)
                    article.author?.let { append("  ·  ").append(it) }
                    append("  ·  ").append(TimeText.absolute(article.publishedAt))
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = article.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(18.dp))

            val lead = article.imageUrl
            if (showImages && !lead.isNullOrBlank()) {
                AsyncImage(
                    model = lead,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth
                )
                Spacer(Modifier.height(18.dp))
            }

            if (blocks.isEmpty()) {
                EmptyState(
                    title = "Is item mein text nahi hai",
                    body = "Ye feed sirf headline bhejta hai. Poora article website par hai.",
                    actionLabel = if (link.isNullOrBlank()) null else "Original kholo",
                    onAction = if (link.isNullOrBlank()) null else {
                        { onOpenLink(link) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                HtmlBody(
                    blocks = blocks,
                    textScale = textScale,
                    showImages = showImages,
                    onLinkClick = onOpenLink
                )
            }

            if (summaryOnly && blocks.isNotEmpty() && !link.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Poora article website par hai",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Ye feed sirf ek chhota hissa bhejta hai.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (!link.isNullOrBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onOpenLink(link) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Original kholo")
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
