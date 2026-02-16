package com.hx.nekomimi.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.hx.nekomimi.data.db.AppDatabase
import com.hx.nekomimi.data.db.dao.BookDao
import com.hx.nekomimi.data.db.dao.BookmarkDao
import com.hx.nekomimi.data.db.dao.MusicPlaylistDao
import com.hx.nekomimi.data.db.dao.PlaybackMemoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "nekomimi.db"
        )
            .fallbackToDestructiveMigration() // 版本升级时销毁重建 (开发阶段)
            .build()
    }

    @Provides
    fun providePlaybackMemoryDao(db: AppDatabase): PlaybackMemoryDao =
        db.playbackMemoryDao()

    @Provides
    fun provideBookmarkDao(db: AppDatabase): BookmarkDao =
        db.bookmarkDao()

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao =
        db.bookDao()

    @Provides
    fun provideMusicPlaylistDao(db: AppDatabase): MusicPlaylistDao =
        db.musicPlaylistDao()

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("nekomimi_prefs", Context.MODE_PRIVATE)
    }
}
