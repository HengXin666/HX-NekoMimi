package com.hx.nekomimi.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hx.nekomimi.data.repository.PlaybackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放器管理器
 * 管理 ExoPlayer 实例，提供播放控制和状态监听
 * 每 3 秒自动保存播放位置 (Room + SharedPreferences 双保险)
 */
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlaybackRepository
) {
    private var _player: ExoPlayer? = null
    val player: ExoPlayer
        get() = _player ?: createPlayer().also { _player = it }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null

    // ==================== 播放状态 ====================

    /** 当前播放文件路径 */
    private val _currentFilePath = MutableStateFlow<String?>(null)
    val currentFilePath: StateFlow<String?> = _currentFilePath.asStateFlow()

    /** 当前播放文件夹路径 */
    private val _currentFolderPath = MutableStateFlow<String?>(null)
    val currentFolderPath: StateFlow<String?> = _currentFolderPath.asStateFlow()

    /** 当前文件显示名称 */
    private val _currentDisplayName = MutableStateFlow<String?>(null)
    val currentDisplayName: StateFlow<String?> = _currentDisplayName.asStateFlow()

    /** 是否正在播放 */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 当前播放位置 (毫秒) */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** 总时长 (毫秒) */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** 播放列表 (当前文件夹下的所有音频) */
    private val _playlist = MutableStateFlow<List<File>>(emptyList())
    val playlist: StateFlow<List<File>> = _playlist.asStateFlow()

    /** 当前播放索引 */
    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // 支持的音频格式
    private val audioExtensions = setOf(
        "mp3", "wav", "m4a", "ogg", "flac", "aac", "wma", "opus", "ape", "alac"
    )

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        startPositionTracking()
                    } else {
                        stopPositionTracking()
                        // 暂停时立即保存位置
                        saveCurrentPositionSync()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _durationMs.value = duration.coerceAtLeast(0)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        // 播放结束，保存位置并尝试播放下一个
                        saveCurrentPositionSync()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 播放列表切换时更新文件信息
                    val idx = currentMediaItemIndex
                    val files = _playlist.value
                    if (idx in files.indices) {
                        val file = files[idx]
                        _currentFilePath.value = file.absolutePath
                        _currentDisplayName.value = file.nameWithoutExtension
                        _currentIndex.value = idx
                    }
                }
            })
        }
    }

    /**
     * 加载文件夹并播放指定文件
     */
    fun loadFolderAndPlay(folderPath: String, filePath: String) {
        val folder = File(folderPath)
        val files = folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in audioExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()

        _playlist.value = files
        _currentFolderPath.value = folderPath

        val startIndex = files.indexOfFirst { it.absolutePath == filePath }.coerceAtLeast(0)
        _currentIndex.value = startIndex
        _currentFilePath.value = files.getOrNull(startIndex)?.absolutePath
        _currentDisplayName.value = files.getOrNull(startIndex)?.nameWithoutExtension

        val mediaItems = files.map { file ->
            MediaItem.fromUri(file.toURI().toString())
        }

        player.apply {
            setMediaItems(mediaItems, startIndex, 0)
            prepare()
        }

        // 恢复上次播放位置
        scope.launch {
            val memory = repository.getMemory(filePath)
            if (memory != null && memory.positionMs > 0) {
                player.seekTo(startIndex, memory.positionMs)
            }
            player.play()
        }
    }

    /**
     * 播放指定索引
     */
    fun playAt(index: Int) {
        val files = _playlist.value
        if (index in files.indices) {
            // 先保存当前位置
            saveCurrentPositionSync()

            _currentIndex.value = index
            _currentFilePath.value = files[index].absolutePath
            _currentDisplayName.value = files[index].nameWithoutExtension

            player.seekTo(index, 0)

            // 恢复该文件的播放位置
            scope.launch {
                val memory = repository.getMemory(files[index].absolutePath)
                if (memory != null && memory.positionMs > 0) {
                    player.seekTo(index, memory.positionMs)
                }
                player.play()
            }
        }
    }

    fun play() { player.play() }
    fun pause() { player.pause() }
    fun seekTo(positionMs: Long) { player.seekTo(positionMs) }

    fun next() {
        if (player.hasNextMediaItem()) {
            saveCurrentPositionSync()
            player.seekToNext()
        }
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) {
            saveCurrentPositionSync()
            player.seekToPrevious()
        }
    }

    // ==================== 位置跟踪与保存 ====================

    /**
     * 启动位置追踪: 每 300ms 更新 UI 状态，每 3 秒保存到数据库
     */
    private fun startPositionTracking() {
        positionSaveJob?.cancel()
        positionSaveJob = scope.launch {
            var saveCounter = 0
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0)
                val dur = player.duration.coerceAtLeast(0)
                _positionMs.value = pos
                _durationMs.value = dur

                saveCounter++
                // 每 3 秒保存一次到数据库 (300ms * 10 = 3s)
                if (saveCounter >= 10) {
                    saveCounter = 0
                    savePositionToDb(pos, dur)
                }

                // 每次都写入 SharedPreferences 快照 (极快，无阻塞)
                savePositionToSnapshot(pos, dur)

                delay(300)
            }
        }
    }

    private fun stopPositionTracking() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    /** 同步保存当前位置 (暂停/切换/退出时调用) */
    fun saveCurrentPositionSync() {
        val filePath = _currentFilePath.value ?: return
        val pos = _player?.currentPosition?.coerceAtLeast(0) ?: return
        val dur = _player?.duration?.coerceAtLeast(0) ?: return
        val folder = _currentFolderPath.value ?: ""
        val name = _currentDisplayName.value ?: ""

        // SharedPreferences 快照 (同步，不会丢)
        repository.saveQuickSnapshot(filePath, pos, dur, folder, name)

        // Room 数据库 (异步)
        scope.launch {
            repository.saveMemory(filePath, pos, dur, folder, name)
        }
    }

    private suspend fun savePositionToDb(positionMs: Long, durationMs: Long) {
        val filePath = _currentFilePath.value ?: return
        val folder = _currentFolderPath.value ?: ""
        val name = _currentDisplayName.value ?: ""
        repository.saveMemory(filePath, positionMs, durationMs, folder, name)
    }

    private fun savePositionToSnapshot(positionMs: Long, durationMs: Long) {
        val filePath = _currentFilePath.value ?: return
        val folder = _currentFolderPath.value ?: ""
        val name = _currentDisplayName.value ?: ""
        repository.saveQuickSnapshot(filePath, positionMs, durationMs, folder, name)
    }

    /**
     * 手动触发记忆 (用户主动按钮)
     */
    suspend fun saveMemoryManually(): PlaybackRepository.MemorySaveResult? {
        val filePath = _currentFilePath.value ?: return null
        val pos = _player?.currentPosition?.coerceAtLeast(0) ?: return null
        val dur = _player?.duration?.coerceAtLeast(0) ?: return null
        val folder = _currentFolderPath.value ?: ""
        val name = _currentDisplayName.value ?: ""
        repository.saveMemory(filePath, pos, dur, folder, name)
        return PlaybackRepository.MemorySaveResult(filePath, pos, dur, name)
    }

    /**
     * 释放播放器资源
     */
    fun release() {
        saveCurrentPositionSync()
        stopPositionTracking()
        _player?.release()
        _player = null
        scope.cancel()
    }
}
