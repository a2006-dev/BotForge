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
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import com.ajx.botforge.ui.text.Txt

/**
 * 插件页（只读）
 *
 * 数据源是 PluginRegistry.all()，真实反射磁盘上的 plugin.json。
 * 不提供「添加/删除」按钮——插件是通过文件夹投放的，
 * 在 App 内删文件风险高于收益，因此只做展示。
 */
@Composable
fun PluginScreen(onBack: () -> Unit) {
    val c = botColors
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

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(
            title = Txt.TITLE_PLUGINS,
            subtitle = if (loaded) String.format(Txt.PLUGIN_LOADED_FMT, plugins.size) else null,
            onBack = onBack
        )

        if (loaded && plugins.isEmpty()) {
            EmptyState(
                title = Txt.PLUGIN_EMPTY_TITLE,
                description = Txt.PLUGIN_EMPTY_DESC,
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
    val c = botColors
    val compatible = p.api == PluginManifest.CURRENT_API
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.md))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = p.name,
                    color = botColors.textHigh,
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = p.id,
                    color = botColors.textLow,
                    fontSize = FontSize.caption
                )
            }
            StatusBadge(
                text = if (compatible) "v${p.version}" else Txt.PLUGIN_API_INCOMPATIBLE,
                tone = if (compatible) StatusTone.Success else StatusTone.Warning
            )
        }

        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = p.description,
                color = botColors.textMid,
                fontSize = FontSize.caption
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row {
            Text(text = "API ", color = botColors.textLow, fontSize = FontSize.caption)
            Text(
                text = p.api.toString(),
                color = if (compatible) botColors.textMid else botColors.textHigh,
                fontSize = FontSize.caption
            )
            if (p.author.isNotBlank()) {
                Text(text = "   " + Txt.PLUGIN_AUTHOR + " ", color = botColors.textLow, fontSize = FontSize.caption)
                Text(text = p.author, color = botColors.textMid, fontSize = FontSize.caption)
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = String.format(Txt.PLUGIN_CMD_FMT, p.commands.size),
            color = botColors.textLow,
            fontSize = FontSize.caption
        )
        Spacer(Modifier.height(Spacing.xxs))
        p.commands.forEach { c ->
            Row(modifier = Modifier.padding(vertical = Spacing.xxs)) {
                Text(
                    text = "/" + c.command,
                    color = botColors.textHigh,
                    fontSize = FontSize.caption
                )
                Text(
                    text = "  " + c.description.ifBlank { c.usage },
                    color = botColors.textLow,
                    fontSize = FontSize.caption
                )
                if (c.scope.contains("group")) {
                    Text(text = "  群", color = botColors.textMid, fontSize = FontSize.caption)
                }
            }
        }
    }
}