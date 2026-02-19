package com.hx.nekomimi.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.hx.nekomimi.R

/**
 * 自定义媒体通知提供者 (纯听书模式)
 *
 * 功能:
 * - 显示章节名称
 * - 播放/暂停、上一章、下一章控制按钮
 * - 点击通知打开应用
 */
@UnstableApi
class NekoNotificationProvider(
    private val delegate: DefaultMediaNotificationProvider
) : MediaNotification.Provider {

    companion object {
        const val NOTIFICATION_ID = 1001

        fun create(context: Context): NekoNotificationProvider {
            val delegate = DefaultMediaNotificationProvider.Builder(context)
                .setNotificationIdProvider { NOTIFICATION_ID }
                .build()
            delegate.setSmallIcon(R.drawable.ic_notification)
            return NekoNotificationProvider(delegate)
        }
    }

    @Volatile var title: String = ""

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        return delegate.createNotification(
            mediaSession, customLayout, actionFactory, onNotificationChangedCallback
        )
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        return delegate.handleCustomCommand(session, action, extras)
    }
}
