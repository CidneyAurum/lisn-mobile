package com.glass.lisn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 桌面端 glass.css 的深空 aurora 色板
val BgDeep = Color(0xFF05090B)
val BgRaise = Color(0xFF0B1113)
val CardGlass = Color(0x0EFFFFFF)
val CardGlassHover = Color(0x16FFFFFF)
val Stroke = Color(0x1AFFFFFF)
val Aurora1 = Color(0xFF39C5BB)
val Aurora2 = Color(0xFFE12885)
val Aurora3 = Color(0xFF86CECB)
val Accent = Color(0xFF39C5BB)
val AccentSoft = Color(0x2939C5BB)
val Text1 = Color(0xF0FFFFFF)
val Text2 = Color(0x99FFFFFF)
val Text3 = Color(0x61FFFFFF)
val Danger = Color(0xFFFF4D6A)
val Ok = Color(0xFF32D074)
val Warn = Color(0xFFFFB340)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Aurora3,
    tertiary = Aurora2,
    background = BgDeep,
    onBackground = Text1,
    surface = BgRaise,
    onSurface = Text1,
    surfaceVariant = CardGlass,
    onSurfaceVariant = Text2,
    outline = Stroke,
    error = Danger
)

val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Text1),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Text1),
    bodyLarge = TextStyle(fontSize = 15.sp, color = Text1),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Text1),
    bodySmall = TextStyle(fontSize = 12.sp, color = Text2),
    labelSmall = TextStyle(fontSize = 11.sp, color = Text3)
)

@Composable
fun LisnTheme(content: @Composable () -> Unit) {
    // 音乐播放器固定深色(毛玻璃深空主题)
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}
