package com.ajx.botforge.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ai.Memory
import com.ajx.botforge.data.Config
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.LoadingState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.RiskConfirmSheet
import com.ajx.botforge.ui.components.RiskConfirmSpec
import com.ajx.botforge.ui.components.RiskLevel
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.components.TextButton
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.LineHeight
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆管理页（任务书 3.3.4）。
 *
 * 三件事：列出全部本地会话记忆、展开预览最近对话、按会话或整体清理。
 *
 * ## 数据来源
 *
 * 会话记忆由 [Memory] 写在 `filesDir/mem/<key>.json`，key 规则在
 * `qq/MessageHandler.kt` 里拼：群聊 `${botId}_g_${groupOpenId}`，
 * 私聊 `${botId}_c_${userOpenId}`。
 *
 * [Memory] 目前**没有**「列出全部会话」的公开方法（`dir()` / `fileOf()` 都是 private），
 * 所以这里用 [scanSessions] 直接读同一个目录，只读、不改格式、不动 key 规则。
 * 这是一处临时读口，等存储层补上 `Memory.listSessions()` 后整段替换即可。
 * 清理动作则完全走 [Memory] 已有的 `clear(ctx, key)`，不写入也不搬移文件。
 *
 * ## 线程
 *
 * 目录扫描与 JSON 解析都在 Dispatchers.IO；主线程只做状态赋值，
 * 列表再多也不会在组合期读盘。
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val c = botColors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var sessions by remember { mutableStateOf<List<MemSession>>(emptyList()) }
    var expandedKey by remember { mutableStateOf("") }
    var previews by remember { mutableStateOf<Map<String, List<Memory.Turn>>>(emptyMap()) }
    var previewLoading by remember { mutableStateOf("") }
    var pendingOne by remember { mutableStateOf<MemSession?>(null) }
    var showClearAll by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadTick) {
        loading = true
        val loaded = withContext(Dispatchers.IO) { scanSessions(ctx) }
        sessions = loaded
        loading = false
    }

    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    val totalRounds = sessions.sumOf { it.rounds }
    val totalSize = sessions.sumOf { it.sizeBytes }
    val totalMessages = sessions.sumOf { it.messages }

    fun toggle(session: MemSession) {
        if (expandedKey == session.key) {
            expandedKey = ""
            return
        }
        expandedKey = session.key
        if (previews.containsKey(session.key)) return
        previewLoading = session.key
        scope.launch {
            val turns = withContext(Dispatchers.IO) {
                Memory.recent(ctx, session.key, PREVIEW_LIMIT)
            }
            previews = previews + (session.key to turns)
            previewLoading = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg0)
    ) {
        PageTopBar(
            title = Txt.TITLE_MEMORY,
            subtitle = Txt.MEM_SUBTITLE,
            onBack = onBack,
            actions = {
                SmallButton(text = Txt.MEM_REFRESH, onClick = { reloadTick++ })
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.md))

            if (loading) {
                LoadingState(message = Txt.LOADING)
            } else if (sessions.isEmpty()) {
                EmptyState(
                    title = Txt.MEM_EMPTY_TITLE,
                    description = Txt.MEM_EMPTY_DESC,
                    icon = "◍"
                )
            } else {
                SummaryCard(
                    sessions = sessions.size,
                    rounds = totalRounds,
                    messages = totalMessages,
                    sizeText = fmtSize(totalSize)
                )

                val grouped = sessions
                    .groupBy { it.botId }
                    .toList()
                    .sortedBy { (_, list) -> list.first().botName }

                for (entry in grouped) {
                    BotGroupSection(
                        botName = entry.value.first().botName,
                        sessions = entry.value,
                        expandedKey = expandedKey,
                        previews = previews,
                        previewLoading = previewLoading,
                        timeFmt = fmt,
                        onToggle = { toggle(it) },
                        onClear = { pendingOne = it }
                    )
                }

                Spacer(Modifier.height(Spacing.lg))
                TextButton(
                    text = Txt.MEM_CLEAR_ALL,
                    color = c.danger,
                    onClick = { showClearAll = true }
                )
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = Txt.MEM_LIMIT_HINT,
                    color = c.textLow,
                    fontSize = FontSize.caption,
                    lineHeight = LineHeight.normal
                )
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }

    pendingOne?.let { session ->
        RiskConfirmSheet(
            spec = RiskConfirmSpec(
                level = RiskLevel.L3,
                action = Txt.MEM_CLEAR_ONE,
                consequence = Txt.MEM_CLEAR_ONE_DESC,
                confirmWord = session.shortId,
                facts = listOf(
                    Txt.MEM_FACT_BOT + "：" + session.botName,
                    Txt.MEM_FACT_PEER + "：" + session.peerLabel,
                    String.format(
                        Locale.US,
                        Txt.MEM_FACT_LOST_FMT,
                        session.rounds,
                        session.messages,
                        fmtSize(session.sizeBytes)
                    )
                )
            ),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { Memory.clear(ctx, session.key) }
                    previews = previews - session.key
                    expandedKey = ""
                    pendingOne = null
                    reloadTick++
                }
            },
            onDismiss = { pendingOne = null }
        )
    }

    if (showClearAll) {
        RiskConfirmSheet(
            spec = RiskConfirmSpec(
                level = RiskLevel.L4,
                action = Txt.MEM_CLEAR_ALL,
                consequence = Txt.MEM_CLEAR_ALL_DESC,
                confirmWord = Txt.MEM_CLEAR_ALL_WORD,
                facts = listOf(
                    String.format(Locale.US, Txt.MEM_FACT_ALL_SESSIONS_FMT, sessions.size),
                    String.format(Locale.US, Txt.MEM_FACT_ALL_ROUNDS_FMT, totalRounds),
                    String.format(Locale.US, Txt.MEM_FACT_ALL_SIZE_FMT, fmtSize(totalSize)),
                    Txt.MEM_FACT_ALL_SCOPE
                )
            ),
            onConfirm = {
                val keys = sessions.map { it.key }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        keys.forEach { Memory.clear(ctx, it) }
                    }
                    previews = emptyMap()
                    expandedKey = ""
                    showClearAll = false
                    reloadTick++
                }
            },
            onDismiss = { showClearAll = false }
        )
    }
}

