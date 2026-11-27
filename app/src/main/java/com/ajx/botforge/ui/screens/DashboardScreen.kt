package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajx.botforge.data.AuthStore
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.ui.components.AttentionCard
import com.ajx.botforge.ui.components.AttentionIssue
import com.ajx.botforge.ui.components.HeroMetric
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusHero
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.components.TopBarMoreAction
import com.ajx.botforge.ui.data.DashboardStore
import com.ajx.botforge.ui.data.Onboarding
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页。
 *
 * 结构自上而下：
 *  1. 顶栏（品牌 + 更多入口）
 *  2. 状态 Hero（一句话说清「现在什么状态」+ 三个指标）
 *  3. 需要关注（只有真有问题时才出现，没问题就不占位）
 *  4. 主操作（启动 / 停止服务，一个页面只允许一个主按钮）
 *  5. 机器人卡片列表
 *  6. 最近事件
 *
 * 首次引导：当「没有任何机器人 且 从未完成引导」时，整页换成引导内容。
 * 引导一旦完成就写 onboarded 标记，即使之后删光机器人也不会再弹。
 */
@Composable
fun DashboardScreen(
    onOpenBots: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPermissions: () -> Unit,
    onToggleService: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val c = botColors
    val state by DashboardStore.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 首页是 DashboardStore 的唯一安装点。
    // 用 DisposableEffect 而不是 LaunchedEffect：卸载时要摘掉监听器，
    // 否则后台服务的日志推送会一直往一个已经销毁的 Store 里写。
    DisposableEffect(Unit) {
        DashboardStore.install()
        onDispose { /* 保留 install 状态：离开首页不代表不要数据，其他 Tab 也在读 */ }
    }

    // 静态数据（机器人数、模型数、日志条数）每次进入刷新一次。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { DashboardStore.refreshStatic() }
    }

    // 时长需要每秒走字，否则「运行 3 分钟」会一直卡在初始值。
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    // 每 30 个 tick（30 秒）兜底刷一次连接快照，
    // 防止极端情况下推送丢失导致首页定格。
    LaunchedEffect(tick) {
        if (tick > 0 && tick % 30 == 0) DashboardStore.refreshBots()
    }

    val showOnboarding = remember { Onboarding.shouldShow(com.ajx.botforge.App.instance) }

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(
            title = "BotForge",
            subtitle = "机器人托管控制台",
            showBack = false,
            actions = {
                TopBarMoreAction(onClick = onOpenAbout)
            }
        )

        if (showOnboarding) {
            OnboardingContent(
                onOpenBots = onOpenBots,
                onOpenModels = onOpenModels,
                onRequestPermissions = onRequestPermissions,
                onDone = onOpenBots
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.lg, end = Spacing.lg,
                top = Spacing.lg, bottom = Spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            // ---------- 状态 Hero ----------
            item {
                StatusHero(
                    status = state.status,
                    subtitle = summaryLine(state),
                    metrics = heroMetrics(state, tick)
                )
            }

            // ---------- 需要关注 ----------
            val issues = attentionIssues(state)
            if (issues.isNotEmpty()) {
                item {
                    AttentionCard(
                        issues = issues,
                        onIssueClick = { issue ->
                            when (issue.route) {
                                "permission" -> onOpenPermissions()
                                "logs" -> onOpenLogs()
                                "bots" -> onOpenBots()
                                else -> onOpenSettings()
                            }
                        }
                    )
                }
            }

            // ---------- 主操作 ----------
            item {
                val running = state.serviceRunning
                PrimaryButton(
                    text = if (running) Txt.DASH_ACTION_STOP else Txt.DASH_ACTION_START,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onToggleService
                )
            }

            // ---------- 机器人卡片 ----------
            item {
                SectionHeader("机器人 · ${state.bots.size}/${state.summary.loaded}")
            }
            if (state.bots.isEmpty()) {
                item {
                    EmptyBotCard(
                        onAdd = onOpenBots,
                        onPermissions = onRequestPermissions
                    )
                }
            } else {
                items(state.bots, key = { it.botId }) { bot ->
                    val cfg = remember(state.bots, tick) {
                        Config.loadBots().firstOrNull { it.id == bot.botId }
                    }
                    BotCard(
                        name = bot.botName.ifEmpty { cfg?.name ?: "未命名" },
                        appId = cfg?.appId.orEmpty(),
                        tone = when {
                            bot.state.name == "ONLINE" -> StatusTone.Success
                            bot.state.name == "CONNECTING" || bot.state.name == "RECONNECTING" ->
                                StatusTone.Warning
                            bot.lastError != null -> StatusTone.Danger
                            else -> StatusTone.Neutral
                        },
                        statusText = botStateText(bot.state.name),
                        heartbeatText = heartbeatText(bot.heartbeatInterval, bot.ackLatency),
                        uptimeText = uptimeText(bot.connectedAt, tick),
                        reconnectCount = bot.reconnectCount,
                        lastError = bot.lastError,
                        onClick = onOpenBots
                    )
                }
            }

            // ---------- 授权状态 ----------
            item {
                val hasOwner = remember(tick) { AuthStore.hasOwner }
                val admins = remember(tick) { AuthStore.admins() }
                AuthSummaryCard(
                    hasOwner = hasOwner,
                    ownerMasked = if (hasOwner) AuthStore.mask(AuthStore.ownerOpenId) else "",
                    adminCount = admins.size,
                    onClick = onOpenPermissions
                )
            }

            // ---------- 最近事件 ----------
            item {
                SectionHeader(Txt.DASH_SECTION_EVENTS)
            }
            if (state.events.isEmpty()) {
                item {
                    Text(
                        text = "暂无事件。服务运行后这里会滚动显示最新日志。",
                        color = c.textLow,
                        fontSize = FontSize.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.bg2, RoundedCornerShape(Radius.lg))
                            .padding(Spacing.lg)
                    )
                }
            } else {
                items(state.events, key = { it.time + it.level + it.text }) { line ->
                    EventRow(line = line, onClick = onOpenLogs)
                }
            }

            // ---------- 快捷入口 ----------
            item {
                SectionHeader(Txt.MORE)
                QuickLinks(
                    onOpenPersonas = onOpenPersonas,
                    onOpenModels = onOpenModels,
                    onOpenPlugins = onOpenPlugins,
                    onOpenTerminal = onOpenTerminal,
                    onOpenTools = onOpenTools,
                    onOpenPermissions = onOpenPermissions,
                    onOpenAbout = onOpenAbout,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 引导

/**
 * 首次引导。
 *
 * 只讲三件事，不写长文：
 *  1. 你得有个 QQ 机器人（去开放平台建一个，拿 AppID/AppSecret）
 *  2. 你得配个模型（AI 才有脑子）
 *  3. 权限得给全（否则后台被杀）
 *
 * 引导不强制：右上角永远有「跳过」，跳过等同完成 —— 反复弹引导是最招人烦的设计。
 */
@Composable
private fun OnboardingContent(
    onOpenBots: () -> Unit,
    onOpenModels: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDone: () -> Unit
) {
    val c = botColors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.lg, end = Spacing.lg,
            top = Spacing.xl, bottom = Spacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        item {
            Text(
                text = Txt.ONBOARD_TITLE,
                color = c.textHigh,
                fontSize = FontSize.display,
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            Text(
                text = Txt.ONBOARD_DESC,
                color = c.textMid,
                fontSize = FontSize.body,
                lineHeight = com.ajx.botforge.ui.theme.LineHeight.relaxed
            )
        }
        item {
            GuideStep(
                index = 1,
                title = Txt.ONBOARD_STEP1_TITLE,
                body = Txt.ONBOARD_STEP1_DESC,
                actionText = Txt.ONBOARD_STEP1_ACTION,
                onClick = onOpenBots
            )
        }
        item {
            GuideStep(
                index = 2,
                title = Txt.ONBOARD_STEP2_TITLE,
                body = Txt.ONBOARD_STEP2_DESC,
                actionText = Txt.ONBOARD_STEP2_ACTION,
                onClick = onOpenModels
            )
        }
        item {
            GuideStep(
                index = 3,
                title = Txt.ONBOARD_STEP3_TITLE,
                body = Txt.ONBOARD_STEP3_DESC,
                actionText = "一键检查权限",
                onClick = onRequestPermissions
            )
        }
        item {
            SecondaryButton(
                text = "跳过引导，直接进首页",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    Onboarding.markDone()
                    onDone()
                }
            )
        }
    }
}

@Composable
private fun GuideStep(
    index: Int,
    title: String,
    body: String,
    actionText: String,
    onClick: () -> Unit
) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
            .padding(Spacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(c.brandGhost, RoundedCornerShape(Radius.full)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index.toString(),
                    color = c.brand,
                    fontSize = FontSize.small,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = title,
                color = c.textHigh,
                fontSize = FontSize.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            color = c.textMid,
            fontSize = FontSize.small,
            lineHeight = com.ajx.botforge.ui.theme.LineHeight.normal
        )
        Spacer(Modifier.height(Spacing.md))
        SmallButton(text = actionText, primary = true, onClick = onClick)
    }
}

// ---------------------------------------------------------------- 卡片

@Composable
private fun EmptyBotCard(onAdd: () -> Unit, onPermissions: () -> Unit) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "○", color = c.textLow, fontSize = FontSize.display)
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "还没有机器人",
            color = c.textHigh,
            fontSize = FontSize.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = Txt.DASH_EMPTY_BOT_DESC,
            color = c.textMid,
            fontSize = FontSize.small,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SmallButton(text = Txt.DASH_EMPTY_BOT_ACTION, primary = true, onClick = onAdd)
            SmallButton(text = Txt.DASH_EMPTY_BOT_PERM, onClick = onPermissions)
        }
    }
}

