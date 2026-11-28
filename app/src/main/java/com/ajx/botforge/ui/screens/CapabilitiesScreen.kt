package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import com.ajx.botforge.ai.tool.BuiltinTools
import com.ajx.botforge.ai.tool.PackageRegistry
import com.ajx.botforge.ai.tool.ToolPermission
import com.ajx.botforge.ui.components.AppCard
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.ListRow
import com.ajx.botforge.ui.components.LoadingState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.RowDivider
import com.ajx.botforge.ui.components.RowGroup
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.LineHeight
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 能力清单页（只读）。
 *
 * ## 这一页回答什么
 *
 * 一个问题：「这个机器人现在到底能干什么。」
 *
 * 答案由三件事实拼成，缺一件就说不清：
 *  1. 有哪些包、包开没开——包关了，包里的工具模型根本看不见；
 *  2. 包里有哪些工具、各自叫什么、干什么用——这是「能力」本身；
 *  3. 每个工具当前生效的权限档（ALLOW / ASK / DENY）——这是「能不能用」。
 *
 * 顶部只放一行总览：已启用 N 个包 / 共 M 个工具 / 其中 K 个需要确认。
 * 这三个数字是用户最先想知道的，也是唯一值得放在首屏的聚合值。
 *
 * ## 为什么不做成可编辑
 *
 * 编辑能力在 [ToolPackageScreen]（设置 → 工具包与权限）。两页分工必须清楚：
 *  - 本页是「事实陈述」：只读、无开关、点任何地方都不会改变机器人行为，
 *    因此可以放心地把它当成一个随时能打开的说明书；
 *  - [ToolPackageScreen] 是「决策入口」：有开关、有循环切档、改完立即生效。
 *
 * 如果本页也放开关，两页会变成同一页的两个副本，改文案时必然漂移，
 * 而且会让「看一眼现在有什么」这个动作带上误触风险。
 *
 * ## 数据来源与线程
 *
 * 读的全部是既有只读 API：[BuiltinTools.ensureRegistered]、[PackageRegistry.all]、
 * [PackageRegistry.isEnabled]、[PackageRegistry.permissionOf]、[PackageRegistry.ALWAYS_ON]。
 * 不新增、不修改这三个文件的任何 API。
 *
 * 这些调用背后有 SharedPreferences 读盘（包开关、单工具权限覆盖），
 * 所以整段读取放在 Dispatchers.IO 里，主线程只接收算好的快照；
 * 组合期不碰盘，列表再长也不会掉帧。
 */
@Composable
fun CapabilitiesScreen(onBack: () -> Unit) {
    val c = botColors
    var loading by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf<List<CapGroup>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    // tick 只做「重新读一遍」：PackagePrefs 背后是 SharedPreferences，
    // 用户在别的页改了开关再回来时，靠手动刷新拿到最新事实，不做跨页状态同步。
    LaunchedEffect(tick) {
        loading = true
        groups = withContext(Dispatchers.IO) { loadCapabilities() }
        loading = false
    }

    val enabledPkgCount = groups.count { it.enabled }
    val toolCount = groups.sumOf { it.tools.size }
    val askCount = groups.sumOf { group -> group.tools.count { it.permission == ToolPermission.ASK } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg0)
    ) {
        PageTopBar(
            title = Txt.TITLE_CAPABILITIES,
            subtitle = Txt.CAP_SUBTITLE,
            onBack = onBack,
            actions = {
                SmallButton(text = Txt.REFRESH, onClick = { tick++ })
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
                // 首帧一定在读盘，先给加载态，避免闪一个空态再跳成列表。
                LoadingState(message = Txt.LOADING)
            } else if (groups.isEmpty()) {
                // 注册表为空只可能是「内置包没挂上」。不编造原因，只给可执行的一句。
                EmptyState(
                    title = Txt.CAP_EMPTY_TITLE,
                    description = Txt.CAP_EMPTY_DESC,
                    icon = "◎"
                )
            } else {
                AppCard {
                    Text(
                        text = String.format(
                            Locale.US,
                            Txt.CAP_OVERVIEW_FMT,
                            enabledPkgCount,
                            toolCount,
                            askCount
                        ),
                        color = c.textHigh,
                        fontSize = FontSize.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = Txt.CAP_OVERVIEW_SUB,
                        color = c.textLow,
                        fontSize = FontSize.caption,
                        lineHeight = LineHeight.normal
                    )
                }

                Spacer(Modifier.height(Spacing.sm))

                for (group in groups) {
                    CapabilityGroupBlock(group = group)
                }

                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = Txt.CAP_ASK_NOTE,
                    color = c.textLow,
                    fontSize = FontSize.caption,
                    lineHeight = LineHeight.normal
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = Txt.CAP_READONLY_NOTE,
                    color = c.textLow,
                    fontSize = FontSize.caption,
                    lineHeight = LineHeight.normal
                )
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }
}

/**
 * 包级事实行：一句包说明 + 启用状态 + 工具数。
 *
 * 不显示包 id：用户认的是「系统控制」这种名字，id 只在排障时有意义，
 * 而排障看日志比看这里准。
 */
/**
 * 一个包（能力分组）的整块渲染：标题 + 包信息 + 逐个工具 + 可选脚注。
 *
 * 为什么抽成独立 @Composable 函数、内部用 for 而不是 forEach：
 *
 * 原先这段写成 `groups.forEach { group -> RowGroup { tools.forEachIndexed { ... } } }`，
 * 是三层嵌套的内联 lambda，最里层在调 ToolRow / RowDivider 这些 Composable。
 * 同类写法在 MemoryScreen 上已经被 CI 抓到过：
 * 「@Composable invocations can only happen from the context of a @Composable function」。
 * inline lambda 本应保留 composable 上下文，但嵌套到两三层的实际表现是
 * 推断失败，而报错点还会落在最不起眼的那一行上。
 *
 * for 循环是普通语句，不参与 lambda 类型推断，从根本上避开这个问题。
 * 抽成函数之后，参数表也把契约写死了：这里只读 group，不持有任何可变状态。
 */
