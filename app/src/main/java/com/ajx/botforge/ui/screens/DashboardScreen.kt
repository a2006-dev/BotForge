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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ajx.botforge.data.Logger
import com.ajx.botforge.permission.PermissionGuide
import com.ajx.botforge.qq.BotConnState
import com.ajx.botforge.qq.BotConnectionState
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.data.DashboardStore
import com.ajx.botforge.ui.theme.Bg0
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.Bg3
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.Danger
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.LineStrong
import com.ajx.botforge.ui.theme.Success
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import com.ajx.botforge.ui.theme.Warning

/**
 * 首页 —— 机器人运行管理中心
 *
 * 按任务书第一节整改：重新梳理信息层级，优先展示
 * 服务状态 / 真实在线机器人 / 运行异常，并增加最近运行事件。
 *
 * 信息层级（从上到下，重要度递减）：
 *   1. 品牌区      BotForge + 一句话概括（在线数如实反映连接层）
 *   2. 服务状态区  前台服务存活 + 主操作按钮（唯一主操作）
 *   3. 连接状态区  每个机器人的真实 WebSocket 状态（不是"配置了几个"）
 *   4. 运行异常    仅在出错时出现，避免常态占位
 *   5. 最近事件    真实日志尾部，让用户看到"刚才发生了什么"
 *   6. 数据统计    人设/模型/日志条数
 *   7. 功能入口    按"管理 / 运维 / 关于"分组
 *
 * 所有数字都来自 BotManager / BotStatusRegistry / Config / Logger，
 * 没有任何虚构指标（不编造成功率、健康度之类的数字）。
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
    /** 启动/停止前台服务 */
    onToggleService: () -> Unit = {},
    /** 补齐通知 / 悬浮窗 / 电池白名单权限 */
    onRequestPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { BotForgeRepository() }

    // 唯一状态源：服务存活 + 连接层聚合 + 每机器人快照 + 真实事件
    val state by DashboardStore.state.collectAsStateWithLifecycle()

    var toast by remember { mutableStateOf<String?>(null) }
    var permStatus by remember { mutableStateOf<PermissionGuide.Status?>(null) }

    // 订阅连接层推送（幂等）。页面重建后不会丢事件，因为 Store 是进程单例。
    LaunchedEffect(Unit) {
        DashboardStore.install()
        DashboardStore.refreshAll()
        permStatus = PermissionGuide.status(context)
    }

    // 服务存活、配置数量、日志需要轮询（这些没有推送源）；
    // 连接状态由 BotStatusRegistry 推送，不依赖轮询。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            DashboardStore.refreshAll()
            permStatus = PermissionGuide.status(context)
        }
    }

    // 离开页面时不解除订阅：DashboardStore 是进程单例，
    // 保留已积累的失败/重连事件，返回首页时能立刻看到刚才发生了什么。
    // 真正需要清理时由 App 退出统一处理。

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2600)
            toast = null
        }
    }

    fun toggle() {
        if (state.serviceRunning) {
            repo.stopService(context)
            DashboardStore.syncService(false)
            toast = "已停止服务"
        } else {
            val reason = repo.startService(context)
            if (reason != null) {
                toast = reason
            } else {
                DashboardStore.syncService(true)
                toast = "已启动，正在连接 QQ 网关…"
            }
        }
        DashboardStore.refreshBots()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg1)
                .verticalScroll(rememberScrollState())
        ) {
            // ---------- 1. 品牌区 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg0)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
            ) {
                Text(
                    text = "BotForge",
                    color = TextHigh,
                    fontSize = FontSize.display,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = summaryLine(state.serviceRunning, state.summary.loaded, state.summary.online, state.summary.hasAny),
                    color = TextMid,
                    fontSize = FontSize.small
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                // ---------- 2. 服务状态区 ----------
                ServicePanel(
                    running = state.serviceRunning,
                    onToggle = { toggle() }
                )

                // 权限未补齐时提示（依附在状态区之后，不抢占主操作位置）
                val ps = permStatus
                if (ps != null && !ps.allGood) {
                    PermissionNotice(
                        text = PermissionGuide.missingText(ps),
                        onFix = {
                            onRequestPermissions()
                            permStatus = PermissionGuide.status(context)
                        }
                    )
                }

                // ---------- 3. 连接状态区 ----------
                // 只在服务启动后才有意义；未启动时显示"未启动"而不是一排 0
                if (state.serviceRunning || state.bots.isNotEmpty()) {
                    SectionTitle("连接状态")
                    if (state.bots.isEmpty()) {
                        HintRow("等待机器人加载…")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            state.bots.forEach { bot -> BotCard(bot) }
                        }
                    }
                }

                // ---------- 4. 运行异常（有才显示） ----------
                if (state.hasAbnormal) {
                    SectionTitle("运行异常")
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        state.abnormalBots.forEach { bot ->
                            AbnormalRow(bot)
                        }
                        if (state.abnormalBots.isEmpty() && state.summary.error > 0) {
                            HintRow("${state.summary.error} 个机器人处于异常状态，详见运行日志")
                        }
                    }
                }

                // ---------- 5. 最近运行事件 ----------
                SectionTitle("最近运行事件")
                EventList(
                    events = state.events,
                    onOpenLogs = onOpenLogs
                )

                // ---------- 6. 数据统计 ----------
                SectionTitle("数据")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    StatTile(
                        label = "人设",
                        value = "${state.personaCount}",
                        sub = "条",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "模型",
                        value = "${state.modelCount}",
                        sub = "个",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "日志",
                        value = "${state.logTotal}",
                        sub = "条",
                        modifier = Modifier.weight(1f)
                    )
                }

                // ---------- 7. 功能入口 ----------
                SectionTitle("管理")
                EntryGroup {
                    EntryRow("机器人配置", "AppID、密钥、群白名单", onOpenBots)
                    Divider()
                    EntryRow("人设", "提示词、语气、记忆深度", onOpenPersonas)
                    Divider()
                    EntryRow("模型", "接口地址、密钥、超时", onOpenModels)
                }

                SectionTitle("运维")
                EntryGroup {
                    EntryRow("运行日志", "${state.logTotal} 条记录", onOpenLogs)
                    Divider()
                    EntryRow("插件", "查看已加载插件", onOpenPlugins)
                    Divider()
                    EntryRow("设置", "全局开关与保活配置", onOpenSettings)
                }

                SectionTitle("关于")
                EntryGroup {
                    EntryRow("关于 BotForge", "版本与项目信息", onOpenAbout)
                }

                Spacer(Modifier.height(Spacing.huge))
            }
        }

        // Compose 内绘制的 toast，不依赖 Activity，任何宿主都能提示启动失败原因
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
                        .border(Sizes.hairline, LineStrong, RoundedCornerShape(Radius.md))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                ) {
                    Text(text = msg, color = TextHigh, fontSize = FontSize.small)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 子组件

/** 品牌区副标题：一句话说清当前处于什么阶段，全部基于真实计数 */
private fun summaryLine(serviceRunning: Boolean, loaded: Int, online: Int, hasAny: Boolean): String {
    if (!serviceRunning) return "前台服务未启动"
    if (loaded == 0) return "服务运行中 · 暂无启用机器人"
    if (!hasAny) return "服务运行中 · 等待连接"
    return "服务运行中 · $online/$loaded 在线"
}

/** 服务状态面板：存活指示 + 主操作按钮（整个 App 最主要的动作） */
@Composable
private fun ServicePanel(running: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(Sizes.dot)
                    .background(if (running) Success else TextLow, CircleShape)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = if (running) "前台服务运行中" else "前台服务已停止",
                color = TextHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = if (running) {
                "退出界面不会断开；熄屏后依赖保活配置"
            } else {
                "启动后建立 QQ 网关长连接"
            },
            color = TextLow,
            fontSize = FontSize.micro
        )
        Spacer(Modifier.height(Spacing.md))
        if (running) {
            SecondaryButton(
                text = "停止服务",
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggle
            )
        } else {
            PrimaryButton(
                text = "启动服务",
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggle
            )
        }
    }
}