@Composable
private fun BotCard(
    name: String,
    appId: String,
    tone: StatusTone,
    statusText: String,
    heartbeatText: String,
    uptimeText: String,
    reconnectCount: Int,
    lastError: String?,
    onClick: () -> Unit
) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
            .clickable(onClick = onClick)
            .padding(Spacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                color = c.textHigh,
                fontSize = FontSize.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            StatusBadge(text = statusText, tone = tone)
        }
        if (appId.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = String.format(Txt.BOT_APPID_FMT, appId),
                color = c.textLow,
                fontSize = FontSize.caption
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            MiniStat("心跳", heartbeatText)
            MiniStat(Txt.BOT_STATE_ONLINE, uptimeText)
            MiniStat("重连", "${reconnectCount} 次")
        }
        if (!lastError.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = lastError,
                color = c.danger,
                fontSize = FontSize.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    val c = botColors
    Column {
        Text(text = label, color = c.textLow, fontSize = FontSize.caption)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = c.textMid,
            fontSize = FontSize.small,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 授权状态卡片。
 *
 * 放在首页而不是只塞进设置页，是因为「有没有主人」直接决定机器人会不会
 * 被不认识的人用 `/绑定主人` 抢走 —— 这是需要用户第一眼看到的安全状态。
 */
@Composable
private fun AuthSummaryCard(
    hasOwner: Boolean,
    ownerMasked: String,
    adminCount: Int,
    onClick: () -> Unit
) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hasOwner) c.bg2 else c.warningGhost, RoundedCornerShape(Radius.lg))
            .border(
                Sizes.hairline,
                if (hasOwner) c.line else c.warning.copy(alpha = 0.3f),
                RoundedCornerShape(Radius.lg)
            )
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (hasOwner) Txt.AUTH_OWNER_BOUND else Txt.AUTH_OWNER_NONE,
                color = if (hasOwner) c.textHigh else c.warning,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (hasOwner) String.format(Txt.AUTH_OWNER_SUMMARY, "$ownerMasked", adminCount)
                else Txt.AUTH_OWNER_NONE_HINT,
                color = c.textLow,
                fontSize = FontSize.caption
            )
        }
        Text(text = "›", color = c.textLow, fontSize = FontSize.title)
    }
}

