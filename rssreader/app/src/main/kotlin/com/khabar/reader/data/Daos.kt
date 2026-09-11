package com.khabar.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {

    @Query("SELECT * FROM feeds ORDER BY sortIndex ASC, title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds ORDER BY sortIndex ASC, title COLLATE NOCASE ASC")
    suspend fun all(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun byId(id: Long): FeedEntity?

    @Query("SELECT * FROM feeds WHERE url = :url")
    suspend fun byUrl(url: String): FeedEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(feed: FeedEntity): Long

    @Update
    suspend fun update(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE feeds SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("SELECT COUNT(*) FROM feeds")
    suspend fun count(): Int
}

@Dao
interface ArticleDao {

    /**
     * One query drives the whole timeline. The flags travel as Int rather than Boolean so the
     * SQL reads the same as it would in a shell, and feedId = null means "all feeds".
     */
    @Query(
        """
        SELECT a.*, f.title AS feedTitle, f.iconUrl AS feedIconUrl
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE (:feedId IS NULL OR a.feedId = :feedId)
          AND (:unreadOnly = 0 OR a.read = 0)
          AND (:savedOnly = 0 OR a.bookmarked = 1)
          AND (:query = '' OR a.title LIKE '%' || :query || '%' OR a.summary LIKE '%' || :query || '%')
        ORDER BY a.publishedAt DESC, a.id DESC
        LIMIT :limit
        """
    )
    fun observeTimeline(
        feedId: Long?,
        unreadOnly: Int,
        savedOnly: Int,
        query: String,
        limit: Int
    ): Flow<List<ArticleWithFeed>>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle, f.iconUrl AS feedIconUrl
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.id = :id
        """
    )
    suspend fun byId(id: Long): ArticleWithFeed?

    @Query("SELECT COUNT(*) FROM articles WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM articles WHERE bookmarked = 1")
    fun observeSavedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun count(): Int

    @Query("SELECT feedId AS feedId, COUNT(*) AS unread FROM articles WHERE read = 0 GROUP BY feedId")
    fun observeUnreadByFeed(): Flow<List<FeedUnread>>

    /** IGNORE, not REPLACE: a re-seen item must not come back unread or lose its bookmark. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ArticleEntity>): List<Long>

    @Query("UPDATE articles SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE articles SET read = 1 WHERE (:feedId IS NULL OR feedId = :feedId)")
    suspend fun markAllRead(feedId: Long?)

    @Query("UPDATE articles SET bookmarked = :value WHERE id = :id")
    suspend fun setBookmarked(id: Long, value: Boolean)

    /** Bookmarks survive forever; everything else is trimmed to the newest :keep per feed. */
    @Query(
        """
        DELETE FROM articles WHERE feedId = :feedId AND bookmarked = 0 AND id NOT IN (
            SELECT id FROM articles WHERE feedId = :feedId ORDER BY publishedAt DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun prune(feedId: Long, keep: Int)

    @Query("DELETE FROM articles WHERE bookmarked = 0")
    suspend fun deleteAllUnsaved()
}
