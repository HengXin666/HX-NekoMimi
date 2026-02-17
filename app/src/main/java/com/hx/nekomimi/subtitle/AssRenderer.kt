package com.hx.nekomimi.subtitle

import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable

/**
 * libass 原生渲染器
 *
 * 通过 JNI 调用 libass C 库，将 ASS/SSA 字幕渲染为 Android Bitmap。
 * 支持所有 ASS 特效: \pos, \move, \an, \frz, \fscx/\fscy, \fad,
 *                    \k/\K/\kf 卡拉OK, \bord, \shad, \blur 等。
 *
 * 使用方式:
 * ```kotlin
 * val renderer = AssRenderer()
 * renderer.setFrameSize(width, height)
 * renderer.loadTrack(assRawContent)
 * val result = renderer.renderFrame(currentTimeMs)
 * if (result != null) {
 *     // result.bitmap: 裁剪后的字幕图片
 *     // result.left, result.top: 在原始帧中的偏移
 * }
 * renderer.destroy()
 * ```
 */
class AssRenderer : Closeable {

    companion object {
        private const val TAG = "AssRenderer"

        init {
            try {
                System.loadLibrary("assrenderer")
                Log.i(TAG, "libass 原生库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "加载 libass 原生库失败: ${e.message}")
            }
        }

        /** 检测 libass 原生库是否可用 */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("assrenderer")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }
    }

    /** 原生指针 */
    private var nativePtr: Long = 0

    /** 帧尺寸 */
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0

    /** 是否已加载 track */
    private var trackLoaded: Boolean = false

    /** 上一帧渲染结果缓存 (用于 change 检测优化) */
    private var lastBitmap: Bitmap? = null
    private var lastTimeMs: Long = -1

    /** 渲染结果 */
    data class RenderResult(
        /** 渲染后的字幕 Bitmap (已裁剪到内容边界) */
        val bitmap: Bitmap,
        /** 字幕内容在原始帧中的左偏移 */
        val left: Int,
        /** 字幕内容在原始帧中的上偏移 */
        val top: Int,
        /** 字幕内容在原始帧中的右边界 */
        val right: Int,
        /** 字幕内容在原始帧中的下边界 */
        val bottom: Int
    ) {
        /** 裁剪区域宽度 */
        val width: Int get() = bitmap.width
        /** 裁剪区域高度 */
        val height: Int get() = bitmap.height
    }

    /**
     * 初始化 libass 渲染器
     * @return 是否初始化成功
     */
    fun init(): Boolean {
        if (nativePtr != 0L) {
            Log.w(TAG, "重复初始化, 先释放旧资源")
            destroy()
        }
        nativePtr = nativeInit()
        if (nativePtr == 0L) {
            Log.e(TAG, "nativeInit 返回空指针")
            return false
        }
        Log.i(TAG, "初始化成功")
        return true
    }

    /**
     * 设置渲染帧尺寸
     *
     * 应与 ASS 文件中的 PlayResX/PlayResY 匹配，
     * 或者设置为显示区域的实际像素尺寸。
     * libass 会根据此尺寸自动缩放坐标。
     */
    fun setFrameSize(width: Int, height: Int) {
        if (nativePtr == 0L) return
        frameWidth = width
        frameHeight = height
        nativeSetFrameSize(nativePtr, width, height)
    }

    /**
     * 加载 ASS 字幕内容
     * @param assContent ASS 文件的原始文本内容
     * @return 是否加载成功
     */
    fun loadTrack(assContent: String): Boolean {
        if (nativePtr == 0L) {
            Log.e(TAG, "尚未初始化")
            return false
        }
        trackLoaded = nativeLoadTrack(nativePtr, assContent)
        lastBitmap = null
        lastTimeMs = -1
        return trackLoaded
    }

    /**
     * 添加字体文件
     * @param fontName 字体名称 (可为 null)
     * @param fontPath 字体文件路径
     */
    fun addFont(fontName: String?, fontPath: String) {
        if (nativePtr == 0L) return
        nativeAddFont(nativePtr, fontName, fontPath)
    }

    /**
     * 渲染指定时间点的字幕
     *
     * @param timeMs 当前播放时间 (毫秒)
     * @return 渲染结果，如果该时间点没有字幕则返回 null
     */
    fun renderFrame(timeMs: Long): RenderResult? {
        if (nativePtr == 0L || !trackLoaded) return null
        if (frameWidth <= 0 || frameHeight <= 0) return null

        val outRect = IntArray(4)
        val bitmap = nativeRenderFrame(nativePtr, timeMs, outRect)
            ?: return null

        lastBitmap = bitmap
        lastTimeMs = timeMs

        return RenderResult(
            bitmap = bitmap,
            left = outRect[0],
            top = outRect[1],
            right = outRect[2],
            bottom = outRect[3]
        )
    }

    /**
     * 释放所有资源
     */
    fun destroy() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0
            trackLoaded = false
            lastBitmap = null
            lastTimeMs = -1
            Log.i(TAG, "资源已释放")
        }
    }

    override fun close() {
        destroy()
    }

    /**
     * 是否已初始化
     */
    val isInitialized: Boolean get() = nativePtr != 0L

    /**
     * 是否已加载字幕
     */
    val isTrackLoaded: Boolean get() = trackLoaded

    // ============================================================
    // JNI 原生方法声明
    // ============================================================

    private external fun nativeInit(): Long
    private external fun nativeSetFrameSize(ptr: Long, width: Int, height: Int)
    private external fun nativeLoadTrack(ptr: Long, assContent: String): Boolean
    private external fun nativeAddFont(ptr: Long, fontName: String?, fontPath: String)
    private external fun nativeRenderFrame(ptr: Long, timeMs: Long, outRect: IntArray): Bitmap?
    private external fun nativeHasChange(ptr: Long, timeMs: Long): Boolean
    private external fun nativeDestroy(ptr: Long)
}
