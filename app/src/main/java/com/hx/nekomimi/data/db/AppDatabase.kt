package com.hx.nekomimi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.hx.nekomimi.data.db.dao.BookDao
import com.hx.nekomimi.data.db.dao.PlaybackMemoryDao
import com.hx.nekomimi.data.db.entity.Book
import com.hx.nekomimi.data.db.entity.PlaybackMemory

@Database(
    entities = [Book::class, PlaybackMemory::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun playbackMemoryDao(): PlaybackMemoryDao
}
