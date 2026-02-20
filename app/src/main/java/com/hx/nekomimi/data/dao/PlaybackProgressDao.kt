package com.hx.nekomimi.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.hx.nekomimi.data.entity.PlaybackProgress

@Dao
interface PlaybackProgressDao {

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    suspend fun getProgressByBookId(bookId: Long): PlaybackProgress?

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    fun getProgressByBookIdLive(bookId: Long): LiveData<PlaybackProgress?>

    /**
     * 获取最近播放的记录（按更新时间降序）
     */
    @Query("SELECT * FROM playback_progress ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLastPlayedProgress(): PlaybackProgress?

    @Query("SELECT * FROM playback_progress ORDER BY updatedAt DESC LIMIT 1")
    fun getLastPlayedProgressLive(): LiveData<PlaybackProgress?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: PlaybackProgress)

    @Query("""
        INSERT OR REPLACE INTO playback_progress (id, bookId, chapterId, positionMs, updatedAt) 
        VALUES (
            (SELECT id FROM playback_progress WHERE bookId = :bookId),
            :bookId, :chapterId, :positionMs, :updatedAt
        )
    """)
    suspend fun upsert(bookId: Long, chapterId: Long, positionMs: Long, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(progress: PlaybackProgress)

    @Query("DELETE FROM playback_progress WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)
}
