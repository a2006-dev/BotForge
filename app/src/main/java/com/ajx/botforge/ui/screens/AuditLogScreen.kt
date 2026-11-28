package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajx.botforge.data.AuditStore
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.LoadingState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工具调用审计日志页。
 *
 * ## 为什么单独做一页，不复用 LogScreen
 *
 * LogScreen 读的是 [com.ajx.botforge.data.Logger] 的纯文本行，一行一条、语义全在字符串里。
 * 审计需要的是「按决策筛选」「看参数摘要」「区分成功失败」这些结构化操作，
 * 对文本行做正则会随着措辞调整而随时失效，所以这里直接消费 [AuditStore] 的 JSON 记录。
 *
 * ## 数据来源与不改数据层的原因
 *
 * [AuditStore] 只提供 `recent(limit): List<JSONObject>` 这一个读取口，没有按决策过滤的查询。
 * 按约束不动数据层，所以：先在 IO 线程把最近 N 条一次性拉出来，
 * 再在内存里做字段映射与筛选。这样切筛选条件不会重复读盘，也避开了
 * 「每改一次筛选就 parse 一遍几十 MB 文件」的卡顿。
 *
 * ## 线程
 *
 * 读盘 + JSON 解析 + 字段映射全部在 Dispatchers.IO；主线程只接结果、改状态。
 * 组合期不读盘，滚动时不会因为 IO 抖帧。
 */
@Composable
fun AuditLogScreen(onBack: () -> Unit) {
    val c = botColors

    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<AuditRow>>(emptyList()) }
    var filter by remember { mutableStateOf(AuditFilter.All) }
    var expandedId by remember { mutableStateOf("") }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadTick) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            // 只取最近一页：审计文件会到几十 MB，全量 parse 会卡住 UI。
            AuditStore.recent(LOAD_LIMIT)
                .mapIndexed { index, obj -> obj.toAuditRow(index) }
                .filter { it.isValid }
        }
        rows = loaded
        loading = false
    }

    // 全部计数用于筛选条上的数字，先算一遍避免每行重组时重复 filter。
    val allowCount = rows.count { it.decision == AuditStore.Decision.ALLOW }
    val askCount = rows.count { it.isAsk }
    val denyCount = rows.count { it.decision == AuditStore.Decision.BLOCKED }
    val shown = when (filter) {
        AuditFilter.All -> rows
        AuditFilter.Allow -> rows.filter { it.decision == AuditStore.Decision.ALLOW }
        AuditFilter.Ask -> rows.filter { it.isAsk }
        AuditFilter.Deny -> rows.filter { it.decision == AuditStore.Decision.BLOCKED }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg0)
    ) {
        PageTopBar(
            title = Txt.TITLE_AUDIT,
            subtitle = String.format(Locale.US, Txt.AUDIT_SUBTITLE_FMT, rows.size),
            onBack = onBack,
            actions = {
                SmallButton(
                    text = Txt.REFRESH,
                    onClick = { reloadTick++ }
                )
            }
        )

        // 筛选条：四个胶囊，当前项用品牌幽灵底强调。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AuditFilterChip(
                label = String.format(Locale.US, Txt.AUDIT_FILTER_ALL_FMT, allowCount + askCount + denyCount),
                selected = filter == AuditFilter.All
            ) { filter = AuditFilter.All }
            AuditFilterChip(
                label = String.format(Locale.US, Txt.AUDIT_FILTER_ALLOW_FMT, allowCount),
                selected = filter == AuditFilter.Allow
            ) { filter = AuditFilter.Allow }
            AuditFilterChip(
                label = String.format(Locale.US, Txt.AUDIT_FILTER_ASK_FMT, askCount),
                selected = filter == AuditFilter.Ask
            ) { filter = AuditFilter.Ask }
            AuditFilterChip(
                label = String.format(Locale.US, Txt.AUDIT_FILTER_DENY_FMT, denyCount),
                selected = filter == AuditFilter.Deny
            ) { filter = AuditFilter.Deny }
        }

        if (loading) {
            LoadingState(message = Txt.LOADING)
        } else if (shown.isEmpty()) {
            EmptyState(
                title = if (rows.isEmpty()) Txt.AUDIT_EMPTY_TITLE else Txt.AUDIT_EMPTY_FILTER_TITLE,
                description = if (rows.isEmpty()) Txt.AUDIT_EMPTY_DESC else Txt.AUDIT_EMPTY_FILTER_DESC,
                icon = "▦"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.md,
                    end = Spacing.md,
                    bottom = Spacing.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(shown) { row ->
                    AuditRecordCard(
                        row = row,
                        expanded = expandedId == row.id,
                        onToggle = {
                            // 单条展开：同一时刻只展开一条，避免长列表里连续展开后找不到位置。
                            expandedId = if (expandedId == row.id) "" else row.id
                        }
                    )
                }
            }
        }
    }
}

