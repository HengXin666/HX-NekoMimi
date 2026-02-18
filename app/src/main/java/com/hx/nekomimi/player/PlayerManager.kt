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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import android.util.Log

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
 * 文件扫描结果状态
 */
enum class ScanStatus {
    /** 音频文件，成功识别 */
    DONE,
    /** 非音频文件，跳过 */
    PASS,
    /** 音频文件但出错了 */
    ERR
}

/**
 * 单个文件的扫描结果
 */
data class ScanResultItem(
    val fileName: String,
    val filePath: String,
    val status: ScanStatus,
    val reason: String = ""
)

/**
 * 文件夹扫描汇总结果
 */
data class FolderScanResult(
    val items: List<ScanResultItem>,
    val totalCount: Int,
    val doneCount: Int,
    val passCount: Int,
    val errCount: Int
)

/**
 * 歌曲元信息 (从媒体文件中提取)
 */
data class TrackInfo(
    val file: File,
    /** 文件 URI (用于 DocumentFile 方式访问的文件) */
    val fileUri: android.net.Uri? = null,
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

    /** 当前播放文件夹的 SAF URI (用于访问隐藏文件夹) */
    private val _currentFolderUri = MutableStateFlow<android.net.Uri?>(null)
    val currentFolderUri: StateFlow<android.net.Uri?> = _currentFolderUri.asStateFlow()

    /** 当前文件显示名称 (可能被媒体元信息标题覆盖) */
    private val _currentDisplayName = MutableStateFlow<String?>(null)
    val currentDisplayName: StateFlow<String?> = _currentDisplayName.asStateFlow()

    /** 当前文件的原始文件名 (不含扩展名, 永远不会被元信息覆盖, 用于字幕匹配等) */
    private val _currentFileName = MutableStateFlow<String?>(null)
    val currentFileName: StateFlow<String?> = _currentFileName.asStateFlow()

    /** 是否正在播放 */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 当前播放位置 (毫秒) */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** 总时长 (毫秒) */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** 播放列表 (当前文件夹下的所有音频, File API 模式) */
    private val _playlist = MutableStateFlow<List<File>>(emptyList())
    val playlist: StateFlow<List<File>> = _playlist.asStateFlow()

    /** URI 播放列表 (SAF 模式, 与 _playlist 互斥使用) */
    private val _uriPlaylist = MutableStateFlow<List<Uri>>(emptyList())
    val uriPlaylist: StateFlow<List<Uri>> = _uriPlaylist.asStateFlow()

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
        // 创建渲染器工厂，启用 FFmpeg 音频解码器 (支持 m4a/m4s 等格式软解码)
        val renderersFactory = DefaultRenderersFactory(context).apply {
            // 启用 FFmpeg 音频渲染器，用于软解码 m4a/m4s 等格式
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build().apply {
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
                    _currentIndex.value = idx

                    // 优先检查 File 模式播放列表
                    val files = _playlist.value
                    if (idx in files.indices) {
                        val file = files[idx]
                        _currentFilePath.value = file.absolutePath
                        _currentDisplayName.value = file.nameWithoutExtension
                        _currentFileName.value = file.nameWithoutExtension
                        // 切歌时加载新歌曲的元信息 (封面、歌手、专辑)
                        loadCurrentTrackMetadata(file)
                        return
                    }

                    // URI 模式播放列表 (SAF 隐藏文件夹)
                    val uris = _uriPlaylist.value
                    if (idx in uris.indices) {
                        val uri = uris[idx]
                        _currentFilePath.value = uri.toString()
                        val fileName = extractFileNameFromUri(uri)
                        val fileNameNoExt = fileName.substringBeforeLast('.')
                        _currentDisplayName.value = fileNameNoExt
                        _currentFileName.value = fileNameNoExt
                        // 切歌时加载新歌曲的元信息 (封面、歌手、专辑)
                        loadCurrentTrackMetadataFromUri(context, uri)
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
     * 加载文件夹并播放指定文件 (递归扫描子文件夹)
     * 当有 folderUri 时，优先使用 SAF DocumentFile 扫描 (支持隐藏文件夹和分区存储)
     * @param folderUri SAF 授权的 URI (用于访问隐藏文件夹)
     * @param targetUri 目标文件的 SAF URI (直接匹配，避免依赖文件名)
     */
    fun loadFolderAndPlay(folderPath: String, filePath: String, playlistId: Long? = null, folderUri: android.net.Uri? = null, targetUri: android.net.Uri? = null) {
        scope.launch {
            // 耗时的扫描操作在 IO 线程执行，避免阻塞主线程导致 UI 卡顿
            // 优先尝试 SAF 方式 (支持隐藏文件夹、分区存储)
            if (folderUri != null) {
                val safHandled = withContext(Dispatchers.IO) {
                    val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
                    if (treeDoc != null && treeDoc.exists()) {
                        val uriList = mutableListOf<Uri>()
                        scanDocumentFileUrisRecursive(treeDoc, uriList)
                        if (uriList.isNotEmpty()) uriList else null
                    } else null
                }

                if (safHandled != null) {
                    val uriList = safHandled
                    Log.d("PlayerManager", "loadFolderAndPlay: SAF 模式, 扫描到 ${uriList.size} 个文件")
                    // 优先使用 targetUri 直接匹配，其次按文件名匹配
                    val startUri = (if (targetUri != null) {
                        uriList.firstOrNull { it == targetUri }
                            ?: run {
                                Log.w("PlayerManager", "loadFolderAndPlay: targetUri=$targetUri 未在扫描列表中找到, 降级到文件名匹配")
                                null
                            }
                    } else null)
                    ?: run {
                        // 从 filePath 中提取文件名来匹配 URI (降级方案)
                        val targetFileName = File(filePath).name
                        Log.d("PlayerManager", "loadFolderAndPlay: 按文件名匹配, targetFileName=$targetFileName")
                        uriList.firstOrNull { uri ->
                            extractFileNameFromUri(uri).equals(targetFileName, ignoreCase = true)
                        } ?: run {
                            Log.w("PlayerManager", "loadFolderAndPlay: 文件名匹配失败 (targetFileName=$targetFileName), 可用URI列表:")
                            uriList.take(5).forEachIndexed { i, uri ->
                                Log.w("PlayerManager", "  [$i] ${extractFileNameFromUri(uri)} -> $uri")
                            }
                            uriList.first()
                        }
                    }

                    loadUrisAndPlay(
                        context = context,
                        uris = uriList,
                        startUri = startUri,
                        playlistFolderPath = folderPath,
                        playlistId = playlistId,
                        folderUri = folderUri
                    )
                    return@launch
                }
                Log.w("PlayerManager", "loadFolderAndPlay: SAF 扫描到 0 个文件, 降级到 File API")
            }

            // 降级: 使用 File API 扫描 (在 IO 线程执行)
            val files = withContext(Dispatchers.IO) {
                scanAudioFiles(folderPath)
            }

            if (files.isEmpty()) {
                Log.w("PlayerManager", "loadFolderAndPlay: File API 也扫描到 0 个文件, folderPath=$folderPath")
                return@launch
            }

            _playlist.value = files
            _uriPlaylist.value = emptyList() // 清除 URI 播放列表
            _currentFolderPath.value = folderPath
            _currentFolderUri.value = folderUri
            _currentPlaylistId.value = playlistId

            val startIndex = files.indexOfFirst { it.absolutePath == filePath }.coerceAtLeast(0)
            _currentIndex.value = startIndex
            _currentFilePath.value = files.getOrNull(startIndex)?.absolutePath
            _currentDisplayName.value = files.getOrNull(startIndex)?.nameWithoutExtension
            _currentFileName.value = files.getOrNull(startIndex)?.nameWithoutExtension

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
     * @param folderUri SAF 授权的 URI (用于访问隐藏文件夹)
     */
    fun loadFilesAndPlay(files: List<File>, filePath: String, playlistFolderPath: String, playlistId: Long? = null, folderUri: android.net.Uri? = null) {
        _playlist.value = files
        _uriPlaylist.value = emptyList() // 清除 URI 播放列表
        _currentFolderPath.value = playlistFolderPath
        _currentFolderUri.value = folderUri
        _currentPlaylistId.value = playlistId

        val startIndex = files.indexOfFirst { it.absolutePath == filePath }.coerceAtLeast(0)
        _currentIndex.value = startIndex
        _currentFilePath.value = files.getOrNull(startIndex)?.absolutePath
        _currentDisplayName.value = files.getOrNull(startIndex)?.nameWithoutExtension
        _currentFileName.value = files.getOrNull(startIndex)?.nameWithoutExtension

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
            try {
                val memory = repository.getMemory(filePath)
                if (memory != null && memory.positionMs > 0) {
                    _player?.seekTo(startIndex, memory.positionMs)
                }
                _player?.play()

                // 更新歌单的最近播放时间
                playlistId?.let { repository.updatePlaylistLastPlayed(it) }
            } catch (e: Exception) {
                Log.e("PlayerManager", "loadFilesAndPlay 恢复播放异常: ${e.message}")
            }
        }
    }

    /**
     * 基于 URI 加载并播放 (支持隐藏文件夹)
     * @param context 用于加载元信息
     * @param uris 音频文件 URI 列表
     * @param startUri 起始播放的 URI
     * @param playlistFolderPath 歌单文件夹路径 (用于存储播放位置)
     * @param playlistId 歌单 ID
     * @param folderUri SAF 授权的 URI (用于访问隐藏文件夹)
     */
    fun loadUrisAndPlay(
        context: Context,
        uris: List<Uri>,
        startUri: Uri,
        playlistFolderPath: String,
        playlistId: Long? = null,
        folderUri: android.net.Uri? = null
    ) {
        _uriPlaylist.value = uris // 设置 URI 播放列表
        _playlist.value = emptyList() // 清除 File 播放列表
        _currentFolderPath.value = playlistFolderPath
        _currentFolderUri.value = folderUri
        _currentPlaylistId.value = playlistId

        val startIndex = uris.indexOfFirst { it == startUri }.coerceAtLeast(0)
        _currentIndex.value = startIndex
        _currentFilePath.value = startUri.toString()
        // 从 URI 提取文件名
        val fileName = extractFileNameFromUri(startUri)
        val fileNameNoExt = fileName.substringBeforeLast('.')
        _currentDisplayName.value = fileNameNoExt
        _currentFileName.value = fileNameNoExt
        Log.d("PlayerManager", "loadUrisAndPlay: ${uris.size} 个URI, startIndex=$startIndex, fileName=$fileName")

        val mediaItems = uris.map { uri -> createMediaItemFromUri(context, uri) }

        // 启动前台服务 (确保通知栏/锁屏/导航栏控制可用)
        ensureServiceStarted()

        player.apply {
            setMediaItems(mediaItems, startIndex, 0)
            prepare()
        }

        // 加载当前歌曲元信息 (从 URI)
        loadCurrentTrackMetadataFromUri(context, startUri)

        // 恢复上次播放位置 (优先精确匹配，降级按文件名模糊匹配)
        scope.launch {
            try {
                val memory = repository.getMemory(startUri.toString())
                    ?: repository.getMemoryByFileName(fileName) // 降级: 跨 File/URI 模式匹配
                if (memory != null && memory.positionMs > 0) {
                    _player?.seekTo(startIndex, memory.positionMs)
                    Log.d("PlayerManager", "loadUrisAndPlay: 恢复记忆 pos=${memory.positionMs}, key=${memory.filePath}")
                }
                _player?.play()

                // 更新歌单的最近播放时间
                playlistId?.let { repository.updatePlaylistLastPlayed(it) }
            } catch (e: Exception) {
                Log.e("PlayerManager", "loadUrisAndPlay 恢复播放异常: ${e.message}")
            }
        }
    }

    /**
     * 从 URI 创建 MediaItem
     */
    private fun createMediaItemFromUri(context: Context, uri: Uri): MediaItem {
        val fileName = extractFileNameFromUri(uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = getMimeTypeForExtension(ext)
        
        Log.d("PlayerManager", "createMediaItemFromUri: $fileName, ext=$ext, mime=$mimeType, uri=$uri")

        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(fileName.substringBeforeLast('.'))
                    .build()
            )

        if (mimeType != null) {
            builder.setMimeType(mimeType)
        }

        return builder.build()
    }

    /**
     * 根据扩展名获取 MIME 类型
     */
    private fun getMimeTypeForExtension(ext: String): String? {
        return when (ext) {
            "m4s" -> MimeTypes.VIDEO_MP4
            "m4a" -> MimeTypes.VIDEO_MP4
            "aac" -> MimeTypes.AUDIO_AAC
            "mp3" -> MimeTypes.AUDIO_MPEG
            "ogg" -> MimeTypes.AUDIO_OGG
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "opus" -> MimeTypes.AUDIO_OPUS
            "wma" -> "audio/x-ms-wma"
            "mp4" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts" -> MimeTypes.VIDEO_MP2T
            "3gp" -> MimeTypes.VIDEO_H263
            "avi" -> MimeTypes.VIDEO_MP4
            "mov" -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

    /**
     * 从 URI 加载当前歌曲的元信息
     */
    private fun loadCurrentTrackMetadataFromUri(context: Context, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val coverBytes = retriever.embeddedPicture
                val cover = coverBytes?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                }

                withContext(Dispatchers.Main) {
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
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 播放指定索引 (同时支持 File 模式和 URI 模式)
     */
    fun playAt(index: Int) {
        // 优先检查 File 模式播放列表
        val files = _playlist.value
        if (index in files.indices) {
            // 先保存当前位置
            saveCurrentPositionSync()

            _currentIndex.value = index
            _currentFilePath.value = files[index].absolutePath
            _currentDisplayName.value = files[index].nameWithoutExtension
            _currentFileName.value = files[index].nameWithoutExtension

            player.seekTo(index, 0)

            // 加载当前歌曲元信息
            loadCurrentTrackMetadata(files[index])

            // 恢复该文件的播放位置
            scope.launch {
                try {
                    val memory = repository.getMemory(files[index].absolutePath)
                    if (memory != null && memory.positionMs > 0) {
                        _player?.seekTo(index, memory.positionMs)
                    }
                    _player?.play()
                } catch (e: Exception) {
                    Log.e("PlayerManager", "playAt(File) 恢复播放异常: ${e.message}")
                }
            }
            return
        }

        // URI 模式播放列表 (SAF 隐藏文件夹)
        val uris = _uriPlaylist.value
        if (index in uris.indices) {
            // 先保存当前位置
            saveCurrentPositionSync()

            val uri = uris[index]
            _currentIndex.value = index
            _currentFilePath.value = uri.toString()
            val fileName = extractFileNameFromUri(uri)
            val fileNameNoExt = fileName.substringBeforeLast('.')
            _currentDisplayName.value = fileNameNoExt
            _currentFileName.value = fileNameNoExt

            player.seekTo(index, 0)

            // 加载当前歌曲元信息 (从 URI)
            loadCurrentTrackMetadataFromUri(context, uri)

            // 恢复该文件的播放位置 (优先精确匹配，降级按文件名模糊匹配)
            scope.launch {
                try {
                    val memory = repository.getMemory(uri.toString())
                        ?: repository.getMemoryByFileName(fileName) // 降级: 跨 File/URI 模式匹配
                    if (memory != null && memory.positionMs > 0) {
                        _player?.seekTo(index, memory.positionMs)
                        Log.d("PlayerManager", "playAt(URI): 恢复记忆 pos=${memory.positionMs}, key=${memory.filePath}")
                    }
                    _player?.play()
                } catch (e: Exception) {
                    Log.e("PlayerManager", "playAt(URI) 恢复播放异常: ${e.message}")
                }
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
                val p = _player ?: break // player 已释放则停止追踪，避免意外创建新实例
                val pos = p.currentPosition.coerceAtLeast(0)
                val dur = p.duration.coerceAtLeast(0)
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
        try {
            val filePath = _currentFilePath.value ?: return
            val pos = _player?.currentPosition?.coerceAtLeast(0) ?: return
            val dur = _player?.duration?.coerceAtLeast(0) ?: return
            val folder = _currentFolderPath.value ?: ""
            val name = _currentDisplayName.value ?: ""

            // SharedPreferences 快照 (同步，不会丢)
            repository.saveQuickSnapshot(filePath, pos, dur, folder, name)

            // Room 数据库 (异步)
            scope.launch {
                try {
                    repository.saveMemory(filePath, pos, dur, folder, name)
                } catch (e: Exception) {
                    Log.e("PlayerManager", "saveMemory 异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // player 正在释放或 scope 已取消时，安全忽略
            Log.e("PlayerManager", "saveCurrentPositionSync 异常: ${e.message}")
        }
    }

    private suspend fun savePositionToDb(positionMs: Long, durationMs: Long) {
        try {
            val filePath = _currentFilePath.value ?: return
            val folder = _currentFolderPath.value ?: ""
            val name = _currentDisplayName.value ?: ""
            repository.saveMemory(filePath, positionMs, durationMs, folder, name)
        } catch (e: Exception) {
            Log.e("PlayerManager", "savePositionToDb 异常: ${e.message}")
        }
    }

    private fun savePositionToSnapshot(positionMs: Long, durationMs: Long) {
        try {
            val filePath = _currentFilePath.value ?: return
            val folder = _currentFolderPath.value ?: ""
            val name = _currentDisplayName.value ?: ""
            repository.saveQuickSnapshot(filePath, positionMs, durationMs, folder, name)
        } catch (e: Exception) {
            Log.e("PlayerManager", "savePositionToSnapshot 异常: ${e.message}")
        }
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
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val coverBytes = retriever.embeddedPicture
                val cover = coverBytes?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                }

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
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 加载指定文件的元信息 (供 UI 列表显示使用)
     * 在 IO 线程执行，返回 TrackInfo
     */
    suspend fun loadTrackInfo(file: File): TrackInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
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
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 批量加载文件夹下所有音频的元信息 (递归扫描子文件夹)
     */
    suspend fun loadFolderTrackInfos(folderPath: String): List<TrackInfo> = withContext(Dispatchers.IO) {
        val folder = File(folderPath)
        val files = scanAudioFiles(folderPath)

        files.map { file -> loadTrackInfo(file) }
    }

    /**
     * 基于 DocumentFile 的加载 (适用于 Android 11+ SAF 授权的文件夹)
     * 返回带有 fileUri 的 TrackInfo 列表
     */
    suspend fun loadFolderTrackInfos(context: Context, folderUri: Uri): List<TrackInfo> = withContext(Dispatchers.IO) {
        val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
        if (treeDoc == null) {
            android.util.Log.e("PlayerManager", "无法从URI创建DocumentFile: $folderUri")
            return@withContext emptyList()
        }
        
        val uriList = mutableListOf<Uri>()
        scanDocumentFileUrisRecursive(treeDoc, uriList)
        
        uriList.map { uri -> loadTrackInfoFromUri(context, uri) }
    }

    /**
     * 递归扫描 DocumentFile 目录，收集所有音频文件的 URI
     */
    private fun scanDocumentFileUrisRecursive(dir: androidx.documentfile.provider.DocumentFile, result: MutableList<Uri>) {
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                scanDocumentFileUrisRecursive(child, result)
            } else if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in mediaExtensions) {
                    result.add(child.uri)
                }
            }
        }
    }

    /**
     * 从 URI 加载单个文件的元信息
     */
    private suspend fun loadTrackInfoFromUri(context: Context, uri: Uri): TrackInfo = withContext(Dispatchers.IO) {
        // 从 URI 获取文件名 (统一使用 extractFileNameFromUri)
        val fileName = extractFileNameFromUri(uri)
        val nameWithoutExt = fileName.substringBeforeLast('.')
        // 创建占位 File 对象 (使用解码后的文件名，便于 UI 显示和比较)
        val placeholderFile = File(nameWithoutExt)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: nameWithoutExt
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val coverBytes = retriever.embeddedPicture
            val cover = coverBytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }

            TrackInfo(
                file = placeholderFile,
                fileUri = uri,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                coverBitmap = cover
            )
        } catch (e: Exception) {
            TrackInfo(
                file = placeholderFile,
                fileUri = uri,
                title = nameWithoutExt,
                durationMs = 0
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 递归扫描文件夹并返回详细扫描结果 (含 [done]/[pass]/[err] 状态)
     * 用于扫描结果弹窗展示
     * 使用 File API (适用于应用私有目录或已获得存储权限的旧设备)
     */
    fun scanFolderWithResult(folderPath: String): FolderScanResult {
        val items = mutableListOf<ScanResultItem>()
        scanWithResultRecursive(File(folderPath), items)
        val doneCount = items.count { it.status == ScanStatus.DONE }
        val passCount = items.count { it.status == ScanStatus.PASS }
        val errCount = items.count { it.status == ScanStatus.ERR }
        return FolderScanResult(
            items = items,
            totalCount = items.size,
            doneCount = doneCount,
            passCount = passCount,
            errCount = errCount
        )
    }

    /**
     * 基于 DocumentFile 的扫描 (适用于 Android 11+ SAF 授权的文件夹)
     * 解决分区存储限制下 File.listFiles() 返回 null 的问题
     */
    fun scanFolderWithResult(context: android.content.Context, folderUri: android.net.Uri): FolderScanResult {
        val items = mutableListOf<ScanResultItem>()
        val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
        if (treeDoc == null) {
            android.util.Log.e("PlayerManager", "无法从URI创建DocumentFile: $folderUri")
            return FolderScanResult(items, 0, 0, 0, 0)
        }
        scanWithDocumentFileRecursive(treeDoc, items)
        val doneCount = items.count { it.status == ScanStatus.DONE }
        val passCount = items.count { it.status == ScanStatus.PASS }
        val errCount = items.count { it.status == ScanStatus.ERR }
        return FolderScanResult(
            items = items,
            totalCount = items.size,
            doneCount = doneCount,
            passCount = passCount,
            errCount = errCount
        )
    }

    /**
     * 递归扫描 DocumentFile 目录
     */
    private fun scanWithDocumentFileRecursive(dir: androidx.documentfile.provider.DocumentFile, result: MutableList<ScanResultItem>) {
        if (!dir.exists()) {
            android.util.Log.w("PlayerManager", "DocumentFile目录不存在: ${dir.uri}")
            result.add(ScanResultItem(
                fileName = dir.name ?: "unknown",
                filePath = dir.uri.toString(),
                status = ScanStatus.ERR,
                reason = "目录不存在"
            ))
            return
        }
        if (!dir.isDirectory) {
            android.util.Log.w("PlayerManager", "DocumentFile不是目录: ${dir.uri}")
            result.add(ScanResultItem(
                fileName = dir.name ?: "unknown",
                filePath = dir.uri.toString(),
                status = ScanStatus.ERR,
                reason = "不是目录"
            ))
            return
        }

        val children = dir.listFiles()
        android.util.Log.d("PlayerManager", "DocumentFile扫描目录: ${dir.uri}, 文件数: ${children.size}")

        for (child in children) {
            if (child.isDirectory) {
                scanWithDocumentFileRecursive(child, result)
            } else if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in mediaExtensions) {
                    result.add(ScanResultItem(
                        fileName = name,
                        filePath = child.uri.toString(),
                        status = ScanStatus.DONE
                    ))
                } else {
                    result.add(ScanResultItem(
                        fileName = name,
                        filePath = child.uri.toString(),
                        status = ScanStatus.PASS,
                        reason = "非音频格式 (.$ext)"
                    ))
                }
            }
        }
    }

    private fun scanWithResultRecursive(dir: File, result: MutableList<ScanResultItem>) {
        // 调试：检查目录是否存在、是否可读
        if (!dir.exists()) {
            android.util.Log.w("PlayerManager", "扫描目录不存在: ${dir.absolutePath}")
            result.add(ScanResultItem(
                fileName = dir.name,
                filePath = dir.absolutePath,
                status = ScanStatus.ERR,
                reason = "目录不存在"
            ))
            return
        }
        if (!dir.canRead()) {
            android.util.Log.w("PlayerManager", "扫描目录不可读: ${dir.absolutePath}")
            result.add(ScanResultItem(
                fileName = dir.name,
                filePath = dir.absolutePath,
                status = ScanStatus.ERR,
                reason = "目录无读取权限"
            ))
            return
        }
        if (!dir.isDirectory) {
            android.util.Log.w("PlayerManager", "路径不是目录: ${dir.absolutePath}")
            result.add(ScanResultItem(
                fileName = dir.name,
                filePath = dir.absolutePath,
                status = ScanStatus.ERR,
                reason = "路径不是目录"
            ))
            return
        }
        
        val children = dir.listFiles()
        if (children == null) {
            android.util.Log.w("PlayerManager", "listFiles返回null: ${dir.absolutePath}")
            result.add(ScanResultItem(
                fileName = dir.name,
                filePath = dir.absolutePath,
                status = ScanStatus.ERR,
                reason = "无法列出目录内容"
            ))
            return
        }
        
        android.util.Log.d("PlayerManager", "扫描目录: ${dir.absolutePath}, 文件数: ${children.size}")
        
        for (child in children) {
            if (child.isDirectory) {
                scanWithResultRecursive(child, result)
            } else if (child.isFile) {
                val ext = child.extension.lowercase()
                if (ext in mediaExtensions) {
                    // 音频/视频文件，尝试确认可读
                    if (child.canRead()) {
                        result.add(ScanResultItem(
                            fileName = child.name,
                            filePath = child.absolutePath,
                            status = ScanStatus.DONE
                        ))
                    } else {
                        result.add(ScanResultItem(
                            fileName = child.name,
                            filePath = child.absolutePath,
                            status = ScanStatus.ERR,
                            reason = "文件不可读"
                        ))
                    }
                } else {
                    // 非音频文件，跳过
                    result.add(ScanResultItem(
                        fileName = child.name,
                        filePath = child.absolutePath,
                        status = ScanStatus.PASS,
                        reason = "非音频格式 (.$ext)"
                    ))
                }
            } else {
                // 既不是文件也不是目录（可能是符号链接等）
                android.util.Log.d("PlayerManager", "跳过特殊文件: ${child.absolutePath}, exists=${child.exists()}, isFile=${child.isFile}, isDirectory=${child.isDirectory}")
            }
        }
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
     *
     * 关键注意:
     * - M4A 是 MP4 容器包裹 AAC 音频，MIME 应为 VIDEO_MP4 (通用 MP4 容器)，让 ExoPlayer 自动提取音频轨
     * - M4S 是 Fragmented MP4 (DASH 分段)，也使用 VIDEO_MP4，让 FragmentedMp4Extractor 处理
     * - AUDIO_AAC (audio/aac) 仅适用于裸 ADTS 流
     * - 对 M4S/M4A 这类 MP4 容器，用 video/mp4 比 audio/mp4 更可靠，因为 ExoPlayer
     *   的 MP4 Extractor 在 video/mp4 下会同时处理音频和视频轨道
     */
    private fun getMimeTypeForFile(file: File): String? {
        return when (file.extension.lowercase()) {
            "m4s" -> MimeTypes.VIDEO_MP4  // B站缓存 DASH Fragmented MP4，需要 FragmentedMp4Extractor
            "m4a" -> MimeTypes.VIDEO_MP4  // M4A 是 MP4 容器 + AAC 音频，用 video/mp4 让 MP4 Extractor 处理
            "aac" -> MimeTypes.AUDIO_AAC  // 裸 AAC (ADTS) 流
            "mp3" -> MimeTypes.AUDIO_MPEG
            "ogg" -> MimeTypes.AUDIO_OGG
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "opus" -> MimeTypes.AUDIO_OPUS
            "wma" -> "audio/x-ms-wma"
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
     * 解决 m4s / m4a 等格式 ExoPlayer 无法自动识别的问题
     *
     * 关键: 
     * - 使用 Uri.fromFile() 生成 Android 原生 Uri (兼容中文/空格路径)
     * - M4S/M4A 等 MP4 容器文件通过 setMimeType(VIDEO_MP4) 强制 ExoPlayer 使用 MP4/FragmentedMp4 Extractor
     * - 设置 setMediaId 以便精确标识每个媒体
     */
    private fun createMediaItem(file: File): MediaItem {
        val uri = Uri.fromFile(file)  // Android 原生 Uri，兼容性更好
        val mimeType = getMimeTypeForFile(file)
        val ext = file.extension.lowercase()
        Log.d("PlayerManager", "createMediaItem: ${file.name}, ext=$ext, mime=$mimeType, uri=$uri")

        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(file.absolutePath)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(file.nameWithoutExtension)
                    .build()
            )

        // 对于所有已知格式都显式设置 MIME 类型
        if (mimeType != null) {
            builder.setMimeType(mimeType)
        }

        return builder.build()
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
     * 从 URI 提取文件名 (支持 SAF content:// URI 和普通 file:// URI)
     * SAF URI 的 lastPathSegment 格式通常为 "primary:path/to/file.mp3"
     */
    private fun extractFileNameFromUri(uri: Uri): String {
        val lastSegment = uri.lastPathSegment ?: return "unknown"
        // SAF URI: lastPathSegment 可能是 "primary:Download/.hidden/audio.mp3"
        // 需要取最后一个 '/' 后面的部分
        val name = lastSegment.substringAfterLast('/')
        // URL 解码 (处理 %20 等编码字符)
        return try {
            java.net.URLDecoder.decode(name, "UTF-8")
        } catch (e: Exception) {
            name
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
