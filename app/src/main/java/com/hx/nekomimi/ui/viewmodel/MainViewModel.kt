package com.hx.nekomimi.ui.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.hx.nekomimi.NekoMimiApp
import com.hx.nekomimi.data.entity.Book
import com.hx.nekomimi.data.repository.BookRepository
import com.hx.nekomimi.ui.adapter.BookAdapter
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BookRepository((application as NekoMimiApp).database)

    private val _books = repository.getAllBooks()
    val books: LiveData<List<Book>> = _books

    // 监听章节表整体变化，当章节插入/删除时会触发
    private val _chapterChange = repository.observeTotalChapterCount()

    /**
     * 将书籍列表转换为带章节数量的 BookItem 列表
     * 同时监听书籍列表和章节表变化，任一变化都会重新计算
     */
    private val _refreshTrigger = MediatorLiveData<Unit>().apply {
        addSource(_books) { value = Unit }
        addSource(_chapterChange) { value = Unit }
    }

    val bookItems: LiveData<List<BookAdapter.BookItem>> = _refreshTrigger.switchMap {
        liveData {
            val bookList = _books.value ?: emptyList()
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
