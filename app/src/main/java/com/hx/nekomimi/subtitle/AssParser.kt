package com.hx.nekomimi.subtitle

import com.hx.nekomimi.subtitle.model.*
import java.io.File

/**
 * ASS/SSA 字幕解析器
 *
 * 支持解析:
 * - [Script Info]: 脚本元信息
 * - [V4+ Styles]: 样式定义 (字体/颜色/描边/阴影/对齐)
 * - [Events]: 对话事件 + 内联特效标签
 *
 * 支持的内联特效标签:
 * \c, \1c~\4c (颜色), \b (加粗), \i (斜体), \fs (字号),
 * \an (对齐), \fad (淡入淡出), \pos (定位), \move (移动),
 * \bord (描边), \shad (阴影), \alpha, \1a~\4a (透明度),
 * \frz (Z轴旋转), \fscx/\fscy (缩放),
 * \k/\K/\kf/\ko (卡拉OK)
 */
object AssParser {

    /** 解析结果 */
    data class AssDocument(
        val scriptInfo: Map<String, String> = emptyMap(),
        val styles: Map<String, AssStyle> = emptyMap(),
        val cues: List<SubtitleCue> = emptyList(),
        /** 视频分辨率 (用于 \pos/\move 坐标转换) */
        val playResX: Int = 384,
        val playResY: Int = 288
    )

    /**
     * 解析 ASS 文件
     */
    fun parse(file: File): AssDocument {
        if (!file.exists()) return AssDocument()
        return parse(file.readText())
    }

    /**
     * 解析 ASS 文本内容
     */
    fun parse(content: String): AssDocument {
        val lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        var currentSection = ""
        val scriptInfo = mutableMapOf<String, String>()
        val styles = mutableMapOf<String, AssStyle>()
        val cues = mutableListOf<SubtitleCue>()

        var styleFormat: List<String>? = null
        var eventFormat: List<String>? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue

            // 段头
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.lowercase()
                continue
            }

