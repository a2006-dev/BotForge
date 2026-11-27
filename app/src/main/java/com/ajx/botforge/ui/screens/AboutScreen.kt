package com.ajx.botforge.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ajx.botforge.BuildConfig
import com.ajx.botforge.R
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.LineHeight
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import com.ajx.botforge.ui.text.Txt

/**
 * 关于页。
 *
 * ## 为什么是这些内容
 *
 * 参考了主流开源 Android 应用（Termux / Aegis / Material Files / KDE Connect）
 * 的关于页写法，共同点：
 *
 *  1. 顶部是身份：图标 + 应用名 + 一句话定位 + 版本号紧跟其下。
 *     版本号必须一眼可见 —— 用户报问题时第一个要的就是它。
 *  2. 不写长段落自夸。成熟项目的关于页都很短，
 *     说清「这是什么、数据存哪、出问题找谁」就结束。
 *  3. 必须有「隐私与数据」一节。本项目处理 AppSecret、API Key、
 *     聊天内容，用户有权知道这些存在哪、会不会上传。
 *  4. 开源许可不堆一屏到关于页里，但要写明主要组件。
 *  5. 不放假链接。能点的必须真实可达，点了没反应的链接比没链接更糟。
 *
 * 分节：身份 / 简介 / 隐私与数据 / 帮助与反馈 / 第三方组件 / 运行环境 / 法律。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = botColors
    val ctx = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(title = "关于", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.xl, bottom = Spacing.huge)
        ) {
            // ---------------------------------------------------------- 身份
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppIconBadge()
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "BotForge",
                    color = c.textHigh,
                    fontSize = FontSize.display,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = Txt.ABOUT_TAGLINE,
                    color = c.textMid,
                    fontSize = FontSize.small,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.sm))
                Box(
                    modifier = Modifier
                        .background(c.bg3, RoundedCornerShape(Radius.full))
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                ) {
                    Text(
                        text = String.format(Txt.ABOUT_VERSION_FMT, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        color = c.textMid,
                        fontSize = FontSize.caption
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            Text(
                text = Txt.ABOUT_INTRO,
                color = c.textMid,
                fontSize = FontSize.small,
                lineHeight = LineHeight.relaxed
            )

            Spacer(Modifier.height(Spacing.xl))

            // ---------------------------------------------------------- 隐私与数据
            SectionHeader(Txt.ABOUT_PRIVACY_TITLE)
            Spacer(Modifier.height(Spacing.sm))
            InfoGroup {
                InfoLine(Txt.ABOUT_CRED_STORE, Txt.ABOUT_CRED_STORE_V)
                InGroupDivider()
                InfoLine(Txt.ABOUT_CHAT, Txt.ABOUT_CHAT_V)
                InGroupDivider()
                InfoLine(Txt.ABOUT_EGRESS, Txt.ABOUT_EGRESS_V)
                InGroupDivider()
                InfoLine(Txt.ABOUT_EXPORT, Txt.ABOUT_EXPORT_V)
            }
            Spacer(Modifier.height(Spacing.sm))
            // 诚实说明局限，不写"绝对安全"。
            // 这类项目最容易犯的错就是过度承诺，用户拿 root 一提就露馅。
            Text(
                text = "说明：本地存储无法抵御已 root 设备上的主动提取。" +
                    "若设备可能被他人物理接触，请先解除绑定并清空凭据。",
                color = c.textLow,
                fontSize = FontSize.caption,
                lineHeight = LineHeight.normal
            )

            Spacer(Modifier.height(Spacing.xl))

            // ---------------------------------------------------------- 帮助与反馈
            SectionHeader("帮助与反馈")
            Spacer(Modifier.height(Spacing.sm))
            InfoGroup {
                LinkRow(
                    label = "开放平台文档",
                    value = "bot.q.qq.com",
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://bot.q.qq.com/wiki/"))
                            )
                        }
                    }
                )
                InGroupDivider()
                LinkRow(
                    label = "项目仓库",
                    value = "a2006-dev/BotForge",
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/a2006-dev/BotForge")
                                )
                            )
                        }
                    }
                )
                InGroupDivider()
                InfoLine("反馈方式", Txt.ABOUT_HELP_ISSUE)
            }

            Spacer(Modifier.height(Spacing.xl))

            // ---------------------------------------------------------- 第三方组件
            SectionHeader("第三方组件")
            Spacer(Modifier.height(Spacing.sm))
            InfoGroup {
                InfoLine("Kotlin", "Apache-2.0")
                InGroupDivider()
                InfoLine("Jetpack Compose", "Apache-2.0")
                InGroupDivider()
                InfoLine("OkHttp / kotlinx", "Apache-2.0")
                InGroupDivider()
                InfoLine("Shizuku API", "Apache-2.0")
                InGroupDivider()
                InfoLine("QQ 开放平台接口", "官方开放接口")
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "以上组件均按其各自许可证使用。本项目未内置任何修改版依赖。",
                color = c.textLow,
                fontSize = FontSize.caption
            )

            Spacer(Modifier.height(Spacing.xl))

            // ---------------------------------------------------------- 运行环境
            SectionHeader("运行环境")
            Spacer(Modifier.height(Spacing.sm))
            InfoGroup {
                InfoLine("系统版本", "Android ${Build.VERSION.RELEASE}")
                InGroupDivider()
                InfoLine("设备", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                InGroupDivider()
                InfoLine("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "—", mono = true)
                InGroupDivider()
                InfoLine("包名", BuildConfig.APPLICATION_ID, mono = true)
            }

            Spacer(Modifier.height(Spacing.xl))

            // ---------------------------------------------------------- 法律
            SectionHeader("法律")
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = Txt.ABOUT_LEGAL_BODY,
                color = c.textMid,
                fontSize = FontSize.caption,
                lineHeight = LineHeight.normal
            )

            Spacer(Modifier.height(Spacing.xl))

            Text(
                text = Txt.ABOUT_FOOTER,
                color = c.textLow,
                fontSize = FontSize.micro,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---------------------------------------------------------------- 小组件

/** 分组卡片容器，与设置页视觉一致（分组卡片 + 行分隔线）。 */
@Composable
private fun InfoGroup(content: @Composable () -> Unit) {
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
private fun InGroupDivider() {
    val c = botColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md)
            .height(Sizes.hairline)
            .background(c.line)
    )
}

