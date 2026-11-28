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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ajx.botforge.data.Config
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.ThemeMode
import com.ajx.botforge.ui.theme.botColors
import com.ajx.botforge.ui.data.UiPrefs
import com.ajx.botforge.ui.text.Txt

/**
 * 设置中心。
 *
 * 只重组现有开关，不新增任何未实现的配置项（任务书第九节要求）。
 * 每个开关直接读写 Config 的 SharedPreferences 属性，键名与旧版完全一致，
 * 保证升级后用户既有配置不失效。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestOverlay: () -> Unit,
    onApplyShizuku: () -> Unit,
    onReloadBots: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenToolPackages: () -> Unit = {}
) {
    val c = botColors
    val themeMode by UiPrefs.themeMode.collectAsState()
    var autoStart by remember { mutableStateOf(true) }
    var keepAlive by remember { mutableStateOf(true) }
    var autoRestart by remember { mutableStateOf(true) }
    var searchEnabled by remember { mutableStateOf(true) }
    var agentEnabled by remember { mutableStateOf(false) }
    var toolRounds by remember { mutableIntStateOf(Config.toolMaxRounds) }
    var termMode by remember { mutableIntStateOf(Config.termMode) }
    var termChosen by remember { mutableStateOf(Config.termChosen) }
    var logEnabled by remember { mutableStateOf(true) }
    var searchTrigger by remember { mutableStateOf("model") }

    LaunchedEffect(Unit) {
        autoStart = Config.autoStart
        keepAlive = Config.keepAlive
        autoRestart = Config.autoRestart
        searchEnabled = Config.searchEnabled
        agentEnabled = Config.agentEnabled
        toolRounds = Config.toolMaxRounds
        termMode = Config.termMode
        termChosen = Config.termChosen
        logEnabled = Config.logEnabled
        searchTrigger = Config.searchTrigger
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg1)
    ) {
        PageTopBar(title = "设置", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            SectionHeader("外观")
            SettingGroup {
                ThemeRow(current = themeMode) { UiPrefs.setThemeMode(it) }
            }

            SectionHeader("运行")
            SettingGroup {
                SwitchRow(
                    title = "开机自启",
                    desc = "系统启动后自动拉起机器人服务",
                    checked = autoStart
                ) { v ->
                    autoStart = v
                    Config.autoStart = v
                }
                SettingDivider()
                SwitchRow(
                    title = "后台保活",
                    desc = "服务被系统回收后尝试重启",
                    checked = keepAlive
                ) { v ->
                    keepAlive = v
                    Config.keepAlive = v
                }
                SettingDivider()
                SwitchRow(
                    title = "自动拉起服务",
                    desc = if (autoRestart)
                        "服务停止后会被自动重新启动（含划掉任务、被系统回收）"
                    else
                        "已关闭：服务停止后保持停止，只有手动点「启动服务」才会运行",
                    checked = autoRestart
                ) { v ->
                    autoRestart = v
                    Config.autoRestart = v
                    if (v) {
                        // 重新开启时清掉「主动停止」标记，否则开关看似打开却仍不拉活
                        Config.userStopped = false
                    }
                }
                SettingDivider()
                SwitchRow(
                    title = "写入运行日志",
                    desc = "关闭后不再记录新的日志条目",
                    checked = logEnabled
                ) { v ->
                    logEnabled = v
                    Config.logEnabled = v
                }
            }

            SectionHeader("能力")
            SettingGroup {
                SwitchRow(
                    title = "联网搜索",
                    desc = "允许模型在回答前检索网络内容",
                    checked = searchEnabled
                ) { v ->
                    searchEnabled = v
                    Config.searchEnabled = v
                }
                SettingDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "搜索触发方式",
                            color = c.textHigh,
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (searchTrigger == "model") {
                                "模型自主决定是否搜索"
                            } else {
                                "关键词命中即搜索"
                            },
                            color = c.textLow,
                            fontSize = FontSize.caption
                        )
                    }
                    SmallButton(
                        text = if (searchTrigger == "model") Txt.SET_SEARCH_MODEL else Txt.SET_SEARCH_KEYWORD,
                        onClick = {
                            val next = if (searchTrigger == "model") "keyword" else "model"
                            searchTrigger = next
                            Config.searchTrigger = next
                        }
                    )
                }
                SettingDivider()
                SwitchRow(
                    title = "工具调用",
                    desc = "允许模型自主调用内置工具执行任务",
                    checked = agentEnabled
                ) { v ->
                    agentEnabled = v
                    Config.agentEnabled = v
                }
                if (agentEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "工具轮数上限",
                                color = c.textHigh,
                                fontSize = FontSize.body,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "单次对话最多连续调用几轮工具，默认 4。" +
                                    "调小省 token，调大能处理更复杂的任务",
                                color = c.textLow,
                                fontSize = FontSize.caption
                            )
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        SmallButton(text = "−") {
                            val next = (toolRounds - 1).coerceAtLeast(Config.MIN_TOOL_ROUNDS)
                            toolRounds = next
                            Config.toolMaxRounds = next
                        }
                        Text(
                            text = toolRounds.toString(),
                            color = c.textHigh,
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = Spacing.sm)
                        )
                        SmallButton(text = "+") {
                            val next = (toolRounds + 1).coerceAtMost(Config.MAX_TOOL_ROUNDS)
                            toolRounds = next
                            Config.toolMaxRounds = next
                        }
                    }
                    SettingDivider()
                    // 工具包权限入口。只放在工具调用开启时显示：
                    // agent 关掉的话，模型根本拿不到工具，配权限没有意义。
                    val pkgCount = com.ajx.botforge.ai.tool.PackageRegistry.all().size
                    val toolCount = com.ajx.botforge.ai.tool.PackageRegistry.activeTools().size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenToolPackages)
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "工具包与权限",
                                color = c.textHigh,
                                fontSize = FontSize.body,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (pkgCount == 0) "正在加载工具包…"
                                else "$pkgCount 个包 · 模型当前可见 $toolCount 个工具",
                                color = c.textLow,
                                fontSize = FontSize.caption
                            )
                        }
                        SmallButton(text = "管理", onClick = onOpenToolPackages)
                    }
                }
                SettingDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "终端环境",
                            color = c.textHigh,
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when {
                                termMode == 1 -> "内置 Alpine · 模型可执行 python3 / apk / git"
                                termChosen -> "已跳过 · 模型只能执行 Android 自带命令"
                                else -> "未配置 · 建议选择一种方式"
                            },
                            color = if (termMode == 1) c.textLow else c.warning,
                            fontSize = FontSize.caption
                        )
                    }
                    SmallButton(
                        text = if (termMode == 1) "重装" else "配置",
                        onClick = {
                            // 进入终端页会弹出配置选择
                            termMode = 0
                            termChosen = false
                            Config.termMode = 0
                            Config.termChosen = false
                            onOpenTerminal()
                        }
                    )
                }
            }

            SectionHeader("保活与权限")
            SettingGroup {
                ActionRow(
                    title = "电池白名单",
                    desc = "避免系统在息屏后杀掉服务",
                    action = "去设置",
                    onClick = onRequestBattery
                )
                SettingDivider()
                ActionRow(
                    title = "悬浮窗权限",
                    desc = "用于展示运行状态浮层",
                    action = "去授权",
                    onClick = onRequestOverlay
                )
                SettingDivider()
                ActionRow(
                    title = Txt.SET_SHIZUKU,
                    desc = "一键放开后台限制",
                    action = "执行",
                    onClick = onApplyShizuku
                )
            }

            SectionHeader("维护")
            SettingGroup {
                ActionRow(
                    title = "重载机器人",
                    desc = "重新读取配置并重建网关连接",
                    action = "重载",
                    onClick = onReloadBots
                )
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/**
 * 主题选择行。
 *
 * 三态而不是开关：用户需要「跟随系统」这一档，Boolean 表达不了。
 * 选中项用 primary 样式强调，未选中保持次级底色。
 */
