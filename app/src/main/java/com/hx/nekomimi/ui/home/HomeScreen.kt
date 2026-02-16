package com.hx.nekomimi.ui.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hx.nekomimi.data.db.entity.MusicPlaylist
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.player.PlayerManager
import com.hx.nekomimi.player.TrackInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ==================== ViewModel ====================

/**
 * 音乐主页 ViewModel
 * 管理歌单列表和当前歌单的歌曲列表
 */
@HiltViewModel
class MusicHomeViewModel @Inject constructor(
    val playerManager: PlayerManager,
    private val repository: PlaybackRepository
) : ViewModel() {

    /** 所有歌单 */
    val playlists = repository.getAllPlaylistsByLastPlayed()

    /** 当前查看的歌单 */
    private val _currentPlaylist = MutableStateFlow<MusicPlaylist?>(null)
    val currentPlaylist: StateFlow<MusicPlaylist?> = _currentPlaylist.asStateFlow()

    /** 当前歌单的歌曲列表 (含元信息) */
    private val _trackInfos = MutableStateFlow<List<TrackInfo>>(emptyList())
    val trackInfos: StateFlow<List<TrackInfo>> = _trackInfos.asStateFlow()

    /** 是否正在加载歌曲元信息 */
    private val _isLoadingTracks = MutableStateFlow(false)
    val isLoadingTracks: StateFlow<Boolean> = _isLoadingTracks.asStateFlow()

    /** 用于删除确认对话框 */
    val showDeleteDialog = mutableStateOf<MusicPlaylist?>(null)

    /** 导入文件夹为歌单 */
    fun importFolder(folderPath: String) {
        viewModelScope.launch {
            val supportedExts = playerManager.getSupportedExtensions()
            val folder = File(folderPath)
            // 递归统计音频文件数量
            val count = countAudioFiles(folder, supportedExts)
            repository.importPlaylist(folderPath, count)
        }
    }

    /** 打开歌单，加载歌曲列表 */
    fun openPlaylist(playlist: MusicPlaylist) {
        _currentPlaylist.value = playlist
        _isLoadingTracks.value = true
        viewModelScope.launch {
            val infos = playerManager.loadFolderTrackInfos(playlist.folderPath)
            _trackInfos.value = infos
            _isLoadingTracks.value = false
            // 更新歌曲数量
            if (infos.size != playlist.trackCount) {
                repository.updatePlaylistTrackCount(playlist.id, infos.size)
            }
        }
    }

    /** 返回歌单列表 */
    fun backToPlaylists() {
        _currentPlaylist.value = null
        _trackInfos.value = emptyList()
    }

    /** 播放歌曲 */
    fun playTrack(trackInfo: TrackInfo) {
        val playlist = _currentPlaylist.value ?: return
        val files = _trackInfos.value.map { it.file }
        playerManager.loadFilesAndPlay(
            files = files,
            filePath = trackInfo.file.absolutePath,
            playlistFolderPath = playlist.folderPath,
            playlistId = playlist.id
        )
    }

    /** 删除歌单 */
    fun deletePlaylist(playlist: MusicPlaylist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist.id)
        }
    }

    /** 刷新歌单歌曲数量 */
    fun refreshPlaylist(playlist: MusicPlaylist) {
        viewModelScope.launch {
            val supportedExts = playerManager.getSupportedExtensions()
            val folder = File(playlist.folderPath)
            val count = countAudioFiles(folder, supportedExts)
            repository.updatePlaylistTrackCount(playlist.id, count)
        }
    }

    private fun countAudioFiles(dir: File, supportedExts: Set<String>): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var count = 0
        val children = dir.listFiles() ?: return 0
        for (child in children) {
            if (child.isFile && child.extension.lowercase() in supportedExts) {
                count++
            } else if (child.isDirectory) {
                count += countAudioFiles(child, supportedExts)
            }
        }
        return count
    }
}

// ==================== UI ====================

