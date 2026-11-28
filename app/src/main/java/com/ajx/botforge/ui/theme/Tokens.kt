package com.ajx.botforge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * BotForge 语义色 token
 *
 * 为什么需要这一层：
 * 原来页面直接 `import com.ajx.botforge.ui.theme.TextHigh`，那是编译期常量，
 * 切主题时不会变。只要页面里还写着 `color = TextHigh`，浅色主题下就永远是
 * 深色主题的那套浅灰字，等于白底白字。
 *
 * 因此把「背景层级 / 文字层级 / 品牌 / 状态 / 线条」全部收进一个不可变数据类，
 * 由 [BotForgeTheme] 按当前模式（亮/暗）提供，页面通过 [LocalBotColors] 读取。
 *
 * 用法：
 *   val c = LocalBotColors.current
 *   Text(text = "标题", color = c.textHigh, fontSize = FontSize.title)
 *
 * 命名与旧常量一一对应（Bg1 → bg1，TextHigh → textHigh），
 * 迁移只是把首字母改小写 + 加 c. 前缀，不改变任何设计决策。
 *
 * 硬性规则（设计规范 §1）：
 *  - 页面一律通过 [botColors] 取色，不得再直接写 `Color(0xFF...)` 或常量名；
 *    （旧 Color.kt 的编译期常量已全部迁入本文件的 Dark/Light 两套，文件已删除）
 *  - 需要新颜色时先问「能不能复用已有角色」，不能才加字段，并同时补齐亮暗两套。
 */
@Immutable
data class BotColors(
    // ---------------- 背景层级（深色 5 级 / 浅色 3 级 + 纯白卡片）
    /** 窗口最底层，与系统栏同色 */
    val bg0: Color,
    /** 页面默认背景 */
    val bg1: Color,
    /** 卡片 / 面板 */
    val bg2: Color,
    /** 输入框 / 浮起元素 */
    val bg3: Color,
    /** 高亮块 / 按下态 */
    val bg4: Color,

    // ---------------- 文字
    /** 主要文字：标题、数值、正文 */
    val textHigh: Color,
    /** 次要文字：说明、副标题 */
    val textMid: Color,
    /** 弱化文字：占位符、极次要信息 */
    val textLow: Color,
    /** 反色文字：用于品牌色块之上 */
    val textInverse: Color,

    // ---------------- 品牌
    val brand: Color,
    val brandPressed: Color,
    val brandDisabled: Color,
    /** 品牌色极低透明底，用于选中背景 */
    val brandGhost: Color,

    // ---------------- 语义状态（前景 / 极低透明底）
    val success: Color,
    val successGhost: Color,
    val warning: Color,
    val warningGhost: Color,
    val danger: Color,
    val dangerGhost: Color,
    val neutral: Color,
    val neutralGhost: Color,
    val info: Color,
    val infoGhost: Color,

    // ---------------- 线条
    /** 常规分割线，低对比 */
    val line: Color,
    /** 强调分割线 / 输入框描边 */
    val lineStrong: Color,

    // ---------------- 特殊
    val scrim: Color,

    /** 当前是否为深色方案，供个别需要判断的场景使用（如阴影强度） */
    val isDark: Boolean
)

/** 深色调色板：深石墨背景 + 克制品牌蓝，靠 5 级 Surface 拉层次。 */
internal val DarkBotColors = BotColors(
    bg0 = Color(0xFF0D1117),
    bg1 = Color(0xFF151A22),
    bg2 = Color(0xFF1C2330),
    bg3 = Color(0xFF232B39),
    bg4 = Color(0xFF2C3546),

    textHigh = Color(0xFFF0F2F5),
    textMid = Color(0xFF929BAA),
    textLow = Color(0xFF626D7C),
    textInverse = Color(0xFF0D1117),

    brand = Color(0xFF6B96F5),
    brandPressed = Color(0xFF4F7EE0),
    brandDisabled = Color(0xFF2E3849),
    brandGhost = Color(0x1A6B96F5),

    success = Color(0xFF4ECB8C),
    successGhost = Color(0x1F4ECB8C),
    warning = Color(0xFFE8A94C),
    warningGhost = Color(0x1FE8A94C),
    danger = Color(0xFFE8696F),
    dangerGhost = Color(0x1FE8696F),
    neutral = Color(0xFF5A6474),
    neutralGhost = Color(0x1F5A6474),
    info = Color(0xFF5AA9E6),
    infoGhost = Color(0x1F5AA9E6),

    line = Color(0xFF232A36),
    lineStrong = Color(0xFF323B4B),

    scrim = Color(0xB3000000),
    isDark = true
)

/**
 * 浅色调色板
 *
 * 浅色不是把深色反相：
 *  - 深色靠 5 级 Surface 拉层次，浅色只有 3 级（bg0/bg1/bg2），
 *    bg3/bg4 复用更深一档，避免整片发灰发糊；
 *  - 正文用 #1A1F26 而非纯黑，纯黑在白底上过于生硬；
 *  - 品牌蓝、状态色统一压深一档，保证在白色背景上仍满足 WCAG AA 4.5:1。
 */
internal val LightBotColors = BotColors(
    bg0 = Color(0xFFF5F6F8),
    bg1 = Color(0xFFF7F8FA),
    bg2 = Color(0xFFFFFFFF),
    bg3 = Color(0xFFEDEFF3),
    bg4 = Color(0xFFE3E7ED),

    textHigh = Color(0xFF1A1F26),
    textMid = Color(0xFF5A6472),
    textLow = Color(0xFF8A94A3),
    textInverse = Color(0xFFFFFFFF),

    brand = Color(0xFF2F6BE0),
    brandPressed = Color(0xFF1E56C4),
    brandDisabled = Color(0xFFC6D4EF),
    brandGhost = Color(0x142F6BE0),

    success = Color(0xFF12855B),
    successGhost = Color(0x1A12855B),
    warning = Color(0xFF9A6410),
    warningGhost = Color(0x1A9A6410),
    danger = Color(0xFFC2333B),
    dangerGhost = Color(0x1AC2333B),
    neutral = Color(0xFF6B7280),
    neutralGhost = Color(0x1A6B7280),
    info = Color(0xFF1B6FA8),
    infoGhost = Color(0x1A1B6FA8),

    line = Color(0xFFE4E8EE),
    lineStrong = Color(0xFFCDD4DE),

    scrim = Color(0x66000000),
    isDark = false
)

/**
 * 当前调色板。默认给深色，保证在没有 Theme 包裹的场景（Preview / 单测）不崩。
 */
val LocalBotColors = staticCompositionLocalOf { DarkBotColors }

/**
 * 便捷读取：`val c = botColors()`。
 * 用 @ReadOnlyComposable 保证它不产生额外重组作用域。
 */
val botColors: BotColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBotColors.current
