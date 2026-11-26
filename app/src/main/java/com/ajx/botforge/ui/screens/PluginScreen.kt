package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ajx.botforge.plugin.PluginManifest
import com.ajx.botforge.plugin.PluginRegistry
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid

/**
 * 插件页（只读）
 *
 * 数据源是 PluginRegistry.all()，真实反射磁盘上的 plugin.json。
 * 不提供「添加/删除」按钮——插件是通过文件夹投放的，
 * 在 App 内删文件风险高于收益，因此只做展示。
 */
@Composable
fun PluginScreen(onBack: () -> Unit) {
    var plugins by remember { mutableStateOf<List<PluginManifest>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        plugins = try {
            PluginRegistry.all()
        } catch (e: Exception) {
            emptyList()
        }
        loaded = true
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg1)) {
        PageTopBar(
            title = "插件",
            subtitle = if (loaded) "${plugins.size} 个已加载" else null,
            onBack = onBack
        )

        if (loaded && plugins.isEmpty()) {
            EmptyState(
                title = "没有找到插件",
                description = "把插件文件夹放到外部存储的 " +
                        "Android/data/com.ajx.botforge/files/plugins/ 下，\n" +
                        "每个插件目录内需要一份 plugin.json。\n" +
                        "首次启动会自动生成 hello 示例。",
                icon = "⚙",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.md,
                    vertical = Spacing.sm
                )
            ) {
                items(plugins, key = { it.id }) { p ->
                    PluginCard(p)
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }
    }
}

@Composable
private fun PluginCard(p: PluginManifest) {
    val compatible = p.api == PluginManifest.CURRENT_API
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.md))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = p.name,
                    color = TextHigh,
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = p.id,
                    color = TextLow,
                    fontSize = FontSize.caption
                )
            }
            StatusBadge(
                text = if (compatible) "v${p.version}" else "API 不兼容",
                tone = if (compatible) StatusTone.Success else StatusTone.Warning
            )
        }

        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = p.description,
                color = TextMid,
                fontSize = FontSize.caption
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row {
            Text(text = "API ", color = TextLow, fontSize = FontSize.caption)
            Text(
                text = p.api.toString(),
                color = if (compatible) TextMid else TextHigh,
                fontSize = FontSize.caption
            )
            if (p.author.isNotBlank()) {
                Text(text = "   作者 ", color = TextLow, fontSize = FontSize.caption)
                Text(text = p.author, color = TextMid, fontSize = FontSize.caption)
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "指令 ${p.commands.size} 条",
            color = TextLow,
            fontSize = FontSize.caption
        )
        Spacer(Modifier.height(Spacing.xxs))
        p.commands.forEach { c ->
            Row(modifier = Modifier.padding(vertical = Spacing.xxs)) {
                Text(
                    text = "/" + c.command,
                    color = TextHigh,
                    fontSize = FontSize.caption
                )
                Text(
                    text = "  " + c.description.ifBlank { c.usage },
                    color = TextLow,
                    fontSize = FontSize.caption
                )
                if (c.scope.contains("group")) {
                    Text(text = "  群", color = TextMid, fontSize = FontSize.caption)
                }
            }
        }
    }
}