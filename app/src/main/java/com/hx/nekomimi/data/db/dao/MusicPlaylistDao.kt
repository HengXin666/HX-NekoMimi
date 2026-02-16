package com.hx.nekomimi.data.db.dao

import androidx.room.*
import com.hx.nekomimi.data.db.entity.MusicPlaylist
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicPlaylistDao {

    /** 插入或更新歌单 (按 folderPath 唯一) */
    @Upsert
    suspend fun upsert(playlist: MusicPlaylist)

    /** 获取所有歌单，按最近播放时间倒序 */
    @Query("SELECT * FROM music_playlist ORDER BY lastPlayedAt DESC")
    fun getAllByLastPlayed(): Flow<List<MusicPlaylist>>

    /** 获取所有歌单，按导入时间倒序 */
    @Query("SELECT * FROM music_playlist ORDER BY importedAt DESC")
    fun getAllByImportDate(): Flow<List<MusicPlaylist>>

    /** 根据文件夹路径获取歌单 */
    @Query("SELECT * FROM music_playlist WHERE folderPath = :folderPath LIMIT 1")
    suspend fun getByFolderPath(folderPath: String): MusicPlaylist?

    /** 根据 ID 获取歌单 */
    @Query("SELECT * FROM music_playlist WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MusicPlaylist?

    /** 更新歌单名称 */
    @Query("UPDATE music_playlist SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    /** 更新歌曲数量 */
    @Query("UPDATE music_playlist SET trackCount = :count WHERE id = :id")
    suspend fun updateTrackCount(id: Long, count: Int)

    /** 更新最近播放时间 */
    @Query("UPDATE music_playlist SET lastPlayedAt = :time WHERE id = :id")
    suspend fun updateLastPlayedAt(id: Long, time: Long)

    /** 删除歌单 */
    @Query("DELETE FROM music_playlist WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 获取所有歌单 (非 Flow，同步查询) */
    @Query("SELECT * FROM music_playlist ORDER BY lastPlayedAt DESC")
    suspend fun getAll(): List<MusicPlaylist>
}
