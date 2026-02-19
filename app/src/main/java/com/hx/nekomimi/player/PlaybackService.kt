package com.hx.nekomimi.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
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
 * 前台播放服务 (纯听书模式)
 *
 * 功能:
 * - 通知栏播放控制 (章节名称)
 * - 蓝牙耳机/车机媒体按键
 * - 锁屏/息屏继续播放
 * - 系统媒体中心集成
 *
 * 当用户滑动清除进程时，保存播放位置
 */
@AndroidEntryPoint
@UnstableApi
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playerManager: PlayerManager

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var metadataUpdateJob: Job? = null

    private lateinit var notificationProvider: NekoNotificationProvider

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        notificationProvider = NekoNotificationProvider.create(this)
        setMediaNotificationProvider(notificationProvider)

        // 点击通知打开应用
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, playerManager.player)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
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

        // 监听章节名称变化更新通知
        startMetadataSync()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun startMetadataSync() {
        serviceScope.launch {
            playerManager.currentDisplayName.collect { displayName ->
                if (displayName != null) {
                    metadataUpdateJob?.cancel()
                    metadataUpdateJob = serviceScope.launch {
                        delay(200)
                        notificationProvider.title = displayName
                    }
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            playerManager.saveCurrentPositionSync()
            val player = mediaSession?.player
            if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "onTaskRemoved 异常: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            playerManager.saveCurrentPositionSync()
            serviceScope.cancel()
            metadataUpdateJob?.cancel()
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "onDestroy 异常: ${e.message}")
        }
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "nekomimi_playback"
    }
}
