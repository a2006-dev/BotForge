package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ajx.botforge.qq.BotManager
import com.ajx.botforge.qq.BotStatusRegistry
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.StatusDot
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.theme.Bg0
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.Bg3
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import kotlinx.coroutines.flow.MutableStateFlow

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
    onOpenAbout: () -> Unit,
    /** 启动/停止前台服务；传 null 表示由上层提供原生实现 */
    onToggleService: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { BotForgeRepository() }
    var personaCount by remember { mutableStateOf(0) }
    var modelCount by remember { mutableStateOf(0) }
    var logCount by remember { mutableStateOf(0) }
    var serviceText by remember { mutableStateOf("-") }
    var runningCount by remember { mutableStateOf(0) }
    var serviceRunning by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 真实的连接状态：由 BotStatusRegistry 推送，StateFlow 驱动 UI 刷新。
    // 初版这里只在 LaunchedEffect(Unit) 里读一次快照，之后永不更新，
    // 且 runningCount 取的是「配置数量」而非在线数，于是网关早断、
    // 界面仍显示「运行中 · N 个连接」。这是任务书第三节的虚假状态。
    val connState = remember { MutableStateFlow(BotManager.summary()) }
    val summary by connState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        personaCount = repo.personas().size
        modelCount = repo.models().size
        logCount = repo.logs().size
        serviceText = repo.serviceStatus()
        runningCount = repo.runningCount()
        serviceRunning = repo.isServiceRunning()
        // 订阅连接层推送，任何状态变化都立刻反映到首页
        connState.value = BotManager.summary()
        BotStatusRegistry.listener = { connState.value = BotManager.summary() }
    }

    DisposableEffect(Unit) {
        onDispose { BotStatusRegistry.listener = null }
    }

    // 日志条数与服务存活随运行变化，做一次轻量轮询即可（不涉及连接状态）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            logCount = repo.logs().size
            serviceText = repo.serviceStatus()
            serviceRunning = repo.isServiceRunning()
        }
    }

    // toast 自动消失，避免常驻遮挡
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2600)
            toast = null
        }
    }

    /**
     * 启停服务
     *
     * 这里是首页唯一的主操作。之前整个 App 只有旧 Activity 里有启停入口，
     * 迁移到 Compose 后链路断掉，用户能看状态却无法启动 —— 这是功能缺口，不是设计。
     */
    fun toggle() {
        if (serviceRunning) {
            repo.stopService(context)
            serviceRunning = false
            serviceText = repo.serviceStatus()
            toast = "已停止服务"
        } else {
            val reason = repo.startService(context)
            if (reason != null) {
                toast = reason
            } else {
                serviceRunning = true
                serviceText = repo.serviceStatus()
                toast = "已启动，正在连接 QQ 网关…"
            }
        }
        // 立刻刷新一次聚合状态，不必等 3 秒轮询
        connState.value = BotManager.summary()
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                // 状态点颜色严格跟随连接状态，不再用「配置数量 > 0」冒充在线
                StatusDot(
                    tone = when {
                        summary.loaded == 0 -> StatusTone.Neutral
                        summary.online == summary.loaded -> StatusTone.Success
                        summary.online > 0 -> StatusTone.Warning
                        summary.reconnecting > 0 || summary.connecting > 0 -> StatusTone.Warning
                        summary.error > 0 -> StatusTone.Danger
                        else -> StatusTone.Neutral
                    },
                    size = Sizes.dot
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (summary.loaded > 0) summary.text else serviceText,
                    color = TextMid,
                    fontSize = FontSize.small
                )
            }

            // 主操作：启动 / 停止
            //
            // 放在状态下面而不是藏进设置里 —— 这是整个 App 最主要的动作，
            // 用户打开首页第一件事就是让机器人跑起来。
            Spacer(Modifier.height(Spacing.md))
            if (serviceRunning) {
                SecondaryButton(
                    text = "停止服务",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { toggle() }
                )
            } else {
                PrimaryButton(
                    text = "启动服务",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { toggle() }
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = if (serviceRunning) {
                    "前台服务运行中，退出界面也不会断开"
                } else {
                    "启动后会建立 QQ 网关长连接，状态实时显示"
                },
                color = TextLow,
                fontSize = FontSize.micro
            )
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
                    label = "在线",
                    value = "${summary.online}/${summary.loaded}",
                    sub = if (summary.loaded == 0) "未启动" else "运行中 / 已加载",
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

    // 轻量 toast：只用 Compose 绘制，不依赖 Activity 的 Toast，
    // 这样在任何宿主里都能显示启动失败的具体原因。
    toast?.let { msg ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .background(Bg3, RoundedCornerShape(Radius.md))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text(
                    text = msg,
                    color = TextHigh,
                    fontSize = FontSize.small
                )
            }
        }
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