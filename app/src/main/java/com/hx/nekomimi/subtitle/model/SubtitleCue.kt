package com.hx.nekomimi.subtitle.model

/**
 * 字幕条目
 * 统一表示 SRT 字幕的一行
 */
data class SubtitleCue(
    /** 开始时间 (毫秒) */
    val startMs: Long,
    /** 结束时间 (毫秒) */
    val endMs: Long,
    /** 纯文本内容 */
    val text: String
)