            when (currentSection) {
                "[script info]" -> {
                    val colonIdx = trimmed.indexOf(':')
                    if (colonIdx > 0) {
                        val key = trimmed.substring(0, colonIdx).trim()
                        val value = trimmed.substring(colonIdx + 1).trim()
                        scriptInfo[key] = value
                    }
                }

                "[v4+ styles]", "[v4 styles]" -> {
                    when {
                        trimmed.startsWith("Format:", ignoreCase = true) -> {
                            styleFormat = trimmed.substringAfter(":").split(",").map { it.trim() }
                        }
                        trimmed.startsWith("Style:", ignoreCase = true) -> {
                            val values = trimmed.substringAfter(":").split(",")
                            val fmt = styleFormat ?: defaultStyleFormat()
                            val style = AssStyle.parse(fmt, values)
                            styles[style.name] = style
                        }
                    }
                }

                "[events]" -> {
                    when {
                        trimmed.startsWith("Format:", ignoreCase = true) -> {
                            eventFormat = trimmed.substringAfter(":").split(",").map { it.trim() }
                        }
                        trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                            val fmt = eventFormat ?: defaultEventFormat()
                            val cue = parseDialogue(trimmed, fmt)
                            if (cue != null) cues.add(cue)
                        }
                    }
                }
            }
        }

        val playResX = scriptInfo["PlayResX"]?.toIntOrNull() ?: 384
        val playResY = scriptInfo["PlayResY"]?.toIntOrNull() ?: 288

        return AssDocument(
            scriptInfo = scriptInfo,
            styles = styles,
            cues = cues.sortedBy { it.startMs },
            playResX = playResX,
            playResY = playResY
        )
    }

    /**
     * 解析 Dialogue 行
     * Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
     */
    private fun parseDialogue(line: String, format: List<String>): SubtitleCue? {
        val rawValues = line.substringAfter(":")
        // Text 字段可能含逗号，需要特殊处理：前 N-1 个字段用逗号分割，剩余全部归入 Text
        val parts = rawValues.split(",", limit = format.size)
        if (parts.size < format.size) return null

        val map = mutableMapOf<String, String>()
        for (i in format.indices) {
            map[format[i]] = parts.getOrElse(i) { "" }.trim()
        }

        val startMs = parseAssTime(map["Start"] ?: return null)
        val endMs = parseAssTime(map["End"] ?: return null)
        val rawText = map["Text"] ?: return null
        val styleName = map["Style"]

        // 解析内联特效标签
        val effects = parseInlineEffects(rawText)
        // 解析卡拉OK音节 (如果存在卡拉OK标签)
        val karaokeSyllables = parseKaraokeSyllables(rawText)
        // 提取纯文本 (去除 {} 标签块, 替换 \N 为换行)
        val text = rawText
            .replace(Regex("\\{[^}]*\\}"), "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .trim()

        if (text.isEmpty()) return null

        return SubtitleCue(
            startMs = startMs,
            endMs = endMs,
            text = text,
            rawText = rawText,
            styleName = styleName,
            effects = effects,
            karaokeSyllables = karaokeSyllables
        )
    }

    /**
     * 解析卡拉OK音节
     * 格式: {\k50}音节1{\k30}音节2{\kf20}音节3
     * 返回每个音节的文本、时间偏移和持续时间
     */
    private fun parseKaraokeSyllables(rawText: String): List<KaraokeSyllable> {
        val syllables = mutableListOf<KaraokeSyllable>()
        val tagBlockPattern = Regex("\\{([^}]*)\\}([^{]*)")
        var currentOffsetMs = 0L
        var currentEffects = mutableListOf<AssEffect>()

        for (match in tagBlockPattern.findAll(rawText)) {
            val tagBlock = match.groupValues[1]
            val textAfter = match.groupValues[2]
                .replace("\\N", "\n")
                .replace("\\n", "\n")

            // 解析该块内的所有标签
            val blockEffects = mutableListOf<AssEffect>()
            parseTagBlock(tagBlock, blockEffects)

            // 更新当前累积的特效（非卡拉OK特效会累积）
            for (effect in blockEffects) {
                if (effect !is AssEffect.Karaoke) {
                    currentEffects.add(effect)
                }
            }

            // 查找卡拉OK标签
            val kTag = blockEffects.filterIsInstance<AssEffect.Karaoke>().firstOrNull()
            if (kTag != null && textAfter.isNotEmpty()) {
                val durationMs = kTag.durationCs * 10L // 厘秒转毫秒
                syllables.add(
                    KaraokeSyllable(
                        text = textAfter,
                        startOffsetMs = currentOffsetMs,
                        durationMs = durationMs,
                        type = kTag.type,
                        effects = currentEffects.toList()
                    )
                )
                currentOffsetMs += durationMs
                // 清除已应用到音节的非持久特效
                currentEffects = currentEffects.filter { effect ->
                    effect is AssEffect.Color || effect is AssEffect.Alpha
                }.toMutableList()
            }
        }

        return syllables
    }

    /**
     * 解析 ASS 时间格式: H:MM:SS.CC (centiseconds)
     */
    private fun parseAssTime(time: String): Long {
        val parts = time.trim().split(":", ".")
        if (parts.size < 4) return 0
        val h = parts[0].trim().toLongOrNull() ?: 0
        val m = parts[1].trim().toLongOrNull() ?: 0
        val s = parts[2].trim().toLongOrNull() ?: 0
        val cs = parts[3].trim().toLongOrNull() ?: 0 // centiseconds (1/100秒)
        return h * 3600_000 + m * 60_000 + s * 1000 + cs * 10
    }

    /**
     * 解析内联特效标签
     * 格式: {\tag1\tag2\tag3}
     */
    private fun parseInlineEffects(text: String): List<AssEffect> {
        val effects = mutableListOf<AssEffect>()
        val tagBlockPattern = Regex("\\{([^}]*)\\}")

        for (blockMatch in tagBlockPattern.findAll(text)) {
            val tagBlock = blockMatch.groupValues[1]
            parseTagBlock(tagBlock, effects)
        }

        return effects
    }

    /**
     * 解析一个 {} 块内的所有标签
     */
    private fun parseTagBlock(block: String, effects: MutableList<AssEffect>) {
        // 用 \ 分割标签 (第一个空字符串跳过)
        val tags = block.split("\\").filter { it.isNotEmpty() }

        for (tag in tags) {
            parseTag(tag, effects)
        }
    }

    /**
     * 解析单个 ASS 标签
     */
    private fun parseTag(tag: String, effects: MutableList<AssEffect>) {
        when {
            // 卡拉OK: \k, \K, \kf, \ko
            tag.startsWith("kf") -> {
                val dur = tag.substring(2).toIntOrNull() ?: return
                effects.add(AssEffect.Karaoke(dur, KaraokeType.SWEEP))
            }
            tag.startsWith("ko") -> {
                val dur = tag.substring(2).toIntOrNull() ?: return
                effects.add(AssEffect.Karaoke(dur, KaraokeType.OUTLINE))
            }
            tag.startsWith("K") && tag.length > 1 && tag[1].isDigit() -> {
                val dur = tag.substring(1).toIntOrNull() ?: return
                effects.add(AssEffect.Karaoke(dur, KaraokeType.SWEEP))
            }
            tag.startsWith("k") && tag.length > 1 && tag[1].isDigit() -> {
                val dur = tag.substring(1).toIntOrNull() ?: return
                effects.add(AssEffect.Karaoke(dur, KaraokeType.FILL))
            }

            // 颜色: \c, \1c, \2c, \3c, \4c
            tag.startsWith("1c") || tag.startsWith("2c") ||
            tag.startsWith("3c") || tag.startsWith("4c") -> {
                val type = tag[0].digitToInt()
                val color = AssStyle.parseAssColor(tag.substring(2))
                effects.add(AssEffect.Color(type, color))
            }
            tag.startsWith("c&") -> {
                val color = AssStyle.parseAssColor(tag.substring(1))
                effects.add(AssEffect.Color(1, color))
            }
            tag == "c" || (tag.startsWith("c") && tag.length > 1 && !tag[1].isLetter()) -> {
                // \c 不带参数或 \c 后跟颜色值
                if (tag.length > 1) {
                    val color = AssStyle.parseAssColor(tag.substring(1))
                    effects.add(AssEffect.Color(1, color))
                }
            }

            // 透明度: \alpha, \1a, \2a, \3a, \4a
            tag.startsWith("alpha") -> {
                val hex = tag.substring(5).replace("&H", "").replace("&h", "").replace("&", "")
                val value = hex.toIntOrNull(16) ?: return
                effects.add(AssEffect.Alpha(0, 255 - value))
            }
            tag.matches(Regex("[1-4]a.*")) -> {
                val type = tag[0].digitToInt()
                val hex = tag.substring(2).replace("&H", "").replace("&h", "").replace("&", "")
                val value = hex.toIntOrNull(16) ?: return
                effects.add(AssEffect.Alpha(type, 255 - value))
            }

            // 加粗: \b0, \b1
            tag.startsWith("b") && tag.length == 2 && (tag[1] == '0' || tag[1] == '1') -> {
                effects.add(AssEffect.Bold(tag[1] == '1'))
            }
            // 粗体权重: \b<weight>
            tag.startsWith("b") && tag.length > 2 && tag.substring(1).all { it.isDigit() } -> {
                val weight = tag.substring(1).toIntOrNull() ?: 0
                effects.add(AssEffect.Bold(weight >= 700))
            }

            // 斜体: \i0, \i1
            tag.startsWith("i") && tag.length == 2 && (tag[1] == '0' || tag[1] == '1') -> {
                effects.add(AssEffect.Italic(tag[1] == '1'))
            }

            // 字号: \fs<size>
            tag.startsWith("fs") && !tag.startsWith("fsc") -> {
                val size = tag.substring(2).toFloatOrNull() ?: return
                effects.add(AssEffect.FontSize(size))
            }

            // 缩放: \fscx, \fscy
            tag.startsWith("fscx") -> {
                val scale = tag.substring(4).toFloatOrNull() ?: return
                effects.add(AssEffect.Scale(scaleX = scale))
            }
            tag.startsWith("fscy") -> {
                val scale = tag.substring(4).toFloatOrNull() ?: return
                effects.add(AssEffect.Scale(scaleY = scale))
            }

            // 旋转: \frz<angle>
            tag.startsWith("frz") -> {
                val angle = tag.substring(3).toFloatOrNull() ?: return
                effects.add(AssEffect.RotationZ(angle))
            }

            // 描边: \bord<size>
            tag.startsWith("bord") -> {
                val size = tag.substring(4).toFloatOrNull() ?: return
                effects.add(AssEffect.Border(size))
            }

            // 阴影: \shad<depth>
            tag.startsWith("shad") -> {
                val depth = tag.substring(4).toFloatOrNull() ?: return
                effects.add(AssEffect.Shadow(depth))
            }

            // 对齐: \an<pos>
            tag.startsWith("an") && tag.length <= 4 -> {
                val pos = tag.substring(2).toIntOrNull() ?: return
                if (pos in 1..9) effects.add(AssEffect.Alignment(pos))
            }

            // 淡入淡出: \fad(fadein,fadeout)
            tag.startsWith("fad(") && tag.endsWith(")") -> {
                val params = tag.substring(4, tag.length - 1).split(",")
                if (params.size >= 2) {
                    val fadeIn = params[0].trim().toLongOrNull() ?: 0
                    val fadeOut = params[1].trim().toLongOrNull() ?: 0
                    effects.add(AssEffect.Fade(fadeIn, fadeOut))
                }
            }

            // 定位: \pos(x,y)
            tag.startsWith("pos(") && tag.endsWith(")") -> {
                val params = tag.substring(4, tag.length - 1).split(",")
                if (params.size >= 2) {
                    val x = params[0].trim().toFloatOrNull() ?: return
                    val y = params[1].trim().toFloatOrNull() ?: return
                    effects.add(AssEffect.Position(x, y))
                }
            }

            // 移动: \move(x1,y1,x2,y2[,t1,t2])
            tag.startsWith("move(") && tag.endsWith(")") -> {
                val params = tag.substring(5, tag.length - 1).split(",")
                if (params.size >= 4) {
                    val x1 = params[0].trim().toFloatOrNull() ?: return
                    val y1 = params[1].trim().toFloatOrNull() ?: return
                    val x2 = params[2].trim().toFloatOrNull() ?: return
                    val y2 = params[3].trim().toFloatOrNull() ?: return
                    val t1 = params.getOrNull(4)?.trim()?.toLongOrNull() ?: 0
                    val t2 = params.getOrNull(5)?.trim()?.toLongOrNull() ?: 0
                    effects.add(AssEffect.Move(x1, y1, x2, y2, t1, t2))
                }
            }
        }
    }

    private fun defaultStyleFormat() = listOf(
        "Name", "Fontname", "Fontsize", "PrimaryColour", "SecondaryColour",
        "OutlineColour", "BackColour", "Bold", "Italic", "Underline", "StrikeOut",
        "ScaleX", "ScaleY", "Spacing", "Angle", "BorderStyle", "Outline", "Shadow",
        "Alignment", "MarginL", "MarginR", "MarginV", "Encoding"
    )

    private fun defaultEventFormat() = listOf(
        "Layer", "Start", "End", "Style", "Name",
        "MarginL", "MarginR", "MarginV", "Effect", "Text"
    )
}
