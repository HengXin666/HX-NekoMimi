package com.hx.nekomimi.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.hx.nekomimi.R

/**
 * 自定义媒体通知提供者
 * 实现 MediaNotification.Provider 接口，委托给 DefaultMediaNotificationProvider
 *
 * 功能:
 * - 显示歌曲封面、标题、歌手 (通过 ForwardingPlayer 的 mediaMetadata)
 * - 播放/暂停、上一首、下一首 控制按钮 (默认提供)
 * - 点击通知打开应用 (在 MediaSession.Builder 中设置)
 */
@UnstableApi
class NekoNotificationProvider(
    private val delegate: DefaultMediaNotificationProvider
) : MediaNotification.Provider {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nekomimi_playback"

        /**
         * 创建通知提供者实例
         * 注意: 通知渠道需要由调用方 (PlaybackService) 创建
         */
        fun create(context: Context): NekoNotificationProvider {
            val delegate = DefaultMediaNotificationProvider.Builder(context)
                .setNotificationIdProvider { NOTIFICATION_ID }
                .setSmallIconResourceId(R.drawable.ic_notification)
                .build()
            return NekoNotificationProvider(delegate)
        }
    }

    // 当前元信息 (由 PlaybackService 更新，用于日志/调试)
    @Volatile var title: String = ""
    @Volatile var artist: String? = null
    @Volatile var albumArt: Bitmap? = null

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        // 委托给 DefaultMediaNotificationProvider 处理
        return delegate.createNotification(
            mediaSession, customLayout, actionFactory, onNotificationChangedCallback
        )
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        // 委托给 DefaultMediaNotificationProvider 处理自定义命令
        return delegate.handleCustomCommand(session, action, extras)
    }
}
