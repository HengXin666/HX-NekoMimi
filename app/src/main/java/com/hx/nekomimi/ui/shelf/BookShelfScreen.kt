package com.hx.nekomimi.ui.shelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hx.nekomimi.data.db.entity.Book
import com.hx.nekomimi.data.repository.PlaybackRepository
import kotlinx.coroutines.launch

/**
 * 书架主界面
 *
 * 原型架构:
 * [打开] -> 主界面: 显示封面 or 书籍名称卡片
 *         -> 添加书籍: 选择文件(夹) -> 递归扫描 mp3
 *         -> 删除书籍
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookShelfScreen(
    repository: PlaybackRepository,
    onBookClick: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 书籍列表 (按最近更新排序)
    val books by repository.getAllBooksByLastUpdated().collectAsState(initial = emptyList())

    // 待删除确认的书
    var bookToDelete by remember { mutableStateOf<Book?>(null) }

    // SAF 文件夹选择器
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        // 持久化 URI 权限
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {}

        // 从 SAF URI 提取文件夹路径
        val folderPath = extractFolderPath(uri)

        scope.launch {
            repository.importBook(folderPath, folderUri = uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "🐱 NekoMimi 书架",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // 添加书籍按钮
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "添加书籍")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (books.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "书架是空的",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击右上角 + 添加有声书文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            // 书籍卡片列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onBookClick(book.id) },
                        onDeleteClick = { bookToDelete = book }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    if (bookToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("删除书籍") },
            text = { Text("确定要删除「${bookToDelete!!.title}」吗？\n（不会删除实际文件）") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteBook(bookToDelete!!.id)
                            bookToDelete = null
                        }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 书籍卡片
 */
@Composable
private fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 书籍图标
            Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            // 书籍信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.lastPlayedDisplayName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "📍 ${book.lastPlayedDisplayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 删除按钮
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * 从 SAF URI 提取文件夹路径
 */
private fun extractFolderPath(uri: Uri): String {
    val path = uri.lastPathSegment ?: return uri.toString()
    // SAF URI lastPathSegment 格式: "primary:Download/MyBooks"
    return if (path.contains(':')) {
        val parts = path.split(':')
        val storage = if (parts[0] == "primary") "/storage/emulated/0" else "/storage/${parts[0]}"
        if (parts.size > 1 && parts[1].isNotEmpty()) "$storage/${parts[1]}" else storage
    } else {
        path
    }
}
