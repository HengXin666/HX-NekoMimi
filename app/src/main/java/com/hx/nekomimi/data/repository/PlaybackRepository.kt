package com.hx.nekomimi.data.repository

import android.content.SharedPreferences
import com.hx.nekomimi.data.db.dao.BookmarkDao
import com.hx.nekomimi.data.db.dao.PlaybackMemoryDao
import com.hx.nekomimi.data.db.entity.Bookmark
import com.hx.nekomimi.data.db.entity.PlaybackMemory
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放数据仓库
 * 统一管理播放记忆和书签的数据访问
 * 使用 SharedPreferences 作为紧急快照 (进程被杀时的最后防线)
 */
@Singleton
class PlaybackRepository @Inject constructor(
    private val memoryDao: PlaybackMemoryDao,
    private val bookmarkDao: BookmarkDao,
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
            .apply() // apply() 是异步的但会在进程退出前刷盘
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

    /** 获取最近一次播放记忆 */
    suspend fun getLatestMemory(): PlaybackMemory? =
        memoryDao.getLatest()

    /** 获取所有播放记忆 */
    fun getAllMemories(): Flow<List<PlaybackMemory>> =
        memoryDao.getAll()

    /** 获取指定文件夹的播放记忆 */
    fun getMemoriesByFolder(folderPath: String): Flow<List<PlaybackMemory>> =
        memoryDao.getByFolder(folderPath)

    // ==================== 书签 ====================

    /** 添加书签 */
    suspend fun addBookmark(
        filePath: String,
        positionMs: Long,
        durationMs: Long,
        label: String,
        folderPath: String,
        displayName: String
    ): Long {
        return bookmarkDao.insert(
            Bookmark(
                filePath = filePath,
                positionMs = positionMs,
                durationMs = durationMs,
                label = label,
                createdAt = System.currentTimeMillis(),
                folderPath = folderPath,
                displayName = displayName
            )
        )
    }

    /** 更新书签 */
    suspend fun updateBookmark(bookmark: Bookmark) =
        bookmarkDao.update(bookmark)

    /** 获取指定文件的书签 */
    fun getBookmarks(filePath: String): Flow<List<Bookmark>> =
        bookmarkDao.getByFilePath(filePath)

    /** 获取所有书签 */
    fun getAllBookmarks(): Flow<List<Bookmark>> =
        bookmarkDao.getAll()

    /** 获取指定文件夹的书签 */
    fun getBookmarksByFolder(folderPath: String): Flow<List<Bookmark>> =
        bookmarkDao.getByFolder(folderPath)

    /** 删除书签 */
    suspend fun deleteBookmark(id: Long) =
        bookmarkDao.deleteById(id)

    /** 手动记忆保存结果 */
    data class MemorySaveResult(
        val filePath: String,
        val positionMs: Long,
        val durationMs: Long,
        val displayName: String
    )
}
