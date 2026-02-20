package com.hx.nekomimi.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import com.hx.nekomimi.NekoMimiApp
import com.hx.nekomimi.data.entity.Chapter
import com.hx.nekomimi.data.entity.PlaybackProgress
import com.hx.nekomimi.data.repository.BookRepository
import com.hx.nekomimi.subtitle.SubtitleEntry
import com.hx.nekomimi.subtitle.SubtitleHelper
import com.hx.nekomimi.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BookRepository((application as NekoMimiApp).database)

    private val _chapter = MutableLiveData<Chapter?>()
    val chapter: LiveData<Chapter?> = _chapter

    private val _subtitles = MutableLiveData<List<SubtitleEntry>>(emptyList())
    val subtitles: LiveData<List<SubtitleEntry>> = _subtitles

    private val _currentPosition = MutableLiveData(0L)
    val currentPosition: LiveData<Long> = _currentPosition

    private val _duration = MutableLiveData(0L)
    val duration: LiveData<Long> = _duration

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _lastProgress = MutableLiveData<PlaybackProgress?>()
    val lastProgress: LiveData<PlaybackProgress?> = _lastProgress

    var bookId: Long = 0L
        private set
    var chapterId: Long = 0L
        private set

    /**
     * 加载章节信息和字幕
     */
    fun loadChapter(bookId: Long, chapterId: Long) {
        this.bookId = bookId
        this.chapterId = chapterId

        viewModelScope.launch {
            val chapter = repository.getChapterById(chapterId)
            _chapter.value = chapter

            // 加载字幕
            if (chapter?.subtitleUri != null) {
                loadSubtitles(chapter)
            }

            // 加载上次播放进度
            val progress = repository.getProgressByBookId(bookId)
            if (progress != null && progress.chapterId == chapterId && progress.positionMs > 0) {
                _lastProgress.value = progress
            }
        }
    }

    private suspend fun loadSubtitles(chapter: Chapter) {
        withContext(Dispatchers.IO) {
            try {
                val subtitleUri = chapter.subtitleUri ?: return@withContext
                val content = FileScanner.readSubtitleContent(getApplication(), subtitleUri)
                    ?: return@withContext

                // 根据 URI 判断字幕类型
                val fileName = Uri.parse(subtitleUri).lastPathSegment ?: "subtitle.srt"
                val entries = SubtitleHelper.parseSubtitle(content, fileName)

                withContext(Dispatchers.Main) {
                    _subtitles.value = entries
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updatePosition(positionMs: Long) {
        _currentPosition.value = positionMs
    }

    fun updateDuration(durationMs: Long) {
        _duration.value = durationMs
    }

    fun updatePlayingState(playing: Boolean) {
        _isPlaying.value = playing
    }

    /**
     * 保存播放进度
     */
    fun saveProgress(positionMs: Long) {
        if (bookId <= 0 || chapterId <= 0) return
        viewModelScope.launch {
            repository.saveProgress(bookId, chapterId, positionMs)
        }
    }

    /**
     * 获取音频文件 URI
     */
    fun getAudioUri(): Uri? {
        return _chapter.value?.let { FileScanner.getAudioUri(it) }
    }

    fun clearLastProgress() {
        _lastProgress.value = null
    }
}
