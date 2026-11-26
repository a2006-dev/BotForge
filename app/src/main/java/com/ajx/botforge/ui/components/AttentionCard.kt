package com.ajx.botforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ui.theme.Danger
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.LineHeight
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextMid
import com.ajx.botforge.ui.theme.Warning
import com.ajx.botforge.ui.theme.WarningGhost

/**
 * 异常聚合卡（提示词 §6.1）
 *
 * 核心规则：**没有问题时整块不渲染**。
 * 常见的错误做法是「没问题时显示一个绿色对勾卡片」，
 * 那会在首屏永久占掉 80dp，却只在 99% 的时间里说「一切正常」——
 * 而首屏的空间应该留给状态与操作。
 *
 * 因此调用方必须写 `if (issues.isEmpty()) return` 之类的判断，
 * 本组件自身不做空态。
 *
 * @param issues 问题列表；空列表时调用方不应调用本组件
 * @param onIssueClick 点某条问题时的回调，null 表示该项不可点击（纯信息）
 */
@Composable
fun AttentionCard(
    issues: List<AttentionIssue>,
    modifier: Modifier = Modifier,
    onIssueClick: ((AttentionIssue) -> Unit)? = null
) {
    if (issues.isEmpty()) return

    val severe = issues.any { it.severe }
    val accent = if (severe) Danger else Warning

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                // 严重问题用更实的底色，一般问题用 ghost
                if (severe) Danger.copy(alpha = 0.14f) else WarningGhost,
                RoundedCornerShape(Radius.lg)
            )
            .padding(Spacing.lg)
    ) {

        // ---------- 标题行 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(Spacing.sm))
            Text(
                text = if (issues.size == 1) "1 个问题需要处理" else "${issues.size} 个问题需要处理",
                color = accent,
                fontSize = FontSize.body,
                lineHeight = LineHeight.normal,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // ---------- 问题条目 ----------
        issues.forEachIndexed { index, issue ->
            if (index > 0) Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onIssueClick != null && issue.actionable) {
                            Modifier.clickable { onIssueClick(issue) }
                        } else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 项目符号：用小圆点而不是数字，避免和「问题数」重复计数
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(4.dp)
                        .background(accent.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    text = issue.text,
                    color = TextHigh,
                    fontSize = FontSize.small,
                    lineHeight = LineHeight.normal,
                    modifier = Modifier.weight(1f)
                )
                if (issue.actionable) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextMid,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 一条待处理问题
 *
 * @param text       问题描述，必须说「后果」而不只是「状态」
 *                   反例：「通知权限未授予」
 *                   正例：「通知权限未授予，后台保活受限」
 * @param actionable 是否可点进详情；false 时右侧不显示箭头
 * @param severe     是否严重（用 danger 色而非 warning 色）
 * @param route      跳转目标，由调用方解释
 */
data class AttentionIssue(
    val text: String,
    val actionable: Boolean = true,
    val severe: Boolean = false,
    val route: String? = null
)

// ---------------------------------------------------------------- 预览

@Preview(showBackground = true, backgroundColor = 0xFF151A22)
@Composable
private fun AttentionCardPreview() {
    AttentionCard(
        issues = listOf(
            AttentionIssue("通知权限未授予，后台保活受限", route = "permission"),
            AttentionIssue("24 小时内断线 5 次，建议检查网络", actionable = false)
        ),
        modifier = Modifier.padding(16.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF151A22)
@Composable
private fun AttentionCardSingleSeverePreview() {
    AttentionCard(
        issues = listOf(AttentionIssue("网关鉴权失败，机器人无法响应", severe = true)),
        modifier = Modifier.padding(16.dp)
    )
}