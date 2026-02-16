package com.hx.nekomimi.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hx.nekomimi.data.db.entity.Bookmark
import com.hx.nekomimi.data.db.entity.PlaybackMemory
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class BookPlayerViewModel @Inject constructor(
    val playerManager: PlayerManager,
    private val repository: PlaybackRepository
) : ViewModel() {

    /** 当前文件的书签列表 */
    val bookmarks: StateFlow<List<Bookmark>> = playerManager.currentFilePath
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { repository.getBookmarks(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 当前文件的播放记忆 */
    val currentMemory = MutableStateFlow<PlaybackMemory?>(null)

    /** 所有文件夹下的播放记忆历史 */
    val memoryHistory: StateFlow<List<PlaybackMemory>> = playerManager.currentFolderPath
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { repository.getMemoriesByFolder(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Toast 消息 */
    val toastMessage = MutableStateFlow<String?>(null)

    init {
        // 监听文件变化，加载记忆
        viewModelScope.launch {
            playerManager.currentFilePath.filterNotNull().distinctUntilChanged().collect { path ->
                currentMemory.value = repository.getMemory(path)
            }
        }
    }

    /** 添加书签 */
    fun addBookmark() {
        val filePath = playerManager.currentFilePath.value ?: return
        val posMs = playerManager.positionMs.value
        val durMs = playerManager.durationMs.value
        val folder = playerManager.currentFolderPath.value ?: ""
        val name = playerManager.currentDisplayName.value ?: ""

        viewModelScope.launch {
            val label = "书签 ${formatTimeLong(posMs)}"
            repository.addBookmark(filePath, posMs, durMs, label, folder, name)
            toastMessage.value = "书签已添加: $label"
        }
    }

    /** 手动触发记忆当前位置 */
    fun saveMemoryManually() {
        viewModelScope.launch {
            val result = playerManager.saveMemoryManually()
            if (result != null) {
                currentMemory.value = repository.getMemory(result.filePath)
                toastMessage.value = "播放位置已记忆: ${formatTimeLong(result.positionMs)}"
            }
        }
    }

    /** 跳转到书签位置 */
    fun seekToBookmark(bookmark: Bookmark) {
        playerManager.seekTo(bookmark.positionMs)
    }

    /** 跳转到记忆位置 */
    fun seekToMemory(memory: PlaybackMemory) {
        // 如果是当前文件，直接跳转
        if (memory.filePath == playerManager.currentFilePath.value) {
            playerManager.seekTo(memory.positionMs)
        } else {
            // 切换文件并跳转
            playerManager.loadFolderAndPlay(memory.folderPath, memory.filePath)
        }
    }

    /** 删除书签 */
    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            repository.deleteBookmark(id)
            toastMessage.value = "书签已删除"
        }
    }

    fun clearToast() { toastMessage.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPlayerScreen(viewModel: BookPlayerViewModel = hiltViewModel()) {
    val pm = viewModel.playerManager
    val isPlaying by pm.isPlaying.collectAsStateWithLifecycle()
    val positionMs by pm.positionMs.collectAsStateWithLifecycle()
    val durationMs by pm.durationMs.collectAsStateWithLifecycle()
    val displayName by pm.currentDisplayName.collectAsStateWithLifecycle()
    val currentFile by pm.currentFilePath.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val currentMemory by viewModel.currentMemory.collectAsStateWithLifecycle()
    val memoryHistory by viewModel.memoryHistory.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearToast()
        }
    }

    // Tab 切换: 书签 / 记忆历史
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        displayName ?: "听书",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    // 手动记忆按钮
                    IconButton(
                        onClick = { viewModel.saveMemoryManually() },
                        enabled = currentFile != null
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "记忆位置")
                    }
                    // 添加书签
                    IconButton(
                        onClick = { viewModel.addBookmark() },
                        enabled = currentFile != null
                    ) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = "添加书签")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (currentFile == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "从主页选择有声书文件夹开始收听",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // 当前记忆信息卡片
                if (currentMemory != null) {
                    MemoryInfoCard(
                        memory = currentMemory!!,
                        currentPositionMs = positionMs,
                        onClick = { viewModel.seekToMemory(currentMemory!!) }
                    )
                }

                // Tab 栏
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("书签 (${bookmarks.size})") },
                        icon = { Icon(Icons.Filled.Bookmark, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("记忆历史") },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) }
                    )
                }

                // Tab 内容
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> BookmarkList(
                            bookmarks = bookmarks,
                            currentPositionMs = positionMs,
                            onSeek = { viewModel.seekToBookmark(it) },
                            onDelete = { viewModel.deleteBookmark(it.id) }
                        )
                        1 -> MemoryHistoryList(
                            memories = memoryHistory,
                            currentPositionMs = positionMs,
                            currentFilePath = currentFile,
                            onSeek = { viewModel.seekToMemory(it) }
                        )
                    }
                }

                // 播放控制栏 (复用音乐页的)
                PlayerControls(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onPlayPause = { if (isPlaying) pm.pause() else pm.play() },
                    onSeek = { pm.seekTo(it) },
                    onPrevious = { pm.previous() },
                    onNext = { pm.next() }
                )
            }
        }
    }
}

