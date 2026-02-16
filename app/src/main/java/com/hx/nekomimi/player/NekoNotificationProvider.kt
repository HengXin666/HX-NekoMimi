package com.hx.nekomimi.player

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.hx.nekomimi.R

/**
 * 自定义媒体通知提供者
 * 使用 DefaultMediaNotificationProvider.Builder 配置通知外观
 *
 * 功能:
 * - 显示歌曲封面、标题、歌手 (通过 ForwardingPlayer 的 mediaMetadata)
 * - 播放/暂停、上一首、下一首 控制按钮 (默认提供)
 * - 点击通知打开应用 (在 MediaSession.Builder 中设置)
 */
@UnstableApi
class NekoNotificationProvider private constructor(
    private val delegate: DefaultMediaNotificationProvider
) : DefaultMediaNotificationProvider by delegate {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nekomimi_playback"

        /**
         * 创建通知提供者实例
         */
        fun create(context: Context): NekoNotificationProvider {
            val delegate = DefaultMediaNotificationProvider.Builder(context)
                .setNotificationIdProvider { NOTIFICATION_ID }
                .setNotificationChannelId(CHANNEL_ID)
                .setNotificationChannelName(context.getString(R.string.notification_channel_playback))
                .setSmallIcon(R.drawable.ic_notification)
                .build()
            return NekoNotificationProvider(delegate)
        }
    }

    // 当前元信息 (由 PlaybackService 更新，用于日志/调试)
    @Volatile var title: String = ""
    @Volatile var artist: String? = null
    @Volatile var albumArt: Bitmap? = null
}
