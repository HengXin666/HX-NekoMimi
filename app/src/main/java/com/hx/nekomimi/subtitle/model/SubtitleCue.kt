package com.hx.nekomimi.subtitle.model

/**
 * 字幕条目
 * 统一表示 SRT 和 ASS 的一行字幕
 */
data class SubtitleCue(
    /** 开始时间 (毫秒) */
    val startMs: Long,
    /** 结束时间 (毫秒) */
    val endMs: Long,
    /** 纯文本内容 (去除特效标签后) */
    val text: String,
    /** 原始文本 (含 ASS 特效标签) */
    val rawText: String = text,
    /** ASS 样式名 (仅 ASS 格式) */
    val styleName: String? = null,
    /** 内联特效覆盖 (解析后的 ASS 特效) */
    val effects: List<AssEffect> = emptyList(),
    /** 卡拉OK音节列表 (仅卡拉OK特效字幕) */
    val karaokeSyllables: List<KaraokeSyllable> = emptyList()
)

/**
 * 卡拉OK音节
 * 表示一个带时间信息的音节，用于逐字填充效果
 */
data class KaraokeSyllable(
    /** 音节文本 */
    val text: String,
    /** 相对于字幕开始时间的偏移量 (毫秒) */
    val startOffsetMs: Long,
    /** 持续时间 (毫秒) */
    val durationMs: Long,
    /** 卡拉OK类型 */
    val type: KaraokeType = KaraokeType.FILL,
    /** 该音节前的特效标签 (应用于此音节) */
    val effects: List<AssEffect> = emptyList()
) {
    /** 音节结束时间偏移 */
    val endOffsetMs: Long get() = startOffsetMs + durationMs
}

/**
 * ASS 特效标签 (内联覆盖)
 */
sealed class AssEffect {
    /** 颜色: \c&HBBGGRR& 或 \1c, \2c, \3c, \4c */
    data class Color(val colorType: Int, val argb: Int) : AssEffect()
    /** 加粗: \b1 / \b0 */
    data class Bold(val enabled: Boolean) : AssEffect()
    /** 斜体: \i1 / \i0 */
    data class Italic(val enabled: Boolean) : AssEffect()
    /** 字号: \fs<size> */
    data class FontSize(val size: Float) : AssEffect()
    /** 对齐: \an<pos> (numpad 位置) */
    data class Alignment(val position: Int) : AssEffect()
    /** 淡入淡出: \fad(fadein, fadeout) 毫秒 */
    data class Fade(val fadeInMs: Long, val fadeOutMs: Long) : AssEffect()
    /** 定位: \pos(x, y) */
    data class Position(val x: Float, val y: Float) : AssEffect()
    /** 移动: \move(x1, y1, x2, y2[, t1, t2]) */
    data class Move(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val t1Ms: Long = 0, val t2Ms: Long = 0
    ) : AssEffect()
    /** 描边宽度: \bord<size> */
    data class Border(val size: Float) : AssEffect()
    /** 阴影: \shad<depth> */
    data class Shadow(val depth: Float) : AssEffect()
    /** 透明度: \alpha&HXX& 或 \1a, \2a 等 */
    data class Alpha(val alphaType: Int, val value: Int) : AssEffect()
    /** 旋转: \frz<angle> */
    data class RotationZ(val angle: Float) : AssEffect()
    /** 缩放: \fscx<percent> 和 \fscy<percent> */
    data class Scale(val scaleX: Float? = null, val scaleY: Float? = null) : AssEffect()
    /** 卡拉OK效果: \k<duration> 或 \K<duration> 或 \kf<duration> */
    data class Karaoke(val durationCs: Int, val type: KaraokeType = KaraokeType.FILL) : AssEffect()
}

enum class KaraokeType {
    /** \k: 逐字填充 */
    FILL,
    /** \K 或 \kf: 逐字渐变 */
    SWEEP,
    /** \ko: 逐字描边 */
    OUTLINE
}
