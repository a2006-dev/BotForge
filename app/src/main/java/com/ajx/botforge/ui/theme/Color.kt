package com.ajx.botforge.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * BotForge 颜色体系
 *
 * 设计原则（对应任务书 3.3）：
 *  - 深石墨主背景，靠 5 级 Surface 拉开层次，不靠边框和阴影
 *  - 品牌蓝克制使用，只出现在主操作与选中态
 *  - 状态色柔和，不刺眼，用于语义而非装饰
 *  - 分割线低对比度，弱化到几乎看不见但仍有边界
 *
 * 命名规则：用途_层级。禁止出现 blue_dark 这类色相命名，
 * 否则换肤或调色时无处下手。
 */

// ---------------- 背景层级（由深到浅）
/** 窗口最底层，系统栏同色 */
val Bg0 = Color(0xFF0D1117)
/** 页面默认背景 */
val Bg1 = Color(0xFF151A22)
/** 卡片 / 面板 */
val Bg2 = Color(0xFF1C2330)
/** 输入框 / 浮起元素 */
val Bg3 = Color(0xFF232B39)
/** 高亮块 / 按下态 */
val Bg4 = Color(0xFF2C3546)

// ---------------- 文字
/** 主要文字：标题、数值、正文 */
val TextHigh = Color(0xFFF0F2F5)
/** 次要文字：说明、副标题 */
val TextMid = Color(0xFF929BAA)
/** 弱化文字：占位符、极次要信息 */
val TextLow = Color(0xFF626D7C)
/** 反色文字：用于品牌色块之上 */
val TextInverse = Color(0xFF0D1117)

// ---------------- 品牌
/** 品牌强调蓝 */
val Brand = Color(0xFF6B96F5)
/** 按下态 */
val BrandPressed = Color(0xFF4F7EE0)
/** 禁用态 */
val BrandDisabled = Color(0xFF2E3849)
/** 品牌色的极低透明底，用于选中背景 */
val BrandGhost = Color(0x1A6B96F5)

// ---------------- 语义状态
/** 成功 / 运行中 / 已连接 */
val Success = Color(0xFF4ECB8C)
val SuccessGhost = Color(0x1F4ECB8C)

/** 警告 / 待配置 / 部分运行 */
val Warning = Color(0xFFE8A94C)
val WarningGhost = Color(0x1FE8A94C)

/** 错误 / 危险 / 断开 */
val Danger = Color(0xFFE8696F)
val DangerGhost = Color(0x1FE8696F)

/** 中性 / 空闲 / 未启用 */
val Neutral = Color(0xFF5A6474)
val NeutralGhost = Color(0x1F5A6474)

/** 信息提示 */
val Info = Color(0xFF5AA9E6)
val InfoGhost = Color(0x1F5AA9E6)

// ---------------- 线条
/** 常规分割线，低对比 */
val Line = Color(0xFF232A36)
/** 强调分割线 / 输入框描边 */
val LineStrong = Color(0xFF323B4B)

// ---------------- 特殊
val Transparent = Color(0x00000000)
/** 遮罩层 */
val Scrim = Color(0xB3000000)
