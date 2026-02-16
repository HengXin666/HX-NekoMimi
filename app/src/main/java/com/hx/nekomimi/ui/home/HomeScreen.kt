package com.hx.nekomimi.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hx.nekomimi.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

/**
 * 音乐主页 ViewModel
 * 使用 PlayerManager 的 getSupportedExtensions() 获取支持的格式
 */
@HiltViewModel
class MusicHomeViewModel @Inject constructor(
    val playerManager: PlayerManager
) : ViewModel() {
    /** 当前选中的文件夹路径 */
    val currentFolder = mutableStateOf<String?>(null)

    /** 文件列表 */
    val audioFiles = mutableStateOf<List<File>>(emptyList())

    /** 是否显示子文件夹列表 */
    val subFolders = mutableStateOf<List<File>>(emptyList())

    private val supportedExtensions = playerManager.getSupportedExtensions()

    fun loadFolder(path: String) {
        currentFolder.value = path
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            audioFiles.value = emptyList()
            subFolders.value = emptyList()
            return
        }

        val children = dir.listFiles() ?: emptyArray()
        subFolders.value = children
            .filter { it.isDirectory }
            .sortedBy { it.name }
        audioFiles.value = children
            .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
            .sortedBy { it.name }
    }

    fun playFile(file: File) {
        val folder = currentFolder.value ?: return
        playerManager.loadFolderAndPlay(folder, file.absolutePath)
    }
}

/**
 * 音乐主页 - 文件夹浏览与音频文件选择
 * @param onNavigateToPlayer 点击播放后跳转到播放页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHomeScreen(
    onNavigateToPlayer: () -> Unit = {},
    viewModel: MusicHomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val currentFolder by remember { viewModel.currentFolder }
    val audioFiles by remember { viewModel.audioFiles }
    val subFolders by remember { viewModel.subFolders }
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val currentFile by viewModel.playerManager.currentFilePath.collectAsStateWithLifecycle()

    // 文件夹选择器
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = getPathFromUri(context, it)
            if (path != null) {
                viewModel.loadFolder(path)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🎵 音乐")
                        if (currentFolder != null) {
                            Text(
                                text = currentFolder!!.substringAfterLast("/"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // 跳转到播放页
                    if (currentFile != null) {
                        IconButton(onClick = onNavigateToPlayer) {
                            Icon(
                                Icons.Filled.PlayCircle,
                                contentDescription = "正在播放",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "选择文件夹")
                    }
                }
            )
        }
    ) { padding ->
        if (currentFolder == null) {
            // 未选择文件夹时的引导页
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "选择一个音乐文件夹开始播放",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("选择文件夹")
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
                // 返回上级目录
                item {
                    val parent = File(currentFolder!!).parentFile
                    if (parent != null && parent.canRead()) {
                        ListItem(
                            headlineContent = { Text("..") },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.ArrowBack,
                                    contentDescription = "返回上级",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.loadFolder(parent.absolutePath)
                            }
                        )
                        HorizontalDivider()
                    }
                }

                // 子文件夹
                items(subFolders, key = { it.absolutePath }) { folder ->
                    val supportedExts = viewModel.playerManager.getSupportedExtensions()
                    ListItem(
                        headlineContent = {
                            Text(
                                folder.name,
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
                            val count = folder.listFiles()
                                ?.count { it.isFile && it.extension.lowercase() in supportedExts }
                                ?: 0
                            if (count > 0) Text("$count 个音频")
                        },
                        modifier = Modifier.clickable {
                            viewModel.loadFolder(folder.absolutePath)
                        }
                    )
                }

                if (subFolders.isNotEmpty() && audioFiles.isNotEmpty()) {
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                }

                // 音频文件
                items(audioFiles, key = { it.absolutePath }) { file ->
                    val isCurrent = currentFile == file.absolutePath
                    ListItem(
                        headlineContent = {
                            Text(
                                file.nameWithoutExtension,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (isCurrent && isPlaying) Icons.Filled.PlayCircle
                                else Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        supportingContent = {
                            Text(
                                file.extension.uppercase(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.playFile(file)
                            onNavigateToPlayer()
                        }
                    )
                }

                if (audioFiles.isEmpty() && subFolders.isEmpty()) {
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
            }
        }
    }
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
