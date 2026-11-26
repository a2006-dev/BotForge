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
import com.ajx.botforge.ui.components.AppCard
import com.ajx.botforge.ui.components.ListRow
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.RowDivider
import com.ajx.botforge.ui.components.RowGroup
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusIndicator
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.data.DashboardStore
import com.ajx.botforge.ui.theme.Bg0
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg3
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.Danger
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import com.ajx.botforge.ui.theme.Warning

/**
 * 首页 —— 机器人运行管理中心
 *
 * 按任务书第一节整改：
 *   1. 重新梳理首页信息层级 —— 服务状态 / 真实在线 / 运行异常优先
 *   2. 增加最近运行事件和错误信息
 *   3. 所有指标来自真实业务数据，禁止虚构
 *   4. 使用 StateFlow 实现状态同步
 *   5. 明确区分服务运行状态与 WebSocket 连接状态
 *
 * 按任务书第二节要求，全部复用 components 包里的标准组件
 * （PageTopBar / AppCard / StatusIndicator / StatusBadge / RowGroup /
 *  ListRow / SectionHeader / RowDivider），不在这里另造一套。
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

    LaunchedEffect(Unit) {
        DashboardStore.install()
        DashboardStore.refreshStatic()
        permStatus = PermissionGuide.status(context)
    }

    // 任务书第一节要求「服务状态、在线数量、运行日志具备响应式更新能力」。
    // 三类数据现在都有推送源，不再需要 3 秒轮询：
    //   - 连接状态 / 在线数 → BotStatusRegistry 推送 → refreshBots()
    //   - 运行事件 / 日志数 → Logger.listener 推送 → onLogLine()
    //   - 服务存活 / 配置数 → 随日志与连接变化被动刷新，无需单独轮询
    // 仅保留一个低频兜底（15 秒），用于服务被系统杀掉但没有日志的场景，
    // 不作为数据更新的主路径。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            DashboardStore.refreshStatic()
            permStatus = PermissionGuide.status(context)
        }
    }

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
            // 品牌区：大字 + 一句话真实状态
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
                    text = summaryLine(
                        state.serviceRunning,
                        state.summary.loaded,
                        state.summary.online,
                        state.summary.hasAny
                    ),
                    color = TextMid,
                    fontSize = FontSize.small
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
            ) {
                // ---------- 服务状态 + 主操作 ----------
                Spacer(Modifier.height(Spacing.md))
                AppCard {
                    StatusIndicator(
                        text = if (state.serviceRunning) "前台服务运行中" else "前台服务已停止",
                        tone = if (state.serviceRunning) StatusTone.Success else StatusTone.Neutral
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = if (state.serviceRunning) {
                            "退出界面不会断开；熄屏后依赖保活配置"
                        } else {
                            "启动后建立 QQ 网关长连接"
                        },
                        color = TextLow,
                        fontSize = FontSize.micro
                    )
                    Spacer(Modifier.height(Spacing.md))
                    if (state.serviceRunning) {
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
                }

                // 权限未补齐提示（有缺才显示）
                val ps = permStatus
                if (ps != null && !ps.allGood) {
                    Spacer(Modifier.height(Spacing.sm))
                    AppCard(background = Bg3) {
                        Text(
                            text = "权限未补齐",
                            color = Warning,
                            fontSize = FontSize.small,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(
                            text = PermissionGuide.missingText(ps),
                            color = TextLow,
                            fontSize = FontSize.micro
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        SecondaryButton(
                            text = "立即补齐权限",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onRequestPermissions()
                                permStatus = PermissionGuide.status(context)
                            }
                        )
                    }
                }

                // ---------- 连接状态（真实 WebSocket 状态，不是配置数） ----------
                if (state.serviceRunning || state.bots.isNotEmpty()) {
                    SectionHeader("连接状态")
                    if (state.bots.isEmpty()) {
                        AppCard {
                            Text(
                                text = "等待机器人加载…",
                                color = TextLow,
                                fontSize = FontSize.small
                            )
                        }
                    } else {
                        state.bots.forEach { bot ->
                            BotCard(bot)
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                }

                // ---------- 运行异常（有才出现） ----------
                if (state.hasAbnormal) {
                    SectionHeader("运行异常")
                    state.abnormalBots.forEach { bot ->
                        AbnormalCard(bot)
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    if (state.abnormalBots.isEmpty() && state.summary.error > 0) {
                        AppCard {
                            Text(
                                text = "${state.summary.error} 个机器人处于异常状态，详见运行日志",
                                color = TextMid,
                                fontSize = FontSize.small
                            )
                        }
                    }
                }

                // ---------- 最近运行事件 ----------
                // 服务没在跑、也没有异常时，历史日志对用户没有决策价值，
                // 反而会把「数据 / 管理 / 运维」整块推到首屏之外。
                // 所以只有「正在运行」或「有异常」时才占用首屏空间。
                val showEvents = state.serviceRunning || state.hasAbnormal
                if (showEvents) {
                    SectionHeader("最近运行事件")
                    RowGroup {
                        if (state.events.isEmpty()) {
                            ListRow(title = "暂无运行记录", subtitle = "启动服务后这里会显示最近事件")
                        } else {
                            state.events.forEachIndexed { index, line ->
                                if (index > 0) RowDivider()
                                EventRow(line)
                            }
                            RowDivider()
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
                }

                // ---------- 数据 ----------
                // 未启动时这些数字只是配置存量，对「要不要启动」没有帮助，
                // 放在首屏只会挤压「管理 / 运维」入口。
                if (state.serviceRunning) {
                    SectionHeader("数据")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        StatTile("人设", "${state.personaCount}", "条", Modifier.weight(1f))
                        StatTile("模型", "${state.modelCount}", "个", Modifier.weight(1f))
                        StatTile("日志", "${state.logTotal}", "条", Modifier.weight(1f))
                    }
                }

                // ---------- 功能入口 ----------
                SectionHeader("管理")
                RowGroup {
                    ListRow(title = "机器人配置", subtitle = "AppID、密钥、群白名单", onClick = onOpenBots)
                    RowDivider()
                    ListRow(title = "人设", subtitle = "提示词、语气、记忆深度", onClick = onOpenPersonas)
                    RowDivider()
                    ListRow(title = "模型", subtitle = "接口地址、密钥、超时", onClick = onOpenModels)
                }

                SectionHeader("运维")
                RowGroup {
                    ListRow(title = "运行日志", subtitle = "${state.logTotal} 条记录", onClick = onOpenLogs)
                    RowDivider()
                    ListRow(title = "插件", subtitle = "查看已加载插件", onClick = onOpenPlugins)
                    RowDivider()
                    ListRow(title = "设置", subtitle = "全局开关与保活配置", onClick = onOpenSettings)
                }

                SectionHeader("关于")
                RowGroup {
                    ListRow(title = "关于 BotForge", subtitle = "版本与项目信息", onClick = onOpenAbout)
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
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                ) {
                    Text(text = msg, color = TextHigh, fontSize = FontSize.small)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 子组件

/** 品牌区副标题：一句话说清当前阶段，全部基于真实计数 */
private fun summaryLine(
    serviceRunning: Boolean,
    loaded: Int,
    online: Int,
    hasAny: Boolean
): String {
    if (!serviceRunning) return "前台服务未启动"
    if (loaded == 0) return "服务运行中 · 暂无启用机器人"
    if (!hasAny) return "服务运行中 · 等待连接"
    return "服务运行中 · $online/$loaded 在线"
}

