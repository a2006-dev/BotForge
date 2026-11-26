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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.CardShape
import com.ajx.botforge.ui.theme.Danger
import com.ajx.botforge.ui.theme.DangerGhost
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Info
import com.ajx.botforge.ui.theme.InfoGhost
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.Neutral
import com.ajx.botforge.ui.theme.NeutralGhost
import com.ajx.botforge.ui.theme.PillShape
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.Success
import com.ajx.botforge.ui.theme.SuccessGhost
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.Warning
import com.ajx.botforge.ui.theme.WarningGhost

/**
 * 状态语义
 *
 * 任务书 6.1 要求 StatusIndicator / StatusBadge。
 * 这里把「颜色 + 文案」绑成一个枚举，避免各页面各写一套配色，
 * 也保证同一状态在不同页面颜色一致。
 */
enum class StatusTone(
    val fg: Color,
    val bg: Color
) {
    /** 运行中 / 已连接 / 已启用 */
    Success(Success, SuccessGhost),

    /** 警告 / 待配置 / 部分运行 */
    Warning(Warning, WarningGhost),

    /** 错误 / 断开 / 危险 */
    Danger(Danger, DangerGhost),

    /** 空闲 / 未启用 / 未知 */
    Neutral(Neutral, NeutralGhost),

    /** 信息 / 处理中 */
    Info(Info, InfoGhost)
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
 * 例：ONLINE / OFFLINE / 已启用 / 已禁用
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        StatusDot(tone)
        Text(
            text = text,
            color = TextHigh,
            fontSize = FontSize.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 通用卡片容器
 *
 * 任务书 6.2 明确警告「不要把一切都做成 Card」。
 * 所以这里只提供最小能力：深色底 + 圆角 + 可选描边。
 * 无阴影（深色背景上阴影不可见且增加渲染成本）。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
    background: Color = Bg2,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, CardShape)
            .then(
                if (bordered) Modifier.border(Sizes.hairline, Line, CardShape)
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
    Text(
        text = text,
        modifier = modifier.padding(
            start = Spacing.xs,
            top = Spacing.xxl,
            bottom = Spacing.sm
        ),
        color = TextLow,
        fontSize = FontSize.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    )
}