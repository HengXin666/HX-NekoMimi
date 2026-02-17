package com.hx.nekomimi.ui.bookmark

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
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.player.PlayerManager
import com.hx.nekomimi.ui.player.formatTimeLong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class BookmarkViewModel @Inject constructor(
    val playerManager: PlayerManager,
    private val repository: PlaybackRepository
) : ViewModel() {
    /** 所有书签 */
    val allBookmarks: StateFlow<List<Bookmark>> =
        repository.getAllBookmarks()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun seekToBookmark(bookmark: Bookmark) {
        val currentFile = playerManager.currentFilePath.value
        if (currentFile == bookmark.filePath) {
            playerManager.seekTo(bookmark.positionMs)
        } else {
            // 只有书签所属文件夹与当前播放文件夹相同时，才传递 folderUri
            // 否则传 null 降级到 File API，避免使用错误的 SAF 授权
            val folderUri = if (bookmark.folderPath == playerManager.currentFolderPath.value) {
                playerManager.currentFolderUri.value
            } else {
                null
            }
            playerManager.loadFolderAndPlay(
                bookmark.folderPath,
                bookmark.filePath,
                folderUri = folderUri
            )
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            repository.deleteBookmark(id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: BookmarkViewModel = hiltViewModel()
) {
    val bookmarks by viewModel.allBookmarks.collectAsStateWithLifecycle()
    val currentFile by viewModel.playerManager.currentFilePath.collectAsStateWithLifecycle()
    val positionMs by viewModel.playerManager.positionMs.collectAsStateWithLifecycle()

    // 按文件分组
    val grouped = remember(bookmarks) {
        bookmarks.groupBy { it.displayName }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全部书签 (${bookmarks.size})") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (bookmarks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无书签",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "在听书页面中添加书签",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                grouped.forEach { (displayName, fileBookmarks) ->
                    // 文件名标题
                    item {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(fileBookmarks, key = { it.id }) { bookmark ->
                        val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                        val isCurrent = bookmark.filePath == currentFile
                        val diffSec = if (isCurrent) (bookmark.positionMs - positionMs) / 1000 else null

                        ListItem(
                            headlineContent = {
                                Text(
                                    bookmark.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                val posStr = formatTimeLong(bookmark.positionMs)
                                val dateStr = dateFormat.format(Date(bookmark.createdAt))
                                val text = if (diffSec != null) {
                                    val sign = if (diffSec >= 0) "+" else ""
                                    "距当前 ${sign}${diffSec}s ($posStr) | $dateStr"
                                } else {
                                    "位置 $posStr | $dateStr"
                                }
                                Text(text)
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.Bookmark,
                                    contentDescription = null,
                                    tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.deleteBookmark(bookmark.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            modifier = Modifier.clickable { viewModel.seekToBookmark(bookmark) }
                        )
                    }

                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        }
    }
}
