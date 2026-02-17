package com.hx.nekomimi.ui.player

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hx.nekomimi.player.PlayMode
import com.hx.nekomimi.player.PlayerManager
import com.hx.nekomimi.subtitle.SubtitleManager
import com.hx.nekomimi.subtitle.model.AssEffect
import com.hx.nekomimi.subtitle.model.AssStyle
import com.hx.nekomimi.subtitle.model.SubtitleCue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    val playerManager: PlayerManager
) : ViewModel() {
    /** 字幕数据 */
    val subtitleResult = MutableStateFlow<SubtitleManager.SubtitleResult>(SubtitleManager.SubtitleResult.None)
    val cues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val assStyles = MutableStateFlow<Map<String, AssStyle>>(emptyMap())

    init {
        // 监听当前文件变化，自动加载字幕
        viewModelScope.launch {
            playerManager.currentFilePath.filterNotNull().distinctUntilChanged().collect { path ->
                val result = SubtitleManager.loadForAudio(path)
                subtitleResult.value = result
                when (result) {
                    is SubtitleManager.SubtitleResult.Ass -> {
                        cues.value = result.cues
                        assStyles.value = result.styles
                    }
                    is SubtitleManager.SubtitleResult.Srt -> {
                        cues.value = result.cues
                        assStyles.value = emptyMap()
                    }
                    SubtitleManager.SubtitleResult.None -> {
                        cues.value = emptyList()
                        assStyles.value = emptyMap()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(
    onNavigateBack: (() -> Unit)? = null,
    viewModel: MusicPlayerViewModel = hiltViewModel()
) {
    val pm = viewModel.playerManager
    val context = LocalContext.current
    val isPlaying by pm.isPlaying.collectAsStateWithLifecycle()
    val positionMs by pm.positionMs.collectAsStateWithLifecycle()
    val durationMs by pm.durationMs.collectAsStateWithLifecycle()
    val displayName by pm.currentDisplayName.collectAsStateWithLifecycle()
    val currentFile by pm.currentFilePath.collectAsStateWithLifecycle()
    val currentArtist by pm.currentArtist.collectAsStateWithLifecycle()
    val currentAlbum by pm.currentAlbum.collectAsStateWithLifecycle()
    val currentCover by pm.currentCover.collectAsStateWithLifecycle()
    val playMode by pm.playMode.collectAsStateWithLifecycle()
    val cues by viewModel.cues.collectAsStateWithLifecycle()
    val assStyles by viewModel.assStyles.collectAsStateWithLifecycle()
    val subtitleResult by viewModel.subtitleResult.collectAsStateWithLifecycle()

    // 是否显示文件位置信息 BottomSheet
    var showFileInfoSheet by remember { mutableStateOf(false) }

    val currentIndex = remember(cues, positionMs) {
        SubtitleManager.findCurrentIndex(cues, positionMs)
    }

    // 歌词列表自动滚动
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && cues.isNotEmpty()) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -200
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            displayName ?: "音乐播放",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        // 显示歌手信息
                        val subtitleText = buildList {
                            currentArtist?.let { add(it) }
                            currentAlbum?.let { add(it) }
                            if (cues.isNotEmpty()) {
                                val subtitleType = when (subtitleResult) {
                                    is SubtitleManager.SubtitleResult.Ass -> "ASS 歌词"
                                    is SubtitleManager.SubtitleResult.Srt -> "SRT 歌词"
                                    else -> ""
                                }
                                add("🎤 $subtitleType · ${cues.size} 行")
                            }
                        }.joinToString(" · ")
                        if (subtitleText.isNotEmpty()) {
                            Text(
                                subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    // 查看文件位置按钮
                    if (currentFile != null) {
                        IconButton(onClick = { showFileInfoSheet = true }) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = "文件信息",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (currentFile == null) {
                // 未播放状态
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "从音乐页选择歌曲开始播放",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // ========== 歌词区域 (占据大部分空间) ==========
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (cues.isEmpty()) {
                        // 无字幕 - 显示封面或大图标
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            ) {
                                // 封面图片
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (currentCover != null) {
                                        Image(
                                            bitmap = currentCover!!.asImageBitmap(),
                                            contentDescription = "封面",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.MusicNote,
                                            contentDescription = null,
                                            modifier = Modifier.size(80.dp),
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    displayName ?: "未知歌曲",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (currentArtist != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        currentArtist!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "♪ 纯音乐，请欣赏 ♪",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    } else if (subtitleResult is SubtitleManager.SubtitleResult.Ass) {
                        // ASS 歌词 (带特效渲染)
                        AssLyricsView(
                            cues = cues,
                            styles = assStyles,
                            currentIndex = currentIndex,
                            positionMs = positionMs,
                            listState = listState
                        )
                    } else {
                        // SRT 歌词 (简洁列表)
                        SrtLyricsView(
                            cues = cues,
                            currentIndex = currentIndex,
                            listState = listState
                        )
                    }

                    // 歌词区域顶部渐变遮罩
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.surface,
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                    )
                                )
                            )
                    )
                    // 歌词区域底部渐变遮罩
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                            )
                    )
                }

                // ========== 播放控制栏 (含播放模式按钮) ==========
                PlayerControlsWithMode(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    playMode = playMode,
                    onPlayPause = { if (isPlaying) pm.pause() else pm.play() },
                    onSeek = { pm.seekTo(it) },
                    onPrevious = { pm.previous() },
                    onNext = { pm.next() },
                    onTogglePlayMode = { pm.togglePlayMode() }
                )
            }
        }
    }

    // ========== 文件信息 BottomSheet ==========
    if (showFileInfoSheet && currentFile != null) {
        FileInfoBottomSheet(
            filePath = currentFile!!,
            artist = currentArtist,
            album = currentAlbum,
            onDismiss = { showFileInfoSheet = false },
            onOpenInFileManager = {
                // 尝试用外部文件管理器打开文件所在目录
                try {
                    val file = File(currentFile!!)
                    val parentDir = file.parentFile
                    if (parentDir != null) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                Uri.parse("content://com.android.externalstorage.documents/document/primary:${parentDir.absolutePath.removePrefix("/storage/emulated/0/")}"),
                                "resource/folder"
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                } catch (e: Exception) {
                    // 通用文件管理器 fallback
                    try {
                        val file = File(currentFile!!)
                        val parentDir = file.parentFile
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse("file://${parentDir?.absolutePath}"), "*/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(intent, "打开文件夹"))
                    } catch (_: Exception) { }
                }
            }
        )
    }
}

/**
 * 文件信息 BottomSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileInfoBottomSheet(
    filePath: String,
    artist: String?,
    album: String?,
    onDismiss: () -> Unit,
    onOpenInFileManager: () -> Unit
) {
    val file = remember(filePath) { File(filePath) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "📄 文件信息",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 文件名
            FileInfoRow(label = "文件名", value = file.name)
            // 文件路径
            FileInfoRow(label = "所在目录", value = file.parent ?: "未知")
            // 文件大小
            FileInfoRow(label = "文件大小", value = formatFileSize(file.length()))
            // 格式
            FileInfoRow(label = "格式", value = file.extension.uppercase())
            // 歌手
            if (artist != null) {
                FileInfoRow(label = "歌手", value = artist)
            }
            // 专辑
            if (album != null) {
                FileInfoRow(label = "专辑", value = album)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 在文件管理器中打开
            FilledTonalButton(
                onClick = onOpenInFileManager,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("在文件管理器中打开")
            }
        }
    }
}

@Composable
private fun FileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.7f),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "未知"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.2f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

/**
 * SRT 歌词列表 - 更明显的高亮效果
 */
@Composable
fun SrtLyricsView(
    cues: List<SubtitleCue>,
    currentIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(cues) { index, cue ->
            val isCurrent = index == currentIndex
            val textColor by animateColorAsState(
                targetValue = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                label = "lyricColor"
            )
            val fontSize by animateFloatAsState(
                targetValue = if (isCurrent) 22f else 16f,
                animationSpec = tween(300),
                label = "lyricSize"
            )

            Text(
                text = cue.text,
                style = TextStyle(
                    fontSize = fontSize.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    lineHeight = (fontSize * 1.4f).sp
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * ASS 歌词视图 (支持特效渲染)
 */
@Composable
fun AssLyricsView(
    cues: List<SubtitleCue>,
    styles: Map<String, AssStyle>,
    currentIndex: Int,
    positionMs: Long,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(cues) { index, cue ->
            val isCurrent = index == currentIndex
            val style = cue.styleName?.let { styles[it] }

            AssStyledText(
                cue = cue,
                style = style,
                isCurrent = isCurrent,
                currentPositionMs = positionMs
            )
        }
    }
}

/**
 * 单行 ASS 特效文本渲染
 * 支持: 颜色、加粗、斜体、字号、淡入淡出、描边、阴影
 */
@Composable
fun AssStyledText(
    cue: SubtitleCue,
    style: AssStyle?,
    isCurrent: Boolean,
    currentPositionMs: Long
) {
    var textColor = style?.let { Color(it.primaryColor) }
        ?: MaterialTheme.colorScheme.onSurface
    var fontSize = style?.fontSize ?: 18f
    var bold = style?.bold ?: false
    var italic = style?.italic ?: false
    var outlineSize = style?.outline ?: 0f
    var outlineColor = style?.let { Color(it.outlineColor) } ?: Color.Black
    var shadowDepth = style?.shadow ?: 0f
    var fadeInMs = 0L
    var fadeOutMs = 0L

    for (effect in cue.effects) {
        when (effect) {
            is AssEffect.Color -> {
                if (effect.colorType == 1 || effect.colorType == 0) {
                    textColor = Color(effect.argb)
                }
            }
            is AssEffect.Bold -> bold = effect.enabled
            is AssEffect.Italic -> italic = effect.enabled
            is AssEffect.FontSize -> fontSize = effect.size
            is AssEffect.Fade -> {
                fadeInMs = effect.fadeInMs
                fadeOutMs = effect.fadeOutMs
            }
            is AssEffect.Border -> outlineSize = effect.size
            is AssEffect.Shadow -> shadowDepth = effect.depth
            is AssEffect.Alpha -> {
                if (effect.alphaType == 0 || effect.alphaType == 1) {
                    textColor = textColor.copy(alpha = effect.value / 255f)
                }
            }
            else -> {}
        }
    }

    var alpha = if (isCurrent) 1f else 0.35f
    if (isCurrent && (fadeInMs > 0 || fadeOutMs > 0)) {
        val elapsed = currentPositionMs - cue.startMs
        val remaining = cue.endMs - currentPositionMs
        alpha = when {
            fadeInMs > 0 && elapsed < fadeInMs -> (elapsed.toFloat() / fadeInMs).coerceIn(0f, 1f)
            fadeOutMs > 0 && remaining < fadeOutMs -> (remaining.toFloat() / fadeOutMs).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    val finalColor = if (isCurrent) textColor.copy(alpha = alpha)
    else textColor.copy(alpha = 0.3f)

    val displayFontSize = if (isCurrent) (fontSize * 1.2f) else fontSize

    // 分割多行文本，每行独立渲染
    val lines = cue.text.split("\n")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        lines.forEachIndexed { index, line ->
            Text(
                text = line,
                style = TextStyle(
                    fontSize = displayFontSize.sp,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    color = finalColor,
                    textAlign = TextAlign.Center,
                    lineHeight = (displayFontSize * 1.4f).sp,
                    shadow = if (shadowDepth > 0 || outlineSize > 0) {
                        Shadow(
                            color = outlineColor.copy(alpha = alpha),
                            offset = Offset(shadowDepth, shadowDepth),
                            blurRadius = outlineSize * 2
                        )
                    } else null
                )
            )
            // 行间距
            if (index < lines.size - 1 && lines.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/**
 * 带播放模式切换的播放控制栏
 */
@Composable
fun PlayerControlsWithMode(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playMode: PlayMode,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlayMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        // 进度条
        var sliderPosition by remember { mutableFloatStateOf(0f) }
        var isDragging by remember { mutableStateOf(false) }

        val displayPosition = if (isDragging) sliderPosition
        else if (durationMs > 0) positionMs.toFloat() / durationMs
        else 0f

        Slider(
            value = displayPosition,
            onValueChange = {
                isDragging = true
                sliderPosition = it
            },
            onValueChangeFinished = {
                onSeek((sliderPosition * durationMs).toLong())
                isDragging = false
            },
            modifier = Modifier.fillMaxWidth(),
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
                formatTime(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 控制按钮行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放模式切换按钮
            IconButton(onClick = onTogglePlayMode) {
                Icon(
                    imageVector = when (playMode) {
                        PlayMode.SEQUENTIAL -> Icons.Filled.Repeat
                        PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                        PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne
                    },
                    contentDescription = when (playMode) {
                        PlayMode.SEQUENTIAL -> "顺序播放"
                        PlayMode.SHUFFLE -> "随机播放"
                        PlayMode.REPEAT_ONE -> "单曲循环"
                    },
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onPrevious) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "上一曲",
                    modifier = Modifier.size(36.dp)
                )
            }

            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一曲",
                    modifier = Modifier.size(36.dp)
                )
            }

            // 播放列表按钮 (占位，保持对称)
            IconButton(onClick = { /* TODO: 显示播放列表 */ }) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = "播放列表",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 播放模式提示文字
        Text(
            text = when (playMode) {
                PlayMode.SEQUENTIAL -> "顺序播放"
                PlayMode.SHUFFLE -> "随机播放"
                PlayMode.REPEAT_ONE -> "单曲循环"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 基础播放控制栏 (无播放模式切换，供听书页复用)
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        var sliderPosition by remember { mutableFloatStateOf(0f) }
        var isDragging by remember { mutableStateOf(false) }

        val displayPosition = if (isDragging) sliderPosition
        else if (durationMs > 0) positionMs.toFloat() / durationMs
        else 0f

        Slider(
            value = displayPosition,
            onValueChange = {
                isDragging = true
                sliderPosition = it
            },
            onValueChangeFinished = {
                onSeek((sliderPosition * durationMs).toLong())
                isDragging = false
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatTime(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "上一曲",
                    modifier = Modifier.size(36.dp)
                )
            }

            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一曲",
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

/** 格式化时间 MM:SS */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

/** 格式化时间 HH:MM:SS (超过1小时) */
fun formatTimeLong(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
