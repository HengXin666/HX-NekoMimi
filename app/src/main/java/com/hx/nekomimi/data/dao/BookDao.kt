package com.hx.nekomimi.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.hx.nekomimi.data.entity.Book

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): LiveData<List<Book>>

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    suspend fun getAllBooksList(): List<Book>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): Book?

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun getBookByIdLive(bookId: Long): LiveData<Book?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)
}
