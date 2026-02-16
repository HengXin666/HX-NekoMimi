package com.hx.nekomimi.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 前台播放服务
 * 继承 MediaSessionService，自动处理:
 * - 通知栏播放控制
 * - 蓝牙耳机/车机媒体按键
 * - 锁屏/息屏继续播放
 * - 系统媒体中心集成
 *
 * 当用户滑动清除进程时，onTaskRemoved 保证保存播放位置
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playerManager: PlayerManager

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSession.Builder(this, playerManager.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
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
