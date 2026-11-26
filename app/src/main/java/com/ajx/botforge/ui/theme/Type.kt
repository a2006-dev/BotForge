package com.ajx.botforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体层级
 *
 * 只使用系统默认字体（不引入自定义字体文件，避免 APK 体积和授权问题）。
 * 靠字重、字号、字间距三层拉开层级，而不是靠换字体。
 */
val BotForgeTypography = Typography(
    // 首页品牌大字
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = FontSize.display,
        lineHeight = LineHeight.huge,
        letterSpacing = (-0.5).sp
    ),
    // 页面标题
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = FontSize.heading,
        lineHeight = LineHeight.loose,
        letterSpacing = (-0.2).sp
    ),
    // 卡片主标题
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = FontSize.title,
        lineHeight = LineHeight.relaxed
    ),
    // 列表项标题
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = FontSize.bodyLarge,
        lineHeight = LineHeight.normal
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = FontSize.body,
        lineHeight = LineHeight.normal
    ),
    // 正文
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = FontSize.bodyLarge,
        lineHeight = LineHeight.relaxed
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = FontSize.body,
        lineHeight = LineHeight.normal
    ),
    // 说明文字
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = FontSize.small,
        lineHeight = LineHeight.normal
    ),
    // 标签
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = FontSize.body,
        lineHeight = LineHeight.normal
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = FontSize.caption,
        lineHeight = LineHeight.tight
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = FontSize.micro,
        lineHeight = LineHeight.tight,
        letterSpacing = 0.4.sp
    )
)