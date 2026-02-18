package com.hx.nekomimi.subtitle

import com.hx.nekomimi.subtitle.model.SubtitleCue
import java.io.File

/**
 * SRT 字幕解析器
 *
 * SRT 格式:
 * ```
 * 1
 * 00:00:01,000 --> 00:00:04,000
 * 第一行歌词
 *
 * 2
 * 00:00:05,000 --> 00:00:08,000
 * 第二行歌词
 * ```
 */
object SrtParser {

    private val timePattern = Regex(
        """(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})"""
    )

    /**
     * 解析 SRT 文件
     * @param file SRT 文件
     * @return 字幕条目列表，按开始时间排序
     */
    fun parse(file: File): List<SubtitleCue> {
        if (!file.exists()) return emptyList()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析 SRT 文本内容
     */
    fun parse(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        // 按空行分割字幕块
        val blocks = content.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (block in blocks) {
            val lines = block.split("\n")
            if (lines.size < 2) continue

            // 查找时间行 (可能第一行是序号也可能不是)
            var timeLineIndex = -1
            for (i in lines.indices) {
                if (timePattern.containsMatchIn(lines[i])) {
                    timeLineIndex = i
                    break
                }
            }
            if (timeLineIndex < 0) continue

            val match = timePattern.find(lines[timeLineIndex]) ?: continue
            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured

            val startMs = timeToMs(h1.toInt(), m1.toInt(), s1.toInt(), ms1.toInt())
            val endMs = timeToMs(h2.toInt(), m2.toInt(), s2.toInt(), ms2.toInt())

            // 时间行之后的所有行都是字幕文本
            val text = lines.drop(timeLineIndex + 1)
                .joinToString("\n")
                .trim()
                // 移除 HTML 标签 (<b>, <i>, <u>, <font> 等)
                .replace(Regex("<[^>]+>"), "")

            if (text.isNotEmpty()) {
                cues.add(
                    SubtitleCue(
                        startMs = startMs,
                        endMs = endMs,
                        text = text
                    )
                )
            }
        }

        return cues.sortedBy { it.startMs }
    }

    private fun timeToMs(h: Int, m: Int, s: Int, ms: Int): Long =
        h * 3600_000L + m * 60_000L + s * 1000L + ms
}