/**
 * 音乐主页 - 歌单列表 / 歌单详情（歌曲列表）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHomeScreen(
    onNavigateToPlayer: () -> Unit = {},
    viewModel: MusicHomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val currentPlaylist by viewModel.currentPlaylist.collectAsStateWithLifecycle()
    val currentFile by viewModel.playerManager.currentFilePath.collectAsStateWithLifecycle()

    // 文件夹选择器
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = getPathFromUri(context, it)
            if (path != null) {
                viewModel.importFolder(path)
            }
        }
    }

    // 根据当前状态显示歌单列表 / 歌单详情
    if (currentPlaylist == null) {
        PlaylistListView(
            viewModel = viewModel,
            onNavigateToPlayer = onNavigateToPlayer,
            onImportFolder = { folderPicker.launch(null) },
            currentFile = currentFile
        )
    } else {
        PlaylistDetailView(
            viewModel = viewModel,
            playlist = currentPlaylist!!,
            onNavigateToPlayer = onNavigateToPlayer,
            currentFile = currentFile
        )
    }
}

// ==================== 歌单列表 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistListView(
    viewModel: MusicHomeViewModel,
    onNavigateToPlayer: () -> Unit,
    onImportFolder: () -> Unit,
    currentFile: String?
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val deleteTarget by remember { viewModel.showDeleteDialog }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("🎵 音乐")
                },
                actions = {
                    // 跳转到正在播放
                    if (currentFile != null) {
                        IconButton(onClick = onNavigateToPlayer) {
                            Icon(
                                Icons.Filled.PlayCircle,
                                contentDescription = "正在播放",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // 导入文件夹
                    IconButton(onClick = onImportFolder) {
                        Icon(Icons.Filled.Add, contentDescription = "导入文件夹")
                    }
                }
            )
        }
    ) { padding ->
        if (playlists.isEmpty()) {
            // 空状态引导
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "还没有歌单",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "导入音乐文件夹创建歌单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(onClick = onImportFolder) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入文件夹")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistItem(
                        playlist = playlist,
                        onClick = { viewModel.openPlaylist(playlist) },
                        onLongClick = { viewModel.showDeleteDialog.value = playlist }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { viewModel.showDeleteDialog.value = null },
            title = { Text("删除歌单") },
            text = { Text("确定要删除「${deleteTarget!!.name}」吗？\n（不会删除实际音频文件）") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(deleteTarget!!)
                        viewModel.showDeleteDialog.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDeleteDialog.value = null }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 歌单列表项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistItem(
    playlist: MusicPlaylist,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                playlist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            val folderExists = remember(playlist.folderPath) {
                File(playlist.folderPath).exists()
            }
            Column {
                Text(
                    "${playlist.trackCount} 首歌曲",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!folderExists) {
                    Text(
                        "⚠ 文件夹不存在",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onLongClick) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

// ==================== 歌单详情 (歌曲列表) ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDetailView(
    viewModel: MusicHomeViewModel,
    playlist: MusicPlaylist,
    onNavigateToPlayer: () -> Unit,
    currentFile: String?
) {
    val trackInfos by viewModel.trackInfos.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingTracks.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            playlist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${trackInfos.size} 首歌曲",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.backToPlaylists() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (currentFile != null) {
                        IconButton(onClick = onNavigateToPlayer) {
                            Icon(
                                Icons.Filled.PlayCircle,
                                contentDescription = "正在播放",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "正在加载歌曲信息...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (trackInfos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MusicOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "此文件夹中没有音频文件",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        playlist.folderPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(trackInfos, key = { it.file.absolutePath }) { trackInfo ->
                    TrackInfoItem(
                        trackInfo = trackInfo,
                        isCurrent = currentFile == trackInfo.file.absolutePath,
                        isPlaying = isPlaying && currentFile == trackInfo.file.absolutePath,
                        onClick = {
                            viewModel.playTrack(trackInfo)
                            onNavigateToPlayer()
                        }
                    )
                }
            }
        }
    }
}

/**
 * 歌曲列表项 - 显示封面、标题、歌手、时长
 */
@Composable
private fun TrackInfoItem(
    trackInfo: TrackInfo,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                trackInfo.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            val parts = mutableListOf<String>()
            trackInfo.artist?.let { parts.add(it) }
            trackInfo.album?.let { parts.add(it) }
            if (trackInfo.durationMs > 0) {
                parts.add(formatDuration(trackInfo.durationMs))
            }
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            TrackCover(
                cover = trackInfo.coverBitmap,
                isCurrent = isCurrent,
                isPlaying = isPlaying
            )
        },
        trailingContent = {
            if (isCurrent && isPlaying) {
                Icon(
                    Icons.Filled.Equalizer,
                    contentDescription = "正在播放",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    trackInfo.file.extension.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/**
 * 歌曲封面组件
 */
@Composable
private fun TrackCover(
    cover: Bitmap?,
    isCurrent: Boolean,
    isPlaying: Boolean
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                if (isCurrent && isPlaying) Icons.Filled.PlayCircle
                else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ==================== 工具函数 ====================

/** 格式化时长 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

/**
 * 从 content URI 获取实际文件路径
 */
fun getPathFromUri(context: android.content.Context, uri: Uri): String? {
    val docId = try {
        android.provider.DocumentsContract.getTreeDocumentId(uri)
    } catch (e: Exception) {
        DocumentFile.fromTreeUri(context, uri)?.uri?.lastPathSegment
    } ?: return null
    val parts = docId.split(":")
    return when {
        parts.size >= 2 && parts[0] == "primary" -> {
            "/storage/emulated/0/${parts[1]}"
        }
        parts.size >= 2 -> {
            "/storage/${parts[0]}/${parts[1]}"
        }
        else -> null
    }
}
