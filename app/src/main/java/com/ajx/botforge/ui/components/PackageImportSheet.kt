package com.ajx.botforge.ui.components

import androidx.compose.foundation.background
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
import com.ajx.botforge.plugin.BfpackImporter
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.LineHeight
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import java.util.Locale

/**
 * `.bfpack` 导入预览面板。
 *
 * 这是「选文件 → 预览 → 权限清单与风险警示 → 用户确认 → 落盘并重载」
 * 这条流程里的第二、三步：在写任何文件之前，把 [BfpackImporter.preview]
 * 解析出的真实清单摆给用户看，并且只在 blockReason 为空时给出安装入口。
 *
 * ## 与 [RiskConfirmSheet] 的分工
 *
 *  - 本面板负责「这是什么包」：身份、体积、指令、权限、警告；
 *  - [RiskConfirmSheet] 负责「这一步不可撤销」：L3 + 输入包 id 核对。
 *
 * 分开是因为前者可能被反复打开查看，后者必须是一次性的强确认。
 *
 * ## 外壳与配色
 *
 * 复用同包的 [BotDialog]：它只借 Dialog 的窗口层，内部自绘，
 * 与项目里其他弹窗共用同一套圆角、描边和背景层次。
 *
 * ## 文案来源说明
 *
 * [Txt] 里目前没有导入相关的常量（只有 TITLE_PLUGINS 与 PLUGIN_* 几条），
 * 而 Txt.kt 不在本次改动的可写范围内，因此本文件的中文文案直接内联在 UI 里。
 * 通用词仍然复用 [Txt.CANCEL] 与 [Txt.CLOSE]，避免「取消」「关闭」出现多种说法。
 *
 * @param preview 导入器解析后的清单，blockReason 非空即不可安装
 * @param onInstall 点「安装」时回调；真正的 L3 确认与落盘由调用方负责
 * @param onDismiss 点「取消」「关闭」或返回键时回调；调用方负责清理临时文件
 */
