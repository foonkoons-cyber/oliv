package com.khabar.reader.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "feeds", indices = [Index(value = ["url"], unique = true)])
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Canonical feed XML URL. Unique — this is what "already subscribed" means. */
    val url: String,
    val title: String,
    val siteUrl: String?,
    val iconUrl: String?,
    /** Conditional-GET validators. With them a refresh is usually one 304 and no body. */
    val etag: String?,
    val lastModified: String?,
    val lastFetchedAt: Long?,
    /** Last failure, shown on the Feeds screen. Null once a fetch succeeds again. */
    val lastError: String?,
    val sortIndex: Int = 0
)

@Entity(
    tableName = "articles",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["feedId", "guid"], unique = true),
        Index(value = ["publishedAt"]),
        Index(value = ["bookmarked"])
    ]
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    /** guid ?: link ?: hash(title + date). Unique per feed; the whole dedup story rests on it. */
    val guid: String,
    val title: String,
    val link: String?,
    val author: String?,
    /** Plain-text snippet for the list row. */
    val summary: String,
    /** Best available body HTML — the full article when the feed ships one, else the summary. */
    val contentHtml: String?,
    /** True when [contentHtml] is the real article rather than a teaser the publisher truncated. */
    val fullContent: Boolean = false,
    val imageUrl: String?,
    val publishedAt: Long,
    val fetchedAt: Long,
    val read: Boolean = false,
    val bookmarked: Boolean = false
)

/** Timeline row: the article plus the two feed fields the list actually shows. */
data class ArticleWithFeed(
    @Embedded val article: ArticleEntity,
    @ColumnInfo(name = "feedTitle") val feedTitle: String,
    @ColumnInfo(name = "feedIconUrl") val feedIconUrl: String?
)

data class FeedUnread(
    @ColumnInfo(name = "feedId") val feedId: Long,
    @ColumnInfo(name = "unread") val unread: Int
)
