package com.hx.nekomimi.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hx.nekomimi.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * 前台播放服务
 * 继承 MediaSessionService，自动处理:
 * - 通知栏播放控制 (含歌曲元信息: 封面、歌名、歌手、专辑)
 * - 蓝牙耳机/车机媒体按键
 * - 锁屏/息屏继续播放
 * - 系统媒体中心集成 (锁屏界面播放器)
 * - 系统导航栏媒体控制 (Android 11+)
 *
 * 当用户滑动清除进程时，onTaskRemoved 保证保存播放位置
 */
@AndroidEntryPoint
@UnstableApi
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playerManager: PlayerManager

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 元信息更新 Job，避免重复更新 */
    private var metadataUpdateJob: Job? = null

    /** 自定义通知提供者 */
    private lateinit var notificationProvider: NekoNotificationProvider

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 初始化自定义通知提供者
        notificationProvider = NekoNotificationProvider(this)
        setNotificationProvider(notificationProvider)

        // 创建点击通知时打开应用的 PendingIntent
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 使用 ForwardingPlayer 包装原始 player，用于注入动态元信息
        // 这样 MediaSession 读取 mediaMetadata 时能获取到实时的歌曲信息
        val wrappedPlayer = MetadataForwardingPlayer(playerManager.player)

        mediaSession = MediaSession.Builder(this, wrappedPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // 允许所有控制器连接，并授予所有可用的播放操作
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        )
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        )
                        .build()
                }
            })
            .build()

        // 监听歌曲元信息变化，更新通知栏/锁屏的元信息
        startMetadataSync()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * 监听 PlayerManager 的元信息状态，同步更新到 MediaSession
     * 通过 ForwardingPlayer 覆写 mediaMetadata，然后触发 session 失效通知系统刷新
     * 不再使用 replaceMediaItem，避免播放状态被打断导致通知消失
     */
    private fun startMetadataSync() {
        // 监听当前文件路径变化 (切歌时)
        serviceScope.launch {
            playerManager.currentFilePath.collect { filePath ->
                if (filePath != null) {
                    metadataUpdateJob?.cancel()
                    metadataUpdateJob = launch {
                        delay(500) // 等待 PlayerManager 加载元信息
                        updateMediaMetadata()
                    }
                }
            }
        }

        // 监听封面变化 (元信息加载完毕后封面更新)
        serviceScope.launch {
            playerManager.currentCover.collect { _ ->
                metadataUpdateJob?.cancel()
                metadataUpdateJob = launch {
                    delay(100)
                    updateMediaMetadata()
                }
            }
        }

        // 监听歌手变化
        serviceScope.launch {
            playerManager.currentArtist.collect { _ ->
                metadataUpdateJob?.cancel()
                metadataUpdateJob = launch {
                    delay(100)
                    updateMediaMetadata()
                }
            }
        }

        // 监听显示名称变化
        serviceScope.launch {
            playerManager.currentDisplayName.collect { _ ->
                metadataUpdateJob?.cancel()
                metadataUpdateJob = launch {
                    delay(100)
                    updateMediaMetadata()
                }
            }
        }
    }

    /**
     * 将 PlayerManager 中的元信息同步到 ForwardingPlayer 的覆写元数据
     * 然后通过 MediaSession.setPlayer() 刷新来通知系统更新通知栏/锁屏
     * 不使用 replaceMediaItem，避免播放状态被打断
     */
    private fun updateMediaMetadata() {
        val session = mediaSession ?: return
        val wrappedPlayer = session.player as? MetadataForwardingPlayer ?: return
        val displayName = playerManager.currentDisplayName.value ?: return
        val artist = playerManager.currentArtist.value
        val album = playerManager.currentAlbum.value
        val cover = playerManager.currentCover.value

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(displayName)
            .setDisplayTitle(displayName)

        if (artist != null) {
            metadataBuilder.setArtist(artist)
        }
        if (album != null) {
            metadataBuilder.setAlbumTitle(album)
        }
        if (cover != null) {
            metadataBuilder.setArtworkData(
                bitmapToByteArray(cover),
                MediaMetadata.PICTURE_TYPE_FRONT_COVER
            )
        }

        // 更新 ForwardingPlayer 的覆写元数据
        wrappedPlayer.overrideMetadata = metadataBuilder.build()

        // 同步更新自定义通知提供者的元信息
        notificationProvider.title = displayName
        notificationProvider.artist = artist
        notificationProvider.albumArt = cover

        // 通知 MediaSession 刷新 (触发系统重新读取元信息并更新通知)
        session.setPlayer(wrappedPlayer)
    }

    /**
     * Bitmap 转 ByteArray (用于 MediaMetadata 的封面)
     */
    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return stream.toByteArray()
    }

    /**
     * 用户从最近任务列表滑动清除时调用
     * 这是保存播放位置的最后时机
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        playerManager.saveCurrentPositionSync()

        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            // 没在播放就停止服务
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 释放前保存位置
        playerManager.saveCurrentPositionSync()
        serviceScope.cancel()
        metadataUpdateJob?.cancel()
        mediaSession?.run {
            // 注意: 不要在这里释放 player，player 由 PlayerManager 管理
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.hx.nekomimi.R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(com.hx.nekomimi.R.string.notification_channel_playback_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * ForwardingPlayer 包装器
     * 覆写 mediaMetadata 属性，让 MediaSession 能获取到实时的歌曲元信息
     * 避免使用 replaceMediaItem 导致播放状态被打断、通知栏消失
     */
    private class MetadataForwardingPlayer(
        player: Player
    ) : ForwardingPlayer(player) {

        /** 覆写的元数据，由 PlaybackService 在收到 PlayerManager 元信息更新时设置 */
        var overrideMetadata: MediaMetadata? = null

        override fun getMediaMetadata(): MediaMetadata {
            return overrideMetadata ?: super.getMediaMetadata()
        }
    }

    companion object {
        const val CHANNEL_ID = "nekomimi_playback"
    }
}