private fun toneOf(state: BotConnState): Pair<StatusTone, String> = when (state) {
    BotConnState.CONNECTED -> StatusTone.Success to "在线"
    BotConnState.CONNECTING -> StatusTone.Warning to "连接中"
    BotConnState.AUTHENTICATING -> StatusTone.Warning to "鉴权中"
    BotConnState.RECONNECTING -> StatusTone.Warning to "重连中"
    BotConnState.ERROR -> StatusTone.Danger to "异常"
    BotConnState.STOPPED -> StatusTone.Neutral to "已停止"
}

/** 单机器人连接卡：状态 + 心跳 / ACK / 重连 + 代次与上线时刻 */
@Composable
private fun BotCard(bot: BotConnectionState) {
    val (tone, label) = toneOf(bot.state)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIndicator(
                text = bot.botName.ifEmpty { bot.botId },
                tone = tone,
                modifier = Modifier.weight(1f)
            )
            StatusBadge(text = label, tone = tone)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            MiniStat("心跳", heartbeatText(bot.heartbeatInterval))
            MiniStat("ACK", bot.ackLatencyText())
            MiniStat("重连", "${bot.reconnectCount}")
        }
        if (bot.generation > 0) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "第 ${bot.generation} 代连接" +
                        if (bot.connectedAt > 0) " · ${clockText(bot.connectedAt)} 上线" else "",
                color = TextLow,
                fontSize = FontSize.micro
            )
        }
    }
}

/** 异常卡：错误原因直接可见，不必点进日志 */
@Composable
private fun AbnormalCard(bot: BotConnectionState) {
    AppCard(bordered = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = bot.botName.ifEmpty { bot.botId },
                color = TextHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            StatusBadge(text = bot.state.label, tone = StatusTone.Danger)
        }
        val err = bot.lastError
        if (!err.isNullOrEmpty()) {
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = err,
                color = Danger,
                fontSize = FontSize.micro,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 事件行：时间 + 内容，级别决定颜色 */
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
private fun MiniStat(label: String, value: String) {
    Column {
        Text(text = label, color = TextLow, fontSize = FontSize.micro)
        Text(text = value, color = TextMid, fontSize = FontSize.small)
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
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

// ---------------------------------------------------------------- 工具

private fun heartbeatText(intervalMs: Long): String = when {
    intervalMs <= 0L -> "—"
    intervalMs % 1000L == 0L -> "${intervalMs / 1000}s"
    else -> "${intervalMs}ms"
}

private fun clockText(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(epochMs))
