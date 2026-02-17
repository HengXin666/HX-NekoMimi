package com.hx.nekomimi.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hx.nekomimi.subtitle.model.AssEffect
import com.hx.nekomimi.subtitle.model.AssStyle
import com.hx.nekomimi.subtitle.model.KaraokeSyllable
import com.hx.nekomimi.subtitle.model.KaraokeType
import com.hx.nekomimi.subtitle.model.SubtitleCue
import androidx.compose.ui.graphics.Paint.Companion.asFrameworkPaint
import android.graphics.Paint as AndroidPaint

/**
 * 卡拉OK文本渲染组件
 * 支持逐字填充、渐变、描边三种卡拉OK效果
 */
@Composable
fun KaraokeText(
    syllables: List<KaraokeSyllable>,
    style: AssStyle?,
    isCurrent: Boolean,
    cueStartMs: Long,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    baseFontSize: Float = 18f,
    baseColor: Color? = null
) {
    // 解析样式参数
    val primaryColor = remember(style, baseColor) {
        baseColor ?: style?.let { Color(it.primaryColor) } ?: Color.White
    }
    val secondaryColor = remember(style) {
        style?.let { Color(it.secondaryColor) } ?: Color(0xFF00BFFF) // 默认蓝色作为未填充色
    }
    val outlineColor = remember(style) {
        style?.let { Color(it.outlineColor) } ?: Color.Black
    }
    val fontSize = remember(style, baseFontSize, isCurrent) {
        val size = style?.fontSize ?: baseFontSize
        if (isCurrent) (size * 1.2f) else size
    }
    val bold = style?.bold ?: false
    val italic = style?.italic ?: false
    val outlineSize = style?.outline ?: 0f

    // 计算相对时间位置
    val elapsed = (currentPositionMs - cueStartMs).coerceAtLeast(0)
    
    // 创建文本样式
    val textStyle = remember(fontSize, bold, italic, primaryColor) {
        TextStyle(
            fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            color = primaryColor
        )
    }

    // 使用 Canvas 绘制卡拉OK效果
    Box(
        modifier = modifier.wrapContentSize(),
        contentAlignment = Alignment.Center
    ) {
        if (syllables.isEmpty()) {
            // 无卡拉OK音节时，显示普通文本
            // 此情况不应发生，但作为兜底
        } else {
            // 使用 Row 布局每个音节
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                syllables.forEachIndexed { index, syllable ->
                    KaraokeSyllableView(
                        syllable = syllable,
                        textStyle = textStyle,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        outlineColor = outlineColor,
                        outlineSize = outlineSize,
                        elapsed = elapsed,
                        isCurrent = isCurrent
                    )
                }
            }
        }
    }
}

/**
 * 单个卡拉OK音节渲染
 */
@Composable
private fun KaraokeSyllableView(
    syllable: KaraokeSyllable,
    textStyle: TextStyle,
    primaryColor: Color,
    secondaryColor: Color,
    outlineColor: Color,
    outlineSize: Float,
    elapsed: Long,
    isCurrent: Boolean
) {
    val text = syllable.text
    if (text.isEmpty()) return

    // 计算填充进度
    val progress = remember(elapsed, syllable) {
        when {
            elapsed < syllable.startOffsetMs -> 0f
            elapsed >= syllable.endOffsetMs -> 1f
            else -> {
                val withinSyllable = elapsed - syllable.startOffsetMs
                when (syllable.type) {
                    KaraokeType.FILL -> {
                        // \k: 瞬间填充 (达到时间点后立即完成)
                        if (withinSyllable > 0) 1f else 0f
                    }
                    KaraokeType.SWEEP -> {
                        // \kf 或 \K: 渐变填充
                        (withinSyllable.toFloat() / syllable.durationMs).coerceIn(0f, 1f)
                    }
                    KaraokeType.OUTLINE -> {
                        // \ko: 描边渐变
                        (withinSyllable.toFloat() / syllable.durationMs).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    // 音节的颜色
    val baseAlpha = if (isCurrent) 1f else 0.35f
    
    // 使用 Canvas 绘制带填充效果的文本
    Box(
        modifier = Modifier.wrapContentSize()
    ) {
        // 底层: 未填充状态 (secondary color)
        Text(
            text = text,
            style = textStyle.copy(
                color = secondaryColor.copy(alpha = baseAlpha)
            )
        )
        
        // 上层: 已填充部分 (primary color) - 使用 clip 实现部分显示
        if (progress > 0f) {
            KaraokeFillText(
                text = text,
                textStyle = textStyle,
                fillColor = primaryColor.copy(alpha = baseAlpha),
                outlineColor = outlineColor.copy(alpha = baseAlpha),
                outlineSize = outlineSize,
                progress = progress,
                syllableType = syllable.type
            )
        }
    }
}

/**
 * 卡拉OK填充文本 - 使用 Canvas 绘制部分填充效果
 */
@Composable
private fun KaraokeFillText(
    text: String,
    textStyle: TextStyle,
    fillColor: Color,
    outlineColor: Color,
    outlineSize: Float,
    progress: Float,
    syllableType: KaraokeType,
    modifier: Modifier = Modifier
) {
    val textPaint = remember(textStyle, fillColor) {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = textStyle.fontSize.value * android.content.res.Resources.getSystem().displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                when (textStyle.fontWeight) {
                    FontWeight.Bold -> android.graphics.Typeface.BOLD
                    else -> android.graphics.Typeface.NORMAL
                } or when (textStyle.fontStyle) {
                    FontStyle.Italic -> android.graphics.Typeface.ITALIC
                    else -> android.graphics.Typeface.NORMAL
                }
            )
            color = fillColor.hashCode()
        }
    }
    
    val textWidth = remember(text, textPaint) {
        textPaint.measureText(text)
    }
    
    val fillWidth = remember(progress, textWidth) {
        (textWidth * progress)
    }

    Canvas(
        modifier = modifier.wrapContentSize()
    ) {
        // 绘制填充部分
        drawIntoCanvas { canvas ->
            val frameworkPaint = textPaint.asFrameworkPaint()
            
            // 保存画布状态
            canvas.save()
            
            // 裁剪区域 - 只显示填充进度部分
            canvas.clipRect(
                left = 0f,
                top = 0f,
                right = fillWidth,
                bottom = size.height
            )
            
            // 设置填充颜色
            frameworkPaint.color = fillColor.hashCode()
            frameworkPaint.style = AndroidPaint.Style.FILL
            
            // 绘制文本
            canvas.nativeCanvas.drawText(
                text,
                0f,
                size.height / 2 + textPaint.textSize / 3,
                textPaint
            )
            
            // 恢复画布
            canvas.restore()
        }
    }
}

/**
 * 检查字幕是否有卡拉OK特效
 */
fun hasKaraokeEffect(cue: SubtitleCue): Boolean {
    return cue.karaokeSyllables.isNotEmpty()
}