/** 只读信息行：左标签、右值，值允许换行（长路径不截断）。 */
@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = c.textMid,
            fontSize = FontSize.small,
            modifier = Modifier.width(88.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = value,
            color = c.textHigh,
            fontSize = if (mono) FontSize.caption else FontSize.small,
            lineHeight = LineHeight.normal,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 可跳转行：右侧带 ›，整行可点。 */
@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = c.textMid,
            fontSize = FontSize.small,
            modifier = Modifier.width(88.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = value,
            color = c.brand,
            fontSize = FontSize.small,
            modifier = Modifier.weight(1f)
        )
        Text(text = "›", color = c.textLow, fontSize = FontSize.body)
    }
}

/**
 * 应用图标。
 *
 * 这里不能给 painterResource 包 runCatching / let：它是 @Composable，
 * 一旦被塞进非 Composable 的 lambda（runCatching 的 block、let 的分支），
 * 组合器的节点插入栈就会错位，抛出
 * ComposeRuntimeError: Cannot end node insertion, there are no pending
 * operations that can be realized. —— 表现为整页闪退。
 *
 * 因此这里一律直白调用，不做运行时兜底；安全性靠资源本身保证：
 * 用 ic_launcher（各密度都是真实 PNG），避开 mipmap-anydpi-v26 下
 * ic_launcher_round 那个自适应图标 XML（painterResource 不支持，会抛
 * IllegalArgumentException: Only VectorDrawables and rasterized asset
 * types are supported）。
 */
@Composable
private fun AppIconBadge() {
    Image(
        painter = painterResource(R.mipmap.ic_launcher),
        contentDescription = null,
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(Radius.xl))
    )
}