@Composable
private fun ThemeRow(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        Text(
            text = "主题",
            color = c.textHigh,
            fontSize = FontSize.body,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "跟随系统会随系统明暗自动切换",
            color = c.textLow,
            fontSize = FontSize.caption
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ThemeMode.entries.forEach { m ->
                SmallButton(
                    text = m.label,
                    primary = m == current,
                    onClick = { onPick(m) }
                )
            }
        }
    }
}

@Composable
private fun SettingGroup(content: @Composable () -> Unit) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
    ) {
        content()
    }
}

@Composable
private fun SettingDivider() {
    val c = botColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md)
            .height(Sizes.hairline)
            .background(c.line)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = c.textHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium
            )
            Text(text = desc, color = c.textLow, fontSize = FontSize.caption)
        }
        Spacer(Modifier.width(Spacing.md))
        TrackSwitch(checked = checked)
    }
}

/** 自绘开关轨道，避免系统 Switch 的默认灰。 */
@Composable
private fun TrackSwitch(checked: Boolean) {
    val c = botColors
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .background(
                if (checked) c.brand else c.bg4,
                RoundedCornerShape(Radius.full)
            )
            .border(
                Sizes.hairline,
                if (checked) c.brand else c.lineStrong,
                RoundedCornerShape(Radius.full)
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .background(
                    if (checked) c.textInverse else c.textMid,
                    CircleShape
                )
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    desc: String,
    action: String,
    onClick: () -> Unit
) {
    val c = botColors
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
                color = c.textHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium
            )
            Text(text = desc, color = c.textLow, fontSize = FontSize.caption)
        }
        Spacer(Modifier.width(Spacing.md))
        SmallButton(text = action, onClick = onClick)
    }
}