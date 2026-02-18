package com.hx.nekomimi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hx.nekomimi.player.PlayerManager
import com.hx.nekomimi.ui.navigation.NavGraph
import com.hx.nekomimi.ui.navigation.Screen
import com.hx.nekomimi.ui.navigation.bottomNavItems
import com.hx.nekomimi.ui.theme.NekoMimiTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* 权限结果处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            NekoMimiTheme {
                MainScreen()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

/**
 * 主界面 ViewModel - 用于在 MainScreen 中访问 PlayerManager
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    val playerManager: PlayerManager
) : ViewModel()

@Composable
fun MainScreen(mainViewModel: MainViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 判断当前是否在底部导航的根页面
    val bottomNavRoutes = bottomNavItems.map { it.route }
    val isBottomBarVisible = currentRoute in bottomNavRoutes

    // 判断是否在播放页 (播放页不显示迷你播放器)
    val isPlayerPage = currentRoute == Screen.MusicPlayer.route ||
            currentRoute == Screen.BookPlayer.route

    // 播放状态
    val pm = mainViewModel.playerManager
    val currentFile by pm.currentFilePath.collectAsStateWithLifecycle()
    val isPlaying by pm.isPlaying.collectAsStateWithLifecycle()
    val displayName by pm.currentDisplayName.collectAsStateWithLifecycle()
    val currentArtist by pm.currentArtist.collectAsStateWithLifecycle()
    val currentCover by pm.currentCover.collectAsStateWithLifecycle()
    val positionMs by pm.positionMs.collectAsStateWithLifecycle()
    val durationMs by pm.durationMs.collectAsStateWithLifecycle()

    // 是否显示迷你播放器 (有歌曲在播放/暂停中，且不在播放页)
    val showMiniPlayer = currentFile != null && !isPlayerPage

    Scaffold(
        bottomBar = {
            Column {
                // 迷你播放器 (在底部导航栏上方)
                if (showMiniPlayer) {
                    MiniPlayerBar(
                        displayName = displayName ?: "未知歌曲",
                        artist = currentArtist,
                        coverBitmap = currentCover,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onPlayPause = { if (isPlaying) pm.pause() else pm.play() },
                        onNext = { pm.next() },
                        onClick = {
                            // 跳转到播放页
                            navController.navigate(Screen.MusicPlayer.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                // 底部导航栏
                if (isBottomBarVisible) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        bottomNavItems.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = screen.title) },
                                label = { Text(screen.title) },
                                selected = currentRoute == screen.route,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                onClick = {
                                    if (currentRoute != screen.route) {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavGraph(navController = navController)
        }
    }
}

/**
 * 迷你播放器栏
 * 显示在底部导航栏上方，包含封面、歌名、歌手、播放/暂停、下一首
 */
@Composable
fun MiniPlayerBar(
    displayName: String,
    artist: String?,
    coverBitmap: android.graphics.Bitmap?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 4.dp
    ) {
        Column {
            // 进度条 (极细，在顶部)
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 封面
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null && !coverBitmap.isRecycled) {
                        try {
                            Image(
                                bitmap = coverBitmap.asImageBitmap(),
                                contentDescription = "封面",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } catch (_: Exception) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 歌名 + 歌手
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (artist != null) {
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 播放/暂停按钮
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 下一首按钮
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一曲",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
