package com.hx.nekomimi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.hx.nekomimi.data.dao.BookDao
import com.hx.nekomimi.data.dao.ChapterDao
import com.hx.nekomimi.data.dao.PlaybackProgressDao
import com.hx.nekomimi.data.entity.Book
import com.hx.nekomimi.data.entity.Chapter
import com.hx.nekomimi.data.entity.PlaybackProgress

@Database(
    entities = [Book::class, Chapter::class, PlaybackProgress::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun playbackProgressDao(): PlaybackProgressDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nekomimi.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
