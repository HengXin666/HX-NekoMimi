package com.hx.nekomimi.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
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
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playerManager: PlayerManager

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 元信息更新 Job，避免重复更新 */
    private var metadataUpdateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 创建点击通知时打开应用的 PendingIntent
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, playerManager.player)
            .setSessionActivity(pendingIntent)
            .build()

        // 监听歌曲元信息变化，更新通知栏/锁屏的元信息
        startMetadataSync()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * 监听 PlayerManager 的元信息状态，同步更新到 MediaSession
     * 这样通知栏、锁屏界面、系统导航栏媒体控制器 都能显示正确的歌曲信息
     */
    private fun startMetadataSync() {
        // 监听当前文件路径变化 (切歌时)
        serviceScope.launch {
            playerManager.currentFilePath.collect { filePath ->
                if (filePath != null) {
                    // 延迟一小段时间等待元信息加载完成
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
    }

    /**
     * 将 PlayerManager 中的元信息同步到 ExoPlayer 的 MediaMetadata
     * 这样 MediaSession 通知栏和锁屏界面会自动显示这些信息
     */
    private fun updateMediaMetadata() {
        val player = mediaSession?.player ?: return
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

        // 通过 MediaSession 设置元信息
        val currentItem = player.currentMediaItem
        if (currentItem != null) {
            val updatedItem = currentItem.buildUpon()
                .setMediaMetadata(metadataBuilder.build())
                .build()
            val currentIndex = player.currentMediaItemIndex
            val currentPosition = player.currentPosition

            // 替换当前 MediaItem 以更新元信息
            player.replaceMediaItem(currentIndex, updatedItem)
            player.seekTo(currentIndex, currentPosition)
        }
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

    companion object {
        const val CHANNEL_ID = "nekomimi_playback"
    }
}
