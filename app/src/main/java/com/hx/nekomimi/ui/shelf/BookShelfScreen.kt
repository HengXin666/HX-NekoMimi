package com.hx.nekomimi.ui.shelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hx.nekomimi.data.db.entity.Book
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.ui.home.getPathFromUri
import com.hx.nekomimi.ui.player.formatTimeLong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * 排序方式
 */
enum class BookSortOrder {
    /** 按导入日期 */
    IMPORT_DATE,
    /** 按最近更新 */
    LAST_UPDATED
}

@HiltViewModel
class BookShelfViewModel @Inject constructor(
    private val repository: PlaybackRepository
) : ViewModel() {

    private val _sortOrder = MutableStateFlow(BookSortOrder.LAST_UPDATED)
    val sortOrder: StateFlow<BookSortOrder> = _sortOrder.asStateFlow()

    /** 按排序方式获取书列表 */
    val books: StateFlow<List<Book>> = _sortOrder
        .flatMapLatest { order ->
            when (order) {
                BookSortOrder.IMPORT_DATE -> repository.getAllBooksByImportDate()
                BookSortOrder.LAST_UPDATED -> repository.getAllBooksByLastUpdated()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val toastMessage = MutableStateFlow<String?>(null)

    fun setSortOrder(order: BookSortOrder) {
        _sortOrder.value = order
    }

    /** 导入一本书 (文件夹) */
    fun importBook(folderPath: String) {
        viewModelScope.launch {
            val book = repository.importBook(folderPath)
            toastMessage.value = "已导入: ${book.title}"
        }
    }

    /** 删除书 */
    fun deleteBook(book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book.id)
            toastMessage.value = "已移除: ${book.title}"
        }
    }

    fun clearToast() { toastMessage.value = null }
}

/**
 * 听书根页面 - 书架
 * 显示所有导入的有声书，支持按导入日期/最近更新排序
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookShelfScreen(
    onNavigateToBookDetail: (String) -> Unit = {},
    viewModel: BookShelfViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val books by viewModel.books.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearToast()
        }
    }

    // 文件夹选择器 (导入新书)
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = getPathFromUri(context, it)
            if (path != null) {
                viewModel.importBook(path)
            }
        }
    }

    // 长按删除确认对话框
    var bookToDelete by remember { mutableStateOf<Book?>(null) }

    if (bookToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("移除有声书") },
            text = { Text("确定要从书架移除「${bookToDelete!!.title}」吗？\n（不会删除实际文件）") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(bookToDelete!!)
                    bookToDelete = null
                }) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("📚 书架")
                        Text(
                            "${books.size} 本有声书",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 排序按钮
                    var showSortMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Filled.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sortOrder == BookSortOrder.LAST_UPDATED) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("最近更新")
                                }
                            },
                            onClick = {
                                viewModel.setSortOrder(BookSortOrder.LAST_UPDATED)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sortOrder == BookSortOrder.IMPORT_DATE) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("导入日期")
                                }
                            },
                            onClick = {
                                viewModel.setSortOrder(BookSortOrder.IMPORT_DATE)
                                showSortMenu = false
                            }
                        )
                    }

                    // 导入按钮
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Filled.Add, contentDescription = "导入有声书")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (books.isEmpty()) {
            // 空书架引导页
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "书架空空如也",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击右上角 + 导入有声书文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入有声书")
                    }
                }
            }
        } else {
            // 书架网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onNavigateToBookDetail(book.folderPath) },
                        onLongClick = { bookToDelete = book }
                    )
                }
            }
        }
    }
}

/**
 * 书卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 统计文件夹内音频数量
    val audioCount = remember(book.folderPath) {
        val dir = File(book.folderPath)
        if (dir.exists() && dir.isDirectory) {
            countAudioFiles(dir)
        } else 0
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 上半部分 - 书名和图标
            Column {
                // 书图标
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 书名
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 下半部分 - 信息
            Column {
                // 音频数量
                Text(
                    text = "$audioCount 个音频",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 上次播放信息
                if (book.lastPlayedDisplayName != null) {
                    Text(
                        text = "▶ ${book.lastPlayedDisplayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatTimeLong(book.lastPlayedPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }

                // 更新时间
                Text(
                    text = dateFormat.format(Date(book.lastUpdatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * 递归统计文件夹内所有音频文件数量
 */
private val AUDIO_EXTS = setOf(
    "mp3", "wav", "m4a", "ogg", "flac", "aac", "wma", "opus", "ape", "alac",
    "mp4", "mkv", "webm", "avi", "mov", "ts", "3gp"
)

private fun countAudioFiles(dir: File): Int {
    var count = 0
    dir.listFiles()?.forEach { file ->
        if (file.isFile && file.extension.lowercase() in AUDIO_EXTS) {
            count++
        } else if (file.isDirectory) {
            count += countAudioFiles(file)
        }
    }
    return count
}
