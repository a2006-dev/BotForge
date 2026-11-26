package com.ajx.botforge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ajx.botforge.data.Config

/**
 * Material3 配色：由 [BotColors] 派生
 *
 * 页面几乎不直接用 M3 的 colorScheme 角色，只给 Slider / TextField 等
 * 系统组件兜底。即便如此也要跟着主题走，否则浅色主题下组件仍是深色。
 */
private fun m3From(c: BotColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.brand,
        onPrimary = Color.White,
        primaryContainer = c.brandGhost,
        onPrimaryContainer = c.brand,
        secondary = c.textMid,
        onSecondary = c.bg1,
        secondaryContainer = c.bg3,
        onSecondaryContainer = c.textHigh,
        tertiary = c.info,
        onTertiary = Color.White,
        tertiaryContainer = c.infoGhost,
        onTertiaryContainer = c.info,
        background = c.bg1,
        onBackground = c.textHigh,
        surface = c.bg1,
        onSurface = c.textHigh,
        surfaceVariant = c.bg2,
        onSurfaceVariant = c.textMid,
        surfaceContainer = c.bg2,
        surfaceContainerHigh = c.bg3,
        surfaceContainerHighest = c.bg4,
        surfaceContainerLow = c.bg1,
        surfaceContainerLowest = c.bg0,
        error = c.danger,
        onError = Color.White,
        errorContainer = c.dangerGhost,
        onErrorContainer = c.danger,
        outline = c.lineStrong,
        outlineVariant = c.line,
        scrim = c.scrim,
        inverseSurface = c.textHigh,
        inverseOnSurface = c.bg1,
        inversePrimary = c.brandPressed
    )
} else {
    lightColorScheme(
        primary = c.brand,
        onPrimary = Color.White,
        primaryContainer = c.brandGhost,
        onPrimaryContainer = c.brandPressed,
        secondary = c.textMid,
        onSecondary = Color.White,
        secondaryContainer = c.bg3,
        onSecondaryContainer = c.textHigh,
        tertiary = c.info,
        onTertiary = Color.White,
        tertiaryContainer = c.infoGhost,
        onTertiaryContainer = c.info,
        background = c.bg1,
        onBackground = c.textHigh,
        surface = c.bg2,
        onSurface = c.textHigh,
        surfaceVariant = c.bg3,
        onSurfaceVariant = c.textMid,
        surfaceContainer = c.bg2,
        surfaceContainerHigh = c.bg3,
        surfaceContainerHighest = c.bg4,
        surfaceContainerLow = c.bg1,
        surfaceContainerLowest = Color.White,
        error = c.danger,
        onError = Color.White,
        errorContainer = c.dangerGhost,
        onErrorContainer = c.danger,
        outline = c.lineStrong,
        outlineVariant = c.line,
        scrim = c.scrim,
        inverseSurface = c.textHigh,
        inverseOnSurface = Color.White,
        inversePrimary = c.brandPressed
    )
}

/**
 * 主题模式（用户可选）
 *
 * 为什么不用一个 Boolean：用户需要「跟随系统」这个第三选项，
 * Boolean 表达不了三态，强行用 null 表示跟随会让调用处到处判空。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/**
 * 应用主题入口
 *
 * 三件事，缺一不可：
 *  1. 提供 [LocalBotColors]（页面所有颜色都从这里取，这是切主题能生效的前提）
 *  2. 提供 M3 colorScheme（给系统组件兜底）
 *  3. 同步系统栏图标明暗（深底浅图标 / 浅底深图标）
 *
 * @param themeMode 用户在设置里选的主题模式；SYSTEM 时跟随系统
 */
@Composable
fun BotForgeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (useDark) DarkBotColors else LightBotColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 边到边：内容延伸到系统栏下方，由各页面自己处理 insets
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            // 系统栏图标颜色必须跟着主题走：
            // 深色背景配浅色图标，浅色背景配深色图标。写死会在浅色主题下
            // 出现「白底白字」的状态栏，时间电量完全看不见。
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    CompositionLocalProvider(LocalBotColors provides colors) {
        MaterialTheme(
            colorScheme = m3From(colors),
            typography = BotForgeTypography,
            shapes = BotForgeShapes
        ) {
            // 全站统一处理系统栏 insets。
            //
            // setDecorFitsSystemWindows(window, false) 让内容可以延伸到
            // 状态栏/导航栏下方，但「延伸」必须由内容自己避让，否则大标题会被
            // 状态栏压住、列表底部会被手势条盖住。
            //
            // 这里在最外层统一吃掉 statusBars + navigationBars，
            // 各页面就不必各自 rememberWindowInsets 一遍——
            // 页面内部只关心自己的业务布局。
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.bg1
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 供宿主 Activity 读取当前主题模式。
 *
 * 失败兜底为 SYSTEM：Config 未初始化（如 Compose Preview）时不能崩。
 */
fun currentThemeMode(): ThemeMode = runCatching {
    ThemeMode.fromKey(Config.themeMode)
}.getOrDefault(ThemeMode.SYSTEM)