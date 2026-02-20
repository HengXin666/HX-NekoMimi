package com.hx.nekomimi.data.repository

import androidx.lifecycle.LiveData
import com.hx.nekomimi.data.AppDatabase
import com.hx.nekomimi.data.entity.Book
import com.hx.nekomimi.data.entity.Chapter
import com.hx.nekomimi.data.entity.PlaybackProgress

class BookRepository(private val db: AppDatabase) {

    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val progressDao = db.playbackProgressDao()

    // ========== 书籍操作 ==========

    fun getAllBooks(): LiveData<List<Book>> = bookDao.getAllBooks()

    suspend fun getBookById(bookId: Long): Book? = bookDao.getBookById(bookId)

    fun getBookByIdLive(bookId: Long): LiveData<Book?> = bookDao.getBookByIdLive(bookId)

    suspend fun insertBook(book: Book): Long = bookDao.insert(book)

    suspend fun updateBook(book: Book) = bookDao.update(book)

    suspend fun deleteBook(bookId: Long) = bookDao.deleteById(bookId)

    // ========== 章节操作 ==========

    fun getChaptersByBookId(bookId: Long): LiveData<List<Chapter>> =
        chapterDao.getChaptersByBookId(bookId)

    suspend fun getChaptersByBookIdList(bookId: Long): List<Chapter> =
        chapterDao.getChaptersByBookIdList(bookId)

    suspend fun getChapterById(chapterId: Long): Chapter? =
        chapterDao.getChapterById(chapterId)

    suspend fun insertChapters(chapters: List<Chapter>) =
        chapterDao.insertAll(chapters)

    suspend fun deleteChaptersByBookId(bookId: Long) =
        chapterDao.deleteByBookId(bookId)

    suspend fun getChapterCount(bookId: Long): Int =
        chapterDao.getChapterCount(bookId)

    // ========== 播放进度操作 ==========

    suspend fun getProgressByBookId(bookId: Long): PlaybackProgress? =
        progressDao.getProgressByBookId(bookId)

    fun getProgressByBookIdLive(bookId: Long): LiveData<PlaybackProgress?> =
        progressDao.getProgressByBookIdLive(bookId)

    suspend fun getLastPlayedProgress(): PlaybackProgress? =
        progressDao.getLastPlayedProgress()

    fun getLastPlayedProgressLive(): LiveData<PlaybackProgress?> =
        progressDao.getLastPlayedProgressLive()

    suspend fun saveProgress(bookId: Long, chapterId: Long, positionMs: Long) {
        progressDao.upsert(bookId, chapterId, positionMs)
    }
}
