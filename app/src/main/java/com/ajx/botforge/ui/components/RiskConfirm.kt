package com.ajx.botforge.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import com.ajx.botforge.ui.text.Txt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.LineHeight
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors

/**
 * 危险操作分级。
 *
 * 这是整套「确认闸门」的骨架，对应《01-设计规范》§6 与提示词 §10：
 *
 *  | 级别 | 含义                             | 确认方式                          |
 *  |------|----------------------------------|-----------------------------------|
 *  | L0   | 只读、可逆、无副作用             | 直接执行                          |
 *  | L1   | 改变本地状态，可撤销             | 直接执行 + Snackbar 提示          |
 *  | L2   | 改变运行行为，影响正在跑的服务   | 轻确认（一次点击）                |
 *  | L3   | 删除数据 / 重置配置，不可撤销    | 强确认 + 必须核对名称             |
 *  | L4   | 权限提升 / 执行任意命令          | 强确认 + 核对名称 + 显示影响范围  |
 *
 * 关键设计：L3/L4 不接受「小按钮直接确认」。用户必须先把对象名称
 * 完整输入一遍（[confirmWord]），否则确认按钮保持禁用。
 * 这条规则来自真实事故：误触「删除」把唯一一个人设删掉，
 * 弹窗里只写「确定删除？」时用户根本没读就点了。
 */
enum class RiskLevel(
    val code: String,
    val label: String,
    /** 是否需要输入名称核对 */
    val requireTypedName: Boolean
) {
    L0("L0", "只读", false),
    L1("L1", "可撤销", false),
    L2("L2", "影响运行", false),
    L3("L3", "不可撤销", true),
    L4("L4", "高风险", true)
}

/**
 * 统一危险操作确认面板的全部入参。
 *
 * 把「文案 + 级别 + 核对词 + 影响数量」打包成一个对象，
 * 避免每个调用点各自拼 6 个参数导致文案风格漂移
 * （旧代码里三处删除弹窗的句式都不一样）。
 */
data class RiskConfirmSpec(
    val level: RiskLevel,
    /** 一句话动作名，如「删除人设」 */
    val action: String,
    /** 一句话说明后果，必须写清「会失去什么」 */
    val consequence: String,
    /** 需要核对的对象名称原文；L3/L4 必填 */
    val confirmWord: String = "",
    /** 附带的事实清单（受影响的机器人数量、群数量等） */
    val facts: List<String> = emptyList()
)

/**
 * L0~L4 统一确认卡。
 *
 * 只负责渲染 + 收集「是否已核对」，真正的执行由调用方在 onConfirm 里做。
 * 这样它能被放进 Dialog、BottomSheet、整页 Sheet，不必为每种容器复制一遍。
 */
@Composable
fun RiskConfirmBody(
    spec: RiskConfirmSpec,
    typed: String,
    onTypedChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = botColors
    val tone: Color = when (spec.level) {
        RiskLevel.L0 -> c.neutral
        RiskLevel.L1 -> c.info
        RiskLevel.L2 -> c.warning
        RiskLevel.L3 -> c.danger
        RiskLevel.L4 -> c.danger
    }
    val toneBg: Color = when (spec.level) {
        RiskLevel.L0 -> c.neutralGhost
        RiskLevel.L1 -> c.infoGhost
        RiskLevel.L2 -> c.warningGhost
        RiskLevel.L3 -> c.dangerGhost
        RiskLevel.L4 -> c.dangerGhost
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 级别徽标 + 动作名
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(toneBg, RoundedCornerShape(Radius.xs))
                    .padding(horizontal = Spacing.sm, vertical = 2.dp)
            ) {
                Text(
                    text = "${spec.level.code} · ${spec.level.label}",
                    color = tone,
                    fontSize = FontSize.caption,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = spec.action,
                color = c.textHigh,
                fontSize = FontSize.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(Spacing.md))
        Text(
            text = spec.consequence,
            color = c.textMid,
            fontSize = FontSize.body,
            lineHeight = LineHeight.relaxed
        )

        // 事实清单：把「影响多少东西」摆出来，比形容词有说服力
        if (spec.facts.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bg3.copy(alpha = if (c.isDark) 1f else 0.7f), RoundedCornerShape(Radius.sm))
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                spec.facts.forEach { fact ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .background(tone, CircleShape)
                                .width(4.dp)
                                .height(4.dp)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = fact,
                            color = c.textMid,
                            fontSize = FontSize.small,
                            lineHeight = LineHeight.normal
                        )
                    }
                }
            }
        }

        // 名称核对输入框：只有 L3/L4 才出现
        if (spec.level.requireTypedName && spec.confirmWord.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "请输入「${spec.confirmWord}」以确认",
                color = c.textLow,
                fontSize = FontSize.small
            )
            Spacer(Modifier.height(Spacing.xs))
            androidx.compose.foundation.text.BasicTextField(
                value = typed,
                onValueChange = onTypedChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = c.textHigh,
                    fontSize = FontSize.body,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(c.bg3, RoundedCornerShape(Radius.sm))
                    .border(Sizes.hairline, c.lineStrong, RoundedCornerShape(Radius.sm))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }
    }
}

/**
 * 判定「当前是否允许确认」。
 *
 * 单独抽出来，是为了让 Dialog 版和 Sheet 版共用同一判断，
 * 不至于一边允许点、另一边灰着。
 */
fun RiskConfirmSpec.canConfirm(typed: String): Boolean =
    !level.requireTypedName || typed.trim() == confirmWord.trim()

/**
 * 分级执行闸门：包一层，让调用点用一句话表达「这是 L3」。
 *
 * 用法：
 * ```
 * var show by remember { mutableStateOf(false) }
 * // 点「删除」→ show = true
 * if (show) RiskConfirmSheet(
 *     spec = RiskConfirmSpec(RiskLevel.L3, "删除人设", "该人设的提示词将被永久移除。",
 *                            confirmWord = persona.name,
 *                            facts = listOf("${refCount} 个机器人将失去人设")),
 *     onConfirm = { vm.delete(id); show = false },
 *     onDismiss = { show = false }
 * )
 * ```
 */
@Composable
fun RiskConfirmSheet(
    spec: RiskConfirmSpec,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = botColors
    val typedState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val typed = typedState.value
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg2,
        shape = RoundedCornerShape(Radius.xl),
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                RiskConfirmBody(
                    spec = spec,
                    typed = typed,
                    onTypedChange = { typedState.value = it }
                )
            }
        },
        confirmButton = {
            val enabled = spec.canConfirm(typed)
            val base = if (spec.level == RiskLevel.L3 || spec.level == RiskLevel.L4) {
                c.danger
            } else {
                c.brand
            }
            Box(
                modifier = Modifier
                    .background(
                        if (enabled) base else c.bg4,
                        RoundedCornerShape(Radius.sm)
                    )
                    .androidClickableIf(enabled, onConfirm)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            ) {
                Text(
                    text = if (spec.level.requireTypedName) "确认${spec.action}" else spec.action,
                    color = if (enabled) Color.White else c.textLow,
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            Box(
                modifier = Modifier
                    .androidClickableIf(true, onDismiss)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text(
                    text = Txt.CANCEL,
                    color = c.textMid,
                    fontSize = FontSize.body
                )
            }
        }
    )
}

/** 只在 enabled 时挂点击，禁用态连涟漪都不该有。 */
private fun Modifier.androidClickableIf(
    enabled: Boolean,
    onClick: () -> Unit
): Modifier = if (enabled) {
    this.then(androidx.compose.foundation.clickable(enabled = true, onClick = onClick))
} else {
    this
}