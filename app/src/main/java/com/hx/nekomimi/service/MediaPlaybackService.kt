package com.hx.nekomimi.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hx.nekomimi.NekoMimiApp
import com.hx.nekomimi.R
import com.hx.nekomimi.ui.PlayerActivity

@UnstableApi
class MediaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handleAudioFocus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player = exoPlayer

        // 创建点击通知时打开播放页面的 Intent
        val intent = Intent(this, PlayerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .build()

        // 使用自定义通知 Provider，强制暗色主题 + 粉色强调色
        setMediaNotificationProvider(CustomMediaNotificationProvider())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    /**
     * 自定义媒体通知 Provider
     * 在 DefaultMediaNotificationProvider 基础上，强制设置通知颜色为暗色背景 + 粉色强调色
     * 解决系统暗黑主题下通知栏/锁屏控件几乎全白无法看清的问题
     */
    private inner class CustomMediaNotificationProvider : MediaNotification.Provider {

        private val delegate = DefaultMediaNotificationProvider.Builder(this@MediaPlaybackService)
            .setChannelId(NekoMimiApp.CHANNEL_ID_PLAYBACK)
            .setChannelName(R.string.notification_channel_name)
            .build().also {
                it.setSmallIcon(R.drawable.ic_notification)
            }

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: com.google.common.collect.ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            // 先让默认 Provider 创建通知
            val mediaNotification = delegate.createNotification(
                mediaSession, customLayout, actionFactory, onNotificationChangedCallback
            )

            // 修改通知颜色 — 强制使用粉色强调 + 暗色配色
            val notification = mediaNotification.notification
            // 设置通知颜色为粉色主色调 (#FF6B9D)，系统会以此渲染媒体通知背景
            notification.color = Color.parseColor("#FF6B9D")
            // 强制使用 colorized 模式（Android 8.0+ 媒体通知支持），让通知背景着色
            notification.extras.putBoolean(NotificationCompat.EXTRA_COLORIZED, true)

            return mediaNotification
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean {
            return delegate.handleCustomCommand(session, action, extras)
        }
    }
}
