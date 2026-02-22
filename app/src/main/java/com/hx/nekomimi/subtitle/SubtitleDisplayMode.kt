package com.hx.nekomimi.subtitle

/**
 * 字幕显示模式
 */
enum class SubtitleDisplayMode {
    /** 歌词模式 — 全部字幕列表，当前行垂直居中高亮，手动滚动可解除居中 */
    LYRIC,

    /** 双行字幕模式 — 仅显示当前行和下一行 */
    DUAL_LINE,

    /** 对话模式 — 解析 [说话人] 内容，渲染为聊天气泡 */
    CHAT;

    companion object {
        /** 默认显示模式 */
        val DEFAULT = LYRIC

        fun fromOrdinal(ordinal: Int): SubtitleDisplayMode {
            return entries.getOrElse(ordinal) { DEFAULT }
        }
    }
}
