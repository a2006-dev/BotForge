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
import androidx.compose.runtime.getValue
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
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.Bg4
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.LineStrong
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid

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
    onReloadBots: () -> Unit
) {
    var autoStart by remember { mutableStateOf(true) }
    var keepAlive by remember { mutableStateOf(true) }
    var searchEnabled by remember { mutableStateOf(true) }
    var agentEnabled by remember { mutableStateOf(false) }
    var logEnabled by remember { mutableStateOf(true) }
    var searchTrigger by remember { mutableStateOf("model") }

    LaunchedEffect(Unit) {
        autoStart = Config.autoStart
        keepAlive = Config.keepAlive
        searchEnabled = Config.searchEnabled
        agentEnabled = Config.agentEnabled
        logEnabled = Config.logEnabled
        searchTrigger = Config.searchTrigger
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
    ) {
        PageTopBar(title = "设置", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
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
                            color = TextHigh,
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (searchTrigger == "model") {
                                "模型自主决定是否搜索"
                            } else {
                                "关键词命中即搜索"
                            },
                            color = TextLow,
                            fontSize = FontSize.caption
                        )
                    }
                    SmallButton(
                        text = if (searchTrigger == "model") "模型自主" else "关键词",
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
                    title = "Shizuku 增强",
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

@Composable
private fun SettingGroup(content: @Composable () -> Unit) {
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
private fun SettingDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md)
            .height(Sizes.hairline)
            .background(Line)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
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
                color = TextHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium
            )
            Text(text = desc, color = TextLow, fontSize = FontSize.caption)
        }
        Spacer(Modifier.width(Spacing.md))
        TrackSwitch(checked = checked)
    }
}

/** 自绘开关轨道，避免系统 Switch 的默认灰。 */
@Composable
private fun TrackSwitch(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .background(
                if (checked) Brand else Bg4,
                RoundedCornerShape(Radius.full)
            )
            .border(
                Sizes.hairline,
                if (checked) Brand else LineStrong,
                RoundedCornerShape(Radius.full)
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .background(
                    if (checked) com.ajx.botforge.ui.theme.TextInverse else TextMid,
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
            Text(text = desc, color = TextLow, fontSize = FontSize.caption)
        }
        Spacer(Modifier.width(Spacing.md))
        SmallButton(text = action, onClick = onClick)
    }
}