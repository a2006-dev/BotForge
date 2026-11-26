package com.ajx.botforge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material3 Shapes
 *
 * 全部改为自定义圆角，覆盖 M3 默认的少量大圆角。
 * 整体偏「中等圆角」——够现代但不软塌，符合开发者工具的克制感。
 */
val BotForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl)
)

/** 卡片：16dp */
val CardShape = RoundedCornerShape(Radius.lg)

/** 按钮：12dp */
val ButtonShape = RoundedCornerShape(Radius.md)

/** 输入框：12dp */
val InputShape = RoundedCornerShape(Radius.md)

/** 胶囊：全圆角 */
val PillShape = RoundedCornerShape(Radius.full)

/** 底部弹层：仅顶部圆角 */
val SheetShape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)

/** 对话框：16dp */
val DialogShape = RoundedCornerShape(Radius.lg)