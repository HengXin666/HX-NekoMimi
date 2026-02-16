package com.hx.nekomimi.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.hx.nekomimi.MainActivity
import com.hx.nekomimi.R

/**
 * 自定义媒体通知提供者
 * 为通知栏和锁屏界面提供自定义布局的播放控制界面
 *
 * 功能:
 * - 显示歌曲封面、标题、歌手
 * - 播放/暂停、上一首、下一首 控制按钮
 * - 点击通知打开应用
 * - 支持紧凑布局和展开布局
 */
@UnstableApi
class NekoNotificationProvider(
    private val context: Context
) : MediaSession.NotificationProvider {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nekomimi_playback"

        // 通知按钮点击 Action
        const val ACTION_PLAY = "com.hx.nekomimi.ACTION_PLAY"
        const val ACTION_PAUSE = "com.hx.nekomimi.ACTION_PAUSE"
        const val ACTION_NEXT = "com.hx.nekomimi.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.hx.nekomimi.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.hx.nekomimi.ACTION_STOP"
    }

    // 当前元信息 (由 PlaybackService 更新)
    var title: String = ""
        @Synchronized set
        @Synchronized get
    var artist: String? = null
        @Synchronized set
        @Synchronized get
    var albumArt: Bitmap? = null
        @Synchronized set
        @Synchronized get

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: List<androidx.media3.session.CommandButton>,
        actionFactory: MediaSession.ConnectionResult.NotificationActionFactory,
        onNotificationChangedCallback: MediaSession.NotificationProvider.Callback
    ): MediaSession.ConnectionResult.CustomLayoutNotification {
        val player = mediaSession.player
        val isPlaying = player.isPlaying

        // 点击通知打开应用的 PendingIntent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            context, 0, openAppIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // 构建通知
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { context.getString(R.string.notification_default_title) })
            .setContentText(artist ?: "")
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 设置封面 (如果存在)
        albumArt?.let { art ->
            builder.setLargeIcon(art)
        }

        // 添加媒体样式 (展开时显示控制按钮)
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionCompatToken)
            .setShowActionsInCompactView(0, 1, 2) // 紧凑视图显示: 上一首、播放/暂停、下一首
            .setShowCancelButton(true)
            .setCancelButtonAction(3) // 停止按钮

        builder.setStyle(mediaStyle)

        // 添加控制按钮
        // 上一首
        builder.addAction(
            R.drawable.ic_skip_previous,
            context.getString(R.string.notification_previous),
            createMediaAction(mediaSession, actionFactory, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        )

        // 播放/暂停
        if (isPlaying) {
            builder.addAction(
                R.drawable.ic_pause,
                context.getString(R.string.notification_pause),
                createMediaAction(mediaSession, actionFactory, Player.COMMAND_PLAY_PAUSE)
            )
        } else {
            builder.addAction(
                R.drawable.ic_play,
                context.getString(R.string.notification_play),
                createMediaAction(mediaSession, actionFactory, Player.COMMAND_PLAY_PAUSE)
            )
        }

        // 下一首
        builder.addAction(
            R.drawable.ic_skip_next,
            context.getString(R.string.notification_next),
            createMediaAction(mediaSession, actionFactory, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        )

        // 停止 (关闭通知)
        builder.addAction(
            R.drawable.ic_stop,
            context.getString(R.string.notification_stop),
            createStopAction(mediaSession, actionFactory)
        )

        val notification = builder.build()

        return MediaSession.ConnectionResult.CustomLayoutNotification(
            notification,
            NOTIFICATION_ID,
            customLayout
        )
    }

    private fun createMediaAction(
        mediaSession: MediaSession,
        actionFactory: MediaSession.ConnectionResult.NotificationActionFactory,
        command: Int
    ): android.app.PendingIntent {
        return actionFactory.createMediaActionPendingIntent(mediaSession, command)
    }

    private fun createStopAction(
        mediaSession: MediaSession,
        actionFactory: MediaSession.ConnectionResult.NotificationActionFactory
    ): android.app.PendingIntent {
        // 停止并关闭通知
        return actionFactory.createCustomActionPendingIntent(
            mediaSession,
            ACTION_STOP,
            null
        )
    }
}