@Composable
private fun EventRow(line: Logger.Line, onClick: () -> Unit) {
    val c = botColors
    val tone = when (line.level) {
        Logger.LEVEL_ERROR -> c.danger
        Logger.LEVEL_WARN -> c.warning
        else -> c.textLow
    }
    val levelMark = when (line.level) {
        Logger.LEVEL_ERROR -> "E"
        Logger.LEVEL_WARN -> "W"
        else -> "I"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = line.time,
            color = c.textLow,
            fontSize = FontSize.micro,
            modifier = Modifier.width(56.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = "[$levelMark]",
            color = tone,
            fontSize = FontSize.micro,
            modifier = Modifier.width(56.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = line.text,
            color = c.textMid,
            fontSize = FontSize.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickLinks(
    onOpenPersonas: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
    ) {
        QuickRow(Txt.LINK_PERSONA, Txt.LINK_PERSONA_DESC, onOpenPersonas)
        QuickDivider()
        QuickRow(Txt.LINK_MODEL, Txt.LINK_MODEL_DESC, onOpenModels)
        QuickDivider()
        QuickRow(Txt.LINK_PLUGIN, Txt.LINK_PLUGIN_DESC, onOpenPlugins)
        QuickDivider()
        QuickRow(Txt.LINK_TERMINAL, Txt.LINK_TERMINAL_DESC, onOpenTerminal)
        QuickDivider()
        QuickRow(Txt.LINK_TOOLS, Txt.LINK_TOOLS_DESC, onOpenTools)
        QuickDivider()
        QuickRow(Txt.LINK_PERMISSIONS, Txt.LINK_PERMISSIONS_DESC, onOpenPermissions)
        QuickDivider()
        QuickRow(Txt.LINK_ABOUT, Txt.LINK_ABOUT_DESC, onOpenAbout)
        QuickDivider()
        QuickRow(Txt.LINK_SETTINGS, Txt.LINK_SETTINGS_DESC, onOpenSettings)
    }
}

@Composable
private fun QuickRow(title: String, subtitle: String, onClick: () -> Unit) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = c.textHigh, fontSize = FontSize.body)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = c.textLow, fontSize = FontSize.caption)
        }
        Text(text = "›", color = c.textLow, fontSize = FontSize.title)
    }
}