/** 概览：会话数、总轮数、总占用。 */
/**
 * 一个机器人下的全部会话区块。
 *
 * 为什么单独抽成一个 @Composable 函数，而不是就地写在 forEach 里：
 * 原先 SectionHeader / SessionCard / Spacer 直接写在 `grouped.forEach { }`
 * 这个内联 lambda 中，CI 在 Spacer 一行报
 * 「@Composable invocations can only happen from the context of a @Composable function」。
 * 内联 lambda 本应保留 composable 上下文，但把渲染逻辑塞进两层嵌套 lambda
 * 依赖的是编译器的上下文推断，而不是显式的函数契约 —— 一旦出错，
 * 报错点还会落在最不起眼的那个 Spacer 上，完全指不到根因。
 *
 * 另外这里用 for 循环而不是 forEach：报错正发生在 forEach 的 lambda 内部，
 * 说明该内联 lambda 没有被当成 composable 上下文。for 循环是普通语句，
 * 不涉及 lambda 推断，能绕开这个坑。
 *
 * 抽成函数后，参数表把「需要哪些状态、能改哪些状态」写死了：
 * 这里只读 expandedKey / previews / previewLoading，所有写操作
 * 都以 onToggle / onClear 回调交回上层。会话本身不可变，
 * 区块内部不再持有任何可变状态。
 */
@Composable
private fun BotGroupSection(
    botName: String,
    sessions: List<MemSession>,
    expandedKey: String,
    previews: Map<String, List<Memory.Turn>>,
    previewLoading: String,
    timeFmt: SimpleDateFormat,
    onToggle: (MemSession) -> Unit,
    onClear: (MemSession) -> Unit
) {
    SectionHeader(
        botName + " · " + String.format(Locale.US, Txt.MEM_BOT_COUNT_FMT, sessions.size)
    )
    for (session in sessions) {
        SessionCard(
            session = session,
            expanded = expandedKey == session.key,
            turns = previews[session.key].orEmpty(),
            previewLoading = previewLoading == session.key,
            timeText = if (session.lastTs > 0L) timeFmt.format(Date(session.lastTs)) else Txt.UNKNOWN,
            onToggle = { onToggle(session) },
            onClear = { onClear(session) }
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun SummaryCard(sessions: Int, rounds: Int, messages: Int, sizeText: String) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md)
    ) {
        Text(
            text = String.format(Locale.US, Txt.MEM_SUMMARY_FMT, sessions, rounds, sizeText),
            color = c.textHigh,
            fontSize = FontSize.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = String.format(Locale.US, Txt.MEM_SUMMARY_SUB_FMT, messages),
            color = c.textLow,
            fontSize = FontSize.caption
        )
    }
}

/** 单个会话：场景 + 轮数 + 大小 + 最后更新，可展开预览，可单会话清空。 */
@Composable
private fun SessionCard(
    session: MemSession,
    expanded: Boolean,
    turns: List<Memory.Turn>,
    previewLoading: Boolean,
    timeText: String,
    onToggle: () -> Unit,
    onClear: () -> Unit
) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                text = if (session.isGroup) Txt.MEM_SCENE_GROUP else Txt.MEM_SCENE_C2C,
                tone = if (session.isGroup) StatusTone.Info else StatusTone.Neutral
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = session.peerLabel,
                color = c.textHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            SmallButton(
                text = if (expanded) Txt.MEM_COLLAPSE else Txt.MEM_EXPAND,
                onClick = onToggle
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = String.format(
                    Locale.US,
                    Txt.MEM_ROW_META_FMT,
                    session.rounds,
                    session.messages,
                    fmtSize(session.sizeBytes)
                ),
                color = c.textMid,
                fontSize = FontSize.small,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = Txt.CLEAR,
                color = c.danger,
                onClick = onClear
            )
        }
        Text(
            text = String.format(Txt.MEM_LAST_UPDATED_FMT, timeText),
            color = c.textLow,
            fontSize = FontSize.caption
        )
        Text(
            text = Txt.MEM_KEY_LABEL + "：" + session.key,
            color = c.textLow,
            fontSize = FontSize.micro,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (expanded) {
            Spacer(Modifier.height(Spacing.sm))
            MemDivider()
            Spacer(Modifier.height(Spacing.sm))
            if (previewLoading) {
                Text(
                    text = Txt.LOADING,
                    color = c.textLow,
                    fontSize = FontSize.small
                )
            } else if (turns.isEmpty()) {
                Text(
                    text = Txt.MEM_PREVIEW_EMPTY,
                    color = c.textLow,
                    fontSize = FontSize.small
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bg3, RoundedCornerShape(Radius.sm))
                        .padding(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    for (turn in turns) {
                        TurnLine(turn)
                    }
                }
            }
        }
    }
}

