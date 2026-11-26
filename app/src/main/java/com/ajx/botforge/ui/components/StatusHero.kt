package com.ajx.botforge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import android.provider.Settings
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.LineHeight
import com.ajx.botforge.ui.theme.NumericStyle
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid

/**
 * 首页状态主视觉（提示词 §6.1）
 *
 * 设计意图：首页第一屏必须在一秒内回答「现在正常吗」。
 * 因此这里不套卡片、不加边框，用 40sp 的状态文字直接压在背景上，
 * 让状态成为整页视觉重心；数值类信息降为第二层。
 *
 * 三块信息：
 *  1. 状态文字（大，取语义色）  + 呼吸点
 *  2. 一句话说明（次级色，解释上一条意味着什么）
 *  3. 两列指标：运行时长 / 最近消息 / 今日断线（由调用方决定传几列）
 *
 * @param status      语义状态，决定文字、色相与说明
 * @param subtitle    覆盖默认说明，传 null 用 [BotStatus.hint]
 * @param metrics     指标列表，最多渲染 3 个（多了会挤）
 * @param reduceMotion 系统是否要求减少动画；true 时不做呼吸动画
 */
@Composable
fun StatusHero(
    status: BotStatus,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    metrics: List<HeroMetric> = emptyList(),
    reduceMotion: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {

        // ---------- 状态 + 呼吸点 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 呼吸点：只对「活着」的状态做动画。
            // 离线和已暂停是静止状态，让它们呼吸反而会误导用户以为在运行。
            val breathing = status == BotStatus.ONLINE ||
                status == BotStatus.CONNECTING ||
                status == BotStatus.RECONNECTING
            BreathingDot(
                color = status.tone.fg,
                animate = breathing && !reduceMotion
            )
            Spacer(Modifier.size(Spacing.md))
            Text(
                text = status.label,
                color = status.tone.fg,
                fontSize = FontSize.metric,
                lineHeight = LineHeight.huge,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // ---------- 说明 ----------
        Text(
            text = subtitle ?: status.hint,
            color = TextMid,
            fontSize = FontSize.body,
            lineHeight = LineHeight.normal
        )

        // ---------- 指标 ----------
        if (metrics.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)
            ) {
                metrics.take(3).forEach { m ->
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = m.label,
                            color = TextLow,
                            fontSize = FontSize.caption,
                            lineHeight = LineHeight.tight
                        )
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(
                            text = m.value,
                            color = TextHigh,
                            fontSize = FontSize.bodyLarge,
                            lineHeight = LineHeight.normal,
                            fontWeight = FontWeight.SemiBold,
                            // 数字等宽：秒数跳动时不会左右抖。
                            // 注意 Material3 的 Text() 没有 fontFeatureSettings 参数，
                            // 该属性只能挂在 TextStyle 上，因此这里必须走 style。
                            style = LocalTextStyle.current.copy(
                                fontFeatureSettings = NumericStyle.tnum
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** StatusHero 的一列指标 */
data class HeroMetric(val label: String, val value: String)

/**
 * 是否应减少动效（无障碍 / 开发者选项）
 *
 * 判定依据：`Settings.Global.ANIMATOR_DURATION_SCALE == 0` 表示用户关掉了动画。
 * 这是系统级的意图表达，所有自绘动画都应尊重它。
 *
 * 放在本文件而不是 theme 包，是因为只有需要做动画的组件才会用到，
 * 避免 theme 依赖 android.provider 这类平台 API。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * 呼吸圆点
 *
 * 用 alpha 动画而不是 size 动画：size 变化会触发重新布局，
 * 每帧布局是列表滚动时的可见卡顿来源。
 *
 * 实现要点：`rememberInfiniteTransition` + 正反向循环，
 * 而不是 animateFloatAsState（后者需要 targetValue 变化才会动，
 * 恒定的 targetValue 会让动画完全静止——这是上一版的实际 bug）。
 */
@Composable
private fun BreathingDot(
    color: Color,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "breathing")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathing-alpha"
        ).value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

// ---------------------------------------------------------------- 预览

@Preview(name = "在线", showBackground = true, backgroundColor = 0xFF151A22)
@Composable
private fun StatusHeroOnlinePreview() {
    StatusHero(
        status = BotStatus.ONLINE,
        metrics = listOf(
            HeroMetric("运行时长", "3 小时 12 分"),
            HeroMetric("最近消息", "2 分钟前"),
            HeroMetric("今日断线", "0 次")
        )
    )
}

@Preview(name = "离线", showBackground = true, backgroundColor = 0xFF151A22)
@Composable
private fun StatusHeroOfflinePreview() {
    StatusHero(
        status = BotStatus.OFFLINE,
        metrics = listOf(
            HeroMetric("上次运行", "2 天前"),
            HeroMetric("今日断线", "3 次")
        )
    )
}

@Preview(name = "已暂停", showBackground = true, backgroundColor = 0xFF151A22)
@Composable
private fun StatusHeroPausedPreview() {
    StatusHero(status = BotStatus.PAUSED)
}

@Preview(name = "异常", showBackground = true, backgroundColor = 0xFF151A22)
@Composable
private fun StatusHeroErrorPreview() {
    StatusHero(
        status = BotStatus.ERROR,
        metrics = listOf(HeroMetric("连续失败", "5 次"))
    )
}