package com.ajx.botforge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * BotForge 深色配色方案
 *
 * 当前只有深色一套。任务书 3.3 要求「为未来浅色主题预留完整设计系统」，
 * 预留方式是把所有颜色收进本文件 + Color.kt，未来加 lightColorScheme() 时
 * 只需在此处并列一份，页面代码不需要改（页面只引用语义角色，不引用具体色值）。
 */
private val BotForgeDarkColors = darkColorScheme(
    // 品牌
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandGhost,
    onPrimaryContainer = Brand,

    // 次要（用于非主操作的强调）
    secondary = TextMid,
    onSecondary = TextInverse,
    secondaryContainer = Bg3,
    onSecondaryContainer = TextHigh,

    // 第三级
    tertiary = Info,
    onTertiary = Color.White,
    tertiaryContainer = InfoGhost,
    onTertiaryContainer = Info,

    // 背景与表面
    background = Bg1,
    onBackground = TextHigh,
    surface = Bg1,
    onSurface = TextHigh,
    surfaceVariant = Bg2,
    onSurfaceVariant = TextMid,
    surfaceContainer = Bg2,
    surfaceContainerHigh = Bg3,
    surfaceContainerHighest = Bg4,
    surfaceContainerLow = Bg1,
    surfaceContainerLowest = Bg0,

    // 错误
    error = Danger,
    onError = Color.White,
    errorContainer = DangerGhost,
    onErrorContainer = Danger,

    // 线条
    outline = LineStrong,
    outlineVariant = Line,

    // 遮罩
    scrim = Scrim,

    // 反色表面（用于 Snackbar 等）
    inverseSurface = TextHigh,
    inverseOnSurface = Bg1,
    inversePrimary = BrandPressed
)

/**
 * 应用主题入口
 *
 * @param darkTheme 当前阶段固定深色，保留参数为未来浅色主题
 */
@Composable
fun BotForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = BotForgeDarkColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 边到边：内容延伸到系统栏下方，由各页面自己处理 insets
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            // 深色背景 → 系统栏图标用浅色
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BotForgeTypography,
        shapes = BotForgeShapes,
        content = content
    )
}