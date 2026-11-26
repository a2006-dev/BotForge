package com.ajx.botforge.ui.components

import com.ajx.botforge.ui.text.Txt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.botColors

/**
 * 底部导航 Tab 定义。
 *
 * 顺序即视觉顺序，也是返回栈的优先级顺序（见 [tabIndexOfRoute]）。
 * 用图标 + 文字而不是纯文字：五个中文词在 360dp 宽屏上靠纯文字区分度低。
 */
enum class NavTab(
    val route: String,
    val label: String,
    val iconOn: ImageVector,
    val iconOff: ImageVector
) {
    DASHBOARD("dashboard", "首页", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    BOTS("bots", Txt.DASH_SECTION_BOTS, Icons.Filled.Bolt, Icons.Outlined.Bolt),
    CAPABILITIES("capabilities", "能力", Icons.Filled.Extension, Icons.Outlined.Extension),
    LOGS("logs", "日志", Icons.Filled.Memory, Icons.Outlined.Memory),
    SETTINGS("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings);

    companion object {
        /** 路由 → Tab 下标；非顶层路由返回 -1。 */
        fun indexOfRoute(route: String?): Int {
            if (route == null) return -1
            return entries.indexOfFirst { it.route == route }
        }
    }
}

/**
 * 自绘底部导航栏
 *
 * 不使用 M3 NavigationBar 的原因：
 *  - M3 默认高度 80dp + 指示胶囊 + 内置涟漪，与本站「克制、无多余动效」的
 *    视觉语言冲突；
 *  - 需要精确控制选中态（图标实心 + 品牌色 + 顶部短横），M3 只能靠覆盖
 *    indicatorColor 硬拗，反而更难维护。
 *
 * 无障碍：整行高度 56dp（≥ 48dp touch target），每个 tab 用 weight(1f) 均分，
 * 点击区域覆盖整个格子而不是只有图标本身。
 */
@Composable
fun BotBottomBar(
    currentRoute: String?,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = botColors
    val current = NavTab.indexOfRoute(currentRoute)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bg0)
    ) {
        // 顶部 1px 分隔：让导航栏与内容区分开，又不制造厚度
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizes.hairline)
                .background(c.line)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEachIndexed { index, tab ->
                val selected = index == current
                TabItem(
                    tab = tab,
                    selected = selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = botColors
    // 选中色用品牌色，未选中用弱化文字色：
    // 两者在亮暗两套主题中都满足 4.5:1，小字号也读得清。
    val tint = if (selected) c.brand else c.textLow
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 选中指示：图标上方 2dp 短横，替代 M3 的胶囊底。
            // 未选中时用同尺寸透明占位，避免选中时整列下移。
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(2.dp)
                    .background(
                        if (selected) c.brand else Color.Transparent,
                        RoundedCornerShape(Radius.full)
                    )
            )
            Spacer(Modifier.height(4.dp))
            Icon(
                imageVector = if (selected) tab.iconOn else tab.iconOff,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tab.label,
                color = tint,
                fontSize = FontSize.micro,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/**
 * 顶栏右侧的「更多」入口按钮，用于把二级页（人设/模型/插件/关于）挂到 Tab 上。
 *
 * 五个 Tab 已经占满底栏，人设、模型这类入口不适合再上底栏，
 * 用顶栏一个「⋯」按钮收拢，比在页面里散落一排按钮更稳。
 */
@Composable
fun TopBarMoreAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = botColors
    Box(
        modifier = modifier
            .size(Sizes.touchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(c.textHigh, CircleShape)
                )
            }
        }
    }
}

/**
 * 覆盖层菜单项（配合 [TopBarMoreAction] 弹出的 Sheet 使用）。
 */
data class MoreMenuItem(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)