@Composable
private fun QuickDivider() {
    val c = botColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg)
            .height(Sizes.hairline)
            .background(c.line)
    )
}

// ---------------------------------------------------------------- 文案

private fun summaryLine(state: com.ajx.botforge.ui.data.DashboardState): String {
    val s = state.summary
    return when {
        !state.serviceRunning -> "服务未运行，点下方按钮启动"
        s.loaded == 0 -> "服务在跑，但还没添加机器人"
        s.online == 0 -> "${s.loaded} 个机器人，正在连接"
        s.online == s.loaded -> "${s.online} 个机器人在线"
        else -> "${s.online}/${s.loaded} 在线，${s.loaded - s.online} 个未连接"
    }
}

private fun heroMetrics(
    state: com.ajx.botforge.ui.data.DashboardState,
    tick: Int
): List<HeroMetric> {
    val list = mutableListOf<HeroMetric>()
    val uptime = state.uptimeMillis
    list += HeroMetric(Txt.DASH_METRIC_UPTIME, if (uptime > 0) uptimeText(0, 0, uptime) else "—")
    val last = state.lastEventTime
    list += HeroMetric(Txt.DASH_SECTION_EVENTS, last ?: "—")
    list += HeroMetric("断线", "${state.totalReconnects} 次")
    return list
}

private fun attentionIssues(
    state: com.ajx.botforge.ui.data.DashboardState
): List<AttentionIssue> {
    val out = mutableListOf<AttentionIssue>()
    val abnormal = state.abnormalBots
    if (abnormal.isNotEmpty()) {
        out += AttentionIssue(
            text = "${abnormal.size} 个机器人连接异常：" +
                abnormal.joinToString("、") { it.botName.ifEmpty { it.botId } },
            route = "logs"
        )
    }
    if (state.summary.error > 0) {
        out += AttentionIssue(
            text = "最近有 ${state.summary.error} 条错误日志",
            route = "logs"
        )
    }
    if (!state.serviceRunning && state.summary.loaded > 0) {
        out += AttentionIssue(
            text = "已配置 ${state.summary.loaded} 个机器人但服务未启动",
            route = "bots"
        )
    }
    return out
}

private fun botStateText(name: String): String = when (name) {
    "ONLINE" -> Txt.BOT_STATE_ONLINE
    "CONNECTING" -> Txt.BOT_STATE_CONNECTING
    "RECONNECTING" -> Txt.BOT_STATE_RECONNECTING
    "STOPPED" -> Txt.BOT_STATE_STOPPED
    "ERROR" -> Txt.BOT_STATE_ERROR
    else -> name
}

private fun heartbeatText(intervalMs: Long, ackLatency: Long): String {
    val sb = StringBuilder()
    if (intervalMs > 0) sb.append("${intervalMs / 1000}s")
    if (ackLatency >= 0) {
        if (sb.isNotEmpty()) sb.append(" · ")
        sb.append(if (ackLatency < 1000) "${ackLatency}ms" else "${ackLatency / 1000}s")
    }
    return if (sb.isEmpty()) "—" else sb.toString()
}

/** 运行时长，形如「3 小时 12 分」。tick 只用于触发重组。 */
private fun uptimeText(connectedAt: Long, tick: Int, override: Long = 0L): String {
    val ms = if (override > 0) override else {
        if (connectedAt <= 0L) 0L else System.currentTimeMillis() - connectedAt
    }
    if (ms <= 0) return "—"
    val sec = ms / 1000
    val d = sec / 86400
    val h = (sec % 86400) / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        d > 0 -> "${d} 天 ${h} 小时"
        h > 0 -> "${h} 小时 ${m} 分"
        m > 0 -> "${m} 分 ${s} 秒"
        else -> "${s} 秒"
    }
}

internal fun clockText(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))