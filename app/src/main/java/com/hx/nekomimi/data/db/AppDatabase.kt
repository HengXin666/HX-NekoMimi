package com.hx.nekomimi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.hx.nekomimi.data.db.dao.BookmarkDao
import com.hx.nekomimi.data.db.dao.PlaybackMemoryDao
import com.hx.nekomimi.data.db.entity.Bookmark
import com.hx.nekomimi.data.db.entity.PlaybackMemory

@Database(
    entities = [PlaybackMemory::class, Bookmark::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playbackMemoryDao(): PlaybackMemoryDao
    abstract fun bookmarkDao(): BookmarkDao
}