/**
 * 当前播放记忆信息卡片
 */
@Composable
fun MemoryInfoCard(
    memory: PlaybackMemory,
    currentPositionMs: Long,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val savedTimeStr = dateFormat.format(Date(memory.savedAt))
    val diffSec = (memory.positionMs - currentPositionMs) / 1000

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Restore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "上次记忆位置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    buildMemoryDescription(diffSec, memory.positionMs, savedTimeStr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Text(
                "跳转",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 构建记忆描述文本
 * 格式: "距当前 +30s (01:25:30) | 记忆于 02-15 14:30:22"
 */
private fun buildMemoryDescription(diffSec: Long, positionMs: Long, savedTimeStr: String): String {
    val sign = if (diffSec >= 0) "+" else ""
    val absDiff = abs(diffSec)
    val diffStr = when {
        absDiff < 60 -> "${sign}${diffSec}s"
        absDiff < 3600 -> "${sign}${diffSec / 60}m${absDiff % 60}s"
        else -> "${sign}${diffSec / 3600}h${(absDiff % 3600) / 60}m"
    }
    return "距当前 $diffStr (${formatTimeLong(positionMs)}) | 记忆于 $savedTimeStr"
}

/**
 * 书签列表
 */
@Composable
fun BookmarkList(
    bookmarks: List<Bookmark>,
    currentPositionMs: Long,
    onSeek: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit
) {
    if (bookmarks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "暂无书签\n点击右上角 + 添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(bookmarks, key = { it.id }) { bookmark ->
                val diffSec = (bookmark.positionMs - currentPositionMs) / 1000
                val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

                ListItem(
                    headlineContent = { Text(bookmark.label) },
                    supportingContent = {
                        val sign = if (diffSec >= 0) "+" else ""
                        Text(
                            "距当前 ${sign}${diffSec}s (${formatTimeLong(bookmark.positionMs)}) | ${dateFormat.format(Date(bookmark.createdAt))}"
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onDelete(bookmark) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除书签",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.clickable { onSeek(bookmark) }
                )
            }
        }
    }
}

/**
 * 播放记忆历史列表
 */
@Composable
fun MemoryHistoryList(
    memories: List<PlaybackMemory>,
    currentPositionMs: Long,
    currentFilePath: String?,
    onSeek: (PlaybackMemory) -> Unit
) {
    if (memories.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无播放记忆",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(memories, key = { it.id }) { memory ->
                val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
                val isCurrent = memory.filePath == currentFilePath
                val diffSec = if (isCurrent) (memory.positionMs - currentPositionMs) / 1000 else null

                ListItem(
                    headlineContent = {
                        Text(
                            memory.displayName,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold
                            else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        val posStr = formatTimeLong(memory.positionMs)
                        val savedStr = dateFormat.format(Date(memory.savedAt))
                        val text = if (diffSec != null) {
                            val sign = if (diffSec >= 0) "+" else ""
                            "距当前 ${sign}${diffSec}s ($posStr) | 记忆于 $savedStr"
                        } else {
                            "位置 $posStr | 记忆于 $savedStr"
                        }
                        Text(text)
                    },
                    leadingContent = {
                        Icon(
                            if (isCurrent) Icons.Filled.PlayCircle else Icons.Filled.History,
                            contentDescription = null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { onSeek(memory) }
                )
            }
        }
    }
}
