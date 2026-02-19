package com.hx.nekomimi.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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
 * 播放器管理器 (纯听书模式)
 *
 * 管理 ExoPlayer 实例，提供播放控制和状态监听
 * 功能:
 * - 加载文件夹 (递归扫描 mp3)，生成播放列表
 * - 播放、暂停、快进、快退、上一章、下一章
 * - 每 3 秒自动保存播放位置 (Room + SP 双写)
 * - 每 5 分钟发出记忆保存事件 (UI 提示)
 * - 支持 SAF URI 模式 (隐藏文件夹)
 */
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlaybackRepository
) {
    companion object {
        private const val TAG = "PlayerManager"
        /** 听书模式 5 分钟自动保存间隔 */
        private const val AUTO_SAVE_INTERVAL_MS = 5 * 60 * 1000L
        /** 快进/快退步长 (秒) */
        const val SEEK_STEP_MS = 10_000L
    }

    private var _player: ExoPlayer? = null
    val player: ExoPlayer
        get() = _player ?: createPlayer().also { _player = it }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null

    // ==================== 支持的媒体格式 ====================

    private val mediaExtensions = setOf(
        "mp3", "wav", "m4a", "ogg", "flac", "aac", "wma", "opus", "ape", "alac",
        "m4s", "mp4", "mkv", "webm", "avi", "mov", "ts", "3gp"
    )

    // ==================== 播放状态 ====================

    /** 当前播放文件路径 (File 模式为绝对路径, URI 模式为 URI 字符串) */
    private val _currentFilePath = MutableStateFlow<String?>(null)
    val currentFilePath: StateFlow<String?> = _currentFilePath.asStateFlow()

    /** 当前播放文件夹路径 */
    private val _currentFolderPath = MutableStateFlow<String?>(null)
    val currentFolderPath: StateFlow<String?> = _currentFolderPath.asStateFlow()

    /** 当前文件夹的 SAF URI */
    private val _currentFolderUri = MutableStateFlow<Uri?>(null)
    val currentFolderUri: StateFlow<Uri?> = _currentFolderUri.asStateFlow()

    /** 当前章节显示名称 (mp3 文件名，不含扩展名) */
    private val _currentDisplayName = MutableStateFlow<String?>(null)
    val currentDisplayName: StateFlow<String?> = _currentDisplayName.asStateFlow()

    /** 当前文件的原始文件名 (不含扩展名, 用于字幕匹配) */
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

    /** 播放列表 (File API 模式) */
    private val _playlist = MutableStateFlow<List<File>>(emptyList())
    val playlist: StateFlow<List<File>> = _playlist.asStateFlow()

    /** URI 播放列表 (SAF 模式) */
    private val _uriPlaylist = MutableStateFlow<List<Uri>>(emptyList())
    val uriPlaylist: StateFlow<List<Uri>> = _uriPlaylist.asStateFlow()

    /** 当前播放索引 */
    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // ==================== 记忆保存事件 ====================

    /** 自动记忆保存事件 (UI 监听此事件显示提示) */
    private val _memorySaveEvent = MutableSharedFlow<MemorySaveEvent>(extraBufferCapacity = 1)
    val memorySaveEvent: SharedFlow<MemorySaveEvent> = _memorySaveEvent.asSharedFlow()

    /** 累计播放时间计数器 (毫秒), 用于 5 分钟记忆 */
    private var playedMs = 0L

    // ==================== 前台服务管理 ====================

    private var serviceStarted = false

    // ==================== Player 创建 ====================

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build().apply {
                // 听书模式: 顺序播放全部章节
                repeatMode = Player.REPEAT_MODE_OFF
                shuffleModeEnabled = false

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) {
                            startPositionTracking()
                        } else {
                            stopPositionTracking()
                            saveCurrentPositionSync()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            _durationMs.value = duration.coerceAtLeast(0)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            saveCurrentPositionSync()
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val idx = currentMediaItemIndex
                        _currentIndex.value = idx

                        // File 模式
                        val files = _playlist.value
                        if (idx in files.indices) {
                            val file = files[idx]
                            _currentFilePath.value = file.absolutePath
                            _currentDisplayName.value = file.nameWithoutExtension
                            _currentFileName.value = file.nameWithoutExtension
                            return
                        }

                        // URI 模式
                        val uris = _uriPlaylist.value
                        if (idx in uris.indices) {
                            val uri = uris[idx]
                            _currentFilePath.value = uri.toString()
                            val fileName = extractFileNameFromUri(uri)
                            val fileNameNoExt = fileName.substringBeforeLast('.')
                            _currentDisplayName.value = fileNameNoExt
                            _currentFileName.value = fileNameNoExt
                        }
                    }
                })
            }
    }

    // ==================== 文件扫描 ====================

    /** 获取支持的媒体扩展名 */
    fun getSupportedExtensions(): Set<String> = mediaExtensions

    /** 递归扫描文件夹下所有音频文件 (File API) */
    fun scanAudioFiles(folderPath: String): List<File> {
        val result = mutableListOf<File>()
        scanRecursive(File(folderPath), result)
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

    /** 递归扫描 DocumentFile 目录收集 URI */
    private fun scanDocumentFileUrisRecursive(dir: DocumentFile, result: MutableList<Uri>) {
        for (child in dir.listFiles()) {
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

    // ==================== 播放控制 ====================

    /**
     * 加载文件夹并播放指定文件 (递归扫描)
     * SAF URI 优先, 降级 File API
     */
    fun loadFolderAndPlay(
        folderPath: String,
        filePath: String,
        folderUri: Uri? = null,
        targetUri: Uri? = null
    ) {
        scope.launch {
            // 优先 SAF 模式
            if (folderUri != null) {
                val uriList = withContext(Dispatchers.IO) {
                    val treeDoc = DocumentFile.fromTreeUri(context, folderUri)
                    if (treeDoc != null && treeDoc.exists()) {
                        val list = mutableListOf<Uri>()
                        scanDocumentFileUrisRecursive(treeDoc, list)
                        if (list.isNotEmpty()) list else null
                    } else null
                }

                if (uriList != null) {
                    val startUri = (if (targetUri != null) {
                        uriList.firstOrNull { it == targetUri }
                    } else null)
                        ?: run {
                            val targetFileName = File(filePath).name
                            uriList.firstOrNull { uri ->
                                extractFileNameFromUri(uri).equals(targetFileName, ignoreCase = true)
                            } ?: uriList.first()
                        }

                    loadUrisAndPlay(uriList, startUri, folderPath, folderUri)
                    return@launch
                }
            }

            // 降级 File API
            val files = withContext(Dispatchers.IO) { scanAudioFiles(folderPath) }
            if (files.isEmpty()) {
                Log.w(TAG, "扫描到 0 个文件: $folderPath")
                return@launch
            }

            _playlist.value = files
            _uriPlaylist.value = emptyList()
            _currentFolderPath.value = folderPath
            _currentFolderUri.value = folderUri

            val startIndex = files.indexOfFirst { it.absolutePath == filePath }.coerceAtLeast(0)
            _currentIndex.value = startIndex
            _currentFilePath.value = files.getOrNull(startIndex)?.absolutePath
            _currentDisplayName.value = files.getOrNull(startIndex)?.nameWithoutExtension
            _currentFileName.value = files.getOrNull(startIndex)?.nameWithoutExtension

            val mediaItems = files.map { createMediaItem(it) }
            ensureServiceStarted()

            try {
                player.apply {
                    setMediaItems(mediaItems, startIndex, 0)
                    prepare()
                }
            } catch (e: Exception) {
                Log.e(TAG, "setMediaItems 异常: ${e.message}")
                return@launch
            }

            // 恢复播放位置
            try {
                val memory = repository.getMemory(filePath)
                if (memory != null && memory.positionMs > 0) {
                    _player?.seekTo(startIndex, memory.positionMs)
                }
                _player?.play()
            } catch (e: Exception) {
                Log.e(TAG, "恢复播放异常: ${e.message}")
            }
        }
    }

    /** URI 模式加载并播放 */
    private fun loadUrisAndPlay(
        uris: List<Uri>,
        startUri: Uri,
        folderPath: String,
        folderUri: Uri?
    ) {
        _uriPlaylist.value = uris
        _playlist.value = emptyList()
        _currentFolderPath.value = folderPath
        _currentFolderUri.value = folderUri

        val startIndex = uris.indexOfFirst { it == startUri }.coerceAtLeast(0)
        _currentIndex.value = startIndex
        _currentFilePath.value = startUri.toString()
        val fileName = extractFileNameFromUri(startUri)
        val fileNameNoExt = fileName.substringBeforeLast('.')
        _currentDisplayName.value = fileNameNoExt
        _currentFileName.value = fileNameNoExt

        val mediaItems = uris.map { createMediaItemFromUri(it) }
        ensureServiceStarted()

        try {
            player.apply {
                setMediaItems(mediaItems, startIndex, 0)
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "URI setMediaItems 异常: ${e.message}")
            return
        }

        scope.launch {
            try {
                val memory = repository.getMemory(startUri.toString())
                    ?: repository.getMemoryByFileName(fileName)
                if (memory != null && memory.positionMs > 0) {
                    _player?.seekTo(startIndex, memory.positionMs)
                }
                _player?.play()
            } catch (e: Exception) {
                Log.e(TAG, "URI 恢复播放异常: ${e.message}")
            }
        }
    }

    /** 播放指定索引 */
    fun playAt(index: Int) {
        // File 模式
        val files = _playlist.value
        if (index in files.indices) {
            saveCurrentPositionSync()
            _currentIndex.value = index
            _currentFilePath.value = files[index].absolutePath
            _currentDisplayName.value = files[index].nameWithoutExtension
            _currentFileName.value = files[index].nameWithoutExtension

            try { _player?.seekTo(index, 0) } catch (e: Exception) {
                Log.e(TAG, "playAt seekTo 异常: ${e.message}")
            }

            scope.launch {
                try {
                    val memory = repository.getMemory(files[index].absolutePath)
                    if (memory != null && memory.positionMs > 0) {
                        _player?.seekTo(index, memory.positionMs)
                    }
                    _player?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "playAt 恢复异常: ${e.message}")
                }
            }
            return
        }

        // URI 模式
        val uris = _uriPlaylist.value
        if (index in uris.indices) {
            saveCurrentPositionSync()
            val uri = uris[index]
            _currentIndex.value = index
            _currentFilePath.value = uri.toString()
            val fileName = extractFileNameFromUri(uri)
            val fileNameNoExt = fileName.substringBeforeLast('.')
            _currentDisplayName.value = fileNameNoExt
            _currentFileName.value = fileNameNoExt

            try { _player?.seekTo(index, 0) } catch (e: Exception) {
                Log.e(TAG, "playAt URI seekTo 异常: ${e.message}")
            }

            scope.launch {
                try {
                    val memory = repository.getMemory(uri.toString())
                        ?: repository.getMemoryByFileName(fileName)
                    if (memory != null && memory.positionMs > 0) {
                        _player?.seekTo(index, memory.positionMs)
                    }
                    _player?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "playAt URI 恢复异常: ${e.message}")
                }
            }
        }
    }

    fun play() {
        try {
            ensureServiceStarted()
            player.play()
        } catch (e: Exception) {
            Log.e(TAG, "play() 异常: ${e.message}")
        }
    }

    fun pause() {
        try { _player?.pause() } catch (e: Exception) {
            Log.e(TAG, "pause() 异常: ${e.message}")
        }
    }

    fun seekTo(positionMs: Long) {
        try { _player?.seekTo(positionMs) } catch (e: Exception) {
            Log.e(TAG, "seekTo() 异常: ${e.message}")
        }
    }

    /** 快进 */
    fun seekForward() {
        try {
            val p = _player ?: return
            val newPos = (p.currentPosition + SEEK_STEP_MS).coerceAtMost(p.duration)
            p.seekTo(newPos)
        } catch (e: Exception) {
            Log.e(TAG, "seekForward() 异常: ${e.message}")
        }
    }

    /** 快退 */
    fun seekBackward() {
        try {
            val p = _player ?: return
            val newPos = (p.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)
            p.seekTo(newPos)
        } catch (e: Exception) {
            Log.e(TAG, "seekBackward() 异常: ${e.message}")
        }
    }

    /** 下一章 */
    fun next() {
        try {
            val p = _player ?: return
            if (p.hasNextMediaItem()) {
                saveCurrentPositionSync()
                p.seekToNext()
            }
        } catch (e: Exception) {
            Log.e(TAG, "next() 异常: ${e.message}")
        }
    }

    /** 上一章 */
    fun previous() {
        try {
            val p = _player ?: return
            if (p.hasPreviousMediaItem()) {
                saveCurrentPositionSync()
                p.seekToPrevious()
            }
        } catch (e: Exception) {
            Log.e(TAG, "previous() 异常: ${e.message}")
        }
    }

    // ==================== 位置跟踪与保存 ====================

    /**
     * 启动位置追踪:
     * - 每 300ms 更新 UI 状态
     * - 每 3 秒保存到数据库 + SP 快照
     * - 每 5 分钟触发记忆保存事件 (UI 提示)
     */
    private fun startPositionTracking() {
        positionSaveJob?.cancel()
        positionSaveJob = scope.launch {
            var saveCounter = 0
            while (isActive) {
                val p = _player ?: break
                val pos = p.currentPosition.coerceAtLeast(0)
                val dur = p.duration.coerceAtLeast(0)
                _positionMs.value = pos
                _durationMs.value = dur

                saveCounter++
                // 每 3 秒保存一次 (300ms * 10)
                if (saveCounter >= 10) {
                    saveCounter = 0
                    savePositionToDb(pos, dur)
                }

                // 每次写入 SP 快照
                savePositionToSnapshot(pos, dur)

                // 5 分钟记忆事件
                if (_isPlaying.value) {
                    playedMs += 300
                    if (playedMs >= AUTO_SAVE_INTERVAL_MS) {
                        playedMs = 0L
                        savePositionToDb(pos, dur)
                        val filePath = _currentFilePath.value
                        val displayName = _currentDisplayName.value
                        if (filePath != null && displayName != null) {
                            _memorySaveEvent.tryEmit(
                                MemorySaveEvent(filePath, pos, displayName, isAutoSave = true)
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

            repository.saveQuickSnapshot(filePath, pos, dur, folder, name)

            scope.launch {
                try {
                    repository.saveMemory(filePath, pos, dur, folder, name)
                } catch (e: Exception) {
                    Log.e(TAG, "saveMemory 异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveCurrentPositionSync 异常: ${e.message}")
        }
    }

    private suspend fun savePositionToDb(positionMs: Long, durationMs: Long) {
        try {
            val filePath = _currentFilePath.value ?: return
            val folder = _currentFolderPath.value ?: ""
            val name = _currentDisplayName.value ?: ""
            repository.saveMemory(filePath, positionMs, durationMs, folder, name)
        } catch (e: Exception) {
            Log.e(TAG, "savePositionToDb 异常: ${e.message}")
        }
    }

    private fun savePositionToSnapshot(positionMs: Long, durationMs: Long) {
        try {
            val filePath = _currentFilePath.value ?: return
            val folder = _currentFolderPath.value ?: ""
            val name = _currentDisplayName.value ?: ""
            repository.saveQuickSnapshot(filePath, positionMs, durationMs, folder, name)
        } catch (e: Exception) {
            Log.e(TAG, "savePositionToSnapshot 异常: ${e.message}")
        }
    }

    /** 手动保存记忆 (用户按钮) */
    suspend fun saveMemoryManually(): Boolean {
        return try {
            val filePath = _currentFilePath.value ?: return false
            val pos = _player?.currentPosition?.coerceAtLeast(0) ?: return false
            val dur = _player?.duration?.coerceAtLeast(0) ?: return false
            val folder = _currentFolderPath.value ?: ""
            val name = _currentDisplayName.value ?: ""
            repository.saveMemory(filePath, pos, dur, folder, name)

            _memorySaveEvent.tryEmit(
                MemorySaveEvent(filePath, pos, name, isAutoSave = false)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveMemoryManually 异常: ${e.message}")
            false
        }
    }

    // ==================== MediaItem 构建 ====================

    private fun createMediaItem(file: File): MediaItem {
        val uri = Uri.fromFile(file)
        val mimeType = getMimeType(file.extension.lowercase())

        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(file.absolutePath)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(file.nameWithoutExtension)
                    .build()
            )

        if (mimeType != null) builder.setMimeType(mimeType)
        return builder.build()
    }

    private fun createMediaItemFromUri(uri: Uri): MediaItem {
        val fileName = extractFileNameFromUri(uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = getMimeType(ext)

        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(fileName.substringBeforeLast('.'))
                    .build()
            )

        if (mimeType != null) builder.setMimeType(mimeType)
        return builder.build()
    }

    private fun getMimeType(ext: String): String? {
        return when (ext) {
            "m4s", "m4a", "mp4", "avi", "mov" -> MimeTypes.VIDEO_MP4
            "aac" -> MimeTypes.AUDIO_AAC
            "mp3" -> MimeTypes.AUDIO_MPEG
            "ogg" -> MimeTypes.AUDIO_OGG
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "opus" -> MimeTypes.AUDIO_OPUS
            "wma" -> "audio/x-ms-wma"
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts" -> MimeTypes.VIDEO_MP2T
            "3gp" -> MimeTypes.VIDEO_H263
            else -> null
        }
    }

    // ==================== 辅助方法 ====================

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
            Log.e(TAG, "启动服务失败: ${e.message}")
        }
    }

    /** 从 URI 提取文件名 */
    fun extractFileNameFromUri(uri: Uri): String {
        val lastSegment = uri.lastPathSegment ?: return "unknown"
        val name = lastSegment.substringAfterLast('/')
        return try {
            java.net.URLDecoder.decode(name, "UTF-8")
        } catch (e: Exception) {
            name
        }
    }

    /** 释放播放器资源 */
    fun release() {
        try {
            saveCurrentPositionSync()
            stopPositionTracking()
            _player?.release()
            _player = null
            serviceStarted = false
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        } catch (e: Exception) {
            Log.e(TAG, "release() 异常: ${e.message}")
        }
    }
}
