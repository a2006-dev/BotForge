package com.ajx.botforge.ui.components

import com.ajx.botforge.ui.text.Txt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors

/**
 * 自定义对话框外壳。使用 Dialog 只借其窗口层，内部完全自绘，
 * 避免 Material AlertDialog 的默认排版与配色干扰。
 */
@Composable
fun BotDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = botColors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(c.bg2, RoundedCornerShape(Radius.xl))
                .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.xl))
                .padding(Spacing.lg)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    color = c.textHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.md))
            }
            content()
        }
    }
}

/** 确认对话框：可选危险操作变体（删除类用 danger = true）。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = Txt.OK,
    dismissText: String = Txt.CANCEL,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = botColors
    BotDialog(onDismiss = onDismiss, title = title) {
        Text(
            text = message,
            color = c.textMid,
            fontSize = FontSize.body
        )
        Spacer(Modifier.height(Spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallButton(text = dismissText, onClick = onDismiss)
            if (danger) {
                DangerButton(text = confirmText, onClick = onConfirm)
            } else {
                SmallButton(text = confirmText, primary = true, onClick = onConfirm)
            }
        }
    }
}

/**
 * 单选列表对话框，用于从候选集中挑一项（如选择人设/模型）。
 * options 为 (显示名, 副标题) 对。
 */
@Composable
fun PickDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val c = botColors
    BotDialog(onDismiss = onDismiss, title = title) {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
        ) {
            options.forEachIndexed { index, (label, sub) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index) }
                        .padding(vertical = Spacing.sm, horizontal = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (index == selectedIndex) "●" else "○",
                        color = if (index == selectedIndex) {
                            c.brand
                        } else {
                            c.textMid
                        },
                        fontSize = FontSize.body
                    )
                    Spacer(Modifier.padding(horizontal = Spacing.xxs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            color = c.textHigh,
                            fontSize = FontSize.body
                        )
                        if (sub.isNotEmpty()) {
                            Text(
                                text = sub,
                                color = c.textMid,
                                fontSize = FontSize.caption
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            SmallButton(text = Txt.CANCEL, onClick = onDismiss)
        }
    }
}

/** 底部提示条，用于替换旧的 Toast 场景（保存成功/失败等）。 */
@Composable
fun SnackBar(
    message: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(tone.bg, RoundedCornerShape(Radius.md))
            .border(Sizes.hairline, tone.fg.copy(alpha = 0.35f), RoundedCornerShape(Radius.md))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Text(
            text = message,
            color = tone.fg,
            fontSize = FontSize.small
        )
    }
}