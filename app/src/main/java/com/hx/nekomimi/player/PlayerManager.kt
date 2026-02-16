package com.hx.nekomimi.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hx.nekomimi.data.repository.PlaybackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放模式
 */
enum class PlayMode {
    /** 顺序播放 */
    SEQUENTIAL,
    /** 随机播放 */
    SHUFFLE,
    /** 单曲循环 */
    REPEAT_ONE
}

/**
 * 自动记忆保存事件 (用于 UI 显示提示)
 */
data class MemorySaveEvent(
    val filePath: String,
    val positionMs: Long,
    val displayName: String,
    val isAutoSave: Boolean = true
)

/**
 * 歌曲元信息 (从媒体文件中提取)
 */
data class TrackInfo(
    val file: File,
    /** 歌曲标题 (元信息中的标题，若无则用文件名) */
    val title: String,
    /** 歌手/艺术家 */
    val artist: String? = null,
    /** 专辑名 */
    val album: String? = null,
    /** 时长 (毫秒) */
    val durationMs: Long = 0,
    /** 封面图 (懒加载，可能为 null) */
    val coverBitmap: Bitmap? = null
)

/**
 * 播放器管理器
 * 管理 ExoPlayer 实例，提供播放控制和状态监听
 * 支持: 顺序/随机/单曲循环 播放模式
 * 支持: 音频 + 视频音轨 播放
 * 听书模式: 每 5 分钟自动保存位置并发出通知事件
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

    /** 当前歌曲封面 */
    private val _currentCover = MutableStateFlow<Bitmap?>(null)
    val currentCover: StateFlow<Bitmap?> = _currentCover.asStateFlow()

    /** 当前歌曲歌手 */
    private val _currentArtist = MutableStateFlow<String?>(null)
    val currentArtist: StateFlow<String?> = _currentArtist.asStateFlow()

    /** 当前歌曲专辑 */
    private val _currentAlbum = MutableStateFlow<String?>(null)
    val currentAlbum: StateFlow<String?> = _currentAlbum.asStateFlow()

    /** 当前歌单ID (用于关联歌单) */
    private val _currentPlaylistId = MutableStateFlow<Long?>(null)
    val currentPlaylistId: StateFlow<Long?> = _currentPlaylistId.asStateFlow()

    // ==================== 播放模式 ====================

    /** 当前播放模式 */
    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    // ==================== 听书模式记忆事件 ====================

    /** 是否处于听书模式 (由外部设置) */
    private val _isAudioBookMode = MutableStateFlow(false)
    val isAudioBookMode: StateFlow<Boolean> = _isAudioBookMode.asStateFlow()

    /** 自动记忆保存事件 (UI 监听此事件显示提示) */
    private val _memorySaveEvent = MutableSharedFlow<MemorySaveEvent>(extraBufferCapacity = 1)
    val memorySaveEvent: SharedFlow<MemorySaveEvent> = _memorySaveEvent.asSharedFlow()

    /** 听书模式下累计播放时间计数器 (毫秒)，用于 5 分钟记忆 */
    private var audiobookPlayedMs = 0L
    private val AUDIOBOOK_SAVE_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟

    // ==================== 支持的媒体格式 ====================

    /**
     * 支持的音频格式 (含视频格式 - 播放音频轨道)
     * 音频: mp3, wav, m4a, ogg, flac, aac, wma, opus, ape, alac
     * 视频(仅播放音轨): mp4, mkv, webm, avi, mov, ts, 3gp
     */
    private val mediaExtensions = setOf(
        // 音频格式
        "mp3", "wav", "m4a", "ogg", "flac", "aac", "wma", "opus", "ape", "alac",
        // B站缓存格式 (DASH 分段音视频)
        "m4s",
        // 视频格式 (播放音频轨道)
        "mp4", "mkv", "webm", "avi", "mov", "ts", "3gp"
    )

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context).build().apply {
            // 初始化播放模式
            applyPlayMode(this, _playMode.value)

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
                        // 播放结束，保存位置
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
                        // 切歌时加载新歌曲的元信息 (封面、歌手、专辑)
                        loadCurrentTrackMetadata(file)
                    }
                }
            })
        }
    }

    /**
     * 将播放模式应用到 ExoPlayer 实例
     */
    private fun applyPlayMode(player: ExoPlayer, mode: PlayMode) {
        when (mode) {
            PlayMode.SEQUENTIAL -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }
            PlayMode.SHUFFLE -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
            PlayMode.REPEAT_ONE -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
        }
    }

    /**
     * 切换播放模式: 顺序 → 随机 → 单曲循环 → 顺序 ...
     */
    fun togglePlayMode() {
        val next = when (_playMode.value) {
            PlayMode.SEQUENTIAL -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SEQUENTIAL
        }
        setPlayMode(next)
    }

    /**
     * 设置播放模式
     */
    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        _player?.let { applyPlayMode(it, mode) }
    }

    /**
     * 设置听书模式
     * 听书模式下每 5 分钟自动记忆一次，并发出事件通知 UI
     */
    fun setAudioBookMode(enabled: Boolean) {
        _isAudioBookMode.value = enabled
        audiobookPlayedMs = 0L
    }

    // ==================== 获取支持的媒体格式 ====================

    /**
     * 获取支持的媒体文件扩展名集合 (供外部使用)
     */
    fun getSupportedExtensions(): Set<String> = mediaExtensions

    /**
     * 加载文件夹并播放指定文件
     */
    fun loadFolderAndPlay(folderPath: String, filePath: String, playlistId: Long? = null) {
        val folder = File(folderPath)
        val files = folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in mediaExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()

        _playlist.value = files
        _currentFolderPath.value = folderPath
        _currentPlaylistId.value = playlistId

        val startIndex = files.indexOfFirst { it.absolutePath == filePath }.coerceAtLeast(0)
        _currentIndex.value = startIndex
        _currentFilePath.value = files.getOrNull(startIndex)?.absolutePath
        _currentDisplayName.value = files.getOrNull(startIndex)?.nameWithoutExtension

        val mediaItems = files.map { file -> createMediaItem(file) }

        // 启动前台服务 (确保通知栏/锁屏/导航栏控制可用)
        ensureServiceStarted()

        player.apply {
            setMediaItems(mediaItems, startIndex, 0)
            prepare()
        }

        // 加载当前歌曲元信息
        loadCurrentTrackMetadata(files.getOrNull(startIndex))

        // 恢复上次播放位置
        scope.launch {
            val memory = repository.getMemory(filePath)
            if (memory != null && memory.positionMs > 0) {
                player.seekTo(startIndex, memory.positionMs)
            }
            player.play()

            // 更新歌单的最近播放时间
            playlistId?.let { repository.updatePlaylistLastPlayed(it) }
        }
    }

    /**
     * 加载文件列表并播放指定文件 (歌单模式，文件可来自不同文件夹)
     */
    fun loadFilesAndPlay(files: List<File>, filePath: String, playlistFolderPath: String, playlistId: Long? = null) {
        _playlist.value = files
        _currentFolderPath.value = playlistFolderPath
        _currentPlaylistId.value = playlistId

        val startIndex = files.indexOfFirst { it.absolutePath == filePath }.coerceAtLeast(0)
        _currentIndex.value = startIndex
        _currentFilePath.value = files.getOrNull(startIndex)?.absolutePath
        _currentDisplayName.value = files.getOrNull(startIndex)?.nameWithoutExtension

        val mediaItems = files.map { file -> createMediaItem(file) }

        // 启动前台服务 (确保通知栏/锁屏/导航栏控制可用)
        ensureServiceStarted()

        player.apply {
            setMediaItems(mediaItems, startIndex, 0)
            prepare()
        }

        // 加载当前歌曲元信息
        loadCurrentTrackMetadata(files.getOrNull(startIndex))

        // 恢复上次播放位置
        scope.launch {
            val memory = repository.getMemory(filePath)
            if (memory != null && memory.positionMs > 0) {
                player.seekTo(startIndex, memory.positionMs)
            }
            player.play()

            // 更新歌单的最近播放时间
            playlistId?.let { repository.updatePlaylistLastPlayed(it) }
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

            // 加载当前歌曲元信息
            loadCurrentTrackMetadata(files[index])

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

    fun play() {
        ensureServiceStarted()
        player.play()
    }
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
     * 启动位置追踪:
     * - 每 300ms 更新 UI 状态
     * - 每 3 秒保存到数据库 + SP 快照
     * - 听书模式: 每 5 分钟触发一次记忆保存事件
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

                // 听书模式: 每 5 分钟发出记忆保存事件
                if (_isAudioBookMode.value && _isPlaying.value) {
                    audiobookPlayedMs += 300
                    if (audiobookPlayedMs >= AUDIOBOOK_SAVE_INTERVAL_MS) {
                        audiobookPlayedMs = 0L
                        // 保存到数据库
                        savePositionToDb(pos, dur)
                        // 发出事件通知 UI 显示 "正在保存位置..." → "ok"
                        val filePath = _currentFilePath.value
                        val displayName = _currentDisplayName.value
                        if (filePath != null && displayName != null) {
                            _memorySaveEvent.tryEmit(
                                MemorySaveEvent(
                                    filePath = filePath,
                                    positionMs = pos,
                                    displayName = displayName,
                                    isAutoSave = true
                                )
                            )
                        }
                    }
                }

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
     * 同时发出事件，让 UI 显示保存提示
     */
    suspend fun saveMemoryManually(): PlaybackRepository.MemorySaveResult? {
        val filePath = _currentFilePath.value ?: return null
        val pos = _player?.currentPosition?.coerceAtLeast(0) ?: return null
        val dur = _player?.duration?.coerceAtLeast(0) ?: return null
        val folder = _currentFolderPath.value ?: ""
        val name = _currentDisplayName.value ?: ""
        repository.saveMemory(filePath, pos, dur, folder, name)

        // 发出保存事件
        _memorySaveEvent.tryEmit(
            MemorySaveEvent(
                filePath = filePath,
                positionMs = pos,
                displayName = name,
                isAutoSave = false
            )
        )

        return PlaybackRepository.MemorySaveResult(filePath, pos, dur, name)
    }

    // ==================== 歌曲元信息加载 ====================

    /**
     * 加载当前歌曲的元信息 (封面、歌手、专辑)
     * 在后台线程中执行，避免阻塞 UI
     */
    private fun loadCurrentTrackMetadata(file: File?) {
        if (file == null) {
            _currentCover.value = null
            _currentArtist.value = null
            _currentAlbum.value = null
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val coverBytes = retriever.embeddedPicture
                val cover = coverBytes?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                }
                retriever.release()

                withContext(Dispatchers.Main) {
                    // 如果有元信息标题，更新显示名称
                    if (!title.isNullOrBlank()) {
                        _currentDisplayName.value = title
                    }
                    _currentArtist.value = artist
                    _currentAlbum.value = album
                    _currentCover.value = cover
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _currentCover.value = null
                    _currentArtist.value = null
                    _currentAlbum.value = null
                }
            }
        }
    }

    /**
     * 加载指定文件的元信息 (供 UI 列表显示使用)
     * 在 IO 线程执行，返回 TrackInfo
     */
    suspend fun loadTrackInfo(file: File): TrackInfo = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val coverBytes = retriever.embeddedPicture
            val cover = coverBytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            retriever.release()

            TrackInfo(
                file = file,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                coverBitmap = cover
            )
        } catch (e: Exception) {
            TrackInfo(
                file = file,
                title = file.nameWithoutExtension,
                durationMs = 0
            )
        }
    }

    /**
     * 批量加载文件夹下所有音频的元信息
     */
    suspend fun loadFolderTrackInfos(folderPath: String): List<TrackInfo> = withContext(Dispatchers.IO) {
        val folder = File(folderPath)
        val files = folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in mediaExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()

        files.map { file -> loadTrackInfo(file) }
    }

    /**
     * 递归扫描文件夹下所有音频文件 (包括子文件夹)
     */
    fun scanAudioFiles(folderPath: String): List<File> {
        val folder = File(folderPath)
        val result = mutableListOf<File>()
        scanRecursive(folder, result)
        return result.sortedBy { it.name }
    }

    private fun scanRecursive(dir: File, result: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                scanRecursive(child, result)
            } else if (child.isFile && child.extension.lowercase() in mediaExtensions) {
                result.add(child)
            }
        }
    }

    // ==================== MediaItem 构建 (MIME 类型支持) ====================

    /**
     * 根据文件扩展名获取对应的 MIME 类型
     * 特别处理 m4s (B站缓存的 DASH 分段格式) 等 ExoPlayer 无法自动识别的格式
     */
    private fun getMimeTypeForFile(file: File): String? {
        return when (file.extension.lowercase()) {
            "m4s" -> MimeTypes.AUDIO_MP4  // B站缓存 DASH 分段，本质是 MP4 容器
            "mp3" -> MimeTypes.AUDIO_MPEG
            "m4a", "aac" -> MimeTypes.AUDIO_AAC
            "ogg" -> MimeTypes.AUDIO_OGG
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "opus" -> MimeTypes.AUDIO_OPUS
            "wma" -> MimeTypes.AUDIO_WMA
            "mp4" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts" -> MimeTypes.VIDEO_MP2T
            "3gp" -> MimeTypes.VIDEO_H263
            "avi" -> MimeTypes.VIDEO_MP4 // AVI 尝试用 MP4 解析
            "mov" -> MimeTypes.VIDEO_MP4
            else -> null // 其他格式让 ExoPlayer 自动检测
        }
    }

    /**
     * 为文件创建 MediaItem，自动设置正确的 MIME 类型
     * 解决 m4s 等格式 ExoPlayer 无法自动识别的问题
     */
    private fun createMediaItem(file: File): MediaItem {
        val uri = file.toURI().toString()
        val mimeType = getMimeTypeForFile(file)
        return if (mimeType != null) {
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mimeType)
                .build()
        } else {
            MediaItem.fromUri(uri)
        }
    }

    // ==================== 前台服务管理 ====================

    /** 是否已经启动过服务 */
    private var serviceStarted = false

    /**
     * 确保 PlaybackService 前台服务已启动
     * 只有服务启动后，通知栏/锁屏/系统导航栏的媒体控制才会生效
     */
    private fun ensureServiceStarted() {
        if (serviceStarted) return
        try {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            serviceStarted = true
        } catch (e: Exception) {
            // 忽略启动失败 (例如后台启动限制)
            e.printStackTrace()
        }
    }

    /**
     * 释放播放器资源
     */
    fun release() {
        saveCurrentPositionSync()
        stopPositionTracking()
        _player?.release()
        _player = null
        serviceStarted = false
        scope.cancel()
    }
}
