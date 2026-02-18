package com.hx.nekomimi.ui.player

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hx.nekomimi.data.db.entity.Book
import com.hx.nekomimi.data.db.entity.PlaybackMemory
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.player.FolderScanResult
import com.hx.nekomimi.player.PlayerManager
import com.hx.nekomimi.ui.home.ScanResultDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * 文件夹项 (子文件夹或音频文件)
 * 同时支持 File API 和 DocumentFile API (SAF)
 */
data class FolderItem(
    val file: File,
    val isDirectory: Boolean,
    val audioCount: Int = 0, // 子文件夹内音频数量
    /** DocumentFile URI (SAF 模式下使用) */
    val documentUri: android.net.Uri? = null
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    val playerManager: PlayerManager,
    private val repository: PlaybackRepository
) : ViewModel() {

    private val supportedExtensions = playerManager.getSupportedExtensions()

    /** 当前书的信息 */
    val book = MutableStateFlow<Book?>(null)

    /** 书的 folderUri (SAF 授权的 URI) */
    val folderUri = MutableStateFlow<android.net.Uri?>(null)

    /** 当前浏览的文件夹路径 */
    val currentBrowsePath = MutableStateFlow<String?>(null)

    /** 书的根文件夹路径 */
    val rootFolderPath = MutableStateFlow<String?>(null)

    /** 当前文件夹下的内容 */
    val folderItems = MutableStateFlow<List<FolderItem>>(emptyList())

    /** 书的记忆位置 */
    val lastMemory = MutableStateFlow<PlaybackMemory?>(null)

    /** 编辑对话框状态 */
    val showEditDialog = MutableStateFlow(false)
    val editTitle = MutableStateFlow("")
    val editDescription = MutableStateFlow("")

    val toastMessage = MutableStateFlow<String?>(null)

    /** 扫描结果弹窗 */
    private val _scanResult = MutableStateFlow<FolderScanResult?>(null)
    val scanResult: StateFlow<FolderScanResult?> = _scanResult.asStateFlow()

    /** 是否正在扫描 */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun dismissScanResult() { _scanResult.value = null }

    /** 刷新当前文件夹: 重新递归扫描并显示扫描结果弹窗 */
    fun refreshCurrentFolder(context: android.content.Context) {
        val path = rootFolderPath.value ?: return
        val uri = folderUri.value
        viewModelScope.launch {
            _isScanning.value = true
            val result = withContext(Dispatchers.IO) {
                // 优先使用 URI 方式扫描 (支持隐藏文件夹)
                if (uri != null) {
                    playerManager.scanFolderWithResult(context, uri)
                } else {
                    playerManager.scanFolderWithResult(path)
                }
            }
            _scanResult.value = result
            _isScanning.value = false
            // 刷新当前浏览的文件夹内容
            currentBrowsePath.value?.let { loadFolderContent(it) }
        }
    }

    /** 用于删除音频文件确认对话框 */
    val showDeleteFileDialog = MutableStateFlow<File?>(null)

    /** 删除音频文件 (删除实际文件) */
    fun deleteAudioFile(file: File) {
        viewModelScope.launch {
            try {
                if (file.exists() && file.delete()) {
                    toastMessage.value = "已删除: ${file.name}"
                    // 刷新当前文件夹内容
                    currentBrowsePath.value?.let { loadFolderContent(it) }
                } else {
                    toastMessage.value = "删除失败"
                }
            } catch (e: Exception) {
                toastMessage.value = "删除失败: ${e.message}"
            }
        }
    }

    /**
     * 初始化: 加载书的信息和文件列表
     */
    fun loadBook(folderPath: String) {
        rootFolderPath.value = folderPath
        currentBrowsePath.value = folderPath

        viewModelScope.launch {
            // 确保书存在于数据库
            val existingBook = repository.getBook(folderPath)
                ?: repository.importBook(folderPath)
            book.value = existingBook
            // 加载 folderUri
            folderUri.value = existingBook.folderUri?.let { android.net.Uri.parse(it) }
            editTitle.value = existingBook.title
            editDescription.value = existingBook.description

            // 加载最近播放记忆
            existingBook.lastPlayedFilePath?.let { filePath ->
                lastMemory.value = repository.getMemory(filePath)
            }

            // 在 folderUri 加载完成后再加载文件夹内容 (确保 DocumentFile API 可用)
            loadFolderContent(folderPath)
        }
    }

    /**
     * 加载文件夹内容
     * 优先使用 DocumentFile API (SAF) 以支持分区存储和隐藏文件夹
     * 如果 DocumentFile API 不可用，则降级使用 File API
     */
    fun loadFolderContent(path: String) {
        currentBrowsePath.value = path

        // 优先尝试 DocumentFile API (SAF)
        val uri = folderUri.value
        if (uri != null) {
            loadFolderContentViaSAF(path, uri)
            return
        }

        // 降级: 使用 File API
        loadFolderContentViaFile(path)
    }

    /**
     * 通过 DocumentFile API 加载文件夹内容 (支持分区存储)
     */
    private fun loadFolderContentViaSAF(path: String, rootUri: android.net.Uri) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                val result = mutableListOf<FolderItem>()
                val rootPath = rootFolderPath.value ?: path

                // 计算相对路径 (如果浏览的是子文件夹)
                val relativePath = if (path != rootPath) {
                    path.removePrefix(rootPath).trimStart('/')
                } else {
                    ""
                }

                // 从 rootUri 导航到目标子文件夹
                val targetDoc = if (relativePath.isEmpty()) {
                    DocumentFile.fromTreeUri(appContext, rootUri)
                } else {
                    var currentDoc = DocumentFile.fromTreeUri(appContext, rootUri)
                    for (segment in relativePath.split("/")) {
                        currentDoc = currentDoc?.listFiles()?.find {
                            it.name == segment && it.isDirectory
                        }
                        if (currentDoc == null) break
                    }
                    currentDoc
                }

                if (targetDoc == null || !targetDoc.exists()) {
                    // SAF 无法导航到目标，降级到 File API
                    return@withContext loadFolderContentViaFileSync(path)
                }

                val children = targetDoc.listFiles()

                // 子文件夹
                children
                    .filter { it.isDirectory }
                    .sortedBy { it.name }
                    .forEach { folder ->
                        val name = folder.name ?: return@forEach
                        val count = countAudioFilesRecursiveDoc(folder)
                        if (count > 0) {
                            // 创建 File 对象用于兼容现有 UI
                            val folderFile = File(path, name)
                            result.add(FolderItem(
                                file = folderFile,
                                isDirectory = true,
                                audioCount = count,
                                documentUri = folder.uri
                            ))
                        }
                    }

                // 音频文件
                children
                    .filter { it.isFile }
                    .sortedBy { it.name }
                    .forEach { file ->
                        val name = file.name ?: return@forEach
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in supportedExtensions) {
                            val audioFile = File(path, name)
                            result.add(FolderItem(
                                file = audioFile,
                                isDirectory = false,
                                documentUri = file.uri
                            ))
                        }
                    }

                result
            }
            folderItems.value = items
        }
    }

    /**
     * 通过 File API 加载文件夹内容 (降级方案)
     */
    private fun loadFolderContentViaFile(path: String) {
        folderItems.value = loadFolderContentViaFileSync(path)
    }

    /**
     * 同步 File API 加载 (可被协程调用)
     */
    private fun loadFolderContentViaFileSync(path: String): List<FolderItem> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }

        val children = dir.listFiles() ?: emptyArray()
        val items = mutableListOf<FolderItem>()

        // 子文件夹
        children
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .forEach { folder ->
                val count = countAudioFilesRecursive(folder)
                if (count > 0) {
                    items.add(FolderItem(folder, isDirectory = true, audioCount = count))
                }
            }

        // 音频文件
        children
            .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
            .sortedBy { it.name }
            .forEach { file ->
                items.add(FolderItem(file, isDirectory = false))
            }

        return items
    }

    /**
     * 递归统计 DocumentFile 下的音频文件数量
     */
    private fun countAudioFilesRecursiveDoc(dir: DocumentFile): Int {
        var count = 0
        for (child in dir.listFiles()) {
            if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in supportedExtensions) count++
            } else if (child.isDirectory) {
                count += countAudioFilesRecursiveDoc(child)
            }
        }
        return count
    }

    /**
     * 返回上级目录 (不超过根目录)
     */
    fun navigateUp(): Boolean {
        val current = currentBrowsePath.value ?: return false
        val root = rootFolderPath.value ?: return false
        if (current == root) return false

        val parent = File(current).parentFile
        if (parent != null && parent.absolutePath.startsWith(root)) {
            loadFolderContent(parent.absolutePath)
            return true
        }
        return false
    }

    /**
     * 播放指定文件 (并设置听书模式)
     * @param item 文件夹项 (包含 File 和可选的 documentUri)
     */
    fun playFile(item: FolderItem) {
        val browsePath = currentBrowsePath.value ?: return
        playerManager.setAudioBookMode(true)
        playerManager.loadFolderAndPlay(
            browsePath,
            item.file.absolutePath,
            folderUri = folderUri.value,
            targetUri = item.documentUri
        )

        // 更新书的最近播放信息
        val root = rootFolderPath.value ?: return
        viewModelScope.launch {
            repository.updateBookLastPlayed(
                folderPath = root,
                filePath = item.file.absolutePath,
                fileUri = item.documentUri?.toString(),
                positionMs = 0,
                durationMs = 0,
                displayName = item.file.nameWithoutExtension
            )
        }
    }

    /**
     * 从记忆位置继续播放
     *
     * SAF 模式: 使用 rootPath + folderUri 从根目录递归扫描 (SAF 必须从 tree URI 根开始)
     * File API 模式: 同样使用 rootPath 递归扫描 (保持行为一致，确保播放列表包含所有音频)
     */
    fun resumeFromMemory() {
        val bookVal = book.value ?: return
        val filePath = bookVal.lastPlayedFilePath ?: return
        val rootPath = rootFolderPath.value ?: return
        val uri = folderUri.value

        // SAF 模式: 不做 File.exists() 检查（分区存储下 File API 可能无法访问）
        if (uri != null) {
            // 优先使用存储的 fileUri 精确定位目标文件，避免特殊字符文件名匹配失败
            val targetUri = bookVal.lastPlayedFileUri?.let { android.net.Uri.parse(it) }
            playerManager.setAudioBookMode(true)
            playerManager.loadFolderAndPlay(rootPath, filePath, folderUri = uri, targetUri = targetUri)
            return
        }

        // File API 模式: 先检查文件是否存在
        val file = File(filePath)
        if (!file.exists()) {
            toastMessage.value = "文件不存在"
            return
        }

        // 使用 rootPath 递归扫描 (与 SAF 模式保持一致，播放列表包含书的所有音频)
        playerManager.setAudioBookMode(true)
        playerManager.loadFolderAndPlay(rootPath, filePath, folderUri = null)
    }

    /**
     * 打开编辑对话框
     */
    fun openEditDialog() {
        val b = book.value ?: return
        editTitle.value = b.title
        editDescription.value = b.description
        showEditDialog.value = true
    }

    /**
     * 保存编辑
     */
    fun saveEdit() {
        val b = book.value ?: return
        viewModelScope.launch {
            repository.updateBookInfo(b.id, editTitle.value, editDescription.value)
            book.value = repository.getBookById(b.id)
            showEditDialog.value = false
            toastMessage.value = "已保存"
        }
    }

    fun clearToast() { toastMessage.value = null }

    private fun countAudioFilesRecursive(dir: File): Int {
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.lowercase() in supportedExtensions) {
                count++
            } else if (file.isDirectory) {
                count += countAudioFilesRecursive(file)
            }
        }
        return count
    }
}

