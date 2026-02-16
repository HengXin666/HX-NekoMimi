package com.hx.nekomimi.data.db.dao

import androidx.room.*
import com.hx.nekomimi.data.db.entity.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    /** 插入书签 */
    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    /** 更新书签 (改名等) */
    @Update
    suspend fun update(bookmark: Bookmark)

    /** 获取指定文件的所有书签，按位置排序 */
    @Query("SELECT * FROM bookmark WHERE filePath = :filePath ORDER BY positionMs ASC")
    fun getByFilePath(filePath: String): Flow<List<Bookmark>>

    /** 获取指定文件夹下所有书签，按创建时间倒序 */
    @Query("SELECT * FROM bookmark WHERE folderPath = :folderPath ORDER BY createdAt DESC")
    fun getByFolder(folderPath: String): Flow<List<Bookmark>>

    /** 获取所有书签，按创建时间倒序 */
    @Query("SELECT * FROM bookmark ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Bookmark>>

    /** 删除书签 */
    @Delete
    suspend fun delete(bookmark: Bookmark)

    /** 按 ID 删除书签 */
    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空所有书签 */
    @Query("DELETE FROM bookmark")
    suspend fun deleteAll()
}
