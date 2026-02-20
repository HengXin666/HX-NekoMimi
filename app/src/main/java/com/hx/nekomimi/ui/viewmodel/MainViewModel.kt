package com.hx.nekomimi.ui.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.hx.nekomimi.NekoMimiApp
import com.hx.nekomimi.data.entity.Book
import com.hx.nekomimi.data.entity.PlaybackProgress
import com.hx.nekomimi.data.repository.BookRepository
import com.hx.nekomimi.ui.adapter.BookAdapter
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BookRepository((application as NekoMimiApp).database)

    private val _books = repository.getAllBooks()
    val books: LiveData<List<Book>> = _books

    /**
     * 将书籍列表转换为带章节数量的 BookItem 列表
     */
    val bookItems: LiveData<List<BookAdapter.BookItem>> = books.switchMap { bookList ->
        liveData {
            val items = bookList.map { book ->
                val count = repository.getChapterCount(book.id)
                BookAdapter.BookItem(book, count)
            }
            emit(items)
        }
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            repository.deleteBook(bookId)
        }
    }
}