/** 一条对话：角色 + 内容。内容过长时截断，避免单条撑满整页。 */
@Composable
private fun TurnLine(turn: Memory.Turn) {
    val c = botColors
    val isUser = turn.role == "user"
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = if (isUser) Txt.MEM_ROLE_USER else Txt.MEM_ROLE_BOT,
            color = if (isUser) c.brand else c.success,
            fontSize = FontSize.micro,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(44.dp)
        )
        Text(
            text = turn.content.replace("\n", " "),
            color = c.textMid,
            fontSize = FontSize.small,
            lineHeight = LineHeight.normal,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MemDivider() {
    val c = botColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.hairline)
            .background(c.line)
    )
}

/**
 * 会话元信息。`key` 是磁盘文件名去掉 `.json`，
 * 因为 [Memory] 的 `fileOf` 只做 `[^a-zA-Z0-9_-] -> _` 的替换，
 * 而 botId / openId 本身都是这几类字符，所以 key 与文件名一一对应。
 */
private data class MemSession(
    val key: String,
    val botId: String,
    val botName: String,
    val isGroup: Boolean,
    val peerOpenId: String,
    val peerLabel: String,
    val messages: Int,
    val rounds: Int,
    val sizeBytes: Long,
    val lastTs: Long
) {
    /** 群 / 用户 openid 尾 6 位，作为可输入、可辨认的短标识。 */
    val shortId: String get() = peerOpenId.takeLast(6)
}

/**
 * 扫描 `filesDir/mem` 得到全部会话。
 *
 * 只读：不改名、不搬移、不重写任何文件；轮数与时间直接读既有 JSON。
 * 读失败的文件跳过，不让单个坏文件拖垮整页。
 */
private fun scanSessions(ctx: Context): List<MemSession> {
    val dir = File(ctx.filesDir, "mem")
    if (!dir.isDirectory) return emptyList()
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
    if (files.isEmpty()) return emptyList()

    val bots = runCatching { Config.loadBots() }.getOrElse { mutableListOf() }
    val out = ArrayList<MemSession>(files.size)

    for (f in files) {
        val key = f.name.removeSuffix(".json")
        val c2cIdx = key.indexOf("_c_")
        val grpIdx = key.indexOf("_g_")
        val isGroup: Boolean
        val idx: Int
        if (grpIdx >= 0 && (c2cIdx < 0 || grpIdx < c2cIdx)) {
            isGroup = true
            idx = grpIdx
        } else if (c2cIdx >= 0) {
            isGroup = false
            idx = c2cIdx
        } else {
            continue
        }
        val botId = key.substring(0, idx)
        val peer = key.substring(idx + 3)
        if (botId.isEmpty() || peer.isEmpty()) continue

        val bot = bots.firstOrNull { it.id == botId }
        val botName = bot?.name?.takeIf { it.isNotBlank() } ?: botId
        val note = if (isGroup) {
            bot?.groups?.firstOrNull { it.groupOpenId == peer }?.note.orEmpty()
        } else {
            ""
        }
        val peerLabel = when {
            note.isNotBlank() -> note
            isGroup -> Txt.MEM_SCENE_GROUP + " " + peer.takeLast(6)
            else -> Txt.MEM_SCENE_C2C + " " + peer.takeLast(6)
        }

        val turns = runCatching { Memory.load(ctx, key) }.getOrElse { mutableListOf() }
        val messages = turns.size
        val rounds = (messages + 1) / 2
        val turnTs = turns.lastOrNull()?.ts ?: 0L
        val lastTs = maxOf(f.lastModified(), turnTs)

        out.add(
            MemSession(
                key = key,
                botId = botId,
                botName = botName,
                isGroup = isGroup,
                peerOpenId = peer,
                peerLabel = peerLabel,
                messages = messages,
                rounds = rounds,
                sizeBytes = f.length(),
                lastTs = lastTs
            )
        )
    }
    return out.sortedWith(
        compareBy<MemSession> { it.botName }.thenByDescending { it.lastTs }
    )
}

/** 字节数转人类可读文本。 */
private fun fmtSize(bytes: Long): String = when {
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** 展开时预览的条数。 */
private const val PREVIEW_LIMIT = 8