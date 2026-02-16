package com.hx.nekomimi.subtitle

import com.hx.nekomimi.subtitle.model.AssStyle
import com.hx.nekomimi.subtitle.model.SubtitleCue
import java.io.File

/**
 * 字幕管理器
 * 自动查找音频文件对应的字幕文件并加载
 * 支持 SRT 和 ASS 格式
 */
object SubtitleManager {

    /** 支持的字幕扩展名 */
    private val subtitleExtensions = listOf("ass", "ssa", "srt")

    /**
     * 字幕加载结果
     */
    sealed class SubtitleResult {
        /** 无字幕 */
        data object None : SubtitleResult()
        /** SRT 字幕 */
        data class Srt(val cues: List<SubtitleCue>) : SubtitleResult()
        /** ASS 字幕 (含样式和特效) */
        data class Ass(
            val document: AssParser.AssDocument,
            val cues: List<SubtitleCue>,
            val styles: Map<String, AssStyle>
        ) : SubtitleResult()
    }

    /**
     * 为指定音频文件查找并加载字幕
     * 查找策略: 同目录下同名文件，优先 ASS > SRT
     *
     * @param audioFilePath 音频文件路径
     * @return 字幕加载结果
     */
    fun loadForAudio(audioFilePath: String): SubtitleResult {
        val audioFile = File(audioFilePath)
        val baseName = audioFile.nameWithoutExtension
        val dir = audioFile.parentFile ?: return SubtitleResult.None

        // 优先查找 ASS，再查找 SRT
        for (ext in subtitleExtensions) {
            val subtitleFile = File(dir, "$baseName.$ext")
            if (subtitleFile.exists() && subtitleFile.isFile) {
                return when (ext) {
                    "ass", "ssa" -> {
                        val doc = AssParser.parse(subtitleFile)
                        SubtitleResult.Ass(doc, doc.cues, doc.styles)
                    }
                    "srt" -> {
                        val cues = SrtParser.parse(subtitleFile)
                        SubtitleResult.Srt(cues)
                    }
                    else -> SubtitleResult.None
                }
            }
        }

        return SubtitleResult.None
    }

    /**
     * 根据当前播放位置获取应显示的字幕行
     */
    fun getActiveCues(cues: List<SubtitleCue>, positionMs: Long): List<SubtitleCue> {
        return cues.filter { positionMs in it.startMs..it.endMs }
    }

    /**
     * 查找当前位置最近的字幕索引 (用于歌词列表滚动定位)
     * @return 当前高亮的字幕索引，-1 表示没有
     */
    fun findCurrentIndex(cues: List<SubtitleCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1

        // 二分查找
        var low = 0
        var high = cues.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) / 2
            when {
                positionMs >= cues[mid].startMs && positionMs <= cues[mid].endMs -> return mid
                positionMs < cues[mid].startMs -> high = mid - 1
                else -> {
                    result = mid
                    low = mid + 1
                }
            }
        }

        return result
    }
}