@Composable
fun PackageImportSheet(
    preview: BfpackImporter.Preview,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val blocked = preview.blockReason

    BotDialog(
        onDismiss = onDismiss,
        title = "导入扩展包"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (blocked != null) {
                // 被拒绝的包：只给原因和关闭，没有安装入口。
                // BfpackImporter.failed() 构造的 Preview 其余字段全是空值，
                // 这里不渲染身份与体积，免得出现一排空行让人以为信息没加载出来。
                BlockedBody(reason = blocked)
                Spacer(Modifier.height(Spacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SmallButton(text = Txt.CLOSE, onClick = onDismiss)
                }
            } else {
                AllowedBody(preview = preview)
                Spacer(Modifier.height(Spacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallButton(text = Txt.CANCEL, onClick = onDismiss)
                    SmallButton(text = "安装", primary = true, onClick = onInstall)
                }
            }
        }
    }
}

/** 拒绝态：一段醒目的红字原因，再加一句「什么都没写」的说明。 */
@Composable
private fun BlockedBody(reason: String) {
    val c = botColors
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .background(c.danger, CircleShape)
                .width(6.dp)
                .height(6.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = "该扩展包不能安装",
            color = c.danger,
            fontSize = FontSize.body,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = reason,
        color = c.textHigh,
        fontSize = FontSize.body,
        lineHeight = LineHeight.relaxed
    )
    Spacer(Modifier.height(Spacing.md))
    Text(
        text = "包内文件未写入任何目录。修正清单或换用与当前版本兼容的扩展包后，可重新选择文件。",
        color = c.textMid,
        fontSize = FontSize.small,
        lineHeight = LineHeight.normal
    )
}

/**
 * 可安装态：身份 → 体积 → 能力 → 权限 → 风险警示。
 *
 * 顺序按「用户判断要不要装」的优先级排，不按 [BfpackImporter.Preview]
 * 的字段声明顺序——读者是用户，不是维护者。
 */
@Composable
private fun AllowedBody(preview: BfpackImporter.Preview) {
    val c = botColors

    SectionLabel("标识与版本")
    Spacer(Modifier.height(Spacing.xs))
    FactRow(label = "包标识", value = preview.id, mono = true)
    if (preview.name.isNotBlank()) {
        FactRow(label = "显示名", value = preview.name)
    }
    FactRow(label = "版本", value = preview.version)
    FactRow(label = "协议", value = "v" + preview.api)
    FactRow(label = "运行时", value = preview.runtime)
    if (preview.author.isNotBlank()) {
        FactRow(label = "作者", value = preview.author)
    }

    if (preview.description.isNotBlank()) {
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = preview.description,
            color = c.textMid,
            fontSize = FontSize.small,
            lineHeight = LineHeight.normal
        )
    }

    Spacer(Modifier.height(Spacing.md))
    SectionLabel("落盘影响")
    Spacer(Modifier.height(Spacing.xs))
    FactRow(label = "解压后", value = humanBytes(preview.totalBytes))
    FactRow(label = "文件数", value = preview.fileCount.toString() + " 个")

    if (preview.commands.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        SectionLabel("指令 " + preview.commands.size + " 条")
        Spacer(Modifier.height(Spacing.xs))
        preview.commands.forEach { cmd ->
            BulletLine(text = "/" + cmd, dotColor = c.info)
        }
    }

    if (preview.apiTools.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        SectionLabel("暴露给模型的 AI 工具 " + preview.apiTools.size + " 个")
        Spacer(Modifier.height(Spacing.xs))
        preview.apiTools.forEach { tool ->
            BulletLine(text = tool, dotColor = c.info)
        }
    }

    if (preview.uiActions.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        SectionLabel("面板动作 " + preview.uiActions.size + " 个")
        Spacer(Modifier.height(Spacing.xs))
        preview.uiActions.forEach { action ->
            BulletLine(text = action, dotColor = c.info)
        }
    }

    Spacer(Modifier.height(Spacing.md))
    SectionLabel("权限清单")
    Spacer(Modifier.height(Spacing.xs))
    if (preview.permissions.isEmpty()) {
        Text(
            text = "该包未声明任何权限。",
            color = c.textMid,
            fontSize = FontSize.small
        )
    } else {
        // permissions 已由 BfpackImporter.readablePermissions 按风险降序排好，
        // 这里保持原序、不二次排序：两处各排一次，规则一旦分叉就会互相掩盖问题。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg3, RoundedCornerShape(Radius.sm))
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            preview.permissions.forEach { perm ->
                BulletLine(text = perm, dotColor = c.warning)
            }
        }
    }

    if (preview.warnings.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        SectionLabel("风险警示 " + preview.warnings.size + " 条")
        Spacer(Modifier.height(Spacing.xs))
        // 警告与权限清单分开渲染：权限是作者声明的，警告是导入器解析时发现的，
        // 混在一起会让人以为警告也是作者写的自我说明。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.dangerGhost, RoundedCornerShape(Radius.sm))
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            preview.warnings.forEach { warning ->
                BulletLine(text = warning, dotColor = c.danger, emphasis = true)
            }
        }
    }
}

/** 段落标题：小一号、次要色、加粗，让长清单有可扫的节拍。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = botColors.textLow,
        fontSize = FontSize.caption,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * 「键  值」一行。
 *
 * [mono] 为真时用等宽字体，用于包标识这类需要逐字符核对的值：
 * 用户下一步要在确认面板里原样输入它，等宽能减少 i/l/1、O/0 这类误读。
 */
@Composable
private fun FactRow(label: String, value: String, mono: Boolean = false) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = c.textLow,
            fontSize = FontSize.small,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            color = c.textHigh,
            fontSize = FontSize.small,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            lineHeight = LineHeight.normal,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 列表项：圆点 + 正文。
 *
 * 排版与 [RiskConfirmBody] 的 facts 一致，两处弹窗依次出现时
 * 不会因为缩进不同而看起来像两个应用。
 */
@Composable
private fun BulletLine(text: String, dotColor: Color, emphasis: Boolean = false) {
    val c = botColors
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .background(dotColor, CircleShape)
                .width(4.dp)
                .height(4.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            color = if (emphasis) c.textHigh else c.textMid,
            fontSize = FontSize.small,
            lineHeight = LineHeight.normal
        )
    }
}

/**
 * 解压体积给人看：MB 保留一位小数，KB 取整，B 原样。
 *
 * 固定 [Locale.US]：某些区域设置会把小数点写成逗号，
 * 而「3.2 MB」这种读法在中文界面里没有歧义。
 */
private fun humanBytes(bytes: Long): String {
    if (bytes >= 1024L * 1024L) {
        return String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }
    if (bytes >= 1024L) {
        return (bytes / 1024L).toString() + " KB"
    }
    return bytes.toString() + " B"
}
