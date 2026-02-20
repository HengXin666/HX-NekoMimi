package com.hx.nekomimi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.hx.nekomimi.data.AppDatabase

class NekoMimiApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    companion object {
        const val CHANNEL_ID_PLAYBACK = "playback_channel"
        lateinit var instance: NekoMimiApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 强制暗色模式，确保通知栏/锁屏栏使用暗色主题
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_PLAYBACK,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