/** 单个机器人的连接卡片：状态、在线时长、心跳、ACK、重连次数 */
@Composable
private fun BotCard(bot: BotConnectionState) {
    val (toneColor, toneLabel) = when (bot.state) {
        BotConnState.CONNECTED -> Success to "在线"
        BotConnState.CONNECTING -> Warning to "连接中"
        BotConnState.AUTHENTICATING -> Warning to "鉴权中"
        BotConnState.RECONNECTING -> Warning to "重连中"
        BotConnState.ERROR -> Danger to "异常"
        BotConnState.STOPPED -> TextLow to "已停止"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(Sizes.dot)
                    .background(toneColor, CircleShape)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = bot.botName.ifEmpty { bot.botId },
                color = TextHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(text = toneLabel, color = toneColor, fontSize = FontSize.caption)
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            MiniStat("心跳", heartbeatText(bot.heartbeatInterval))
            MiniStat("ACK", bot.ackLatencyText())
            MiniStat("重连", "${bot.reconnectCount}")
        }

        // 代次与连接时刻：排查问题时的关键线索，平时不喧宾夺主
        if (bot.generation > 0) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "第 ${bot.generation} 代连接" +
                        if (bot.connectedAt > 0) " · " + clockText(bot.connectedAt) + " 上线" else "",
                color = TextLow,
                fontSize = FontSize.micro
            )
        }
    }
}

