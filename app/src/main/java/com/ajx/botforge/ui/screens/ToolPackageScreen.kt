package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ai.tool.BuiltinTools
import com.ajx.botforge.ai.tool.PackageRegistry
import com.ajx.botforge.ai.tool.ToolDef
import com.ajx.botforge.ai.tool.ToolPackage
import com.ajx.botforge.ai.tool.ToolPermission
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.botColors

/**
 * 工具包管理页。
 *
 * ## 这个页面的职责
 *
 * 它是 [ToolPermission.ASK] 语义能成立的前提。后台机器人没有可以点确认的界面，
 * 所以「需确认」的真实含义是「用户还没表态，先不给模型」。用户在这里把它改成
 * 「允许」，这个动作本身就是一次性、可追溯的确认。
 *
 * ## 为什么按包 + 按工具两层
 *
 * 只按包开关太粗：想用 system_command 的 am start，但不想让它能改系统设置，
 * 就需要单工具粒度。只按工具又太碎：7 个工具还好，搬到一百多个之后没法用。
 * 所以先按包分组，包内再逐工具调权限。
 *
 * ## 状态刷新
 *
 * 所有读写都直接打到 [PackageRegistry] / [PackagePrefs]（SharedPreferences），
 * 没有内存缓存层。改完立刻生效，下次 [BuiltinTools.all] 就是新的结果——
 * 不需要重启机器人，也不需要「保存」按钮。
 */
@Composable
fun ToolPackageScreen(onBack: () -> Unit) {
    val c = botColors
    var tick by remember { mutableIntStateOf(0) }

    // 第一次进页面时确保内置包已注册（正常流程 App.onCreate 已经做过，
    // 这里兜底是为了「清数据后直接进设置页」这种情况）。
    remember { BuiltinTools.ensureRegistered(); tick }

    val packages = remember(tick) { PackageRegistry.all() }
    val enabledCount = remember(tick) { packages.count { PackageRegistry.isEnabled(it) } }
    val toolCount = remember(tick) { PackageRegistry.activeTools().size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg0)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md)
    ) {
        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "工具包",
                color = c.textHigh,
                fontSize = FontSize.title,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            SmallButton(text = "返回", onClick = onBack)
        }

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = "当前 $enabledCount / ${packages.size} 个包启用，模型可见 $toolCount 个工具。" +
                "改动立即生效，无需重启。",
            color = c.textLow,
            fontSize = FontSize.caption
        )

        Spacer(Modifier.height(Spacing.md))

        packages.forEach { pkg ->
            PackageBlock(
                pkg = pkg,
                onChanged = { tick++ }
            )
            Spacer(Modifier.height(Spacing.md))
        }

        // 底部说明：把 ASK 的真实语义讲清楚，避免用户以为是 bug
        Text(
            text = "「需确认」的工具不会出现在模型可见列表里。因为机器人跑在后台，" +
                "没有能点确认的界面，所以这一类按「未授权」处理——" +
                "模型调它会收到明确拒绝，而不是静默执行。想启用就改成「允许」。",
            color = c.textLow,
            fontSize = FontSize.caption
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun PackageBlock(pkg: ToolPackage, onChanged: () -> Unit) {
    val c = botColors
    val enabled = PackageRegistry.isEnabled(pkg)
    val locked = pkg.id in PackageRegistry.ALWAYS_ON
    var expanded by remember { mutableStateOf(false) }

    Column {
        SectionHeader(pkg.displayName)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg2, RoundedCornerShape(Radius.lg))
                .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
        ) {
            // 包级开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !locked) {
                        PackageRegistry.setEnabled(pkg.id, !enabled)
                        onChanged()
                    }
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pkg.displayName,
                        color = c.textHigh,
                        fontSize = FontSize.body,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (locked) "${pkg.description}（核心包，不可关闭）"
                        else pkg.description,
                        color = c.textLow,
                        fontSize = FontSize.caption
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                if (locked) {
                    Text(
                        text = "锁定",
                        color = c.textMid,
                        fontSize = FontSize.caption
                    )
                } else {
                    TrackSwitch(checked = enabled)
                }
            }

            if (!enabled) {
                // 包关闭时把工具列表折叠掉，避免误以为工具还在生效
                SettingDivider()
                Text(
                    text = "此包已关闭，${pkg.defs.size} 个工具都不会交给模型。",
                    color = c.textLow,
                    fontSize = FontSize.caption,
                    modifier = Modifier.padding(Spacing.md)
                )
                return@Column
            }

            // 展开/收起工具级权限
            SettingDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pkg.defs.size} 个工具的权限",
                    color = c.textMid,
                    fontSize = FontSize.caption
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起" else "展开",
                    color = c.brand,
                    fontSize = FontSize.caption
                )
            }

            if (expanded) {
                pkg.defs.forEach { def ->
                    SettingDivider()
                    ToolPermRow(
                        pkg = pkg,
                        def = def,
                        onChanged = onChanged
                    )
                }
            }
        }
    }
}

/**
 * 单个工具的权限行。
 *
 * 点一下在 ALLOW → ASK → DENY 之间循环，而不是弹三选一菜单：
 * 三档循环对这种高频微调更顺手，也少一次点击。
 */
@Composable
private fun ToolPermRow(pkg: ToolPackage, def: ToolDef, onChanged: () -> Unit) {
    val c = botColors
    val perm = PackageRegistry.permissionOf(pkg, def)
    val overridden = com.ajx.botforge.ai.tool.PackagePrefs
        .toolPermission(pkg.id, def.tool.name) != null

    val (label, tint) = when (perm) {
        ToolPermission.ALLOW -> "允许" to c.brand
        ToolPermission.ASK -> "需确认" to c.warning
        ToolPermission.DENY -> "禁用" to c.textLow
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val next = when (perm) {
                    ToolPermission.ALLOW -> ToolPermission.ASK
                    ToolPermission.ASK -> ToolPermission.DENY
                    ToolPermission.DENY -> ToolPermission.ALLOW
                }
                PackageRegistry.setPermission(pkg.id, def.tool.name, next)
                onChanged()
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = def.tool.name,
                    color = c.textHigh,
                    fontSize = FontSize.caption,
                    fontWeight = FontWeight.Medium
                )
                if (overridden) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = "已改动",
                        color = c.textLow,
                        fontSize = FontSize.caption
                    )
                }
            }
            if (def.note.isNotBlank()) {
                Text(
                    text = def.note,
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Box(
            modifier = Modifier
                .background(tint.copy(alpha = 0.14f), RoundedCornerShape(Radius.sm))
                .border(Sizes.hairline, tint.copy(alpha = 0.5f), RoundedCornerShape(Radius.sm))
                .padding(horizontal = Spacing.sm, vertical = 3.dp)
        ) {
            Text(text = label, color = tint, fontSize = FontSize.caption)
        }
    }
}

@Composable
private fun SettingDivider() {
    val c = botColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md)
            .height(Sizes.hairline)
            .background(c.line)
    )
}

/** 与 SettingsScreen 同款自绘开关，保持视觉一致 */
@Composable
private fun TrackSwitch(checked: Boolean) {
    val c = botColors
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .background(
                if (checked) c.brand else c.bg4,
                RoundedCornerShape(Radius.full)
            )
            .border(
                Sizes.hairline,
                if (checked) c.brand else c.lineStrong,
                RoundedCornerShape(Radius.full)
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .background(
                    if (checked) c.textInverse else c.textMid,
                    CircleShape
                )
        )
    }
}