/**
 * 书详情页
 * 上方: 书信息卡片 (可编辑) + 记忆位置
 * 下方: 文件夹视图 (支持进入子文件夹)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    folderPath: String,
    onNavigateToPlayer: () -> Unit = {},
    onNavigateToBookmarks: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val currentBrowsePath by viewModel.currentBrowsePath.collectAsStateWithLifecycle()
    val rootFolderPath by viewModel.rootFolderPath.collectAsStateWithLifecycle()
    val folderItems by viewModel.folderItems.collectAsStateWithLifecycle()
    val lastMemory by viewModel.lastMemory.collectAsStateWithLifecycle()
    val showEditDialog by viewModel.showEditDialog.collectAsStateWithLifecycle()
    val editTitle by viewModel.editTitle.collectAsStateWithLifecycle()
    val editDescription by viewModel.editDescription.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()
    val currentFile by viewModel.playerManager.currentFilePath.collectAsStateWithLifecycle()
    val currentFileName by viewModel.playerManager.currentFileName.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearToast()
        }
    }

    // 初始化加载
    LaunchedEffect(folderPath) {
        viewModel.loadBook(folderPath)
    }

    // 拦截系统返回键: 如果在子文件夹中，先返回上级目录；否则才退出页面
    val isAtRoot = currentBrowsePath == rootFolderPath
    BackHandler(enabled = !isAtRoot) {
        viewModel.navigateUp()
    }

    // 删除音频文件确认对话框
    val deleteFileTarget by viewModel.showDeleteFileDialog.collectAsStateWithLifecycle()
    deleteFileTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.showDeleteFileDialog.value = null },
            title = { Text("删除音频文件") },
            text = { Text("确定要删除「${target.name}」吗？\n\n⚠ 此操作将删除实际文件，不可恢复！") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAudioFile(target)
                        viewModel.showDeleteFileDialog.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDeleteFileDialog.value = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 编辑对话框
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showEditDialog.value = false },
            title = { Text("编辑书信息") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { viewModel.editTitle.value = it },
                        label = { Text("书名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { viewModel.editDescription.value = it },
                        label = { Text("描述 (可选)") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveEdit() }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showEditDialog.value = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        book?.title ?: "加载中...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 正在播放按钮
                    if (currentFile != null) {
                        IconButton(onClick = onNavigateToPlayer) {
                            Icon(
                                Icons.Filled.PlayCircle,
                                contentDescription = "正在播放",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // 刷新按钮
                    val context = LocalContext.current
                    IconButton(onClick = { viewModel.refreshCurrentFolder(context) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新扫描",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 书签按钮
                    IconButton(onClick = onNavigateToBookmarks) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "书签")
                    }
                    // 编辑按钮
                    IconButton(onClick = { viewModel.openEditDialog() }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ========== 书信息卡片 ==========
            item {
                BookInfoCard(
                    book = book,
                    lastMemory = lastMemory,
                    onResume = {
                        viewModel.resumeFromMemory()
                        onNavigateToPlayer()
                    },
                    onEdit = { viewModel.openEditDialog() }
                )
            }

            // ========== 文件夹路径面包屑 ==========
            item {
                val isAtRoot = currentBrowsePath == rootFolderPath
                if (!isAtRoot) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.navigateUp() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = "返回上级",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        val relativePath = currentBrowsePath
                            ?.removePrefix(rootFolderPath ?: "")
                            ?.trimStart('/')
                            ?: ""
                        Text(
                            text = "📂 $relativePath",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            // ========== 文件列表 ==========
            if (folderItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "此文件夹中没有音频文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(folderItems, key = { it.file.absolutePath }) { item ->
                if (item.isDirectory) {
                    // 子文件夹
                    ListItem(
                        headlineContent = {
                            Text(
                                item.file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        supportingContent = {
                            Text("${item.audioCount} 个音频")
                        },
                        trailingContent = {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.loadFolderContent(item.file.absolutePath)
                        }
                    )
                } else {
                    // 音频文件 (用文件名匹配，兼容 SAF URI 模式)
                    val isCurrent = currentFileName != null &&
                            currentFileName == item.file.nameWithoutExtension
                    ListItem(
                        headlineContent = {
                            Text(
                                item.file.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isCurrent) FontWeight.Bold else null
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (isCurrent && isPlaying) Icons.Filled.PlayCircle
                                else Icons.Filled.AudioFile,
                                contentDescription = null,
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { viewModel.showDeleteFileDialog.value = item.file },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.playFile(item)
                            onNavigateToPlayer()
                        }
                    )
                }
            }
        }
    }

    // 扫描结果弹窗 (必须放在 Scaffold 外部，确保 Dialog 不受 content 区域约束)
    val scanResultVal by viewModel.scanResult.collectAsStateWithLifecycle()
    val isScanningVal by viewModel.isScanning.collectAsStateWithLifecycle()
    ScanResultDialog(
        scanResult = scanResultVal,
        isScanning = isScanningVal,
        onDismiss = { viewModel.dismissScanResult() }
    )
}

/**
 * 书信息卡片
 * 显示书名、描述、记忆位置信息，支持继续播放
 */
@Composable
fun BookInfoCard(
    book: Book?,
    lastMemory: PlaybackMemory?,
    onResume: () -> Unit,
    onEdit: () -> Unit
) {
    if (book == null) return

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 书名和编辑按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (book.description.isNotBlank()) {
                        Text(
                            book.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 导入时间
            Text(
                "导入于 ${dateFormat.format(Date(book.importedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            // 记忆位置 - 继续播放
            if (book.lastPlayedFilePath != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "📍 上次收听",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            book.lastPlayedDisplayName ?: "未知",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (book.lastPlayedPositionMs > 0) {
                            Text(
                                "进度: ${formatTimeLong(book.lastPlayedPositionMs)} / ${formatTimeLong(book.lastPlayedDurationMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FilledTonalButton(onClick = onResume) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("继续")
                    }
                }
            }
        }
    }
}
