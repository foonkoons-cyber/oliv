package com.khabar.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FeedEntity::class, ArticleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KhabarDatabase : RoomDatabase() {

    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao

    companion object {
        @Volatile private var instance: KhabarDatabase? = null

        fun open(context: Context): KhabarDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KhabarDatabase::class.java,
                    "khabar.db"
                )
                    // Everything here is a cache of public feeds plus two user-owned bits
                    // (read, bookmarked). Losing it on a schema change is survivable; shipping
                    // a migration bug that bricks the app is not.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
