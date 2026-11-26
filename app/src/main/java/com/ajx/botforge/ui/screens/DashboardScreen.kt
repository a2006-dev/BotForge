package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.StatusDot
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.theme.Bg0
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import com.ajx.botforge.ui.viewmodel.BotListViewModel
import kotlinx.coroutines.launch

/**
 * 首页仪表盘。
 * 只展示真实可计算的数据：机器人数量与启用数、人设数、模型数、服务状态、最近日志条数。
 * 不虚构任何“健康度”“成功率”之类的假指标。
 */
@Composable
fun DashboardScreen(
    onOpenBots: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val botVm: BotListViewModel = viewModel()
    val botState by botVm.state.collectAsStateWithLifecycle()

    val repo = remember { BotForgeRepository() }
    var personaCount by remember { mutableStateOf(0) }
    var modelCount by remember { mutableStateOf(0) }
    var logCount by remember { mutableStateOf(0) }
    var serviceText by remember { mutableStateOf("-") }
    var runningCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        personaCount = repo.personas().size
        modelCount = repo.models().size
        logCount = repo.logs().size
        serviceText = repo.serviceStatus()
        runningCount = repo.runningCount()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部品牌区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg0)
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
        ) {
            Text(
                text = "BotForge",
                color = TextHigh,
                fontSize = FontSize.display,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = "QQ 官方机器人工作台",
                color = TextLow,
                fontSize = FontSize.small
            )
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    tone = if (runningCount > 0) StatusTone.Success else StatusTone.Neutral,
                    size = Sizes.dot
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (runningCount > 0) "运行中 · $runningCount 个连接" else serviceText,
                    color = TextMid,
                    fontSize = FontSize.small
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 统计条
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                StatTile(
                    label = "机器人",
                    value = "${botState.bots.size}",
                    sub = "${botState.enabledCount} 启用",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "人设",
                    value = "$personaCount",
                    sub = "条",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "模型",
                    value = "$modelCount",
                    sub = "个",
                    modifier = Modifier.weight(1f)
                )
            }

            SectionHeader("管理")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                EntryRow("机器人配置", "AppID、密钥、群白名单") { onOpenBots() }
                EntryRow("人设", "提示词、语气、记忆深度") { onOpenPersonas() }
                EntryRow("模型", "接口地址、密钥、超时") { onOpenModels() }
            }

            SectionHeader("运维")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                EntryRow("运行日志", "$logCount 条记录") { onOpenLogs() }
                EntryRow("插件", "查看已加载插件") { onOpenPlugins() }
                EntryRow("设置", "全局开关与服务保活") { onOpenSettings() }
            }

            SectionHeader("关于")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                EntryRow("关于 BotForge", "版本与项目信息") { onOpenAbout() }
            }

            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Bg2, RoundedCornerShape(com.ajx.botforge.ui.theme.Radius.lg))
            .padding(Spacing.md)
    ) {
        Text(text = label, color = TextLow, fontSize = FontSize.caption)
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = value,
            color = if (label == "机器人" && value != "0") Brand else TextHigh,
            fontSize = FontSize.metric,
            fontWeight = FontWeight.Bold
        )
        Text(text = sub, color = TextMid, fontSize = FontSize.caption)
    }
}

@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(com.ajx.botforge.ui.theme.Radius.md))
            .androidxClickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium
            )
            Text(text = subtitle, color = TextLow, fontSize = FontSize.caption)
        }
        Text(text = "›", color = TextLow, fontSize = FontSize.title)
    }
}

/** 避免 import 冲突的 clickable 别名。 */
private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))