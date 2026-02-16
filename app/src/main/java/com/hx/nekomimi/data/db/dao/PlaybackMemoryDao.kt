package com.hx.nekomimi.data.db.dao

import androidx.room.*
import com.hx.nekomimi.data.db.entity.PlaybackMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackMemoryDao {

    /** 插入或更新播放记忆 (按 filePath 唯一) */
    @Upsert
    suspend fun upsert(memory: PlaybackMemory)

    /** 获取指定文件的播放记忆 */
    @Query("SELECT * FROM playback_memory WHERE filePath = :filePath LIMIT 1")
    suspend fun getByFilePath(filePath: String): PlaybackMemory?

    /** 获取指定文件夹下所有播放记忆，按记忆时间倒序 */
    @Query("SELECT * FROM playback_memory WHERE folderPath = :folderPath ORDER BY savedAt DESC")
    fun getByFolder(folderPath: String): Flow<List<PlaybackMemory>>

    /** 获取所有播放记忆，按记忆时间倒序 */
    @Query("SELECT * FROM playback_memory ORDER BY savedAt DESC")
    fun getAll(): Flow<List<PlaybackMemory>>

    /** 获取最近的播放记忆 (用于恢复上次播放) */
    @Query("SELECT * FROM playback_memory ORDER BY savedAt DESC LIMIT 1")
    suspend fun getLatest(): PlaybackMemory?

    /** 删除指定记忆 */
    @Delete
    suspend fun delete(memory: PlaybackMemory)

    /** 清空所有记忆 */
    @Query("DELETE FROM playback_memory")
    suspend fun deleteAll()
}