/**
 * 单条审计记录卡片。
 *
 * 默认只显示两行：头部（时间 + 工具名 + 决策徽标）与第二行（来源 + 结果）。
 * 展开后才多出参数摘要与失败原因，这样长列表里一屏能看到更多条，
 * 但想深究时又不用跳页。
 */
@Composable
private fun AuditRecordCard(
    row: AuditRow,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val c = botColors
    val tone = row.tone

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, if (expanded) tone.fg.copy(alpha = 0.4f) else c.line, RoundedCornerShape(Radius.lg))
            .clickable(onClick = onToggle)
            .padding(Spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 时间用等宽字体：上下对齐后能一眼看出密集发生的批量调用。
            Text(
                text = row.timeText,
                color = c.textLow,
                fontSize = FontSize.micro,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = row.toolOrCmd.ifBlank { Txt.AUDIT_UNKNOWN_TOOL },
                color = c.textHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            StatusBadge(text = row.decisionLabel, tone = tone)
        }

        Spacer(Modifier.height(Spacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = row.sourceText,
                color = c.textMid,
                fontSize = FontSize.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            // 成败与耗时成对展示：只看「成功」不看耗时，会漏掉卡死的慢调用。
            Text(
                text = String.format(Locale.US, Txt.AUDIT_COST_FMT, row.durationMs),
                color = c.textLow,
                fontSize = FontSize.caption,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = row.resultLabel,
                color = if (row.ok) c.success else c.danger,
                fontSize = FontSize.caption,
                fontWeight = FontWeight.Medium
            )
        }

        // 折叠态只给一行摘要，点击整卡展开。展开后摘要完整换行，并补出失败原因。
        if (row.argsDigest.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = row.argsDigest,
                color = c.textLow,
                fontSize = FontSize.caption,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
            )
        }

        if (expanded) {
            Spacer(Modifier.height(Spacing.sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizes.hairline)
                    .background(c.line)
            )
            Spacer(Modifier.height(Spacing.sm))

            AuditMetaLine(label = Txt.AUDIT_LABEL_SCENE, value = row.sceneText)
            AuditMetaLine(label = Txt.AUDIT_LABEL_USER, value = row.userText)
            AuditMetaLine(label = Txt.AUDIT_LABEL_PACKAGE, value = row.packageText)
            AuditMetaLine(label = Txt.AUDIT_LABEL_EXIT, value = row.exitText)

            if (!row.ok && row.error.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = row.error,
                    color = c.danger,
                    fontSize = FontSize.caption,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** 展开区的单行键值。标签固定宽度，值自动换行，避免长 openid 把行撑爆。 */
@Composable
private fun AuditMetaLine(label: String, value: String) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = c.textMid,
            fontSize = FontSize.caption,
            modifier = Modifier.width(META_LABEL_WIDTH)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = value,
            color = c.textHigh,
            fontSize = FontSize.caption,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 筛选胶囊。
 *
 * 不复用 StatusBadge：那个组件是「状态展示」，不可点；
 * 这里的胶囊需要可点、有选中态，语义不同，硬套会让徽标组件多出一堆交互参数。
 */
@Composable
private fun AuditFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val c = botColors
    Box(
        modifier = Modifier
            .background(
                if (selected) c.brandGhost else c.bg2,
                RoundedCornerShape(Radius.full)
            )
            .border(
                Sizes.hairline,
                if (selected) c.brand.copy(alpha = 0.5f) else c.line,
                RoundedCornerShape(Radius.full)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Text(
            text = label,
            color = if (selected) c.brand else c.textMid,
            fontSize = FontSize.caption,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// ---------------------------------------------------------------- 字段映射

/**
 * 把一条 JSON 审计记录拍平成 UI 可直接渲染的行。
 *
 * 为什么不直接拿 AuditStore.AuditRecord：
 * AuditStore 只有 `record()` / `trace()` 写入口和 `recent()` 读入口，
 * `recent()` 返回的是已 parse 的 JSONObject，没有反向的 `fromJson()`。
 * 按约束不改数据层，映射就放在页面内部。
 *
 * key 名必须与 [AuditStore.AuditRecord.toJson] 里的短名严格一致（ts/bot/scene/user/pkg/tool/args/decision/exit/ms/err）。
 * 一旦字段对不上，行会被 [AuditRow.isValid] 丢掉，而不是默默显示错值——
 * 审计页宁可少一条，不能报假数。
 */
private fun JSONObject.toAuditRow(index: Int): AuditRow {
    val ts = optLong("ts", 0L)
    val tool = optString("tool", "")
    val decisionName = optString("decision", "")
    val decision = parseDecision(decisionName)
    val exitCode = if (isNull("exit")) null else optInt("exit", 0)
    val err = if (isNull("err")) "" else optString("err", "")

    return AuditRow(
        // id 用「序号 + 时间 + 工具名」拼：同一毫秒内的并发调用也不会撞，
        // 且不依赖列表外任何字段，重载后仍稳定用于展开定位。
        id = index.toString() + "_" + ts + "_" + tool,
        timestamp = ts,
        botId = optString("bot", ""),
        scene = optString("scene", ""),
        userOpenId = if (isNull("user")) "" else optString("user", ""),
        packageId = if (isNull("pkg")) "" else optString("pkg", ""),
        toolOrCmd = tool,
        argsDigest = optString("args", ""),
        decision = decision,
        exitCode = exitCode,
        durationMs = optLong("ms", 0L),
        error = err,
        // 解析不出 decision 枚举时丢弃：未知决策既归不到放行，也不该冒充拒绝。
        isValid = decision != null
    )
}

/** 字符串转枚举。写了未知值（旧版或手改过的文件）时返回 null，不抛异常。 */
private fun parseDecision(raw: String): AuditStore.Decision? =
    AuditStore.Decision.values().firstOrNull { it.name == raw }

/** 一条待渲染的审计记录。字段全部拍平，卡片里不再碰 JSONObject。 */
private data class AuditRow(
    val id: String,
    val timestamp: Long,
    val botId: String,
    val scene: String,
    val userOpenId: String,
    val packageId: String,
    val toolOrCmd: String,
    val argsDigest: String,
    val decision: AuditStore.Decision?,
    val exitCode: Int?,
    val durationMs: Long,
    val error: String,
    val isValid: Boolean
) {
    /**
     * 行内时间。只到秒：审计看的是「先后顺序」与「集中在哪个时间窗」，
     * 毫秒位在这个密度下只会把行推得更长。
     */
    val timeText: String get() = AUDIT_TIME_FMT.format(Date(timestamp))

    /** 决策对应中文。null 走兼容分支，正常不应出现。 */
    val decisionLabel: String
        get() = when (decision) {
            AuditStore.Decision.ALLOW -> Txt.AUDIT_DECISION_ALLOW
            AuditStore.Decision.ASK_OK -> Txt.AUDIT_DECISION_ASK_OK
            AuditStore.Decision.ASK_DENY -> Txt.AUDIT_DECISION_ASK_DENY
            AuditStore.Decision.BLOCKED -> Txt.AUDIT_DECISION_BLOCKED
            null -> Txt.AUDIT_DECISION_UNKNOWN
        }

    /**
     * 决策到颜色。
     * ASK_OK / ASK_DENY 用警告色而非成功/错误色：
     * 「问了用户」这件事本身需要被注意到，用绿/红会混进「无需确认」和「被拦截」两类。
     */
    val tone: StatusTone
        get() = when (decision) {
            AuditStore.Decision.ALLOW -> StatusTone.Success
            AuditStore.Decision.ASK_OK -> StatusTone.Info
            AuditStore.Decision.ASK_DENY -> StatusTone.Warning
            AuditStore.Decision.BLOCKED -> StatusTone.Danger
            null -> StatusTone.Neutral
        }

    /** 是否走过「询问用户」链路。筛选「待确认」就靠它把 ASK_OK 与 ASK_DENY 一起收进来。 */
    val isAsk: Boolean
        get() = decision == AuditStore.Decision.ASK_OK || decision == AuditStore.Decision.ASK_DENY

    /**
     * 是否算成功。
     * 没执行（BLOCKED / ASK_DENY）一律不算成功；
     * 有 error 或有非零退出码也不算——只看决策会把「放行了但命令报错」误报成成功。
     */
    val ok: Boolean
        get() = decision != AuditStore.Decision.BLOCKED &&
            decision != AuditStore.Decision.ASK_DENY &&
            error.isBlank() &&
            (exitCode == null || exitCode == 0)

    /** 成败文案。未执行与执行失败语义不同，分开说。 */
    val resultLabel: String
        get() = when {
            decision == AuditStore.Decision.BLOCKED -> Txt.AUDIT_RESULT_BLOCKED
            decision == AuditStore.Decision.ASK_DENY -> Txt.AUDIT_RESULT_DENIED
            ok -> Txt.AUDIT_RESULT_SUCCESS
            else -> Txt.AUDIT_RESULT_FAILED
        }

    /**
     * 调用来源。优先展示「哪个机器人」，再带场景。
     * openid 截尾 6 位：完整 openid 常在 32 位以上，一行放不下，
     * 且审计排查只需能区分不同对象，不需要完整值（完整值在展开区）。
     */
    val sourceText: String
        get() {
            val bot = botId.ifBlank { Txt.AUDIT_UNKNOWN_BOT }
            val tail = userOpenId.takeLast(6)
            return if (tail.isBlank()) {
                bot
            } else {
                String.format(Locale.US, Txt.AUDIT_SOURCE_FMT, bot, sceneText, tail)
            }
        }

    /** 场景原始值。c2c / group，空则显示未知。 */
    val sceneText: String get() = scene.ifBlank { Txt.UNKNOWN }

    /** 未带发起人时显式说明，而不是留白。系统自发动作本来就没有发起人。 */
    val userText: String get() = userOpenId.ifBlank { Txt.AUDIT_NO_USER }

    val packageText: String get() = packageId.ifBlank { Txt.AUDIT_BUILTIN_PACKAGE }

    /** 退出码。null 表示未执行或非进程类工具，不应显示为 0。 */
    val exitText: String get() = exitCode?.toString() ?: Txt.AUDIT_NO_EXIT
}

/** 筛选档位。刻意不用 AuditStore.Decision：一个档位对应两个枚举值（待确认）。 */
private enum class AuditFilter { All, Allow, Ask, Deny }

/** 一次拉多少条。与 AuditStore.recent 的默认值拉开一点，但仍有上限。 */
private const val LOAD_LIMIT = 300

/** 展开区标签列宽。固定宽让多行键值左侧对齐，值区不会被推来推去。 */
private val META_LABEL_WIDTH = 64.dp

/** 行内时间格式。复用同一实例即可：只在主线程渲染时调用，无并发风险。 */
private val AUDIT_TIME_FMT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
