package com.hx.nekomimi.subtitle

/**
 * 字幕条目
 * @param startMs 开始时间（毫秒）
 * @param endMs 结束时间（毫秒）
 * @param text 字幕文本
 */
data class SubtitleEntry(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * 字幕解析器接口
 */
interface SubtitleParser {
    fun parse(content: String): List<SubtitleEntry>
}

/**
 * SRT 字幕解析器
 *
 * SRT 格式示例:
 * 1
 * 00:00:01,000 --> 00:00:04,000
 * 这是第一行字幕
 *
 * 2
 * 00:00:05,000 --> 00:00:08,000
 * 这是第二行字幕
 */
class SrtParser : SubtitleParser {

    private val timePattern = Regex(
        """(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})"""
    )

    override fun parse(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val blocks = content.trim().split(Regex("""\r?\n\r?\n"""))

        for (block in blocks) {
            val lines = block.trim().split(Regex("""\r?\n"""))
            if (lines.size < 2) continue

            // 查找时间行
            var timeLineIndex = -1
            for (i in lines.indices) {
                if (timePattern.containsMatchIn(lines[i])) {
                    timeLineIndex = i
                    break
                }
            }
            if (timeLineIndex == -1) continue

            val match = timePattern.find(lines[timeLineIndex]) ?: continue
            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured

            val startMs = timeToMs(h1.toInt(), m1.toInt(), s1.toInt(), ms1.toInt())
            val endMs = timeToMs(h2.toInt(), m2.toInt(), s2.toInt(), ms2.toInt())

            // 时间行之后的所有行是字幕文本
            val text = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) {
                entries.add(SubtitleEntry(startMs, endMs, text))
            }
        }

        return entries.sortedBy { it.startMs }
    }

    private fun timeToMs(h: Int, m: Int, s: Int, ms: Int): Long {
        return (h * 3600000L) + (m * 60000L) + (s * 1000L) + ms
    }
}

/**
 * ASS/SSA 字幕解析器（纯文本解析，不使用 native libass）
 *
 * ASS 格式示例:
 * [Events]
 * Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
 * Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,这是字幕文本
 */
class AssParser : SubtitleParser {

    private val timePattern = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""")

    override fun parse(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val lines = content.split(Regex("""\r?\n"""))

        var inEvents = false
        var textIndex = -1

        for (line in lines) {
            val trimmed = line.trim()

            // 检测 [Events] 段
            if (trimmed.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                continue
            }

            // 如果遇到其他段落标记，退出 Events 段
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inEvents) inEvents = false
                continue
            }

            if (!inEvents) continue

            // 解析 Format 行，确定 Text 字段的位置
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                val fields = trimmed.substringAfter(":").split(",").map { it.trim() }
                textIndex = fields.indexOfFirst { it.equals("Text", ignoreCase = true) }
                continue
            }

            // 解析 Dialogue 行
            if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                val afterDialogue = trimmed.substringAfter(":")
                // Text 字段可能包含逗号，所以需要限制 split 数量
                val maxSplit = if (textIndex > 0) textIndex else 9
                val parts = afterDialogue.split(",", limit = maxSplit + 1)

                if (parts.size < 3) continue

                val startTime = parseAssTime(parts[1].trim()) ?: continue
                val endTime = parseAssTime(parts[2].trim()) ?: continue

                // 获取文本字段（最后一个字段）
                val text = if (parts.size > maxSplit) {
                    parts[maxSplit].trim()
                } else {
                    parts.last().trim()
                }

                // 清理 ASS 样式标签
                val cleanText = cleanAssText(text)
                if (cleanText.isNotEmpty()) {
                    entries.add(SubtitleEntry(startTime, endTime, cleanText))
                }
            }
        }

        return entries.sortedBy { it.startMs }
    }

    private fun parseAssTime(timeStr: String): Long? {
        val match = timePattern.find(timeStr) ?: return null
        val (h, m, s, cs) = match.destructured
        return (h.toInt() * 3600000L) + (m.toInt() * 60000L) + (s.toInt() * 1000L) + (cs.toInt() * 10L)
    }

    /**
     * 清理 ASS 格式标签
     * 例如: {\i1}斜体文本{\i0} -> 斜体文本
     *       {\b1}粗体{\b0} -> 粗体
     *       \N -> 换行
     */
    private fun cleanAssText(text: String): String {
        return text
            .replace(Regex("""\{[^}]*\}"""), "") // 移除 {} 内的样式标签
            .replace("\\N", "\n")                 // ASS 换行符
            .replace("\\n", "\n")                 // ASS 软换行
            .replace("\\h", " ")                  // ASS 硬空格
            .trim()
    }
}

/**
 * 字幕管理工具
 */
object SubtitleHelper {

    /**
     * 根据文件扩展名选择解析器并解析字幕
     */
    fun parseSubtitle(content: String, fileName: String): List<SubtitleEntry> {
        val parser: SubtitleParser = when {
            fileName.endsWith(".srt", ignoreCase = true) -> SrtParser()
            fileName.endsWith(".ass", ignoreCase = true) -> AssParser()
            fileName.endsWith(".ssa", ignoreCase = true) -> AssParser()
            else -> SrtParser() // 默认使用 SRT 解析
        }
        return parser.parse(content)
    }

    /**
     * 根据当前播放时间获取对应的字幕
     */
    fun getCurrentSubtitle(entries: List<SubtitleEntry>, positionMs: Long): SubtitleEntry? {
        return entries.firstOrNull { positionMs in it.startMs..it.endMs }
    }

    /**
     * 获取当前字幕在列表中的索引
     */
    fun getCurrentSubtitleIndex(entries: List<SubtitleEntry>, positionMs: Long): Int {
        return entries.indexOfFirst { positionMs in it.startMs..it.endMs }
    }
}
