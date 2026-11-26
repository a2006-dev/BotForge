package com.ajx.botforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajx.botforge.ui.theme.CardShape
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.PillShape
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors

/**
 * 状态语义
 *
 * 任务书 6.1 要求 StatusIndicator / StatusBadge。
 * 把「语义」和「颜色」拆开：枚举只回答「我在说哪种状态」，
 * 具体色值由当前调色板决定。
 *
 * 之前这里是 `Success(Success, SuccessGhost)`，直接吃 Color.kt 的编译期常量，
 * 于是切到浅色主题后徽标仍是深色方案的高亮绿，白底上几乎看不见。
 * 现在改成按 LocalBotColors 取值，亮暗两套各自成立。
 */
enum class StatusTone {
    /** 运行中 / 已连接 / 已启用 */
    Success,

    /** 警告 / 待配置 / 部分运行 */
    Warning,

    /** 错误 / 断开 / 危险 */
    Danger,

    /** 空闲 / 未启用 / 未知 */
    Neutral,

    /** 信息 / 处理中 */
    Info;

    /** 主色：文字 / 圆点 / 描边 */
    val fg: Color
        @Composable
        @ReadOnlyComposable
        get() {
            val c = botColors
            return when (this) {
                Success -> c.success
                Warning -> c.warning
                Danger -> c.danger
                Neutral -> c.neutral
                Info -> c.info
            }
        }

    /** 极低透明底：徽标背景 / 选中态 */
    val bg: Color
        @Composable
        @ReadOnlyComposable
        get() {
            val c = botColors
            return when (this) {
                Success -> c.successGhost
                Warning -> c.warningGhost
                Danger -> c.dangerGhost
                Neutral -> c.neutralGhost
                Info -> c.infoGhost
            }
        }
}

/**
 * 状态圆点
 *
 * 只表达颜色语义，不承担文字。配合 [StatusBadge] 或相邻文本使用。
 */
@Composable
fun StatusDot(
    tone: StatusTone,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = Sizes.dot
) {
    Box(
        modifier = modifier
            .size(size)
            .background(tone.fg, CircleShape)
    )
}

/**
 * 状态胶囊标签
 *
 * 例：在线 / 已启用 / 已禁用
 * 低透明底 + 同色文字，视觉轻，不抢主内容。
 */
@Composable
fun StatusBadge(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(tone.bg, PillShape)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs + 1.dp)
    ) {
        Text(
            text = text,
            color = tone.fg,
            fontSize = FontSize.caption,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 圆点 + 文字的一体化状态指示
 *
 * 用于「服务运行中」这类需要明确语义的地方。
 */
@Composable
fun StatusIndicator(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    val c = botColors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        StatusDot(tone)
        Text(
            text = text,
            color = c.textHigh,
            fontSize = FontSize.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 通用卡片容器
 *
 * 任务书 6.2 明确警告「不要把一切都做成 Card」。
 * 所以这里只提供最小能力：表面色 + 圆角 + 可选描边，不做阴影。
 * 深色下阴影不可见，浅色下也用描边区分层次，两套观感才一致。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
    background: Color? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val c = botColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background ?: c.bg2, CardShape)
            .then(
                if (bordered) Modifier.border(Sizes.hairline, c.line, CardShape)
                else Modifier
            )
            .padding(Spacing.lg),
        content = content
    )
}

/**
 * 分区标题
 *
 * 用于 Settings、列表页的区块分隔。
 * 字小、色弱、字间距宽，起「分组」作用而不抢内容。
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    val c = botColors
    Text(
        text = text,
        modifier = modifier.padding(
            start = Spacing.xs,
            top = Spacing.xxl,
            bottom = Spacing.sm
        ),
        color = c.textLow,
        fontSize = FontSize.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    )
}