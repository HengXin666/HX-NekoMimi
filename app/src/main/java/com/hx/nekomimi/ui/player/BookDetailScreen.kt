package com.hx.nekomimi.ui.player

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.hx.nekomimi.data.db.entity.Book
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 章节节点 (树结构)
 */
data class ChapterNode(
    /** 显示名称 */
    val displayName: String,
    /** 文件绝对路径 (文件夹节点为 null) */
    val filePath: String? = null,
    /** 文件 URI (SAF 模式) */
    val fileUri: Uri? = null,
    /** 是否为文件夹 */
    val isFolder: Boolean = false,
    /** 树深度 (用于缩进) */
    val depth: Int = 0,
    /** 在所有音频文件中的扁平索引 (-1 为文件夹) */
    val flatIndex: Int = -1
)

/**
 * 书详情页
 *
 * 原型架构:
 * |    书籍名称     |
 * | > 上次播放位置 < |
 * | -------------- |
 * | List Item 1    |
 * | List Item 2    |
 * | List Item 3    |
 *
 * -> 1. 可看到上次记忆的播放文件和位置
 * -> 2. 显示章节列表 (保留树结构)
 * -> 3. 可以刷新章节列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    repository: PlaybackRepository,
    playerManager: PlayerManager,
    onNavigateBack: () -> Unit,
    onPlayChapter: (Long, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 书信息
    var book by remember { mutableStateOf<Book?>(null) }
    // 章节树列表
    var chapters by remember { mutableStateOf<List<ChapterNode>>(emptyList()) }
    // 加载状态
    var isLoading by remember { mutableStateOf(true) }

    // 加载书信息和章节
    fun loadChapters() {
        scope.launch {
            isLoading = true
            book = repository.getBookById(bookId)
            val b = book ?: return@launch

            chapters = withContext(Dispatchers.IO) {
                val folderUri = b.folderUri?.let { Uri.parse(it) }
                if (folderUri != null) {
                    // SAF 模式
                    scanChaptersFromUri(context, folderUri, playerManager.getSupportedExtensions())
                } else {
                    // File API 模式
                    scanChaptersFromFile(b.folderPath, playerManager.getSupportedExtensions())
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(bookId) { loadChapters() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = book?.title ?: "加载中...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 刷新章节列表
                    IconButton(onClick = { loadChapters() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新章节")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // 上次播放位置 (如果有)
                val b = book
                if (b?.lastPlayedDisplayName != null) {
                    item(key = "last_played") {
                        LastPlayedCard(
                            book = b,
                            chapters = chapters,
                            onResume = { flatIndex ->
                                onPlayChapter(bookId, flatIndex)
                            }
                        )
                    }
                }

                // 章节分隔线
                item(key = "divider") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            text = "  章节列表 (${chapters.count { !it.isFolder }} 个)  ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }
                }

                // 章节树列表
                itemsIndexed(chapters, key = { _, node -> node.displayName + node.depth + node.flatIndex }) { _, node ->
                    if (node.isFolder) {
                        FolderItem(node)
                    } else {
                        ChapterItem(
                            node = node,
                            onClick = { onPlayChapter(bookId, node.flatIndex) }
                        )
                    }
                }

                // 底部间距
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

/**
 * 上次播放位置卡片
 */
@Composable
private fun LastPlayedCard(
    book: Book,
    chapters: List<ChapterNode>,
    onResume: (Int) -> Unit
) {
    // 查找记忆文件对应的 flatIndex
    val flatIndex = remember(book, chapters) {
        chapters.firstOrNull { node ->
            !node.isFolder && (
                node.filePath == book.lastPlayedFilePath ||
                node.fileUri?.toString() == book.lastPlayedFileUri ||
                node.displayName == book.lastPlayedDisplayName
            )
        }?.flatIndex ?: 0
    }

    val positionText = remember(book.lastPlayedPositionMs) {
        formatTime(book.lastPlayedPositionMs)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onResume(flatIndex) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "▶ 继续收听",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${book.lastPlayedDisplayName ?: "未知"} · $positionText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 文件夹项
 */
@Composable
private fun FolderItem(node: ChapterNode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 + node.depth * 24).dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

/**
 * 章节项
 */
@Composable
private fun ChapterItem(
    node: ChapterNode,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (16 + node.depth * 24).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Audiotrack,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// ==================== 辅助函数 ====================

/** 格式化时间 */
private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * 从 File API 扫描章节树
 */
private fun scanChaptersFromFile(
    folderPath: String,
    supportedExts: Set<String>
): List<ChapterNode> {
    val result = mutableListOf<ChapterNode>()
    var flatIndex = 0
    fun scan(dir: File, depth: Int) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        // 先处理文件夹
        for (child in children) {
            if (child.isDirectory) {
                result.add(ChapterNode(displayName = child.name, isFolder = true, depth = depth))
                scan(child, depth + 1)
            }
        }
        // 再处理文件
        for (child in children) {
            if (child.isFile && child.extension.lowercase() in supportedExts) {
                result.add(
                    ChapterNode(
                        displayName = child.nameWithoutExtension,
                        filePath = child.absolutePath,
                        isFolder = false,
                        depth = depth,
                        flatIndex = flatIndex++
                    )
                )
            }
        }
    }
    scan(File(folderPath), 0)
    return result
}

/**
 * 从 SAF URI 扫描章节树
 */
private fun scanChaptersFromUri(
    context: android.content.Context,
    folderUri: Uri,
    supportedExts: Set<String>
): List<ChapterNode> {
    val result = mutableListOf<ChapterNode>()
    var flatIndex = 0
    fun scan(dir: DocumentFile, depth: Int) {
        val children = dir.listFiles().sortedBy { it.name ?: "" }
        for (child in children) {
            if (child.isDirectory) {
                result.add(ChapterNode(displayName = child.name ?: "未知文件夹", isFolder = true, depth = depth))
                scan(child, depth + 1)
            }
        }
        for (child in children) {
            if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in supportedExts) {
                    result.add(
                        ChapterNode(
                            displayName = name.substringBeforeLast('.'),
                            fileUri = child.uri,
                            isFolder = false,
                            depth = depth,
                            flatIndex = flatIndex++
                        )
                    )
                }
            }
        }
    }
    val treeDoc = DocumentFile.fromTreeUri(context, folderUri)
    if (treeDoc != null && treeDoc.exists()) {
        scan(treeDoc, 0)
    }
    return result
}
