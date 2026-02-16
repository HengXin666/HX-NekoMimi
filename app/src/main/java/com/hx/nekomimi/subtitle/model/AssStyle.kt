package com.hx.nekomimi.subtitle.model

/**
 * ASS 样式定义
 * 对应 ASS [V4+ Styles] 段中的一行样式
 */
data class AssStyle(
    val name: String,
    val fontName: String = "Arial",
    val fontSize: Float = 20f,
    /** 主要颜色 (ARGB) */
    val primaryColor: Int = 0xFFFFFFFF.toInt(),
    /** 次要颜色 / 卡拉OK前颜色 (ARGB) */
    val secondaryColor: Int = 0xFF00FFFF.toInt(),
    /** 描边颜色 (ARGB) */
    val outlineColor: Int = 0xFF000000.toInt(),
    /** 阴影颜色 (ARGB) */
    val shadowColor: Int = 0x80000000.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeOut: Boolean = false,
    val scaleX: Float = 100f,
    val scaleY: Float = 100f,
    val spacing: Float = 0f,
    val angle: Float = 0f,
    val borderStyle: Int = 1,
    val outline: Float = 2f,
    val shadow: Float = 1f,
    /** 对齐 (numpad 位置，1-9) */
    val alignment: Int = 2,
    val marginL: Int = 10,
    val marginR: Int = 10,
    val marginV: Int = 10,
    val encoding: Int = 1
) {
    companion object {
        /**
         * 从 ASS 样式行解析
         * Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour,
         *         Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle,
         *         BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
         */
        fun parse(formatFields: List<String>, valueFields: List<String>): AssStyle {
            val map = mutableMapOf<String, String>()
            for (i in formatFields.indices) {
                if (i < valueFields.size) {
                    map[formatFields[i].trim()] = valueFields[i].trim()
                }
            }
            return AssStyle(
                name = map["Name"] ?: "Default",
                fontName = map["Fontname"] ?: "Arial",
                fontSize = map["Fontsize"]?.toFloatOrNull() ?: 20f,
                primaryColor = parseAssColor(map["PrimaryColour"]),
                secondaryColor = parseAssColor(map["SecondaryColour"]),
                outlineColor = parseAssColor(map["OutlineColour"]),
                shadowColor = parseAssColor(map["BackColour"]),
                bold = map["Bold"] == "-1" || map["Bold"] == "1",
                italic = map["Italic"] == "-1" || map["Italic"] == "1",
                underline = map["Underline"] == "-1" || map["Underline"] == "1",
                strikeOut = map["StrikeOut"] == "-1" || map["StrikeOut"] == "1",
                scaleX = map["ScaleX"]?.toFloatOrNull() ?: 100f,
                scaleY = map["ScaleY"]?.toFloatOrNull() ?: 100f,
                spacing = map["Spacing"]?.toFloatOrNull() ?: 0f,
                angle = map["Angle"]?.toFloatOrNull() ?: 0f,
                borderStyle = map["BorderStyle"]?.toIntOrNull() ?: 1,
                outline = map["Outline"]?.toFloatOrNull() ?: 2f,
                shadow = map["Shadow"]?.toFloatOrNull() ?: 1f,
                alignment = map["Alignment"]?.toIntOrNull() ?: 2,
                marginL = map["MarginL"]?.toIntOrNull() ?: 10,
                marginR = map["MarginR"]?.toIntOrNull() ?: 10,
                marginV = map["MarginV"]?.toIntOrNull() ?: 10,
                encoding = map["Encoding"]?.toIntOrNull() ?: 1
            )
        }

        /**
         * 解析 ASS 颜色格式: &HAABBGGRR 或 &HBBGGRR
         * 转换为 Android ARGB int
         */
        fun parseAssColor(color: String?): Int {
            if (color == null) return 0xFFFFFFFF.toInt()
            val hex = color.replace("&H", "").replace("&h", "").replace("&", "")
                .trimStart('0').ifEmpty { "0" }
            return try {
                val value = hex.toLong(16)
                when {
                    // &HAABBGGRR (8位: 含透明度)
                    hex.length > 6 -> {
                        val aa = ((value shr 24) and 0xFF).toInt()
                        val bb = ((value shr 16) and 0xFF).toInt()
                        val gg = ((value shr 8) and 0xFF).toInt()
                        val rr = (value and 0xFF).toInt()
                        // ASS alpha: 00=不透明, FF=全透明 → 反转为 Android alpha
                        val alpha = 255 - aa
                        (alpha shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                    // &HBBGGRR (6位: 无透明度，默认不透明)
                    else -> {
                        val bb = ((value shr 16) and 0xFF).toInt()
                        val gg = ((value shr 8) and 0xFF).toInt()
                        val rr = (value and 0xFF).toInt()
                        (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            } catch (e: Exception) {
                0xFFFFFFFF.toInt()
            }
        }
    }
}
