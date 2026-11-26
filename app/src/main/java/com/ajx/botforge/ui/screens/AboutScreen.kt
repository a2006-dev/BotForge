package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ajx.botforge.BuildConfig
import com.ajx.botforge.ui.components.InfoRow
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextMid

/** 关于页：展示版本与关键说明，不放假链接。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Bg1)) {
        PageTopBar(title = "关于", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            Text(
                text = "BotForge",
                color = TextHigh,
                fontSize = FontSize.display,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "QQ 官方机器人工作台",
                color = TextMid,
                fontSize = FontSize.body
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("版本")
            Spacer(Modifier.height(Spacing.sm))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg2, androidx.compose.foundation.shape.RoundedCornerShape(Radius.md))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                InfoRow(label = "版本名", value = BuildConfig.VERSION_NAME)
                InfoRow(label = "版本号", value = BuildConfig.VERSION_CODE.toString())
                InfoRow(label = "包名", value = BuildConfig.APPLICATION_ID, mono = true)
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("说明")
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "本项目通过 QQ 官方开放平台的机器人接口收发消息，" +
                        "不依赖第三方协议库，也不会读取你的 QQ 账号数据。\n\n" +
                        "AppSecret 与 API Key 保存在本地保险箱，" +
                        "配置文件中只留有占位符。" +
                        "但请注意：任何本地存储都无法抵御已 root 设备上的主动提取。\n\n" +
                        "事件订阅固定为 GROUP_AND_C2C_EVENT(1<<25)。" +
                        "订阅无权限的 intents 会让网关在 Identify 后立即断开，" +
                        "因此本版本不再提供手填入口。",
                color = TextMid,
                fontSize = FontSize.caption
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("开源许可")
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "界面基于 Jetpack Compose 与 Material 3 构建。\n" +
                        "Compose Compiler 1.5.8 ／ Kotlin 1.9.22 ／ 最低 Android 8.0。",
                color = TextMid,
                fontSize = FontSize.caption
            )

            Spacer(Modifier.height(Spacing.xl))
            Text(
                text = "本地运行 · 不收集任何遥测数据",
                color = Brand,
                fontSize = FontSize.caption
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}