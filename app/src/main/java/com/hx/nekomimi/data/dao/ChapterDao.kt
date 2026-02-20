package com.hx.nekomimi.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.hx.nekomimi.data.entity.Chapter

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY parentFolder, sortOrder, title")
    fun getChaptersByBookId(bookId: Long): LiveData<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY parentFolder, sortOrder, title")
    suspend fun getChaptersByBookIdList(bookId: Long): List<Chapter>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getChapterById(chapterId: Long): Chapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<Chapter>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: Chapter): Long

    @Update
    suspend fun update(chapter: Chapter)

    @Delete
    suspend fun delete(chapter: Chapter)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun getChapterCount(bookId: Long): Int

    /**
     * 监听章节表的整体变化（用于触发主页刷新）
     */
    @Query("SELECT COUNT(*) FROM chapters")
    fun observeTotalChapterCount(): LiveData<Int>
}
