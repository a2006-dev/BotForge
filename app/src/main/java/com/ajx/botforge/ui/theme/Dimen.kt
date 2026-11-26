package com.ajx.botforge.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 间距体系（4dp 基准栅格）
 *
 * 统一用这套，禁止在页面里写 13.dp / 7.dp 这类魔法数字。
 * 需要新值时先问：是不是能在已有档位里选？不能再加。
 */
object Spacing {
    /** 2dp —— 极紧凑，图标与文字之间 */
    val xxs = 2.dp
    /** 4dp —— 紧凑 */
    val xs = 4.dp
    /** 8dp —— 小 */
    val sm = 8.dp
    /** 12dp —— 中，卡片内边距 */
    val md = 12.dp
    /** 16dp —— 大，页面边距 */
    val lg = 16.dp
    /** 20dp —— 更大，区块之间 */
    val xl = 20.dp
    /** 24dp —— 大区块分隔 */
    val xxl = 24.dp
    /** 32dp —— 页面底部留白 */
    val huge = 32.dp
}

/**
 * 圆角体系
 */
object Radius {
    /** 6dp —— 小标签订 */
    val xs = 6.dp
    /** 8dp —— 输入框、小按钮 */
    val sm = 8.dp
    /** 12dp —— 按钮、卡片内元素 */
    val md = 12.dp
    /** 16dp —— 卡片、面板 */
    val lg = 16.dp
    /** 20dp —— 大面板、底部弹层顶部 */
    val xl = 20.dp
    /** 全圆角，用于胶囊 */
    val full = 999.dp
}

/**
 * 字体体系
 *
 * 不使用默认字体缩放，明确指定每个层级的字号与行高，
 * 保证「标题够大、正文够读、说明够弱」的层级差。
 */
object FontSize {
    /** 10sp —— 极小标注（如 AppID 缩略） */
    val micro = 10.sp

    /** 11sp —— 状态标签、辅助信息 */
    val caption = 11.sp

    /** 12sp —— 次要说明 */
    val small = 12.sp

    /** 14sp —— 正文 */
    val body = 14.sp

    /** 16sp —— 强调正文、列表标题 */
    val bodyLarge = 16.sp

    /** 18sp —— 卡片主标题 */
    val title = 18.sp

    /** 22sp —— 页面标题 */
    val heading = 22.sp

    /** 28sp —— 首页品牌大字 */
    val display = 28.sp

    /** 40sp —— 数值强调（首页指标） */
    val metric = 40.sp
}

/**
 * 数字样式（设计规范 §2 / 提示词 §4.2）
 *
 * 所有会跳动或对齐的数值（运行时长、计数、耗时、心跳间隔）
 * 必须使用等宽数字，否则秒数跳动时会左右抖动，视觉上毛毛糙糙。
 * 用法：Text(..., style = LocalTextStyle.current.copy(fontFeatureSettings = NumericStyle.tnum))
 * 注意 Material3 的 Text() 没有 fontFeatureSettings 参数，只能通过 style 传入。
 *  或直接用 Modifier + 下文的 TextStyle 扩展。
 */
object NumericStyle {
    /** 等宽数字，用于所有动态更新的数值 */
    const val tnum = "tnum"
}

object LineHeight {
    val tight = 18.sp
    val normal = 20.sp
    val relaxed = 22.sp
    val loose = 26.sp
    val huge = 32.sp
}

/**
 * 组件尺寸
 */
object Sizes {
    /** 主按钮高度 */
    val buttonHeight = 50.dp
    /** 次按钮 / 小按钮高度 */
    val buttonSmall = 38.dp
    /** 列表行最小高度 */
    val rowMin = 56.dp
    /** 输入框最小高度 */
    val inputMin = 52.dp
    /** 多行输入框最小高度 */
    val inputMultiline = 160.dp
    /** 状态圆点直径 */
    val dot = 9.dp
    /** 分割线粗细 */
    val hairline = 1.dp
    /** 图标 */
    val icon = 20.dp
    val iconLarge = 24.dp
    /** 顶栏高度 */
    val topBar = 56.dp
    /** 触摸目标最小值（无障碍） */
    val touchTarget = 48.dp
}