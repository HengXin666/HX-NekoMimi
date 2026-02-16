package com.hx.nekomimi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 🐱 NekoMimi 猫耳粉色主题
// 亮色
private val PinkLight = Color(0xFFE91E63)           // 主粉色
private val PinkLightContainer = Color(0xFFFCE4EC)  // 粉色容器
private val PinkLightSecondary = Color(0xFFFF80AB)   // 浅粉
private val PinkLightTertiary = Color(0xFFAD1457)    // 深玫瑰
private val PinkLightSurface = Color(0xFFFFF0F3)     // 极浅粉底色
private val PinkLightBackground = Color(0xFFFFF5F7)  // 背景色

// 暗色
private val PinkDark = Color(0xFFFF80AB)             // 亮粉
private val PinkDarkContainer = Color(0xFF880E4F)    // 暗粉容器
private val PinkDarkSecondary = Color(0xFFF48FB1)    // 柔粉
private val PinkDarkTertiary = Color(0xFFFF4081)     // 活力粉
private val PinkDarkSurface = Color(0xFF1A0E14)      // 暗底
private val PinkDarkBackground = Color(0xFF1A0E14)   // 暗背景

private val LightColorScheme = lightColorScheme(
    primary = PinkLight,
    onPrimary = Color.White,
    primaryContainer = PinkLightContainer,
    onPrimaryContainer = Color(0xFF880E4F),
    secondary = PinkLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCE4EC),
    onSecondaryContainer = Color(0xFF880E4F),
    tertiary = PinkLightTertiary,
    onTertiary = Color.White,
    surface = PinkLightSurface,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFFCE4EC),
    onSurfaceVariant = Color(0xFF49454F),
    background = PinkLightBackground,
    onBackground = Color(0xFF1C1B1F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFFE91E63).copy(alpha = 0.5f)
)

private val DarkColorScheme = darkColorScheme(
    primary = PinkDark,
    onPrimary = Color(0xFF1A0E14),
    primaryContainer = PinkDarkContainer,
    onPrimaryContainer = Color(0xFFFCE4EC),
    secondary = PinkDarkSecondary,
    onSecondary = Color(0xFF1A0E14),
    secondaryContainer = Color(0xFF4A0E2F),
    onSecondaryContainer = Color(0xFFFCE4EC),
    tertiary = PinkDarkTertiary,
    onTertiary = Color(0xFF1A0E14),
    surface = PinkDarkSurface,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2D1B25),
    onSurfaceVariant = Color(0xFFCAC4D0),
    background = PinkDarkBackground,
    onBackground = Color(0xFFE6E1E5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFFFF80AB).copy(alpha = 0.5f)
)

@Composable
fun NekoMimiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 始终使用固定粉色主题，不使用动态取色
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