/** 异常行：错误原因直接展示，不需要点进日志 */
@Composable
private fun AbnormalRow(bot: BotConnectionState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.md))
            .border(Sizes.hairline, Danger.copy(alpha = 0.35f), RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Text(
            text = bot.botName.ifEmpty { bot.botId } + " · " + bot.state.label,
            color = Danger,
            fontSize = FontSize.small,
            fontWeight = FontWeight.Medium
        )
        val err = bot.lastError
        if (!err.isNullOrEmpty()) {
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = err,
                color = TextMid,
                fontSize = FontSize.micro,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 最近运行事件列表：来自 Logger 环形缓冲的真实日志尾部 */
@Composable
private fun EventList(events: List<Logger.Line>, onOpenLogs: () -> Unit) {
    if (events.isEmpty()) {
        HintRow("暂无运行记录")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.lg))
    ) {
        events.forEachIndexed { index, line ->
            if (index > 0) Divider()
            EventRow(line)
        }
        Divider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLogs)
                .padding(vertical = Spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "查看全部日志 ›", color = Brand, fontSize = FontSize.small)
        }
    }
}

@Composable
private fun EventRow(line: Logger.Line) {
    val color = when (line.level) {
        2 -> Danger
        1 -> Warning
        else -> TextMid
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = line.time,
            color = TextLow,
            fontSize = FontSize.micro,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = line.text,
            color = color,
            fontSize = FontSize.micro,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PermissionNotice(text: String, onFix: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg3, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Text(
            text = "权限未补齐",
            color = Warning,
            fontSize = FontSize.small,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(text = text, color = TextLow, fontSize = FontSize.micro)
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(
            text = "立即补齐权限",
            modifier = Modifier.fillMaxWidth(),
            onClick = onFix
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = TextLow,
        fontSize = FontSize.caption,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun HintRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Text(text = text, color = TextLow, fontSize = FontSize.small)
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(text = label, color = TextLow, fontSize = FontSize.micro)
        Text(text = value, color = TextMid, fontSize = FontSize.small)
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md)
    ) {
        Text(text = label, color = TextLow, fontSize = FontSize.caption)
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = value,
            color = TextHigh,
            fontSize = FontSize.title,
            fontWeight = FontWeight.Bold
        )
        Text(text = sub, color = TextLow, fontSize = FontSize.micro)
    }
}

@Composable
private fun EntryGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.lg))
    ) {
        content()
    }
}

@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            Text(text = subtitle, color = TextLow, fontSize = FontSize.micro)
        }
        Text(text = "›", color = TextLow, fontSize = FontSize.title)
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md)
            .height(Sizes.hairline)
            .background(Line)
    )
}

// ---------------------------------------------------------------- 工具

private fun heartbeatText(intervalMs: Long): String = when {
    intervalMs <= 0L -> "—"
    intervalMs % 1000L == 0L -> "${intervalMs / 1000}s"
    else -> "${intervalMs}ms"
}

private fun clockText(epochMs: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
    return fmt.format(java.util.Date(epochMs))
}