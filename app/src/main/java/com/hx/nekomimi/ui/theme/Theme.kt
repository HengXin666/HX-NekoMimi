package com.hx.nekomimi.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 🐱 NekoMimi 猫耳主题色
private val NekoLightPrimary = Color(0xFFE91E8C)      // 猫耳粉
private val NekoLightSecondary = Color(0xFF9C27B0)     // 紫罗兰
private val NekoLightTertiary = Color(0xFFFF6F00)      // 橙色点缀

private val NekoDarkPrimary = Color(0xFFFF80CB)        // 亮粉
private val NekoDarkSecondary = Color(0xFFCE93D8)      // 淡紫
private val NekoDarkTertiary = Color(0xFFFFAB40)       // 暖橙

private val LightColorScheme = lightColorScheme(
    primary = NekoLightPrimary,
    secondary = NekoLightSecondary,
    tertiary = NekoLightTertiary,
    surface = Color(0xFFFFF8FA),
    background = Color(0xFFFFF8FA),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onSurface = Color(0xFF1A1A2E),
    onBackground = Color(0xFF1A1A2E)
)

private val DarkColorScheme = darkColorScheme(
    primary = NekoDarkPrimary,
    secondary = NekoDarkSecondary,
    tertiary = NekoDarkTertiary,
    surface = Color(0xFF1A1A2E),
    background = Color(0xFF1A1A2E),
    onPrimary = Color(0xFF1A1A2E),
    onSecondary = Color(0xFF1A1A2E),
    onSurface = Color(0xFFEEEEEE),
    onBackground = Color(0xFFEEEEEE)
)

@Composable
fun NekoMimiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12+ 支持动态取色 (Material You)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
