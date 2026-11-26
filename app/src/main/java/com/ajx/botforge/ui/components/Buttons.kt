package com.ajx.botforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ui.theme.Bg3
import com.ajx.botforge.ui.theme.Bg4
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.BrandDisabled
import com.ajx.botforge.ui.theme.BrandPressed
import com.ajx.botforge.ui.theme.ButtonShape
import com.ajx.botforge.ui.theme.Danger
import com.ajx.botforge.ui.theme.DangerGhost
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.LineStrong
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow

/**
 * 主按钮：品牌蓝实心
 *
 * 全项目只允许「每个页面最多一个」主按钮，
 * 任务书 3.2 明确禁止「大面积使用品牌蓝」。
 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val bg = when {
        !enabled -> BrandDisabled
        pressed -> BrandPressed
        else -> Brand
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.buttonHeight)
            .background(bg, ButtonShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick
            )
            .padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Sizes.icon),
                color = TextHigh,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.icon),
                        tint = if (enabled) TextHigh else TextLow
                    )
                }
                Text(
                    text = text,
                    color = if (enabled) TextHigh else TextLow,
                    fontSize = FontSize.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 次按钮：深灰底 + 描边
 *
 * 用于「取消」「返回」「群管理」这类并列但不主导的操作。
 */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .height(Sizes.buttonHeight)
            .background(if (pressed) Bg4 else Bg3, ButtonShape)
            .botBorder(if (pressed) LineStrong else Line)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.icon),
                    tint = TextHigh
                )
            }
            Text(
                text = text,
                color = if (enabled) TextHigh else TextLow,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 危险操作按钮：红字 + 极淡红底
 *
 * 用于「删除」「停止服务」这类不可逆操作。
 * 不用纯红实心——太扎眼，也不符合「克制」原则。
 */
@Composable
fun DangerButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .height(Sizes.buttonHeight)
            .background(if (pressed) Danger.copy(alpha = 0.24f) else DangerGhost, ButtonShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Danger,
            fontSize = FontSize.body,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 小尺寸按钮：用于列表行内的并列操作
 *
 * 高度 38dp，自适应宽度，不撑满。
 */
@Composable
fun SmallButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val bg = when {
        primary && !enabled -> BrandDisabled
        primary && pressed -> BrandPressed
        primary -> Brand
        pressed -> Bg4
        else -> Bg3
    }

    Box(
        modifier = modifier
            .height(Sizes.buttonSmall)
            .background(bg, ButtonShape)
            .then(if (primary) Modifier else Modifier.botBorder(if (pressed) LineStrong else Line))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (primary) TextHigh else if (enabled) TextHigh else TextLow,
            fontSize = FontSize.small,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 纯文字按钮：视觉最轻
 *
 * 用于「删除」这类需要存在感但不应抢焦点的操作。
 */
@Composable
fun TextButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Brand,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = Sizes.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) color else TextLow,
            fontSize = FontSize.body,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 图标按钮
 */
@Composable
fun IconTextButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = TextHigh,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(Sizes.touchTarget)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(Sizes.iconLarge),
            tint = if (enabled) tint else TextLow
        )
    }
}

/** 描边辅助，避免每处重复写 border(hairline, color, shape) */
private fun Modifier.botBorder(color: Color): Modifier =
    this.border(width = Sizes.hairline, color = color, shape = ButtonShape)