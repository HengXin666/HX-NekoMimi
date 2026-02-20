package com.hx.nekomimi.bgm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * 背景音乐管理器（全局单例）
 * - 使用独立的 MediaPlayer 实例播放背景音乐，与听书音频互不干扰
 * - 支持循环播放、音量微调
 * - 设置通过 SharedPreferences 全局持久化
 */
object BgmManager {

    private const val TAG = "BgmManager"
    private const val PREFS_NAME = "bgm_settings"
    private const val KEY_BGM_URI = "bgm_uri"
    private const val KEY_BGM_VOLUME = "bgm_volume"
    private const val KEY_BGM_ENABLED = "bgm_enabled"
    private const val DEFAULT_VOLUME = 0.3f // 默认背景音乐音量 30%

    private var mediaPlayer: MediaPlayer? = null
    private var currentUri: Uri? = null
    private var volume: Float = DEFAULT_VOLUME
    private var isEnabled: Boolean = false
    private var isPrepared: Boolean = false
    private var shouldPlayWhenReady: Boolean = false

    /**
     * 从持久化设置恢复 BGM 状态
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_BGM_URI, null)
        volume = prefs.getFloat(KEY_BGM_VOLUME, DEFAULT_VOLUME)
        isEnabled = prefs.getBoolean(KEY_BGM_ENABLED, false)

        if (uriStr != null) {
            currentUri = Uri.parse(uriStr)
        }
    }

    /**
     * 获取当前BGM的URI
     */
    fun getBgmUri(): Uri? = currentUri

    /**
     * 获取当前音量
     */
    fun getVolume(): Float = volume

    /**
     * BGM 是否已启用
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 设置BGM文件并持久化
     */
    fun setBgmUri(context: Context, uri: Uri?) {
        currentUri = uri
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BGM_URI, uri?.toString()).apply()

        if (uri == null) {
            stop()
            setEnabled(context, false)
        }
    }

    /**
     * 设置音量并持久化（0.0 ~ 1.0）
     */
    fun setVolume(context: Context, vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_BGM_VOLUME, volume).apply()

        // 实时更新播放音量
        try {
            mediaPlayer?.setVolume(volume, volume)
        } catch (e: Exception) {
            Log.w(TAG, "设置音量失败", e)
        }
    }

    /**
     * 启用/禁用BGM并持久化
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BGM_ENABLED, enabled).apply()

        if (enabled) {
            start(context)
        } else {
            stop()
        }
    }

    /**
     * 开始播放背景音乐（循环）
     */
    fun start(context: Context) {
        val uri = currentUri
        if (uri == null || !isEnabled) {
            Log.d(TAG, "BGM 未设置或未启用，跳过播放")
            return
        }

        // 如果正在播放同一首，不重复初始化
        if (isPlaying()) {
            Log.d(TAG, "BGM 已在播放中")
            return
        }

        // 释放旧的 MediaPlayer
        releasePlayer()

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context.applicationContext, uri)
                isLooping = true // 循环播放
                setVolume(volume, volume)

                setOnPreparedListener {
                    isPrepared = true
                    if (shouldPlayWhenReady) {
                        it.start()
                        shouldPlayWhenReady = false
                        Log.d(TAG, "BGM 开始播放，音量: $volume")
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "BGM 播放错误: what=$what, extra=$extra")
                    isPrepared = false
                    releasePlayer()
                    true
                }

                shouldPlayWhenReady = true
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "BGM 初始化失败", e)
            releasePlayer()
        }
    }

    /**
     * 暂停播放（听书暂停时可选择是否暂停BGM）
     */
    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                Log.d(TAG, "BGM 已暂停")
            }
        } catch (e: Exception) {
            Log.w(TAG, "暂停BGM失败", e)
        }
    }

    /**
     * 恢复播放
     */
    fun resume() {
        if (!isEnabled || currentUri == null) return
        try {
            if (isPrepared && mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
                Log.d(TAG, "BGM 已恢复")
            }
        } catch (e: Exception) {
            Log.w(TAG, "恢复BGM失败", e)
        }
    }

    /**
     * 停止并释放
     */
    fun stop() {
        shouldPlayWhenReady = false
        releasePlayer()
        Log.d(TAG, "BGM 已停止")
    }

    /**
     * 释放 MediaPlayer 资源
     */
    private fun releasePlayer() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 MediaPlayer 失败", e)
        }
        mediaPlayer = null
        isPrepared = false
    }

    /**
     * 获取BGM文件名（用于UI显示）
     */
    fun getBgmDisplayName(context: Context): String? {
        val uri = currentUri ?: return null
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return it.getString(nameIndex)
                    }
                }
            }
            // fallback: 从URI路径中提取文件名
            uri.lastPathSegment?.substringAfterLast('/')
        } catch (e: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }
}
