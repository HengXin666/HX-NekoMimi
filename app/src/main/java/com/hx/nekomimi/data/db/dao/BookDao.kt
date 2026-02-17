package com.hx.nekomimi.data.db.dao

import androidx.room.*
import com.hx.nekomimi.data.db.entity.Book
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    /** 插入或更新书 (按 folderPath 唯一) */
    @Upsert
    suspend fun upsert(book: Book)

    /** 插入书 (如果已存在则忽略) */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(book: Book): Long

    /** 更新书 */
    @Update
    suspend fun update(book: Book)

    /** 根据文件夹路径获取书 */
    @Query("SELECT * FROM book WHERE folderPath = :folderPath LIMIT 1")
    suspend fun getByFolderPath(folderPath: String): Book?

    /** 根据 ID 获取书 */
    @Query("SELECT * FROM book WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Book?

    /** 获取所有书，按导入日期倒序 */
    @Query("SELECT * FROM book ORDER BY importedAt DESC")
    fun getAllByImportDate(): Flow<List<Book>>

    /** 获取所有书，按最近更新日期倒序 */
    @Query("SELECT * FROM book ORDER BY lastUpdatedAt DESC")
    fun getAllByLastUpdated(): Flow<List<Book>>

    /** 获取所有书 (不排序，用于通用查询) */
    @Query("SELECT * FROM book")
    fun getAll(): Flow<List<Book>>

    /** 更新书的最近播放信息 */
    @Query("""
        UPDATE book SET 
            lastPlayedFilePath = :filePath,
            lastPlayedPositionMs = :positionMs,
            lastPlayedDurationMs = :durationMs,
            lastPlayedDisplayName = :displayName,
            lastUpdatedAt = :updatedAt
        WHERE folderPath = :folderPath
    """)
    suspend fun updateLastPlayed(
        folderPath: String,
        filePath: String,
        positionMs: Long,
        durationMs: Long,
        displayName: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    /** 更新书的元信息 (标题、描述) */
    @Query("""
        UPDATE book SET 
            title = :title,
            description = :description,
            lastUpdatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateBookInfo(
        id: Long,
        title: String,
        description: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    /** 删除书 */
    @Delete
    suspend fun delete(book: Book)

    /** 按 ID 删除书 */
    @Query("DELETE FROM book WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 更新书的 folderUri */
    @Query("UPDATE book SET folderUri = :folderUri WHERE id = :id")
    suspend fun updateFolderUri(id: Long, folderUri: String?)
}
