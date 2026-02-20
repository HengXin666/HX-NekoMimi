package com.hx.nekomimi.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import com.hx.nekomimi.NekoMimiApp
import com.hx.nekomimi.data.entity.Book
import com.hx.nekomimi.data.entity.Chapter
import com.hx.nekomimi.data.entity.PlaybackProgress
import com.hx.nekomimi.data.repository.BookRepository
import com.hx.nekomimi.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BookRepository((application as NekoMimiApp).database)

    private val _bookId = MutableLiveData<Long>()

    val book: LiveData<Book?> = _bookId.switchMap { id ->
        repository.getBookByIdLive(id)
    }

    val chapters: LiveData<List<Chapter>> = _bookId.switchMap { id ->
        repository.getChaptersByBookId(id)
    }

    val progress: LiveData<PlaybackProgress?> = _bookId.switchMap { id ->
        repository.getProgressByBookIdLive(id)
    }

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _scanResult = MutableLiveData<String?>()
    val scanResult: LiveData<String?> = _scanResult

    fun setBookId(bookId: Long) {
        _bookId.value = bookId
    }

    /**
     * 获取上次播放章节的标题
     */
    suspend fun getChapterTitle(chapterId: Long): String? {
        return repository.getChapterById(chapterId)?.title
    }

    /**
     * 刷新章节列表（重新扫描）
     */
    fun refreshChapters() {
        val bookId = _bookId.value ?: return
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val book = repository.getBookById(bookId) ?: return@launch
                val treeUri = book.rootUri?.let { Uri.parse(it) } ?: return@launch

                val chapters = withContext(Dispatchers.IO) {
                    FileScanner.scanFromUri(getApplication(), treeUri, bookId)
                }

                // 删除旧章节，插入新章节
                repository.deleteChaptersByBookId(bookId)
                repository.insertChapters(chapters)

                _scanResult.value = "扫描完成，共 ${chapters.size} 个章节"
            } catch (e: Exception) {
                e.printStackTrace()
                _scanResult.value = "扫描失败: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clearScanResult() {
        _scanResult.value = null
    }
}