@Composable
private fun CapabilityGroupBlock(group: CapGroup) {
    SectionHeader(group.name)
    RowGroup {
        PackageFactRow(group)
        if (group.tools.isNotEmpty()) {
            RowDivider()
            // 工具逐条列出，不折叠：本页的用途就是「一次看全」，
            // 需要展开才能看到的东西等于没看到。
            for (index in group.tools.indices) {
                if (index > 0) RowDivider()
                ToolRow(group.tools[index])
            }
        }
        val note = groupFooterNote(group)
        if (note != null) {
            RowDivider()
            GroupNote(note)
        }
    }
    Spacer(Modifier.height(Spacing.md))
}

@Composable
private fun PackageFactRow(group: CapGroup) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.description,
                color = c.textMid,
                fontSize = FontSize.small,
                lineHeight = LineHeight.normal
            )
            if (group.locked) {
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = Txt.CAP_LOCKED,
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(horizontalAlignment = Alignment.End) {
            StatusBadge(
                text = if (group.enabled) Txt.ENABLED else Txt.DISABLED,
                tone = if (group.enabled) StatusTone.Success else StatusTone.Neutral
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = String.format(Locale.US, Txt.CAP_TOOL_COUNT_FMT, group.tools.size),
                color = c.textLow,
                fontSize = FontSize.caption
            )
        }
    }
}

/**
 * 单工具一行：名称 + 说明 + 当前权限档。
 *
 * 说明优先用包声明里的 note 字段——那一句是专门写给用户看的；
 * note 为空时退回工具自己的 description（写给模型看的那段），
 * 这样任何工具都不会出现「有名字但没有解释」的空行。
 *
 * 权限档用 [StatusBadge] 而不是自绘胶囊：全站状态语义只有这一个出口，
 * 三档颜色与 MemoryScreen、ToolScreen 的徽标天然一致。
 */
@Composable
private fun ToolRow(tool: CapTool) {
    val label = when (tool.permission) {
        ToolPermission.ALLOW -> Txt.CAP_PERM_ALLOW
        ToolPermission.ASK -> Txt.CAP_PERM_ASK
        ToolPermission.DENY -> Txt.CAP_PERM_DENY
    }
    val tone = when (tool.permission) {
        ToolPermission.ALLOW -> StatusTone.Success
        ToolPermission.ASK -> StatusTone.Warning
        ToolPermission.DENY -> StatusTone.Danger
    }
    ListRow(
        title = tool.name,
        subtitle = tool.note,
        trailing = { StatusBadge(text = label, tone = tone) }
    )
}

/** 包尾注：解释「这一包的工具现在能不能被模型拿到」。 */
@Composable
private fun GroupNote(text: String) {
    val c = botColors
    Text(
        text = text,
        color = c.textLow,
        fontSize = FontSize.caption,
        lineHeight = LineHeight.normal,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
    )
}

/**
 * 包尾注文案。
 *
 * 只有两种情况需要解释，其余返回 null（不显示空行）：
 *  - 整包停用：包里每个工具的权限档此时都不生效，容易误读成「还能用」；
 *  - 包启用但有工具被 DENY：这些工具数量少，不点名指出会被忽略。
 */
private fun groupFooterNote(group: CapGroup): String? {
    if (!group.enabled) {
        return String.format(Locale.US, Txt.CAP_PKG_OFF_NOTE_FMT, group.tools.size)
    }
    val denied = group.tools.count { it.permission == ToolPermission.DENY }
    return if (denied > 0) String.format(Locale.US, Txt.CAP_DENY_NOTE_FMT, denied) else null
}

/**
 * 读一次能力快照。**必须在 IO 线程调用**：内部会读 SharedPreferences。
 *
 * 顺序说明：先 ensureRegistered 再读注册表。正常流程 App.onCreate 已经注册过，
 * 这一步只覆盖「清数据后直接进本页」——否则注册表为空，页面会误报「没有工具包」。
 * ensureRegistered 是幂等的且带锁，重复调用无副作用。
 *
 * 快照是纯数据（[CapGroup] / [CapTool]），不含 Tool 实例：
 * activeTools() 返回的 ASK 档工具是守卫包装对象，与本页要陈述的「声明事实」不是一回事。
 */
private fun loadCapabilities(): List<CapGroup> {
    BuiltinTools.ensureRegistered()

    return PackageRegistry.all().map { pkg ->
        val enabled = PackageRegistry.isEnabled(pkg)
        CapGroup(
            id = pkg.id,
            name = pkg.displayName,
            description = pkg.description,
            enabled = enabled,
            // ALWAYS_ON 里的包关不掉，这一条要在界面上说清楚，否则用户会去设置里找开关。
            locked = pkg.id in PackageRegistry.ALWAYS_ON,
            tools = pkg.defs.map { def ->
                CapTool(
                    name = def.tool.name,
                    note = def.note.takeIf { it.isNotBlank() } ?: def.tool.description,
                    permission = PackageRegistry.permissionOf(pkg, def)
                )
            }
        )
    }
}

/** 一个包的能力快照。 */
private data class CapGroup(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val locked: Boolean,
    val tools: List<CapTool>
)

/** 一个工具的能力快照。 */
private data class CapTool(
    val name: String,
    val note: String,
    val permission: ToolPermission
)