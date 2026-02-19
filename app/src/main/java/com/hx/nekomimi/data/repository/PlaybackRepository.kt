package com.hx.nekomimi.data.repository

import android.content.SharedPreferences
import com.hx.nekomimi.data.db.dao.BookDao
import com.hx.nekomimi.data.db.dao.PlaybackMemoryDao
import com.hx.nekomimi.data.db.entity.Book
import com.hx.nekomimi.data.db.entity.PlaybackMemory
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据仓库
 * 统一管理有声书和播放记忆的数据访问
 * 使用 SharedPreferences 作为紧急快照 (进程被杀时的最后防线)
 */
@Singleton
class PlaybackRepository @Inject constructor(
    private val bookDao: BookDao,
    private val memoryDao: PlaybackMemoryDao,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_LAST_FILE = "last_file_path"
        private const val KEY_LAST_POSITION = "last_position_ms"
        private const val KEY_LAST_DURATION = "last_duration_ms"
        private const val KEY_LAST_FOLDER = "last_folder_path"
        private const val KEY_LAST_DISPLAY_NAME = "last_display_name"
        private const val KEY_LAST_SAVED_AT = "last_saved_at"
    }

    // ==================== 播放记忆 ====================

    /**
     * 保存播放位置到 Room 数据库
     */
    suspend fun saveMemory(
        filePath: String,
        positionMs: Long,
        durationMs: Long,
        folderPath: String,
        displayName: String
    ) {
        val now = System.currentTimeMillis()
        memoryDao.upsert(
            PlaybackMemory(
                filePath = filePath,
                positionMs = positionMs,
                durationMs = durationMs,
                savedAt = now,
                folderPath = folderPath,
                displayName = displayName
            )
        )
        // 同时写入 SharedPreferences 作为快照
        saveQuickSnapshot(filePath, positionMs, durationMs, folderPath, displayName, now)
    }

    /**
     * 紧急快照: 写入 SharedPreferences (同步, 极快)
     * 用于进程被杀前的最后一刻保存
     */
    fun saveQuickSnapshot(
        filePath: String,
        positionMs: Long,
        durationMs: Long,
        folderPath: String,
        displayName: String,
        savedAt: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putString(KEY_LAST_FILE, filePath)
            .putLong(KEY_LAST_POSITION, positionMs)
            .putLong(KEY_LAST_DURATION, durationMs)
            .putString(KEY_LAST_FOLDER, folderPath)
            .putString(KEY_LAST_DISPLAY_NAME, displayName)
            .putLong(KEY_LAST_SAVED_AT, savedAt)
            .apply()
    }

    /**
     * 从快照恢复 (进程重启后先从 SP 快速读取)
     */
    fun getQuickSnapshot(): PlaybackMemory? {
        val filePath = prefs.getString(KEY_LAST_FILE, null) ?: return null
        return PlaybackMemory(
            filePath = filePath,
            positionMs = prefs.getLong(KEY_LAST_POSITION, 0),
            durationMs = prefs.getLong(KEY_LAST_DURATION, 0),
            savedAt = prefs.getLong(KEY_LAST_SAVED_AT, 0),
            folderPath = prefs.getString(KEY_LAST_FOLDER, "") ?: "",
            displayName = prefs.getString(KEY_LAST_DISPLAY_NAME, "") ?: ""
        )
    }

    /** 获取指定文件的播放记忆 */
    suspend fun getMemory(filePath: String): PlaybackMemory? =
        memoryDao.getByFilePath(filePath)

    /**
     * 按文件名模糊匹配播放记忆 (降级方案)
     * 用于跨 File/URI 模式的记忆恢复
     */
    suspend fun getMemoryByFileName(fileName: String): PlaybackMemory? =
        memoryDao.getByFileNameLike(fileName)

    /** 获取最近一次播放记忆 */
    suspend fun getLatestMemory(): PlaybackMemory? =
        memoryDao.getLatest()

    /** 获取所有播放记忆 */
    fun getAllMemories(): Flow<List<PlaybackMemory>> =
        memoryDao.getAll()

    /** 获取指定文件夹的播放记忆 */
    fun getMemoriesByFolder(folderPath: String): Flow<List<PlaybackMemory>> =
        memoryDao.getByFolder(folderPath)

    // ==================== 有声书 ====================

    /**
     * 导入一本书 (根文件夹)
     * 如果已存在则忽略，返回已有或新建的 Book
     * @param folderUri SAF 授权的 URI (用于访问隐藏文件夹)
     */
    suspend fun importBook(folderPath: String, folderUri: String? = null): Book {
        val existing = bookDao.getByFolderPath(folderPath)
        if (existing != null) {
            if (folderUri != null) {
                bookDao.updateFolderUri(existing.id, folderUri)
            }
            return existing.copy(folderUri = folderUri ?: existing.folderUri)
        }

        val folderName = File(folderPath).name
        val book = Book(
            folderPath = folderPath,
            folderUri = folderUri,
            title = folderName,
            importedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )
        bookDao.upsert(book)
        return bookDao.getByFolderPath(folderPath) ?: book
    }

    /** 获取所有书，按导入日期倒序 */
    fun getAllBooksByImportDate(): Flow<List<Book>> =
        bookDao.getAllByImportDate()

    /** 获取所有书，按最近更新倒序 */
    fun getAllBooksByLastUpdated(): Flow<List<Book>> =
        bookDao.getAllByLastUpdated()

    /** 根据文件夹路径获取书 */
    suspend fun getBook(folderPath: String): Book? =
        bookDao.getByFolderPath(folderPath)

    /** 根据 ID 获取书 */
    suspend fun getBookById(id: Long): Book? =
        bookDao.getById(id)

    /** 更新书的最近播放信息 */
    suspend fun updateBookLastPlayed(
        folderPath: String,
        filePath: String,
        fileUri: String? = null,
        positionMs: Long,
        durationMs: Long,
        displayName: String
    ) {
        bookDao.updateLastPlayed(folderPath, filePath, fileUri, positionMs, durationMs, displayName)
    }

    /** 更新书的元信息 (标题、描述) */
    suspend fun updateBookInfo(id: Long, title: String, description: String) {
        bookDao.updateBookInfo(id, title, description)
    }

    /** 删除书 */
    suspend fun deleteBook(id: Long) =
        bookDao.deleteById(id)
}
