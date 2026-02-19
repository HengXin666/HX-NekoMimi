package com.hx.nekomimi.ui.player

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.hx.nekomimi.data.db.entity.Book
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.player.MemorySaveEvent
import com.hx.nekomimi.player.PlayerManager
import com.hx.nekomimi.subtitle.AssRenderer
import com.hx.nekomimi.subtitle.SubtitleManager
import com.hx.nekomimi.subtitle.model.SubtitleCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 播放页面
 *
 * 原型架构:
 * -> 显示章节名称 (mp3 文件名)
 * -> 显示字幕 (SRT / ASS)
 * -> 显示进度条
 * -> 显示播放按钮 (播放、暂停、快进、快退)
 * -> [后台功能: 自动记忆播放进度]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPlayerScreen(
    bookId: Long,
    chapterIndex: Int,
    repository: PlaybackRepository,
    playerManager: PlayerManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 播放状态
    val isPlaying by playerManager.isPlaying.collectAsState()
    val positionMs by playerManager.positionMs.collectAsState()
    val durationMs by playerManager.durationMs.collectAsState()
    val currentDisplayName by playerManager.currentDisplayName.collectAsState()
    val currentFileName by playerManager.currentFileName.collectAsState()
    val currentFolderUri by playerManager.currentFolderUri.collectAsState()

    // 字幕状态
    var subtitleResult by remember { mutableStateOf<SubtitleManager.SubtitleResult>(SubtitleManager.SubtitleResult.None) }
    var srtCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }

    // ASS 渲染器
    var assRenderer by remember { mutableStateOf<AssRenderer?>(null) }
    var assRenderResult by remember { mutableStateOf<AssRenderer.RenderResult?>(null) }

    // 记忆保存提示
    var saveStatusText by remember { mutableStateOf<String?>(null) }

    // 启动播放
    LaunchedEffect(bookId, chapterIndex) {
        val book = repository.getBookById(bookId) ?: return@LaunchedEffect
        val folderUri = book.folderUri?.let { Uri.parse(it) }

        // 扫描章节列表
        val audioFiles = withContext(Dispatchers.IO) {
            if (folderUri != null) {
                val treeDoc = DocumentFile.fromTreeUri(context, folderUri)
                if (treeDoc != null && treeDoc.exists()) {
                    val uris = mutableListOf<Uri>()
                    scanUrisRecursive(treeDoc, playerManager.getSupportedExtensions(), uris)
                    uris
                } else emptyList()
            } else {
                playerManager.scanAudioFiles(book.folderPath).map { Uri.fromFile(it) }
            }
        }

        if (audioFiles.isEmpty() || chapterIndex !in audioFiles.indices) return@LaunchedEffect

        val targetUri = audioFiles[chapterIndex]
        val targetFilePath = if (folderUri != null) targetUri.toString()
        else File(targetUri.path ?: "").absolutePath

        playerManager.loadFolderAndPlay(
            folderPath = book.folderPath,
            filePath = targetFilePath,
            folderUri = folderUri,
            targetUri = if (folderUri != null) targetUri else null
        )

        // 更新书的最近播放信息
        val displayName = if (folderUri != null) {
            playerManager.extractFileNameFromUri(targetUri).substringBeforeLast('.')
        } else {
            File(targetFilePath).nameWithoutExtension
        }
        repository.updateBookLastPlayed(
            folderPath = book.folderPath,
            filePath = targetFilePath,
            fileUri = targetUri.toString(),
            positionMs = 0,
            durationMs = 0,
            displayName = displayName
        )
    }

    // 加载字幕
    LaunchedEffect(currentFileName) {
        val fileName = currentFileName ?: return@LaunchedEffect
        subtitleResult = withContext(Dispatchers.IO) {
            val folderUriVal = currentFolderUri
            if (folderUriVal != null) {
                SubtitleManager.loadForAudioFromUri(context, folderUriVal, "$fileName.mp3")
            } else {
                val filePath = playerManager.currentFilePath.value ?: return@withContext SubtitleManager.SubtitleResult.None
                SubtitleManager.loadForAudio(filePath)
            }
        }
        when (val result = subtitleResult) {
            is SubtitleManager.SubtitleResult.Srt -> {
                srtCues = result.cues
                // 释放旧的 ASS 渲染器
                assRenderer?.destroy()
                assRenderer = null
                assRenderResult = null
            }
            is SubtitleManager.SubtitleResult.Ass -> {
                srtCues = emptyList()
                // 初始化 ASS 渲染器
                withContext(Dispatchers.IO) {
                    if (AssRenderer.isAvailable) {
                        val renderer = AssRenderer()
                        if (renderer.init()) {
                            renderer.setFrameSize(1920, 1080)
                            if (renderer.loadTrack(result.rawContent)) {
                                assRenderer?.destroy()
                                assRenderer = renderer
                            } else {
                                renderer.destroy()
                            }
                        } else {
                            renderer.destroy()
                        }
                    }
                }
            }
            else -> {
                srtCues = emptyList()
                assRenderer?.destroy()
                assRenderer = null
                assRenderResult = null
            }
        }
    }

    // ASS 渲染循环
    LaunchedEffect(assRenderer) {
        val renderer = assRenderer ?: return@LaunchedEffect
        while (true) {
            val pos = playerManager.positionMs.value
            val result = withContext(Dispatchers.IO) {
                try {
                    renderer.renderFrame(pos)
                } catch (_: Exception) { null }
            }
            assRenderResult = result
            delay(100)
        }
    }

    // 监听记忆保存事件
    LaunchedEffect(Unit) {
        playerManager.memorySaveEvent.collect { event ->
            saveStatusText = if (event.isAutoSave) "正在保存位置..." else "💾 手动保存..."
            delay(1000)
            saveStatusText = "✓ 已保存"
            delay(2000)
            saveStatusText = null
        }
    }

    // 持续更新书的播放位置
    LaunchedEffect(positionMs, currentDisplayName) {
        val book = repository.getBookById(bookId) ?: return@LaunchedEffect
        val filePath = playerManager.currentFilePath.value ?: return@LaunchedEffect
        val fileUri = playerManager.currentFilePath.value
        val name = currentDisplayName ?: return@LaunchedEffect
        if (positionMs > 0 && positionMs % 3000 < 350) {
            repository.updateBookLastPlayed(
                folderPath = book.folderPath,
                filePath = filePath,
                fileUri = fileUri,
                positionMs = positionMs,
                durationMs = durationMs,
                displayName = name
            )
        }
    }

    // 释放 ASS 渲染器
    DisposableEffect(Unit) {
        onDispose {
            assRenderer?.destroy()
            assRenderer = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentDisplayName ?: "播放中",
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
                    // 手动保存按钮
                    IconButton(onClick = {
                        scope.launch { playerManager.saveMemoryManually() }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "保存位置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 保存提示
            if (saveStatusText != null) {
                Text(
                    text = saveStatusText!!,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 字幕区域 (占据大部分空间)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                when {
                    // ASS 字幕渲染
                    assRenderResult != null -> {
                        val result = assRenderResult!!
                        val bitmap = result.bitmap
                        if (!bitmap.isRecycled) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "字幕",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                            )
                        }
                    }
                    // SRT 字幕列表
                    srtCues.isNotEmpty() -> {
                        SrtSubtitleList(
                            cues = srtCues,
                            positionMs = positionMs
                        )
                    }
                    // 无字幕
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Headphones,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = currentDisplayName ?: "",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            // 进度条
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                    onValueChange = { fraction ->
                        playerManager.seekTo((fraction * durationMs).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                // 时间显示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTimePlayer(positionMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatTimePlayer(durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // 播放控制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 上一章
                IconButton(onClick = { playerManager.previous() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一章",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 快退 10s
                IconButton(onClick = { playerManager.seekBackward() }) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = "快退10秒",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 播放/暂停
                FilledIconButton(
                    onClick = {
                        if (isPlaying) playerManager.pause() else playerManager.play()
                    },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // 快进 10s
                IconButton(onClick = { playerManager.seekForward() }) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = "快进10秒",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 下一章
                IconButton(onClick = { playerManager.next() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一章",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * SRT 字幕列表 (歌词式滚动)
 */
@Composable
private fun SrtSubtitleList(
    cues: List<SubtitleCue>,
    positionMs: Long
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(positionMs, cues) {
        SubtitleManager.findCurrentIndex(cues, positionMs)
    }

    // 自动滚动到当前字幕行
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -200
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(cues) { index, cue ->
                val isActive = index == currentIndex
                val alpha = if (isActive) 1f else 0.4f
                val color by animateColorAsState(
                    targetValue = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    label = "subtitle_color"
                )

                Text(
                    text = cue.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(alpha)
                        .padding(horizontal = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = if (isActive) 18.sp else 15.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = color
                )
            }
        }

        // 顶部渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            Color.Transparent
                        )
                    )
                )
        )

        // 底部渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )
    }
}

/** 格式化播放器时间 */
private fun formatTimePlayer(ms: Long): String {
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

/** 递归扫描 URI 列表 */
private fun scanUrisRecursive(dir: DocumentFile, supportedExts: Set<String>, result: MutableList<Uri>) {
    for (child in dir.listFiles().sortedBy { it.name ?: "" }) {
        if (child.isDirectory) {
            scanUrisRecursive(child, supportedExts, result)
        } else if (child.isFile) {
            val name = child.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in supportedExts) {
                result.add(child.uri)
            }
        }
    }
}
